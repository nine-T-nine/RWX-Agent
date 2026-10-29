# RWX CLI

`rwx-help` 显示[英文手册](help.md)；本页为中文命令手册。

- `rwx-help`：显示完整文档。

- `rwx-help <标题编号>...`：显示指定标题及其全部子标题下的内容，包含标题本身。如 `rwx-help 5.5` 只显示 5.5，`rwx-help 5` 显示整个第 5 章。可选择多个编号，按文档顺序输出，重叠内容只显示一次。

- `rwx-help [--session|--observe|--combat|--develop|--automation|--history|--reference|--system]`：保留原来的 8 个大模块筛选，英文名称仅用于这些 h2 标题，可组合选择多个模块。编号筛选与英文模块筛选互斥；传入编号时只按编号筛选，例如 `rwx-help --combat 5.5` 只显示 5.5。

- `rwx-help --list [depth]`：只列出标题及编号，每行一个，按深度优先顺序输出，子标题缩进。`depth` 为正整数，默认 1（只列 h2）；2 包含 h3，3 包含 h4，以此类推，无最大深度限制。可与筛选组合，如 `rwx-help 5 --list 2`；深度始终从 h2 算起。

## 1. Session — 对局管理

管理一局游戏的生命周期和运行环境。

### 1.1 战役室

> 这些命令只能在**对局未开始**的时候使用

- `rwx-skirmish`：进入本地战役室。

- `rwx-battleroom-show [--player] [--currentMap] [--enabledMap] [--settings] [--chatHistory]`：查看房间信息；无参数时显示玩家和当前地图。各开关分别显示玩家列表、当前地图、可选地图、房间设置、聊天记录。

- `rwx-battleroom-add-ai [--difficulty <very-easy|easy|medium|hard|very-hard|impossible|自定义AI|id|编号>]`：在第一个空位添加 AI，默认 Hard（1）。支持原版六档英文名和编号 `-2..3`、自定义 AI 中英文显示名（如 `AI-high`、`AI_S`、`AI-hard-1income`、`AI-非常困难-1倍收入`）。大小写、空格、`_`、`-` 不敏感。

- `rwx-battleroom-show-ai`：列出所有可添加的 AI，含编号、显示名（中/英）和稳定 id；不需要在战役室里也能查。

- `rwx-battleroom-set-player --player/--name <出生点或名称> (--move <出生点> | --switch <A–J|1–10|spec> | --kick | --difficulty <AI>)`：调整玩家位置、阵营或 AI 难度。

- `rwx-battleroom-select-map --name <地图名>`：按显示名、文件名或资源路径选择；找不到时给出相近候选，不自动选中。

- `rwx-battleroom-settings <选项...>`：修改房间设置；至少要给一个选项，读取当前设置用 `rwx-battleroom-show --settings`，仅房主、开局前可改。

- `rwx-battleroom-chat <内容>`：发送聊天消息。

- `rwx-battleroom-start [--record]`：开始对局；`--record` 从 tick 0 录制完整回放。

- `rwx-battleroom-quit`：退出战役室；对局已载入时会拒绝。

房间设置选项：

- `--income <0.1..100>`：收入倍数，如 `2.5`。

- `--credits <default|0|1000|2000|4000|5000|10000|50000|100000|200000>`：初始资金。

- `--fog <none|basic|los|0|1|2>`：雾效。

- `--startUnits <1..5>`：初始单位档位。

- `--aiDifficulty <AI>`：默认 AI 难度，取值同 `--difficulty`。

- `--maxPlayers <10..100>`：最大玩家数。

- `--teamLayout <2|3|ffa|spectators|random|allvsai|allvs2>`：一键分队；`ffa` 会重排 teamId，房主出生点可能变化。

- `--revealedMap` / `--noNukes` / `--sharedControl` / `--allowSpectators` / `--teamLock` / `--roomLock` / `--fixedAllyTeams <true|false>`：布尔开关。

### 1.2 对局生命周期

- `rwx-game-restart`：同一进程内重载当前地图，回到 tick 0；保留当前房间设置（初始资金、初始单位、雾效、收入等）以及玩家出生点、阵营、颜色和 AI 难度覆盖，清除上一局的投降、战败和 AI 战局状态。

- `rwx-game-surrender`：本地对局提交可回放的投降命令，下一模拟 tick 生效；联机对局仍走原版投降投票。CLI 投降同时关闭微操、清空自动补产和待发指令。

- `rwx-game-pause --value <true|false>`：暂停/恢复；恢复时保留暂停前速度。

- `rwx-game-speed --value <1..64>`：设置速度；暂停只能用 `rwx-game-pause`。

- `rwx-game-step [--seconds 5] [--timeout-ms N]`：按游戏时间推进模拟后暂停，`--seconds` 上限 1800。

本地时间控制、`step` 和单机 `restart` 不能用于多人游戏；多人指令必须交给房主调度和原版锁步同步。

### 1.3 多人连接、托管与进程退出

```text
rwx-connect <host[:port]|RELAY-CN|connect-descriptor> [--name NAME] [--password VALUE|--password-env ENV_NAME] [--room CODE] [--timeout-ms 20000]
rwx-connect --reconnect true
rwx-connection-status
rwx-disconnect
rwx-host [--port 5123] [--name NAME] [--map "Lake (2p)"] [--password VALUE|--password-env ENV_NAME] [--public false] [--spectator false] [--auto-start-players 0] [--managed-room false]
rwx-server-list [--refresh true]
rwx-exit
```

