# FlightCache 票据保留（retention）快路径

状态：进度候选，未发布。范围仅限 `core/.../cache/FlightCache.java` 的票据终态引用清理；公开 API 与单飞语义不变。
一项关闭行为修正是：关闭前已入投递队列、但尚未开始交付的结果在关闭后丢弃，并计入 `droppedStale`；
该分支由 `closeBeforeDrainDropsPostedDelivery` 精确断言。其他回调路径与计数条件保持。
**这是部分保留削减（partial retention reduction）**，不是“已完成
票据完全不保留 cache”：STARTED/JOINED 票据在存续期仍持有 `owner == cache`，因为 `cancel()` 需要它；
终态后 `owner` 链仍能把共享 cache 留在内存里。

## 问题（改动前）

```
Ticket → Sub → Flight → { producer(用户 Producer), key, task:Runnable }
                   task 捕获 FlightCache.this → 整个 cache
Ticket → owner → cache
```

- 已完成/已取消票据经 `Sub.flight` 长时保留 producer、key、`task`（→ cache）与 subscriber。
- `run()` 只把 flight 从 `flights` 移除并 `drain().clear()`；`Sub.flight` 仍指向 flight。
- `cancel()` 只把 sub 从 `flight.subscribers` 移除；`Sub.flight/subscriber` 原样。
- `close()` 只清 `flight.subscribers`；Sub 内字段原样。
- `deliverAll` 中 `owner.post` 失败时只计 STALE，Sub 字段原样。
- READY / REJECTED_CAPACITY / REJECTED_CLOSED 的 `Ticket` 带 `owner=cache`，但 `subscription=null`，
  `cancel()` 永假，owner 纯属多余引用。

## 改动（最小净改进）

1. READY / REJECTED_* 的 `Ticket` 传 `owner=null`（`cancel()` 先判 `subscription != null`，安全）。
2. 终态断开 `Sub`：`cancel()`、`deliver()` 的 `finally`、`close()`、`deliverAll` 投递失败时，
   置 `sub.subscriber=null`、`sub.flight=null`。`deliver` 在锁内读入 `target` 局部变量，回调在锁外
   执行；不把 callback 移进锁内，不吞 `Error`。
3. 终态断开 `Flight`：`run()`（含 `state != QUEUED` 与 closed/empty 早退）、REE 回滚、`computeNow`
   取代、`close()`、`dropOneAbandoned` 置 `flight.producer=null`。`run()` 在锁内把 producer 读入
   局部再 `produce`，所以 `close()` 清 `flight.producer` 不会影响正在运行的 producer。
4. `deliver()` 增加 `closed` 判断：close 前已 drain/ post 的投递不在 flight 中，close 遍历不到；
   现在 deliver 见到 `closed` 就丢弃并计 STALE，兑现“close 后不再回调”的文档承诺。
5. `request()` 用局部 `task` 提交给 executor：task 赋值在 lock 外，close/computeNow 可能在赋值与
   `execute` 之间清空 `flight.task`；局部保证 `workers.execute` 永不收到 null。

### 不清 `flight.task` 的终态

`run()` 正常 DONE 时清 `flight.task`（任务已执行完，安全）。`close()` / `computeNow` **不清**：
task 赋值在 lock 外，清它会与 `request()` 的 `execute` 竞态；且此时 task 已收集进 `unqueue`，
ThreadPoolExecutor 会移除，自定义 executor 的队列本就在 cache 控制之外。因此文档与测试只承诺
`run()`/REE 终态 `task == null`，不承诺所有终态。

## JMM

- `sub.subscriber` / `sub.flight`：全部读写在 `lock` 下；`deliver` 在锁内读 `target`，回调在锁外；
  `release` 在锁下清。测试用同线程 `drain()`/`cancel()` 程序序 + `stats()` 的 monitor 边，反射断言
  确定性可见，不依赖 GC。
- `flight.producer`：`run()` 在锁内读入局部；所有写在锁下。`close()` 对 RUNNING flight 清
  `flight.producer` 不影响局部。
- `flight.task` 保持 `volatile`；`request()` 赋值是 lock 外 volatile 写，Executor 契约提供
  happens-before。不新增 `volatile`。

## 证据（before = 9f7aee2 原 FlightCache，after = 本改动；同一探针二进制）

探针 `benchmark/FlightRetentionProbe.java`（`javac --release 8`，反射强引用列 + 保留 ticket 时的
有界 GC WeakReference）：

| 场景 | before sub.flight/subscriber | before producer/task | before GC 保留 | after sub.flight/subscriber | after producer/task | after GC 保留 |
|---|---|---|---|---|---|---|
| started_complete | 非空 | 非空 | 3/3 | null | null | 0/3 |
| joined_complete | 非空 | 非空 | 3/3 | null | null | 0/3 |
| cancelled | 非空 | 非空 | 3/3 | null | null | 0/3 |
| closed | 非空 | 非空 | 2/2（payload） | null | null（task 保留） | 0/2 |
| poster_refused | 非空 | 非空 | 3/3 | null | null | 0/3 |
| ready | owner非空 | — | — | owner=null | — | — |
| rejected_capacity | owner非空 | — | — | owner=null | — | — |

- before：`remaining_while_ticket_retained = 14/14`，`violations = 8`，探针退出码 1。
- after：`remaining_while_ticket_retained = 0/14`，`violations = 0`，探针退出码 0。
- 计数语义由既有 `FlightCacheContractTest` 的 `droppedStale`/`delivered`/`cancelledSubscribers` 等
  断言覆盖，after 全部通过；探针本身不打印计数。

## 测试

- `FlightCacheContractTest.retentionFastPath`（已接线）：完成/取消票据的反射字段断言。
- `TicketRetentionContractTest`（新文件，main 已接线 `CoreContractTest`）：STARTED/JOINED 完成、
  取消、close、poster 拒绝、READY/REJECTED owner、computeNow 取代、REE 回滚（含 executor 先 join
  再拒绝）、close/computeNow 与 execute 的 task 赋值竞态、回调内 reentrant cancel/close、close 前
  已 post 投递的丢弃、cancel 语义。
- 默认 `CoreContractTest`：707 项通过，包含完整票据终态路径断言；被缩减的是重复随机/穷举与压力轮次，
  不是取消、关闭、拒绝或清引用的独特回归。大语料另用显式 `:core:contractStress` 运行。

## 剩余限制（明确记录）

1. **部分保留**：STARTED/JOINED 终态票据仍 `owner=cache`，owner 链可保留整个共享 cache 及其缓存值。
   要完全断开需要 Ticket↔Sub 回引用，属后续可选增强，本轮不做。
2. `close()` / `computeNow` 不清 `flight.task`；自定义 executor 队列里的已终止 task 仍捕获
   flight→cache，直到被执行或队列丢弃。ThreadPoolExecutor 由 `removeFromQueue` 处理。
3. 自定义 Executor 抛非 `RejectedExecutionException` 的 `RuntimeException` 或 `Error` 时，飞行会被
   完整回滚（与原拒绝路径同一逻辑），已加入的订阅者收到 `onFailed`，原异常从 `request` 抛出；不再
   留下永不完成的死飞行（2026-11-05 修复）。
4. `close()` 与已 drain 未 post 的投递之间仍有窄竞态：deliver 的 `closed` 判断覆盖 close 先于
   deliver 获取锁的情形；若 close 发生在 deliver 通过判断之后、回调之前，回调仍可能执行（与 HEAD
   相同的既有限制，未扩大）。
5. 设备/ART 与真机未验证；以上为宿主 JVM 证据。
