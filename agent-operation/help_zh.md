# RWX CLI

本页可用 `rwx-help` 查看。

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

- `connect` 异步建立游戏连接，默认原版 TCP 端口 `5123`。支持原版直连地址、`host:port/房间码`、短中继码及 `get|...` 主服务器描述符。`--room` 为直连中继追加原版查询串；`get|...` 已标识房间，不能再叠加 `--room`。玩家名长度 2..20。
- `RELAY-CN` 按当前公共主服务器列表的发布者/房间标识查找，不硬编码地址；列表不可达、无匹配或协议握手失败均会明确报告。入口名称本身不能证明服务可用。公共中继的创建/加入指令以入口实际提示为准。
- 连接回执仅表示已请求连接；`connection-status` 的 `joined` 需要收到原版房间配置及本地队伍分配，`in-game` 还需要游戏载入。状态另含 `resolving`、`connecting`、`hosting`、`disconnected`、`error`，并显示游戏端口、队伍和错误。
- 超时覆盖 TCP 建连和房间注册，范围 1000..120000 ms。超时会取消尝试并关闭游戏连接，迟到的 socket 会关闭。密码提示可通过 `--password` 或 RWX 进程环境变量 `--password-env` 回答，错误密码不会无限重试。
- `--reconnect true` 复用上次地址和同一客户端身份。房主决定能否恢复原队伍；已开始的游戏通常拒绝全新玩家。多个 Agent 应分别使用 `--data-dir`，不能共享同一持久客户端身份。
- `disconnect` 关闭游戏网络连接并回到菜单，保留 RWX 进程和本地 CLI；关闭 Python/PowerShell CLI 客户端也不会退出游戏。`exit` 则关闭 RWX 进程及其 CLI。
- `host` 复用原版托管：监听指定的 TCP 和可靠 UDP 游戏端口，默认不向公共主服务器发布。`--spectator true` 让服务器本地玩家旁观；`--auto-start-players 2` 明确授权房主等待至少两个非旁观真人客户端后开局，`0` 关闭自动开局。AI 不计入该门槛。
- `--managed-room true` 启用常驻房间策略，隐含服务器旁观，与自动人数开局互斥。首位客户端获得原版代理房主权限；退出后按加入顺序接替，选举只在大厅发生，对局中冻结。代理房主的 GUI/CLI 管理请求通过原版聊天包发送给服务器校验和执行，CLI 回执表示请求已提交，需查询房间确认结果。
- 托管对局中，新身份加入自动观战；原玩家断线重连保留原席位。所有在线非旁观玩家均已退出或战败时，服务器强制结束并通过原版包返回大厅，保留仍在线客户端。观战客户端和游戏 AI 不阻止结束；不指定胜方。代理权限不会赋予其他队伍单位的控制权。
- `connection-status` 增加 `roomAdmin`（本客户端的代理管理权）、`roomManager`（服务器本地查询时的管理员名）和 `spectator`。普通客户端只能通过原版房间状态和代理权限位获知其权限。
- 普通玩家可查看房间、聊天、按原版规则调整自己的出生点/阵营，并在游戏中查询可见信息和控制己方单位。房主/原版代理房主才可修改房间、地图、AI 或开始游戏。Agent 没有额外权限，指令走原版 `Command` 网络队列。

示例：Agent 本地运行自己的 RWX，再让本地 CLI 连接云服务器的游戏端口：

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

默认按固定顺序显示战局、经济、我方/友军/当前视野内敌军的兵力、生产、升级、我方血量分档、CLI 待发项、需关注的单位与建筑，以及最近 30 游戏秒的重要事件。空区块显示 `none`。`--summary` 保留全部宏观统计和事件，不显示逐实体明细；它与四个实体筛选开关互斥。

- `--units-acting`：正在移动、交战、建造、生产、修理、巡逻、守卫或跟随等的非建筑单位。
- `--units-damaged`：HP 严格低于 70% 的非建筑单位。
- `--building-acting`：正在施工、生产、升级、修理、攻击或防御的建筑。
- `--building-actionable`：当前资金与条件满足、可以升级或生产的建筑。

