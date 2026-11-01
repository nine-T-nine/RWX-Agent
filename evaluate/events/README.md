# 第二轮事件响应评估

本文件是第二轮判定的**唯一来源**：六类事件（A–F）的材料、判定条件、排除规则和参数都在这里。两轮目标、产物与完整运行顺序见[总 README](../README.md)，测试用途见[test/README.md](../test/README.md)。以下命令均从仓库根目录执行。

第二轮读取真实引擎证据，输出事件与经济时间序列。第一轮 `evaluate_stage_1.js` 和已有命令映射保持独立。

**仅有 `replay.py` 的命令 JSONL 无法完成第二轮。** 它没有完整的伤害、死亡、空闲段、工厂队列或历史资金；必须用同版本引擎实际重放一次，或用 `--evaluate` 采集的实时证据。报告不提供评分，不把战损直接解释为 Agent 迟钝。

## 使用

直接分析完整的实时 telemetry 只需 Node.js，并且必须同时给出本局的端口命令日志和引擎经济日志；缺任一个会直接报错，不产出报告：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/events-live/match-001-20261101-082618-team0/telemetry.jsonl' --commands 'evaluate/output/commands-你的对局.txt' --economy 'evaluate/output/events-live/match-001-20261101-082618-team0/economy.log'
```

输出默认写到 `evaluate/output/<对局名>/stage_2/`，`--out` 可覆盖；`--replay` 可在有第一轮唯一归属执行时补充生效证据。

下面是 Replay 补采示例，需先构建项目的 `:core:test :desktop:headlessArchive`；假设 Headless 发行包位于 `desktop/build/headless/`，Java 为 25。`--commands` 必填，脚本用它与补采出的 `economy.log` 一起驱动第二轮：

```powershell
python evaluate/utils/evaluate_replay.py 'replays/你的对局.replay' --team 0 --commands 'evaluate/output/commands-你的对局.txt' --java 'A:/javas/java25/bin/java.exe'
```

产物默认落在 `evaluate/output/<对局名>/`：`engine/match-…` 是原始证据，`engine/playback.log`、`engine/playback.json` 记录播放与输入校验，`replay/` 是自动解析的录像命令（省略 `--jsonl` 时生成，也可传入已有解析结果），`stage_2/` 是报告。`--dist` 可指定隔离构建的 `agent-tmp/priority-build/RWX_desktop/headless`。`--speed` 为原生 Replay 播放速度，默认 64；它改变回放耗时，采样仍使用模拟时钟。脚本不向 CLI 发送状态查询或游戏操作，Replay 结束后关闭独立进程，核对输入 SHA-256。

已有引擎证据可以直接重复分析（同样必须带 commands 和 economy）：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/telemetry.jsonl' --commands 'evaluate/output/commands-你的对局.txt' --economy 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/economy.log'
```

引擎证据按状态跃变采样，一局 14 分钟 2 人对局约 9–13 MB（旧版未精简的 14 分钟样本为 12.8 MB，其中 25% 是逐 tick 的 `frame` 行）。Claude 两局录像在玩家失去行动后仍由 AI 继续对局，对应的命令日志里用 `----以下是意外或者人为干涉情况----` 标出了边界；证据已按该边界截断，文件最后一行是 `reason=truncated_after_interference` 的结束记录。截断后仍可按上面的命令重新分析；删除证据后只能按重放命令重新采集。

实时对局启动时加 `--evaluate`，它设置默认证据目录 `evaluate/output/events-live` 并打开 CLI 与命令录制；要换目录或队伍可用 `-Drwx.evaluate.dir=…`、`-Drwx.evaluate.team=0` 覆盖。该开关不自动开启本地原生Replay，需从开局录像时使用 `rwx-battleroom-start --record`。默认不启用采集。每局自动分文件夹，同目录已有的局不覆盖；正常离开、退出和Replay结束会flush，实时证据关闭后运行同一Node入口。

每局的文件夹名形如 `match-<局内序号>-<yyyyMMdd-HHmmss>-team<队伍>`，例如 `match-001-20261101-082618-team0`，时间戳是本机墙钟，因此从目录名就能看出是哪一局。想给某一局起名字，启动时加 `-Drwx.evaluate.label=<名字>`（如 `-Drwx.evaluate.label=case4-run3`），名字会被清洗成路径安全字符并接在末尾。同一个 `telemetry.jsonl` 的 `meta` 行也记了 `startedAt`（带时区的 ISO 时间）与 `label`。

## 输出与证据

