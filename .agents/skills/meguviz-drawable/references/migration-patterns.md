# MeguvizDrawable 迁移对照与排障

本文档提供从旧绘制库或手写 Drawable/缓存方案迁移至 `MeguvizDrawable 1.2.0` 的前后对照、线程与所有权约束、常见问题排查与回退方案。

## 1. 存量代码迁移范式

### 1.1 `DrawableToolbox` / 手工 `GradientDrawable` -> `DrawingRecipe` + `RecipeDrawable`

**迁移前**：为每个 View 每次构造可变的 `DrawableBuilder` / `GradientDrawable`，易产生隐式状态共享、Padding 被背景覆盖或线程误用。

```java
// 迁移前：每次构造可变 GradientDrawable
GradientDrawable bg = new GradientDrawable();
bg.setCornerRadius(dp16);
bg.setColor(0xFFFFFFFF);
bg.setStroke(dp1, 0x1F000000);
view.setBackground(bg);
```

**迁移后**（`1.2.0` 推荐工厂方法）：

```java
// 配方不可变，可定义为常量跨界面或跨线程复用；每个 View 独立创建 Drawable 实例
private static final DrawingRecipe CARD_RECIPE = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(16)), FillSpec.solid(0xFFFFFFFF))
        .withStroke(new StrokeSpec(Length.dp(1), 0x1F000000));

view.setBackground(RecipeDrawable.forView(view, CARD_RECIPE));
```

### 1.2 `StateListDrawable` -> `StateColorList` + `FillSpec.statefulSolid`

**迁移前**：依赖 XML `<selector>` 或运行时构造 `StateListDrawable`，AOSP 在首遍未命中时会回退到 `StateSet.WILD_CARD`。

**迁移后**：按优先级顺序明确规则，末条可放空条件 `new StateSpec()`，找不到匹配时进入显式 `fallbackColor`：

```java
StateColorList colors = new StateColorList(
        Arrays.asList(
                new StateSpec(-android.R.attr.state_enabled), // 禁用态优先（负号表示非）
                new StateSpec(android.R.attr.state_pressed),  // 按下态
                new StateSpec()                               // 默认规则
        ),
        new int[] { 0xFF9AA0A6, 0xFF1E4FD8, 0xFF3366FF }
);
DrawingRecipe buttonRecipe = DrawingRecipe.of(
        ShapeSpec.roundRect(Length.dp(12)),
        FillSpec.statefulSolid(colors, 0xFF3366FF));
```

### 1.3 `LruCache<Key, Bitmap>` 背景光栅化 -> `FlightCache`

**迁移前**：多个页面/卡片冷启动同时触发同一 key 重复绘制；没有代次检查和取消机制，界面销毁后回调仍写回或误回收正在屏幕显示中的 Bitmap。

**迁移后**（使用 `MainThreadPoster` 与 `BitmapWeigher.INSTANCE`）：

```java
FlightCache<TileKey, Bitmap> tileCache = FlightCache.<TileKey, Bitmap>builder(workers, new MainThreadPoster())
        .weigher(BitmapWeigher.INSTANCE)
        .maxWeight(24L * 1024 * 1024)
        .maxEntries(64)
        .maxFlights(8)
        .build();

// 绘制路径仅只读查询：
Bitmap ready = tileCache.peek(key);
if (ready != null) {
    canvas.drawBitmap(ready, 0f, 0f, null);
    return;
}

// 异步准备：必须同步判断 READY，命中时不会回调 onReady
FlightCache.Ticket<Bitmap> ticket = tileCache.request(key, (k, cancel) -> renderTile(k, cancel),
        new FlightCache.Subscriber<Bitmap>() {
            @Override public boolean isCurrent() { return generation == activeGeneration; }
            @Override public void onReady(Bitmap value) { installAndInvalidate(value); }
            @Override public void onFailed(Throwable failure) { reportFailure(failure); }
        });
if (ticket.status() == FlightCache.Status.READY) {
    installAndInvalidate(ticket.value());
}
// 界面分离或不再需要该图块时在主线程取消：
// ticket.cancel();
```

### 1.4 保护宿主 Padding 与业务主动覆盖 -> `BindingScope` + `ViewSlots`

当 View 的背景可能在业务层被临时替换，或 View 在 XML 中预设了不可被背景破坏的内边距时，使用 `SlotBinding`：

```java
BindingScope scope = new BindingScope(1L);
scope.bind(new BindingEnvironment(1L, 1L));
SlotBinding<Drawable> bgSlot = scope.slot(
        "background",
        ViewSlots.background(view, ViewSlots.PaddingPolicy.PRESERVE_HOST),
        null);

bgSlot.ensure(view.getBackground(), cardRecipe, 1L, () -> RecipeDrawable.forView(view, cardRecipe));
// 若业务中途执行 view.setBackground(customDrawable)，后续 ensure 返回 NOT_OWNED 且不覆盖业务图
// 需要收回控制权时显式调用：
// bgSlot.takeOver(RecipeDrawable.forView(view, newRecipe), newRecipe, 2L);
```

### 1.5 接入 `MeguvizAndroid` 或 `MeguvizUI`

