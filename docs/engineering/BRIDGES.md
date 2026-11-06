# 桥接模块与接口对齐表

对应计划 MD-043、MD-044、MD-045 以及“与其他自研依赖的协作合同”。每个桥是独立的可选制品，基础库
（`meguviz-drawable-core`、`meguviz-drawable-android`）不依赖其中任何一个，也不依赖它们的上游。

原则：不复制上游内部对象图，只使用上游已发布制品的公开 API；上游的行为以真实制品在设备上的观测为准，
不以注释或文档推断。下表中的版本和 SHA-256 取自本机 Gradle 缓存里实际解析到的字节。

## 制品一览

| 制品 | 上游 | minSdk | 语言 | 进入 Central 包（1.2.0） |
|---|---|---|---|---|
| `meguviz-drawable-compose` | Compose BOM 2026.09.00（运行时、ui、ui-graphics 解析为 1.12.1），Kotlin 2.4.21 | 23 | Kotlin | 是 |
| `meguviz-drawable-meguviz-android` | `meguviz-android-runtime` 1.2.0（含共享唤醒停摆修复，尚未发布） | 27 | Java | 是，须等 runtime 1.2.0 先发布 |
| `meguviz-drawable-meguviz-ui` | `meguviz-ui-core` 1.2.0（尚未发布） | 27 | Kotlin | 是，须等 ui-core 1.2.0 先发布 |
| `meguviz-drawable-lumen` | `lumen-engine` 1.5.5（JitPack 坐标） | 27 | Java | 否 |

Compose、MeguvizAndroid、MeguvizUI 三个桥只依赖已发布的 `meguviz-drawable-android:1.0.0`（Central 上）；
其中 MeguvizAndroid 桥另依赖 `meguviz-android-runtime` 1.2.0、MeguvizUI 桥另依赖 `meguviz-ui-core` 1.2.0，它们上 Central 之前对应的桥既不能在默认仓库下解析，也不能发布；
上传前用 `-Pmeguviz.againstPublished=true` 针对已发布字节再构建并测试一次。

Lumen 不进 Central 包：`lumen-engine` 只发布在 JitPack，Central 上的制品不应依赖 Central 用户无法解析的坐标
（MeguvizUI 对它自己的 Lumen 桥采取同样的做法）。该桥仍可从本地 staging 仓库构建和使用，待 Lumen 上 Central 后再纳入。

上游字节：

| 制品 | SHA-256 |
|---|---|
| `lumen-engine-1.5.5.aar` | `07480e39d154f731b1dd0daa394a4e69a33ccc499c5b254791f650db146cf8c0` |
| `meguviz-android-runtime-1.2.0.aar` | `a37b19835a6f459145b878a7936ebf46f13aafc99e45442980a6d59192cfab3c`（本地候选） |
| `meguviz-ui-core-1.2.0.aar` | `e3db238a8ca3e801b61e50c4eb440eb90fad2c349cc98400e8f536154348a845`（本地候选） |

`meguviz-android-runtime` 与 `meguviz-ui-core` 的 1.2.0 尚未发布，上表是本地候选的字节；发布后须与 Central 上的字节重新核对。

## Compose（MD-043）

`RecipePainter` 是 `Painter`，渲染仍走 `RecipeDrawable`，Compose 不引入第二套渲染。`rememberRecipePainter` 负责缓存。

| 输入变化 | 处理 | 依据 |
|---|---|---|
| recipe（按值）、density、`mirrorInRtl` | 换一个新 painter，旧的在 composition 遗忘时释放 | `RecipeDrawable` 固定 recipe 与 density |
| 状态（`DrawableState`） | 更新同一个 painter；外观不变则不重绘 | 设备合同：状态切换前后 painter 同一实例 |
| 尺寸 | 绘制时设为 bounds（四舍五入到整数像素） | 后端使用整数 bounds |
| 布局方向 | 仅在 `mirrorInRtl` 时于 RTL 下水平翻转绘制；不进入缓存键 | recipe 本身与方向无关 |
| alpha、ColorFilter | `applyAlpha`/`applyColorFilter` 转给 drawable，且不触发二次重绘 | 评审发现并修复 |

离开 composition 时 painter `close()`：清除 drawable 回调并置为不可见；之后绘制是空操作，状态赋值被忽略。
普通 View 依赖图不引入 Compose：`meguviz-drawable-android` 的 POM 不含 Compose。

## MeguvizAndroid（MD-044，运行时部分）

`MeguvizBinding` 把一个 `BindingScope` 接到三个上游权威，不新增线程、Handler 或帧回调。

| 上游 | 采用 | 对应 |
|---|---|---|
| `ResourceState.dependencies(ALL)` 的 `EpochDomain.stamp().epoch()` | 两个代次之和 | `BindingEnvironment.resourceRevision`（只增不减，任何失效都会改变它） |
| `ResourceState.snapshot().densityDpi` | `/ 160` | `density()`，供 `RecipeDrawable` |
| 调用方提供的 `RegistrySource` | 修订号 | `BindingEnvironment.registryRevision` |
| `LifecycleScope.isActive()` | 对齐方式 | `sync()`：不活跃则 `suspend`，恢复则 `resume`（上游没有状态变化回调，所以按需对齐） |
| `LifecycleScope.lease(ALL, 资源域)` / `WorkToken` | 每个请求一个租约 | 提交需要租约 token 仍当前、生命周期活跃，且 `BindingScope` 的 stamp 也有效，两道门都过才安装 |
| `LifecycleScope.own(Registration)` | 生命周期关闭时关闭桥与其 scope | 槽位仅在仍为本库所有时被清除 |
| `BoundedMailbox.offerState`（每个 key 一个 slot） | 工作线程到所有者线程的单调度 | `post`：最新者胜，被替换/拒绝/丢弃的结果由 releaser 释放（可能发生在投递线程，须线程安全） |