四个筛选可组合，取并集并去重；不传时启用全部四类。筛选只影响实体明细，不影响统计。状态取引擎实际活动；详情包含 ID、坐标、血量、当前指令、目标和 waypoint，建筑附科技等级及生产队列摘要。

- `--event-seconds N`：游戏时间窗口，默认 30，必须大于 0 且不超过 180。
- `--event-types`：逗号分隔，默认 `damaged,destroyed,losses,new`；`info` 仅显式指定时显示。
- `--event-allies`：`destroyed` 和 `losses` 仅显示友军；其他类型规则不变，见 6.1。
- `--no-events`：不查询或显示事件；不能与其他事件参数同用，不清除历史。
- `--json`：输出标准 JSON，与文本使用相同报告数据。

JSON 顶层分为 `battle`、`economy`、`forces`、`production`、`upgrades`、`healthStatus`、`pending`、`notableUnits`、`notableBuildings` 和 `events`。`--summary` 的两个实体数组为空；`--no-events` 省略 `events`。`battle` 包含 `tick`、`time`、`gameTimeMillis`、`paused`、`speed`、`map`、`controlledTeam`、`outcome`；经济为 `credits`、`incomePerSecond`。

- `forces.self` / `allies` / `visibleEnemies` 分别包含 `unitCount`（不含建筑）、`buildingCount` 和结构化的 `typeCounts`（每项 `type`、`count`）；我方和友军另含 `idleUnitCount`。友军不包含我方，敌军仅为当前可见实体，生产队列不计入兵力。
- `production.factoryCount` 为我方已完工且能生产单位的建筑数；`busyFactoryCount` / `idleFactoryCount` 按队列是否为空统计，升级占用算忙碌；`productionQueueEntryCount` 只计生产条目，不计升级。
- `upgrades` 包含 `id`、`type`、`upgrade`、`progress`；进度为 0..1，未知为 null。
- `healthStatus.units` / `buildings` 按类型统计 `criticalCount`（HP <10%）、`lowHealthCount`（10% ≤ HP <50%）、`injuredCount`（50% ≤ HP ≤80%）。
- `pending` 包含 `pendingOrders`、`waitingForCreditsCount`、`waitingForUpgradeCount`；不支持待发查询时为 null。
- `events.items` 包含共享查询处理后的 `gameTimeMillis`、`type`、`description`；窗口为 `windowSeconds`。事件使用游戏时间，查询不消费历史。

例如：

```text
rwx-game-state
rwx-game-state --summary --json
rwx-game-state --units-damaged --building-acting --event-seconds 60
rwx-game-state --event-types info --event-allies
rwx-game-state --no-events
```

- `rwx-game-team-list`：全部队伍、阵营、AI、active/defeated、本队资金与净收入；非本队不暴露经济。

### 2.2 单位列表与详情

`game-state` 只给需关注实体的简要状态；完整清单、单个实体的深入信息和可用动作分别使用以下命令。

- `rwx-game-unit-list [--owner self|ally|enemy|neutral|any] [--type <id或名称,...>] [--group <name>] [--units <ids|选择器>] [--x X --y Y --radius R] [--hp <百分比>|--full] [--idle] [--exclude-builders] [--exclude <id|名称,...>] [--summary] [--delta] [--compact] [--json]`：单位列表，默认只列血量低于 80% 的实体。
- `rwx-game-unit-show --id <id>`：单个单位详情，含阵营、T 级、升级等级、攻击模式、生产队列、完整 waypoint 队列；被暂存的指令会列在 `cliPending`。
- `rwx-game-unit-actions --unit <id> [--json]`：该单位当前可执行的动作，含动作 id、价格、是否可下、排队数、不可用原因；`queued` 是真实生产队列里该动作的数量（含正在生产的那个），`available` 表示现在真的买得起至少一个。

`unit-list` 的常用开关：

这个命令列出匹配的实体（包括单位和建筑），用于获取 ID、世界坐标、血量、指令和队列；敌方实体仅在当前可见时返回。默认是“需要关注”的视图：只列血量低于 80% 的实体；`--hp N` 改这条线，`--full` 取消筛选列出全部。输出默认不带变化区，只有显式 `--delta` 才报告相对上次 `--delta` 查询的变化并推进基线；`--json` 永远只读。

