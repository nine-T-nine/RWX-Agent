# CASE1-duel 命令手册

本文件定义 CASE1 的合法操作、返回字段与相关游戏规则，也是 `rwx-micro-1-help` 返回的全文。任务、胜负、预算和单位属性见 [START.md](START.md)。命令命名空间为 `rwx-micro-1-`。

文中的 `>` 表示向已连接的 CLI 客户端输入一条命令；其后的输出是格式示例，坐标、数量、时间和 id 随本局状态变化。连接方法见 START §0。

## 1. 总览

| 类别 | 用途 | 命令 |
|---|---|---|
| A | 观察（不是游戏命令，不进 Replay） | `rwx-micro-1-state`、`rwx-micro-1-units`、`rwx-micro-1-unit`、`rwx-micro-1-map`、`rwx-micro-1-path` |
| B | 操作（进入 Replay，按 tick 执行） | `rwx-micro-1-move`、`rwx-micro-1-attack-move`、`rwx-micro-1-attack`、`rwx-micro-1-stop`、`rwx-micro-1-mode`、`rwx-micro-1-batch` |
| C | 生命周期 | `rwx-micro-1-begin`、`rwx-micro-1-next` |
| — | 通用 | `rwx-ping`、`rwx-micro-1-help` |

- **realtime 赛道**：布景完成后游戏在 **tick 0 暂停**（briefing），你可以观察和下令；`rwx-micro-1-begin` 后 1× 连续运行，此后不能暂停/变速，也没有 `next`。从第一条命令起 briefing 默认最多 180 秒，超时自动开局（一条命令都没发则 600 秒后 `no-agent`）。
- **pause 赛道**：固定回合制。每回合默认 **60 tick**（`t=0.960`，即 1 个名义游戏秒）；`rwx-micro-1-begin` 跑第 1 回合，`rwx-micro-1-next` 跑后续回合；回合结束自动暂停。暂停期间可以观察和下令，B 类命令在**下一回合第 1 个 tick** 生效。Agent 没有 pause/step/speed 命令。
- 除 `rwx-micro-1-*` 和 `rwx-ping` 外的命令服务端都会返回 `error: unknown command`。查看本手册只能用 `rwx-micro-1-help`。
- 赛道由启动参数 `track` 指定，局内不能切换。A/B/C 是命令类别，不是评分项；辅助统计不改变胜负，没有加权总分。

## 2. 通用约定

- **坐标**：世界坐标，1 格 = 20 世界单位；`x` 向右、`y` 向下。所有 A/B 命令都用世界坐标。
- **时间**：`t=` 是游戏时钟秒，保留 3 位小数；每 tick 增加 0.016，60 tick 对应 `t=0.960`。1× 名义速度为每秒 60 tick。pause 的 `turn=0` 为尚未推进，`begin` 后为第 1 回合；`turnTicks` 是回合步数，`turnSeconds=turnTicks/60` 是名义回合长度。
- **墙钟起算**：从第一条 CLI 输入起算（`state`/`ping`/`help`/拼错命令都算），不是从 `begin` 起算；briefing 和暂停思考都计入 1200 秒预算。`briefingSeconds` 只在 realtime 生效；`startTimeoutSeconds` 两条赛道都生效（一直不发命令 → `outcome=error, endReason=no-agent`）。
- **状态词**：文本回执以 `ok|partial|error` 开头。未知命令、赛道不允许、参数错误和禁止下令都会返回错误；选择中部分已死单位被跳过为 `partial`。`ok: submitted` 仅表示受理，不保证已经执行、路径可达、订单入队或攻击命中。
- **单位选择**：`--units` 接受别名（`M1`、`E2`）、引擎 id（`1000`）、逗号分隔混合列表，或 `all`。B 类只能控制我方单位，选择中混入敌方会报错；敌方别名可用于只读查询或攻击的 `--target`。`all` 在 A 类里表示“双方全部”，在 B 类里只表示“我方全部存活单位”。
- **别名**：我方 `M1..Ma`、敌方 `E1..Eb`，按出生位置稳定排序；`--id/--unit/--target` 同样接受别名或引擎 id。
- **`--hp N`**（B 类）：`1≤N≤100`，只选当前 HP **低于 N%** 的单位（严格小于，不含护盾）；没有符合条件的单位会报错。
- **`--queue`**（`move/attack-move/attack`）：把命令排到当前命令之后；不带时替换当前命令。`stop` 不支持 `--queue`，`mode` 会静默忽略 `--queue`（本命令没有排队语义）。
- **已死单位**：选择里混有已死单位时跳过并在回执标 `partial: skipped dead=...`；全部已死则 `error`。
- **回执**：每个请求返回一份响应，正文可以多行。通常带 `t=<秒>`；不要将响应中的示例值或省略号作为新命令发送。
- **`--json`**：A 类命令支持，返回一个 JSON 对象。`map/path` 的结果放在 `text` 字符串中，其余 A 类返回结构化字段。