- **连接**：`rwx-connect` 异步建立连接，默认 TCP 端口 `5123`；支持原版直连地址、`host:port/房间码`、短中继码和 `get|...` 主服务器描述符。`--room` 为直连中继附加原版查询串，不可与 `get|...` 同用。玩家名长度 2..20。
- **公共中继**：`RELAY-CN` 按公共主服务器列表中的发布者/房间标识查找，不硬编码地址。列表不可达、无匹配或握手失败均会报告；入口名称不保证服务可用。创建/加入指令以入口实际提示为准。
- **连接状态**：连接回执仅表示请求已提交。`rwx-connection-status` 的 `joined` 表示已收到房间配置并分配本地队伍；`in-game` 还要求完成游戏载入。其他状态有 `resolving`、`connecting`、`hosting`、`disconnected`、`error`；同时显示端口、队伍和错误。
- **超时与密码**：连接超时覆盖 TCP 建连和房间注册，范围 1000..120000 ms；超时取消连接并关闭 socket，包括迟到的连接。密码可用 `--password` 或 RWX 进程环境变量 `--password-env` 提供，错误密码不会无限重试。
- **重连**：`--reconnect true` 使用上次地址和同一客户端身份；能否恢复原队伍由房主决定，已开局通常拒绝新玩家。多个 Agent 须使用各自的 `--data-dir`，不可共享持久客户端身份。
- **断开与退出**：`rwx-disconnect` 返回游戏菜单，但保留 RWX 进程和本地 CLI；关闭 Python/PowerShell CLI 客户端也不会退出游戏。`rwx-exit` 关闭进程及其 CLI。
- **托管**：`rwx-host` 监听指定 TCP 和可靠 UDP 游戏端口，默认不公开发布。`--spectator true` 使服务器本地玩家旁观；`--auto-start-players 2` 授权房主等待至少两名非旁观真人客户端后自动开局，`0` 关闭；AI 不计人数。
- **常驻房间**：`--managed-room true` 隐含服务器旁观，与自动人数开局互斥。首位客户端获得原版代理房主权限；退出后按加入顺序接替，仅大厅选举，对局中冻结。代理管理请求通过原版聊天包交由服务器校验执行；CLI 回执仅表示已提交，须查询房间确认结果。
- **托管中的席位与结束**：新身份加入已开局房间时自动观战，原玩家断线重连保留席位。所有在线非旁观玩家退出或战败后，服务器通过原版包强制结束并返回大厅，保留在线客户端、不指定胜方；旁观者和 AI 不阻止结束。代理房主不能控制其他队伍单位。
- **权限**：`connection-status` 含 `roomAdmin`（当前客户端代理权限）、`roomManager`（服务器本地查询的管理员名）、`spectator`；普通客户端只能从原版房间状态及代理权限位判断权限。普通玩家可查看房间、聊天、按原版规则调整自己的出生点/阵营，并在游戏中查看可见信息、控制己方单位；只有房主或原版代理房主能修改房间、地图、AI 或开局。Agent 无额外权限，命令走原版 `Command` 网络队列。

示例（本地 RWX 连接云服务器游戏端口）：

```text
rwx-connect <server>:5123 --name AgentA
rwx-connection-status
rwx-game-state --summary
rwx-game-unit-list --owner self --full --compact
```

## 2. Observe — 信息统计

查询游戏当前状态大致统计。

### 2.1 对局状态

`rwx-game-state [--summary] [--json] [--units-acting] [--units-damaged] [--building-acting] [--building-actionable] [--event-seconds N] [--event-types type1,type2,...] [--event-allies] [--no-events]`

默认依次显示战局、经济、我方/友军/可见敌军兵力、生产、升级、我方血量分档、CLI 待发项、需关注的单位与建筑，以及最近 30 游戏秒的重要事件；空区块为 `none`。`--summary` 保留宏观统计和事件、隐藏实体明细，且与下列四个实体筛选开关互斥：

- `--units-acting`：正在移动、交战、建造、生产、修理、巡逻、守卫或跟随等的非建筑单位。
- `--units-damaged`：HP <70% 的非建筑单位。
- `--building-acting`：正在施工、生产、升级、修理、攻击或防御的建筑。
- `--building-actionable`：资金与条件满足、当前可升级或生产的建筑。

四类默认全选；显式选择时取并集、去重，只影响实体明细，不影响统计。活动以引擎状态为准；详情含 ID、坐标、血量、当前指令、目标、waypoint，建筑另含科技等级和生产队列摘要。

- `--event-seconds N`：事件窗口，默认 30 游戏秒，范围 (0, 180]。
- `--event-types`：逗号分隔，默认 `damaged,destroyed,losses,new`，`info` 须显式指定。
- `--event-allies`：`destroyed`、`losses` 只显示友军，其余事件规则不变（见 6.1）。
- `--no-events`：不查询或显示事件，不清除历史；不可与其他事件参数同用。
- `--json`：输出与文本共享报告数据的标准 JSON。

JSON 字段：

- 顶层：`battle`、`economy`、`forces`、`production`、`upgrades`、`healthStatus`、`pending`、`notableUnits`、`notableBuildings`、`events`。`--summary` 时两个实体数组为空；`--no-events` 时省略 `events`。
- `battle`：`tick`、`time`、`gameTimeMillis`、`paused`、`speed`、`map`、`controlledTeam`、`outcome`；`economy`：`credits`、`incomePerSecond`。
- `forces.self` / `allies` / `visibleEnemies`：`unitCount`（不含建筑）、`buildingCount`、`typeCounts`（含 `type`、`count`）。我方与友军另含 `idleUnitCount`；友军不含我方，敌军仅含当前可见实体，生产队列不计入兵力。
- `production`：`factoryCount` 为我方已完工、可生产单位的建筑数；`busyFactoryCount` / `idleFactoryCount` 按队列是否为空统计（升级算忙碌）；`productionQueueEntryCount` 只计生产，不计升级。
- `upgrades`：`id`、`type`、`upgrade`、`progress`（0..1，未知为 null）。
- `healthStatus.units` / `buildings`：按类型统计 `criticalCount`（HP <10%）、`lowHealthCount`（10% ≤ HP <50%）、`injuredCount`（50% ≤ HP ≤80%）。
- `pending`：`pendingOrders`、`waitingForCreditsCount`、`waitingForUpgradeCount`；不支持待发查询时为 null。
- `events`：`windowSeconds`、`items`（每项含 `gameTimeMillis`、`type`、`description`）。使用游戏时间，查询不消费历史。

