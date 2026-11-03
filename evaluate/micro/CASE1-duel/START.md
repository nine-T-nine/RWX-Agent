# CASE1-duel 参与说明

你将操控一组机枪机甲，在给定地图和时限内消灭全部敌方猛犸坦克。本文说明任务、公开游戏规则和参与流程；[commands.md](commands.md) 定义完整命令接口。

## 0. 启动与连接

所有命令在仓库根目录执行，使用 Windows PowerShell。正式评测的 `task`、`seed`、`track` 和预算由评测方指定；不要自行更换。下面以 `task=1_2_2,seed=1,track=pause` 示范启动格式；练习时未指定参数可使用这一组。省略 `seed` 时会随机生成；想复盘就记下实际使用的 seed。

```powershell
New-Item -ItemType Directory -Force agent-operation/tmp/micro-a | Out-Null

# 在另一个终端运行；启动命令会持续占用该终端
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=pause --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'
```

- GUI模式：把 `--headless` 换成 `--window`（两者互斥，其余参数不变）：

```powershell
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=pause --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'
```

上面的单次调用适合连通性确认或偶发查询。整局操作优先复用连接，按下面的场景选择；客户端选项（如 `--port-file`、`--stdio`）放在游戏命令之前。`rwx-micro-1-help` 返回完整命令手册。

首次启动可能需要几分钟构建。等待 stdout 出现 `RWX_MICRO_READY case=CASE1-duel phase=briefing tick=0` 后，通过端口文件连接；不要硬编码端口。如果评测方已启动实例，直接使用其提供的端口文件，不再启动一局。

一局只连接一个指定实例。stdout 中的 `RWX_MICRO_RUN <目录>` 是评测证据位置，参与过程中不得读取，详见 §6。

### 0.1 pause：逐回合观察与决策

人工操作适合 `--interactive`：同一个终端逐行输入命令，复用 TCP 连接，直接显示文本回复。

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/micro-a/port --interactive
```

以下是**客户端内的输入**，每次输入一行，读完回复再决定下一步，不要整段预先粘贴：

```text
rwx-micro-1-state
rwx-micro-1-units
rwx-micro-1-begin
rwx-micro-1-units
rwx-micro-1-next
```

`begin` 推进第一回合并自动暂停；此后在暂停期观察、按局面发送 B 类命令，再用 `next` 推进一回合。`next` 默认附带单位状态，可直接据此决定下一步；只需回执时使用 `next --quiet`。`exit`、`quit` 或 `:q` 只退出交互客户端，不退出游戏。

Agent 自动逐回合操作优先用下节的 `--stdio`，按“读取状态 → 下令 → `begin`/`next` → 读取结果”的顺序循环；暂停不消耗游戏时间，但仍消耗墙钟预算。

### 0.2 realtime：用持久标准流持续操作

Agent 宿主能保留子进程和标准流时，优先启动一次 `--stdio` 并整局复用，减少反复启动 Python、发现端口和建立连接的开销。pause 的自动操作也适用。

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/micro-a/port --stdio
```

1. 宿主保留该进程及其 stdin、stdout、stderr，等待 stderr 输出 `ready`。
2. 向 stdin 写入一行 `rwx-micro-1-state --json` 并 flush，读取 stdout 的一行 JSON。
3. 每次读取对应回复后，再发送下一条命令。例如 briefing 时发送 `rwx-micro-1-units --json` 观察，准备好后发送 `rwx-micro-1-begin`，之后根据最新观察下令。
4. realtime 的 `begin` 后持续运行；交替发送查询和操作，不发送 `next`。人工实时操作也可以使用上节的 `--interactive`。

`--stdio` 每条回复是 `{"error":false,"reply":"..."}` 这样的单行 JSON；多行游戏回复保存在 `reply` 字符串中。游戏命令带 `--json` 时，`reply` 仍是字符串，需要再解析一次才能得到状态对象。先检查外层 `error`，再读取游戏回复；诊断信息从 stderr 读取。

