# 评估采集与 Replay 工具

三阶段目标、原始产物与完整运行顺序见[总 README](../README.md)，本文件集中说明工具接口、Replay格式及端口录制。所有命令从仓库根目录执行。

| 工具 | 职责 |
|---|---|
| `replay.py`、`__init__.py` | 离线解析Replay，输出summary/TXT/JSONL，提供Python API |
| `evaluate_replay.py` | 启动Java25 Headless真实重放、核验Replay、用命令日志重建 command/result 与原生归属，再生成第二、三阶段事件报告；必须传入同局commands |
| `paths.js` | 对局路径的唯一入口：由对局目录/命令日志/裸名字解析出工作区，定位各 `stage_N/` 与 `replays/<对局名>.replay`，并给出收敛后的规范文件名 |
| `CommandRecorder.java` | 在线记录CLI收包，只生成一份commands TXT；随core打包 |
| `EngineEvidence.java` | 引擎内采集任务/队列/伤害/死亡/经济和命令关联；随core打包 |

## Replay 离线解析

`replay.py` 把游戏内部录制的 `.replay` 反解析成**命令执行文本 / JSONL**，用于评测、复盘与
RL 数据抽取。纯 Python 标准库，不需要启动游戏或 JVM。

与同目录的 [`CommandRecorder.java`](CommandRecorder.java) 分工：

| 工具 | 数据来源 | 用途 |
|---|---|---|
| `CommandRecorder.java` | 本机 CLI Socket 收包（在线） | 记录完整CLI请求，含拒绝请求，不写独立stats |
| `replay.py` | `.replay` 文件（离线） | 还原对局里执行过的**全部**命令（含 AI、内置 UI 操作） |

## 用法

```powershell
python evaluate/utils/replay.py "replays/<对局名>.replay" --commands "evaluate/output/<对局名>/commands-<对局名>.txt"
```

从仓库根目录运行。带 `--commands` 时按对局归档到 `evaluate/output/<对局名>/replay/`，与两轮报告同目录；不带时退回 `evaluate/output/<回放安全文件名>/replay/`。两种方式都生成 `.summary.txt`、`.txt`、`.jsonl` 三个文件。命令文本附带游戏时间。当前 CLI 不接收 `--summary`、`--jsonl` 等选项；需要队伍、时间窗或条数过滤时使用下面的 Python API。

材料还没收敛时命令日志仍在 `evaluate/output/commands-<对局名>.txt`，先跑 [`tools/match_materials.js`](../tools/README.md) 搬进对局目录（回放也会一并改名成 `<对局名>.replay`），再执行上面的命令。

```python
from evaluate.utils import Replay

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

```text
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
| `[3:44.4]` | 同一 tick 换算出的**游戏时间**（CLI默认开启，API使用 `clock=True`），口径见表头 `# clock` |
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
JSONL 的 `noop` 字段把它们过滤掉。

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
{"tick":52,"time_s":0.832,"team":0,"team_name":"AI - Hard(t0)","type":"build","build":"custom:extractorT1","x":170.0,"y":170.0,"units":[288],"unit_types":["builder"],"noop":false}
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

字段不适用时为 `null` 或 `false`。旧 JSONL 缺少这些字段时应重新解析原 Replay，再运行 `evaluate_stage_1.js`。端口日志新增的 `[MM:SS:mmm]` 是收包游戏时间；Replay 文本的十分位显示只是展示，JSONL 的 `time_s` 保留毫秒。

## 格式要点

容器：

```text
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

```text
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

- 单位 id 是**运行期 objectId**，原版普通局内单调生成；跨局不能视为同一实体。只有内嵌存档的开局对象表能给出 `id → 类型`，重同步/替换实体需另审生命周期。
- 回放不记录"建造产生的新 id"，无法反推每个新单位是什么。
- 未完成录制（没有 `end`/`endReplayMetaData`）或损坏文件：默认容错，解析到出错处并标记
  `partial`（API `Replay.load(path, strict=True)` 则直接报错，CLI不提供strict选项）。
