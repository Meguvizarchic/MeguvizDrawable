# FlightCache 票据引用清理设计

本文件记录 `core/.../cache/FlightCache.java`（随 `1.2.0` 发布）在票据终态的强引用清理改动与边界条件。公开 API 与单飞语义保持不变；仅补充“关闭前已入投递队列但尚未交付的结果，在缓存关闭后丢弃并计入 `droppedStale`”（由 `closeBeforeDrainDropsPostedDelivery` 断言）。

本设计属于**部分保留削减（partial retention reduction）**：`STARTED` / `JOINED` 票据在存续期仍需通过 `owner` 引用执行 `cancel()`，因此终态后长期持有未丢弃的 `Ticket` 仍可能经 `owner` 链保留共享缓存，调用方在结束观察后仍应及时释放票据引用。

## 改动前引用链与内存滞留原因

```text
Ticket → Sub → Flight → { producer (用户 Producer), key, task: Runnable }
                                  └── 捕获 FlightCache.this
Ticket → owner → cache
```

改动前存在以下滞留点：`run()` 仅移除 `flights` 映射而不清空 `Sub.flight`；`cancel()` 仅把订阅者移出列表而不清理 `Sub.flight`/`Sub.subscriber`；`close()` 与投递失败路径同理；`READY` / `REJECTED_*` 票据无取消能力却仍持有多余的 `owner` 引用。

## 清理方案（保持公开 API 不变）

1. **`READY` / `REJECTED_*` 票据置空 `owner`**：构造此类票据时传入 `owner = null`（`cancel()` 先检查 `subscription != null`）。
2. **终态断开 `Sub` 引用**：在 `cancel()`、`deliver()` 的 `finally`、`close()` 及投递失败分支中，于锁内将 `sub.subscriber` 与 `sub.flight` 置为 `null`；`deliver()` 在锁内读取局部 `target` 后在锁外执行回调。
3. **终态断开 `Flight.producer`**：在 `run()`、`RejectedExecutionException` 回滚、`computeNow` 取代、`close()` 与 `dropOneAbandoned()` 中清空 `flight.producer`（`run()` 已提前在锁内把 `producer` 读入局部变量）。
4. **`deliver()` 增加 `closed` 校验**：若缓存在投递入队后关闭，执行 `deliver()` 时直接丢弃并计入 `droppedStale`。
5. **`request()` 使用局部 `task` 提交**：防止锁外赋值后、调用 `workers.execute` 前被 `close()` 或 `computeNow()` 清空 `flight.task` 引发空指针。

注：`close()` 与 `computeNow()` 故意不清空 `flight.task`，以免与 `request()` 的锁外提交产生竞态；`ThreadPoolExecutor` 会通过队列移除清理已取消任务，自定义执行器中的已终止任务则由执行器自身队列生命周期决定。

## JMM 可见性与探针证据

- `sub.subscriber`、`sub.flight` 与 `flight.producer` 的全部读写均在 `lock` 监视器保护下完成，具有明确的同步先后序；`flight.task` 保持 `volatile`，由 `Executor.execute` 建立 happens-before。
- 探针 `benchmark/FlightRetentionProbe.java`（基于对比基线 `9f7aee2` 在 `javac --release 8` 下运行，通过反射检查字段及有界 GC `WeakReference`）测得：

| 场景 | 改前 `sub.flight/subscriber` | 改前 `producer/task` | 改前 GC 保留 | 改后 `sub.flight/subscriber` | 改后 `producer/task` | 改后 GC 保留 |
| --- | --- | --- | --- | --- | --- | --- |
| `started_complete` | 非空 | 非空 | `3/3` | `null` | `null` | `0/3` |
| `joined_complete` | 非空 | 非空 | `3/3` | `null` | `null` | `0/3` |
| `cancelled` | 非空 | 非空 | `3/3` | `null` | `null` | `0/3` |
| `closed` | 非空 | 非空 | `2/2` | `null` | `producer=null` | `0/2` |
| `poster_refused` | 非空 | 非空 | `3/3` | `null` | `null` | `0/3` |
| `ready` / `rejected_capacity` | `owner` 非空 | — | — | `owner = null` | — | — |

改前 `remaining_while_ticket_retained = 14/14`（`violations = 8`），改后 `remaining_while_ticket_retained = 0/14`（`violations = 0`）。

## 残余限制与适用范围说明

1. `STARTED` / `JOINED` 终态票据仍持有 `owner`，若调用方长期不释放该 `Ticket` 对象本身，仍会间接保留共享缓存。
2. 自定义非 `ThreadPoolExecutor` 执行器的待执行队列中，已取消的 `task` 仍持有 `flight → cache` 直至出队执行或被丢弃。
3. 执行器抛出非 `RejectedExecutionException` 的异常时，飞行完整回滚并通知已加入订阅者后重抛原异常。
4. `close()` 与已抽取未投递任务之间仍存在与初版相同的极窄竞态（若 `close()` 发生在 `deliver()` 通过 `closed` 检查之后、执行回调之前，该次回调仍可能触发）。
5. 以上探针与 `TicketRetentionContractTest`、`FlightCacheContractTest`（以及手动压力入口 `:core:contractStress`）均运行于宿主 JVM，真机 ART 内存回收行为须在设备侧单独观测。
