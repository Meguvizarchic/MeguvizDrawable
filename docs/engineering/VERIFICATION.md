# 验证记录

2026-10-28，基线提交 `4c5dd54`，JDK 17、Gradle 9.8.2：`gradlew --offline --no-daemon check --rerun-tasks`，退出码 0，重新编译后执行 `CoreContractTest`，20,018 项检查通过。范围仅包括当时的状态匹配、输入复制和圆角约束；后续包名迁移及新增代码尚未验证。

初次 `check` 时合同测试通过，但默认 Gradle `test` 任务因没有测试框架用例而失败。构建随后改为禁用该默认任务，由 `check` 显式依赖可执行的合同测试；上述重跑包含修正后的接线。另一次在受限环境运行时 wrapper 缓存锁文件访问被拒绝，使用已有缓存权限重跑成功。

这些结果不验证 Android、设备、BIL 迁移、独立制品、ABI 或性能。机器相关原始日志保存在本地，不纳入仓库。

## 扩展验证，2026-10-28

- 宿主 JDK 17：`gradlew --offline --no-daemon check --rerun-tasks`，退出 0，20,058 项检查通过，涵盖状态、模型防御复制、参数隔离与绑定所有权。完整模型、缓存和 SPI 仍未完成，G2 不能判定通过。
- Android：使用已安装 SDK 执行 `gradlew --offline --no-daemon -Pmeguviz.profile=android check`，退出 0，含构建和 lint；首次未提供 SDK 位置的运行失败，保留本地日志。另在 API 37 单设备通过 29 项 instrumentation 检查，覆盖软件 Canvas 像素、状态数组别名、失效回调、实例隔离、固定 metrics、alpha 与 tint。其他 API、硬件 Canvas、完整 Drawable 协议和真实生产页面尚未验证。
- 实际生产源码基线的文本检索未发现旧 Builder 调用，但依赖声明仍存在；这不是运行依赖图、APK 或设备退出证明。

## 性能试验，2026-10-28

- 对重复传入相同状态的路径，当前实现直接比较状态，避免同值时克隆；状态输入和输出仍防御复制。在单台 API 37 设备、debug 构建中，以测试专用的简化旧克隆路径为基线，7 个不同 PID、每进程 12 组交替配对，每路径每组 50 万次。各进程中位数：基线 100.829–151.185 ns/次，当前路径 7.976–11.967 ns/次，对应下降 91.63%–92.10%。这仅是重复相同状态调用的局部测量，不代表整库、页面或 FPS 收益。
- 主机试验固定 8 状态、13 条规则，10 个新 JVM 进程各 7 轮配对；包含状态编码的实验查表相对直接匹配的进程级配对降幅中位数为 68.487%。表载荷 1,024 字节，未计表构建和完整对象内存；候选没有进入生产。实验语料、单机负载和 HotSpot 与实际 Android 工作负载不同，不能推断设备收益。

性能原始数据及设备身份信息仅保存在本地；协议和可复现工具位于 `benchmark/`。所有实验都不构成完整性能门禁通过。

## 设备验证复现

安装 Android SDK 并选择设备后，在 PowerShell 中执行 `./gradlew.bat --offline --no-daemon '-Pmeguviz.profile=android' :android:assembleDebugAndroidTest`。测试 APK 自包含，包名及 instrumentation 目标均为 `io.github.jichuo1.meguviz.drawable.contracttest`。安装前先检查设备是否已有同名包，避免覆盖已有应用。安装后运行：

```text
adb -s <device> shell am instrument -w -r io.github.jichuo1.meguviz.drawable.contracttest/io.github.jichuo1.meguviz.drawable.android.test.ContractInstrumentation
```

性能模式在组件名前添加 `-e mode benchmark`，应启动多个独立进程，并保留每次原始输出和 PID。设备差异、构建模式、基线实现及热身方式均应随结果记录。

## 发布候选与扩展设备验证，2026-10-28

