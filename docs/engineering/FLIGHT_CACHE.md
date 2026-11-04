# FlightCache：共享、有界、单飞的结果缓存

`io.github.jichuo1.meguviz.drawable.cache.FlightCache`（`meguviz-drawable-core` 1.1.0，未发布）把“很贵、纯、可被多个所有者共享”的结果——
例如背景 tile——的生产放到工作线程，并保证：绘制路径只读内存，同一个键同时只生产一次，没人要的结果不会被送到不该收的所有者，所有数量都有上限。
Android 侧配套 `MainThreadPoster`（主线程投递）与 `BitmapWeigher`（按分配字节计重），在 `meguviz-drawable-android` 1.1.0。

## 契约

| 能力 | 行为 |
|---|---|
| 查找不计算 | `peek(key)` 与 `request` 的命中路径只读内存、除返回的 `Ticket` 外不分配对象，绘制线程可以直接用；加入路径只分配登记到飞行上的订阅者，拒绝路径不分配 |
| 同键单飞 | 并发请求同一个缺失键，生产者只运行一次，结果入缓存并送给所有仍然需要的订阅者；先到者的 producer 运行，所以相等的键必须代表相等的输入 |
| 订阅者在所有者线程、带代际 | 回调经 `OwnerPoster` 在所有者线程执行，执行前再问 `Subscriber.isCurrent()`；`Ticket.cancel()` 返回后不会再有回调 |
| 放弃的工作不积压 | 排队中的飞行失去全部订阅者后，轮到它时直接丢弃（`ThreadPoolExecutor` 会立即把任务移出队列），到达上限时它最先让位，同键新请求可复活它；已开始的飞行会跑完并缓存（`abandonRunning` 可让生产者在下次检查时退出） |
| 有界 | 条目数、总权重、同时存在的飞行数都有上限；飞行超限时 `request` 返回 `REJECTED_CAPACITY`，不无限排队 |
| 失败 | 生产者抛出（或返回 null）则所有等待者收到 `onFailed`，不缓存，下次请求重新生产 |
| 不回收值 | 淘汰时不 `recycle`，被淘汰的值可能仍在屏幕上；用 `EvictionListener` 释放所有者确认空闲的资源 |
| 精确同步导出 | `computeNow(key, producer)` 在调用线程生产并缓存，从不等待线程池（不会被排在后面的任务卡死）；已排队的同键飞行用这个值直接完成 |
| 可观测 | `stats()`：命中、未命中、合并、生产、失败、淘汰、拒绝、放弃、取消、投递、代际丢弃；`version()` 每发布一个值加一 |

生产者必须纯且线程安全，值发布后不可变。`close()` 清空缓存、让排队的任务永不运行、让运行中的生产者在 `Cancellation` 检查处看到取消。

## 对应 BIL 的 `HostBackgroundScene` / `HostBackgroundRasterCache`

BIL 当前（`hook/hostui/background`）：
- `HostBackgroundScene` 用静态 `LruCache`（24 MiB）与 2 线程池；每个 Scene 自己的 `working` 标志和 `wanted/ready`，两个 Scene 对同一个 tile 同时冷未命中会各自渲染；
  `synchronous` 绘制（软件 Canvas）在绘制线程里 `render`。
- `HostBackgroundRasterCache` 有 pending 回调表，但没有取消、代际检查、数量上限，失败直接吞掉。

用 `FlightCache` 替换两处共享层的做法：

下面是接口示意，没有编译过：

```kotlin
// 进程内一份；Key 同现有 Scene.Key（config、night、width、band）
val tiles = FlightCache.builder<Key, Bitmap>(worker /* 现有 2 线程池 */, MainThreadPoster())
    .maxWeight(24L * 1024 * 1024).weigher(BitmapWeigher.INSTANCE)
    .maxEntries(64).maxFlights(8).build()

// Scene.draw：只读缓存；缺的 tile 提交，不在绘制线程渲染
val bitmap = tiles.peek(key)                      // 命中直接画
if (bitmap == null && tickets[key] == null) {
    tickets[key] = tiles.request(key, FlightCache.Producer { k, _ -> render(k) }, sceneSubscriber(key)).also {
        if (it.status() == FlightCache.Status.READY) ready[key] = it.value()   // 提交瞬间已命中
    }
}
// 视口变化：取消不再需要的 tile
for ((k, t) in tickets) if (k !in wanted) t.cancel()
// subscriber.isCurrent() = key in wanted（现有“始终追随最新视口”语义）；onReady 里写 ready[key] 并 invalidate()
```

- “软件 Canvas 同步渲染”的入口不要用 `peek` 兜底后在绘制线程 `render`：需要精确图像的导出路径用 `computeNow`，交互绘制的软件路径取就绪内容或降级底色并提交异步任务。
- 每个 tile 就绪都会让 `version()` 加一，可作为玻璃采样的“内容版本”，避免部分 tile 就绪后继续用旧底图。
- 一个 Scene 的 detach 只 `cancel` 自己的 `Ticket`，不影响其他 Scene 仍在等的飞行。

