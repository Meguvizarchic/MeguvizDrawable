# 发布到 Maven Central

## 已发布与待发布

| 制品 | 版本 | 状态 |
|---|---|---|
| `meguviz-drawable-core` | 1.0.0 | **已在 Central**（2026-10-28），字节 `6115921e…`，不含 `BindingScope.claim` |
| `meguviz-drawable-android` | 1.0.0 | **已在 Central**，字节与本仓库当前构建逐字节一致（`e9a74e01…`） |
| `meguviz-drawable-core` | 1.2.0 | 未发布；仓库当前的 core，比 1.0.0 多 `BindingScope.claim`、`FlightCache` 与 `StateMatcherCache`，没有删除或改签名（1.1.0 从未发布，已被取代） |
| `meguviz-drawable-android` | 1.2.0 | 未发布；比 1.0.0 多 `MainThreadPoster`、`BitmapWeigher`，没有删除或改签名（1.1.0 从未发布，已被取代） |
| `-compose`、`-meguviz-ui` | 1.1.0 | 待发布；`-meguviz-ui` 含 `reclaim()` 与密度变化重建修复（1.0.0 未发布，已被取代） |
| `-meguviz-android` | 1.1.0 | 待发布；依赖 `meguviz-android-runtime` 1.2.0，须等它先上 Central |
| `-lumen` | 1.1.0 | 不进 Central：依赖只发布在 JitPack 的 `lumen-engine` |

Central 的发布不可替换、不可撤销。各模块独立定版，版本取自它自己的 `build.gradle.kts`：已发布的版本号不再改字节，
有变化就升版本（core 与 android 新增 API，按语义化版本升级，当前为 1.2.0）。`meguviz-drawable-lumen` 依赖 `claim`，因此显式依赖 core，待 Lumen 上 Central 且 core 1.2.0 发布后再纳入。

## 生成上传包

前提：JDK 17、Android SDK、联网（解析 Compose、MeguvizAndroid、MeguvizUI 与已发布的基础库）、系统 `gpg` 中有签名密钥且公钥已发布到 keyserver。
签名口令由 gpg-agent 弹窗询问，不进入构建参数；必须在有人操作的终端里运行，口令缓存过期后无人值守会在签名处超时。

```text
gradlew -Pmeguviz.profile=bridges -Pmeguviz.sign=true -Pmeguviz.againstPublished=true centralBundle
python tools/verify_central_bundle.py build/central/meguviz-drawable-bridges-<版本>-central-bundle.zip --check-unpublished
```

- `-Pmeguviz.againstPublished=true` 让三个桥针对 Central 上已发布的基础库构建，而不是工作树里的 `:android`/`:core`，
  这样它们不可能用到尚未发布的 API；`centralBundle` 在缺少该参数时拒绝运行。同样的参数也可用于 `check`，在上传前先跑一遍单测与 lint。
- Gradle 在制品字节没变时会复用上次已签好的 `.asc`（签名任务 UP-TO-DATE），此时不会弹出口令窗口。需要重新签名时：
  `gradlew … :compose:signCandidatePublication --rerun :meguviz-android:signCandidatePublication --rerun :meguviz-ui:signCandidatePublication --rerun centralBundle`（并先 `gpgconf --kill gpg-agent` 清掉口令缓存）。
- 不带 `-Pmeguviz.sign=true` 时 `centralBundle` 也会拒绝运行，避免产出无签名的包。
- 产物为 Maven 仓库布局的 zip；每个文件带 `.asc` 与 `.md5/.sha1/.sha256/.sha512`，包含 sources 与 javadoc jar。`maven-metadata.xml` 被有意排除，由 Portal 生成。
- 校验脚本检查签名、校验和、POM 必填字段与文件齐全；`--check-unpublished` 逐个组件向 Central 查询，若版本已存在就失败。
  它不检查命名空间是否已验证，也不检查公钥是否在 keyserver 上。

## 上传

在 Central Portal 的 Publish 页面上传该 zip，先选 User Managed 部署，确认校验通过后再手动 Publish。

## 上传前自查

- 在另一台机器或全新 clone 中构建应得到相同的制品字节（`.gitattributes` 固定了被打包文本的换行符）；两处 SHA-256 不一致说明输入不同，不要上传。
- 构建内容变化后须重新生成并重验：`docs/engineering/VERIFICATION.md` 中的 SHA-256 只绑定当时的字节。签名每次不同，包的 SHA-256 只标识那一份包。
- `docs/abi/<版本>/` 与独立 consumer/provider fixture 用同一份 staging 候选生成；已发布版本的 ABI 记录必须取自 Central 上的字节。
- 版本号不等于成熟度：对外声明的稳定范围以 `STATUS.md` 与 README 为准。
