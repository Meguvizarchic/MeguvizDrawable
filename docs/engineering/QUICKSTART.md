# 快速上手

本页 Java 代码片段与以下两份受测样例保持逐字一致：

- 纯 Java 部分：[`QuickStartSample.java`](../../core/src/test/java/io/github/jichuo1/meguviz/drawable/samples/QuickStartSample.java)，由 `:core:contractTest`（`gradlew check`）编译并运行，结果由 `CoreAdditionalContractTest.quickStartSampleRuns` 断言。
- Android 部分：[`AndroidQuickStartSample.java`](../../android/src/androidTest/java/io/github/jichuo1/meguviz/drawable/android/samples/AndroidQuickStartSample.java)，由 `:android:assembleDebugAndroidTest` 随 Android 构建编译校验（不直接作为设备用例执行）。

同步修改文档代码与样例文件，确保示例与当前 API 一致。

## 1 分钟接入

`meguviz-drawable-core` 与 `meguviz-drawable-android` `1.2.0` 均已发布至 Maven Central（可通过家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 对齐版本，或直接声明 `1.2.0`）。本页用到的 `DrawingRecipe.of`、`withStroke`、`withMetrics`、`RecipeDrawable.forView` 与 `FlightCache` 均已包含在 `1.2.0` 制品中：

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-drawable-android:1.2.0") // 已传递包含 core
}
```

一个带圆角与描边的 View 背景：

```java
DrawingRecipe card = DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(16)), FillSpec.solid(0xFFFFFFFF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x1F000000));
view.setBackground(RecipeDrawable.forView(view, card));
```

`RecipeDrawable.forView(view, recipe)` 等价于 `new RecipeDrawable(recipe, view.getResources().getDisplayMetrics().density)`：在调用时读取一次 View 自身资源的密度（与 `Resources.getSystem()` 或 Application 密度在多屏/覆盖配置下可能不同），不安装监听器也不缓存实例；密度变化后仍需新建实例。早期 `1.0.0` 版本不包含该便捷工厂方法，须直接使用构造器。

Kotlin 调用写法相同（`DrawingRecipe.of(...).withStroke(...)`）。`of` 使用私有默认 `LayoutMetrics` 常量，只分配返回的 recipe 对象；而直接调用四参构造器配合公开 `LayoutMetrics.unspecified()` 会额外分配一个 `LayoutMetrics` 实例（宿主 JVM 分配探针测得 `32 B` vs `96 B`，详见 [性能指南](PERFORMANCE_GUIDE.md) 2026-11-08 节）。

## 配方（Recipe）规则

- `DrawingRecipe` 不可变且按值比较，可在线程间及多个 `Drawable` 间共享；**`RecipeDrawable` 不可共享**，每个宿主 View 独占一个实例。
- 状态色按规则列表顺序首个匹配（不做 AOSP `StateListDrawable` 的二次通配匹配），未命中时使用 `fallbackColor`：

```java
StateColorList colors = new StateColorList(
        Arrays.asList(new StateSpec(PRESSED), new StateSpec()),   // first match wins
        new int[] {0xFF1E4FD8, 0xFF3366FF});
return DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(12)), FillSpec.statefulSolid(colors, 0xFF3366FF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x33000000));
```

- 线性渐变：`FillSpec.linearGradient(startX, startY, endX, endY, colors, stops)`，端点采用相对边界的 `[0, 1]` 坐标。
- 尺寸单位：`Length.px`、`Length.dp` 或 `Length.boundsFraction(value, axis)`；`dp` 在构造 `RecipeDrawable` 时按当前密度换算。

## 共享缓存：`FlightCache`

相同键在同一时间只由工作线程生产一次，并在所有者线程交付结果；条目数、总权重与并行飞行数均受上限约束，绘制路径仅调用 `peek` 只读查询：

```java
FlightCache.<String, Bitmap>builder(workers, new MainThreadPoster())
        .weigher(BitmapWeigher.INSTANCE)
        .maxWeight(24L * 1024 * 1024)
        .maxEntries(64)
        .maxFlights(8)
        .build();
```

`request` 返回 `READY` 时**不会回调 `onReady`**，必须当场读取 `ticket.value()`；`STARTED` 或 `JOINED` 会在所有者线程回调一次（除非取消、代次过期或缓存关闭）。完整语义见 [FLIGHT_CACHE.md](FLIGHT_CACHE.md)。

## `1.0.0` 至 `1.2.0` 便捷 API 迁移

| 旧写法（`1.0.0`） | 新写法（`1.2.0`） | 说明 |
| --- | --- | --- |
| `new DrawingRecipe(shape, fill, null, LayoutMetrics.unspecified())` | `DrawingRecipe.of(shape, fill)` | 结果按 `equals` 相等 |
| `new DrawingRecipe(r.shape(), r.fill(), stroke, r.metrics())` | `r.withStroke(stroke)` | 原 recipe 不变 |
| `new DrawingRecipe(r.shape(), r.fill(), r.stroke(), metrics)` | `r.withMetrics(metrics)` | 原 recipe 不变 |
| `new RecipeDrawable(recipe, view.getResources().getDisplayMetrics().density)` | `RecipeDrawable.forView(view, recipe)` | 同一密度来源，逐像素一致 |

四参构造器与两参 `RecipeDrawable` 构造器继续保留，不会弃用。

## 常见问题排查

| 现象 | 原因与处理 |
| --- | --- |
| 构造 `RecipeDrawable` 抛 `optical insets are not supported` | 当前 Canvas 后端不支持非零光学内边距，请保留 `opticalInset*` 为 `0` |
| `setBounds` 抛 `stroke is too wide` 或 `collapse at Canvas float precision` | 描边宽度大于等于边界宽/高，或坐标在 float 精度下塌缩，需调整描边或尺寸 |
| 设置 tint 或 `setColorFilter` 后 `getConstantState()` 返回 `null` | 有意保守处理：任意外部滤镜与 tint 无法安全重建 |
| `FlightCache.request` 返回 `READY` 却未触发 `onReady` | 命中路径不走异步回调，见上文直接读取 `ticket.value()` |
| 设置 `elevation` 后无阴影或 `clipToOutline` 不生效 | `getOutline` 当前有意返回空轮廓（alpha `0`），见 [联合审计](AUDIT_JOINT_R1.md) D-05 |
| 单条结果权重大于 `maxWeight` | `1.2.0` 起超重值只直接交付而不入缓存、不触发 `CAPACITY` 淘汰、不计 `produced` 也不递增 `version()`，`peek` 仍为 `null`；需观察每次交付请使用 `onReady` |

## 延伸阅读

- 性能协议与测量记录：[PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)
- 可选桥接模块（Compose、MeguvizAndroid、MeguvizUI、Lumen）：[BRIDGES.md](BRIDGES.md)
- 状态表预编译与共享缓存：[STATE_MATCHER_PREPARED.md](STATE_MATCHER_PREPARED.md)