| 文件 | 内容 |
|---|---|
| `engine/match-…/telemetry.jsonl` | 有序引擎证据：游戏毫秒、tick、局内递增 seq、状态变化、实际伤害/死亡、**空闲段**、CLI 接收/展开/结果、原生命令前后变化 |
| `engine/match-…/economy.log` | 引擎直接采样的资金和 HUD 收入字段；每秒一项、60 项一行 |
| `engine/playback.log`、`engine/playback.json` | 仅补采方式：原生播放日志、输入 SHA-256 与校验告警数 |
| `replay/<录像名>.jsonl` | 补采方式自动解析的录像命令，也作为 `--replay` 证据 |
| `stage_2/events.jsonl` | 六类事件的结构化依据、响应命令、排除和不确定原因 |
| `stage_2/events.txt` | `HH:MM:SS.mmm` 事件时间线 |
| `stage_2/summary.json` | 事件总数、状态分布、严格数、响应时间、空闲段诊断、输入路径及限制 |
| `stage_2/economy.log` | 从证据重建的同格式经济序列 |

`telemetry.jsonl` 在每局结束时自动精简：连续的每 tick `frame` 行，只要 `tick` 和 `seq` 都恰好加 1、且游戏时钟按固定步长前进，就合并成一行
`{"kind":"frames",…}`。读取端按 `timeMs + i*stepMs`、`tick + i`、`seq + i` 展开，事件结果与未精简证据完全一致。需要原始逐 tick 行时用 `-Drwx.evaluate.trim=false` 启动。

结构化文件保留候选中的 `EXPLAINED`、`UNCERTAIN`，`strict` 才表示通过全部严格条件。`DETECTED`、`UNANSWERED` 是证据属性；没有预设任意"迟到"阈值，因此不自动输出 `LATE_RESPONSE` 或分数。

原版单位 ID 由 `NetworkEngine.nextUnitId` 单调生成，在一局内不复用；新地图、游戏时钟回退及换队会开启独立证据局。记录同时含 `life`。

## 采集端材料

第二轮的所有判定都只读这些记录。**没有一种判定需要新采集**，需要的是"采集端把判定要用的字段写清楚"。

| 记录 | 关键字段 | 服务于 |
|---|---|---|
| `unit` | `id/life/type/side/x/y/alive/complete/visible/canAttack/idle/**workIdle**/building/mobile/builder/factory/task/queue/capRemaining/actions/repairable` | A、D、E |
| `damage` | `victim`/`attacker` 的**战斗快照**：`id/life/type/side/x/y/alive/idle/building/canAttack/visible` | A |
| `death` | `unit`（完整快照）、`killer`、`nearby`（我方死亡的邻居 id/side/alive） | A、B |
| `idle` | `unitId/type/roles/fromMs/toMs/durationMs/closeReason/closeAction/startFunds/endFunds/startCap/startX/startY/actions/lastProduct` | D、E |
| `command` | `id/name/args/units/builders/refs/x/y/targetId`，`refs` 带收包时 `status/checkedMs/atReceipt/deathTimeMs/deathSeq` | A、C |
| `result` | `accepted/**code**/**creditsBefore**/**creditsAfter**/reply` | C、F |
| `economy` | `sampleMs/funds/income/**incomePerSecond**` | D、E、F |
| `eventstream` | `connected`/`clients`：Agent 何时连上/断开实时事件 Socket | B（知情判定） |
| `lifecycle` | `phase/unitId/relatedId` | D、E |
| `native` | 原生命令前后任务/队列变化 | 兼容旧判定 |

三条口径：

- **`workIdle` 与 `idle` 分开。** `idle` 是"没有任务、没有队列、没有攻击目标、最近 1 秒没开火"；`workIdle` 与它相同，但**指令中心**在开火防守时仍算 `workIdle`（指挥中心一边防守一边生产是正常状态）。D/E 用 `workIdle`，A 仍用 `idle`。
- **`damage` 只写战斗快照。** 一次伤害曾经写两个完整快照（含动作菜单、队列、单位上限），实测 775 B/行、占日志 18.7%；A 只读 11 个字段，瘦身后约 350 B/行。**行数一条不减**，所以判定语义完全不变。
- **`idle` 由采集端维护。** 每个资产最多一个未闭合的 pair；闭合原因是枚举（`BUILD/REPAIR/MOVE/ATTACK_MOVE/GUARD_AT/PATROL/ATTACK/PRODUCE/AUTO_ENGAGE/DEATH/EVIDENCE_END`）。`<3s` 的段在采集端丢弃（`-Drwx.evaluate.idleMinMs` 可调），其余全部保留：**离线层可以再筛，但采集端丢掉就不可逆**。

