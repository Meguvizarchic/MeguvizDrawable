# 原始测量输出（版本化证据）

本目录下的 `runs/**` 是**原始输出**，不是摘要。任何文档里引用的纳秒数、分配字节数都必须能在这些 CSV 里逐行找到；只写结论不带 CSV 的表述视为未验证。

## 环境与口径

- 主机：macOS / Apple M4 / 16 GB，JDK `21.0.12.1`（macOS aarch64）。
- 运行方式：HotSpot JVM，独立进程；`old` 与 `new` 交替（ABAB…）各 5 个 fork，每 fork 内多轮。**没有 ART、没有 Android 设备、没有模拟器**，因此这些数字只能说「在该主机 JVM 上测得」，不得外推到 ART 或真机。
- `old` = 基线提交 `b087b3f` 的干净副本；`new` = 第一轮改动后的工作树。归因见各 CSV 的 `build` 列。
- 探针源码：`benchmark/ModelProbe.java`、`benchmark/FlightCacheHitLatencyProbe.java`（纯 Java，无第三方依赖）。
- 编译/运行（在仓库根，以 `ModelProbe` 为例；`JAVA_HOME` 指向上述 JDK）：

  ```sh
  "$JAVA_HOME/bin/javac" -d /private/tmp/meguviz-probe \
      core/src/main/java/io/github/jichuo1/meguviz/drawable/model/*.java \
      benchmark/ModelProbe.java
  "$JAVA_HOME/bin/java" -cp /private/tmp/meguviz-probe ModelProbe
  ```

  `FlightCacheHitLatencyProbe` 还需要 `core` 下 cache/binding 相关源文件；见其文件头注释。两个探针都接受 `-Dprobe.*` 参数控制 fork 数、轮数与竞争模式，参数含义以源码为准。

## 文件

| 文件 | 列 | 支撑的结论 |
|---|---|---|
| `runs/2026-11-08-model-probe/model-probe.csv` | `build,fork,metric,round,value` | `hash_ns`、`map_get_equal_key_ns`、`alloc_ctor_unspecified_B` |
| `runs/2026-11-08-flightcache-hit/fc-hit-probe.csv` | `build,mode,fork,metric,round,value` | `peek_hit_ns`、`request_hit_ns`（`mode=single` 与竞争模式分开） |

## 已知局限（不要在别处把它们写成「已验证」）

1. 竞争模式离散度大，只作方向参考；只有 `mode=single` 的中位数/p95 被文档引用。
2. 没有 p50/p95/p99 的完整分布落盘，没有 bootstrap 置信区间；`benchmark/analyze.py` 期望的是 `median_paired_delta_percent` 列，与本目录 CSV 的列不同，**尚未接线**，不能拿它直接汇总本目录数据。
3. 仓库自带的性能脚本是 PowerShell（`*.ps1`），本机 macOS/Zsh 不能直接跑；本 README 的 `javac`/`java` 命令是等价的手动复现路径。补齐跨平台 `sh` 驱动属于下一轮工作。
4. 分配数来自 HotSpot `ThreadMXBean`，不是 ART 的 `Debug.startAllocCounting()`；「零额外分配」的说法只对被测量的构造路径成立。
