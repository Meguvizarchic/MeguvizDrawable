# 验证记录

本文按时间顺序记录 `MeguvizDrawable` 从早期基线至 `1.2.0` 演进期间在宿主 JVM、独立消费夹具及部分模拟器/真机上的实际校验结果。（注：下文中提及当时“未发布”的 `1.2.0` 模块，除 `meguviz-drawable-lumen` 之外现均已正式发布至 Maven Central。）

## 1. 基线与早期扩展验证（2026-10-28）

- **宿主基线**：提交 `4c5dd54`（JDK 17、Gradle 9.8.2）运行 `gradlew --offline --no-daemon check --rerun-tasks` 退出码 `0`，`CoreContractTest` 通过 `20,018` 项检查；构建脚本禁用空跑的默认 `test` 任务，由 `check` 显式挂载 `:core:contractTest`。
- **扩展核心与 Android Profile**：宿主通过 `20,058` 项检查（涵盖状态匹配、防御复制、参数隔离与绑定所有权）；`-Pmeguviz.profile=android check` 编译与 Lint 通过；API 37 单设备通过 `29` 项软件 Canvas 与状态隔离 instrumentation 检查。
- **局部性能试验**：对重复相同状态的 `setState` 短路优化，在单台 API 37 debug 构建（7 个独立 PID × 12 组交替配对 × 每组 50 万次）中，各进程单次调用中位数由 `100.829–151.185 ns` 降至 `7.976–11.967 ns`（降幅 `91.63%–92.10%`）；宿主 JVM 10 进程实验查表相比直接匹配中位降幅 `68.487%`（表载荷 `1,024 B`）。

### 设备验证复现命令

```text
# 构建测试 APK（需配置 Android SDK）：
./gradlew.bat --offline --no-daemon '-Pmeguviz.profile=android' :android:assembleDebugAndroidTest

# 在目标设备执行：
adb -s <device> shell am instrument -w -r io.github.jichuo1.meguviz.drawable.contracttest/io.github.jichuo1.meguviz.drawable.android.test.ContractInstrumentation
```

性能基准模式可在组件名前追加 `-e mode benchmark`，多进程独立采集并保留各自 PID 与原始输出。

## 2. 发布候选、`0.1.0-dev.*` 与 `1.0.0` 基线（2026-10-28）

