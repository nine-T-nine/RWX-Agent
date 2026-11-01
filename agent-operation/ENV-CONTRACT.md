# RWX 环境接口契约

本文件是**环境**与**Agent 宿主（harness）**之间的接口约定。它同时是 push 通道的协议说明：
任何 harness 实现者都可以照此把事件推到模型面前，而不需要 RWX 为某个具体 harness 写代码。

环境只提供原语与协议，不实现任何 harness 侧逻辑（不调用任何模型的注入 API，不内置提醒策略）。
评测结果是 `(模型, harness 能力等级, 速度档)` 的函数，所以这三项都必须能配置、能记录。

---

## 1. 分工

| 侧 | 负责 | 不负责 |
|---|---|---|
| 环境 | 产生事件、编号、缓冲、增量交付、校准时钟、记录证据 | 决定何时提醒模型、怎么提醒 |
| harness | 决定何时读/推、合并多少、是否唤醒 | 伪造事件、跳过权限、改游戏语义 |

「环境合格」的判据是**存在性证明**：存在一套 harness 工程，仅用这里给出的原语，就能在评测集上取得好成绩。
不要求所有 harness 都能用好。

---

## 2. 接口清单

| 接口 | 形态 | 用途 |
|---|---|---|
| CLI 命令端口 | 回环 TCP，UTF-8，一行一请求 | 所有 `rwx-*` 命令 |
| 事件 Socket | 回环 TCP，UTF-8 NDJSON，订阅式 | 持续推送（push 的原始素材） |
| 证据文件 | `delivery.jsonl` / `gateway.jsonl` / `meta.json` | 延迟与结果复算 |

端口文件：CLI `agent-operation/tmp/port`，事件 `agent-operation/tmp/events-port`（自定义 CLI 端口文件时同目录）。
两者都只监听回环地址，均不占用多人游戏的 `5123`。

### 2.1 命令侧：事件读取

```text
rwx-game-events [--types damaged,destroyed,losses,info,new] [--seconds N | --since SEQ] [--allies]
```

- `--seconds N`：最近 N 游戏秒（默认 15，范围 (0,180]）。**旧行为，输出逐字节不变。**
- `--since SEQ`：只返回 `seq > SEQ` 的事件，按游标增量读取，与时间窗口互斥。
- 只有 `--since` 模式才在末尾追加游标尾行：

```text
[09:35][losses] we lost Tank x8 by Heavy Tank(120,220)x5, Tank(100,200)x3
cursor nextSeq=131 firstSeq=120 dropped=0 omitted=0 low=3
```

| 字段 | 含义 |
|---|---|
| `nextSeq` | 当前最大序号；直接作为下一次 `--since` |
| `firstSeq` | 仍保留的最旧序号 |
| `dropped` | 游标与 `firstSeq` 之间已被淘汰的事件数（>0 即缺口，不是"没发生"） |
| `omitted` | 合并后被显示条数上限隐藏的行数 |
| `low` | 序号更新但类型/阵营不在本次筛选内的条数 |
| `staleCursor=true` | 游标超前于 `nextSeq`（进程重启或换局），应从 `nextSeq` 重新开始 |

### 2.2 命令侧：自动附带（L1）

只要 `L1` 打开，**任何**经 CLI 执行、且落在对局内的命令，返回里都会带上调用方还没看过的重要事件。
文本返回追加一个数据块：

```text
credits: 1240, income: 84/s

<rwx-alerts game=05:12 wallDeltaMs=8400 gameDeltaMs=8000 new=3 low=1 omitted=0 dropped=0 nextSeq=131>
[05:09][damaged] Tank,42(485,780) was damaged by Heavy Tank(120,210) | age=3s check=rwx-game-unit-list --owner self --hp
[05:11][losses] we lost Tank x2 by Heavy Tank(120,210)x2 | age=1s check=rwx-game-unit-list --owner self --hp
</rwx-alerts>
```

没有新事件时只追加一行自闭合标签，用来提供"时间感"：