- `--owner self|ally|enemy|neutral|any`：所属关系；省略时只包含我方和当前可见敌方，排除盟友和中立实体；`ally` 仅盟友。默认变化记录采用相同的阵营范围。
- `--type <id或名称,...>`：按单位类型筛选，内部 id 精确匹配，找不到时按本地化名称匹配（支持“重型坦克”这种部分名）。这是唯一的类型筛选入口。
- `--units <ids|选择器>` / `--group <name>`：按实体 ID、类型、编组或区域进一步筛选，选择器见 3.4。
- `--x X --y Y --radius R`：世界坐标圆形范围，中心坐标必须成对给出，半径非负；此处的 `--radius` 保留，不受 

`--owner any` 包含所有可观察阵营，省略时仍只包含我方和当前可见敌方。`--idle` 只选没有当前 waypoint 和生产队列的实体；`--exclude-builders` 和 `--exclude` 排除建造者或指定类型。`--summary` 仅统计所有匹配项，不接受 `--hp`、`--full`、`--compact`、`--delta` 或 `--json`。`--compact` 仅改变文本展示；`--delta` 与上一次 `--delta` 的结果比较并更新基线，检测新增、离开、血量下降和科技等级提高；`--json` 与 `--delta` 互斥且不更新基线。

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

**Agent 手动指令优先于全部自动化。** 对单位下达移动、攻击、`attack-move`、停止、巡逻、驻守、排队任务、动作或姿态命令时，撤销该单位尚未执行的微操，并持续交还控制权；旧规则不会在下一次采样、任务结束或单位再次空闲后自行抢回。只影响此次选中的单位，其他单位继续自动化。

要重新授权某条微操规则，显式执行该规则的 `auto-set` 开启命令；仅在该规则配置范围内恢复授权，不会顺便开启其他规则。例如：

```text
rwx-game-order-attack-move --units 123 --x 1800 --y 900
rwx-game-auto-set --rule guard --units 123 --x 1200 --y 900 --radius 260
```

第一条命令让 #123 执行手动推进，旧 guard 不再把它拉回；第二条明确把 #123 重新交给区域防御。`--queue` 会先撤掉临时微操、保留接管前的完整任务队列，再追加手动任务；纯动作和姿态命令保留原任务。

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

微操默认全部关闭，每条规则只有一份配置，重复设置会更新它，更换对局后全部重置。

- `rwx-game-auto-set --rule <id> --enabled false`：关闭指定功能并释放其接管的单位，保留配置。重新开启用 `--enabled true`，也可省略 `--enabled`。
- `rwx-game-auto-list`：查看所有功能的开关、当前配置、候选数、接管数、触发次数和最近记录。
- `rwx-game-auto-show --rule <id>`：查看一个功能的配置、接管中的单位 id 和触发记录。
- `rwx-game-auto-clear --rule <id>`：关闭一个功能并恢复其默认配置。
- `rwx-game-auto-reset`：关闭全部微操、释放接管的单位，并恢复全部默认配置。

例如，只关闭火炮微操并查看状态：

```text
rwx-game-auto-set --rule artillery --enabled false
rwx-game-auto-show --rule artillery
```

各功能默认作用于我方符合条件的单位。需要自定义缩小范围时，可在开启命令后追加以下公共筛选；不需要筛选时直接使用各节的最简命令（`turret` 和 `assist-counter` 不接受筛选，见 5.3.8、5.3.10）。

- `--units <ids|all-army|all-units>` :逗号分隔的单位 id；`all-army` 选非建筑单位，`all-units` 包含建筑。仍须满足该功能自身的条件。
- `--group <编组名>` : 使用 `group-save` 保存的编组，如 `--group 3`；随编组成员变化自动更新。与 `--units` 二选一。
- `--type <id或名称,...>` : 只选择指定类型。
- `--exclude <id或名称,...>` / `--exclude-builders` : 排除指定单位、类型或建造者。

