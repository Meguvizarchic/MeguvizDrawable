# 发布到 Maven Central

## 制品发布状态

| 制品 | 版本 | Central 状态 | 说明 |
| --- | --- | --- | --- |
| `meguviz-drawable-core` | `1.0.0` | **已发布（2026-10-28）** | 历史基线（不含 `BindingScope.claim`、`FlightCache`、`StateMatcherCache`） |
| `meguviz-drawable-android` | `1.0.0` | **已发布（2026-10-28）** | 历史基线（不含 `RecipeDrawable.forView`、`MainThreadPoster`、`BitmapWeigher`） |
| `meguviz-drawable-core` | `1.2.0` | **已发布** | 纳入 `meguviz-bom:1.2.0`；新增 `BindingScope.claim`、`FlightCache`、`StateMatcherCache`、`DrawingRecipe.of`/`withStroke`/`withMetrics` |
| `meguviz-drawable-android` | `1.2.0` | **已发布** | 纳入 `meguviz-bom:1.2.0`；新增 `RecipeDrawable.forView`、`MainThreadPoster`、`BitmapWeigher` |
| `meguviz-drawable-compose` | `1.2.0` | **已发布** | 纳入 `meguviz-bom:1.2.0` |
| `meguviz-drawable-meguviz-android` | `1.2.0` | **已发布** | 纳入 `meguviz-bom:1.2.0`；依赖 `meguviz-android-runtime:1.2.0` |
| `meguviz-drawable-meguviz-ui` | `1.2.0` | **已发布** | 纳入 `meguviz-bom:1.2.0`；含 `reclaim()` 与密度变更重建；依赖 `meguviz-ui-core:1.2.0` |
| `meguviz-drawable-lumen` | `1.2.0` | **未入 Central（仅源码构建）** | 仓库当前 `gradle/libs.versions.toml` 固定 JitPack `lumen-engine:1.5.5`；Central 另已发布独立 `io.github.jichuo1:lumen-engine:1.6.2` |

> **版本规则**：Maven Central 已发布的版本不可修改或覆盖（`1.1.0` 从未发布，已被 `1.2.0` 取代）。后续如有 API 或字节变化，必须递增各子模块 `build.gradle.kts` 中的版本号（例如 `1.2.1`），再生成新的上传包。

## 生成后续发布上传包（示例：`1.2.1+`）

前提：JDK 17、Android SDK、可访问依赖仓库的网络环境、系统 `gpg` 配置发布密钥且公钥已上传 keyserver。签名口令由 `gpg-agent` 弹窗询问，禁止写入构建参数。

```text
gradlew -Pmeguviz.profile=bridges -Pmeguviz.sign=true -Pmeguviz.againstPublished=true centralBundle
python tools/verify_central_bundle.py build/central/meguviz-drawable-bridges-<版本>-central-bundle.zip --check-unpublished
```

- `-Pmeguviz.againstPublished=true` 强制桥接模块针对 Central 上已发布的基础库构建，避免误引用工作树未发布 API；缺少该参数或 `-Pmeguviz.sign=true` 时 `centralBundle` 会拒绝运行。
- 若字节未变，Gradle 会将签名任务标为 `UP-TO-DATE`；需要强制重签时，先清理 `gpg-agent` 缓存并加上 `--rerun` 执行签名任务。
- 校验脚本 `tools/verify_central_bundle.py` 会检查 `.asc`、`md5/sha1/sha256/sha512`、POM 必填字段与源码/Javadoc JAR；`--check-unpublished` 会向 Central 查询确认新版本尚未存在（不校验命名空间归属与公钥服务端同步）。

## 上传与核对

在 Central Portal 的 Publish 页面选择 `User Managed` 上传 zip，确认检查通过后手动点击 Publish。

## 适用范围与验证说明

- 打包上传前须在干净构建环境中复现制品字节（`.gitattributes` 已固定打包源码换行符），并核对 `docs/abi/<版本>/` 及独立消费夹具。
- 版本号不代表全矩阵设备门禁均已通过；各模块实际的兼容、设备验证范围见 [STATUS.md](STATUS.md) 与 [VERIFICATION.md](VERIFICATION.md)。