### 2.1 受理、生效与订单队列

`pending` 与 `--queue` 是两件事：前者是尚未生效的请求，后者是单位已生效的执行顺序。

- briefing 的 B 请求在 `begin` 后第一个模拟步生效；pause 暂停期的 B 请求在下一回合首步生效。例如 tick 60 暂停时下令，在从 tick 60 推进的首步执行，此前 `order` 不变，`pending` 可见。realtime 运行中的 B 请求在后续模拟步生效，受真实传输延迟影响。
- `move/attack-move/attack` 不带 `--queue` 时，生效后清空旧订单并执行新订单；带 `--queue` 时追加到队尾。单位没有当前订单时，追加项成为第一条。
- 每单位最多 **29 条有效订单，包含当前执行的一条**。满队列后额外追加不会进入有效队列，提交回执仍可能为 `ok`。同一 tick 受理的请求按顺序处理：后来的非排队订单会覆盖前面的订单。
- 原生移动排队会合并队尾附近的重复目标，因此重复发送相近的 `move/attack-move --queue` 不保证新增一条独立订单。
- 当前订单结束后，下一条自动成为当前订单；可能在后续模拟步才显示新状态。队列清空后 `order=idle`，但姿态仍可能允许自动开火和追击。
- `stop` 生效时清空当前及后续订单，清除当前攻击目标；不改变攻击姿态，之后仍可能自动索敌。`mode` 只改姿态，不清空、不追加订单。
- `pending=[]` 只表示没有待生效请求，**不表示订单队列已空**。`order` 只显示当前订单，接口不枚举全部后续订单。

### 2.2 命令何时结束

| 当前订单 | 正常结束条件 | 有后续订单时 |
|---|---|---|
| `move` | 到达目标附近的完成范围，或走完引擎给出的最终路径；不要求坐标精确相等 | 自动切换下一条 |
| `attackMove` | 到达目标附近且没有仍需交战的有效目标 | 交战可能延迟切换；完成后自动切换 |
| `attack` | 指定目标死亡、丢失或不再合法；目标仍存活时不会因为射程外而自动完成 | 有后续订单则切换；没有后续订单时会尝试在索敌范围内重新选敌，找到则继续攻击 |

不可达不是统一的“失败即跳过”，具体规则见 §2.3。`stop` 没有等待前一订单结束的排队形式。

### 2.3 不可达、受阻与寻路查询

- `move/attack-move/attack` 受理时不保证存在完整路径。目标点落在阻塞地形或不连通区域时，寻路可能给出接近目标的可达替代终点；找不到完整路线时也可能只返回部分路径或暂时没有移动。
- 单位会按实际状态重新寻路；命令受理不会因暂时受阻自动变成错误回执。动态碰撞会使单位停顿或偏离路径，地形不会因下令而变得可通行。
- 普通 `move` 可能在走完替代路径后结束并推进队列；`attack-move` 仍以原目标位置和交战状态判断结束，可能停在可达位置后继续寻路，阻住后续队列。对不可达单位的 `attack` 也可能继续保留并尝试接近，不保证自动跳过。
- `path` 是只读的当前路径查询，不提交移动订单，不为后续执行预留路线。`reachable`、`straightReachable` 分别说明能否到达和直线是否可通行；`reachable=false` 不等于操作请求必定被拒绝。
- 地形、移动类型与动态单位碰撞分别影响实际移动；不要把 `ok: submitted` 或路径查询当作到达通知。

## 3. 命令

### 3.1 `rwx-micro-1-state [--json]`

观察阶段、时间、回合和双方总量。