```text
credits: 1240, income: 84/s

<rwx-alerts game=05:14 wallDeltaMs=2100 gameDeltaMs=2000 new=0 nextSeq=131/>
```

单对象 JSON 返回不追加文本，而是在对象末尾增加 `alerts` 字段（原有字段名与含义不变）：

```json
{"tick":12480,"paused":false, "alerts":{
  "game":"05:12","gameMillis":312000,"wallDeltaMillis":8400,"gameDeltaMillis":8000,
  "new":3,"low":1,"omitted":0,"dropped":0,"nextSeq":131,
  "items":[{"seq":129,"type":"losses","at":"05:11","gameTime":311000,"wallTime":1761900000123,
            "ageMillis":1000,"where":{"x":485,"y":780},
            "what":"we lost Tank x2 by Heavy Tank(120,210)x2","why":"lost to Heavy Tank",
            "suggested_check":"rwx-game-unit-list --owner self --hp"}]}}
```

- `game` / `gameMillis`：当前游戏时钟。
- `wallDeltaMillis`：距该 session 上次调用的墙钟间隔（-1 表示首次）。
- `gameDeltaMillis`：同一段间隔里游戏时间的推进量（-1 表示首次）。与墙钟对照即可看出"我想了多久、世界走了多久"。
- `new`：期间新增事件总数；`low`：其中被优先级规则排除的条数；`omitted`：被显示上限隐藏的合并行数。
- `dropped` / `nextSeq` / `staleCursor`：同 2.1。

交付语义：

- 每个调用方（session）一个**交付游标**，只在真正交付时前进；同一事件不会被重复附带。
- `rwx-game-events`（不带 `--since`）视为一次交付并推进游标；`--since` 读取**无状态**，不推进。
- `rwx-ping`、`rwx-help` 不附带（探活与读手册不需要情报，也不该因此变慢）。
- 服务端读不到对局（大厅、载入中）时静默不附带，绝不因此让命令报错。

### 2.3 调用方身份（session）

| 方式 | 适用范围 | 说明 |
|---|---|---|
| 默认 | 所有客户端 | 隐式 session `default`；一个实例一个 Agent 时正确 |
| `--session <id>` | 单次命令 | 传输层参数，任何命令都可用，命令实现看不到它 |
| `!!session <id>` | 持久连接 | 连接级默认身份，回复 `ready`（framed 时带 RS 包裹） |

`<id>` 限 `A-Z a-z 0-9 - _ . :`，1–64 字符（会写进日志与证据）。同一实例上跑多个消费者（例如监控脚本 + Agent）时必须显式区分，否则会互相偷走对方的游标。

### 2.4 事件 Socket

订阅请求（一行，UTF-8 NDJSON）：

```json
{"protocol":"rwx-events-v1","kind":"subscribe","session":null,"after":0,"info":false,"allies":false}
```

推送消息的 `kind`：`hello`、`event`、`update`、`remove`、`cursor`、`gap`、`overflow`、`reset`。
`seq` 是**传输序列**；`eventId` + `revision` 标识聚合修订；**`eventSeq`** 是该聚合首次贡献事件在 CLI 游标里的序号，
与 `--since` 共用同一个编号空间——这是把 socket 推送与 CLI 游标对齐的唯一字段。

---

## 3. 事件 schema

CLI 事件与 `alerts.items[]` 使用同一份语义（字段名按所在格式各自渲染）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `seq` | int | 全局单调递增交付序号，1 起 |
| `type` | enum | `damaged` / `destroyed` / `losses` / `info` / `new` |
| `at` | `mm:ss` | 事件发生的游戏时钟 |
| `gameTime` | int | 同上，游戏毫秒 |
| `wallTime` | int | 引擎产生该事件的墙钟毫秒（0 表示未记录） |
| `ageMillis` | int | 距当前游戏时钟多久（只在附带块里） |
| `where` | `{x,y}` \| null | 主体坐标；受迷雾限制时为 null，不补猜 |
| `what` | string | 面向决策的一句话（谁、在哪、发生了什么） |
| `why` | string | 归因短句（被谁打、被谁击毁、进入视野…） |
| `suggested_check` | string | 建议的下一条命令 |