显式开启微操后，规则可以在允许的 `attack-move`（边走边打）、`guard-at`（驻守）、`patrol`（巡逻）或待机任务上工作；火炮、维修等功能的额外限制见各自章节。此后再下达手动命令即持续让权，必须重新 `auto-set` 才恢复授权。直接移动、指定目标攻击、跟随、建造等任务不被战斗微操覆盖；`unstuck` 也必须先获得授权才能重发任务或侧移。

临时微操结束后会恢复原任务及后续队列；若你已下达新指令，则以新指令为准。暂停、速度为 0 或播放回放时不做新决策。触发、恢复和失败记录可用 `auto-list` / `auto-show` 查看，最近记录最多保留 60 条。

下表的数字仅用于自动化之间发生接管冲突时的排序，数值越大越优先；全部低于手动命令。`dive` 只下发动作，`idle-alert` 只提醒，不持有任务接管。

| 优先级 | 规则 | 手动指令后的行为 |
|---:|---|---|
| 100 | `retreat` | 停止自动后撤 |
| 90 | `unstuck` | 停止重发及侧移 |
| 80 | `assist-counter` | 停止自动支援、反击和后撤 |
| 70 | `dive` | 撤销待执行动作，停止自动上下潜 |
| 60 | `mech` | 停止自动后撤 |
| 50 | `kite` | 停止改写站位与目标 |
| 40 | `artillery` | 停止火炮微操 |
| 35 | `turret` | 停止自动指定攻击目标 |
| 30 | `focus-fire` | 停止自动转火 |
| 20 | `guard` | 停止接敌和拉回旧防区 |
| 15 | `assist-build` | 停止自动协助施工 |
| 10 | `repair` | 停止自动修理 |
| 0 | `idle-alert` | 继续只读提醒，不发命令 |

#### 5.3.1 远程单位微操

- `rwx-game-auto-set --rule artillery`：我方所有名称含 `artillery` 的火炮单位；只在边走边打、驻守或待机时接管，不接管巡逻。驻守和待机时在原驻守点附近交战，偏移不超过 120 世界单位。
- `rwx-game-auto-set --rule kite [--radius R]`：作用于我方已完工、可移动的非建筑攻击单位，不限陆军、海军或空军；陆地火炮使用 `artillery` 规则。单位对当前可见、可攻击目标的射程必须达到 `--radius`（默认 400 世界单位）。武器就绪时停留开火，冷却时拉开距离。

- `--radius R`：允许接管的最低对敌射程，默认 400 世界单位。这里表示射程门槛。

`kite` 只在边走边打、驻守、巡逻或待机时接管；运输中的单位、禁止开火或仅还击姿态的单位不参与。无需逐个指定兵种，符合条件的单位会自动纳入，也可用公共筛选限定。

例如，只让对敌射程至少为 350 的单位参与风筝：

```text
rwx-game-auto-set --rule kite --radius 350
```

#### 5.3.2 智能低血后撤（retreat）

`rwx-game-auto-set --rule retreat [--hp N] [--radius R]`：让可移动攻击单位低血量时后撤，否则会继续原任务。只负责短距离避险；返回基地、寻找修复设施仍需另行下令。

- `--hp N`：血量触发线，取值 1..100，默认 20；血量 ≤ N% 时可触发。
- `--radius R`：威胁搜索半径，默认 300 世界单位。每次后撤距离为半径的 40%，最少 90、最多 200 世界单位。

例如，血量不超过 25%、320 范围内有威胁时后撤：

```text
rwx-game-auto-set --rule retreat --hp 25 --radius 320
```

`rwx-game-auto-set --rule mech [--hp N] [--radius R]`：攻击性机甲单位最近 2 秒受到攻击，或附近发现可攻击的敌人时，远离威胁约 60 世界单位，脱离后恢复原任务。

- `--hp N`：仅在血量 ≤ N% 时后撤，取值 1..100；默认 40，即血量不超过 40% 时才后撤。设为 100 可取消血量限制。
- `--radius R`：附近敌人的搜索半径，默认 320 世界单位；最近攻击者也可触发后撤。

例如，只让半血及以下的机甲执行后撤：

```text
rwx-game-auto-set --rule mech --hp 50
```

#### 5.3.3 智能潜水（dive）

根据射程内交战目标切换上下潜：目标在水下时下潜，否则上浮。