## 六类事件

### A 偷袭（`AMBUSH`）

敌人实际伤害我方 **idle** 单位或建筑；至少一条相同攻击关系的实际记录跨满 5s，连续记录间隔不超过 3s。单位周围半径 X 的圆按相交传递合并。首次相关主动 B 后再收集 3s 命令。

| 字段 | 含义 |
|---|---|
| `kind` | `UNITS` = 打单位；`BASE` = **偷家**（参战受害单位全是无武器建筑） |
| `agentState` | 事件窗口内 Agent 在做什么，见下 |
| `self`/`enemy` | 参战构成 |
| `lossesSelf`/`lossesEnemy` | 己方战损与击杀分开；`losses` 仍是二者合计 |

- **偷家不再被排除。** 旧规则把"无武器普通建筑"整条丢掉（实测一局丢掉 171 次伤害），现在同样成为候选，只是标 `kind=BASE`，便于按权重区分。
- 自动反击不是主动响应；攻击者受伤立即失活，受害者非 idle 达 3s 后失活。
- 仅命中/伤害证据，未命中和免伤射击不算。
- `observability` 只说明"游戏层可见或有受伤提示"，不证明 Agent 已查询或理解。

### B 绞肉机（`GRINDER`）

**目标**：识别 Agent **把我方刚刚死过人的地方当成安全区**，在没有重新获取战报的情况下把部队派去集合、或把建造者派去施工，结果在那里挨打。人眼在回放里一眼就能看出这种延迟。

旧 `FEED` 不能承担这个语义：它要求"半径 X 内没有其他友军"（孤立损失），而实测 101 次己方损失里 84 次附近有友军；它还排除 `attackMove` 损失，而那是全部己方损失的 46%。一局 14 分钟 101 次损失，`FEED` 输出 0 条。

判定分四步，缺一条不出事件：

| 步 | 条件 | 数据 |
|---|---|---|
| 1 危险点 | 我方（`side=self`）死亡位置；取该位置在 `T_GRINDER_RECENT`（**40s**）内的**最近一次**死亡时刻 `t_loss`。友军/盟友死亡不算 | `death` |
| 2 投入 | `t_order > t_loss` 的 B 命令，目标点落在 `t_loss` 半径 X 内：`order-move` / `order-build` / `order-repair` / `order-rally`。**`attack-move` 与 `guard-at` 不算**——那是主动进攻或驻守 | `command` |
| 3 后果 | 被点名的单位里至少一个在**到达危险点之后 `T_GRINDER_AFTER`（10s）内**、在半径 X 内受伤或死亡；且**挨打时**它的任务不是 `attackMove`/`guard`/`guardAt`/`patrol`/`attack`（有攻击能力的单位才检查；建造者、运输等非攻击单位不受此限）。整段等待还有 `T_GRINDER_HARD_CAP`（120s，从下令起算）的硬上限。没有后果不出事件 | `damage`、`death`、`unit` |
| 4 知情 | `(t_loss, t_order)` 之间**没有任何**一次信息获取能覆盖 `t_loss`：<br>· `rwx-game-events` 窗口 `[t_query − --seconds, t_query]`（默认 15s）覆盖 `t_loss`，或带 `--since`<br>· `rwx-game-state` 的 Recent Events 窗口（`--event-seconds` 默认 10s，`--no-events` 不算）<br>· `threat-list` 的圆覆盖危险点，或 `unit-list --owner enemy`<br>· 实时事件 Socket 在 `(t_loss, t_order)` 内处于连接状态 | `command`、`eventstream` |

**"到达"怎么算**：单位第一次被观察到进入危险点半径 X 的时刻。下令时它已经站在那里（最近 5 秒内的位置记录在半径内）就从 `t_order` 起算；否则从观察到的第一次进入起算。战斗单位行军途中不是每 tick 采样，若始终没观察到它进入，就退回"从下令起算 + 硬上限"这一档，并在事件里标 `arrivalObserved:false`——这样长距离行军不会被 10 秒窗口误杀，但"到了以后待很久才被打"这种情况只有可观测到的单位才排得掉。