```text
rwx-game-state --summary --json
rwx-game-state --units-damaged --building-acting --event-seconds 60
```

- `rwx-game-team-list`：列出所有队伍、阵营、AI、active/defeated、本队资金与净收入；不暴露其他队伍经济。

### 2.2 单位列表与详情

- `rwx-game-unit-list [--owner self|ally|enemy|neutral|any] [--type <id或名称,...>] [--group <name>] [--units <ids|选择器>] [--x X --y Y --radius R] [--hp <百分比>|--full] [--idle] [--exclude-builders] [--exclude <id|名称,...>] [--summary] [--delta] [--compact] [--json]`：列出单位与建筑的 ID、世界坐标、血量、指令和队列。默认仅列 HP <80% 的匹配实体，敌方实体仅在当前可见时返回。
- `rwx-game-unit-show --id <id>`：单个实体详情，包括阵营、T 级、升级等级、攻击模式、生产队列、完整 waypoint 队列和暂存指令 `cliPending`。
- `rwx-game-unit-actions --unit <id> [--json]`：查看动作 id、价格、可执行性、排队数和不可用原因。`queued` 含正在生产的动作；`available` 表示当前能买得起至少一个。

`unit-list` 参数：

- `--owner self|ally|enemy|neutral|any`：所属关系；默认仅我方与当前可见敌方，排除盟友、中立。`ally` 仅友军；`any` 为所有可观察阵营。默认变化记录采用相同的阵营范围。
- `--type <id或名称,...>`：类型筛选；内部 id 精确匹配，否则按本地化名称匹配，可用部分名称。这是唯一的类型筛选入口。
- `--units <ids|选择器>` / `--group <name>`：按 ID、编组或区域筛选；选择器见 3.4。
- `--x X --y Y --radius R`：世界坐标圆形范围；中心坐标须成对提供，半径非负。
- `--hp N`：只保留 HP 低于 N% 的实体；`--full`：取消默认血量筛选，列出全部匹配实体。
- `--idle`：只保留没有当前 waypoint 且生产队列为空的实体；`--exclude-builders` / `--exclude`：排除建造者或指定单位、类型。
- `--summary`：只统计匹配项，不支持与 `--hp`、`--full`、`--compact`、`--delta`、`--json` 同用。
- `--compact`：仅改变文本格式；`--delta`：相对上次 `--delta` 查询报告新增、离开、血量下降、科技等级提高，并更新基线。普通查询不显示变化区。
- `--json`：只读，不更新 `--delta` 基线；与 `--delta` 互斥。

### 2.3 地图与地形信息

- `rwx-game-map-show [--spawns] [--terrain ...]`：地图大小、格子尺寸，默认列出全部资源池的建筑中心和真实占用状态，不受迷雾限制。在建建筑也算占用，已摧毁建筑不算；占用项额外列出阵营、类型和建筑 ID。`--spawns` 额外列出各阵营已知的指挥中心或首次出现位置，未侦察到的敌方基地仍为未知/近似位置。

- `rwx-game-map-path --unit <id> --x X --y Y`：查询该单位到目标点的静态地形可达性、直线阻挡、路径长度和绕路比。

`rwx-game-map-show --terrain` 的窗口大小参数：

- `--x/--y` 是世界坐标中心，省略则取地图中心。

- `--half-width W --half-height H`：必须一起给出，分别为左右、上下的格数（1..40）；尺寸为 `(2W+1) × (2H+1)` 格，默认 `W=H=8`，即 17×17 格。仅支持矩形；越出地图时裁剪并提示。

- 默认输出分类数字及图例。建筑实际占用的各格覆盖为：我方 `#建筑ID`、所有友军统一 `4`、所有敌方统一 `-4`，不区分建筑类型；在建建筑也覆盖，已摧毁建筑排除，敌方建筑仅当前可见时显示。每格按当前窗口最长标记等宽对齐，包括负数和 `#ID`。

- `--ascii`：切换为原地形/资源字符图并显示字符图例，关闭建筑 ID 覆盖；每格间一个空格，需要 `--terrain`。

- `--fog`：地形图中当前不可见的格子统一输出 `-2`（字符图 `?`），包括未探索和已探索但脱离视野的格子；关闭游戏迷雾时不遮罩。此遮罩覆盖网格中的资源标记，但不遮盖资源池列表的占用信息。

- `range` 的世界坐标右/下边界是区域外沿，格子坐标右/下边界是最后一格编号；`scale` 按裁剪后的实际范围计算。

| 分类 | 默认数字 | `--ascii` 字符 |

|---|---:|---:|

| 陆地、水上桥 | 1 | `.` |

| 水域 | -1 | `~` |

| 山地、悬崖、障碍、空白格 | -7 | `^` |

| 岩浆 | -9 | `*` |

| 未占用资源池 | 0 | `0` |

| 已占用资源池 | 8 | `8` |

| 迷雾（仅加 `--fog` 时） | -2 | `?` |

| 我方建筑 | `#ID` | 不覆盖 |

| 友军建筑 | 4 | 不覆盖 |

| 敌方可见建筑 | -4 | 不覆盖 |

### 2.4 敌情与镜头

