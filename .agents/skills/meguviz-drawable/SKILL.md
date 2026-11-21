---
name: meguviz-drawable
description: 在 Android 或纯 Java 工程中接入 MeguvizDrawable 1.2.0（DrawingRecipe、RecipeDrawable、rememberRecipePainter、FlightCache、BindingScope、ViewSlots、MeguvizBinding、UiSession.bindRecipeBackground），或将 DrawableToolbox、手工 GradientDrawable/StateListDrawable 与无界位图缓存迁移为不可变绘制配方与作用域绑定。涉及窗口与 Fragment 生命周期调度时转交 MeguvizAndroid，涉及完整原生 View 树与布局 DSL 时转交 MeguvizUI，涉及玻璃 blur/折射材质与手势动效时转交 LumenCoacervationEngine。
---

# MeguvizDrawable 接入与迁移

面向需要以不可变配方统一绘制 Android View 背景、Compose Painter 与共享结果缓存，或替代存量 `GradientDrawable` / `DrawableToolbox` / 自建缓存的工程，按以下四个阶段完成评估、接入、迁移与验证。

## 第 1 阶段：盘点工程现状

1. **检查构建配置与平台下限**
   - 读取 `settings.gradle(.kts)`、`build.gradle(.kts)`、`gradle/libs.versions.toml` 及模块 `AndroidManifest.xml`。
   - 核对 SDK 基线：所有 Android 模块均要求 `compileSdk 37` 或更新版本；各制品 `minSdk` 不同：
     - `meguviz-drawable-core`：纯 Java 8 JAR（无 Android 或 Kotlin 依赖）。
     - `meguviz-drawable-android`：`minSdk 21`，纯 Java 8（不引入 Kotlin 运行时）。
     - `meguviz-drawable-compose`：`minSdk 23`，Java 17 + Kotlin `2.4.21`（需匹配 Compose BOM）。
     - `meguviz-drawable-meguviz-android` 与 `meguviz-drawable-meguviz-ui`：`minSdk 27`，Java 17（`meguviz-ui` 桥含 Kotlin `2.4.21`）。
     - `meguviz-drawable-lumen`：`minSdk 27`，Java 17，**尚未发布至 Maven Central，仅支持源码或本地 staging 构建**。
2. **检索存量绘制与缓存代码**
   - 替换对象：XML `<shape>` / `<selector>`、手工 `GradientDrawable` / `StateListDrawable`、第三方 `DrawableBuilder`（如 DrawableToolbox）、自建 `LruCache<Key, Bitmap>` 背景缓存。
   - 核对关键差异：是否依赖 `elevation` 阴影或 `View.setClipToOutline(true)`（`RecipeDrawable.getOutline` 故意返回空轮廓），是否依赖 `StateListDrawable` 的二次通配兜底，以及是否需要保留 XML 预设的 View `padding`。
3. **输出评估摘要后再改代码**
   - 明确目标宿主类型（纯 View、Compose、或已使用 MeguvizAndroid / MeguvizUI），确定最小依赖集合与替代顺序。

## 第 2 阶段：选择模块与接入依赖

启用 `google()` 与 `mavenCentral()`。以下 5 个制品已发布到 Maven Central `1.2.0`（`groupId`：`io.github.jichuo1.meguviz`），均由 `meguviz-bom:1.2.0` 统一管理版本：