- **`0.1.0-dev.1`**：API 34 模拟器与 API 37 真机各通过 `52` 项软件 Canvas 合同；`fixtures/consumer` 验证 Gradle metadata / POM 解析与 `R8` 构建，确认未自动注入 Kotlin 标准库（core JAR SHA-256 `280635cf5e23b9c82be44195bdb8c2dcbb493b388e6071b63bd8f3de132f373e`；Android AAR SHA-256 `bce48e151df9ac37f591080a2dc625d39d106fe553ffbcb40db6454411304272`）。
- **`0.1.0-dev.2`**：引入实验性闭合多边形 provider（`22` 项宿主合同、独立 `ProviderContract` 与 `R8` 构建）；API 21/22 云端模拟器（提交 `48999e0`）通过 `52` 项软件合同与独立 provider 合同；API 37 硬件加速窗口 PixelCopy 逐像素对照 4 组场景（渐变 `6,084` 像素、不对称圆角 `6,400` 像素、`checked`/`selected` 各 `6,400` 像素）与平台参考一致（core JAR `51b17b45e0397b5b322b1382bb73e42bb7e7a91199e538eb79e31031a352c9ba`；Android AAR `48ad5815a27ea0e2844a5bc50cdc869d3a6963ea17735a5db47e28353be6424f`）。
- **`0.1.0-dev.3` / `dev.4` 与 BIL 依赖退出**：`dev.3` 增加无 tint/滤镜时的 `ConstantState` 支持（API 37 通过 `22` 项协议、`52` 项软件与 `60` 项硬件合同；API 21/22 提交 `9965c3c` [CI 运行](https://github.com/jichuo1/MeguvizDrawable/actions/runs/40688326056) 通过；Android AAR `8335d055955304e1dab311a505a236860a5d849ec48db05e0a9970c2ef07260f`）。BIL 依赖移除见 [隔离补丁与 PR](../../integration/bil/README.md)（PR #39 合入 `Advanced` 后 [CI 通过](https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/40688542546)，unitTest `4,116` 项通过、`1` 跳过、`0` 失败）。`dev.4` 新增 `OwnedDrawableWrapper`（[API 21/22 矩阵](https://github.com/jichuo1/MeguvizDrawable/actions/runs/40692606779) 各通过 `36` 项包装器合同，API 34 通过 `37` 项）。独立页对比实验（`benchmark/page/CONFIRMATION.md`，API 37 约 120 Hz R8，64 View × 30 对 AB/BA）p95 帧耗差中位数 `-0.0758 ms`（95% 重采样区间 `[-0.5091, +0.0168] ms`），满足 0.5 ms 非劣化判据。
- **绑定所有权与 `1.0.0` Central 发布**：补充 `SlotBinding`、`BindingScope`、`ViewSlots`、`ViewLifecycleBinder` 与 SPI `ShapeProviderConformance`（宿主 `20,221` 项通过，API 34 `binding` `29` 项通过）。随后发布 `core`（SHA-256 `6115921e7289196c084e2d82185a71f448716e75c0d9bfe42c1987e9fba53a37`）与 `android`（`e9a74e017fcf0e46cee7593694aea639d316339a03b84a47d4e51baec6c4b0ac`）`1.0.0` 到 Maven Central，独立 `ProviderContract` 增至 `38` 项并在 API 34 通过。

## 3. 桥接模块与 `FlightCache` 演进（2026-10-29 至 2026-11-05）

- **四座桥接模块与可复现构建**：`-Pmeguviz.profile=bridges check` 覆盖全部 6 模块（`CoreContractTest` `20,235` 项、`MeguvizBindingTest` `15` 项）；单台 API 34 模拟器通过 Compose `89` 项（含 PixelCopy）、MeguvizAndroid `17` 项、Lumen 1.5.5 `45` 项、MeguvizUI `58` 项与基础库合同，并完成关键逻辑变异检验。`.gitattributes` 将打包源码换行固定为 `LF`，全新 `git clone` 构建出逐字节一致制品。
- **`FlightCache` 与票据保留/状态表编译**：`CoreContractTest` 增至 `20,366` 项并经连续 5 轮复跑与变异校验；单台 API 34 模拟器通过 `cache` 模式 `14` 项。2026-11-04 至 2026-11-05 完成惰性分配清理、执行器异常回滚（`executorFailureIsNotARefusal`、`posterErrorIsCountedAndDoesNotLeak`）、`StateMatcher.prepare` / `StateMatcherCache` 与票据终态清理（[FLIGHT_RETENTION.md](FLIGHT_RETENTION.md)），并把日常默认 `check` 精简为覆盖独特等价类的快速入口（`106`~`148` 项，经 4 组关键变异验证 100% 检出），全量随机/穷举大语料移入显式 `:core:contractStress`（`447,186`~`447,225` 项）。

## 4. 联合改进第一轮与便捷 API 补充（2026-11-08 至 2026-11-11）

- **联合改进第一轮（`perf/joint-r1`，现已合入 `main`）**：审计发现与修复见 [AUDIT_JOINT_R1.md](AUDIT_JOINT_R1.md)（`D-01`~`D-04`）。每个提交在 JDK `21.0.12.1` 下通过 `-Pmeguviz.profile=android check :android:assembleDebugAndroidTest :core:contractStress`（默认 `145` 项，stress `447,225` 项）；`docs/abi/1.2.0/core-api.*` 相对 `1.1.0` 仅新增签名，`LegacyCoreClient` 在新 JAR 上运行通过；Model 与 `FlightCache` 原始 CSV 已存档至 `docs/engineering/evidence/runs/`。
- **便捷一行调用（`2026-11-11`）**：新增 `RecipeDrawable.forView(view, recipe)` 与 `MeguvizBinding.create(lifecycle, resources)`。JDK `17.0.20.1` 下 `-Pmeguviz.profile=android check :android:assembleDebugAndroidTest` 通过（默认 `147` 项），`-Pmeguviz.profile=bridges :meguviz-android:check` 通过（`MeguvizBindingTest` `17` 项）；`check_abi_superset.py` 确认 `android` 模块包含 `1.0.0` 全部公开成员；Xiaomi `2210132C`（API 33）真机通过 `contracts` 模式（`CONTRACT PASS checks=79`，含 `forView` 6 项检查）。（注：`tools/run-api-contracts.sh` 内硬编码的 `checks=52` 仅反映早期 API 21/22 环境，暂未重新标定。）

## 适用范围与验证说明

- 文档中所列 SHA-256 校验值、检查条目数与局部基准耗时均严格绑定当时的源码提交与受测环境（宿主 HotSpot JVM、单台模拟器或特定测试机），产品源码变更后须重新生成并重验。
- 未覆盖完整矩阵的组合包括：API 23–26 下的 Compose 桥、API 27–33 下的桥接模块、多品牌真机 GPU 差异、`R8` 混淆后的桥接端到端行为与真实业务页面帧率，这些场景须在对应接入方工程与真机矩阵中独立验收。