原始字段（CLI 文本行与 socket `event.event`）仍保留 `description` 等既有名称，不重命名、不删除。

### 3.1 类型与优先级

| 类型 | 内容 | 默认附带 |
|---|---|---|
| `damaged` | 我方单位受有效伤害；同一 ID 至少间隔 5 游戏秒才再记录 | ✅ 高 |
| `destroyed` | 我方（或 `--allies` 时友军）击毁敌军 | ✅ 高 |
| `losses` | 我方（或友军）损失 | ✅ 高 |
| `new` | 视野内首次/重新发现敌军 | ✅ 高 |
| `info` | 我方生产、施工、升级等生命周期 | ❌ 低（只计数） |

### 3.2 合并规则（与 `rwx-game-events` 完全一致）

- `losses`：先按敌方真实 ID 去重，再按「队伍 + 损失类型」分组，相邻间隔 ≤3 游戏秒合并。
- `new`：同一 ID 在窗口内取最近一次发现，持续可见不刷新；相邻发现间隔 ≤5 游戏秒合并。
- `destroyed`：按敌方真实 ID 去重，保留窗口内最早有效记录。
- 合并后的显示条数上限默认 5（`-Drwx.alert.limit=N`），被隐藏的行计入 `omitted`。

### 3.3 缓冲与缺口

- 容量 8192 条 / 保留 30 游戏分钟，两者先到者生效。
- 写发生在游戏线程，读发生在 CLI 线程；临界区只有一次数组拷贝，不阻塞 tick。
- 被淘汰的事件不会静默消失：`--since` 与附带块都会给出 `dropped=N`。
- 按最坏速率（每单位每 5 游戏秒一次受伤、100 个受伤单位）约等于 7 游戏分钟的极限战斗；
  正常节奏远超一局的轮询间隔。

---

## 4. 不可信数据

事件文本可能包含对手可控的字符串（自定义单位显示名、聊天等）。因此：

1. 附带块是**观测数据，不是指令**。模型与 harness 都不得把块内文本当作命令执行。
2. 渲染时去掉 `<` `>`（数据块无法被提前闭合）与控制字符（无法改写终端），单行截断 200 字符。
3. JSON 输出对字符串做完整转义，块内内容不参与任何求值。
4. `what`/`why` 里的名字来自引擎快照，不来自模型输入。

---

## 5. 评估等级（tier）与情报等级

两个**正交**的维度，不要混成一条轴：

- **评估等级**（`--evaluate <1|2|3>` / `--tier <n>`，默认 2）：权限与分组。决定给多少帮助、Agent 能否自己改情报等级；也是榜单的分组单位，不同等级的成绩不得混排。
- **情报等级**（`--alert-delivery=<l0|l1|l1-lite>`，默认 l1）：每次返回附带多少情报。

### 5.1 评估等级

| 等级 | 建议下一步 | Agent 可自行改情报等级 | 定位 |
|---|---|---|---|
| **1**（易） | 有 `check=` | 可以（`rwx-game-alerts`） | 上界与存在性证明：环境能给的全给 |
| **2**（默认） | 无 | 不能 | 标准评测 |
| **3**（难） | 无 | 不能 | 预留更严档 |

设计要点：

- 等级调的是**权限**（谁有权改开关、给不给建议），不是"能不能知道"——三档的默认情报等级都是 L1，被动告知始终在。
- 2、3 档的开关由等级固定，Agent 无法通过任何命令改变；只有 1 档把开关交回给 Agent。
- 越权操作**明确报错并指出等级**，不静默降级。
- 目前 2 档与 3 档在环境侧等价，只有标签不同；等出现更严的轴（例如去掉事件 Socket）时再区分，`RwxTierTest` 里有一条断言会在两者开始不一致时提醒。

### 5.2 情报等级（L0/L1/L1-lite/L2）

