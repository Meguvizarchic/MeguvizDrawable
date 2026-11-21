# 工程基线

基线快照时间：2026-10-28（后续 `1.2.0` 发布与演进状态见 [RELEASE.md](RELEASE.md) 与 [STATUS.md](STATUS.md)）。

## 架构与构建基线

- 独立 `:core` 模块编译为 Java 8 字节码目标，根工程使用 JDK 17 与 Gradle Wrapper 9.8.2；Android 与可选桥接模块通过 `-Pmeguviz.profile=android|bridges` 显式引入。
- `:core` 与 `:android` 零第三方及 Kotlin 运行时依赖；状态匹配采用有序规则首匹配、正负条件、零终止与显式无匹配（`fallbackColor`）；`CornerRadii` 采用统一公共缩放比例。
- `gradlew check` 运行纯 Java 可执行合同测试；`-Pmeguviz.profile=android check` 额外覆盖 Android 构建与 Lint；设备测试与性能探针为独立入口。
- 源码采用 Apache License 2.0 授权；当前 `1.2.0`（`:core`、`:android`、`:compose`、`:meguviz-android`、`:meguviz-ui` 及家族 BOM `meguviz-bom:1.2.0`）已正式发布至 Maven Central。

## 适用范围与验证说明

- 基线构建与 `check` 结果仅覆盖宿主环境编译与合同校验；各 API 版本覆盖、真机渲染与性能结论须以 [VERIFICATION.md](VERIFICATION.md) 中对应命令、设备和环境记录为准，本地机器相关日志不随仓库提交。