```text
> rwx-micro-1-state
ok: state t=0.000 tick=0 phase=briefing track=pause turn=0 turnTicks=60 remainingWall=1199.936 outcome=active reason=-
self={"alive":2,"hp":1200.0,"shield":1600.0} enemy={"alive":2,"hp":5200.0,"shield":0.0}
```

```json
{"status":"ok","t":0.0,"tick":0,"phase":"briefing","track":"pause","turn":0,"turnTicks":60,"turnSeconds":1.0,"remainingWallSeconds":1199.929,"outcome":"active","endReason":"","self":{"alive":2,"hp":1200.0,"shield":1600.0},"enemy":{"alive":2,"hp":5200.0,"shield":0.0}}
```

- `phase`：`briefing | running | ended`；`outcome`：`active | win | loss | draw | timeout | error`。
- briefing 期间游戏停在 tick 0，不会消耗游戏时间（realtime 与 pause 都一样）。
- **结束后进程会退出**：headless 直接打印判定结果（`RWX_MICRO_RESULT ...`）并结束进程；开着窗口（`--window`）或人类模式则播放原版胜利/失败结算画面，`15` 秒倒计时后退出。所以 `phase=ended` 后只有很短的窗口还能读 A 类查询。
- `remainingWallSeconds`：从收到第一条 Agent 命令起算的剩余墙钟预算；human 模式不受墙钟限制。
- `self`/`enemy` 只统计存活单位；`turnSeconds` 只在 pause 赛道有意义。

### 3.2 `rwx-micro-1-units [--owner self|enemy|any] [--units <sel>] [--compact] [--delta] [--json]`

一次输出全部（或选定）单位，**不做 HP<80% 的默认过滤**。默认 `--owner any`。

```text
> rwx-micro-1-units
ok: units t=0.000 count=4
M1 id=1000 type=mechMinigun x=370.000 y=730.000 hp=600.000/600.000 shield=800.000/800.000 order=idle target=- cd=0.000 warm=0.000 nearest=1149.783 hit=- threatened=- pending=[]
M2 id=1001 type=mechMinigun x=410.000 y=1430.000 hp=600.000/600.000 shield=800.000/800.000 order=idle target=- cd=0.000 warm=0.000 nearest=1444.991 hit=- threatened=- pending=[]
E1 id=1002 type=c_mammothTank x=1490.000 y=470.000 hp=2600.000/2600.000 shield=0.000/0.000 order=idle target=- cd=0.000 warm=0.000 nearest=1149.783 hit=- threatened=- pending=[]
E2 id=1003 type=c_mammothTank x=1890.000 y=1070.000 hp=2600.000/2600.000 shield=0.000/0.000 order=idle target=- cd=0.000 warm=0.000 nearest=1523.155 hit=- threatened=- pending=[]
```

- `--owner self|enemy` 按本次对局的 team0/team1 过滤。
- `--delta` 首次返回所选单位，之后只输出与上一次 `--delta` 查询相比有变化的单位；普通 `units` 查询不更新这个基准。`--compact` 当前与默认输出一致。
- `--json` 的字段见 §4；`nearestEnemyDistance`、`canHit`、`threatenedBy` 是按当前坐标和武器射程实时算的。
- 暂停期间已受理但未生效的命令出现在 `pending`，`order` 仍是已生效的旧命令。

### 3.3 `rwx-micro-1-unit --id <别名|id> [--json]`

单个单位的完整只读视图；字段与 `rwx-micro-1-units --json` 的单行相同。

```text
> rwx-micro-1-unit --id M1
ok: units t=0.000 count=1
M1 id=1000 type=mechMinigun x=370.000 y=730.000 hp=600.000/600.000 shield=800.000/800.000 order=idle target=- cd=0.000 warm=0.000 nearest=1149.783 hit=- threatened=- pending=[]
```

### 3.4 `rwx-micro-1-map [--x X --y Y --half-width N --half-height N] [--json]`

只读地形+陆军位置图。不带坐标时给默认窗口（地图中心 17×17）；带 `--x/--y` 时以该点为中心，`--half-width/--half-height` 是半宽/半高的格数（1–40）。每格固定 5 字符：前 4 个字符是地形符号，前导的 `+`/`-` 表示阵营（`+` 我方/友军，`-` 敌方/中立），因此**图上直接能看到单位在哪一格**；窗口内实体的 id 和 tile 坐标列在 `own:` 索引里。本 case 关闭迷雾。

