# 联合改进第一轮审计（2026-11-08）

范围：分支 `perf/joint-r1`（基于 `origin/main` `b087b3f`）上的 `core`、`android`、`compose`、`meguviz-ui`、`lumen`、`meguviz-android` 源码与 `docs/`。方法：逐文件源码审查、`javac` 编译运行宿主合同、宿主 JVM 探针及最小复现程序。

## 基线校验命令

| 命令（JDK `21.0.12.1`，Gradle Wrapper `9.8.2`，`--offline --no-daemon --max-workers=2`） | 当时结果 |
| --- | --- |
| 新克隆直接执行 `./gradlew ...` | 退出码 `126`：`gradlew` 权限为 `100644`（`D-09`，已于 `d6e2335` 修复） |
| `bash ./gradlew -Pmeguviz.profile=android check` | 通过：`CoreContractTest` 默认 `148` 项，Android 编译与 Lint 通过 |
| `-Pmeguviz.profile=bridges` | 当时未在离线环境运行：上游 `1.2.0` 制品在审计时尚未入本地缓存或发布（现 `1.2.0` 已上线 Central） |
| `:android:publishCandidatePublicationToStagingRepository`（离线） | 当时离线缺失 `dokka-core:2.2.0` 缓存失败（`D-10`）；`:core` staging 正常 |

根构建要求 JDK `17`；`tools/dump_abi.py` 使用的 `javap` 文本格式可能随 JDK 小版本微调（见 [docs/abi/README.md](../abi/README.md)）。

## 发现与处理清单

严重度分级：高 = 错误结果或资源安全；中 = 契约或性能缺陷可被正常用法触发；低 = 局部开销或可维护性；信息 = 文档或流程差异。

| ID | 严重度 | 位置 | 问题与证据 | 状态 |
| --- | --- | --- | --- | --- |
| `D-01` | 中 | `core/.../core/StateMatcherCache.java` | 早期提交将 `seen[distinct++] = id;` 误并入行尾注释，导致 `distinctIds` 恒返回 `0`，建表前两条拒绝分支失效并白付建表开销，仅靠建表后过滤维持结果正确 | 已修 `740a7cb` |
| `D-02` | 中 | `core/.../cache/FlightCache.java` `publish` | 单条权重大于 `maxWeight` 的值曾先入缓存再同次触发 `CAPACITY` 淘汰，导致淘汰监听在交付之前回调并可能误回收在屏资源 | **已修**：超重值只直接交付而不入缓存、不触发 `CAPACITY`、不计 `produced` / `version()`，并新增 `oversizeValueIsDeliveredButNeverCached` |
| `D-03` | 低 | `FlightCache.run` → `publish` | `computeNow` 抢先写入后，后完成的同键运行中飞行会以 `REPLACED` 淘汰正在使用的结果 | **已修**：`publish()` 见到已有条目直接返回已有引用，不触发 `REPLACED` 且不二次递增 `version()`，新增 `runningFlightJoinsTheCachedValue` |
| `D-04` | 低 | `FlightCache` 计数器 | 13 个相邻 `AtomicLong` 中 10 个仅在 `synchronized (lock)` 内更新，存在冗余原子操作及伪共享风险 | 已改 `689da2c`：锁内 10 项改普通 `long[]`，单线程 `peek` 中位数 `6.64 → 5.29 ns` |
| `D-05` | 中（行为） | `android/.../RecipeDrawable.java` `getOutline` | 恒返回空轮廓且 alpha `0`：圆角或椭圆背景配 `elevation` 无默认阴影、`clipToOutline` 无效（有意保守处理） | 保留现状以兼容已有视觉契约，并在文档与 Skill 中明确说明 |
| `D-06` | 低 | `RecipeDrawable.onBoundsChange`、`getConstantState`、`onStateChange` | 非绘制热路径的局部对象分配（`radii()`、`colors()/stops()` 克隆、`new LinearGradient` 等）；`draw()` 本身零分配 | 列入候选，待设备探针实测尺寸动画场景后再评估 |
| `D-07` | 低 | `RecipeDrawable.getOpacity` | 恒返回 `PixelFormat.TRANSLUCENT` | 保留保守返回值，避免老系统错用不透明窗口优化 |
| `D-08` | 信息 | 历史 README、`STATUS.md` 与 `docs/abi/1.1.0` | 2026-11-08 审计当时曾存在描述 `1.0.0` 与未发布 `1.2.0` 口径不一致的文档差异 | **现已同步**：`core`/`android`/`compose`/`meguviz-android`/`meguviz-ui` 及 `meguviz-bom` `1.2.0` 已发布至 Central，并已更新文档口径 |
| `D-09` | 低 | `gradlew` | Git 权限 `100644` 导致 Unix 环境无法直接执行 | 已修 `d6e2335` |
| `D-10` | 低 | `:android` 本地发布 | 离线构建 javadoc JAR 需要未缓存的 `dokka-core:2.2.0` | 记录于构建前提说明 |
| `D-11` | 信息 | `Length.hashCode` | 曾使用枚举身份哈希导致跨进程哈希不一致 | 已在后续提交改为 `ordinal()`，并由合同锁定 |
| `D-12` | 信息 | 默认合同预算 | `CoreContractTest` 默认上限 `150`，通过合并同场景关联断言腾出预算，并将大语料保留在 `:core:contractStress` | 已处理 |
| `D-13` | 低 | `StateSpec.equals` | 零终止符之后的多余元素仍参与 `equals` 比较（有文档约定），仅影响缓存键复用率 | 保留文档约定 |

## 已核对通过的设计不变式

- **模型不可变性**：构造与访问均做防御性拷贝，`-0.0f` 归零，拒绝 `NaN`/无穷大，`equals` 与 `hashCode` 一致。
- **`FlightCache` 并发边界**：生产者、订阅者、淘汰监听与 weigher 均在锁外执行；取消、关闭与执行器/投递失败路径已完整覆盖。
- **绘制零分配与 Compose 复用**：`RecipeDrawable.draw` 零分配并复用 `Paint`/`Path`/`RectF`；Compose 端仅在 `recipe`/`density`/`mirrorInRtl` 变化时重建，状态切换原地更新 `RecipeDrawable`。

## 外部调研采纳与否决记录

- 采纳计数器锁内去原子优化（`689da2c`）。
- 暂不引入复杂无锁队列与 TinyLFU/ARC 淘汰策略：`FlightCache` 默认 `64` 条、`StateMatcherCache` 每实例仅查一次，尚无真实轨迹证明锁或 LRU 命中率为瓶颈。
- 协程封装不进入零依赖的 `core` / `android` 制品（`gradle.properties` 明确禁用自动注入 Kotlin 标准库）。

## 适用范围与验证说明

- 本轮审计基于源码阅读、宿主合同执行与 JVM 探针，**未在模拟器或真机上运行设备测试**；所有涉及 Android 视图绘制、Outline 表现与 ART 运行时的结论均须结合真机测试另行确认。
