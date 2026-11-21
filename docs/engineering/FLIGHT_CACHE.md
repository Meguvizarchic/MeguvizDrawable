# FlightCache：共享、有界、单飞的结果缓存

`io.github.jichuo1.meguviz.drawable.cache.FlightCache`（位于 `meguviz-drawable-core:1.2.0`）将昂贵、纯计算、可跨所有者共享的结果（如背景 tile）交由后台工作线程生产，同时确保：绘制路径只读内存、同键并发只生产一次、过期或取消的结果不送达无关所有者，且条目、权重与在途飞行数均有明确上限。Android 侧配套的 `MainThreadPoster`（主线程投递）与 `BitmapWeigher`（按分配字节计重）位于 `meguviz-drawable-android:1.2.0`。（注：`1.1.0` 从未发布且已被 `1.2.0` 取代，`docs/abi/1.1.0` 仅作历史快照保留。）

## 核心契约

| 能力 | 行为 |
| --- | --- |
| 查找零计算 | `peek(key)` 与 `request` 命中路径只读内存（命中路径除返回的 `Ticket` 外不分配对象），可在绘制线程直接调用；加入在途飞行时分配订阅者记录，拒收路径不分配 |
| 同键单飞 | 并发请求同一缺失键时，先到者的 `Producer` 在锁外运行一次，结果入缓存并交付所有仍关注该键的订阅者；因此相等的键必须对应等价输入 |
| owner 线程与代际校验 | 订阅者回调经 `OwnerPoster` 在所有者线程触发，并在回调前再次核对 `Subscriber.isCurrent()`；`Ticket.cancel()` 在所有者线程返回后保证不再触发回调 |
| 放弃任务不积压 | 尚在排队的飞行失去全部订阅者后，轮到执行时直接丢弃（`ThreadPoolExecutor` 会立即移出队列），到达上限时最先让位，同键新请求可将其复活；已运行中的飞行允许完成并缓存（开启 `abandonRunning` 后可通过 `Cancellation` 提前退出） |
| 显式有界 | 条目数（`maxEntries`）、总权重（`maxWeight`）与并发飞行数（`maxFlights`）均受限制；飞行超限时 `request` 返回 `REJECTED_CAPACITY`，不无限排队 |
| 失败不缓存 | `Producer` 抛出异常或返回 `null` 时，所有等待者收到 `onFailed`，结果不入缓存，后续请求可重新生产 |
| 不自动回收资源 | 淘汰条目时不自动调用 `recycle()`（被淘汰的 Bitmap 可能仍在屏幕上显示）；需要释放资源时请通过 `EvictionListener` 在确认空闲后处理 |
| 同步精确导出 | `computeNow(key, producer)` 在调用线程直接生产并缓存，不排队等待线程池；尚在排队的同键飞行直接复用该结果完成 |
| 可观测统计 | `stats()` 提供命中、未命中、合并、生产、失败、淘汰、拒绝、放弃、取消、投递与代际丢弃计数；`version()` 仅在有新值实际入缓存时递增 |
| 超重值处理 | 单条结果权重大于 `maxWeight` 时仍正常交付给调用者与订阅者，但**不进入缓存**、不触发 `CAPACITY` 淘汰、不计 `produced` 也不递增 `version()`；需监听每次交付应通过 `Subscriber.onReady` |
| 重复发布收敛 | 若运行中飞行被 `computeNow` 抢先写入同键，后完成的飞行沿用缓存中已有引用，不触发 `REPLACED` 淘汰、不重复加 `version()`，保证订阅者拿到同一引用 |

`Producer` 必须纯且线程安全，产出的值发布后须保持不可变。调用 `close()` 会清空缓存、阻止排队任务执行，并在运行中生产者的 `Cancellation` 上标记取消。

## 典型替换示例（共享背景缓存）

将原有的“全局静态 `LruCache` + 各自维护 `working` 标志 + 绘制线程兜底同步渲染”替换为 `FlightCache` 的写法示意如下：

```kotlin
// 进程内共享实例；Key 同原有配置键（config、night、width、band）
val tiles = FlightCache.builder<Key, Bitmap>(worker, MainThreadPoster())
    .maxWeight(24L * 1024 * 1024).weigher(BitmapWeigher.INSTANCE)
    .maxEntries(64).maxFlights(8).build()

// 绘制路径：只读缓存；未命中时提交异步请求，不在绘制线程渲染
val bitmap = tiles.peek(key)
if (bitmap == null && tickets[key] == null) {
    tickets[key] = tiles.request(key, FlightCache.Producer { k, _ -> render(k) }, sceneSubscriber(key)).also {
        if (it.status() == FlightCache.Status.READY) ready[key] = it.value() // 命中时直接取值
    }
}
// 视口变化：取消不再需要的 tile
for ((k, t) in tickets) if (k !in wanted) t.cancel()
```

