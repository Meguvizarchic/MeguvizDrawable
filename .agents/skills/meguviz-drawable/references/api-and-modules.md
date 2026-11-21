# MeguvizDrawable API 与模块速查

本文件整理 `MeguvizDrawable` `1.2.0` 的全部模块坐标、SDK 与编译条件、公开 API 签名及与家族其他库的桥接边界。

## 1. 模块坐标、平台要求与发布状态

除 `meguviz-drawable-lumen` 外，以下 `1.2.0` 制品均已发布至 Maven Central（groupId：`io.github.jichuo1.meguviz`），并纳入 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 管理。

| Artifact ID | Gradle 子项目 | minSdk / 运行时 | compileSdk / 语言 | Central `1.2.0` 状态 | 关键依赖与说明 |
| --- | --- | --- | --- | --- | --- |
| `meguviz-drawable-core` | `:core` | JVM 8+ | Java 8 | 已发布 | 无任何外部依赖；配方模型、状态匹配与缓存、`BindingScope`、SPI |
| `meguviz-drawable-android` | `:android` | `21` | `37` / Java 8 | 已发布 | 仅依赖 `core`；不含 Kotlin/Compose 运行时 |
| `meguviz-drawable-compose` | `:compose` | `23` | `37` / Kotlin `2.4.21` | 已发布 | 依赖 `android`、Compose BOM（`runtime`/`ui-graphics`/`ui`） |
| `meguviz-drawable-meguviz-android` | `:meguviz-android` | `27` | `37` / Java 17 | 已发布 | 依赖 `android` 与 `meguviz-android-runtime:1.2.0` |
| `meguviz-drawable-meguviz-ui` | `:meguviz-ui` | `27` | `37` / Kotlin `2.4.21` | 已发布 | 依赖 `android` 与 `meguviz-ui-core:1.2.0` |
| `meguviz-drawable-lumen` | `:lumen` | `27` | `37` / Java 17 | **未上 Central（仅源码）** | 依赖本地 `:core`（`BindingScope.claim`）、`:android` 与 `lumen-engine` |

> **Maven 与源码构建差异提醒**：
> - `meguviz-drawable-lumen` 暂未随 `1.2.0` 发布到 Maven Central。本仓库默认配置（`gradle/libs.versions.toml`）目前指向 JitPack `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5`；若工程需要从 Central 接入 `LumenCoacervationEngine`（`io.github.jichuo1:lumen-engine:1.6.2`），须以源码引入 `:lumen` 模块并自行对齐依赖坐标。
> - 仓库通过 `-Pmeguviz.profile=host|android|bridges` 控制包含的子项目，默认 `host` 仅包含 `:core`。

## 2. 核心公开 API 签名速查

### 2.1 `core` — 不可变配方模型（`io.github.jichuo1.meguviz.drawable.model`）

- `DrawingRecipe`
  - `static DrawingRecipe of(ShapeSpec shape, FillSpec fill)`（无描边，使用私有默认 `LayoutMetrics`，不额外分配 `LayoutMetrics`）
  - `DrawingRecipe(ShapeSpec shape, FillSpec fill, StrokeSpec stroke, LayoutMetrics metrics)`
  - `DrawingRecipe withStroke(StrokeSpec stroke)` / `DrawingRecipe withMetrics(LayoutMetrics metrics)`
  - `shape()` / `fill()` / `stroke()` / `metrics()`
- `ShapeSpec`
  - `static ShapeSpec rectangle()` / `static ShapeSpec ellipse()`
  - `static ShapeSpec roundRect(Length radius)` / `static ShapeSpec roundRect(Length... radii)`（8 个分量：TL、TR、BR、BL 的 x/y）
- `FillSpec`
  - `static FillSpec solid(int color)`
  - `static FillSpec statefulSolid(StateColorList rules, int fallbackColor)`
  - `static FillSpec linearGradient(float startX, float startY, float endX, float endY, int[] colors, float[] stops)`（坐标与 stops 须在 `[0, 1]`，2..64 个停靠点）