仅执行一次启动命令并不能保证后续工具调用复用连接，宿主必须持续保留进程及标准流。关闭 stdin 退出客户端。realtime 中查询、思考和传输期间游戏都在推进，应按需查询；`--stdio` 不会自动轮询或替你决策。

### 0.3 短批次：一次发送已确定的命令

`--batch-file` 适合已经确定的几条连续命令，两种赛道都可用。例如一次查询阶段和全部单位：

```powershell
@'
rwx-micro-1-state
rwx-micro-1-units
'@ | Set-Content -Encoding utf8 agent-operation/tmp/micro-a/observe.txt
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/micro-a/port --batch-file agent-operation/tmp/micro-a/observe.txt
Remove-Item -LiteralPath agent-operation/tmp/micro-a/observe.txt
```

文件为 UTF-8，每行一条命令，空行和 `#` 注释会被忽略。批次内复用连接，逐条发送并等待回复，但不会根据回复调整后续命令，也不是原子事务。pause 适合提交本回合已决定的命令后推进一次，再回到观察；不要批量连续 `next` 跳过需要决策的回合。realtime 适合短批次，批次执行期间游戏继续推进，各条查询不保证来自同一 tick。

宿主无法保留进程时，可继续使用本节开头的单次调用，或用短批次减少启动次数。超时或断线后先连接并查询状态，再判断是否需要重新下令，避免重复提交可能已经执行的操作。

## 1. 任务与评价

我方 team 0 为机枪机甲 `mechMinigun` × a；敌方 team 1 为猛犸坦克 `c_mammothTank` × b。没有建造、生产或增援，单位损失无法补充。

任务编码为 `task=1_<mapType>_<level>`：

| 段 | 含义 | 取值 |
|---|---|---|
| 第一段 `1` | CASE1 的任务系列编号 | 固定为 `1` |
| 第二段 `mapType` | 地图类型 | `1` 随机地图；`2` 环湖地图 |
| 第三段 `level` | 双方数量关系 | `1` 我方多一台；`2` 数量相同；`3` 敌方多一台；`4` 敌方多两台 |

例如 `1_2_2` 表示 CASE1、环湖地图、双方数量相同。同一 `(task, seed)` 的地图和出生布景相同；实际单位数量用 `units` 查询。

| level | 数量规则 |
|---|---|
| `1` | `a=b+1`，`1≤b≤4` |
| `2` | `a=b`，`1≤a≤4` |
| `3` | `b=a+1`，`1≤a≤4` |
| `4` | `b=a+2`，`1≤a≤3` |

结局由引擎判定：

| outcome | 含义 |
|---|---|
| `win` | 敌方单位全部被消灭，我方仍有单位存活 |
| `loss` | 我方单位全部被消灭，敌方仍有单位存活 |
| `draw` | 同一 tick 内双方全部被消灭 |
| `timeout` | 到达墙钟或游戏时间上限，尚未分出胜负 |
| `error` | 对局异常，例如一直没有接入命令而触发启动超时 |

**任务成功条件是 `win`。胜负只由引擎判定，局内没有可见的加分或扣分。** 评测方在对局结束后另做离线指标与行为分析（口径不在局内公布，也不改变胜负）。超时不算胜利，也不被规定为优于失败。不同赛道分别评价；局间比较以相同 `task`、`seed` 和赛道为条件。

## 2. 赛道、时间与预算

实际赛道由本次启动的 `track` 决定，不能在局内切换。两条赛道都从 `phase=briefing`、`tick=0` 开始，游戏时间暂不推进。可以观察；默认也可以下令。两条赛道均不要求预先编排完整命令序列。

| track | 开始与推进 | 操作时的时间约束 |
|---|---|---|
| `realtime` | `begin` 后以 1× 连续推进，之后不能暂停或变速；没有 `next` | 查询、思考、传输和下令期间游戏继续运行 |
| `pause` | `begin` 运行第一回合；此后每次 `next` 运行固定回合并自动暂停，默认每回合 60 tick | 暂停期间可以查询和下令，思考仍消耗墙钟预算 |

默认预算如下；评测方指定其它值时，以指定值为准：