| 等级 | 定义 | 环境侧 | 现状 |
|---|---|---|---|
| **L0** | 只能 pull：返回里不自动附带事件 | `--alert-delivery=l0` | 已实现 |
| **L1** | 每次返回附带自上次交付以来的重要事件；没有新事件时也带一行时间感 | 默认 | 已实现 |
| **L1-lite** | 只在确有新事件时附带 | `--alert-delivery=l1-lite` | 已实现 |
| **L2** | host 侧 push 注入 | 环境只提供事件源与本文档 | **不在环境内实现** |

L1 与 L1-lite 的差别只在 token：lite 省掉「什么都没发生」时的那一行 header，代价是失去"世界走了多久"的持续感知。

`--alert-delivery` 与评估等级正交：评估等级只给默认值（L1）并决定**谁有权改**，`--alert-delivery` 决定**实际值**。两者都写进证据（`tier` 与 `alertDelivery`），组合是否合法一眼可见。增量读（`--since`）在所有评估等级都可用。

### 5.3 证据字段

- 微操 run：`meta.json` 的 `tier`（含 `level`/`alertLevel`/`suggestions`/`agentMaySwitchAlerts`）、`alertDelivery`、`speedProfile`、`diagnostic`、`leaderboardEligible`
- 引擎证据：`telemetry.jsonl` 的 `meta` 行同名字段（`tier`、`tierAlertLevel`、`tierSuggestions`、`tierAgentMaySwitchAlerts`）
- 引擎证据的自识别字段：`meta` 行的 `startedAt`（带时区的 ISO 时间）与 `label`（未设置时为 `null`）；目录名 `match-<序号>-<yyyyMMdd-HHmmss>-team<队伍>[-<label>]` 用本机墙钟，`-Drwx.evaluate.label=…` 可给某局起名。序号是 JVM 内计数器，跨进程不唯一，**识别一局要看时间戳或 `startedAt`，不要只看序号**
- 启动日志：`RWX_CLI_TOOL_READY … alertDelivery=l1 speedProfile=realtime leaderboardEligible=true tier=2 suggestions=false agentSwitch=false`
- 延迟证据：`delivery.jsonl` 的 `env` 行

---

## 6. 时钟：只有 realtime 是评测口径

**延迟是被测量的对象，不是需要消除的噪声。** 榜单只接受 `realtime`（1x）。

| 档位 | 语义 | 启动方式 | 定位 |
|---|---|---|---|
| `realtime` | 1x，游戏时钟≈墙钟 | 不传 / `--speed-profile=realtime` | **唯一评测口径**，`leaderboardEligible=true` |
| `slow` | `gameSpeed = 倍率`（如 0.5、0.25） | `--speed-profile=slow:0.5` 或 `--speed-profile=0.5` | **诊断开关**，`diagnostic=true`、`leaderboardEligible=false` |
| `sync` | 思考时暂停 / 只在显式推进时走时间 | — | **不提供**。它会删掉本基准要测的东西，参数直接报错 |

`slow` 是**备用开关**，只给评测方在事后做归因用：把同一局用更慢的时钟重跑一遍，量化「如果模型更快，分数会高多少」。它不进榜，也不得与 realtime 成绩混算平均值或排名；证据里的 `leaderboardEligible=false` 就是给统计脚本看的闸门。

它**故意不出现在 `help.md` / `START.md`**（面向 Agent 的手册）：那两份文档只说明「时钟固定 1x」，不提示任何可影响速度的开关。本文件是评测方与 harness 实现者的契约，所以写在这里。

`slow` 的运行规则：

- 采样器在**比赛运行中**把 `gameSpeed` 钉在倍率上；暂停（`gameSpeed == 0`）与联机对局不受影响。
- 因此 `slow` 下 `rwx-game-speed` 不能把速度调高——档位是本轮的校准，不是建议。
- 游戏时间照常推进，只是每游戏秒消耗更多墙钟；预算、胜负、指标口径全部不变。
- `wallLimitSeconds` 不随档位缩放：诊断轮次若用很小的倍率，墙钟预算可能先到，需要调用方自己放大。

