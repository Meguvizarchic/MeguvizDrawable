# 性能测量指南

本文件记录 MeguvizDrawable 的性能测量协议与已完成的测量。原则：只报告可复现的测量，不把主机实验
推断成设备收益；分配、准备成本、内存和帧耗时分开陈述；每个候选在进入生产前先通过正确性合同。

外部检索通道本轮不可用（Exa 返回 401）。下文引用均来自对上游源码与官方文档的直接阅读，不声称来自
外部研究服务。

## 测量什么、怎么测

| 项 | 方法 |
|---|---|
| 分配 | 宿主 JVM，`com.sun.management.ThreadMXBean.getThreadAllocatedBytes(线程 id)`（`java.lang.management.ThreadMXBean` 没有该方法）包裹测量循环，报告每次调用的字节数 |
| 视图 | 每个构建跑两种 JVM 视图：默认（逃逸分析开启）与 `-XX:-DoEscapeAnalysis`（原始分配视图）。两者都记录 |
| 夹具 | 固定夹具：预建的键、预建的生产者与订阅者，测量循环内夹具自身零分配；每轮 1,000,000 次调用，4 轮热身 + 7 轮测量，每轮报告中位数 |
| 进程 | 每个视图独立 JVM 进程。分配计数受 JIT/逃逸分析影响，不做"绝对不波动"的断言；本轮观测：改动前每视图 1 个 fork、改动后默认视图 3 个 fork 与 raw 视图 2 个 fork，数值全部一致 |
| 正确性前提 | 同一构建先过 `gradlew --offline --no-daemon check`（宿主合同）与 Android profile 的编译/lint |

不测、不宣称：帧耗时、FPS、Android/ART 收益、热节流、GC 暂停。设备侧行为以设备合同为准
（见 [VERIFICATION.md](VERIFICATION.md) 的 instrumentation 协议），未跑设备就不写设备结论。

## 首轮测量：FlightCache.request 惰性分配（2026-11-04）

改动：命中与拒绝路径不再预先创建订阅者记录与 `unqueue` 列表；加入路径仍必须创建订阅者记录
（它登记到飞行上、会逃逸），但不再创建 `unqueue`；`unqueue` 仅在真正启动一个飞行（或为它腾位而
丢弃一个被放弃的飞行）时创建。`dropOneAbandoned` 随之改为返回被丢弃的飞行，由调用方按
`task != null` 决定是否入队移除——原实现区分“丢弃了飞行”与“该飞行 task 尚未赋值”的语义保持不变。
公开 API、回调时机、owner 线程归属、统计计数、取消与 `RejectedExecutionException` 回滚均不变
（`CoreContractTest` 20,405 项通过，含本轮新增的回归与异步准备示例合同）。

结果（每调用分配字节数，7 轮中位数；确定性数值。由 `benchmark/FlightCacheAllocationProbe.java`
对固定基线提交 `2690458` 还原的改动前构建与候选构建分别测得，同一探针二进制、同一夹具）：

| 路径 | 改动前（默认 EA） | 改动后（默认 EA） | 改动前（`-XX:-DoEscapeAnalysis`） | 改动后（`-XX:-DoEscapeAnalysis`） |
|---|---|---|---|---|
| 命中 | 32.000 | 32.000 | 104.000 | 32.000 |
| 加入 | 104.000 | 56.000 | 104.000 | 56.000 |
| 拒绝 | 104.000 | 32.000 | 136.000 | 64.000 |
| `peek`（参照，未改） | 0.000 | 0.000 | 0.000 | 0.000 |

如实解读：

- 默认视图下命中路径前后都是 32.000 B：HotSpot C2 在热循环里已把不逃逸的 `Sub` 与 `ArrayList`
  标量替换，字节计数里只剩逃逸的 `Ticket`。也就是说，这条路径在主机热循环上的分配收益为 0，
  收益存在于原始分配视图（104.000 → 32.000 B，即每次少分配 `Sub`、`ArrayList` 及其 backing array）
  和未充分优化的代码路径（冷启动、解释执行）。
- 加入路径两种视图都是 104.000 → 56.000 B：该路径的 `Sub` 必须保留（它登记到飞行上、会逃逸），
  移除的是 `ArrayList` 与 backing array；默认视图下 C2 未能消除这个列表，因此这里是即使在热循环中
  也真实存在的分配下降。
- 拒绝路径默认视图 104.000 → 32.000 B，关闭 EA 视图 136.000 → 64.000 B；两者之差是
  `dropOneAbandoned` 迭代器的分配，改动前后相同。
- 以上都是宿主 JVM 分配计数。没有测量帧耗时，没有在 Android/ART 上运行，不声称任何设备/ART 收益。

配套的 host 合同（`FlightCacheContractTest`，随 `gradlew check` 执行）钉住本次改动的可观测面：

- `rejectionPathLeavesNothingToCancel`：拒绝路径不登记订阅者——被拒的 `Ticket` 无可取消
  （`cancel()` 为 false）、订阅者永不被调用、`cancelledSubscribers` 不增加。
- `droppedFlightWithoutAssignedTaskStillMakesRoom`：`dropOneAbandoned` 改返回被丢弃飞行后，
  “丢弃了飞行但 task 尚未赋值”这一竞态窗口（纳秒级、公开 API 不可达，测试用反射直接构造）
  仍然为请求腾位而不是拒绝；task 为 null 时不入队移除。改动前该窗口由
  `if (flight.task != null) unqueue.add(...); return true;` 覆盖，行为等价。

## 复现

宿主合同与 Android profile（JDK 17、Gradle Wrapper 9.8.2、仓库根目录）：