| 限制 | 默认值 | 起算与适用范围 |
|---|---:|---|
| 墙钟预算 | 1200 秒 | 从第一条 CLI 请求开始，含 `state`、`ping`、`help` 和错误请求；briefing 与暂停均计时 |
| 游戏时间上限 | 600 游戏秒（10 分钟） | 仅模拟推进时消耗；按回执 `t` 所用的游戏时钟计算 |
| realtime briefing | 180 墙钟秒 | 从第一条请求开始；到期自动开始运行；`briefingOrders=deny` 时此阶段不能下 B 类命令 |
| 无命令接入上限 | 600 墙钟秒 | 从布景就绪开始；一直没有任何 CLI 请求则结束为 `error/no-agent`，两赛道均适用 |

`pause` 不受 realtime 的 briefing 时限和禁止下令选项限制，但仍受总墙钟预算限制。消灭判定或时限可能提前截断一个回合，因此终局回合可能不足 60 tick。

时间有三种口径：

- `tick`：离散模拟步；1× 的名义速度为每秒 60 tick。
- 回执 `t`：游戏时钟秒，每 tick 增加 0.016。因此默认一回合 60 tick 对应 `t=0.960`；两回合对应 `t=1.920`。`turnSeconds` 是按 60 tick/秒换算的名义回合长度。
- 墙钟秒：现实经过的时间，用于思考和总预算。pause 回合快速模拟，不要求耗费等长墙钟时间。

B 类回执 `ok: submitted` 表示已受理，尚未保证执行或到达目标。briefing 下令在开局后的第一个模拟步执行；pause 暂停期下令在下一回合第一个模拟步执行；realtime 运行中下令在后续模拟步执行。`units` 的 `pending` 可查看已受理但未生效的命令。详见命令手册 §2。

`state` 的 `phase=ended` 表示本局结束，`outcome` 给出结局；`endReason` 为 `elimination`、`wall-limit`、`game-limit` 或 `no-agent`。结束后进程随即退出：headless 打印 `RWX_MICRO_RESULT ...` 后结束，开窗口或人类模式播放原版结算画面并倒计时 15 秒退出，因此不要依赖结束后的继续查询。

## 3. 单位公开属性

1 格 = 20 世界单位。以下时间属性以 tick 表示，不包含转向、移动、寻敌和弹道飞行时间。

| 属性 | 机枪机甲 `mechMinigun` | 猛犸坦克 `c_mammothTank` |
|---|---|---|
| HP | 600 | 2600 |
| 护盾 | 800；每 tick 回复 0.08，无受击延迟 | 无 |
| 最大移速 | 0.6 世界单位/tick | 0.6 世界单位/tick |
| 移动加速 / 减速 | 0.07 / 0.17 | 0.04 / 0.06 |
| 最大车体转速 | 5 度/tick | 1.4 度/tick |
| 碰撞半径 | 23 | 22 |
| 质量 | 6000 | 14000 |
| 移动类型 | `OVER_CLIFF`，可通行允许翻越的山地/悬崖 | `LAND`，不能翻越山地/悬崖 |
| 攻击目标 | 地面单位 | 地面单位 |
| 武器标称单发伤害 | 55 | 130；护盾结算见 §4 |
| 射程 | 210，中心距 | 190，中心距 |
| 装填 | 初始 34 tick，随预热线性缩短至 15 tick | 50 tick |
| 预热 | 上限 200 tick；开火不重置；无有效射击状态时每 tick 衰减 1 | 每发需 30 tick 充能；开火重置；不能继续充能时每 tick 衰减 4 |
| 命中方式 | 追踪弹道，速度 11 世界单位/tick，寿命 160 tick | 即时命中 |

预热的触发条件、弹道生命周期与攻击姿态见命令手册 §6。

## 4. 基础游戏规则

### 4.1 HP 与护盾

HP 降至 0 时单位死亡，即使仍有护盾。护盾回复不回复 HP。

双方弹药的护盾结算不同。设命中前护盾为 `S`，HP 实际减少量最多为剩余 HP：