```text
> rwx-micro-1-map --x 485 --y 780 --half-width 2 --half-height 2
ok: observation t=0.000
map(WxH/2020.0x1660.0,world unit):
1 tile=20 world units
  range: x=440..540 y=740..840(world unit) x=22..26 y=37..41(tiles)
  scale: 100x100(world unit) 5x5(tile)
  window: origin=tile 22,37(top-left) x=left-to-right y=top-to-bottom | 5x5 cells | cell=5 chars | mode=unit-land | fog=off
..... ..... ..... ..... .....
..... ..... ..... ..... .....
..... ..... +mmgn ..... +mmgn
..... ..... ..... ..... .....
..... ..... +mmgn ..... .....
  terrain: .....=land
  faction: +xxxx=own +XXXX=ally -XXXX=enemy -xxxx=neutral
  units: MMGN/mmgn = mech_minigun
  own:
    mech_minigun: #1000(id=1000)@tile(24,39), #1001(id=1001)@tile(26,39), #1002(id=1002)@tile(24,41)
cliff (^/-7): OVER_CLIFF can cross cliff tiles; LAND cannot. Explicit blocked tiles remain impassable.
```

图例（`terrain:` 只解释本次出现的符号）：

| 含义 | ASCII（每格重复 5 次，如 `.....`） |
|---|---|
| 陆地 | `.` |
| 水 | `~` |
| 山地/悬崖（机甲可翻越，猛犸不可） | `^` |
| 熔岩 | `*` |
| 空闲资源点 | `0` |
| 已占用资源点 | `8` |
| 战争迷雾 | `?` |

只在 `own:` 索引里出现的单位一定在窗口内；`-xxxx` 格只能看到阵营和类型编码，看不到具体 id。

山地/悬崖与显式阻塞格不同，地图字符不能完整表达每种单位的通行约束。`OVER_CLIFF` 不是无视所有地形，水和显式阻塞仍会限制它；具体点位用 `path` 查询。

### 3.5 `rwx-micro-1-path --unit <别名|id> --x X --y Y [--json]`

按该单位真实移动类型（机甲 `OVER_CLIFF`、猛犸 `LAND`）查询可达性和路径，不下达订单。可查询敌方路径，但不能控制敌方。不可达与实际执行的关系见 §2.3。

```text
> rwx-micro-1-path --unit M1 --x 370 --y 730
ok: observation t=0.000
可达性
reachable: true
movement: OVER_CLIFF
startTile: 18,36
goalTile: 18,36 | goal: 370,730 (world)
straightReachable: true
straightBlockedTiles: 0
reason: already at the target tile
straightDistance: 0 world
pathDistance: 0 world
detourRatio: 0.00x the Manhattan minimum (-100% extra travel)
pathTiles: 0 tiles
waypoints (2):
  · 370,730
  · 370,730
```

### 3.6 `rwx-micro-1-move --units <sel> --x X --y Y [--queue] [--hp N]`

前往指定坐标。移动订单不会为交战停步或追击；默认 `onlyInRange` 姿态下仍可自动向射程内敌人开火。到达目标附近或最终路径结束后完成，并推进后续订单。射击取决于姿态和武器状态，见 §3.10、§6。

```text
> rwx-micro-1-move --units M1 --x 370 --y 730
ok: submitted t=0.000
```

### 3.7 `rwx-micro-1-attack-move --units <sel> --x X --y Y [--queue] [--hp N]`

前往指定坐标，途中自动索敌、接近并交战；进入有效攻击位置后可能停步。当前敌人失效后继续索敌或向原目标点推进。到达目标附近且没有有效交战目标时完成；有目标时可能继续交战而不推进队列。禁止自动攻击的姿态同样会限制此命令。

```text
> rwx-micro-1-attack-move --units all --x 1490 --y 470
ok: submitted t=0.000
```

### 3.8 `rwx-micro-1-attack --units <sel> --target <别名|id> [--queue] [--hp N]`

攻击指定敌方单位。**目标在射程外时会主动寻路接近，目标移动时会追击**；不是原地等待目标进入射程，也不是只设置一个射击标记。进入可射击位置后尝试开火，目标离开后可继续追击。