- `rwx-game-threat-list --x X --y Y [--radius 300] [--cluster [--cluster-radius 120]] [--json]`：查看非迷雾区范围内的敌方单位，包含总数、兵种/攻击能力统计、最近距离和逐单位明细；`--cluster` 合并为集群，`--cluster-radius` 设置合并半径。

- `rwx-game-camera [--x X --y Y] [--center|--top-left] [--zoom Z | --zoom-by 倍率]`：读取或移动 GUI 视野，不暂停游戏。`--x/--y` 默认按中心定位，`--top-left` 改为左上角；`--zoom` 是绝对值，`--zoom-by` 是相对当前缩放；越界会钳制并回报调整。

威胁范围、距离和射程均使用世界坐标单位；`nearestDistance` 相对查询中心。`antiAir`、`antiGround`、`antiSubmerged` 是拥有武器且引擎声明具备对应攻击能力的实体数，不代表当前有目标、在射程内或可开火。`air` 由引擎飞行状态判断，`buildings` 按单位类型判断，`ground` 是非飞行且非建筑的实体；目前没有独立水下分类。

## 3. Combat — 战斗指挥

控制军事单位的行动与战斗组织。

### 3.1 姿态与推进

- `rwx-game-order-attack-mode [--units <ids|选择器>] --mode <outOfRange|onlyInRange|returnFire|holdFire|guardArea|aggressive|mixed> [--apply-to-new]`：设置攻击姿态。`returnFire` 只还击，`holdFire` 不主动开火，`guardArea` 守区，`aggressive` 主动追击，`outOfRange`/`onlyInRange` 按射程约束，`mixed` 混合。

- `rwx-game-order-attack-mode --apply-to-new --mode off`：清除新单位默认姿态。

- `rwx-game-order-push [--units <ids|选择器>|--group <name>] --x X --y Y [--rally-x X --rally-y Y] [--idle] [--min-count 1..200] [--type ...] [--exclude ...] [--hp ...] [--exclude-builders]`：先集结再推进；给集结点时先 `move` 再排 `attack-move`，否则直接 `attack-move`。单位数少于 `--min-count` 会拒绝。

### 3.2 编组

- `rwx-game-group-save --name <name> --units <ids|选择器> [--type ...] [--exclude ...] [--hp ...] [--idle] [--exclude-builders]`：保存编组；名字 1~32 位字母/数字/`_`/`-`/`.`。

- `rwx-game-group-list`：列出编组、存活数/保存数和类型组成。

- `rwx-game-group-show --name <name>`：查看编组、存活类型组成和阵亡数。

- `rwx-game-group-delete --name <name>`：删除编组。

编组可直接用于 `--units group:<name>`；死亡单位会被跳过并回执 `skipped dead/missing units`。换地图或重开会清空；最多 64 组、每组最多 500 个单位。

### 3.3 移动与交战

- `rwx-game-order-move --units <ids|选择器> --x X --y Y [--queue]`：直接移动，不主动找敌人。

- `rwx-game-order-attack-move --units <ids|选择器> --x X --y Y [--queue]`：边推进边交战，威胁清除后继续前进。

- `rwx-game-order-attack --units <ids|选择器> --target <id> [--queue]`：追击指定目标；只给坐标时请改用 `rwx-game-order-attack-move`。

- `rwx-game-order-stop --units <ids|选择器>`：清空 waypoint 与攻击目标。

- `rwx-game-order-patrol --units <ids|选择器> --x X --y Y [--queue]`：在当前位置与目标点之间巡逻。

- `rwx-game-order-guard --units <ids|选择器> --target <unitId> [--queue]`：保护友军，攻击正在攻击它的敌人，必要时修理。

- `rwx-game-order-guard-at --units <ids|选择器> --x X --y Y [--queue]`：守住一个区域，被拉开后返回。

- `rwx-game-order-follow --units <ids|选择器> --target <unitId> [--queue]`：纯跟随，不专门替目标复仇。

- `rwx-game-order-load-into --units <ids|选择器> --target <transportId> [--queue]`：装进运输单位。

- `rwx-game-order-load-up --units <transportIds|选择器> --target <unitId> [--queue]`：让运输单位装载目标。

### 3.4 复杂参数：单位选择--units 说明

`--units` 参数接受以下形式：

- `id1,id2,...`：显式单位 id。

- `all-army`：全部可控制、非建筑、非建造者单位。

- `all-units`：全部可控制单位，含建筑。

- `group:<name>`：`group-save` 保存的编组。

- `area:<x>,<y>[,<半径格>]`：以世界坐标为中心的圆形区域，默认半径 2 格，只选我方非建筑单位。

- `area:unit:<id>[,<半径格>]`：以某个我方单位为圆心。

- `rect:<x>,<y>[,<H/2>[,<W/2>]]`：轴对齐矩形，默认半高/半宽各 1 格。

- `rect:unit:<id>[,<H/2>[,<W/2>]]`：以某个我方单位为矩形中心。

可叠加的筛选参数：

- `--type <id或名称,...>`：只保留这些类型，支持内部 id 或部分中文名。

- `--exclude <id|名称,...>`：数字按 id 排除，名称按类型排除，可混写。

- `--hp <百分比>`：只保留血量低于该百分比的单位/建筑。

- `--idle`：只看没有 waypoint、工厂队列为空的单位。

- `--exclude-builders`：排除建造者。

区域选择器只取我方、可指挥、非建筑单位；区域半径/半宽是格，中心坐标是世界坐标。类型筛选统一走 `--type`，旧的 `--units type:...` 写法已删除。

## 4. Develop — 基地发展

直接修改建筑、生产、建设和升级状态。

### 4.1 建筑选址