| 接入场景 | 选用模块 | 最低 SDK 与说明 |
| --- | --- | --- |
| 纯 Java 配方模型、状态编译、`FlightCache`、`BindingScope` | `meguviz-drawable-core` | JVM 8+；纯 Java 零第三方依赖 |
| Android View `RecipeDrawable`、`ViewSlots`、`MainThreadPoster` | `meguviz-drawable-android` | `minSdk 21`；自动传递引入 `core`，不引入 Kotlin/Compose |
| Jetpack Compose `rememberRecipePainter` / `DrawableState` | `meguviz-drawable-compose` | `minSdk 23`；底层仍复用 `RecipeDrawable` |
| 对接 `MeguvizAndroid` 生命周期与有界邮箱 | `meguviz-drawable-meguviz-android` | `minSdk 27`；须工程已接入 `meguviz-android-runtime` |
| 对接 `MeguvizUI` 属性绑定系统 | `meguviz-drawable-meguviz-ui` | `minSdk 27`；须工程已接入 `meguviz-ui-core` |

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-drawable-android")
    // Compose 宿主按需加入：
    // implementation("io.github.jichuo1.meguviz:meguviz-drawable-compose")
}
```

**边界与转交原则**：不接入未使用的桥接库。纯 View 项目禁止误引 `-compose`、`-meguviz-android` 或 `-meguviz-ui`；需要窗口/Fragment 生命周期管理转交 `$meguviz-android`；需要整套声明式控件与布局 DSL 转交 `$meguviz-ui`；需要玻璃模糊/折射材质与手势动效转交 `$lumen-coacervation-engine`。

## 第 3 阶段：执行最小迁移与所有权对齐

### 1. View 背景与 Compose Painter 最小写法

```java
// View 场景：DrawingRecipe 不可变可跨线程共享；RecipeDrawable 每 View 独占
DrawingRecipe card = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(16)), FillSpec.solid(0xFFFFFFFF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x1F000000));
view.setBackground(RecipeDrawable.forView(view, card));
```

```kotlin
// Compose 场景：状态变化更新同一 painter，recipe / density / mirrorInRtl 变化才重建
val painter = rememberRecipePainter(recipe = card, state = DrawableState(pressed = isPressed))
```

### 2. 保护 View Padding 与控制背景所有权

当需要保留 View 原有 padding 或防止误覆盖业务临时设置的自定义背景时，使用 `BindingScope` + `ViewSlots`：

```java
BindingScope scope = new BindingScope(1L);
scope.bind(new BindingEnvironment(1L, 1L));
SlotBinding<Drawable> slot = scope.slot(
        "background", ViewSlots.background(view, ViewSlots.PaddingPolicy.PRESERVE_HOST), null);
slot.ensure(view.getBackground(), card, 1L, () -> RecipeDrawable.forView(view, card));
```

### 3. 单飞共享缓存 `FlightCache` 必守规则

```java
FlightCache.Ticket<Bitmap> ticket = cache.request(key, producer, subscriber);
if (ticket.status() == FlightCache.Status.READY) {
    // 命中缓存时不会触发 subscriber.onReady，必须同步读取 ticket.value()
    applyBitmap(ticket.value());
}
```

### 4. 生命周期、所有权与渲染铁律

1. **所有权隔离**：`RecipeDrawable`、`RecipePainter`、`BindingScope` 与 `SlotBinding` 均仅限创建线程使用且不可跨 View 共享；接有 `MeguvizAndroid` 或 `MeguvizUI` 时使用 `MeguvizBinding.create(...)` 或 `uiSession.bindRecipeBackground(ref)` 管理失效与清理。
2. **密度与轮廓限制**：`RecipeDrawable.forView` 仅在创建时读取一次 View 资源密度，配置变化后须重建（`rememberRecipePainter` 与 `UiSession.bindRecipeBackground` 会自动感知）。非零 `opticalInsets` 会抛出异常；`getOutline` 固定返回空轮廓（无默认 `elevation` 阴影与 `clipToOutline`）。
3. **状态规则与超重值**：`StateColorList` 按声明顺序首个匹配（无 AOSP 二次通配），无匹配用 `fallbackColor`。`FlightCache` 中单条权重大于 `maxWeight` 的值会直接交付但不进缓存、`version()` 不递增；组件离开界面时务必在主线程调用 `ticket.cancel()` 与 `scope.close()`。

> **按需深入阅读**：完整模块坐标、公开 API 签名与家族桥接说明见 [API 与模块速查](references/api-and-modules.md)；旧库替换对照、异步准备与排障方案见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方工程验证**
   - 依赖检查：`./gradlew :app:dependencies --configuration debugRuntimeClasspath`，确认纯 Java View 场景未意外引入 `kotlin-stdlib`，且所有 `meguviz-drawable-*` 制品均为 `1.2.0`。
   - 构建与回归：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。
   - 视觉与缓存核对：按压/选中状态切换色正确、密度切换后圆角/描边比例正确、宿主覆盖背景后 `SlotBinding.isOwned()` 立即为 `false`、`FlightCache` 命中 `READY` 时界面正常更新且 `Ticket.cancel()` 后无迟到回调。
2. **MeguvizDrawable 仓库源码自检（维护本库时执行）**
   - 纯 Java 核心合同：`./gradlew --offline --no-daemon check`
   - Android 模块（需配置 Android SDK）：`./gradlew --offline --no-daemon -Pmeguviz.profile=android check :android:assembleDebugAndroidTest`
   - 桥接档位检查：`./gradlew --offline --no-daemon -Pmeguviz.profile=bridges check`（完整压力语料可显式运行 `:core:contractStress`）
3. **交付与回退**：确认无遗留未取消的 `FlightCache.Ticket` 或未关闭的 `BindingScope` / `RecipePainter`，并在摘要中附带改动文件、验证命令及按控件可逆回退说明。
