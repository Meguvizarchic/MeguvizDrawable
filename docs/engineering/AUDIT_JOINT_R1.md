# 联合改进第一轮审计（2026-11-08）

范围：分支 `perf/joint-r1`（起点 `origin/main` `b087b3f`）上的 core、android、compose、meguviz-ui、lumen、meguviz-android
源码与 `docs/`。方法：逐文件阅读、`javac` 直接编译运行宿主合同、宿主 JVM 探针、对可疑点写最小复现程序。
没有 codegraph/ast-grep 类工具；没有运行任何设备或模拟器测试（本轮没有占用 emulator 池），
下文凡涉及设备行为的结论都是**未经设备验证**的源码推断。

## 基线 gate

| 命令（JDK 21.0.12.1，Gradle Wrapper 9.8.2，`--offline --no-daemon --max-workers=2`） | 结果 |
|---|---|
| 新克隆执行 `./gradlew …` | 失败，退出 126：`gradlew` 在 git 中是 100644（D-09，已修 `d6e2335`） |
| `bash ./gradlew -Pmeguviz.profile=android check`（`git archive b087b3f` 的干净副本） | 通过；`CoreContractTest` 默认 148 项，Android 编译与 lint 通过 |
| `-Pmeguviz.profile=bridges` | 未运行：`meguviz-android-runtime:1.2.0` 与 `meguviz-ui-core:1.2.0` 不在 Central/本地缓存（`c44099d` 已记录同一限制）；当时本地还没有兄弟库的候选制品 |
| `:android:publishCandidatePublicationToStagingRepository`（离线） | 失败：android 的 javadoc 生成需要 `org.jetbrains.dokka:dokka-core:2.2.0`，离线缓存中没有（D-10）。core 的 staging 发布正常 |

文档写的是 JDK 17；本机 `JAVA_HOME` 是 21.0.12.1，core 以 `--release 8` 编译，未发现差异，但 `dump_abi.py` 的
`javap` 文本可能随 JDK 变化（`docs/abi/README.md` 已提醒）。

## 发现清单

严重度：高 = 错误结果或资源安全；中 = 契约/性能缺陷可被正常用法触发；低 = 局部成本或可维护性；信息 = 文档/流程。