| 字段 | 含义 |
|---|---|
| `t_start` / `t_end` | 损失时刻 → 后果时刻 |
| `delayMs` | `t_order − t_loss`，即"多久之后又把东西送回去" |
| `operation` | `move` / `build` / `repair` / `rally` |
| `consequence` | `{timeMs, unitId, type, alive, taskType, arrivalMs, arrivalObserved}`：挨打的那个单位，以及它的到达时刻（未观测到时为 null） |
| `awareness` | `{aware, by}`：`events_window` / `events_since` / `state_events` / `threat_list` / `enemy_list` / `event_stream` / `null` |
| `agentState` | 窗口内 Agent 在做什么 |

status：命令覆盖不全 → `UNCERTAIN`；知情 → `EXPLAINED`（知道还去，是战术选择）；否则 `DETECTED`，`scoreable:true`。

注意两点口径：

- **"已经有友军"不算危险点。** 只认我方死亡；否则"派兵支援正在交战的友军"会被判成错误，而那是正常战术。
- **`order-rally` 无法点名被派去的单位**（命令里是工厂），所以它的后果用"任何我方单位在危险点伤亡"判定，`unitsResolved:false`，并在报告里标出。

### C 抖动过期指令（`STALE_BURST`）

**目标**：Agent 的世界模型过期或命令互相冲突，导致连续被拒/失败。

判定：把**同一条命令**的两类证据合并成一行——引擎明确拒绝（`result.accepted=false`）与收包时对象已死/已失去控制（`command.refs`）。按时间排序后：

1. 相邻两次间隔 ≤ `T_STALE_BURST_WINDOW`（10s）的算同一个**突发**；
2. 突发内相邻间隔 ≤ `T_STALE_BATCH`（250ms）的算**同一批**，因为同时发出的指令会互相取消、各自被拒；
3. 批数 ≥ `T_STALE_BURST_MIN`（3）才出事件。

| 字段 | 含义 |
|---|---|
| `attempts` | 批数（判定用的量） |
| `count` | 原始被拒/失效条数 |
| `codes` | 按结构化原因码计数 |
| `commands` | 每条 `commandId/timeMs/name/code/source` |

- **原因码来自引擎**：`result.code` 在异常还有类型时赋值（`INSUFFICIENT_CREDITS` 是真实类型，其余按最具体优先的规则表）。回复文本原样保留，但**判定不再依赖文案**；没有 `code` 的旧证据才回退到文案分类。
- 只有包含"世界不是我以为的那样"（`STALE_OBJECT`/`NOT_CONTROLLABLE`/`PLACEMENT_BLOCKED`/`UNAVAILABLE_ACTION`）的突发才 `scoreable`；纯参数拼写错误只进复核。
- 单条拒绝不扣分：可能是探测，也可能是同一批的自伤。

### D 空闲建造者（`BUILDER_IDLE`）/ E 空闲工厂（`FACTORY_IDLE`）

一个采集端 `idle` 段 → 一条事件。角色由**动作菜单**推导（有 `build` 动作 = builder，有 `produce` 动作 = factory），不用名字清单；航母、鹦鹉螺、飞行堡垒这类混合单位只出一条，`roles` 里两个都记。

| 字段 | 含义 |
|---|---|
| `tier` | `SHORT`（<10s）/ `LONG`（10–60s）/ `STALL`（≥60s） |
| `durationMs`、`closeReason`、`closeAction` | 段长与结束原因 |
| `referenceAction` | 参照动作：该资产上次实际产出的型号（采集端 `lastProduct`），没有则最便宜的可用项 |
| `affordableStart`/`affordableEnd` | 段首/段尾是否买得起参照动作 |
| `agentState` | 窗口内 Agent 在做什么 |
| `keepRule` | 该资产是否有生效中的 `keep-set` 补产规则 |

判定顺序（任一条命中即定级，后面的不再看）：

| 序 | 条件 | 结果 |
|---|---|---|
| 1 | 命令覆盖不全 | `UNCERTAIN` |
| 2 | 没有可用参照动作 | `UNCERTAIN` |
| 3 | 有生效的 `keep-set` 规则 | `EXPLAINED`（引擎自动补产，空闲只能是买不起或动作不可用） |
| 4 | 段首和段尾都买不起 | `EXPLAINED` |
| 5 | 其余 | `DETECTED`；`reason` 区分 `agent_silent` / `agent_busy_elsewhere` |

- **`agentState`**：`SILENT` = 窗口内没有任何 Agent 命令（模型或链路停摆）；`OBSERVING` = 只有查询；`BUSY_ELSEWHERE` = 在给别的东西下令。它不改变判定，只解释判定。
- `scoreable`：`DETECTED` 且 `tier != SHORT` 且是工厂。**建造者默认不可扣分**，因为"能不能放下"无法从证据证明；把 `idleScoreBuilders` 设为 true 才纳入。
- `durationIsLowerBound`：段在证据结束处截断时为 true。

