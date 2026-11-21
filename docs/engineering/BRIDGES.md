# 桥接模块与接口对齐表

对应计划 MD-043、MD-044、MD-045 与跨库协作合同。各桥接模块均为独立可选制品；基础库 `meguviz-drawable-core` 与 `meguviz-drawable-android` 不反向依赖任何桥接模块或上游运行时。

设计原则：不复制上游内部对象图，仅调用上游公开 API。`meguviz-drawable-compose`、`meguviz-drawable-meguviz-android` 与 `meguviz-drawable-meguviz-ui` 已随基础库统一发布至 Maven Central `1.2.0`，并由家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 管理版本；`meguviz-drawable-lumen` 暂未进入 Central，保留源码构建。

## 制品一览与发布口径

| 制品 | 上游依赖 | minSdk | 语言 | Central `1.2.0` 状态 |
| --- | --- | --- | --- | --- |
| `meguviz-drawable-compose` | Compose BOM `2026.09.00`（`runtime`、`ui`、`ui-graphics`），Kotlin `2.4.21` | `23` | Kotlin | 已发布（纳入 `meguviz-bom:1.2.0`） |
| `meguviz-drawable-meguviz-android` | `meguviz-android-runtime:1.2.0`（含共享唤醒停摆修复） | `27` | Java | 已发布（纳入 `meguviz-bom:1.2.0`） |
| `meguviz-drawable-meguviz-ui` | `meguviz-ui-core:1.2.0` | `27` | Kotlin | 已发布（纳入 `meguviz-bom:1.2.0`） |
| `meguviz-drawable-lumen` | 仓库 `libs.versions.toml` 当前固定 JitPack `lumen-engine:1.5.5`；`Lumen` `1.6.2` 亦已独立上线 Central | `27` | Java | **未入 Central（仅源码/本地构建）** |

> **说明**：
> - `meguviz-android-runtime:1.2.0`、`meguviz-ui-core:1.2.0`、`meguviz-drawable-core:1.2.0`、`meguviz-drawable-android:1.2.0`、上述三座桥接制品与 `meguviz-bom:1.2.0` 现均已发布至 Maven Central；生产消费请以 Central 实际字节与哈希为准（不要将历史本地候选的临时 SHA 当作不可变发布哈希）。
> - `meguviz-drawable-lumen` 因仓库构建仍钉住 JitPack `lumen-engine:1.5.5`（Central 制品不允许依赖外部 JitPack 坐标）且依赖 `core 1.2.0` 新增的 `BindingScope.claim`，暂未进入 `1.2.0` Central 发布包；需要源码构建时请使用 `-Pmeguviz.profile=bridges`。

## Compose 桥（MD-043）

`RecipePainter` 继承 `Painter`，渲染仍走 `RecipeDrawable`，Compose 端不引入第二套渲染器；`rememberRecipePainter` 负责按组合作用域缓存。

| 输入变化 | 处理方式 | 依据 |
| --- | --- | --- |
| `recipe`（按值）、`density`、`mirrorInRtl` | 重建新 `RecipePainter`，旧实例在组合遗忘时自动 `close()` | `RecipeDrawable` 固定配方与密度 |
| `state`（`DrawableState`） | 原地更新同一个 `RecipePainter`，外观未变则不触发重绘 | 设备合同验证切换前后实例不变 |
| 尺寸（`Size`） | 绘制时四舍五入为整数像素边界 | 后端基于整数 `Rect` 运算 |
| 布局方向 | 仅当 `mirrorInRtl == true` 且为 RTL 时水平翻转绘制，不进入缓存键 | 配方本身与方向无关 |
| `alpha`、`ColorFilter` | `applyAlpha` / `applyColorFilter` 直接转发至 drawable，不额外触发二次重绘 | 同值守卫避免多余 `invalidate` |

离开组合时 `close()` 会移除 drawable callback 并将可见性设为 `false`，之后绘制为空操作并忽略迟到的状态赋值。纯 View 工程引入 `meguviz-drawable-android` 时不会传递引入任何 Compose 或 Kotlin 依赖。

## MeguvizAndroid 桥（MD-044，运行时部分）

`MeguvizBinding` 将 `BindingScope` 接入 MeguvizAndroid 的三大权威组件，不自建线程、Handler 或帧回调。

仅在主线程提交且不注册自定义 provider 注册表的宿主，可使用两参便捷入口 `MeguvizBinding.create(lifecycle, resources)`（内部等价于传入 `null` 邮箱、恒为 `0` 修订号的 `RegistrySource` 及 `ownerEpoch = 0`；传入 `ViewScope` 时写作 `MeguvizBinding.create(scope.lifecycle(), scope.resources().state())`）。若需后台线程异步准备并在下一帧回主线程安装，使用五参 `create(lifecycle, resources, mailbox, registry, ownerEpoch)` 并调用 `post(...)`。