- 同一测试 APK 在 API 34 模拟器和 API 37 真机上各通过 52 项软件 Canvas 合同，覆盖透明渐变及 alpha、非对称圆角变换、滤镜与 tint、实例隔离、错误恢复、状态数组原地复用及 checked/selected 区分。硬件 Canvas、API 21/22 和其他版本仍未验证。
- 本地隔离 Maven 仓库的 `0.1.0-dev.1` 候选由独立 Gradle 根目录 `fixtures/consumer` 消费，不使用 project 依赖或 composite 替换。Gradle metadata 和 Maven POM 两条解析路径都检查了解析字节与候选一致；releaseRuntimeClasspath 仅包括本库 Android 和 core，release 消费者完成 R8 构建。该未签名 APK 的独立调试签名副本在 API 37 真机安装并冷启动，确认安装字节与前台 Activity；未作页面像素验证，也不代表正式签名或公开发行。
- 初版候选元数据曾意外包含 Kotlin 标准库，已保留本地失败证据；仅有 Java 生产源码的当前配置已禁用自动注入，重新生成的 Android 候选只声明本库 core 依赖。项目已选用 Apache-2.0，候选 POM、二进制与源码归档均携带对应许可证，并以 `tools/verify_candidate_licenses.py` 核对。
- 从候选 JAR 和 AAR 生成首版 [ABI 清单](../abi/README.md)，分别覆盖 17 个和 1 个公开或受保护类。首版无历史版本，不声称旧二进制兼容。

候选 JAR SHA-256：`280635cf5e23b9c82be44195bdb8c2dcbb493b388e6071b63bd8f3de132f373e`；候选 AAR SHA-256：`bce48e151df9ac37f591080a2dc625d39d106fe553ffbcb40db6454411304272`。结果只绑定这些候选字节；若重新生成制品导致摘要变化，必须重新验证。完整构建与测试原始日志保留在本地。

## 扩展候选验证，2026-10-28

- `0.1.0-dev.2` 引入实验性闭合多边形 provider：冷路径显式注册、版本/依赖/顶点预算校验及独立 Android 绘制入口；不扩展 `RecipeDrawable`，也不宣称实现完整 SPI 或 tint 协议。独立 provider 的 22 项宿主合同、metadata/POM 两种解析和 R8 release 构建通过；API 37 真机上的 debug fixture 验证软件绘制与 owner 隔离，不能称为 R8 设备运行。
- 先前 `0.1.0-dev.1` 编译并冻结的有限 Java consumer 字节码，不重编译即可在 dev.2 core 上运行；旧 core 的 17 个公开/受保护类 API 文本均与新清单一致。该证据只覆盖此 fixture 和清单，不代表任意历史消费者或 Android ABI 已验证。
- API 21/22 云端模拟器在提交 `48999e0` 上各通过 52 项软件合同和独立 provider 合同；构建和设备回读的三个 APK 均逐字节一致，debug/release 依赖解析均核对了实际 staging 制品。受测源码摘要与本机测试一致。首次运行因 shell 不兼容在测试前失败，后续 API 22 一次模拟器下载损坏未执行测试；原始失败和成功重试均保留。
- API 37 真机的测试 APK 在硬件加速窗口内对照四个场景：渐变 6,084 像素、不对称圆角 6,400 像素、checked/alpha 128 与 selected/alpha 255 各 6,400 像素，均与平台参考逐像素一致。初次全屏截图对照失败并已保留；后续使用测试窗口 PixelCopy，未放宽容差。不能由此推断所有硬件、GPU 或绘制效果均通过。

dev.2 候选 core JAR SHA-256 `51b17b45e0397b5b322b1382bb73e42bb7e7a91199e538eb79e31031a352c9ba`；Android AAR SHA-256 `48ad5815a27ea0e2844a5bc50cdc869d3a6963ea17735a5db47e28353be6424f`。若这些字节改变，相关消费者和 ABI 证据需重新核验。

独立 provider 的 R8 应用使用调试签名副本，在 API 37 设备上通过黑盒 instrumentation 的四点屏幕采样。原 debug instrumentation 直接链接未混淆类名，在 R8 目标上以 `NoClassDefFoundError` 失败，该失败保留；黑盒结果不替代原合同或完整像素对照。BIL 依赖退出见 [隔离补丁与 PR](../../integration/bil/README.md)，已合入 `Advanced`，尚未合入 BIL `main` 或完成正式 release APK、Hook 和 UI 验证。