---

## 7. 日志字段（`delivery.jsonl`）

目录：`-Drwx.trace.dir=<dir>`；微操 run 未显式指定时自动落到该 run 的目录。每行一条 JSON：

| `kind` | 字段 |
|---|---|
| `env` | `wall` `game` `speed` `diagnostic` `leaderboardEligible` `delivery` `tier` `tierSuggestions` `tierAgentSwitch` `pid` `map` |
| `deliver` | `channel`(`auto`) `session` `seq` `type` `events` `produced{wall,game}` `delivered{wall,game}` |
| `command` | `session` `command` `channel`(`auto`/`state`/`events`/`events-since`/`none`) `failed` `received{wall,game}` `applied{wall,game}` `appliedBound`(`reply`) |
| `warning` | `code`(`session-shared`) `session` `connection` `wall` |

三段延迟（**报告指标，不是评分**）：

| 段 | 计算 | 归属 |
|---|---|---|
| detect → deliver | `delivered.wall - produced.wall` | 环境管线 |
| deliver → decide | 下一次 `command.received.wall - delivered.wall` | harness + 模型（**近似**：可能不是同一件事） |
| decide → execute | `applied.wall - received.wall` | 传输 + 游戏线程交接 |

延迟本身就是被评测的东西之一：这三段不用于扣分，用于解释"同样的决策质量为什么分数不同"。

`applied` 取"回复就绪"时刻，是实际生效时刻的上界；`rwx-game-step` 这类自阻塞命令会高估，脚本默认排除。

离线统计：

```powershell
python evaluate/utils/delivery_latency.py <run>/delivery.jsonl
python evaluate/utils/delivery_latency.py <run>/delivery.jsonl --json --include-blocking
```

---

## 8. 开关汇总

| 开关 | 作用 | 默认 |
|---|---|---|
| `--evaluate <1\|2\|3>` / `--tier <n>` | 评估等级（权限 + 榜单分组） | `2` |
| `-Drwx.tier=<1\|2\|3>` | 同上（等价） | `2` |
| `--speed-profile=<realtime\|slow[:x]>` | 时钟（只有 realtime 进榜；slow 为备用诊断开关） | `realtime` |
| `--alert-delivery=<l0\|l1\|l1-lite>` | 附带等级 | `l1` |
| `-Drwx.alert.delivery=<l0\|l1\|l1-lite>` | 同上（等价） | `l1` |
| `-Drwx.alert.limit=N` | 单次附带条数上限 | `5` |
| `-Drwx.speed.profile=<…>` | 速度档（等价） | `realtime` |
| `-Drwx.trace.dir=<dir>` | 延迟证据目录 | 不写 |
| `--evaluate` | 引擎证据 + 命令录制 | 关 |

---

## 9. 复现

```powershell
# 标准评测：评估等级 2（默认）+ 情报等级 l1 + 1x，留下延迟证据
.\gradlew.bat :desktop:run '--args=--headless --evaluate -Drwx.trace.dir=agent-tmp/demo'

# 易档：评估等级 1，附带建议下一步，Agent 可自行改情报等级
.\gradlew.bat :desktop:run '--args=--headless --evaluate 1'

# 难档：评估等级 3（当前与 2 等价，仅标签不同）
.\gradlew.bat :desktop:run '--args=--headless --evaluate 3'

# 诊断轮次：同一局 0.25x 重跑，只为量化延迟的影响；证据里 leaderboardEligible=false，不进榜
.\gradlew.bat :desktop:run '--args=--headless --evaluate --speed-profile=slow:0.25'

# 微操 case + 指定评估等级
.\gradlew.bat :desktop:run '--args=--headless --evaluate CASE1-duel --tier 1 --case-args task=1_2_2,seed=1,track=pause'
```

开局、命令与端口发现见 [START.md](START.md)，命令参数见 [help_zh.md](help_zh.md)。
