# 状态匹配 prepared matcher（opt-in）

## 结论

`StateMatcher.prepare(StateColorList)` 返回可复用的 `PreparedMatcher`，只暴露
`matchIndex(int[])`。它是显式 opt-in：默认 `StateMatcher.matchIndex` / `matches` 行为不变，
调用方自行持有 prepared 实例并重复使用才值得。

`RecipeDrawable` 自 2026-11-05 起**按实例惰性**持有一个 `PreparedMatcher`：首次
`STATEFUL_SOLID` 外观更新时才 `prepare` 一次，之后每次状态变化只做一次字段读 + 数组读。
不在构造期建表，因为 `ConstantState.newDrawable()` 与 XML inflation 会创建大量可能永不
变化状态的实例，而大表建表成本按 `(1<<K)*R` 外推可达数毫秒。宽规则或超预算时 `prepare`
返回委托 direct 的 fallback，行为与直接匹配一致。

## 有界共享缓存（`StateMatcherCache`）

`StateMatcherCache` 是进程级、有界、LRU 的编译表缓存，供 `RecipeDrawable` 在**每实例首次**
解析时调用一次 `acquire`：

- **键只含规则条件**（有序 `StateSpec` 列表），不含颜色：条件相同、颜色不同的两个列表共享
  同一张表。键在构造时算好 `hashCode`，查找不再重走每个条件。
- **准入**：distinct ID ≤ 8 无条件缓存；> 8 需**表负载**（`2 * 2^K`，不含键字节）≤ `maxWeight/8`。默认 1 MiB 下 K=16（128 KiB）恰好可缓存，K=17 起 `prepare` 本身已 fallback。**超限在 `prepare` 之前按 distinct-ID 扫描预估拒绝**，不付建表成本（K=16/R=64 的建表成本按 `(1<<K)*R` 预算与实测 `ns/填充操作` 外推为数毫秒，仓库内无该 fixture 的直接实测）。成本超过整个 `maxWeight` 的条目直接拒绝，避免「每次建表又立刻淘汰」的空转。委托 direct 的 fallback **不缓存**（无收益且会保留 rules 引用）。
- **权重**：表字节（`2 * 2^K`）+ 键保留字节；默认上限 1 MiB，按访问序 LRU 淘汰。键保留字节只计 `StateSpec[]` 数组本身，不含被共享的 `StateSpec` 对象，因此实际内存略高于记账值。
- **建表在锁外**，锁只保护映射操作，避免 K=16 的 ~2 ms 建表阻塞其他线程。访问序 `LinkedHashMap.get` 会改写链表，所以查找本身在监视器内串行；「每实例一次」量级可接受，每帧调用则需换并发映射 + 独立 LRU。
- **查询热路径绝不查缓存**：独立测量显示 `HashMap<StateColorList,…>` 在 R≥16 时每次 52–61 ns，比 direct（10–66 ns）更慢，因为 `StateColorList.hashCode` 是 O(R)。该测量来自未跟踪的 scratch 探针（`benchmark/build/scratch/StateMatcherProbe.java` 的 `cacheOverhead`），未并入已跟踪探针。

合同：`StateMatcherCacheContractTest`（31 项：同条件共享、异条件不共享、fallback 不缓存、
超限表在建表前拒绝、极小预算不空转、LRU 命中刷新、跨列表取色、权重淘汰、clear、
与 direct 逐点一致、8 线程并发只留一条且权重不重复计）。`:core:contractTest` 默认 148 项通过。

**ABI 变更**：`StateMatcherCache` 是新增公开类，`docs/abi/1.1.0/core-api.json` 的
`api_sha256` 由 `320ede76…` 更新为 `7763f8e4…`（导出类 53 → 54）。1.1.0 尚未发布，属
发布前 API 扩展。

逐点等价于本库 direct 匹配：同一规则顺序首匹配、正/负条件、spec 与 state 的 0 终止、
无匹配返回 -1、null 抛 `NullPointerException`。unknown/custom ID、state 中的负值、
`Integer.MIN_VALUE`、空数组、无终止符数组均保持原语义。