`rwx-game-auto-set --rule dive`

适用于同时拥有 `Dive` 和 `Surface` 动作的普通潜艇、鹦鹉螺号、两栖喷气机及同类 mod 单位。

```text
rwx-game-auto-set --rule dive
```

#### 5.3.4 智能集火（focus-fire）

`rwx-game-auto-set --rule focus-fire`:单位在交战时智能分配火力，按攻击者数量分配火力，目标血量 ≤40% 时继续收尾

例如，只对编组 3 开启智能集火：

```text
rwx-game-auto-set --rule focus-fire --group 3
```

#### 5.3.5 区域防御（guard）

让可移动攻击单位守住一个圆形区域：区域内出现敌人时主动接敌，否则返回区域中心。单位在中心两倍半径以内仍会被拉回，超出后不再由该区域防御规则接管。

上述牵引只适用于仍被授权的单位。手动 `attack-move`、`move`、`stop`、`guard-at`、`patrol` 等命令立即优先，不会因为单位还在两倍半径内就被旧 guard 反复拉回。

`rwx-game-auto-set --rule guard --x X --y Y [--radius R]`

- `--x X --y Y`：必填，防守区域的中心世界坐标，也是返回的集结点。
- `--radius R`：防守半径，默认 300 世界单位。

例如，让编组 3 防守中心为 (1200, 900)、半径为 260 的区域：

```text
rwx-game-auto-set --rule guard --group 3 --x 1200 --y 900 --radius 260
```

#### 5.3.6 空闲自动修理、协助施工（repair/assist-build）

让空闲建造者或工程师寻找附近已完工、受损的己方建筑、未完成的施工或单位并修理，完成后返回原位置。能够修理哪些目标由单位自身能力决定。

`rwx-game-auto-set --rule repair [--radius R]`
`rwx-game-auto-set --rule assist-build [--radius R]`
- `--radius R`：以修理者为中心的目标搜索半径，默认 220 世界单位。

只作用于空闲建造单位。建造者通常只能修建筑，能够修单位的工程师也会修理己方单位。

```text
rwx-game-auto-set --rule repair --radius 180
rwx-game-auto-set --rule assist-build --radius 200
```

#### 5.3.7 卡住脱困（unstuck）

`rwx-game-auto-set --rule unstuck`:当单位卡住时智能脱困,多次尝试失败会记录“无法脱困”并暂时停止尝试。

```text
rwx-game-auto-set --rule unstuck
```

#### 5.3.8 智能炮塔（turret）

`rwx-game-auto-set --rule turret`

让我方所有炮塔攻击更加智能.不主动开火的炮塔除外.手动指定攻击目标时以你的指令优先.

启用、关闭与查看：

```text
rwx-game-auto-set --rule turret
rwx-game-auto-set --rule turret --enabled false
rwx-game-auto-show --rule turret
```

#### 5.3.9 工厂与部队空闲提醒（idle-alert）

默认只在我方已完工工厂的生产队列为空时写入提醒。也可开启空闲部队提醒，便于发现未分配任务的攻击单位。只提醒，不下达命令。

`rwx-game-auto-set --rule idle-alert [--include-units <true|false>]`

- `--include-units <true|false>`：部队提醒开关，默认 `false`，保留工厂提醒。
- 传入公共筛选（如 `--group`、`--units`、`--type`、`--exclude`）也会自动开启部队提醒，并只提醒符合筛选的空闲攻击单位；排除建造者。
- 重复设置时保留已有配置。

只开启工厂提醒：

```text
rwx-game-auto-set --rule idle-alert
```

开启部队提醒，再将部队提醒范围改为编组 3：

```text
rwx-game-auto-set --rule idle-alert --include-units true
rwx-game-auto-set --rule idle-alert --group 3
```

关闭部队提醒，并查看状态：

```text
rwx-game-auto-set --rule idle-alert --include-units false
rwx-game-auto-show --rule idle-alert
```

#### 5.3.10 智能辅助、反击

让全部己方空闲攻击单位和攻击性建筑智能辅助友军、反击敌人、连续避险

```text
rwx-game-auto-set --rule assist-counter
rwx-game-auto-set --rule assist-counter --enabled false
```