`rwx-game-map-place --builder <id> --type <typeId> --x X --y Y [--radius 0..10]` 只检查能否放置建筑，不会下达建造指令。`--radius` 是搜索最近可放点的范围，单位为格，默认 2；`0` 只检查原点。检查项与 `order-build` 一致：该建造者能否建造这种建筑、地形是否允许（海军基地等需要整块占地都是水面）、建造者能否到达；放不下时会指出占地里落在陆地上的位置。

### 4.2 建造、生产与升级

- `rwx-game-order-build --builder <id> --type <typeId> --x X --y Y [--queue] [--count 1..20] [--auto-place] [--radius 0..10] [--allow-unaffordable]`：让建造者建造；`--count` 一次排多个，`--auto-place` 被阻挡时在 `--radius`（默认 2 格）内自动选最近可放点。

- `rwx-game-order-action --units <ids|选择器> --action <actionId> [--target <id> | --x X --y Y] [--count 1..20] [--allow-unaffordable] [--after-upgrade]`：执行生产/升级/技能动作。

- `rwx-game-order-cancel --unit <工厂 id> [--action <actionId>] [--count N | --all]`：从原版队列取消生产/升级；未执行时退款显示为待结算。

- `rwx-game-order-assist --units <建造者 ids|选择器> --target <在建建筑 id> [--queue]`：续建；完整工厂的生产队列不支持协助。

- `rwx-game-order-rally --units <factoryIds|选择器> --x X --y Y`：设置工厂集结点。

- `rwx-game-order-repair --units <ids|选择器> --target <id> [--queue]`：修理友军。

- `rwx-game-order-reclaim --units <ids|选择器> --target <id> [--queue] [--force]`：回收；回收己方单位必须加 `--force`。

- `rwx-game-nuke-status --unit <发射井 id> [--x X --y Y]`：库存、队列、冷却、射程、爆炸半径和落点范围情报。

### 4.3 待发与延迟执行参数

`--allow-unaffordable`：买不起时不报错，指令进入待发队列；采样器在资金足够时发出，仍会写入回放。生产、升级、建造都适用（升级动作在资金不足时同样进入待发，而不是直接报错）。

`--after-upgrade`：工厂正在升级、下一级动作还不存在时，等升级完成再自动发出；隐含 `--allow-unaffordable`。

## 5. Automation — 自动化

**手动指令优先于全部自动化。** 对选中单位下达移动、攻击、`attack-move`、停止、巡逻、驻守、排队任务、动作或姿态命令时，会撤销其尚未执行的微操并持续交还控制权；旧规则不会因下一次采样、任务结束或再次空闲而恢复。其他单位不受影响。

只有重新执行对应规则的 `auto-set` 开启命令，才会在该规则配置范围内恢复授权，不会开启其他规则。使用 `--queue` 时先撤销临时微操、保留接管前的完整任务队列，再追加手动任务；纯动作和姿态命令保留原任务。

### 5.1 自动补产

`keep` 让工厂自动补产：

- `rwx-game-keep-set --unit <工厂id> --action <产出动作id> --count N`：保持队列中始终有 N 个该动作。

- `rwx-game-keep-list`：查看规则、队列深度、补产次数和状态。

- `rwx-game-keep-clear [--id N]`：删除一条或全部。

规则只在动作可用且买得起时补产；升级中会等待。同一工厂同一动作重复 `keep-set` 是更新数量。规则随地图/对局清空，最多 32 条。

手动向工厂下达生产、升级或技能动作，以及成功执行 `order-cancel` 时，该工厂的旧补产规则暂停，尚未执行的补产指令撤销，避免立即补回被取消的生产。已有原版生产队列按手动命令正常处理；重新执行对应的 `keep-set` 才恢复该条补产规则，`keep-list` 可查看暂停状态。

### 5.2 待发队列

- `rwx-game-pending-list`：查看待发项、等待原因，以及最近 20 条已发出/已丢弃记录。

- `rwx-game-pending-clear [--id N]`：取消一条；省略 `--id` 清空。

待发项上限 20 条；超过 10 分钟仍发不出会丢弃，单位死亡、工厂被毁或动作失效也会丢弃。

待发请求固定使用接受时选中的单位，不会因 `all-army` 或编组后来变化而扩大范围。较新的非排队任务（包括停止）会取消所选单位的旧待发任务；多单位待发项只移除冲突成员。`order-cancel` 同时移除该工厂对应动作的待发生产，省略动作时移除全部待发生产，避免稍后重新生产。

### 5.3 智能微操说明

微操默认关闭。每条规则只有一份配置，重复设置会更新配置，更换对局后重置。

- `rwx-game-auto-set --rule <id> --enabled false`：关闭规则、释放接管单位但保留配置；重新开启用 `--enabled true` 或省略 `--enabled`。
- `rwx-game-auto-list`：列出规则开关、配置、候选数、接管数、触发次数和最近记录。
- `rwx-game-auto-show --rule <id>`：查看单条规则的配置、已接管单位 ID 和触发记录。
- `rwx-game-auto-clear --rule <id>`：关闭规则并恢复默认配置。
- `rwx-game-auto-reset`：关闭全部规则、释放单位并恢复所有默认配置。

规则默认作用于我方符合条件的单位。开启时可通过公共筛选缩小范围（`turret`、`assist-counter` 不接受筛选）：

- `--units <ids|all-army|all-units>`：逗号分隔的单位 ID，或选择非建筑单位的 `all-army`、包含建筑的 `all-units`；仍须满足规则条件。
- `--group <编组名>`：使用保存的编组，成员变化时自动更新；与 `--units` 互斥。
- `--type <id或名称,...>`：仅指定类型。
- `--exclude <id或名称,...>` / `--exclude-builders`：排除指定 ID、类型或建造者。