- 交互绘制的软件路径若未命中，应使用占位底色并提交异步生产；需要精确导出图像时使用 `computeNow`。
- 若业务需要感知包括超重值在内的每一次结果交付，应依赖 `Subscriber.onReady` 回调或计数，不可仅轮询 `version()`。
- 单个界面的分离只 `cancel` 自己持有的 `Ticket`，不影响其他界面等待同一键的在途飞行。

## 通用异步准备组合

将上述模型与 `BindingScope` 结合的最小无宿主示例已固化为 `core/src/test/java/io/github/jichuo1/meguviz/drawable/core/AsyncPreparationContractTest.java`：

```kotlin
// 所有者线程：stamp 控制同 slot 最新请求有效
val stamp = scope.begin(key)
val ticket = cache.request(key, producer, object : Subscriber<V> {
    override fun isCurrent() = current[key] === submission
    override fun onReady(value: V) { scope.commit(stamp, value, installer, releaser) }
    override fun onFailed(failure: Throwable) { failures.add(...) }
})
when (ticket.status()) {
    READY -> scope.commit(stamp, ticket.value(), installer, releaser) // 命中不回调，须同步安装
    REJECTED_CAPACITY, REJECTED_CLOSED -> { /* 记录状态，由宿主择机重试 */ }
}
```

关键注意事项：
- 同 key 替换提交时，先 `request` 注册新订阅（保留已有在途飞行）再 `cancel()` 旧票据。
- `releaser` 仅在结果未能安装时被调用，此时 `FlightCache` 仍可能持有同一实例并供其他订阅者读取，因此 `releaser` 绝不可销毁共享值 `V`（缓存侧资源释放应统一放在 `EvictionListener`）。
- `Ticket.cancel()` 与 `scope.close()` 只解除订阅关系，缓存生命周期由 `FlightCache.close()` 独立控制。

## 执行器失败回滚与锁粒度加固

- **执行器失败回滚**：`request` 提交任务到 `workers.execute(task)` 时，若抛出 `RejectedExecutionException` 则回滚飞行并返回 `REJECTED_CAPACITY`；若自定义执行器抛出其他 `RuntimeException` 或 `Error`，在完成飞行回滚（移除映射、通知已合并订阅者 `onFailed`）后原样重抛，避免残留死飞行卡住后续同键请求（见 `FlightCacheContractTest.executorFailureIsNotARefusal`）。
- **命中路径锁内零分配与投递容错**：`request` 命中与 `REJECTED_CLOSED` 路径在锁内仅读取状态，锁外构建 `Ticket`；`Flight.subscribers` 初始容量设为 `1`。投递时的 `owner.post` 与淘汰时的 `onEvicted` 捕获 `Throwable` 并计入失败统计而不阻塞后续订阅者（订阅者自身回调异常仍按既定契约向上传播）。
- **票据引用清理**：完成、取消、投递失败与关闭时及时断开 `Sub` 与 `Flight.producer` 引用，详见 [票据引用释放](FLIGHT_RETENTION.md)。

## 获取方式

`meguviz-drawable-core:1.2.0`、`meguviz-drawable-android:1.2.0` 与 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 均已发布至 Maven Central，可直接在 Gradle 中接入。

## 适用范围与验证说明

- 宿主 JVM 契约测试 `FlightCacheContractTest`（含 8 线程 × 20 轮并发单飞、4 线程随机限额压测、执行器与投递失败回滚）及 `AsyncPreparationContractTest` 均已通过；单台 API 34 模拟器验证了真实线程池、主 Looper 与 `BitmapWeigher` 字节上限淘汰。
- 宿主 JVM 分配探针（`benchmark/FlightCacheAllocationProbe.java`，协议见 [PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)）测得关闭逃逸分析下命中路径 `104.000 → 32.000 B/次`、加入路径 `104.000 → 56.000 B/次`、拒绝路径 `136.000 → 64.000 B/次`。上述分配数字反映宿主 HotSpot JVM 局部开销，不构成真机帧耗或 ART 性能结论。
