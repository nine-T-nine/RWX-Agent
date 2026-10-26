# task-010（2026-10-26）

## 目标

在 `evaluate/utils` 下提供回放反解析工具：把游戏内部 `.replay` 反向解析成命令执行文本 /
JSONL / 概要，并把样例产物写到 `evaluate/output`。

## 改动

- 新增 `evaluate/utils/replay.py`（自包含，纯标准库）：容器与块解析、`rc` 命令全字段解析
  （对应 `Command.deserializeCommand`，protocol 96）、内嵌 `gamesave` 元数据（地图路径、队伍表、
  开局单位 `objectId→类型`）、文本/JSONL/概要渲染、CLI（`--summary/--text/--jsonl/--out/--name/
  --team/--from-tick/--to-tick/--limit/--checksums/--strict/--index`）。文件开头即用法说明。
- 新增 `evaluate/utils/test_replay.py`：合成回放往返（含中文地图名/队名、gzip 存档块、系统命令、
  聊天、校验块）、截断/非回放容错、`--strict`、渲染过滤、`order_rows` JSON 安全、落盘，
  以及 `replays/` 全量回归（命令载荷必须逐字节精确消费）。
- 新增 `evaluate/utils/replay.md`（格式与用法文档）、`evaluate/utils/__init__.py`（公开导出）。
- 新增 `evaluate/output/README.replay.md`，并在 `evaluate/output/.gitignore` 追加
  `/replay-*.txt`、`/replay-*.jsonl`、`/replays.index.txt`（不影响既有的 `/commands-*.txt`）。
- 样例产物：`replay-lake-2p.txt|jsonl`、`replay-valley-pass-6p.txt|jsonl`、
  `replay-island-freeze-4p.txt`、`replays.index.txt`（全部样本概要）。
- 未改动引擎 Java/Kotlin 代码；`evaluate/utils/CommandRecorder.java` 与 `README.md` 属并行工作的
  在线记录器，未触碰（本工具文档另放 `replay.md`）。

## 验证

- `python -W error::ResourceWarning evaluate/utils/test_replay.py`：26 项全通过（含资源泄漏检查）。
- 真实样本回归：命令解析后 `剩余字节 == 0`（字段布局无漂移）；`endReplayMetaData.commandCount`
  与实际解析命令数一致。
- 端到端抽查：`--summary/--text/--jsonl/--out/--name/--index/--clock` 均按预期产出；
  中文路径与队名、AI 系统命令、`[system:...]`、空单位命令均正确渲染。
- 期间 `replays/` 由 22 个变为 10 个（19:59 被清理，非本工具所为；本工具只读样本），
  另有 1 个正在录制的文件被容错解析为"未完成录制"，未报错。

## 追加：tick → 游戏时间（2026-10-26 晚）

- 新增 `NOMINAL_MS_PER_TICK`、`Replay.anchors / ms_per_tick / time_basis / tick_to_ms /
  tick_to_seconds / clock`；`--clock` 在文本里加 `[分:秒.十分位]`，JSONL 加 `game_time_ms` / `clock`。
- 依据：`GameLogic.update` 里 `gameTimeMillis += f*16.6667`，而本仓库 10 个本地样本实测
  `frames × 16 == 结尾 time` 精确成立（1 tick = 16 ms）；有改速度命令时按 `resync`/结尾锚点
  分段线性估算，并在表头 `# clock` 与概要"时间口径"标注精确/估算。
- 文档补充"各列含义"（含 `units[0]=` 的成因：`BaseZone.commandUnitsToAttackClosestEnemy`
  无条件下发空单位命令）与"tick 与游戏时间"两节。

## 遗留

- 未跑 Gradle（本轮只加 Python 与文档，且仓库有并行的 Gradle/游戏实例在跑）。
- 单位 id 跨对局不可复用、新造单位类型不可反推——属录制格式固有限制，已在文档标注。
- 若后续需要 JVM 侧同能力，可在 `evaluate/utils` 加 Kotlin 实现复用同一字段表；当前 Python 版
  已覆盖评测与数据抽取需求。
- 并行工作把 `evaluate/utils/README.md` 改名为 `COMMAND_RECORDING.md`（在线记录器文档），
  回放工具文档仍为 `replay.md`；`evaluate/output/README.replay.md` 已被清理，不再重建。
