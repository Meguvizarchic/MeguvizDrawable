# 性能测量指南

本文件记录 MeguvizDrawable 的性能测量协议与已完成的宿主 JVM 测量。原则：仅报告可复现测量，分配、准备成本、内存与帧耗时分开陈述，且每个优化候选在合入前必须先通过对应正确性合同。引用上游文档（Go `singleflight`、Android `Drawable` 官方参考）均来自直接阅读源码与官方文档。

## 测量协议

| 项 | 方法 |
| --- | --- |
| 分配计数 | 宿主 JVM 使用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(tid)` 包裹测量循环，报告单次调用分配字节数 |
| 运行视图 | 每次对比同时记录默认视图（开启逃逸分析 EA）与 `-XX:-DoEscapeAnalysis`（原始分配视图） |
| 固定夹具 | 预建键、生产者与订阅者，循环内夹具零额外分配；每轮 1,000,000 次调用，4 轮热身 + 7 轮测量取中位数 |
| 进程隔离 | 每个视图在独立 JVM 进程中运行 |
| 正确性前提 | 同源码先通过 `./gradlew --offline --no-daemon check` 与 `-Pmeguviz.profile=android` 编译及 Lint |

## 首轮测量：`FlightCache.request` 惰性分配（2026-11-04）

改动：`request` 命中与拒绝路径不再提前分配订阅者记录与 `unqueue` 列表；加入在途飞行时仍创建订阅者记录（需登记在飞行上），但不再预建 `unqueue`；仅在启动新飞行或为腾位淘汰已放弃飞行时按需创建 `unqueue`。`dropOneAbandoned` 改为返回被丢弃飞行，保留“丢弃飞行”与“`task` 尚未赋值”的语义区分，同时把执行器失败回滚扩展到非 `RejectedExecutionException` 的 `RuntimeException` / `Error`。

结果（每调用分配字节数，7 轮中位数；对比基线 `2690458` 还原构建与候选构建，探针 `benchmark/FlightCacheAllocationProbe.java`）：

| 路径 | 改前（默认 EA） | 改后（默认 EA） | 改前（`-XX:-DoEscapeAnalysis`） | 改后（`-XX:-DoEscapeAnalysis`） |
| --- | --- | --- | --- | --- |
| 命中 | `32.000` | `32.000` | `104.000` | `32.000` |
| 加入 | `104.000` | `56.000` | `104.000` | `56.000` |
| 拒绝 | `104.000` | `32.000` | `136.000` | `64.000` |
| `peek`（参照，未改） | `0.000` | `0.000` | `0.000` | `0.000` |

解读：
- 默认 EA 下命中路径前后均为 `32.000 B`（HotSpot C2 已标量替换未逃逸的 `Sub` 与 `ArrayList`，仅剩逃逸出的 `Ticket`）；收益体现在原始分配视图（`104.000 → 32.000 B`）及冷启动/解释执行路径。
- 加入路径因 `Sub` 必须登记到飞行上，两种视图均从 `104.000` 降至 `56.000 B`（消除了多余 `ArrayList` 及其底层数组）。
- 拒绝路径默认视图 `104.000 → 32.000 B`、原始分配视图 `136.000 → 64.000 B`。
- `FlightCacheContractTest`（含 `rejectionPathLeavesNothingToCancel` 与 `droppedFlightWithoutAssignedTaskStillMakesRoom`）与 `AsyncPreparationContractTest` 锁定上述可观测行为。

## 复现命令

```text
gradlew --offline --no-daemon check
gradlew --offline --no-daemon -Pmeguviz.profile=android check :android:assembleDebugAndroidTest
# 手动扩展压力语料（不纳入默认 check）：
gradlew --offline --no-daemon :core:contractStress