## 平台差异

AOSP `StateListDrawable.onStateChange` 在 `indexOfStateSet` 返回 -1 后会再用
`StateSet.WILD_CARD` 做第二次通配匹配。本库不这样做：`matchIndex` 返回 -1，由调用方
（`RecipeDrawable` 的 fallback 颜色）处理；空规则只是普通有序规则，不额外回退。
prepared 不改变这一点。

## 策略与边界

对规则列表收集去重后的条件 ID（正负取绝对值），设 K=distinct、R=规则数：

| 条件 | 策略 |
|---|---|
| K ≤ 16 且 R ≤ 32767 且 `(1<<K)*R ≤ 1<<22` | short 查表 |
| 其他 | 直接匹配（fallback） |

- 表项为 `short`，payload = `2 * 2^K` 字节；K=16 时 128 KiB（int 表则 256 KiB），
  另有 `ids` 数组。表建成时 `PreparedMatcher` 不持有 `rules`。
- 收集 ID 时一旦出现第 17 个不同 ID 立即停止并 fallback；`R > 32767` 直接 fallback。
- 预算只约束建表填充 `(1<<K)*R`；ID 扫描长度由输入决定，不对任意规则宣称绝对有界。

## 成本

- 编码 state：O(state 长度 × K)；查表：一次数组读。整体不是 O(1)。
- 准备：最坏 O((1<<K)*R)，受预算上限约束；不保证任意规则绝对有界。
- 只有重复使用才划算。探针（JDK 17 宿主，1M 次/轮，4 热身 + 7 测量，中位数）：

| fixture | K | R | prep ns | 表 B | direct ns | prepared ns | 加速 | 回本查询数 |
|---|---|---|---|---|---|---|---|---|
| pilot8 | 8 | 13 | 3900 | 512 | 26.3M | 8.8M | 2.98x | 224 |
| page2 | 2 | 2 | 100 | 8 | 4.9M | 3.1M | 1.55x | 58 |
| real4 | 4 | 4 | 200 | 32 | 5.9M | 4.0M | 1.46x | 108 |
| custom6 | 6 | 9 | 699 | 128 | 15.0M | 3.4M | 4.36x | 61 |
| short1 | 1 | 1 | 400 | 4 | 4.2M | 1.9M | 2.16x | 178 |
| wide20 | 20 | 9 | 24900 | 0 | 15.7M | 10.2M | n/a（fallback） | n/a |
| budgetFallback | 16 | 65 | 2199 | 0 | 182.0M | 192.4M | n/a（fallback） | n/a |

同一 fixture 多次运行有 ±20% 抖动；real4 观测到的区间约 1.26-1.63x。fallback 两列计时
走同一代码路径，比值无意义，标 n/a。

一次性或状态序列很短时，准备成本可能超过收益（break-even 列）。K 较大、规则互斥导致
建表扫描很长时，budget fallback 回到直接匹配。

## 证据与文件

- 实现：`core/src/main/java/io/github/jichuo1/meguviz/drawable/core/StateMatcher.java`
- 差分测试：`core/src/test/java/io/github/jichuo1/meguviz/drawable/core/StateMatcherFastPathTest.java`
  默认仅 224 项真实检查：代表掩码、少量固定种子差分、first-match/正负条件/终止符、K16/17、
  R32767/32768、预算 64/65（反射锁定 table 非空/为空）、unknown/custom/null 等关键语义。
- 核心默认合同总计 707 项；原穷举与广随机语料保留在手动压力入口，不接入默认 `check`。
  `gradlew :core:contractStress --offline --no-daemon` 显式运行原大语料；matcher 压力入口仍为
  426,900 项。计数表示实际断言，未聚合或隐藏循环；默认采样量和实际执行量均已缩减。
- 探针：`benchmark/StateMatcherProbe.java`；原始 CSV 在 `benchmark/build/`（本地忽略，不发布）
- 基线：`2690458` / `9f7aee2`，StateMatcher 源码相同

宿主 JVM 实验，不外推 Android/ART、帧耗时或设备收益。
