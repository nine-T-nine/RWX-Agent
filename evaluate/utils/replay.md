# 回放反解析（`.replay` → 命令执行文本）

`replay.py` 把游戏内部录制的 `.replay` 反解析成**命令执行文本 / JSONL**，用于评测、复盘与
RL 数据抽取。纯 Python 标准库，不需要启动游戏或 JVM。

与同目录的 [`CommandRecorder.java`](CommandRecorder.java) 分工：

| 工具 | 数据来源 | 用途 |
|---|---|---|
| `CommandRecorder.java` | 本机 CLI Socket 收包（在线） | 记录实际下发过的 CLI 命令与统计 |
| `replay.py` | `.replay` 文件（离线） | 还原对局里执行过的**全部**命令（含 AI、内置 UI 操作） |

## 用法

```powershell
# 概要：地图、队伍、帧数、各队/各指令命令数、文件健康度
python evaluate/utils/replay.py "replays/xxx.replay" --summary

# 命令文本（一行一条命令）
python evaluate/utils/replay.py "replays/xxx.replay" --text --limit 50

# JSONL（一行一个 JSON 对象，喂数据集）
python evaluate/utils/replay.py "replays/xxx.replay" --jsonl

# 只关心某个队伍 / 某个时间窗
python evaluate/utils/replay.py "replays/xxx.replay" --text --team 1 --from-tick 600 --to-tick 3000

# 落盘到 evaluate/output（--name 覆盖输出主名）
python evaluate/utils/replay.py "replays/xxx.replay" --text --jsonl `
  --out evaluate/output --name replay-xxx

# 多文件概要汇总成 evaluate/output/replays.index.txt
python evaluate/utils/replay.py <多个文件> --summary --out evaluate/output --index
```

```python
from replay import Replay            # 或 from utils.replay import Replay

r = Replay.load("replays/xxx.replay")
r.header.version_string              # "1.15"
r.meta.map_path                      # "maps/skirmish/[p2]Lake (2p).tmx"
r.meta.teams[0].name                 # 队伍名（来自内嵌存档）
for cmd in r.commands:
    print(cmd.tick, cmd.team_id, cmd.unit_command and cmd.unit_command.type)
print(r.render_text(limit=20))
rows = r.order_rows()                # 结构化命令（JSON 友好）
```

## 输出长什么样

```
# file   : [local] Valley Pass (6p) [v1.15] (23 Oct 2026 10.04.08).replay
# build  : v1.15 (versionCode 176, protocol 96)
# map    : maps/skirmish/[p6]Valley Pass (6p).tmx
# teams  : 0=AI - Hard, 1=AI - Hard, 2=AI - Hard, 3=AI - Hard, 4=Player, 5=AI - Hard
# frames : 42713  duration: 683.4s  commands: 2868  chats: 1  resyncs: 0
# status : 完整
t=52     AI - Hard(t0)        build custom:extractorT1 @(170,170)            units[1]=#288(builder)
t=102    AI - Hard(t0)        action u_builder                               units[1]=#287(commandCenter)
t=1302   AI - Hard(t0)        build landFactory @(805,1680)                  units[1]=#4(builder)
```

- `t=` 是**执行 tick**（标称 62.5 tick/s ≈ 16 ms/tick）。
- `units[...]` 是这条命令作用的单位 id；开局单位能显示类型（`#288(builder)`），之后新造的单位只有 id。
- `[system:...]` 是系统命令（改游戏速度 / 投降 / 快速重同步 / 刷兵）。

JSONL 一行示例（`--jsonl`）：

```json
{"tick": 52, "time_s": 0.832, "team": 0, "team_name": "AI - Hard(t0)",
 "type": "build", "build": "custom:extractorT1", "x": 170.0, "y": 170.0,
 "units": [288], "unit_types": ["builder"], "noop": false, ...}
```

## 格式要点

容器：

```
UTF "rustedWarfareReplay" | int versionCode | int protocol(96) | UTF version | bool demo
之后：UTF blockName | int payloadLen | payload[payloadLen]        （块载荷可能是 gzip）
```

| 块 | 载荷 | 说明 |
|---|---|---|
| `gamesave` | 完整初始存档（内层 gzip） | 取地图路径、队伍表、初始单位 `id→类型` 表 |
| `rc` | `int tick` + 内嵌块 `c` | 一条命令（字段见下） |
| `chat` | `int tick, int senderId, UTF? name, UTF? text` | 聊天，senderId=-1 为系统消息 |
| `wait` | `int tick` | 时间屏障 |
| `cs` | `int tick, long checksum` | 粗校验，每 6 条命令一条 |
| `es` | `int tick, int len, [byte flag, int n, long×n]` | 全量校验，每 300 tick 一条 |
| `resync` | `int tick, int resyncTick, int ms, float stepRate, float k, int len, bytes` | 重同步快照 |
| `end` | 空 | 正常结束 |
| `endReplayMetaData` | `byte, int frames, int ms, int commands, int resyncs, UTF 摘要` | 帧数/命令数（排在 `end` 之后） |

`rc` 内层块 `c` 的字段顺序（protocol 96，与
[`Command.deserializeCommand`](../../core/src/main/java/com/corrodinggames/rts/gameFramework/Command.java)
一一对应）：

```
byte teamId | bool hasUnitCommand
  UnitCommand: int type | int buildType(-1 无 / -2 跟自定义名 / 其它为枚举序号)
             | float x | float y | long targetUnitId | byte buildQueueSize
             | float attackMoveRange | float maxWayPointSurvivingTime
             | bool isRepeating | bool isQueued | bool isForceMove | UTF? actionId
bool isQueued | bool stopCurrentAction | int（遗留 actionId，恒 -1）
int attackMode | bool hasRally + float×2 | bool clearExistingOrders
int unitCount + long×N | bool hasSourceTeam + byte | bool hasTargetPoint + float×2
long targetUnit | UTF actionId | bool isInstant | short allowedTeamMask
bool isSystemAction [+ byte, float gameSpeedChange, float systemFloat, int systemActionType]
int commandTargetCount + [long unitId, float×4, int createdTick, int movementType, bool hasPath, (Path)]
bool isHighPriority
```

`Path`（`commandTargets` 里）是一个 gzip 的 `p` 块：`int count, short x, short y, byte×N` 差分编码。

## 已知限制

- 单位 id 是**运行期 objectId**，跨对局不复用；只有内嵌存档的开局对象表能给出 `id → 类型`。
- 回放不记录"建造产生的新 id"，无法反推每个新单位是什么。
- 未完成录制（没有 `end`/`endReplayMetaData`）或损坏文件：默认容错，解析到出错处并标记
  `partial`（`--strict` 则直接报错）。
- 时间以 tick 为准；整局毫秒时长受游戏速度命令影响，别用毫秒倒推 tick。
- 普通遭遇战回放都带 mission 状态块；带"区域控制/传送门"快照的地图会跳过元数据（拿不到地图/队伍），
  命令流仍能正常解析。

## 自测

```powershell
python evaluate/utils/test_replay.py                              # 直接跑
python -W error::ResourceWarning evaluate/utils/test_replay.py    # 连资源泄漏一起卡
```

覆盖：合成回放全字段往返（含中文地图名/队名、gzip 存档块、系统命令、聊天、校验块）、
截断与非回放文件容错、`--strict` 报错、文本过滤、`order_rows` JSON 安全、`write_outputs` 落盘，
以及仓库 `replays/` 全部样本的回归（要求命令载荷逐字节精确消费、无字段漂移）。