## 6. History — 实时事件

### 6.1 统一事件查询

`rwx-game-events [--types <type1,type2,...>] [--seconds N] [--allies]`

`game-state` 与 `game-events` 共享同一事件采集器、缓存和查询处理器；查询不会消费或重置历史。

- `--types`：多个类型取并集；默认固定为 `damaged,destroyed,losses,new`。`info` 仅在显式指定时输出。
- `--seconds`：最近 N 游戏秒，默认 30，最大 180，必须大于 0。
- `--allies`：将 `destroyed` 和 `losses` 切换为仅友军，不包含我方。`damaged`、`info` 和 `new` 始终以我方为准。

| 类型 | 内容 |
|---|---|
| `damaged` | 我方单位或建筑受到有效伤害；同一受伤 ID 至少相隔 5 游戏秒才保存下一条，与攻击者是否变化无关。 |
| `destroyed` | 我方击毁敌方目标；`--allies` 时仅友军战果。按敌方真实 ID 去重，保留窗口内最早有效记录。 |
| `losses` | 我方损失；`--allies` 时仅友军损失。按 ID 去重后，按所属队伍和损失类型分组，相邻间隔不超过 3 游戏秒的损失连续合并。 |
| `info` | 我方单位创建、生产、施工开始/完成、升级开始/完成；只描述已发生的真实阶段。 |
| `new` | 我方视野内首次或重新发现的敌军。同一 ID 在查询窗口中采用最新一次发现；持续可见时不刷新。相邻发现间隔不超过 3 游戏秒时连续合并。 |

输出每行固定为 `[MM:SS][type] description`，名称和文本为英文，不带标题、游标或统计汇总。没有匹配事件时返回空文本。

```text
rwx-game-events
rwx-game-events --types losses,destroyed --seconds 90
rwx-game-events --types losses --allies
rwx-game-events --types info --seconds 60
```

```text
[09:32][damaged] Tank,123(100,200) was damaged by Heavy Tank(120,210)
[09:33][destroyed] our Tank,123 has destroyed Turret(120,240)
[09:35][losses] we lost Tank x8 by Heavy Tank(120,220)x5, Tank(100,200)x3
[09:36][info] Factory,123 produced Tank,456
[09:37][new] found Tank x3, Turret T1 x1 around (100,200)
```

原始缓存最多保留 180 游戏秒、1000 条

`rwx-game-observe-reset [--keep-groups]`：清空事件历史和独立的 `unit-list --delta` 基线。默认同时清空编组；`--keep-groups` 保留编组。默认姿态及其已下发单位集合不受影响。

事件流与 CLI 查询共享同一采集器和聚合器。启用本地 CLI 的游戏启动后，除命令端口外还在回环地址另开一个事件 Socket 端口，日志 `RWX_EVENTS_READY` 包含端口；默认发现文件为 `agent-operation/tmp/events-port`，自定义 CLI 端口文件时事件端口文件位于同一目录。命令端口文件保持不变。

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
python agent-operation/tools/rwx-game-events-to-jsonl.py --info --allies
```

接收器把 NDJSON 消息追加至 `agent-operation/tmp/game-events.jsonl`，文件只保留最新 500 条记录（超过后重写为尾部 500 条，旧记录丢弃），Ctrl+C 停止；`--port` 或 `--port-file` 可选择实例。默认事件类型与 `game-events` 一致，`--info` 显式包含信息事件，`--allies` 按查询命令的友军规则切换击杀和损失。断线重连使用日志中最后的会话和序列号；缓存最多 180 游戏秒和 1000 条传输记录，超出范围或队列溢出会记录 `gap` 或 `overflow`，无法保证补全。进程重启产生新会话，无法恢复旧进程的内存记录。

协议为 UTF-8 NDJSON：客户端发送 `{"protocol":"rwx-events-v1","kind":"subscribe","session":null,"after":0,"info":false,"allies":false}` 并换行；服务端返回 `hello`，然后发送 `event`、`update`、`remove`、`cursor`、`gap`、`overflow` 或 `reset`。`seq` 是传输序列，`eventId` 和 `revision` 标识同一聚合的修订；`remove` 的 `reason` 为 `replaced` 或 `expired`。`event.type`、`event.description`、`gameTimeMillis` 和 `tick` 源于实际事件，`session`、序列、修订及缺口消息属于传输元数据。聚合消费者须按事件 ID 应用修订与删除，不能将 `update` 另计为新事件。

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

启动游戏与命令端点：`:desktop:rwxCliRun`（或 `:desktop:run --args="--rwx-cli"`）。`rwx-ping` 返回 `pong`，用于探活。

`--headless`：不创建 GUI、窗口、图形上下文、音频设备或额外终端；保留原版游戏 Tick、寻路、网络、CLI 和命令调度，隐含 `--rwx-cli`。图像适配器只保留精确尺寸和按需 CPU 像素读取，绘制为空操作；不使用隐藏窗口或 Xvfb。GUI 默认启动行为保持不变。需要 Java 25。

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
python agent-operation/tools/rwx-cli.py rwx-connection-status
```