已授权的规则可在允许的 `attack-move`、`guard-at`、`patrol` 或待机任务中工作；具体限制见各规则。直接移动、指定目标攻击、跟随、建造等任务不被战斗微操覆盖；`unstuck` 也须先授权才可重发任务或侧移。临时微操结束后恢复原任务及后续队列，除非已收到新的手动指令。暂停、速度为 0 或回放期间不产生新决策；`auto-list` / `auto-show` 最多保留最近 60 条触发、恢复和失败记录。

自动化冲突按下列优先级处理，数值越高越优先，但全部低于手动指令。`dive` 只下发动作，`idle-alert` 只提醒、不接管任务。

| 优先级 | 规则 |
|---:|---|
| 100 | `retreat` |
| 90 | `unstuck` |
| 80 | `assist-counter` |
| 70 | `dive` |
| 60 | `mech` |
| 50 | `kite` |
| 40 | `artillery` |
| 35 | `turret` |
| 30 | `focus-fire` |
| 20 | `guard` |
| 15 | `assist-build` |
| 10 | `repair` |
| 0 | `idle-alert` |

#### 5.3.1 远程单位微操

- `rwx-game-auto-set --rule artillery`：适用于我方名称含 `artillery` 的火炮；仅在 `attack-move`、驻守或待机时接管，不接管巡逻。驻守、待机时在原驻守点附近交战，偏移不超过 120 世界单位。
- `rwx-game-auto-set --rule kite [--radius R]`：适用于我方已完工、可移动的非建筑攻击单位（陆、海、空均可；陆地火炮使用 `artillery`）。只接管对当前可见、可攻击目标的射程达到 `--radius` 的单位；默认 400 世界单位，此参数是**最低射程门槛**。武器就绪时停留开火，冷却时拉开距离。

`kite` 只在 `attack-move`、驻守、巡逻、待机时接管；运输中的单位、禁止开火或仅还击姿态的单位不参与。符合条件的单位自动纳入，可用公共筛选缩小范围。

#### 5.3.2 智能低血后撤（retreat）

- `rwx-game-auto-set --rule retreat [--hp N] [--radius R]`：让可移动攻击单位在 HP ≤ N% 时短距离避险，否则继续原任务；不负责返回基地或寻找修复设施。`--hp` 范围 1..100，默认 20；`--radius` 是威胁搜索半径，默认 300 世界单位。每次后撤距离为半径的 40%，但不少于 90、不多于 200 世界单位。
- `rwx-game-auto-set --rule mech [--hp N] [--radius R]`：攻击性机甲 HP ≤ N%，且（最近 2 秒受攻击或附近发现可攻击敌人）时，远离威胁约 60 世界单位，脱离后恢复原任务。`--hp` 范围 1..100，默认 40，设为 100 可取消血量限制；`--radius` 是敌军搜索半径，默认 320 世界单位，最近攻击者也可触发后撤。

#### 5.3.3 智能潜水（dive）

`rwx-game-auto-set --rule dive`：按射程内交战目标切换上下潜：目标在水下时下潜，否则上浮。适用于同时拥有 `Dive`、`Surface` 动作的普通潜艇、鹦鹉螺号、两栖喷气机和同类 mod 单位。

#### 5.3.4 智能集火（focus-fire）

`rwx-game-auto-set --rule focus-fire`：交战时按攻击者数量分配火力；目标 HP ≤40% 时继续收尾。可用 `--group` 等公共筛选限定范围。

#### 5.3.5 区域防御（guard）

`rwx-game-auto-set --rule guard --x X --y Y [--radius R]`：让可移动攻击单位防守圆形区域，敌人进入时主动接敌，否则回到中心。`--x`、`--y` 为必填的中心世界坐标，也是返回集结点；`--radius` 默认 300 世界单位。单位距中心不超过两倍半径时可被拉回，超出后不再接管。仅对仍被授权的单位有效，手动命令优先。

```text
rwx-game-auto-set --rule guard --group 3 --x 1200 --y 900 --radius 260
```

#### 5.3.6 空闲自动修理、协助施工（repair/assist-build）

- `rwx-game-auto-set --rule repair [--radius R]`
- `rwx-game-auto-set --rule assist-build [--radius R]`

只作用于空闲建造单位，搜索附近受损的己方已完工建筑、未完成施工或单位进行修理/协助，完成后返回原位置。能否修理特定目标由单位能力决定：建造者通常只能修建筑，部分工程师还能修单位。`--radius` 是以修理者为中心的搜索半径，默认 220 世界单位。

#### 5.3.7 卡住脱困（unstuck）

`rwx-game-auto-set --rule unstuck`：单位卡住时尝试脱困；多次失败后记录“无法脱困”，并暂时停止尝试。

#### 5.3.8 智能炮塔（turret）

`rwx-game-auto-set --rule turret`：让我方炮塔更智能地攻击，不主动开火的炮塔除外；手动指定目标优先。不接受公共筛选。

#### 5.3.9 工厂与部队空闲提醒（idle-alert）

`rwx-game-auto-set --rule idle-alert [--include-units <true|false>]`：默认仅提醒我方已完工工厂生产队列为空，不下达命令。

- `--include-units true`：额外提醒空闲攻击单位；默认 `false`，工厂提醒始终保留。
- 添加公共筛选（如 `--group`、`--units`、`--type`、`--exclude`）会自动开启部队提醒，只针对匹配的空闲攻击单位，排除建造者。
- 重复设置保留既有配置。

```text
rwx-game-auto-set --rule idle-alert --include-units true
rwx-game-auto-set --rule idle-alert --group 3
rwx-game-auto-set --rule idle-alert --include-units false
```

#### 5.3.10 智能辅助、反击

`rwx-game-auto-set --rule assist-counter`：让全部己方空闲攻击单位与攻击性建筑智能辅助友军、反击敌人、连续避险。不接受公共筛选；用 `--enabled false` 关闭。