## Drawable 协议候选，2026-10-28

- `0.1.0-dev.3` 对静态、无 tint 和外部滤镜的 `RecipeDrawable` 提供可重建的 `ConstantState`；实例各自持有绘制和运行状态。存在 tint 或显式滤镜时保守返回 `null`。API 37 真机协议模式通过 22 项检查，原软件合同 52 项及硬件合同 60 项通过。
- 对新候选重新构建 core/Android、独立消费者及 provider；Gradle metadata、POM、强制 R8 构建和许可证检查通过。API 21/22 对提交 `9965c3c` 的独立矩阵各通过软件 52 项、快照协议 22 项及 provider 合同，构建与设备回读 APK 字节一致；[原始运行](https://github.com/jichuo1/MeguvizDrawable/actions/runs/40688326056)。
- `Advanced` 已非强制快进到 BIL 基线，PR #39 已合并，合并树与隔离验证候选一致；合并提交的 [Advanced CI](https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/40688542546) 通过。BIL 候选隔离快照的 testDebugUnitTest 共 4,116 项、1 跳过、0 失败/错误；lint 未发现有效新增错误。隔离的基线和候选控件 fixture 在 API 37 真机分别通过构造、测量、attach、状态、整行 Ripple、点击及拒绝回滚合同，控件位图和指标摘要一致。fixture 使用唯一包名及中性 Application，不覆盖已安装模块；固定签名 release APK、实际 Hook 和真实页面仍缺证据。

dev.3 core JAR SHA-256 `51b17b45e0397b5b322b1382bb73e42bb7e7a91199e538eb79e31031a352c9ba`；Android AAR SHA-256 `8335d055955304e1dab311a505a236860a5d849ec48db05e0a9970c2ef07260f`。这些结果仅绑定上述候选字节。

`dev.4` 新增外部 child 的单 owner 包装器，处理回调解绑、来源校验、调度取消、状态与可见性。普通 consumer 和 provider 的 metadata/POM 解析、R8 构建通过；[API 21/22 模拟器矩阵](https://github.com/jichuo1/MeguvizDrawable/actions/runs/40692606779) 各通过 36 项包装器合同、52 项基础合同、22 项快照协议和 provider 合同，安装 APK 与构建字节一致；API 34 只读模拟器另通过 37 项包装器合同。调度型 child 必须先绑定宿主 callback，再安装到空包装器；宿主替换 callback 前须先隐藏或关闭包装器。API 29 BlendMode tint 不转发，此入口不声称与任意平台 wrapper 完全等价；真机验证待完成。

独立 View 页面的确认实验在预先提交的 `benchmark/page/CONFIRMATION.md` 协议下进行：API 37 单设备、约 120 Hz、release/R8、64 个 View，每帧人为重复 512 次相同状态更新，30 对 AB/BA 独立进程。逐 run 帧耗 p95 的配对差（候选减基线）中位数为 -0.0758 ms，按配对块重采样的探索性 95% 区间为 [-0.5091, +0.0168] ms；在该实验与统计假设下满足预注册的 0.5 ms 非劣化判据，但未证明至少 0.2 ms 的帧耗收益。软件位图预检一致、窗口为硬件加速且未丢帧指标回调；这不证明硬件像素等价，也不能推断 BIL 真实页面或其他设备的收益。完整 run 日志和原始数据保留在本地。

用户自行打开并滚动已安装的既有 BIL 版本后，另以不重置、不注入输入的方式读取了前后 `gfxinfo framestats`；两次进程与安装 APK 摘要一致。环形缓冲各保留 120 条且无重叠，已覆盖部分区间，因此无法还原整个滚动过程。后一次保留数据中，107 条未标记且时间字段有效的帧，其 `FrameCompleted - IntendedVsync` 中位数为 13.542 ms、最近秩 p95 为 17.017 ms；这些是约 1.24 秒范围内的有限近期帧，不能确定全部属于滚动，也不能作为完整 run、卡顿率或候选 A/B 收益。该安装版本不是当前 Advanced 候选，原始记录仅保留在本地。

## 绑定所有权与 Provider 扩展，2026-10-28（未提交，工作树）

- 新增：`SlotBinding` 的幂等 `ensure`、`takeOver` 显式重绑、spec/revision/owner token 记录与释放；`BindingScope`（生命周期、绑定代次、按 key 的最新请求有效、校验与安装不可穿插的 `commit`）；`ViewSlots` 与 `ViewLifecycleBinder`（View 背景/前景槽位、attach/detach 映射到 suspend/resume）；SPI 的能力声明、命名回退、`ShapeProviderRegistry.toBuilder()`/`unregister`、`ShapeProviderConformance`。
- 宿主 JVM：`gradlew --offline --no-daemon -Pmeguviz.profile=android --rerun-tasks check` 退出 0，`CoreContractTest` 20,221 项通过（含新增绑定与扩展合同），Android lint 随 `check` 通过。
- API 34 模拟器单台：同一测试 APK 的 `binding` 模式 29 项、`contracts` 52 项、`wrapper` 37 项、`protocol` 22 项通过。`binding` 模式包含 View 背景所有权、宿主替换后不覆盖、PRESERVE_HOST 内边距、Activity 内 attach/detach/reattach 与迟到结果、回退后的多边形像素。
- 审查中发现并修复：`ensure` 在工厂执行后未复核槽位；环境 A→B→A 使旧结果重新有效（现以变更计数区分）；释放回调在不可重入段内执行；绝对内边距被恢复为相对内边距；一致性检查在工作线程失败时挂起或空指针，并对非捕获 lambda 误报。
- 未验证：API 21/22/37、硬件窗口、RTL 布局；`docs/abi` 未重新生成；独立 consumer/provider fixture 仍固定旧候选，未覆盖新增 API。核心与 Android 字节相对 `0.1.0-dev.4` 已变化，此前绑定到 dev.4 SHA-256 的证据不适用于当前源码；已升到 `1.0.0` 并重新取证，见下一节。
- 仍未实现：计划 C07 中的共享策略、outline/外扩/离屏声明；后台准备的调度器（`commit` 只负责校验与安装）；FillProvider、EffectProvider、BackendProvider；Compose、Meguviz、Lumen 适配。

## 1.0.0 候选与 Central 上传包，2026-10-28

- 版本从 `0.1.0-dev.4` 改为 `1.0.0`（core、android、独立 consumer/provider fixture、辅助脚本）。`benchmark/page` 仍固定 `dev.3`，因为它记录的是该候选的历史基线。
- staging 候选（当时）：core JAR SHA-256 `6115921e7289196c084e2d82185a71f448716e75c0d9bfe42c1987e9fba53a37`；Android AAR SHA-256 `e9a74e017fcf0e46cee7593694aea639d316339a03b84a47d4e51baec6c4b0ac`。这两份随后被上传到 Maven Central 作为 1.0.0（2026-10-28 15:35 UTC），2026-10-29 核对：Central 上的 core jar 与 android aar 字节与此一致。
- 独立 consumer：Gradle metadata 与 POM 两条解析路径的 `verifyCandidateResolution` 和 R8 `assembleRelease` 通过，解析字节与上述 SHA-256 一致。独立 provider：两条解析路径、R8 release 与 `assembleDebugAndroidTest` 通过。随后为该 fixture 补充了能力声明、命名回退、注册表派生与一致性检查的用例（新增第三方示例 `RangeLimitedCutProvider`、`RectangleProvider`）：`ProviderContract` 由 22 项增至 38 项，且仍只从 staging 的 1.0.0 字节解析；API 34 模拟器上 `ProviderInstrumentation` 通过，含回退后的像素检查。R8 黑盒模式的 MainActivity 未改动，未在设备上重跑。
- ABI：`docs/abi/1.0.0/` 为新基线；与 `0.1.0-dev.4` 的 API 文本相比只有新增，没有删除或改签名。这只是文本比较，不是旧字节码不重编译运行的兼容证明。
- Central 上传包：见下面“桥接模块”一节。core 与 android 的 1.0.0 已在 Central 上，不再进上传包；core 的新字节作为 1.0.1 单独记录。
- 版本号 `1.0.0` 不改变 API 的实际成熟度：provider SPI 仍标为实验性，`README.md` 仍写 API provisional，G0–G6 门禁未通过，见 `STATUS.md`。

## 桥接模块，2026-10-29

新增四个可选制品：`meguviz-drawable-compose`、`meguviz-drawable-meguviz-android`、`meguviz-drawable-meguviz-ui`、`meguviz-drawable-lumen`，
以及 core 的 `BindingScope.claim`（独占属性 key）。接口对齐表、上游版本与字节、观测到的上游行为与限制见 [BRIDGES.md](BRIDGES.md)。

- 构建：新增 `-Pmeguviz.profile=bridges`（包含 core、android 与四个桥）；`host` 与 `android` 档位的产物不含 Kotlin（构建类路径会解析 Kotlin 插件；JitPack 仓库只限定 Lumen 的坐标）。
  引入 Kotlin 插件后，core JAR 与 android AAR 的字节与引入前逐字节一致（`6115921e…`、`e9a74e01…`）。
- 宿主 JVM：`CoreContractTest` 20,235 项；`MeguvizBindingTest` 15 项（真实 MeguvizAndroid 运行时，手动驱动的线程与帧源）。
  `bridges` 档位的 `check`（含全部模块 lint 与单元测试）、`android` 与 `host` 档位的 `check` 均通过。
- API 34 模拟器单台，针对最终字节：基础库 52 项合同与 29 项绑定合同；Compose 89 项（含 PixelCopy 硬件窗口对照）；
  MeguvizAndroid 桥在真实 `MainThreadDispatcher`/`ChoreographerDriver`/`ResourceScope` 上 17 项；Lumen 45 项；MeguvizUI 58 项。
  每个桥都做过一次删除关键逻辑的变异，合同随之失败，已还原。
- 可复现性：`.gitattributes` 把 `LICENSE`、`*.java`、`*.kt` 固定为 LF（它们被原样打进 `META-INF/LICENSE` 与 `-sources.jar`）。此后在本机工作树与全新 `git clone` 中分别构建，六个模块的全部制品（jar/aar、sources、javadoc、POM、Gradle metadata）逐字节一致；此前一次对照暴露了换行符与残留空目录造成的差异，已修正。
- 独立 consumer 与 provider fixture 针对新 core 字节重新通过（两条解析路径、R8 release、`ProviderContract` 38 项）。
- 未验证：API 23–33 上的桥、物理设备（本轮未连接）、R8 后的桥行为、独立 consumer 对桥制品的消费、Lumen 的其他版本。

## 版本整理与 Central 上传包，2026-10-29

- 发现：Central 上已有 core 与 android 的 1.0.0（此前上传），其中 core 是加入 `BindingScope.claim` 之前的字节。因此：core 版本改为 1.0.1
  （当前字节 `26d0eb75fb7ffe0e68449183c70920cb4a74de025e92bb30a15edd2327d5985a`，仅比 1.0.0 多 `claim`），android 保持 1.0.0；独立 consumer 与 provider fixture
  的 core 坐标随之改为 1.0.1 并重新通过；`tools/verify_candidate_licenses.py` 改为按模块读取各自版本。
- ABI：`docs/abi/1.0.0/core-api.*` 现在取自 Central 上的 1.0.0 jar（SHA-256 与上面一致），`docs/abi/1.0.1/core-api.*` 取自当前字节；1.0.0 到 1.0.1 只有新增（`BindingScope.Claim` 与 `claim`），没有删除或改签名。
- 本地 android 1.0.0 候选的 POM 因 core 变为 1.0.1 而与 Central 上的 android POM 不同，该候选不上传；aar 字节与 Central 一致。
- 三个新桥（compose、meguviz-android、meguviz-ui）不使用 `claim`。针对 Central 上已发布的 android/core 1.0.0 构建（`-Pmeguviz.againstPublished=true`）：
  `check`（含 lint）通过，`MeguvizBindingTest` 15 项通过，三个桥的设备合同在 API 34 模拟器上通过（Compose 89 项、MeguvizAndroid 17 项、MeguvizUI 58 项）。
- 上传包 `build/central/meguviz-drawable-bridges-1.0.0-central-bundle.zip`：compose、meguviz-android、meguviz-ui 三个模块，90 个文件，
  SHA-256 `fd926fcdc806215184de2175be31fc2311bf583b3cecd6611898de22c359cfd4`。`verify_central_bundle.py --check-unpublished` 通过（含 gpg 验签；三个组件在 Central 上均不存在）；
  POM 依赖都是已发布坐标：`meguviz-drawable-android:1.0.0`、`meguviz-android-runtime:1.0.0`、`meguviz-ui-core:1.0.0`，Compose 由 BOM 2026.09.00 管理。
  命名空间 `io.github.jichuo1` 是否已在 Central Portal 验证，以及上传本身，都未在此验证。
- 同一次核对还发现，原先的五模块包会被 Portal 拒绝（core、android 的 1.0.0 已存在），已删除；校验脚本新增 `--check-unpublished` 以便今后提前发现。

## FlightCache 与 1.1.0，2026-10-29

配合 BIL 性能报告 P1-03（共享生成、非阻塞冷未命中），新增 `FlightCache`（core）与 `MainThreadPoster`、`BitmapWeigher`（android），说明与接入方式见 [FLIGHT_CACHE.md](FLIGHT_CACHE.md)。

- 版本：上面提到的 core 1.0.1 从未发布，已被取代。core 与 android 都只新增 API，按语义化版本升到 **1.1.0**（均未发布）；Central 上的 1.0.0 不变。
  `docs/abi/1.0.1/` 作废删除，新增 `docs/abi/1.1.0/`；相对 Central 上的 1.0.0，core 与 android 的 API 文本都没有删除或改签名。独立 consumer 与 provider fixture 的坐标随之改为 1.1.0。
- 宿主 JVM：`CoreContractTest` 20,366 项（其中 `FlightCacheContractTest` 覆盖并发合并、取消与复活、代际、失败、上限、淘汰、`computeNow`、`close`、执行器与投递拒绝、`abandonRunning`、线程池队列移除、
  8 线程 × 20 轮竞争只生产一次、4 线程随机负载下三个上限从不被突破）。连续重跑 5 次稳定；两处关键逻辑（同键合并、取消后不投递）的变异都使合同失败，已还原。
- API 34 模拟器（单台）：`cache` 模式 14 项（真实线程池、主 Looper、Bitmap tile：两个 Scene 共用一次生产且在主线程回调；取消与过期订阅者不被调用；字节上限按分配字节淘汰）；基础库 52 项合同与 29 项绑定合同仍通过。
- 所有档位的 `check`、针对已发布 android 1.0.0 构建的三个桥的 `check`、独立 consumer 与 provider（两条解析路径、R8、`ProviderContract` 38 项）通过。
- 已签名的 Central 上传包（三个桥）不受影响：用当前代码重新构建这三个模块，15 个载荷文件与包中的逐字节一致，`--check-unpublished` 仍通过。
- 评审中发现并修复：执行器拒绝任务时已加入的订阅者没有回调；被通知停止的运行中飞行仍可被新请求加入；`Flight.task` 的跨线程可见性。已知并记录的限制：`abandonRunning` 下被替换的运行中飞行可短暂超出飞行上限。
- 未验证：真机与硬件 Canvas 路径；BIL 实际替换后的帧耗时（属 BIL 侧测量）。

## 首轮性能清理与异步准备示例（进度候选），2026-11-04

本节对应进度候选，尚未发布到 Maven Central；公开 API 不变，`docs/abi/1.1.0/` 不需要重新生成（无签名增删）。协议、夹具与完整
数据见 [PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)；分配基准的仓库外一次性夹具已固化为
`benchmark/FlightCacheAllocationProbe.java` + `benchmark/run-flightcache-probe.ps1`。

- 改动（core/android，无生产 API 变化）：`FlightCache.request` 的命中与拒绝路径不再预先创建订阅者
  记录与 `unqueue` 列表（加入路径仍创建订阅者记录——它登记到飞行上；`unqueue` 仅在启动飞行或为腾位
  丢弃被放弃飞行时创建）；`dropOneAbandoned` 改为返回被丢弃的飞行，保留"丢弃了飞行"与"task 尚未
  赋值"的区分。`RecipeDrawable.onBoundsChange` 复用字段级 scratch 数组；`setTintList`/`setTintMode`
  增加身份比较守卫，相同实例重复设置不再触发 `updateTint()` 与 `invalidateSelf()`。
- 宿主 JVM：`gradlew --offline --no-daemon check`，`CoreContractTest` 20,405 项通过（改动前本地基线
  20,366 项；新增 8 项 FlightCache 回归与 31 项异步准备示例合同）。新增回归：
  `rejectionPathLeavesNothingToCancel`（拒绝路径不登记订阅者：被拒 Ticket 无可取消、订阅者不被调用、
  `cancelledSubscribers` 不增）；`droppedFlightWithoutAssignedTaskStillMakesRoom`（反射构造"排队飞行
  task 未赋值"的纳秒级竞态窗口，验证仍腾位而非拒绝、task 为 null 不入队移除）。
- 异步准备示例（`core/src/test/java/.../AsyncPreparationContractTest.java`，纯测试作用域，复用
  `BindingScope.begin/commit`、`FlightCache.request`、`Subscriber.isCurrent`，无新线程/池/公有 API）：
  READY 命中立即安装不等回调；每 slot 至多一个 current 提交，完成/失败/拒绝即清理（50 次重复提交后
  `pendingSubmissions()==1`）；失败与拒绝可观测（不吞）；同 key 替换先 join 新再 cancel 旧，单飞不被
  自己的替换杀死；仅 owner 线程安装（外部线程投递被 scope 拒绝）；取消/关闭只释放订阅，共享缓存值仍可
  `peek`，releaser 不销毁共享 V。
- Android profile：`gradlew --offline --no-daemon -Pmeguviz.profile=android check :android:assembleDebugAndroidTest`
  （SDK 经本次命令环境变量定位，未写 `local.properties`），编译、lint（无新增错误）与设备测试 APK 组装
  通过；`ContractInstrumentation.filtersAndAlpha` 新增 tint 守卫断言（同实例重复设置 `invalidations==0`
  且像素不变；换实例/换 mode/清空各重绘一次）。设备测试 APK 只编译，未安装、未运行。
- Bridges profile（离线，SDK 经本次命令环境变量定位）：`gradlew --offline --no-daemon -Pmeguviz.profile=bridges check`
  BUILD SUCCESSFUL，六模块编译/lint/宿主测试（含 `MeguvizBindingTest`）通过。范围：本地 core/android 加
  已发布上游制品（`meguviz-ui-core`/`meguviz-android-runtime` 1.0.0、JitPack 缓存的 `lumen-engine` 1.5.5），
  不是三库端到端设备验证。桥仅为 opt-in profile，host 档 `check` 只包含 `:core`；core/android/FlightCache/
  BindingScope/异步示例不依赖任何 UI/Android 兄弟库，任一库可脱离体系独立使用。
- 分配（宿主 JVM，`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`，固化探针，7 轮中位数；
  before 为固定提交 `2690458` 还原构建、同一探针二进制、同一夹具；before 每视图 1 fork、after 默认视图 3 fork、
  raw 视图 2 fork，观测一致）：命中 raw 104.000 → 32.000 B/次（默认视图前后均 32.000，C2 已标量替换
  非逃逸对象）；加入两种视图均 104.000 → 56.000；拒绝 raw 136.000 → 64.000、默认 104.000 → 32.000；
  `peek` 全程 0.000。这是分配计数，不是帧耗时，也不是 Android/ART 收益。
- 设备：有设备在线但未获用户安装授权，未安装、未运行。`contracts`/`binding`/`cache`/`protocol` 等设备
  模式待授权后执行；未跑通前不声称像素或设备行为结论。
- 外部检索通道本轮不可用（Exa 返回 401）；上游引用（Go singleflight、Android Drawable 官方文档）均来自
  直接阅读，不称外部研究。
- 已修复（2026-11-05）：`request` 对执行器抛出的任何 `RuntimeException`（含 `RejectedExecutionException`）
  与 `Error` 都会回滚飞行；`executorRefusal` 继续钉住 REE 路径，新增 `executorFailureIsNotARefusal`
  钉住非 REE 路径（异常传播、`flights == 0`、`peek == null`、加入者收到失败）。默认 `contractTest`
  109 项、`contractStress` 447,189 项通过；未修复版本在同一用例上失败（`flights=1`）。
- 未验证：真机与硬件 Canvas 路径；R8 后行为；BIL 实际替换后的帧耗时（属 BIL 侧测量）。

## 二轮算法与默认检查精简，2026-11-04

- 新增显式 `StateMatcher.prepare(rules)` 与可复用 `PreparedMatcher`，默认 direct matcher 和
  `RecipeDrawable` 不自动预编译；K、规则数及建表预算超限均直接回退。细节与准备成本见
  [状态匹配预编译](STATE_MATCHER_PREPARED.md)。未发布的 1.1.0 ABI 文本与 JSON 已从实际 staging
  候选成套生成并复核：仅新增 prepare/PreparedMatcher，Central 1.0.0 基线未改。
- `FlightCache` 终态清理订阅者、飞行与生产者引用，READY/拒绝票据不保存 cache；关闭后尚未交付的
  已投递结果丢弃并计 `droppedStale`。STARTED/JOINED 票据仍可能经 owner 保留 cache，未宣称完全
  脱引用；确定性断言与其余限制见 [票据引用释放](FLIGHT_RETENTION.md)。
- 用户要求进一步减少日常检查量后，默认 `gradlew --offline --no-daemon check` 实际通过 **106 项**：
  核心状态/几何 15、绑定 34、状态匹配差分 16、票据引用 7、FlightCache 11、Provider 13、异步准备 7、
  补充模型/插槽 4。没有聚合断言或仅修改打印；同场景的关联断言合并为单个 `check`，并把次要场景
  移入显式 stress 入口。绑定、Provider、票据、取消、关闭、拒绝与所有权独特断言全部保留。
- 大语料与次要场景保留为显式 `gradlew --offline --no-daemon :core:contractStress`，不被默认 check
  拉入；默认总数若达到 150 会失败并要求重新划分语料，防止测试再次无界增长。stress 入口复核为
  447,186 项，不能把该数当日常默认量。
- 精简后 `-Pmeguviz.profile=bridges check :android:assembleDebugAndroidTest` 通过：核心仍为
  106 项，Android 与四个可选桥的编译/lint/现有宿主合同通过，测试 APK 仅组装未安装。
- 精简后在隔离构建副本验证 4 个关键变异：首匹配改末匹配、忽略 state 的 0 终止符、建表预算
  `>` 改 `>=`、删除订阅者终态清引用。四个变异均编译成功、均被默认合同识别并非零退出；原版
  默认合同仍为 106 项通过。没有修改正式生产源码，也未借压力语料补检出。
- 独立核心与可选桥边界不变：core 无 Android/兄弟库依赖，桥仍需显式选档；这些主机结果不代表
  真机像素、ART 性能或 R8 后设备行为通过。

## 联合改进第一轮，2026-11-08（分支 `perf/joint-r1`，未推送）

- 基线：`git archive b087b3f` 的干净副本上 `-Pmeguviz.profile=android check` 通过（默认 148 项）。新克隆因 `gradlew`
  缺可执行位失败（已修 `d6e2335`）。审计与全部发现见 [AUDIT_JOINT_R1.md](AUDIT_JOINT_R1.md)。
- 每个提交后：`-Pmeguviz.profile=android check :android:assembleDebugAndroidTest :core:contractStress`
  （JDK 21.0.12.1，`--offline`）通过；默认合同 145 项、stress 447,225 项。`740a7cb`、`ff4b26f` 另以单独检出 + `javac`
  复跑确认提交本身可过合同。
- ABI：`docs/abi/1.2.0/core-api.*` 相对 1.1.0 只有新增；`LegacyCoreClient`（针对 Central 1.0.0 编译）在新 jar 上 PASS。
- 性能：模型 hash/分配与 `FlightCache` 命中路径的宿主测量见 [PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md) 2026-11-08 两节。
- 未运行：任何设备/模拟器测试、`bridges` 档位（上游 1.2.0 制品不可得）、R8。