| 上游权威 | 采用字段 | 映射对象 |
| --- | --- | --- |
| `ResourceState.dependencies(ALL)` | 两个 `EpochDomain` 的 `epoch()` 之和 | `BindingEnvironment.resourceRevision`（单调递增，任何失效均改变修订号） |
| `ResourceState.snapshot().densityDpi` | `densityDpi / 160f` | `density()`，供 `RecipeDrawable` 使用 |
| 调用方 `RegistrySource` | `revision()` | `BindingEnvironment.registryRevision` |
| `LifecycleScope.isActive()` | `sync()` 对齐 | 不活跃则 `suspend`，恢复则 `resume` |
| `LifecycleScope.lease(ALL, 资源域)` / `WorkToken` | 每次请求关联一个租约 | `commit`/`post` 需同时通过 `WorkToken` 与 `BindingScope` 的 `RequestStamp` 双重校验 |
| `LifecycleScope.own(Registration)` | 生命周期关闭时级联关闭桥与 `BindingScope` | 仅在槽位仍为本库所有时清除 |
| `BoundedMailbox.offerState` | 按 `key` 分配 `Slot` | `post` 仅保留最新项；被替换/拒绝/丢弃时在对应线程执行线程安全的 `releaser` |

**与上游的有意差异**：`LifecycleScope.commit` 回调若抛异常会关闭整个 `LifecycleScope`，`MeguvizBinding.commit` 在回调内捕获后先释放结果再重抛，避免单个绑定失败拖垮同属该窗口的其他绑定；`post` 中若传入了 `Listener`，异常转交 `Listener.onFailure` 而不关闭共享邮箱。此外，View 分离使 `attach` 代次变化，令牌立即失效并返回 `STALE`；仅 `setStarted(false)` 等不改代次的暂停状态返回 `INACTIVE`。

## MeguvizUI 桥（MD-044，属性所有权部分）

`UiSession.bindRecipeBackground(ref)` 返回 `RecipeBackground`，以 `MeguvizUI` 属性机制管理 View 背景（默认属性 ID `"background"`，`PropertyOwnership.EXCLUSIVE`、可合并、按值判等、资源敏感）。`MeguvizUI` 负责帧调度与作用域失效时机，本模块决定安装行为：

- 等值配方由属性绑定跳过；调用 `invalidate()` 后若配方与密度均未变，仍保留已安装的 `RecipeDrawable`。
- 当 View 的 `densityDpi` 变化时，即使配方值相等也不再跳过，下次提交会按新密度重建 drawable。
- 宿主在外部覆盖背景后，`isOwned` 立即变为 `false`，后续写入不会覆盖宿主背景；调用 `reclaim()` 会同时清掉绑定等值缓存，使随后提交同一配方时也能正常收回所有权。
- 与 Lumen 的 `reserveProperties("background")` 或其他绑定重复声明同一属性 ID 时，由 `MeguvizUI` 注册表显式拒绝冲突。
- View 释放、节点 `rebind` 或会话关闭时自动关闭绑定，且仅在当前背景仍是本绑定安装的实例时将其清除。

## Lumen 桥（MD-045，仅源码构建）

`LumenSurfaceBridge` 仅接管 `BindingScope.claim(key)` 独占登记，材质、采样与动画决策仍由 Lumen 自身控制。通过真实会话探针（`LumenProbe`）观测到的核心行为如下：

| 实际观测行为 | 桥接层处理方式 |
| --- | --- |
| `bind` 将 View 背景替换为 Lumen `SurfaceDrawable`，`update` 复用同一实例 | 首次绑定后记录所装背景引用；`update` 仅在 `options` 变化时透传给 Lumen |
| 宿主替换背景后，Lumen 原生 `isBound()` 仍可能返回 `true` | 桥接对比 `view.getBackground()` 与已记录实例，立即报告 `LOST` 并停止写入 |
| View 分离时 Lumen 自动解绑并恢复原背景，重附着后不会自发重绑 | 状态转为 `UNBOUND`；重附着后调用 `refresh()` 以最新选项重绑并返回 `REBOUND` |
| 对同一 View 再次 `bind` 会顶替旧绑定 | 每次登记独占唯一 `key`，关闭旧登记不会影响后继新登记 |
| 会话已关闭时直接调用原生 `bind` 可能返回无效绑定而不抛错 | 桥接层在占用 `key` 前检查 `session.isClosed()` 并抛出 `IllegalStateException` |

使用共享 `LumenSurfaceSession` 时请将 `ownsSession` 设为 `false`，桥接关闭时不会暂停或销毁共享会话。

## 适用范围与验证说明

- **已验证覆盖**：宿主 JVM 上 `CoreContractTest`（含 `BindingScope.claim`）与 `MeguvizBindingTest` 通过；单台 API 34 模拟器上 Compose（89 项，含硬件窗口 PixelCopy）、MeguvizAndroid 桥（17 项）、MeguvizUI 桥（58 项）、Lumen 1.5.5 桥（45 项）及基础库合同通过，全部模块 Lint 与许可证检查通过。
- **未覆盖边界**：API 23–26 上的 Compose 桥、API 27–33 上的各桥接模块、真机硬件差异、`R8` 混淆后的桥接消费、独立消费者工程对桥制品的解析、`BoundedMailbox` 真机高压场景，以及除 `1.5.5` 之外其他 Lumen 版本的适配行为，均须在各自目标环境补跑验证。