与上游的有意差异：`LifecycleScope.commit` 的回调抛异常会关闭整个生命周期；桥在回调内捕获，释放结果后在调用方重新抛出，
不让一个安装失败关闭同一所有者的其他绑定。邮箱的 `action` 抛异常同样会让邮箱失败关闭，所以 `post` 把异常交给 `Listener`。

观测到的上游事实（设备合同依赖）：分离导致 attach 代次变化，令牌立即失效，提交返回 STALE 而不是 INACTIVE；
只有停止（`setStarted(false)`）这类不改变代次的情况才返回 INACTIVE。

## Lumen（MD-045）

`LumenSurfaceBridge` 只拥有“登记”，材质、采样和动画决策仍属于 Lumen。以下事实均来自 1.5.5 真实会话的设备探针
（`LumenProbe`），不是文档推断：

| 观测 | 桥的处理 |
|---|---|
| `bind` 把 View 背景换成 Lumen 的 `SurfaceDrawable`；`update` 保持同一 drawable | 首次绑定后记录 View 背景；`update` 仅在选项变化时调用 Lumen |
| 宿主替换背景后 `isBound` 仍为 true | 登记比较 View 当前背景与记录值，报告 `LOST`，不再写入；关闭时 Lumen 不会覆盖宿主的 drawable |
| 分离时 Lumen 解绑并恢复原背景，重新附着不会自动重绑 | 状态为 `UNBOUND`；`refresh()` 在重新附着后用最新选项重新绑定（`REBOUND`） |
| 对同一 View 再次 `bind` 使旧绑定失效，关闭旧绑定不影响新绑定 | 每个登记只持有一个绑定 |
| 会话关闭后 `bind` 不抛异常、返回无效绑定 | 桥在占用 key 之前抛 `IllegalStateException` |

所有权：登记通过 `BindingScope.claim(key)` 独占属性 key（该 API 为此新增，也可用于其他自管属性的所有者），
与同 scope 的配方槽位或第二个登记互斥。共享会话不会被桥暂停或关闭，只有声明 `ownsSession` 才可以。

`claim` 只存在于未发布的 core 1.2.0，所以 Lumen 桥显式依赖 `meguviz-drawable-core`；已发布的 core 1.0.0 没有它。

没有复用 `lumen-controls` 或 `LiquidChoiceDrawable`：计划要求先读取实际解析的 AAR 的公开 API 并验证，
当前解析到的 `lumen-engine`/`lumen-motion` 不含 controls 制品；BIL 本地副本的删除仍然受阻，本桥不改变这一点。

## MeguvizUI（MD-044，属性所有权部分）

`UiSession.bindRecipeBackground(ref)` 返回 `RecipeBackground`，把配方作为一个 MeguvizUI 属性（id 默认 `background`，
`EXCLUSIVE`、可合并、按值判等、对资源敏感）。MeguvizUI 决定何时写（帧调度、合并、attach/rebind/主题/资源代次令牌），
本库决定写什么：

- 相等的配方由属性绑定跳过；`invalidate()` 后重写同一配方与密度时保留已安装的 drawable，不再新建。
- View 密度与上次写入不同时不再按值跳过：下一次提交即使配方相等也会写入，并按新密度重建 drawable。
- 宿主替换背景后 `isOwned` 立即为 false，后续写入不覆盖宿主的 drawable；`reclaim()` 显式夺回，并清掉绑定的等值缓存，
  所以夺回后重新提交宿主接管期间的同一配方也会生效。
- 与 Lumen 的 `reserveProperties("background")` 或另一个绑定冲突时，两种顺序都由 MeguvizUI 自己的注册表拒绝。
- View 释放、节点 `rebind` 或会话关闭会关闭绑定，并仅在背景仍是本绑定安装的那个时清除它。

## 验证范围

- 宿主 JVM：`CoreContractTest` 20,235 项（含新增的属性占用 `claim`）；`MeguvizBindingTest` 15 项，使用真实 MeguvizAndroid 运行时，
  以手动驱动的所有者线程和帧源。
- API 34 模拟器（单台）：Compose 89 项（含硬件窗口 PixelCopy 对照）、MeguvizAndroid 真实平台后端 17 项、Lumen 45 项、
  MeguvizUI 58 项，另有基础库的 52 项合同与 29 项绑定合同。每个桥都做过一次删除关键逻辑的变异，合同随之失败后已还原。
- 全部模块 lint 通过；`verify_candidate_licenses.py` 对所有候选通过。

未验证：API 23–26（Compose）与 API 27–33 上的任何桥、真机与硬件差异（本轮物理设备未连接）、
R8 后行为、独立 consumer 对桥制品的消费（现有 fixture 只覆盖 core 与 android）、`BoundedMailbox` 在真实帧压力下的行为、
Lumen 在其他版本上的行为（只对 1.5.5 做了探针）。