### F 资金空余（只出材料，不出事件）

不判定、不计分，只产出一张表：

```sh
node evaluate/score_stage_2.js "evaluate/output/<对局名>/stage_2"
# → stage_2/economy_seconds.csv: second,funds,incomeField,fundsDelta
```

**`incomeField` 不是每秒。** 它是"每 `resourceIncomeRatePeriod`（40 tick）"的额度；实测资金曲线是它的约 1.5625 倍（1x 下时钟每 tick 走 16ms）。遥测的 `economy` 行另有 `incomePerSecond`（采集端按实际时钟步长换算），`result` 行有 `creditsBefore/creditsAfter`，两者合起来可以把"收入"和"花费"分开。CLI 的 `Credits: … (+N/s)` 已改为真实每秒值。

## 参数与验证

参数在 `events/config.js` 集中：`X`=运行时中位数（通常160）、偷袭5s、失活3s、响应尾窗3s、突发窗口10s、批次250ms、突发阈值3批、空闲档位10s/60s、危险区记忆40s、到达后窗口10s、硬上限120s、经济1s。Node 的 `--config` 接收 JSON 覆盖。

`legacyDetectors` 默认为 **false**：旧的 3–10s `BUILDER_GAP`/`PRODUCTION_GAP`、`FEED`、`STALE_COMMAND`、`COMMAND_FAILURE` 已被 D/E、B（待实现）、C 取代。需要复现旧报告时用 `--config '{"legacyDetectors":true}'`。

```powershell
node --test evaluate/test/test-evaluate.js evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js evaluate/test/test-score.js
python -W error::ResourceWarning evaluate/test/test_replay.py
```

引擎测试为 `core/src/test/kotlin/evaluate/utils/EngineEvidenceTest.kt`：暂停重复帧、整分钟/最后一行、时间跳跃不造数据、收包对象状态（含 `--builder`）、跨线程关联、死亡去重、连续多局；`EngineEvidenceTrimTest.kt` 覆盖 frames 行的合并、变速/跳号分段与单帧原样保留；`RwxEventsTest.kt` 覆盖 `new`/`info` 的发现确认、区域聚类与生产聚合。

## 来源、顺序和不确定性

`RwxCliTool` 在执行前冻结接收记录，原始参数不丢弃；上下文经既有模拟线程派发传递。MANUAL 的原生 Command 带局内关联，KEEP/PENDING 与引擎/UI/Replay 未知来源分别标识。

原生 Command 前后任务/队列确实改变才记 `applied=true`；重复设置相同状态等无法通过快照证明的情况为 null，不能伪造"成功"。

旧日志保留第一轮解析的人工接管截止位置；日志覆盖外不确认全局无 B。旧整秒时间保留 1s 不确定区间，空窗检查保守地考虑可能落入区间的 B。

事件 JSONL 分两遍流式读取：先索引较少的 CLI/原生命令，后执行检测。采集器复用实体更新与伤害/死亡通知，并按"状态跃变才写"采样：我方建造者与所有建筑每 tick 比对状态，受击单位随后 1 秒内每 tick 观察，位置变化最多每秒记一次；危险区用引擎空间索引，只记成员身份且仅成员变化时写一行。没有每秒 CLI 查询，也没有评测器发出的游戏操作。

`summary.json` 的 `diagnostics` 记录每种输入记录数、各检测器的候选数与淘汰原因，用来区分"严格过滤后确实没有事件"和"证据没有被采集或没有被读到"。

## 面向 Agent 的事件流（与第二轮判定无关）

`rwx-game-events` 的 `new` 与 `info` 是 Agent 的情报通道，不是第二轮判定材料，但改动会影响 Agent 的表现：

- **`new`**：敌方单位必须连续可观察满 1 秒才被记录，且同一 ID 一局只报一次（进出迷雾不重复上报）。渲染时按 5 秒窗口 + **距离 ≤160** 聚成一个区域，输出构成与平均位置 `around (x,y)`；不同区域不会混进同一条。
- **`info`**：`produced` 的产物放在事件的 `attacker` 字段，渲染时按 5 秒窗口聚合成一条：`produced Tank x2 (#99 #100) by #15 #87`。
- 聚合发生在**渲染层**而不是采集层，所以 `--since <seq>` 的游标语义不变，Agent 也不会因为缓冲多等 5 秒。
