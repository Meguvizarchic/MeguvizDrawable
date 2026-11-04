# 状态匹配 prepared matcher（opt-in）

## 结论

`StateMatcher.prepare(StateColorList)` 返回可复用的 `PreparedMatcher`，只暴露
`matchIndex(int[])`。它是显式 opt-in：默认 `StateMatcher.matchIndex` / `matches` 与
`RecipeDrawable` 行为不变，调用方自行持有 prepared 实例并重复使用才值得。

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