- 时间以 tick 为准；整局毫秒时长受游戏速度命令影响，别用毫秒倒推 tick。
- 普通遭遇战回放都带 mission 状态块；带"区域控制/传送门"快照的地图会跳过元数据（拿不到地图/队伍），
  命令流仍能正常解析。

## 测试

```powershell
python evaluate/test/test_replay.py                              # 直接跑
python -W error::ResourceWarning evaluate/test/test_replay.py      # 连资源泄漏一起卡
```

覆盖：合成回放全字段往返（含中文地图名/队名、gzip 存档块、系统命令、聊天、校验块）、
截断与非回放文件容错、API的strict报错、文本过滤、`order_rows` JSON 安全、`write_outputs` 落盘，
以及仓库 `replays/` 全部样本的回归（要求命令载荷逐字节精确消费、无字段漂移）。

测试源码与说明统一放在上级 [test/](../test/README.md)，不会随 `--evaluate` 自动执行。

## 第一轮如何使用命令字段

第一轮入口是上级目录的 `evaluate_stage_1.js`（原 `evaluate.js` 与 `evaluate_1.js` 已合并），它复用 `execution-mapping.js`。严格以日志 `player=N` 对齐Replay的 `team=N`：

- `build --count N` 对应一条底层 `build_count=N`；建筑坐标按20单位网格吸附，auto-place覆盖方环角点，内置建筑替换使用明确别名。
- 生产数量按每座工厂累计，动作ID大小写、目标、取消标志、外层queue分开检查。
- `push` 无集结点为一条attackMove；有集结点时为move加排队attackMove。
- 动态选择器名单不能完整还原时保留覆盖不可验证；竞争有限底层记录的重复请求保留ambiguous，同一底层记录只归属一次。
- 新旧 `queued` 不能代替 `command_queued`，旧导出缺少必要字段时重新解析Replay。

入口在保留原有节奏和映射产物的同时生成 `stage1_metrics.json`、`score.json`、`score.txt`，并把结果写入 `report.html`。`mapping_rules.json` 还会给出每条映射规则的审计说明；评分阈值、健康门与不计分项见 [Stage1 评分规则](../STAGE1_SCORING.md)。


## 在线命令录制

[`CommandRecorder.java`](CommandRecorder.java) 从游戏的本机 CLI Socket 收包入口记录完整命令，在 `evaluate/output/` 只生成一份 UTF-8 命令日志。重新编译并启动游戏即可使用，无需替换发送命令的客户端。2026-10-29 起取消独立 `-stats.txt` 及后台统计导出线程；汇总在离线评估时生成。评测结束后由 [`tools/match_materials.js`](../tools/README.md) 把这份日志搬进 `evaluate/output/<对局名>/commands-<对局名>.txt`，外面不再留第二份。

录制只在评测模式开启：启动参数加 `--evaluate`；或显式给出 `-Drwx.evaluate.dir=…` / `-Drwx.cli.recordingDir=<目录>` 并启用CLI。`--evaluate` 本身同时开启CLI，但不会自动录制本地Replay。普通对局即使开着CLI也不录制端口日志，避免把每条收包复制到磁盘拖慢帧率。

### 获取输出

```powershell
.\gradlew.bat :desktop:run '--args=--evaluate'
# 无窗口评测：
.\gradlew.bat :desktop:run '--args=--evaluate --headless --no-steam'
```

启动日志的 `RWX_CLI_COMMAND_RECORDING log=...` 行给出命令文件的绝对路径。默认在仓库的 `evaluate/output/` 中，每个监听实例独立生成：