- 姿态仍然有效：`holdFire/returnFire` 不会因显式 `attack` 自动变成允许开火；攻击订单可能仍在接近目标。自动索敌与姿态见 §3.10、§6.1。
- 下令时目标必须是可查询、存活且合法的敌方单位；不能用 `attack` 控制敌军。
- 目标死亡或失效时，有后续订单就切换下一条；没有后续订单则尝试自动选取索敌范围内的新目标，找不到才结束。不可达目标不会保证超时或自动跳过，见 §2.3。

```text
> rwx-micro-1-attack --units M1 --target E1
ok: submitted t=0.000
```

### 3.9 `rwx-micro-1-stop --units <sel> [--hp N]`

生效后停止当前订单、清空后续订单并清除当前攻击目标。攻击姿态不变，之后仍可能自动索敌、开火或按姿态追击，因此 `stop` 不等于禁止开火。**不接受 `--queue`**；带上会返回 `error: Unknown option: --queue`。

```text
> rwx-micro-1-stop --units M1
ok: submitted t=0.000
```

### 3.10 `rwx-micro-1-mode --units <sel> --mode <holdFire|returnFire|onlyInRange|outOfRange|guardArea|aggressive> [--hp N]`

切换攻击姿态，在后续模拟步生效（pause 下为下一回合首步）。`units --json` 的 `mode` 字段生效后才变；文本输出不显示 `mode`，暂停期可看 `pending`。姿态不清空或追加移动/攻击订单，`--queue` 在本命令中被忽略。

表中 `R` 为该单位武器射程：

| mode | 自动索敌 | 行为 |
|---|---|---|
| `holdFire` | 否 | 不开火；显式 `attack` 不覆盖此姿态 |
| `returnFire` | 否 | 本版本不自动还击，开火限制同 `holdFire` |
| `onlyInRange` | `R` | 默认姿态；自动攻击只限射程内，不因自动索敌追击 |
| `outOfRange` | `R+250` | 可以自动追击射程外的目标 |
| `guardArea` | `R+150` | 可以自动追击；本接口没有额外守卫点，不保证自动回位 |
| `aggressive` | `R+180` | 可以自动追击射程外的目标 |

执行 `attack-move` 时，以上半径再增加 20，且至少为 190；半径扩大不增加实际武器射程。普通 `move` 不会因为这些姿态改变为接敌停车订单；显式 `attack` 的追击也不受 `onlyInRange` 的自动追击限制。

```text
> rwx-micro-1-mode --units all --mode holdFire
ok: submitted t=0.000
```

### 3.11 `rwx-micro-1-batch "<子命令>; <子命令>; ..."`

分号分隔，子命令省略 `rwx-micro-1-` 前缀。规则：

- 整个 batch 是**一个参数**；最多 128 条子命令；
- 只接受 B 类子命令（`move/attack-move/attack/stop/mode`），**不能含 `begin`**，也不能嵌套 `batch`；
- `next` 只能作为最后一条，且仅 pause 赛道；`next` 不在 batch 里时，pause 下需要单独发；
- 子命令同一 tick 受理（pause 下都进同一批 pending，下一回合首个 tick 一起生效），逐条回执；
- 子命令的值里不要出现 `;`（先按分号切分）；子命令内部需要引号的值用外层引号包住整个 batch，例如 `rwx-micro-1-batch "move --units all --x 100 --y 200; next"`。

```text
> rwx-micro-1-batch "attack-move --units all --x 1490 --y 470; next"
ok: batch t=1.920
rwx-micro-1-attack-move ok: submitted t=0.960
rwx-micro-1-next ok: turn completed t=1.920 tick=120 turn=2
ok: units t=1.920 count=4
M1 id=1000 type=mechMinigun x=400.603 y=722.964 hp=600.000/600.000 shield=800.000/800.000 order=attackMove target=- cd=0.000 warm=0.000 nearest=1035.550 hit=- threatened=- pending=[]
...
```

子命令失败时整体为 `partial: batch`，后续子命令继续执行；示例（briefing 阶段就带 `next`）：

```text
> rwx-micro-1-batch "move --units M1 --x 370 --y 730; next"
partial: batch t=0.000
rwx-micro-1-move ok: submitted t=0.000
rwx-micro-1-next error: use begin first t=0.000
```

### 3.12 `rwx-micro-1-begin`

结束 briefing，开始运行：realtime 从 tick 0 起连续 1×；pause 运行第 1 回合（默认 60 tick）。重复调用返回 `error: already begun`。