```text
gradlew --offline --no-daemon check
gradlew --offline --no-daemon -Pmeguviz.profile=android check :android:assembleDebugAndroidTest
```

宿主合同现包含 `AsyncPreparationContractTest`：只用既有 API（`BindingScope.begin/commit` +
`FlightCache.request` + `Subscriber.isCurrent`）演示通用异步准备——READY 命中立即安装、每 slot 一个
有界提交、失败与拒绝可观测、latest-wins、仅 owner 安装、取消/关闭不销毁共享缓存值。完整可运行版本
即该测试文件；短示例与所有权规则见 [FLIGHT_CACHE.md](FLIGHT_CACHE.md)。

分配夹具已固化为仓库内的探针（纯 Java，不执行 shell、不添依赖，CSV 输出，与
`benchmark/CorePilot.java` 同一模式）：

- `benchmark/FlightCacheAllocationProbe.java`：固定夹具（八个预建键、一个排队飞行、占满的飞行
  预算），每轮 1,000,000 次调用、4 轮热身 + 7 轮测量，报告中位数；命中/加入/拒绝/`peek` 四个
  场景，并用生产者调用计数防夹具漂移。
- `benchmark/run-flightcache-probe.ps1`：编译当前 core 源码与探针到本地构建目录，跑 N 个独立
  JVM 进程，原始输出与源码 SHA-256、协议写入 `docs/engineering/evidence/runs/`（本地忽略目录）；
  `-View raw` 追加 `-XX:-DoEscapeAnalysis`（探针通过 `-Dprobe.rawAllocation` 记录同一事实）。

```text
powershell -ExecutionPolicy Bypass -File benchmark/run-flightcache-probe.ps1 -Forks 3 -View default
powershell -ExecutionPolicy Bypass -File benchmark/run-flightcache-probe.ps1 -Forks 3 -View raw
```

对固定基线提交 `2690458` 还原的改动前构建用同一探针二进制补测，保证前后同夹具、同工具；不要把提交后的 `HEAD` 当作改动前基线。

## 同期结构性改动（无宿主运行视图）

`RecipeDrawable`（android 模块）的两项清理没有宿主运行测量：`android.jar` 是抛桩存根，不能承载
框架类的真实执行，因此不做桌面 JVM 基准，也不据此推断设备数字。

1. `onBoundsChange` 复用字段级 scratch 数组（每次 ROUND_RECT 边界变化少一个 `float[8]`）。
   `CornerRadii.normalize` 的输入契约是"复制输入、不保留、不修改"，drawable 实例又被约束在 owner
   线程，复用不改变语义。`CornerRadii.normalize` 内部的 `clone()` 是该公开方法的既定契约，不动。
2. `setTintList`/`setTintMode` 增加身份比较守卫：相同实例重复设置不再触发 `updateTint()` 与
   `invalidateSelf()`，与既有 `setAlpha`/`setColorFilter` 的守卫一致。就本类事实而言：
   `RecipeState` 只保存不可变数据（recipe、density、alpha、tintMode、configurations），
   `getConstantState()` 在存在 tint 或显式滤镜时保守返回 `null`，`newDrawable()` 重新跑
   `updateTint()`，`mutate()` 是空操作，有状态 tint 的重新解析走 `onStateChange`——
   共享路径上不会出现“另一个实例用同一实例修补 tint”的情形，守卫只消除无效重绘，视觉语义不变；
   `setTint(int)` 每次传入新建的 `ColorStateList`，行为与改动前相同。这是对本类实现的陈述，
   不推广为对平台 `Drawable` 的一般结论。设备合同（`ContractInstrumentation.filtersAndAlpha`）
   已补“同实例重复设置 `invalidations == 0` 且像素不变”的断言，本轮只编译未运行（无设备授权）。

这两项的验证依赖 Android profile 编译/lint 与设备合同（`contracts`/`binding` 模式）；设备合同本轮
未运行（有设备在线但未获安装授权，见 [VERIFICATION.md](VERIFICATION.md) 对应小节），在跑通前不声称
像素结论。

## 参考（直接阅读，非外部研究服务）

- Go `golang.org/x/sync/singleflight`（https://pkg.go.dev/golang.org/x/sync/singleflight）：同键并发
  调用共享一次执行的“单飞”语义，是 `FlightCache` 同键合并的设计参照；本轮改动不触碰该语义
  （8 线程 × 20 轮竞争合同仍通过）。
- Android 官方文档 `android.graphics.drawable.Drawable`
  （https://developer.android.com/reference/android/graphics/drawable/Drawable）：
  `invalidateSelf`、`mutate`、`ConstantState`、`setTintList`/`setTintMode` 的公开契约，是
  `RecipeDrawable` tint 守卫与 ConstantState 重建路径的阅读依据；上文对该契约的陈述仅限本类
  `RecipeState` 的实现事实，不做全平台概括。

## 模块独立性

- 单用 core：`gradlew --offline --no-daemon check`（host 档只包含 `:core`），覆盖全部宿主合同，
  包括 `FlightCacheContractTest` 与 `AsyncPreparationContractTest`。core 不依赖 Android，也不依赖
  任何兄弟库。
- 单用 android：`gradlew --offline --no-daemon -Pmeguviz.profile=android check`（需本机 SDK，
  经环境变量或 local.properties 定位），core + android 编译、lint 与单测。
- 显式选桥：`-Pmeguviz.profile=bridges` 才包含 compose/meguviz-android/meguviz-ui/lumen；四个桥
  只依赖已发布基础库与各自上游，基础库不反向依赖任何桥（见 [BRIDGES.md](BRIDGES.md)）。