| 弹药 | 护盾减少 | HP 伤害 |
|---|---|---|
| 机甲标称 55 伤害弹药 | `min(S,55)` | `55−min(S,55)` |
| 猛犸标称 130 伤害弹药 | `min(S,130)` | `130−0.1×min(S,130)` |

例如护盾充足时，猛犸单发同时扣除 **130 护盾和 117 HP**；无护盾时扣除 130 HP。这是引擎对两个血量池分别扣减的机制，不能将其理解为把总量 130 守恒分配到护盾和 HP，也不能称作“只有 10% 伤害进入 HP”。这里的 0.1 表示护盾扣减量中用于抵消 HP 伤害的比例。

### 4.2 射程与开火

射程按单位中心距离计算，不加碰撞半径。开火还需要有效目标、炮塔对准、预热和装填等条件；射程内不等于已经开火。观察字段 `hit`/`threatened` 只说明距离关系，不保证下一 tick 开火或命中，精确位于射程边界也不保证开火。

默认姿态为 `onlyInRange`：单位可以自动攻击射程内目标。`move` 期间仍可开火，移动订单不会因交战改成停步或追击；`attack-move` 会途中接敌并可能停步；`attack --target` 会主动接近和追击指定目标。姿态可改变自动索敌和追击行为，完整区别见命令手册 §3。

双方武器没有随机散布、伤害浮动或溅射。发射后的弹道与是否能继续开火是两件事，具体生命周期见命令手册 §6。

### 4.3 地形与碰撞

战争迷雾关闭，双方存活单位的位置和血量可通过 A 类查询观察。`map` 查询地形，`path` 按指定单位的移动类型查询路径。类型 1 的随机地图宽、高各为 80–120 格，双方出生区域对 `LAND` 连通；这不表示任意目标点均可达。

双方单位都有实体碰撞：同方单位会互相挤压和影响通行，敌我单位之间也会碰撞，不能当作可自由穿透的点。碰撞采用软挤压，短暂重叠、被推开或绕行都可能发生。**质量参与挤压分配，影响谁更容易被推开；不表示没有碰撞。** 碰撞半径影响占位，不增加武器射程。地形通行与单位挤压是不同约束，路径查询不保证沿途没有动态单位阻挡。

### 4.4 敌方行为

敌方只操控现有猛犸坦克，会接近并攻击我方单位，交战中也会尝试拉开与当前目标的距离。敌方同样受地形、碰撞和武器规则约束。具体行动以局内观察为准。

## 5. 操作流程与查阅

1. 用 `rwx-micro-1-state` 确认赛道、阶段、回合长度和剩余预算。
2. 用 `rwx-micro-1-units`、`rwx-micro-1-map` 和 `rwx-micro-1-path` 观察局面。
3. 用 B 类命令控制我方单位；用 `rwx-micro-1-begin` 结束 briefing。
4. pause 下用 `rwx-micro-1-next` 推进，realtime 下持续运行；根据实际状态继续观察、操作，直至 `phase=ended`。

命令都以 `rwx-micro-1-` 开头，另有 `rwx-ping`。我方别名 `M1..Ma`，敌方 `E1..Eb`；别名仅在本局有效。`--units all` 在操作命令中只选我方全部存活单位，在观察命令中表示双方。

完整语法、自动索敌、排队与命令结束条件、不可达行为、错误处理和字段含义均在 [commands.md](commands.md)。可使用 `rwx-micro-1-help --list` 查目录，再用 `rwx-micro-1-help 2` 或 `rwx-micro-1-help 3.8` 读取章节。

## 6. 参与边界

- 局内只能通过本文件和命令手册列出的接口观察、操作游戏。除执行上述启动和连接步骤外，不得读取源码、配置、评测输出目录、其它 run 或额外资料。
- 启动配置由评测方指定；不得修改单位、地图、敌方行为、时限或赛道。
- 每次正式评测只有一局；不得重启、重开、投降、暂停 realtime、变速或绕过命令白名单。
- 结果由引擎生成，不得伪造或修改。输出目录即使在文件系统中可见，也不属于参与者的观察接口。