## 通用异步准备示例（无宿主身份）

把上面的用法抽象成与宿主无关的最小组合。完整、可运行、被 host 合同钉住的版本是
`core/src/test/java/io/github/jichuo1/meguviz/drawable/core/AsyncPreparationContractTest.java`
（`gradlew check` 执行；纯测试作用域，不新增生产 API、线程或线程池，core 不依赖任何兄弟库）：

```kotlin
// 所有者线程。V 是缓存生产、按引用共享给所有订阅者的不可变 payload
val stamp = scope.begin(key)                                   // 同 slot 最新者胜
val ticket = cache.request(key, producer, object : Subscriber<V> {
    override fun isCurrent() = current[key] === submission        // 代际检查：被取代的不安装
    override fun onReady(value: V) { scope.commit(stamp, value, installer, releaser) }
    override fun onFailed(failure: Throwable) { failures.add(...) }   // 不吞
})
when (ticket.status()) {
    READY -> scope.commit(stamp, ticket.value(), installer, releaser)  // 已缓存：没有回调，立即安装
    REJECTED_CAPACITY, REJECTED_CLOSED -> { /* 记录并可观察；本 slot 退役，宿主重试 */ }
}
```

要点（合同逐条断言）：

- 每 slot 至多一个 current 提交；新提交先注册（同 key 在飞飞行是 JOINED，不会杀单飞）再取消旧
  ticket；完成/失败/拒绝即移除该 slot 的引用；跨 key 规模由宿主显式限制。
- READY 命中不会回调，必须按 `ticket.value()` 立即安装，否则静默不安装。
- 拒绝（容量或缓存关闭）时 `begin` 已推进该 slot 的 stamp，旧提交同样不可能再安装：slot 整体退役、
  错误可观测、由宿主在缓存有空间/重开后重试；不存在“恢复旧提交”。
- V 由缓存共享引用交付且必须发布后不可变；每次安装由所有者创建自己的实例（例如自己的
  Drawable），不把可变实例放进缓存共享。
- releaser 只在结果未安装时收到 V（STALE/CLOSED 等），此时缓存仍持有该 V、其他订阅者仍可能
  收到同一实例，因此 releaser 不得销毁 V；释放缓存侧资源用 `EvictionListener`。
- `Ticket.cancel()` 与 `scope.close()` 只释放订阅，不动缓存；缓存的命运只由 `FlightCache.close()`
  决定。合同覆盖：两次提交旧 ready 被丢弃不安装、同 key 并发只生产一次、缓存命中立即安装、
  只在所有者线程安装（非所有者线程投递被 scope 拒绝）、失败/拒绝可观测、取消/关闭后共享值仍可
  `peek` 到。

## 获取方式

1.1.0 尚未发布到 Central。在本仓库：

```text
gradlew -Pmeguviz.profile=android :core:publishCandidatePublicationToStagingRepository :android:publishCandidatePublicationToStagingRepository
```

产物写到 `build/staging-maven`（`io/github/jichuo1/meguviz/meguviz-drawable-core/1.1.0` 与 `-android/1.1.0`）。BIL 的 release 构建要依赖发布到 Central 的版本，
所以合并使用它的改动之前需要先发布 1.1.0（见 [RELEASE.md](RELEASE.md)）；发布由仓库所有者决定。

## 验证

- 宿主 JVM 契约（`FlightCacheContractTest`）：未命中到命中、并发合并、取消与复活、代际、失败、飞行上限与放弃让位、条目与权重淘汰、`computeNow`、`close`、执行器与投递拒绝、
  `abandonRunning`、`ThreadPoolExecutor` 队列移除、8 线程 × 20 轮竞争只生产一次、4 线程随机负载下三个上限从不被突破。
- 分配（2026-11-04，宿主 JVM，`benchmark/FlightCacheAllocationProbe.java` + `run-flightcache-probe.ps1`，`ThreadMXBean.getThreadAllocatedBytes`；协议与全部数据见 [PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)）：命中路径关闭逃逸分析视图 104.000 → 32.000 B/次（默认视图前后均为 32.000，C2 已把非逃逸对象标量替换）；拒绝路径 104.000 → 32.000（默认）/ 136.000 → 64.000（关闭逃逸分析）；加入路径 104.000 → 56.000（两种视图一致，`Sub` 因登记到飞行上保留）。这是分配测量，不是帧耗时，也不是 Android/ART 收益。
- API 34 模拟器（单台）：真实线程池、主 Looper 与 Bitmap tile——两个 Scene 共用一次生产、回调在主线程、取消与过期订阅者不被调用、字节上限按真实分配字节淘汰。
- 未做：真机与硬件 Canvas 路径、BIL 实际替换后的帧耗时（属于 BIL 侧的测量，见其性能报告第 9 章）。