```text
# pause：跑完第 1 回合
> rwx-micro-1-begin
ok: begun t=0.960 tick=60 turn=1

# realtime：回执记录开局边界，此后连续推进
> rwx-micro-1-begin
ok: begun t=0.000 tick=0 turn=0
```

### 3.13 `rwx-micro-1-next [--quiet]`

仅 pause 赛道：运行 1 个固定回合（默认 60 tick）后自动暂停；胜负或时限可提前结束回合。默认回执后附 units，`--quiet` 只回执。realtime 返回 `error: not available in realtime track`；briefing 阶段返回 `error: use begin first`。

```text
> rwx-micro-1-next --quiet
ok: turn completed t=1.920 tick=120 turn=2
```

### 3.14 `rwx-micro-1-help [--list [depth]] [<编号> ...]`

返回本手册：

- 不带参数：全文；
- `rwx-micro-1-help --list`：列出所有带编号的章节标题；`--list 2` 把标题展开到第 2 层；
- `rwx-micro-1-help 3` / `rwx-micro-1-help 3.4`：只返回该编号的章节；可给多个编号，按文档顺序拼接；未知编号返回错误并提示用 `--list`。
- 本手册标题是中文，`--list` 之后给中文短名不可用；按编号选择即可。

```text
> rwx-micro-1-help --list
ok: help t=0.000
1. 总览
2. 通用约定
3. 命令
4. 单位输出字段
5. 与完整对局命令的差异
6. 攻击与武器规则
```

```text
> rwx-micro-1-help 2
ok: help t=0.000
## 2. 通用约定
...
```

```text
> rwx-micro-1-help --nope
ok: help t=0.000
error: unknown help section '--nope' (use: rwx-micro-1-help --list)
```

`rwx-help` 在本模式不可用（`error: unknown command`），请用本 case 的 `rwx-micro-1-help`。

### 3.15 `rwx-ping`

```text
> rwx-ping
ok: pong t=0.000
```

## 4. 单位输出字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `alias` | string | `M1..` / `E1..` |
| `id` | int | 引擎单位 id |
| `team` | int | 我方 `0`，敌方 `1`（与环湖 TMX 的 team0/team1 一致） |
| `type` | string | `mechMinigun` / `c_mammothTank` |
| `x`,`y` | float | 世界坐标 |
| `hp`,`maxHp` | float | 当前/最大 HP，不含护盾；`--hp N` 按此值过滤 |
| `shield`,`maxShield` | float | 当前/最大护盾；机甲 800，`0.08/tick` 持续回复、无受击延迟 |
| `alive` | bool | 存活 |
| `order` | string | `idle / move / attackMove / attack / ...`；暂停期间仍是已生效的旧命令 |
| `orderTarget` | id/null | 当前命令的目标单位 |
| `target` | id/null | 当前攻击目标 |
| `orderX`,`orderY` | float/null | 当前命令目标点 |
| `weapons[]` | array | 可射击武器的 `index`、`cooldownFrames`（剩余装填 tick）、`warmupFrames`（已积累预热 tick）、`target`（id/null） |
| `lastFireMs` | int | 最近开火游戏毫秒，`-1` 表示还没开火 |
| `mode` | string | 当前攻击姿态（下一个 tick 才更新；仅 `--json`，文本输出不显示） |
| `pending[]` | array | 已受理、未生效的请求，含 `order/target/x/y/queue/mode`；不是完整的订单队列 |
| `nearestEnemyDistance` | float/null | 到最近存活敌方的**中心距** |
| `canHit` | string[] | 中心距 `d≤该单位射程` 的对手别名；文本名为 `hit`，只看距离 |
| `threatenedBy` | string[] | 中心距 `d≤对手射程` 的对手别名；文本名为 `threatened`，只看距离 |

`cd`/`warm` 是文本输出中各武器最大值除以 60 后的名义秒数；JSON 保留 tick 数。`warm` 是已经积累的预热，不是距离开火还需等待的时间。`hit`/`threatened` 不检查姿态、炮塔朝向、装填、预热或弹道，不能作为实际开火/命中保证；精确位于射程边界也不保证开火。碰撞半径不加入射程。

常规单位查询不保证保留死亡单位；`--delta` 也不是死亡事件流。判断结局用 `state` 的总量与 `outcome`，不要将一条查询里没有变化误读成没有损失。