| ID | 严重度 | 位置 | 问题与证据 | 状态 |
|---|---|---|---|---|
| D-01 | 中 | `core/.../core/StateMatcherCache.java:136`（修复前） | `db56243` 把 `seen[distinct++] = id;` 并进上一行的行尾注释，`distinctIds` 恒返回 0，建表前的两条提前拒绝成了死代码：被拒绝的表每次 `acquire` 都先付完整建表成本。准入结果仍正确（建表后检查），所以原合同没发现。新合同直接钉住计数，修复前失败 | 已修 `740a7cb` |
| D-02 | 中 | `core/.../cache/FlightCache.java` `publish`（`run`/`computeNow` 调用） | 单个值的权重大于 `maxWeight` 时，先入缓存再在同一次 `publish` 里以 `CAPACITY` 淘汰，**淘汰监听在交付之前被调用**；`computeNow` 返回的值同样已被报告淘汰。复现输出：`[evicted toolarge CAPACITY, onReady toolarge, evicted alsolarge CAPACITY, peek=null computeNow=alsolarge peekj=null]`。按文档“用 EvictionListener 释放所有者确认空闲的资源”，监听方若按“不在屏幕上”回收 Bitmap，订阅者随后拿到的是已回收的值 | 原为未修并上交主控决定；**r3 已修**：超重值只交付不缓存、不发 CAPACITY、不加 version/produced，新增 `oversizeValueIsDeliveredButNeverCached` 合同 |
| D-03 | 低 | `FlightCache.run` → `publish` | `computeNow` 在同键飞行 RUNNING 时发布，飞行完成后再次 `publish` 以 `REPLACED` 淘汰 `computeNow` 已返回、可能已上屏的值，监听方同样可能误释放。`computeNow` 的 Javadoc 只说“可能再生产一次”，未提监听 | 原为未修并上交主控决定；**r3 已修**：`publish()` 在已有值时返回该引用，飞行交付同一值，不 REPLACED、不二次加 version，`runningFlightJoinsTheCachedValue` 合同钉住。被抢占产物的所有权边界见 FlightCache.publish 的 KDoc（新增 SUPERSEDED 通知需扩 ABI，暂缓） |
| D-04 | 低 | `FlightCache` 计数器 | 13 个相邻 `AtomicLong`，10 个只在锁内写（原子操作多余），3 个锁外写（研究笔记 R-B3） | 已改 `689da2c`，宿主单线程命中路径 `peek` 中位数 6.64 → 5.29 ns |
| D-05 | 中（行为） | `android/.../RecipeDrawable.java` `getOutline` | 恒为空轮廓且 alpha 0：圆角/椭圆背景配 elevation 没有阴影，`clipToOutline` 无效；平台 `GradientDrawable` 会给出圆角轮廓。是有意保守（注释：alpha 与滤镜可改变覆盖），但用户迁移时会遇到 | 未改：改变视觉默认值，提交主控决定（候选：显式可选的 outline 模式，默认关闭） |
| D-06 | 低 | `RecipeDrawable.onBoundsChange`、`getConstantState`、`onStateChange` | 非绘制路径的分配：`shape.radii()` 每次克隆 `Length[8]`、`fill.colors()/stops()` 克隆加每次 `new LinearGradient`、每次 `getConstantState()` 新建 `RecipeState`、有状态 tint 每次颜色变化新建 `PorterDuffColorFilter`。`draw()` 本身零分配（已核对） | 候选，未测；`setBounds` 只在尺寸变化时触发，收益依赖动画尺寸场景，需要设备分配探针后再决定 |
| D-07 | 低 | `RecipeDrawable.getOpacity` | 恒为 `TRANSLUCENT`，不透明矩形纯色背景也不能让 View 走不透明优化 | 候选，改变返回值属默认行为变化，未做 |
| D-08 | 信息 | README、`STATUS.md`、组织主页 | ① README 写 “no artifact has been published”、STATUS 写“尚无发布制品”，但 core/android 1.0.0 已在 Central（本地镜像 SHA-256 `6115921e…`、`e9a74e01…`，与 RELEASE.md 一致）；② 组织主页把 MeguvizDrawable 写成 “`1.0.0` · Central” 并列出 `FlightCache`，而 `FlightCache` 只在未发布的 1.2.0；③ 仓库没有任何 GitHub release 或 tag；④ 三个桥的 1.0.0 上传包曾构建并验签（VERIFICATION.md），但镜像中没有任何桥制品，即未上 Central；⑤ `docs/abi/1.1.0/` 描述的是现为 1.2.0 的未发布 API | 记录；README/STATUS 的修订属于对外表述，留给主控（见公告板） |
| D-09 | 低 | `gradlew` | git 模式 100644，Unix 新克隆无法 `./gradlew` | 已修 `d6e2335` |
| D-10 | 低 | android 发布 | 离线时 android 的 javadoc jar 依赖未缓存的 Dokka 2.2.0，staging 发布失败；可复现构建需要网络 | 记录 |
| D-11 | 信息 | `Length.hashCode` | 用枚举身份 hash，数值跨进程不可复现（`FillSpec`/`ShapeSpec` 已在 `c006a11` 改为 ordinal） | 候选，极小 |
| D-12 | 信息 | 默认合同预算 | `CoreContractTest` 默认上限 150，基线已用 148；本轮通过合并同场景断言腾出空间（不删断言）。之后每个特性都要先腾预算 | 提交主控/用户决定是否调整预算或拆分 |
| D-13 | 低 | `StateSpec.equals` | 零终止符之后的值也参与相等（有文档），行为等价的规则不共享缓存，只影响命中率 | 记录 |

## 已核对、未发现问题

- 模型不可变性：所有数组在构造与访问时防御复制；`-0f` 归一；NaN/无穷拒绝；`equals`/`hashCode` 一致（`floatToIntBits` 两侧一致）。
- `FlightCache` 并发：取消/关闭/执行器失败/投递失败/监听异常路径与已有合同一致；生产者、订阅者、监听、weigher 都在锁外调用，可重入；`close` 与 `request` 赋值 task 的竞态已有处理。未发现新的竞态，D-02/D-03 是契约层面的问题。
- `RecipeDrawable.draw` 零分配；Paint/Path/RectF 每实例复用；`mutate()` 返回自身与“实例不共享”契约一致；`setState` 同值短路。
- Compose 桥：`onDraw` 每帧只有 `setBounds`（同值不重算）+ save/restore；`applyAlpha`/`applyColorFilter` 依赖 `RecipeDrawable` 的同值守卫；`remember` 以 recipe 值相等为键（深比较，D-04/`c006a11` 的 hash 缓存不影响它）。
- 软件/硬件路径：没有 `RenderNode` 专用路径，所有绘制走 Canvas 基本图元，硬件与软件 Canvas 共用；先前的 API 37 PixelCopy 对照见 VERIFICATION.md。

## 研究笔记的采用与否决

- 采用 R-B3（计数器）：`689da2c`。
- 暂不做 R-A3（无锁交接）与 TinyLFU/S3-FIFO/ARC 类淘汰：`FlightCache` 默认 64 条、`StateMatcherCache` 每实例只查一次，
  没有真实访问轨迹证明 LRU 命中率是瓶颈；研究笔记也建议先用 Perfetto `android_monitor_contention` 证明主线程阻塞在
  `%FlightCache%` 上。列入候选，前提是拿到 BIL 或基准页的轨迹。
- R-F2（协程适配放可选 `-ktx` 模块）：与本轮 Kotlin DSL 的结论一致——core/android 产物不得引入 Kotlin 标准库
  （`gradle.properties` 明确禁用），DSL 只能放新的可选制品，新增发布坐标需主控决定。
