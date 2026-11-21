# 状态匹配预编译与共享缓存（PreparedMatcher & StateMatcherCache）

## 概述

`StateMatcher.prepare(StateColorList)` 返回可复用的 `PreparedMatcher`（仅暴露 `matchIndex(int[])`）。默认的 `StateMatcher.matchIndex` 与 `matches` 保持直接遍历匹配；当同一套状态规则会被高频查询时，调用方持有 `PreparedMatcher` 可消除重复扫描。

`RecipeDrawable` 采取**按实例惰性解析**：不在构造阶段或 XML inflate 时建表（避免大量永不切换状态的实例白白支付建表开销），而是在首次 `STATEFUL_SOLID` 外观更新时向 `StateMatcherCache.shared()` 查询或构建一次 `PreparedMatcher`，之后每次状态切换仅执行一次字段读与表读取。若规则过宽或超出建表预算，`prepare` 会自动返回委托至直接匹配的 fallback 实例。

## 有界共享缓存：`StateMatcherCache`

`StateMatcherCache` 为进程级、按权重上限管理的有界 LRU 缓存，供 `RecipeDrawable` 在每实例首次解析时调用 `acquire`（该类随 `1.2.0` 正式发布）：

- **缓存键仅含条件列表**：键为有序 `StateSpec` 数组而不含颜色值。规则条件相同但配色不同的两个 `StateColorList` 共享同一张查找表；键在构造时预计算 `hashCode`。
- **缓存接收策略**：去重后的条件 ID 数 `distinct <= 8` 时无条件接收；`distinct > 8` 时要求表载荷（`2 * 2^K` 字节，不含键自身开销）不超过 `maxWeight / 8`。在默认 `1 MiB` 上限下，最大可编译表 `K = 16`（`128 KiB`）恰好允许入缓存，而 `K >= 17` 在 `prepare` 阶段已直接走 fallback。超限表会在 `prepare` 之前按 `distinctIds` 预扫描直接拒绝，避免支付大表构建成本；单条总成本超过 `maxWeight` 的表亦直接拒绝，防止每次建表即被淘汰的空转。委托直接匹配的 fallback 绝不入缓存。
- **权重记账**：总权重按表载荷（`2 * 2^K`）与键数组自身引用保留字节累计（默认上限 `DEFAULT_MAX_WEIGHT = 1 MiB`），基于访问序 `LinkedHashMap` 按 LRU 淘汰（注：因共享 `StateSpec` 对象本身未计入键字节，实际内存占用略高于记账值）。
- **锁外建表与查表边界**：查表和建表均在锁外执行，监视器仅保护缓存映射读写，避免 `K = 16`（约数毫秒）的建表阻塞其他线程。`StateMatcherCache` 设计前提为“每实例初始化调用一次”，切忌在每帧或每次状态查询时直接查询共享 `HashMap`（独立探针测得 `R >= 16` 时 `HashMap<StateColorList, ...>` 每次查询达 `52–61 ns`，慢于直接匹配的 `10–66 ns`）。

## 等价性与 platform 差异

`PreparedMatcher` 与直接匹配在所有输入下逐点等价：相同有序规则首匹配、正/负条件语义、`0` 提前终止、无匹配返回 `-1`、`null` 抛 `NullPointerException`，并同样保留 unknown/custom ID、负数状态、空数组与无终止符数组的行为。

**与 AOSP `StateListDrawable` 的差异**：AOSP 在 `indexOfStateSet` 返回 `-1` 后会用 `StateSet.WILD_CARD` 再做一次通配匹配；本库不做二次通配，`matchIndex` 返回 `-1` 后交由 `RecipeDrawable` 的 `fallbackColor` 处理，空 `StateSpec()` 只是按序排列的一条普通规则。

## 编译策略与预算上限

设去重后的条件 ID 数为 `K`（取正负条件绝对值）、规则条数为 `R`：

| 条件 | 策略 |
| --- | --- |
| `K <= 16` 且 `R <= 32767` 且 `(1 << K) * R <= 1 << 22` | 编译为 `short[]` 查找表 |
| 其他情况 | 回退直接匹配（fallback） |

- `short[]` 表在最坏 `K = 16` 时载荷为 `2 * 2^16 = 128 KiB`（使用 `int` 则需 `256 KiB`），且建表完成后不保留 `StateColorList` 引用。
- 扫描条件 ID 时一旦发现第 `17` 个不同 ID 立即中止扫描并回退；`R > 32767` 直接回退。
- 预算上限约束表填充循环 `(1 << K) * R`，前缀 ID 扫描长度取决于调用方输入长度。

## 复杂度与宿主探针数据

- 单次状态编码复杂度为 O(`state.length * K`)，查表为一次数组读；准备期最坏为 O(`(1 << K) * R`)（受预算保护）。
- 仅在状态会被多次触发的控件上能摊销建表成本。宿主 JVM 探针（`benchmark/StateMatcherProbe.java`，JDK 17，每轮 100 万次，4 轮热身 + 7 轮测量取中位数）：

| 夹具 | K | R | 建表耗时 (ns) | 表大小 (B) | direct (ns) | prepared (ns) | 加速比 | 回本查询次数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `pilot8` | 8 | 13 | 3,900 | 512 | 26.3M | 8.8M | 2.98x | 224 |
| `page2` | 2 | 2 | 100 | 8 | 4.9M | 3.1M | 1.55x | 58 |
| `real4` | 4 | 4 | 200 | 32 | 5.9M | 4.0M | 1.46x | 108 |
| `custom6` | 6 | 9 | 699 | 128 | 15.0M | 3.4M | 4.36x | 61 |
| `short1` | 1 | 1 | 400 | 4 | 4.2M | 1.9M | 2.16x | 178 |
| `wide20` | 20 | 9 | 24,900 | 0 | 15.7M | 10.2M | n/a（fallback） | n/a |
| `budgetFallback` | 16 | 65 | 2,199 | 0 | 182.0M | 192.4M | n/a（fallback） | n/a |

多次运行存在约 `±20%` 抖动；fallback 夹具两侧走同一直接匹配分支，因此不计算加速比。

## 适用范围与验证说明

- 实现位于 `core/src/main/java/io/github/jichuo1/meguviz/drawable/core/StateMatcher.java` 与 `StateMatcherCache.java`，由 `StateMatcherFastPathTest`（默认 224 项精准检查）与 `StateMatcherCacheContractTest`（31 项）覆盖，完整穷举与差分语料可通过 `./gradlew :core:contractStress --offline --no-daemon` 运行。
- 上述实验数据全部来自宿主 HotSpot JVM（对比基线 `2690458` / `9f7aee2`），不外推至 Android ART 性能、设备帧耗时或真实功耗收益。
