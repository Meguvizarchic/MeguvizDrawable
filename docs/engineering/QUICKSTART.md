# 快速上手

本页代码与两份样例逐字对应，样例被 gate 编译（并在宿主运行）：

- 纯 Java 部分：`core/src/test/java/io/github/jichuo1/meguviz/drawable/samples/QuickStartSample.java`，
  由 `:core:contractTest`（`gradlew check` 的一部分）编译并运行，结果由合同
  `CoreAdditionalContractTest.quickStartSampleRuns` 断言。
- Android 部分：`android/src/androidTest/java/io/github/jichuo1/meguviz/drawable/android/samples/AndroidQuickStartSample.java`，
  由 `:android:assembleDebugAndroidTest` 编译；**只编译、不在设备上运行**。

改了这里的代码就要同步改样例，反之亦然。

## 1 分钟

坐标（Central 上只有 1.0.0；本页用到的 `DrawingRecipe.of`/`withStroke`/`withMetrics` 与 `FlightCache` 属于未发布的 1.2.0，
在仓库里用 `-Pmeguviz.profile=android :core:publishCandidatePublicationToStagingRepository` 产出到 `build/staging-maven`）：

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-drawable-android:1.0.0") // 已带 core
}
```

一个带圆角与描边的 View 背景：

```java
DrawingRecipe card = DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(16)), FillSpec.solid(0xFFFFFFFF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x1F000000));
view.setBackground(new RecipeDrawable(card, view.getResources().getDisplayMetrics().density));
```

Kotlin 里写法相同（`DrawingRecipe.of(...).withStroke(...)`）；这些都是普通静态/实例方法，没有额外的包装对象：
`of` 只分配返回的那个 recipe（`LayoutMetrics.unspecified()` 返回共享常量），`withStroke`/`withMetrics` 各分配一个新 recipe，
与手写四参构造器完全相同（宿主 JVM 分配探针，见 [性能指南](PERFORMANCE_GUIDE.md) 2026-11-08 节）。

## 配方（recipe）

- recipe 不可变、按值比较，可以在线程间、在多个 Drawable 之间共享；**Drawable 不能共享**，每个所有者一个。
- 状态色按顺序首个匹配（不做 AOSP 的第二遍通配），没有规则匹配时用 fallback：

```java
StateColorList colors = new StateColorList(
        Arrays.asList(new StateSpec(PRESSED), new StateSpec()),   // first match wins
        new int[] {0xFF1E4FD8, 0xFF3366FF});
return DrawingRecipe.of(ShapeSpec.roundRect(Length.dp(12)), FillSpec.statefulSolid(colors, 0xFF3366FF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x33000000));
```

- 渐变：`FillSpec.linearGradient(startX, startY, endX, endY, colors, stops)`，端点是相对 bounds 的 [0,1] 坐标。
- 尺寸：`Length.px`/`dp`/`boundsFraction(value, axis)`；密度在 `RecipeDrawable` 构造时固定，密度变化要新建实例。

## 共享的昂贵结果：FlightCache

同一个键同时只生产一次、在所有者线程交付、条目/权重/飞行数都有上限。绘制路径只用 `peek`：

```java
FlightCache.<String, Bitmap>builder(workers, new MainThreadPoster())
        .weigher(BitmapWeigher.INSTANCE)
        .maxWeight(24L * 1024 * 1024)
        .maxEntries(64)
        .maxFlights(8)
        .build();
```

`request` 返回 `READY` 时**不会回调**，必须当场用 `ticket.value()`；`STARTED`/`JOINED` 会在所有者线程回调一次（除非取消、过期或关闭）。
完整规则见 [FLIGHT_CACHE.md](FLIGHT_CACHE.md)。

## 迁移

| 旧写法 | 新写法（1.2.0，未发布） | 说明 |
|---|---|---|
| `new DrawingRecipe(shape, fill, null, LayoutMetrics.unspecified())` | `DrawingRecipe.of(shape, fill)` | 结果 `equals` 相同 |
| `new DrawingRecipe(r.shape(), r.fill(), stroke, r.metrics())` | `r.withStroke(stroke)` | 原 recipe 不变 |
| `new DrawingRecipe(r.shape(), r.fill(), r.stroke(), metrics)` | `r.withMetrics(metrics)` | 原 recipe 不变 |

旧构造器保留，不弃用。

## 排错

| 现象 | 原因与处理 |
|---|---|
| 构造 `RecipeDrawable` 抛 “optical insets are not supported” | 该后端不支持非零光学内边距；去掉 `LayoutMetrics` 的 optical inset |
| `setBounds` 抛 “stroke is too wide” / “collapse at Canvas float precision” | 描边宽度 ≥ 宽或高，或 bounds 太大导致 float 精度塌缩；准备阶段就拒绝，不画错图 |
| 设置了 tint 或 `setColorFilter` 后 `getConstantState()` 为 null | 有意保守：任意滤镜与 tint 无法安全重建 |
| `FlightCache.request` 返回 `READY` 却一直没收到 `onReady` | 命中路径不回调，见上文 |
| 有 elevation 的 View 没有阴影 / `clipToOutline` 无效 | `getOutline` 当前有意返回空轮廓（alpha 0），见 [联合审计](AUDIT_JOINT_R1.md) D-05 |
| 单个值的权重大于 `maxWeight` | 会入缓存后立即以 `CAPACITY` 淘汰，且淘汰监听在交付之前被调用；不要在监听里回收“看起来不在屏幕上”的值，见审计 D-02 |

## 下一步

- 性能约束与测量协议：[PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)
- 可选桥（Compose、MeguvizAndroid、MeguvizUI、Lumen）：[BRIDGES.md](BRIDGES.md)
- 状态表预编译：[STATE_MATCHER_PREPARED.md](STATE_MATCHER_PREPARED.md)
