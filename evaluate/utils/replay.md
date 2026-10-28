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
python evaluate/utils/replay.py "replays/xxx.replay"
```

从仓库根目录运行，只传一个 `.replay` 路径；自动生成 `evaluate/output/<安全文件名>/` 下的 `.summary.txt`、`.txt`、`.jsonl` 三个文件。命令文本附带游戏时间。当前 CLI 不接收 `--summary`、`--jsonl` 等选项；需要队伍、时间窗或条数过滤时使用下面的 Python API。

```python
from replay import Replay            # 或 from utils.replay import Replay

r = Replay.load("replays/xxx.replay")
r.header.version_string              # "1.15"
r.meta.map_path                      # "maps/skirmish/[p2]Lake (2p).tmx"
r.meta.teams[0].name                 # 队伍名（来自内嵌存档）
for cmd in r.commands:
    print(cmd.tick, cmd.team_id, cmd.unit_command and cmd.unit_command.type)
print(r.render_text(team=1, from_tick=600, to_tick=3000, limit=20, clock=True))
rows = r.order_rows()                # 结构化命令（JSON 友好）
```

## 输出长什么样

```
# file   : [local] Island freeze (4p) [by hxyy] [v1.15] (24 Oct 2026 13.08.27).replay
# build  : v1.15 (versionCode 176, protocol 96)
# map    : maps/skirmish/[p4]Island freeze (4p) [by hxyy].tmx
# teams  : 0=AI - Very Hard, 1=AI - Very Hard, 2=Player, 3=AI - Very Hard
# frames : 90235  duration: 1443.8s  commands: 18438  chats: 0  resyncs: 0
# clock  : 16.000 ms/tick（90235 tick = 1443760 ms），整局速率恒定，换算精确
# status : 完整
t=14027  [3:44.4] AI - Very Hard(t1)   attack -> #187                                 units[0]=
t=14027  [3:44.4] AI - Very Hard(t1)   attack -> #100                                 units[0]=
t=14077  [3:45.2] AI - Very Hard(t1)   attack -> #412                                 units[1]=#426
```

### 各列含义

| 列 | 含义 |
|---|---|
| `t=14027` | 命令被执行时的**模拟 tick**（不是秒，也不是"第几条命令"） |
| `[3:44.4]` | 同一 tick 换算出的**游戏时间**（`--clock` 才有），口径见表头 `# clock` |
| `AI - Very Hard(t1)` | 下令的队伍（`t` 后是引擎的队伍槽 id）；`TEAM_ALL` 表示全体/系统 |
| `attack -> #187` | 指令类型与目标；`->` 后面是**目标单位 id**（`@(x,y)` 表示坐标目标） |
| `units[...]` | **这条命令实际作用的单位 id 列表**（引擎里的 `selectedUnits + pendingUnitIds`） |
| `units[0]=` | 空列表：命令发出去了，但**执行时没有任何单位可作用**，等于空指令（no-op） |

`units[0]=` 很常见，不是解析错误。典型来源是 AI 的"区域被攻击"反应：

```java
// BaseZone.commandUnitsToAttackClosestEnemy()
for (每个己方单位) if (能交战 && 可选 && 没有当前 waypoint && 可达) command.addUnitToCommand(unit);
command.setAttackTarget(closestEnemyUnit);   // ← 无条件下发，即使一个都没加进去
```

所以同一条 `attack -> #187` 有时是 `units[1]=#426`（真下给某个单位），有时是 `units[0]=`
（当时没有空闲单位，命令执行后什么都不发生）。做数据集抽样时建议用
`--jsonl` 的 `noop` 字段把它们过滤掉。

## tick 与游戏时间

- `gameTimeMillis` 在 `GameLogic.update` 里每 tick 推进 `stepRate × 16.6667` 毫秒；本地
  1× 速度的对局实测正好是 **16.000 ms/tick**，且 `结束帧数 × 16 == 结尾 time` 精确成立
  （本仓库 10 个本地样本全部满足），所以 `tick × 16 ms` 在这些对局里**没有偏差**。
- 一旦出现改速度（`[system:... speed=N]` 或联机步进调整），每 tick 的毫秒数就变了
  （历史上见过 19.9 / 32.8 ms/tick 的样本），此时工具按 `resync` 快照与结尾元数据做
  **分段线性估算**，并在表头 `# clock` / 概要"时间口径"里写明"精确"还是"估算"。
- 能换算的是**游戏时间**（`gameTimeMillis`，HUD 上那个计时）。**真实墙钟时间无法还原**：
  暂停、加速、掉帧都不进 tick，回放也不记录。
- 想自己算：`Replay.ms_per_tick` 是整局平均，`Replay.tick_to_ms(tick)` / `Replay.clock(tick)`
  走锚点插值，`Replay.anchors` 给出所有 `(tick, gameTimeMillis)` 锚点。

```python
r = Replay.load("replays/xxx.replay")
r.ms_per_tick            # 16.0
r.tick_to_ms(14027)      # 224432
r.clock(14027)           # "3:44.4"
r.time_basis             # "16.000 ms/tick（…），整局速率恒定，换算精确"
```

JSONL 一行示例：

```json
{"tick": 52, "time_s": 0.832, "team": 0, "team_name": "AI - Hard(t0)",
 "type": "build", "build": "custom:extractorT1", "x": 170.0, "y": 170.0,
 "units": [288], "unit_types": ["builder"], "noop": false, ...}
```

用于双源评测的附加字段：

| 字段 | 引擎含义 |
|---|---|
| `command_queued` | `Command.isQueued`，CLI `--queue` 的真正标志 |
| `waypoint_queued` | `UnitCommand.isQueued`，独立于外层队列；旧 `queued` 保持历史含义 |
| `build_count` | `UnitCommand.buildQueueSize`；`build --count N` 是一条记录 |
| `rally` | `Command.rallyPoint` 的 `[x,y]`；没有 waypoint 时 `type=null` |
| `target_point` | `Command.targetPoint`，如核弹/技能的目标坐标 |
| `stop_current_action` | 取消生产/升级的标志，需要与普通 action 区分 |
| `clear_existing_orders` | `order-stop` 的清空 waypoint 标志 |
| `is_high_priority` | CLI move/attackMove 的原生命令标志 |

字段不适用时为 `null` 或 `false`。旧 JSONL 缺少这些字段时应重新解析原 Replay，再运行 `evaluate.js`。端口日志新增的 `[MM:SS:mmm]` 是收包游戏时间；Replay 文本的十分位显示只是展示，JSONL 的 `time_s` 保留毫秒。

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