- `StrokeSpec(Length width, int color)`（居中描边；半透明或透明颜色仍保留描边宽度，且总宽不得大于等于宽/高）
- `Length`：`px(float)`、`dp(float)`、`boundsFraction(float value, Axis axis)`（`Axis.WIDTH` / `Axis.HEIGHT`，比例 `[0, 1]`）
- `LayoutMetrics`：`unspecified()`、`LayoutMetrics(12 个像素参数)`（当前 Android Canvas 后端要求 `opticalInset*` 全为 `0`）
- `StateSpec(int... conditions)`（正数要求存在，负数要求不存在，`0` 提前终止；禁止 `Integer.MIN_VALUE`）
- `StateColorList(List<StateSpec> specs, int[] colors)`（顺序首匹配，大小须一致）

### 2.2 `core` — 状态编译、缓存与作用域（`...core` / `...cache` / `...binding`）

- `StateMatcher` & `StateMatcherCache`
  - `static boolean matches(StateSpec spec, int[] state)` / `static int matchIndex(StateColorList rules, int[] state)`
  - `static PreparedMatcher prepare(StateColorList rules)`（`distinct IDs <= 16` 且 `rules <= 32767` 且 `(1<<K)*R <= 1<<22` 编译查表，否则自动退回直接匹配）
  - `StateMatcherCache.shared().acquire(StateColorList rules)`（按条件列表缓存表，默认上限 `DEFAULT_MAX_WEIGHT = 1 MiB`）
- `FlightCache<K, V> implements AutoCloseable`
  - `static <K, V> Builder<K, V> builder(Executor workers, OwnerPoster owner)`
  - `Builder`: `maxEntries(int)`（默认 64）、`maxWeight(long)`、`maxFlights(int)`（默认 16）、`weigher(Weigher<V>)`、`evictionListener(EvictionListener<K, V>)`、`abandonRunning(boolean)`、`build()`
  - `V peek(K key)`（只读缓存，不触发计算）
  - `Ticket<V> request(K key, Producer<K, V> producer, Subscriber<V> subscriber)`（`Status { READY, JOINED, STARTED, REJECTED_CAPACITY, REJECTED_CLOSED }`；`Ticket.cancel()` 仅 owner 线程调用）
  - `V computeNow(K key, Producer<K, V> producer) throws Exception`（调用线程同步计算）
  - `long version()` / `Stats stats()` / `boolean isClosed()` / `void close()`
- `BindingScope implements AutoCloseable` & `SlotBinding<T>`
  - `BindingScope(long ownerEpoch)`: `bind(BindingEnvironment)`、`suspend()`、`resume(BindingEnvironment)`、`updateEnvironment(BindingEnvironment)`、`slot(String key, Slot<T>, Releaser<T>)`、`claim(String key)`（返回 `Claim`）、`begin(String key)`（返回 `RequestStamp`）、`commit(RequestStamp, R, Installer<R>, Releaser<R>)`、`own(Releasable)`、`close()`
  - `SlotBinding<T>`: `ensure(T expected, Object spec, long revision, Factory<T>)`（返回 `Ensure { UNCHANGED, INSTALLED, REPLACED, NOT_OWNED }`）、`takeOver(T replacement, Object spec, long revision)`、`isOwned()`、`current()`、`close()`
- `spi`（实验性冷路径多边形扩展）
  - `ShapeProviderRegistry.Builder`: `offer(...)`、`register(ShapeProviderDescriptor, ShapeProviderFactory)`、`unregister(String id)`、`snapshot(long revision)`
  - `prepare(String id, ShapeRequest)` / `prepareWithFallback(String id, ShapeRequest)`

### 2.3 `android`（`io.github.jichuo1.meguviz.drawable.android`）

- `RecipeDrawable extends Drawable`
  - `RecipeDrawable(DrawingRecipe recipe, float density)` / `static RecipeDrawable forView(View view, DrawingRecipe recipe)`
  - `DrawingRecipe recipe()`、`setAlpha`、`setColorFilter`、`setTintList`/`setTint`/`setTintMode`、`getConstantState()`（含 tint 或显式滤镜时保守返回 `null`）、`mutate()`（返回自身）