## 6. History — 实时事件

### 6.1 统一事件查询

`rwx-game-events [--types <type1,type2,...>] [--seconds N] [--allies]`

与 `game-state` 共用事件采集器、缓存及查询处理器；查询不消费或重置历史。

- `--types`：类型取并集；默认 `damaged,destroyed,losses,new`，`info` 须显式指定。
- `--seconds N`：最近 N 游戏秒，默认 30，范围 (0, 180]。
- `--allies`：`destroyed`、`losses` 改为仅友军（不含我方），`damaged`、`info`、`new` 仍仅我方。

| 类型 | 内容与聚合规则 |
|---|---|
| `damaged` | 我方单位/建筑受到有效伤害；同一受伤 ID 至少间隔 5 游戏秒才再次记录，攻击者变化不影响。 |
| `destroyed` | 我方击毁敌军，或 `--allies` 时友军击毁敌军；按敌方真实 ID 去重，保留窗口内最早有效记录。 |
| `losses` | 我方（`--allies` 时为友军）损失；按 ID 去重，再按队伍和损失类型分组，相邻损失间隔 ≤3 游戏秒时连续合并。 |
| `info` | 我方单位创建、生产、施工开始/完成、升级开始/完成，只报告真实发生的阶段。 |
| `new` | 我方视野首次或重新发现敌军；同一 ID 在窗口中采用最近一次发现，持续可见不刷新；相邻发现间隔 ≤5 游戏秒时合并（同一单位 5 秒内反复出现只算一次，跨帧修订也用 5 秒窗口）。 |

每行格式为 `[MM:SS][type] description`，使用英文名称和文本，无标题、游标或汇总；无匹配时返回空文本。原始缓存最多保存 180 游戏秒、1000 条。

```text
rwx-game-events --types losses,destroyed --seconds 90
rwx-game-events --types losses --allies
rwx-game-events --types info --seconds 60
```

```text
[09:35][losses] we lost Tank x8 by Heavy Tank(120,220)x5, Tank(100,200)x3
[09:37][new] found Tank x3, Turret T1 x1 around (100,200)
```

`rwx-game-observe-reset [--keep-groups]`：清空事件历史和独立的 `unit-list --delta` 基线，默认也清空编组；`--keep-groups` 保留编组。默认姿态及其已下发单位集合不受影响。

**实时事件流**