- **`MeguvizAndroid` 宿主**：用 `MeguvizBinding.create(viewScope.lifecycle(), viewScope.resources().state())`（纯主线程）或五参 `create(..., mailbox, registrySource, epoch)`（后台计算后通过 `BoundedMailbox` 回主线程安装），`BindingLease` 与 `BindingScope` 双重校验后才安装。
- **`MeguvizUI` 宿主**：用 `val bg = uiSession.bindRecipeBackground(viewRef)`，调用 `bg.submit(recipe)`；当宿主覆盖背景后可通过 `bg.isOwned` 感知并在需要时调用 `bg.reclaim()`。

### 1.6 Compose 迁移

将 Compose 中手写 `Canvas` 圆角描边与状态颜色切换替换为 `rememberRecipePainter`：

```kotlin
val state = DrawableState(
    enabled = enabled,
    pressed = isPressed,
    focused = isFocused,
)
val painter = rememberRecipePainter(recipe = cardRecipe, state = state, mirrorInRtl = true)
Box(modifier = Modifier.paint(painter, contentScale = ContentScale.FillBounds))
```

## 2. 线程、所有权与边界规则

| 对象 / 方法 | 线程限制 | 所有权与共享边界 |
| --- | --- | --- |
| `DrawingRecipe` / `ShapeSpec` / `FillSpec` / `StateColorList` / `RequestStamp` | 任意线程 | 完全不可变、按值比较，可全局静态共享与跨线程传递 |
| `RecipeDrawable` / `OwnedDrawableWrapper` | 仅创建（owner）线程 | **严禁多个 View 共用同一 Drawable 实例**；密度变化须新建实例 |
| `RecipePainter` | 仅 Compose UI 线程 | 一对一拥有一个 `RecipeDrawable`；脱离组合自动 `close()`，手动构造需自行 `close()` |
| `BindingScope` / `SlotBinding` / `ViewSlots` | 仅创建（owner）线程 | `close()` 按逆序释放登记资源，仅清除仍属于自己的槽位 |
| `FlightCache.request` / `peek` / `computeNow` | 线程安全 | `Ticket.cancel()` 与 `Subscriber` 回调在 owner 线程；不要在 `EvictionListener` 中直接 `recycle()` 仍在屏幕上引用的 Bitmap |

## 3. 高频错误与排障对照表

| 报错 / 现象 | 原因 | 处理方法 |
| --- | --- | --- |
| `IllegalArgumentException: optical insets are not supported by this backend` | `LayoutMetrics` 的 `opticalInset*` 非 `0` | 将 `opticalInsetLeft/Top/Right/Bottom` 设为 `0`，或使用 `DrawingRecipe.of(...)` |
| `IllegalArgumentException: stroke is too wide for these bounds` | `StrokeSpec` 宽度 `>=` 边界宽或高 | 缩小描边宽度，或在布局测量宽高中预留充足尺寸 |
| `FlightCache.request` 返回 `READY` 但界面一直空白 | 缓存命中路径不回调 `Subscriber.onReady` | 紧接 `request` 后判断 `ticket.status() == READY` 并直接取 `ticket.value()` |
| 配置变更后多块屏幕/显示器密度切换导致圆角大小不对 | `RecipeDrawable.forView` 仅在构造时读取一次密度 | 密度变化后重建 `RecipeDrawable`，或使用 `rememberRecipePainter` / `UiSession.bindRecipeBackground` |
| View 设置了 `elevation` 或 `clipToOutline = true` 但无阴影/裁剪 | `RecipeDrawable.getOutline` 有意返回空轮廓（alpha 0） | 若必须依赖系统轮廓阴影与裁剪，由外层容器提供平台 Outline 或手动 `ViewOutlineProvider` |
| `IllegalArgumentException: key already claimed: background` | 同一 `BindingScope` 内重复绑定或 `LumenSurfaceBridge` 已占用 `"background"` | 协调使用唯一属性归属，或将另一方绑定至不同 `propertyId` / `key` |
| 找不到 `meguviz-drawable-lumen:1.2.0` | 该桥接模块未上传 Maven Central | 改为从 `MeguvizDrawable` 源码通过 `-Pmeguviz.profile=bridges` 构建或使用本地 staging |

## 4. 安全回退方案

1. **按控件局部回退**：`RecipeDrawable` 与原生 `android.graphics.drawable.Drawable` 兼容，若个别页面强依赖平台 `Outline` 阴影或 `BlendMode` tint，可直接换回原生 `GradientDrawable` 或将 `View` 背景交还宿主管理（`BindingScope.close()` / `SlotBinding.close()` 仅清除本库安装的对象，不破坏宿主之后设置的背景）。
2. **版本回退**：若仅消费纯基础能力且需临时退回 `1.0.0`，须同时去掉仅在 `1.2.0` 新增的 `DrawingRecipe.of`、`withStroke`、`withMetrics`、`RecipeDrawable.forView`、`FlightCache`、`StateMatcherCache`、`MainThreadPoster`、`BitmapWeigher` 与 `BindingScope.claim` 调用，改回四参 `DrawingRecipe` 与两参 `RecipeDrawable` 构造器；三座已发布的桥接库均从 `1.2.0` 起上 Central，无 `1.0.0` 线上桥接制品可退回。