- `ViewSlots`
  - `enum PaddingPolicy { PRESERVE_HOST, ADOPT_DRAWABLE }`
  - `static SlotBinding.Slot<Drawable> background(View view, PaddingPolicy policy)`
  - `static SlotBinding.Slot<Drawable> foreground(View view)`（仅 API 23+）
- `ViewLifecycleBinder`: `static ViewLifecycleBinder attach(View, BindingScope, EnvironmentSource)`
- `MainThreadPoster implements FlightCache.OwnerPoster` / `BitmapWeigher.INSTANCE`
- `OwnedDrawableWrapper extends Drawable implements Drawable.Callback, AutoCloseable`（单所有者外部 Drawable 包装器，支持 `replaceChild`、`close`，限制最多 256 个挂起调度且不转发 API 29 `BlendMode`）
- `ExperimentalPolygonDrawable`（配合 `ShapePlanView` 的实验性多边形 Drawable）

### 2.4 可选桥接模块

- **`compose`（`io.github.jichuo1.meguviz.drawable.compose`）**
  - `@Composable fun rememberRecipePainter(recipe: DrawingRecipe, state: DrawableState = DrawableState.Default, mirrorInRtl: Boolean = false): Painter`
  - `RecipePainter(recipe, density, mirrorInRtl, state)`、`DrawableState(enabled, focused, pressed, hovered, selected, checked, activated)`
- **`meguviz-android`（`io.github.jichuo1.meguviz.drawable.meguviz.android`）**
  - `MeguvizBinding.create(LifecycleScope, ResourceState)`（纯 owner 线程简写）
  - `MeguvizBinding.create(LifecycleScope, ResourceState, BoundedMailbox, RegistrySource, long ownerEpoch)`
  - `scope()`、`project()`、`density()`、`isActive()`、`sync()`、`begin(String key)`、`commit(Request, R, Installer<R>, Releaser<R>)`、`post(Request, R, Installer<R>, Releaser<R>, Listener)`、`close()`
- **`meguviz-ui`（`io.github.jichuo1.meguviz.drawable.ui`）**
  - `const val BACKGROUND_PROPERTY = "background"`
  - `fun <V : View> UiSession.bindRecipeBackground(ref: ViewRef<V>, padding: PaddingPolicy = PRESERVE_HOST, propertyId: String = BACKGROUND_PROPERTY): RecipeBackground`
  - `RecipeBackground`: `submit(DrawingRecipe)`、`invalidate()`、`writes`、`skips`、`isOwned`、`reclaim()`、`close()`
- **`lumen`（`io.github.jichuo1.meguviz.drawable.lumen`，未上 Central）**
  - `LumenSurfaceBridge(BindingScope, LumenSurfaceSession, boolean ownsSession)`: `bind([String key,] View, LumenSurfaceOptions, View source)`、`pause()`、`resume()`、`close()`
  - `Registration`: `key()`、`options()`、`status()`（`BOUND`/`UNBOUND`/`LOST`/`CLOSED`）、`update(LumenSurfaceOptions)`、`refresh()`、`close()`

## 3. 与家族兄弟库的配合边界

- **`MeguvizAndroid`**：需接管 Activity/Fragment/Dialog 窗口生命周期、`BoundedMailbox` 或 `ResourceScope` 时转交 `$meguviz-android`；仅需把已有 `ViewScope` 接入配方提交时，添加 `meguviz-drawable-meguviz-android:1.2.0`。
- **`MeguvizUI`**：需构建原生 View 树与布局作用域时转交 `$meguviz-ui`；仅需以属性方式把配方绑到 `ViewRef` 背景时，添加 `meguviz-drawable-meguviz-ui:1.2.0`（支持与 Lumen 的 `reserveProperties("background")` 冲突检测）。
- **`LumenCoacervationEngine`**：需毛玻璃/液态折射/手势动效时转交 `$lumen-coacervation-engine`；`meguviz-drawable-lumen` 仅做 `BindingScope.claim` 互斥登记，不参与材质渲染。