启用本地 CLI 的游戏会在回环地址另开事件 Socket，日志 `RWX_EVENTS_READY` 给出端口，默认端口文件为 `agent-operation/tmp/events-port`；自定义 CLI 端口文件时，事件端口文件位于同目录。CLI 命令端口文件不变。使用现有接收脚本：

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
python agent-operation/tools/rwx-game-events-to-jsonl.py --info --allies
```

脚本在接收侧做二次过滤，只把 `kind == "update"` 记录按事件类型分成两个各滚动 500 条的日志：`agent-operation/tmp/game-events-observe.jsonl` 存 `new`（发现敌军）与 `info`（我方生产/升级），`agent-operation/tmp/game-events-combat.jsonl` 存 `damaged`、`destroyed`、`losses`。分开存放可避免反复的重新发现把战损记录挤出窗口。`hello`、`event`、`remove`、`cursor` 与传输诊断不落盘，诊断只输出到 stderr。用 `--observe-output` / `--combat-output` 指定两个日志，`--port` 或 `--port-file` 选择实例，按 Ctrl+C 退出。

默认订阅为 `info=false, allies=false`：`new`、`info` 始终保留我方与盟友记录（阵营范围不会隐藏观察类事件），而 `damaged`、`destroyed`、`losses` 只订阅我方阵营。`--info` 加入信息事件，`--allies` 把击杀与损失切到友军。

断线时接收器依据最后会话和序列号重连；游标跟随每一条序列（无论是否落盘），重连不会重放也不会误报缺口，重启后从最后一条已存 `update` 继续。传输缓存最多 180 游戏秒、1000 条。超出缓存或队列溢出会在 stderr 报告 `gap`、`overflow`，不保证补全。进程重启会产生新会话，无法恢复旧进程的内存记录。

**订阅协议**

使用 UTF-8 NDJSON，客户端发送以下一行并以换行结束：

```json
{"protocol":"rwx-events-v1","kind":"subscribe","session":null,"after":0,"info":false,"allies":false}
```

服务端先返回 `hello`，随后发送 `event`、`update`、`remove`、`cursor`、`gap`、`overflow` 或 `reset`。`seq` 为传输序列；`eventId` 和 `revision` 标识聚合修订；`remove.reason` 为 `replaced` 或 `expired`。`event.type`、`event.description`、`gameTimeMillis`、`tick` 来自真实事件，`session`、序列、修订、缺口消息属于传输元数据。`allies` 只管 `destroyed` 与 `losses`（取友军而非我方），`damaged`、`new`、`info` 不因此收窄，信息事件只由 `info` 决定。聚合消费者必须按 `eventId` 应用修订和删除，不得把 `update` 算作新事件。

### 6.2 回放

- `rwx-game-replay-start [--name <文件名>]`：对已开局对局从当前状态开始录。

- `rwx-game-replay-stop`：停止并落盘。

- `rwx-game-replay-status`：查看录制状态、文件、大小和已记录命令数。

回放文件写入 `../replays`。暂停和 `gameSpeed` 不会写进回放；暂停期间下达的命令会记录在同一 tick。要在开局时从 tick 0 录制，用 `rwx-battleroom-start --record`。

## 7. Reference — 游戏百科

查询兵种、建筑、经济与科技等静态资料。

`--name` 接受内部 id、内部短名、本地化名称或自定义单位 `displayText`；大小写、空格、下划线和连字符不敏感。

单位与建筑共用同一套定义结构：`id`、`name`（英文）、`name_zh`（中文）、`kind`、`cost`、`hp`、`speed`、`vision`、`weapon`、`ability`、`actions`、`produces`、`builds`、`upgrade`、`requires`、`from`、`placement`、`features`、`about`。结构性不适用的字段整行省略（如坦克没有 `produces`）；数值统一用格、秒、credits。

### 7.1 单位

- `rwx-query-unit [--name <名称>] [--category <builder|transport|land|air|sea|lab>] [--level 0..2] [--detail] [--json]`：省略参数时列出全部单位与建筑的名称索引；`--name` 输出单个单位定义；`--category` 输出对应类别的表格（运输单位带载人容量）。

- `--detail`：展开逐项动作（id、费用、目标）、通用指令、别名与游戏描述全文。

- `--json`：与当前档位同字段的 JSON；`--detail --json` 输出展开版。

- `--level` 只对 `land`、`air`、`sea` 有效：`0` 不限等级，`1` 为未升级工厂可生产，`2` 为需要升级后的工厂。

### 7.2 建筑

- `rwx-query-building [--name <名称>] [--category <turret|defense|economy|nuke>] [--detail] [--json]`：省略参数时列出全部建筑的名称索引；`--name` 输出建筑定义（含升级路径与占地）；`--category` 输出对应类别的表格。

### 7.3 生产能力

- `rwx-query-production --factory <land|air|sea|lab|commander|名称|id> [--level 0..2] [--maxCost <成本>]`：指定工厂的可生产单位或可建造建筑；产出单位时给生命/射程列，产出建筑时给占地列。

## 8. System — 系统工具

CLI 端点与连接相关功能。

### 8.1 启动与连接

启动游戏和命令端点：`:desktop:rwxCliRun`，或 `:desktop:run --args="--rwx-cli"`；需要 Java 25。`rwx-ping` 返回 `pong`，用于探活。

`--headless` 隐含 `--rwx-cli`，不创建 GUI、窗口、图形上下文、音频设备或额外终端；保留游戏 Tick、寻路、网络、CLI 和命令调度，不需要 Xvfb。

`--data-dir=<目录>` 设置实例数据和身份目录；`--cli-port-file=<文件>` 设置 CLI 端口文件。多实例须分别指定目录和端口文件。

纯 Headless 发行包：`:desktop:headlessArchive`，产物为 `desktop/build/distributions/rwx-headless.tar.gz`；Linux 使用 `bash bin/rwx-headless` 启动。部署见 [SERVER.md](SERVER.md)。

`--evaluate` 一次打开所有内置评估程序（引擎证据采集、命令自动录制、事件流），并设置对局开始自动录制；不加该参数时这些功能全部关闭，正式对局不会被采样和录包拖慢。证据默认写入 `evaluate/output/events-live`，可用 `-Drwx.evaluate.dir=<目录>` 覆盖。

整局复用持久连接：宿主可保持子进程和标准流时优先使用 `rwx-cli.py --stdio`；宿主已支持持久 TCP 时也可直接使用。无法保持连接时使用单次或批量客户端。启动和开局见 [START.md](START.md)，其他客户端与协议见 [tools/README.md](tools/README.md)。

### 8.2 部分约定

- **端口文件**：CLI 启动时随机监听端口，成功后原子写入 `<仓库根>/agent-operation/tmp/port`（只含端口数字）。可用 `--cli-port-file=<路径>`、`-Drwx.cli.portFile=<路径>` 或 `RWX_CLI_PORT_FILE` 指定路径。此端口仅供本地 CLI 使用，不是多人游戏端口。
- **多实例**：默认端口文件由持有进程锁的主实例发布，其余实例不覆盖。每个实例另有 `port.instances/<PID>-<端口>.port`，路径见 `RWX_CLI_TOOL_READY` 的 `instancePortFile`。主实例退出后其他实例接替；退出清理时仍持有文件锁，不会误删新主实例记录。
- **端口验证**：异常退出可能遗留文件；客户端须用 `rwx-ping` 验证端口。Python 工具按显式端口/文件、环境变量、主文件、独立实例文件的顺序发现端口；多候选时需显式选择。端口记录目录已加入 `.gitignore`。
- **编码**：收发文本均为 UTF-8，服务端回复固定 UTF-8，旧客户端按 GBK 发送的命令行可兼容解码。`--rwx-cli` 进程的标准输出/错误及日志也是 UTF-8；GBK 终端直接显示中文可能乱码，不影响文件和回环回复。
- **命令参数**：含空格的值使用单双引号，如 `--name "Lake (2p)"`；服务端支持纯文本命令行。
- **坐标**：默认使用世界坐标，实际对局 1 格 = 20 世界单位。仅明确标注“格”的范围参数使用格数（如 `map-place --radius`、`map-show --terrain --half-width/--half-height`），`unit-list --radius` 等其余范围参数使用世界坐标。

### 8.3 指令回执语义与命令队列机制说明

- `submitted`：命令已提交给引擎；是否立即应用取决于暂停及回放录制状态，见下文。
- `pending`：命令由 CLI 暂存，等待资金或升级完成后自动发出。
- `partial`：`--count N` 仅提交一部分，回执含 `requested/submitted/pending/failed`。
- `error:`：整条命令失败，没有发出任何命令。
- 未加 `--queue` 时替换原命令；加上则追加到原队列。发生替换时回执会提示。
- 暂停或速度为 0 且未录制回放时立即应用（`appliedImmediately: true`）；回放录制期间排队到下一 tick，避免回放 desync。
- 提交前校验控制权、目标关系、坐标、建造能力和动作可用性。