```bash
bash ./gradlew :desktop:run --args='--headless --no-steam'
python3 agent-operation/tools/rwx-cli.py rwx-connection-status
```

`--data-dir=<目录>` 设置实例的数据/身份目录，`--cli-port-file=<文件>` 显式设置 CLI 端口文件；多个实例应各用独立目录和端口文件。纯 Headless 发行包：`:desktop:headlessArchive`，产物 `desktop/build/distributions/rwx-headless.tar.gz`；Linux 用 `bash bin/rwx-headless` 启动。部署、systemd 与网络验证见 `agent-operation/SERVER.md`。

### 8.2 部分约定

- CLI 每次启动随机监听端口，监听成功后原子写入 `<仓库根>/agent-operation/tmp/port`，只含端口数字；也可用 `--cli-port-file=<路径>`、`-Drwx.cli.portFile=<路径>` 或 `RWX_CLI_PORT_FILE` 指定。它是本地 CLI 端口，不是多人游戏端口。
- 同一默认文件由持有进程锁的主实例发布，其他实例不会覆盖；每个实例另有 `port.instances/<PID>-<端口>.port`，完整路径打印在 `RWX_CLI_TOOL_READY` 的 `instancePortFile`。主实例退出后其他实例接替；退出清理仍持有文件锁，不能误删后继实例的记录。
- 异常退出可能留下旧文件；客户端必须 `rwx-ping` 验证，失效端口不可直接使用。Python 工具优先显式端口/文件、环境变量、主文件，再检查独立实例文件；有多个候选时要求显式选择。端口记录目录已加入 `.gitignore`。
- 所有收发文本都是 **UTF-8**。服务端回复固定 UTF-8；旧客户端若按 GBK 发命令行，服务端会兼容解码该行。
- 以 `--rwx-cli` 启动时，游戏进程的标准输出/错误也会切到 UTF-8，因此日志文件同样是 UTF-8；如果你的终端本身是 GBK 代码页，直接在终端里看这些中文可能显示为乱码，但文件内容和回环回复不受影响。
- 参数值含空格时用双引号或单引号，例如 `--name "Lake (2p)"`；服务端也支持纯文本命令行。
- 坐标默认是世界坐标；实际对局 1 格 = 20。只有明确标为“格”的范围参数使用格数，例如 `map-place --radius`、`map-show --terrain --half-width/--half-height`；`unit-list --radius` 等其余范围参数使用世界坐标。

### 8.3 指令回执语义与命令队列机制说明

- `submitted`：命令已提交给引擎；暂停时立即生效，否则下一 tick 生效。
- `pending`：命令被 CLI 暂存，等资金或升级完成后自动发出。
- `partial`：`--count N` 只提交了一部分，回执带 `requested/submitted/pending/failed`。
- `error:`：整条失败，没有发出任何命令。
- 未加 `--queue` 会替换已有命令，加了则追加到现有队列；发生替换时回执会提示。
- 暂停或速度 0 且未录制回放时，命令立即应用（`appliedImmediately: true`）；录制回放期间改为排队到下一 tick，避免回放 desync。
- 提交前会校验控制权、目标关系、坐标、建造能力和动作可用性。
