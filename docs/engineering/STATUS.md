# 工程状态

## 当前发布口径

- `meguviz-drawable-core`、`meguviz-drawable-android`、`meguviz-drawable-compose`、`meguviz-drawable-meguviz-android` 与 `meguviz-drawable-meguviz-ui` 的 `1.2.0` 制品及家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 均已发布至 Maven Central（`1.0.0` core/android 保持不可变历史版本，`1.1.0` 从未发布且已被 `1.2.0` 取代）。
- `meguviz-drawable-lumen`（依赖 `BindingScope.claim` 与 `lumen-engine`）暂未进入 Central，仅支持从源码或本地 staging 构建，详见 [RELEASE.md](RELEASE.md) 与 [BRIDGES.md](BRIDGES.md)。

## 历史工程门禁快照（2026-10-28）

下表记录 2026-10-28 阶段性门禁盘点状态；2026-11 以来已完成共享单飞缓存 `FlightCache`、状态表缓存 `StateMatcherCache`、四座可选桥接实现与 `1.2.0` 发布打包，后续演进验证记录见 [VERIFICATION.md](VERIFICATION.md)。

| 门禁 | 2026-10-28 快照状态 | 主要未覆盖项 |
| --- | --- | --- |
| G0 基线 | `IN_PROGRESS` | 依赖制品、运行轨迹和实验协议尚未完整核验 |
| G1 迁移 | `IN_PROGRESS` | 隔离候选依赖图、R8、debug APK 与控件对照已验证；固定签名 release APK、真实页面与 Hook 仍缺证据 |
| G2 纯 JVM | `IN_PROGRESS` | 完整模型、独立参考与缓存全矩阵持续补齐 |
| G3 Android 与绑定 | `IN_PROGRESS` | API 21/22/34/37 模拟器及部分真机合同已推进，真机全矩阵待补齐 |
| G4 独立消费者 | `IN_PROGRESS` | 独立 consumer/provider 与桥接制品的完整独立消费验证待补齐 |
| G5 发布验证 | `IN_PROGRESS` | 真实业务页面与多设备硬件加速对照待验证 |
| G6 高级专项 | `NOT_STARTED` | 专项渲染能力与净收益验证待启动 |

## 阶段性技术进展摘要

- **核心与 Android**：`CoreContractTest`（及可选 `:core:contractStress`）、`FlightCache`、`StateMatcherCache`、`RecipeDrawable.forView`、Model 哈希缓存与 `BindingScope` 均在宿主 JVM 通过对应合同验证；API 21/22/33/34/37 的部分模拟器与真机合同见 [VERIFICATION.md](VERIFICATION.md)。
- **桥接模块**：Compose（89 项，含 PixelCopy）、MeguvizAndroid（17 项）、MeguvizUI（58 项）与 Lumen 1.5.5（45 项）已在宿主 JVM 与单台 API 34 模拟器通过合同验证。
- **原始测量轨迹**：2026-11-08 起的性能探针原始 CSV 已纳入 `docs/engineering/evidence/runs/`；更早日志与本机环境记录仅保留在本地。

## 适用范围与验证说明

- 历史门禁快照与局部设备结果不代表全 API 版本（尤其 API 21–26 的真实真机硬件路径）或全量业务页面的完整回归通过；消费方升级或集成新版本时应结合目标设备执行集成与渲染验收。