# 宿主 JVM 分配探针（输出写入本地忽略目录 docs/engineering/evidence/runs/）：
powershell -ExecutionPolicy Bypass -File benchmark/run-flightcache-probe.ps1 -Forks 3 -View default
powershell -ExecutionPolicy Bypass -File benchmark/run-flightcache-probe.ps1 -Forks 3 -View raw
```

## `RecipeDrawable`、`DrawableState` 与惰性状态表结构优化（2026-11-04 至 2026-11-05）

以下改动均在保持既有公开语义不变的前提下减少中间对象分配：
1. `RecipeDrawable.onBoundsChange` 复用实例字段 `resolved` scratch 数组，每次 `ROUND_RECT` 尺寸变化省去一个 `float[8]`（instance 限于 owner 线程且 `CornerRadii.normalize` 自行复制输入）。
2. `setTintList` / `setTintMode` 增加引用同一性守卫，重复设置同一实例不再触发多余 `updateTint()` 与 `invalidateSelf()`。
3. `core/model` 下 8 个不可变模型、`BindingEnvironment` 与 `DrawableState` 在 `equals` 开头增加 `this == other` 短路；`DrawableState.stateSet` 由 `IntArray(7)` + `copyOf` 两次分配改为先计数再一次精确分配 `IntArray(count)`。
4. `RecipeDrawable` 增加 owner 线程惰性 `PreparedMatcher` 字段（详见 [STATE_MATCHER_PREPARED.md](STATE_MATCHER_PREPARED.md)），首次 `STATEFUL_SOLID` 外观更新时向 `StateMatcherCache.shared()` 查询/构建一次，避免为永不改状态的实例支付建表成本，也不在状态查询热路径引入共享 `HashMap`。

## `MeguvizBinding` 投影缓存（2026-11-07）

仅针对 `meguviz-android` 桥模块：构造时捕获一次不可变的 `EpochDomain[2]`（`resources` / `theme`），按 `EpochDomain.matches` 增量刷新 `Stamp`，在 `(resourceRevision, registryRevision)` 未变时直接复用同一不可变 `BindingEnvironment` 实例。

结果（`benchmark/BindingPostAllocationProbe.java`，结合 `meguviz-android-runtime:1.2.0` jar，宿主 JVM，3 fork 中位数）：

| 路径 | 改前（默认 EA） | 改后（默认 EA） | 改前（`-XX:-DoEscapeAnalysis`） | 改后（`-XX:-DoEscapeAnalysis`） |
| --- | --- | --- | --- | --- |
| `project()` | `32` | `0` | `104` | `0` |
| `begin()` | `352` | `328` | `456` | `328` |
| `begin+post+drain+frame()` | `621.2` | `594.8` | `944` | `712` |

`MeguvizBindingTest` 16/16 通过。（注：2026-11-07 实验时 `meguviz-android-runtime:1.2.0` 尚在本地缓存调试，现已正式发布至 Maven Central。）

## 平台渲染与模糊边界红线（调研固化，2026-11-07）

- `RecipeDrawable` 的 `ConstantState`/`mutate`、Paint/Path 复用与惰性 `PreparedMatcher` 符合平台 copy-on-write 原则。
- 当前无跨进程位图路径；后续若新增跨进程传输，应走 `SharedMemory` / `HardwareBuffer` 句柄避免 CPU 读回。
- 非 uniform 圆角依赖 `drawPath` 光栅化；`RenderEffect`（API 31+）/ `RuntimeShader`（API 33+）与复杂模糊效果优先接入或转交 Lumen，基础库保持纯 Java 且不引入 `BlurMaskFilter`、RenderScript 或 AndroidX。

## 联合改进第一轮：模型哈希缓存、构造分配与命中路径锁优化（2026-11-08）

1. **模型 `hashCode` 缓存与私有默认 metrics**：`DrawingRecipe`、`FillSpec`、`ShapeSpec`、`StateSpec`、`StateColorList` 惰性缓存 `hashCode()`（`String` 同款良性竞争），`equals` 先比已缓存非零哈希；`FillSpec`、`ShapeSpec` 与 `Length` 改用枚举 `ordinal()` 使哈希值跨进程一致；`DrawingRecipe.of` 使用私有默认 `LayoutMetrics` 常量（公开 `LayoutMetrics.unspecified()` 仍每次返回新实例以维持既有身份契约）。
   - 宿主 JVM（OpenJDK `21.0.12.1`，`benchmark/ModelProbe.java`，对比基线 `b087b3f`，5 进程交错，原始数据见 `docs/engineering/evidence/runs/2026-11-08-model-probe/model-probe.csv`）：
     - 重复 `hashCode()`：`26.8–35.2 ns`（中位 `29.3`，p95 `40.9`）降至 `0.049 ns`（循环内字段读）；
     - `HashMap.get`（相等但非同一键）：`96.7–117.1 ns`（中位 `106.3`，p95 `145.0`）降至 `59.5–102.6 ns`（中位 `85.5`，p95 `133.4`，剩余耗时在深度 `equals`）；
     - `FillSpec.solid(c)` 分配：`80 B → 56 B`；`DrawingRecipe.of(s, f)`：`32 B`（手写四参构造器配合 `LayoutMetrics.unspecified()` 为 `96 B`）。
2. **`FlightCache` 锁内计数改为普通 `long[]`**：将仅在 `synchronized (lock)` 内更新的 10 个计数器从 `AtomicLong` 改为锁保护 `long[]`，仅锁外更新的 `delivered`、`stale` 与 `listenerFailures` 保持为独立 `AtomicLong`。
   - 宿主 JVM（`benchmark/FlightCacheHitLatencyProbe.java`，对比基线 `c006a11`，5 进程交错，原始数据见 `docs/engineering/evidence/runs/2026-11-08-flightcache-hit/fc-hit-probe.csv`）：
     - 单线程 `peek` 命中：`6.64 ns`（`5.20–9.48`，p95 `13.68`）降至 `5.29 ns`（`4.96–6.20`，p95 `8.43`）；
     - 单线程 `request` 命中：`10.06 ns`（`7.89–20.08`，p95 `20.20`）降至 `9.65 ns`（`7.65–10.83`，p95 `12.11`）；
     - 高竞争模式下 `peek`（`247 → 206 ns`）与 `request`（`180 → 141 ns`）因同时发生大量淘汰未命中、离散度大，仅作方向参考。

## 模块独立性

- 仅使用 `:core`（默认 `host` profile）时零 Android 与兄弟库依赖；
- 仅使用 `:android`（`-Pmeguviz.profile=android`）时零 Kotlin 与 Compose 依赖；
- 四个可选桥接模块（`-Pmeguviz.profile=bridges`）仅单向依赖基础库与各自对应上游，详细边界见 [BRIDGES.md](BRIDGES.md)。

## 适用范围与验证说明

- 本文记录的所有微基准、分配字节数与延迟中位数均采集于桌面宿主 HotSpot JVM，用于对比代码局部改动在纯 JVM 路径上的差异。
- 上述数据不代表 Android ART 对象布局、GC 停顿、设备帧耗时（FPS）、热节流或真实业务页面收益；Android 与硬件 Canvas 表现须以对应设备 instrumentation 测试及业务页面基准为准。
