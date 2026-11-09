# 工程状态

更新时间：2026-11-09（发布状态以本段和 RELEASE.md 为准；下表与设备验证段是 2026-10-28 的快照）。

当前阶段：核心与 Android 基线功能验证和性能试验进行中。`meguviz-drawable-core` / `-android` 的 1.0.0 已发布到 Maven Central 且不可变；1.1.0 从未发布、已被取代；当前 1.2.0 尚未发布到 Central，只以本地未签名候选（维护者联合工作区里的 `meguviz-bom:1.2.0`，不在任何公开仓库里）供跨库联调。详见 RELEASE.md。

| 门禁 | 状态 | 主要缺口 |
|---|---|---|
| G0 基线 | IN_PROGRESS | 依赖制品、运行轨迹和实验协议尚未完整核验 |
| G1 迁移 | IN_PROGRESS | BIL PR 已合入 `Advanced`；隔离候选的图、R8、debug APK、测试/lint 和控件对照已验证；固定签名 release APK、真实页面与 Hook 仍缺证据 |
| G2 纯 JVM | IN_PROGRESS | 完整模型、独立参考、扩展与缓存合同尚未齐备 |
| G3 Android 和绑定 | IN_PROGRESS | `dev.3` 静态快照/owner 协议有证据；`dev.4` 子项包装器通过 API 21/22/34 模拟器合同，真机及完整生命周期尚未完成 |
| G4 独立消费者 | IN_PROGRESS | 独立 consumer、实验性 provider 和有限旧字节码对照已推进；完整扩展与兼容性证据仍未完成 |
| G5 发布验证 | IN_PROGRESS | 独立复现页性能确认未证明预注册收益；真实 BIL 页面、更多设备与公开制品回读仍待验证 |
| G6 高级专项 | NOT_STARTED | 前置依赖、实验和设备验证尚未完成 |

扩展核心合同在宿主 JVM 通过 20,058 项检查；API 21/22 模拟器对提交 `9965c3c` 的 `dev.3` 各通过 52 项软件、22 项快照协议及独立 provider 合同，API 37 真机也通过相同软件及协议合同。API 37 硬件窗口对照了四个限定场景。独立 provider 只覆盖实验性多边形能力。该段为 2026-10-28 的历史快照；绑定所有权与 provider 能力/回退实现已在 2026-11-08 的 `perf/joint-r1` 分支上提交，并形成了 1.2.0 本地候选制品（见 RELEASE.md）。另有 Compose、MeguvizAndroid、MeguvizUI、Lumen 四个可选桥接制品，仅在宿主 JVM 与单台 API 34 模拟器上验证，见 [BRIDGES.md](BRIDGES.md)；G4 的“可选适配”条目不因此通过。另有共享单飞缓存 `FlightCache`（core/android 1.2.0，Central 未发布；1.1.0 从未发布），见 [FLIGHT_CACHE.md](FLIGHT_CACHE.md)。覆盖范围与性能试验见 [VERIFICATION.md](VERIFICATION.md)，这些结果不足以证明任何完整门禁通过。2026-11-08 起性能原始 CSV 已入库（`docs/engineering/evidence/runs/`）；更早的原始输入与机器相关日志仍只保留在本地。

下一步：扩充 API 版本与硬件路径覆盖，完善状态、几何和生命周期对照，核验生产依赖图并完成独立制品消费者。性能候选须包含真实工作负载和准备成本后再裁决。