- `commands-地图名-日期--时分.txt`（如 `commands-Ice-Island-(2p)-20261029--20-40.txt`）：每条接收到的完整命令，实时追加并刷新。启动时还没有地图，先命名为 `commands-lobby-日期--时分.txt`；第一局开始后改名，大厅期间的命令仍保留在同一文件。时分是记录器启动时的本地时间（24 小时制 `HH-mm`），改名后保持不变。地图名中的空格和 Windows 非法字符替换为单个 `-`；同一进程内的后续对局继续写这个文件，由 `match` 字段区分。同一分钟内启动两次时后者自动加 `-2`、`-3` 后缀（同一分钟、同一地图的改名同理），不会覆盖已有文件。

改名后会再打印一行相同格式的 `RWX_CLI_COMMAND_RECORDING`，其中的路径为最终文件名。

每次收包立即 flush；`export()` 仅刷新当前命令文件，`close()` 刷新并关闭文件。需要自定义目录时，在启动 JVM 时设置 `-Drwx.cli.recordingDir=<目录>`。目录不可写时游戏日志会说明记录失败，命令本身继续按原流程执行。

### 读取命令与时间

```text
[01:05:432]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-attack-mode --apply-to-new --mode off>
[01:06:001]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-attack-mode --units 42 --mode holdFire>
[01:08:000]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-push --units 42 --x 300 --y 400>
```

`[分:秒:毫秒]`（`[MM:SS:mmm]`）是收到命令时的当前对局时间，直接读取并完整保留 `GameEngine.gameTimeMillis`，在命令执行前冻结；毫秒固定三位，例如 `[00:04:007]`。暂停时不前进，加速时跟随游戏时间；这是游戏时钟，精度受模拟步进限制，并非墙钟。超过一小时显示 `[60:00:000]`，分钟不归零。大厅或尚未进入对局的命令显示 `[--:--:---]`，`match=none`。

2026-10-28 起的新日志使用毫秒格式；`evaluate_stage_1.js` 同时兼容旧 `[MM:SS]` 与新 `[MM:SS:mmm]`。已有日志丢失的毫秒无法还原，不会用回放执行时间或补零冒充真实收包时间。

执行者取该端口所属游戏实例的当前控制玩家：对局中取 `engine.playerTeam`，大厅中取网络本地玩家。`player` 是引擎的玩家槽 ID，`faction` 是联盟阵营及其原始 ID，两者可以不同。旁观者标为 `role=spectator`，尚未分配玩家时标为 `unassigned`，不根据命令的 `--units` 或目标参数猜测执行者。

`match` 区分同一进程内的连续对局；中途加入时游戏时间可以大于零。对局边界通过引擎现有的每帧任务队列观察，独立于命令到达时间。命令文件只写收包记录，不插入对局首部或统计段；本地开局日期/时区不再单独导出，Replay 文件名及其元数据可供核对。

纯文本命令保留收到的内容；`tab + base64 argv` 协议解码为可读的完整命令，有空格或空值的参数加引号。日志中的反斜杠、换行、制表符及尖括号使用 `\\`、`\n`、`\t`、`\u003c`、`\u003e` 等转义，确保一条完整命令只占一行。

### 记录口径

包含查询、`rwx-ping`、`rwx-help`、未知命令及参数验证失败的命令。`!!framed` 属于传输握手，不计为命令。误发到端口的 `Start-Sleep` 等非 RWX 命令也保留原文；离线评估将其列为 C 类未知命令，不作为主动 B，也不中断后续记录。

每个实例只记录发到自身 CLI 端口的命令。要覆盖联机双方，分别收集双方实例的输出；同一端口上的多个外部程序共享游戏内玩家身份，协议本身没有外部操作者 ID。鼠标操作、游戏网络包和自动微操内部派生的动作不作为额外 CLI 命令重复统计。

### 集成位置

- 收包入口：[`RwxCliTool.serveClient`](../../core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliTool.java)，握手处理后、`handle()` 前记录。
- 游戏身份与时间桥接：[`RwxCliCommandCapture`](../../core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliCommandCapture.java)。通过现有帧任务队列观察对局进入，无需修改原版引擎生命周期。
- `core/build.gradle.kts` 将 `evaluate/utils` 加入 Java 源目录，工具随 core 一起打包，GUI 和 Headless 共用。