## 5. 与完整对局命令的差异

- micro 模式只注册 `rwx-micro-1-*`、`rwx-ping`；`rwx-help`、`rwx-game-*` 一律返回 `error: unknown command`（服务端拒绝，不是文档隐藏）。
- 没有 pause/step/speed/restart/surrender/observe-reset；没有建造、生产、编组、auto/keep/pending。
- 单位选择用 micro 自己的别名/`all`（B 类 `all` 只含我方）；完整对局的 `--owner`/`--group` 语义不直接复用。
- 输出是 micro 专用的紧凑格式，不承诺与 `rwx-game-*` 的文本/JSON 相同。

## 6. 攻击与武器规则

### 6.1 自动索敌

单位在攻击姿态允许时自动寻找存活、可攻击的敌方单位；范围由 §3.10 决定。自动索敌在 `idle` 和移动期间都可发生。有效且已进入射程的当前目标通常会保留，不会仅因另一目标更近就立即切换；需要重新索敌时，在合法候选中选择较近者，本 case 双方均为可攻击地面的战斗单位。同距目标不承诺固定别名优先级，也不按残血自动优先。

`orderTarget` 是当前订单指定的目标，`target` 是当前攻击目标，武器还各有 `target`。三者不必始终相同；显式 `attack` 的移动追击以订单目标为准。自动索敌不会让普通 `move` 自动改成接敌停步。

### 6.2 预热、装填与中断

预热需要存活且可攻击的目标进入有效武器范围、炮塔转到可射击方向，并满足当前姿态；仅显示一个射程外的 `target` 或存在 `attack` 订单不代表正在预热。实际开火还要完成装填和预热。

- **机甲**：满足以上射击条件时，预热每 tick 增加 1，最多 200；装填期间也可继续预热。每次发射使用当前预热值确定装填长度，从初始 34 tick 线性缩短至满预热时 15 tick。开火本身不重置预热。
- **猛犸**：装填结束且满足射击条件后才开始每 tick 增加 1 的充能；积累 30 后开火并清零，再进行 50 tick 装填。保持有效射击条件时，连续发射的基础周期为 80 tick；寻敌、转向和中断可能使间隔更长。
- 不能继续有效预热时，机甲每 tick 衰减 1，猛犸每 tick 衰减 4，最低为 0。目标离开射程、死亡、失去有效攻击目标、炮塔未对准或姿态禁止开火，都可能导致中断。
- 移动、停止或换目标不是独立的“强制清零”操作；影响取决于是否继续满足射击条件。重新满足条件时，从剩余预热值继续积累；猛犸开火后的重置仍适用。
- 预热值归武器所有，不绑定某个目标；换目标期间可能因失去射击条件而衰减。`cooldownFrames` 表示装填剩余值，`warmupFrames` 表示已经积累的预热值。

### 6.3 发射后的弹道

- 猛犸电磁炮即时命中，没有需要等待的飞行过程。
- 机甲弹药跟踪发射时选中的目标，速度 11 世界单位/tick，寿命 160 tick；未命中时到期消失，不产生额外溅射。没有独立的“飞出武器射程即消失”距离限制；最大飞行路程受速度和寿命限制，不能把武器射程当作弹道航程。
- 弹药发射后，目标离开射手射程不会使它立即失效；射手移动、停止或换目标也不会取消已经发射的弹药。
- 弹药没有启用目标死亡后的自动换目标或飞行中重新选敌；它不转而攻击另一个敌人。缺失目标引用时弹药消失。弹药追踪原目标的运动，不应假定为射向发射时坐标的固定直线。

### 6.4 伤害与护盾

HP 降至 0 即死亡，不要求护盾先耗尽。设命中前护盾为 `S`：

- 机甲单发：护盾扣 `min(S,55)`，HP 伤害为 `55−min(S,55)`。
- 猛犸单发：护盾扣 `min(S,130)`，HP 伤害为 `130−0.1×min(S,130)`。

因此护盾充足时，猛犸单发实际同时扣 130 护盾与 117 HP；护盾不足时按剩余护盾计算，无护盾时为 130 HP。两个池的扣减相加可以超过标称 130，这不是总伤害守恒分配。HP 实际扣减不超过剩余 HP；机甲护盾持续每 tick 回复 0.08，观测到的净变化可能包含回复。
