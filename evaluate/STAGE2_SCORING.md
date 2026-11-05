# Stage2 完整对局事件评估与评分（`stage2-v2`）

本文件是**第二轮完整对局评估的唯一细则来源**：证据采集、六类事件（A–F）的判定条件、排除规则、参数与评分公式，
以及**哪些扣分项在人类对局里算不出来**。管理员启动与开局概览见[评测管理员指南](README.md)，
测试用途见[test/README.md](test/README.md)。以下命令均从仓库根目录执行。

第二轮读取**真实引擎证据**，输出事件与经济时间序列；它与第一轮 `evaluate_stage_1.js` 的映射完全独立。
**战损不会被直接解释为 Agent 迟钝**——先判定事件，再单独计分。

> 版本前提：`stage2-v2` 才可互相比较；旧事件名仍能映射到新评分族，但不再沿用旧的死亡计数/按分钟扣分。
> 第二轮不并入 Stage1 的分数；合成见 [`score_match.js`](README.md#对局结束后的评分流程)：`stage1*0.4 + stage2*0.6`，只接受当前版本。

**读法**：§1–§2 讲"证据从哪来"，§3 讲"事件怎么判定"，§4–§5 讲"分怎么算"，§6 讲"人类对局算不算"，
§7 是验证与不确定性，§8 附录。

---

## 1. 使用

### 1.1 实时采集（有 `--evaluate` 的对局）

只分析完整实时 telemetry 时只需 Node.js，但**必须同时给出本局的端口命令日志和引擎经济日志**，
缺任一个直接报错、不产出报告：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/<对局名>/events-live/<对局名>/telemetry.jsonl' --commands 'evaluate/output/<对局名>/commands-<对局名>.txt' --economy 'evaluate/output/<对局名>/events-live/<对局名>/economy.log'
```

输出默认写 `evaluate/output/<对局名>/stage_2/`，`--out` 可覆盖；`--replay` 可在有第一轮唯一归属执行时补充生效证据。

### 1.2 Replay 补采（只有录像的对局）

**仅有 `replay.py` 的命令 JSONL 无法完成第二轮**：它没有完整的伤害、死亡、空闲段、工厂队列或历史资金，
必须用同版本引擎实际重放一次。需先构建 `:core:test :desktop:headlessArchive`（Java 25，发行包默认
`desktop/build/headless/`）：

```powershell
python evaluate/utils/evaluate_replay.py 'replays/<对局名>.replay' --team 0 --commands 'evaluate/output/<对局名>/commands-<对局名>.txt' --java 'A:/javas/java25/bin/java.exe'
```

产物默认落在 `evaluate/output/<对局名>/`：`engine/match-…` 是原始证据，`engine/playback.log`、
`engine/playback.json` 记录播放与输入校验，`replay/` 是自动解析的录像命令，`stage_2/` 是报告。
`--commands` **必填**（脚本用它驱动第二轮）；`--dist` 可指定隔离构建，`--speed` 是原生 Replay 播放速度
（默认 64，只改变回放耗时，采样仍用模拟时钟）。脚本不向 CLI 发送状态查询或游戏操作，Replay 结束后关闭独立进程，
核对输入 SHA-256。

已有引擎证据可以直接重复分析（同样必须带 commands 和 economy）：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/telemetry.jsonl' --commands 'evaluate/output/<对局名>/commands-<对局名>.txt' --economy 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/economy.log'
```

### 1.3 采集开关

实时对局启动时加 `--evaluate`，它设置默认证据目录 `evaluate/output/events-live` 并打开 CLI 与命令录制；
要换目录或队伍用 `-Drwx.evaluate.dir=…`、`-Drwx.evaluate.team=0`。该开关**不自动开启**本地原生 Replay，
需从开局录像时使用 `rwx-battleroom-start --record`。默认不启用采集。

每局自动分文件夹 `match-<局内序号>-<yyyyMMdd-HHmmss>-team<队伍>[-<标签>]`，同目录已有的局不覆盖；
正常离开、退出和 Replay 结束会 flush。评测结束后由 [`tools/match_materials.js`](tools/README.md)
把采集目录搬进 `evaluate/output/<对局名>/events-live/<对局名>/`，`events-live/` 下不再留副本。
标签默认取当时正在录制的回放名（去掉 `[local]`、`[v1.15]` 与时间括号后清洗成路径安全字符），
也可加 `-Drwx.evaluate.label=<名字>`；没录像又没标签时退回纯序号名。`telemetry.jsonl` 的 `meta` 行同时记
`startedAt`（带时区的 ISO）、`label`、`replay`、`gameVersion`。

---

## 2. 输出与证据

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

**`telemetry.jsonl` 在每局结束时自动精简**：连续的每 tick `frame` 行，只要 `tick` 和 `seq` 都恰好加 1、
且游戏时钟按固定步长前进，就合并成一行 `{"kind":"frames",…}`。读取端按 `timeMs + i*stepMs`、`tick + i`、
`seq + i` 展开，事件结果与未精简证据完全一致。需要原始逐 tick 行时用 `-Drwx.evaluate.trim=false`。

其他约定：

- 结构化文件保留候选中的 `EXPLAINED`、`UNCERTAIN`，**只有 `strict` 才表示通过全部严格条件**。
- `DETECTED`、`UNANSWERED` 是证据属性；没有预设任意"迟到"阈值，因此不自动输出 `LATE_RESPONSE` 或分数。
- 一次 14 分钟 2 人对局约 9–13 MB。Claude 两局在玩家失去行动后仍由 AI 继续，命令日志里用
  `----以下是意外或者人为干涉情况----` 标出边界，证据已按该边界截断，最后一行是
  `reason=truncated_after_interference`；截断后仍可重新分析，但删除证据就只能重采。
- 原版单位 ID 由 `NetworkEngine.nextUnitId` 单调生成、一局内不复用；新地图、游戏时钟回退及换队会开启独立证据局。

### 2.1 采集端材料

第二轮的所有判定只读这些记录。**v2 评分新增实际 HP 损失和连续可行性字段**；旧证据缺这些字段时保留事件，
但**不能推算相应扣分**，需要重新采集。

| 记录 | 关键字段 | 服务于 |
|---|---|---|
| `unit` | `id/life/type/side/x/y/alive/complete/visible/canAttack/idle/**workIdle**/building/mobile/builder/factory/task/queue/capRemaining/actions/repairable` | A、D、E |
| `damage` | `victim`/`attacker` 的**战斗快照**：`id/life/type/side/x/y/alive/idle/building/canAttack/visible/hp/maxHp`，行上另有 `hpLoss` | A |
| `death` | `unit`（完整快照）、`killer`、`nearby`（我方死亡的邻居 id/side/alive） | A、B |
| `idle` | `unitId/type/roles/fromMs/toMs/durationMs/closeReason/closeAction/startFunds/endFunds/startCap/startX/startY/actions/lastProduct/closeQueue/closeTask/feasibility` | D、E |
| `command` | `id/name/args/units/builders/refs/x/y/targetId`，`refs` 带收包时 `status/checkedMs/atReceipt/deathTimeMs/deathSeq` | A、C |
| `result` | `accepted/**code**/**creditsBefore**/**creditsAfter**/reply` | C、F |
| `economy` | `sampleMs/funds/income/**incomePerSecond**` | D、E、F |
| `eventstream` | `connected`/`clients`：Agent 何时连上/断开实时事件 Socket | B（知情判定） |
| `lifecycle` | `phase/unitId/relatedId` | D、E |
| `native` | 原生命令前后任务/队列变化 | 兼容旧判定、D/E 的排除 |

### 2.2 三条口径

- **`workIdle` 与 `idle` 分开。** `idle` 是"没有任务、没有队列、没有攻击目标、最近 1 秒没开火"；`workIdle` 相同，
  但**指令中心**在开火防守时仍算 `workIdle`（指挥中心一边防守一边生产是正常状态）。**D/E 用 `workIdle`，A 仍用 `idle`。**
- **`damage` 只写战斗快照。** 曾写两个完整快照（含动作菜单、队列、单位上限），实测 775 B/行、占日志 18.7%；
  A 只需精简字段与实际 HP 损失，瘦身后约 350 B/行。**行数一条不减**，判定语义不变。
- **`idle` 由采集端维护。** 每个资产最多一个未闭合的 pair；闭合原因是枚举
  （`BUILD/REPAIR/MOVE/ATTACK_MOVE/GUARD_AT/PATROL/ATTACK/PRODUCE/AUTO_ENGAGE/DEATH/EVIDENCE_END`）。
  `<3s` 的段在采集端丢弃（`-Drwx.evaluate.idleMinMs` 可调），其余全部保留：**离线层可以再筛，采集端丢掉就不可逆**。

---

## 3. 六类事件

### 3.1 A 偷袭（`AMBUSH`）

敌人实际伤害我方 **idle** 单位或建筑；至少一条相同攻击关系的实际记录跨满 5s，连续记录间隔不超过 3s。
单位周围半径 X 的圆按相交传递合并。首次相关主动 B 后再收集 3s 命令供旧报告；评分另外保留区间内全部相关主动响应，
并以实际生效时刻计算。

| 字段 | 含义 |
|---|---|
| `kind` | `UNITS` = 打单位；`BASE` = **偷家**（参战受害单位全是无武器建筑） |
| `agentState` | 事件窗口内 Agent 在做什么 |
| `self`/`enemy` | 参战构成 |
| `lossesSelf`/`lossesEnemy` | 己方战损与击杀分开；`losses` 仍是二者合计 |

- **偷家不再被排除。** 旧规则把"无武器普通建筑"整条丢掉（实测一局丢掉 171 次伤害），现在同样成为候选，
  只标 `kind=BASE` 便于按权重区分。
- 自动反击不是主动响应；攻击者受伤立即失活，受害者非 idle 达 3s 后失活。
- 仅命中/伤害证据，未命中和免伤射击不算。
- `observability` 只说明"游戏层可见或有受伤提示"，不证明 Agent 已查询或理解。

### 3.2 B 绞肉机（`GRINDER`）

**目标**：识别 Agent **把我方刚刚死过人的地方当成安全区**，在没有重新获取战报的情况下把部队派去集合、
或把建造者派去施工，结果在那里挨打。

> 旧 `FEED` 承担不了这个语义：它要求"半径 X 内没有其他友军"（孤立损失），而实测 101 次己方损失里 84 次附近有友军；
> 它还排除 `attackMove` 损失，而那是全部己方损失的 46%。一局 14 分钟 101 次损失，`FEED` 输出 0 条。

判定分四步，缺一条不出事件：

| 步 | 条件 | 数据 |
|---|---|---|
| 1 危险点 | 我方（`side=self`）死亡位置；取该位置在 `T_GRINDER_RECENT`（**40s**）内的**最近一次**死亡时刻 `t_loss`。友军/盟友死亡不算 | `death` |
| 2 投入 | `t_order > t_loss` 的 B 命令，目标点落在 `t_loss` 半径 X 内：`order-move` / `order-build` / `order-repair` / `order-rally`。**`attack-move` 与 `guard-at` 不算**——那是主动进攻或驻守 | `command` |
| 3 后果 | 被点名的单位里至少一个在**到达危险点之后 `T_GRINDER_AFTER`（10s）内**、在半径 X 内受伤或死亡；且**挨打时**它的任务不是 `attackMove`/`guard`/`guardAt`/`patrol`/`attack`（有攻击能力的单位才检查；建造者、运输等非攻击单位不受此限）。整段等待还有 `T_GRINDER_HARD_CAP`（120s，从下令起算）的硬上限。没有后果不出事件 | `damage`、`death`、`unit` |
| 4 知情 | `(t_loss, t_order)` 之间**没有任何**一次信息获取能覆盖 `t_loss`：`rwx-game-events` 窗口 `[t_query − --seconds, t_query]`（默认 15s）覆盖 `t_loss`，或带 `--since`；`rwx-game-state` 的 Recent Events 窗口（`--event-seconds` 默认 10s，`--no-events` 不算）；`threat-list` 的圆覆盖危险点，或 `unit-list --owner enemy`；实时事件 Socket 在 `(t_loss, t_order)` 内处于连接状态 | `command`、`eventstream` |

**"到达"怎么算**：单位第一次被观察到进入危险点半径 X 的时刻。下令时它已经站在那里（最近 5 秒内的位置记录在半径内）
就从 `t_order` 起算；否则从观察到的第一次进入起算。战斗单位行军途中不是每 tick 采样，若始终没观察到它进入，
就退回"从下令起算 + 硬上限"这一档，并在事件里标 `arrivalObserved:false`——这样长距离行军不会被 10 秒窗口误杀，
但"到了以后待很久才被打"只有可观测到的单位才排得掉。

| 字段 | 含义 |
|---|---|
| `t_start` / `t_end` | 危险点损失时刻 → 后果后连续 3 秒无新增伤害、硬上限或证据结束 |
| `delayMs` | `t_order − t_loss`，即"多久之后又把东西送回去" |
| `operation` | `move` / `build` / `repair` / `rally` |
| `consequence` | `{timeMs, unitId, type, alive, taskType, arrivalMs, arrivalObserved}` |
| `awareness` | `{aware, by}`：`events_window` / `events_since` / `state_events` / `threat_list` / `enemy_list` / `event_stream` / `null` |
| `agentState` | 窗口内 Agent 在做什么 |

status：命令覆盖不全 → `UNCERTAIN`；知情 → `EXPLAINED`（知道还去，是战术选择）；否则 `DETECTED`，`scoreable:true`。

两点口径：

- **"已经有友军"不算危险点。** 只认我方死亡；否则"派兵支援正在交战的友军"会被判成错误。
- **`order-rally` 无法点名被派去的单位**（命令里是工厂），所以后果用"任何我方单位在危险点伤亡"判定，
  `unitsResolved:false`，报告中标出。

### 3.3 C 抖动过期指令（`STALE_BURST`）

**目标**：Agent 的世界模型过期或命令互相冲突，导致连续被拒/失败。

把**同一条命令**的两类证据合并成一行——引擎明确拒绝（`result.accepted=false`）与收包时对象已死/已失去控制
（`command.refs`）。按时间排序后：

1. 相邻两次间隔 ≤ `T_STALE_BURST_WINDOW`（10s）的算同一个**突发**；
2. 突发内相邻间隔 ≤ `T_STALE_BATCH`（250ms）的算**同一批**（同时发出的指令会互相取消、各自被拒）；
3. 批数 ≥ `T_STALE_BURST_MIN`（3）才出事件。

| 字段 | 含义 |
|---|---|
| `attempts` | 批数（判定用的量） |
| `count` | 原始被拒/失效条数 |
| `codes` | 按结构化原因码计数 |
| `commands` | 每条 `commandId/timeMs/name/code/source/kind/staleAgeMs/staleInfo` |

- **原因码来自引擎**：`result.code` 在异常还有类型时赋值（`INSUFFICIENT_CREDITS` 是真实类型，其余按最具体优先的规则表）。
  回复文本原样保留，但**判定不再依赖文案**；没有 `code` 的旧证据才回退到文案分类。
- 只有包含"世界不是我以为的那样"（`STALE_OBJECT`/`NOT_CONTROLLABLE`/`PLACEMENT_BLOCKED`/`UNAVAILABLE_ACTION`）
  的突发才 `scoreable`；纯参数拼写错误只进复核。
- 未达突发阈值的单条拒绝仍不出事件。突发内逐指令评分，无首条宽限；只有已证明失效时间的指令可计算过期严重程度。

### 3.4 D 空闲建造者（`BUILDER_IDLE`）/ E 空闲工厂（`FACTORY_IDLE`）

一个采集端 `idle` 段对应一条事件。动作菜单决定 `roles`；兼有生产/建造能力的资产按工厂只出一条。
判据是"该资产确实闲了"这个引擎侧事实；`feasibility` 只用来**排除**（证明当时确实做不了），不用来放行。
只有证明做不了的 `EXPLAINED` 与 `EXCLUDED` 不计分：后者指 Agent 的原生命令碰过该资产（建造者/工厂通用），
或对工厂而言"别的工厂在排产"（见下）。

| 证据 | 用法 |
|---|---|
| `closeQueue` / `closeTask` | B 实际入队的型号和数量，或建造类型/坐标/数量、维修目标 |
| `feasibility.actions` | 每 tick 都可用且可支付的动作交集；值为区间内最大单价 |
| `feasibility.minFunds/minCap` | 区间内资金/单位剩余额度最低值；除每 tick 外，也在原生命令改动共享预算前采样 |
| `feasibility.repairable` | 整段都存活、受损、可由该建造者维修的同一目标交集 |
| `feasibility.complete/fromMs/toMs` | 每个 tick 覆盖完整且时间端点匹配；缺帧不可通过 |
| `feasibility.placements` | 建造落点的全区间证明；`valid:false` 证明当时确实建不了 → `EXPLAINED`。缺失只改 `reason`，不再挡计分 |

**预筛选**：工厂必须以真实生产关闭区间；B 的整批生产总成本不超过 `minFunds`，数量不超过 `minCap`，
所有型号均在连续动作交集中。同一请求拆成多个原生包时用索引汇总的整批数量；多工厂共享预算无法全程重建时为
`UNCERTAIN`。维修必须保证同一目标全程可维修。建造同时要求动作、整批资金与落点全程合法。
明确中途不可做 → `EXPLAINED`（不计分）；证据不足 → `UNCERTAIN`（**仍按同一曲线计分**）。
已证明由自动化/待发队列下发或在区间前安排的工作为 `EXPLAINED`。

**先于预筛选的排除规则**：`[A,B)` 内出现过任何**原生命令**涉及该资产 id，这一段直接 `EXCLUDED`
（`reason=native_order_during_gap`），不进预筛选也不扣分。对建造者，另外只要**高层行动指令自己明确点名**该 id，
这一段就直接 `EXCLUDED`（`reason=high_level_order_during_gap`），不再要求等到底层执行行或应用结果——
请求成功还是被拒都一样：点了名就说明 Agent 在这段时间里考虑过这个建造者。
高层判据只读已解析的 `units`/`builders` id，`--units all-army` 之类的未解析选择器不证明这一个建造者被点名，
回放推导出来的执行单位也不会反向扩展成高层目标。计入的来源是 `agent`/`pending`/`keep`/`auto`：
keep-set 补产与 `AiAutomate` 都是 Agent 自己的配置在替它执行，算它碰过；`engine`（自动维修及一切引擎自发命令）
与 `replay_unknown`（回放期间写出的命令）不算。被拒请求不产生原生命令行，天然不计入。
窗口是半开的 `[fromMs,toMs)`：关闭该区间的命令自己的原生命令行落在 `toMs`，不会把它结束的这段区间排除掉。
排除事件仍保留 `durationMs`、`nativeTouched` 与 `highLevelTouched`，供审计。

**工厂空闲的额外排除（只对 E）**：`[fromMs,toMs)` 内只要有任意一条**真实工厂**的原生命令（来源同样只认
`agent`/`pending`/`keep`/`auto`，不必是这条工厂自己），这段即 `EXCLUDED`，`reason=factory_production_elsewhere`，
命中明细写进 `factoryProduction`。真实工厂指 `factory=true` 的**建筑**；**指挥中心的 id 不参与匹配**——
它只负责补产建造者，算进来会让整局的工厂空闲全部免责。类工厂单位（鹦鹉螺号、航空母舰、飞行堡垒这类
`factory=true` 且可移动的单位）的空闲段用更宽的集合：真实工厂 id 或类工厂 id 任一命中都排除。
建造者空闲的高层补充判据只针对建造者；工厂仍只按原生命令生产活动判断。

**其余边界**：

- 采集器维护交集与最小值，即使单位任务未变化也每 tick 检查，不额外逐 tick 写完整动作菜单。
- 当前采集器**尚不生成任意建造落点的全区间证明**；建造落点仅在 B 合法不能替代历史证明，因此这类建造空闲记为
  `continuous_placement_unavailable` 的 `UNCERTAIN`，**照常按 D 曲线计分**——只有 `valid:false` 的落点证明才会降为 `EXPLAINED`。
- 因死亡、移动或其他非 BUILD/REPAIR 任务关闭的建造者区间，没有可核验的建造端点，直接 `EXCLUDED`
  （`reason=no_build_or_repair_endpoint`）；证据结束时仍未结束的空闲段也直接 `EXCLUDED`
  （`reason=evidence_ended_with_idle`），不把残局/证据尾部当成 Agent 闲置扣分。
- 命令日志是完整的 Agent 指令源：首条/末条命令的时间戳只是日志中实际有命令的范围，不是命令证据覆盖边界。
  因而区间内没有命令就表示该区间没有命令，不再生成 `CLI_command_coverage_unavailable` 或
  `endpoint_outside_command_coverage`。
- `DETECTED` 与 `UNCERTAIN` 的工厂/建造者都计分，`idleScoreBuilders` 默认 true，可显式关闭。
- `tier`（<10s / 10–60s / ≥60s）只作描述，不再阻止短建造者区间按曲线计分。
- `agentState` 的 `SILENT/OBSERVING/BUSY_ELSEWHERE` 只说明当时在做什么，不改变判定。

### 3.5 F 资金空余

由 `score_stage_2.js` 读取 `economy.log` 分段评分，区间写入 `score.json.financialEvents` 和 `timeline.csv`，
原始逐秒数据仍写 `economy_seconds.csv`。

`incomeField` 是每 40 tick 的收入额；默认按 `1/(40×0.016)=1.5625` 换算每游戏秒。不同采样时钟须覆盖
`funds.incomeFieldPerSecond`，不能直接把 HUD 字段当秒收入。内存 API 可给每秒样本提供 `incomePerSecond`，优先使用。

---

## 4. 参数

参数在 `events/config.js` 集中：`X`=运行时中位数（通常 160）、偷袭 5s、失活 3s、响应尾窗 3s、突发窗口 10s、
批次 250ms、突发阈值 3 批、空闲档位 10s/60s、危险区记忆 40s、到达后窗口 10s、硬上限 120s、经济 1s。
Node 的 `--config` 接收 JSON 覆盖。

`legacyDetectors` 默认为 **false**：旧的 3–10s `BUILDER_GAP`/`PRODUCTION_GAP`、`FEED`、`STALE_COMMAND`、
`COMMAND_FAILURE` 已被 D/E、B、C 取代。复现旧报告时用 `--config '{"legacyDetectors":true}'`。

---

## 5. 评分

事件检测与评分后处理分开：

```powershell
node evaluate/score_stage_2.js "evaluate/output/<对局名>/stage_2"
```

输出 `score.json/score.txt/timeline.csv/economy_seconds.csv`；`--weights <json文件>` 覆盖配置，`--out <目录>`
指定输出，时间线复用同一次计分结果。总分 `max(0, 100 − Σ min(类别上限, Σ事件扣分))`，保留 3 位小数。

### 5.1 扣分项总表

`人类`列见 §6：✅ 可统计，⚠️ 只有一部分，❌ 不可统计。

| 族 | 上限 | 单次上限 | 响应窗口 T | 死区 I0 | 满额 Ifull | γ | 人类 |
|---|---:|---:|---:|---:|---:|---:|---|
| `AMBUSH` 偷袭 | 40 | 18 | 30 s | 1 | 12 | 3 | ⚠️ |
| `GRINDER` 绞肉机 | 20 | 15 | 45 s | 3 | 18 | 1.5 | ❌ |
| `STALE` 过期指令 | 15 | 逐条 `K` | — | — | — | — | ❌ |
| `BUILDER_IDLE` 空闲建造者 | 25 | 8 | — | — | — | — | ❌ |
| `FACTORY_IDLE` 空闲工厂 | 20 | 5 | — | — | — | — | ❌ |
| `FUNDS` 资金空余 | 30 | 7 | — | — | — | — | ⚠️ |

### 5.2 A/B 实际 HP 损失与响应

`I = Σ_j w_j·ΔHP_j/HPmax_j`：各目标按自己的最大 HP 归一化再加权，**I 可大于 1**。实际损失在伤害入口前后相减，
不含护盾、免伤、过量伤害；治疗后再次损失可以累计。最大 HP 和权重**不随当前残血变化**。
`hpLosses` 保留按身份聚合的原始 HP 损失，`impactComplete` 表明证据完整。

默认 HP 档位：单位 `≤500` / `(500,1500]` / `>1500`，建筑 `≤2000` / `>2000`。
AMBUSH 权重为单位 `1/2/3`、建筑 `2/4`；GRINDER 单位 `3/2/1`，草稿未定义建筑权重，**暂不把建筑损失计入 GRINDER**。
可覆盖 `hp.unitLow/unitMedium/buildingLow`。

`S = clamp((I−I0)/(Ifull−I0),0,1)^gamma`；`R = min(1, max(0,(有效响应时刻−确认时刻)/1000)/T)`；
`P = SingleMax·S·R`；族扣分 `= min(上限, ΣP)`。

- 有效响应要求相关主动 B 有实际 `applied=true` 的生效证据；**拒绝请求、自动命令、查询不算**。
- 确认前已有有效响应按延迟 0；事件结束前无有效响应按 T 计算。
- AMBUSH 用持续攻击确认时刻；GRINDER 用重复投入后首次实际受击/死亡的确认时刻，不把投入前的旧损失当成本次新增损失。
- GRINDER 从后果开始累计同一次投入的实际伤害，连续 3s 无新增后果即结算，仍受原硬上限约束；
  一个伤害只归于一个投入候选。AMBUSH 合并区域时对原始伤害记录按 seq 去重。
- 旧日志无逐次 HP 损失时，**不根据死亡个数猜 I**，列 `missing_hp_loss_evidence`。

### 5.3 C 过期指令

类别上限 15；每个突发中逐条指令求 `K = w·f(ageSeconds)`，再累计封顶。保留原突发检测门槛，
取消旧首条宽限和固定突发扣分。

| 过期秒数 | ≤5 | (5,10) | [10,20) | [20,40) | [40,60) | ≥60 |
|---|---:|---:|---:|---:|---:|---:|
| `f` | 0 | 0.5 | 1 | 1.5 | 2 | 3 |

类型权重 `w`：查询 **0**、配置 **1**、操作 **2**。

只对 `staleInfo=true` 的世界状态失效项计分；以**已证明的失效时刻**（如 `deathTimeMs`）到提交时刻计算
`staleAgeMs`，不能把突发跨度当作指令过期时长。同一命令多种证据只记一次，优先保留有过期时间的收包证据；
未知失效时刻列为缺证据，不猜测扣分。

### 5.4 D/E 空闲区间

先通过全区间 B 工作预筛选；每段 `P = c·f(d)`，各类独立累计封顶。曲线都使用
[Stage1 中定义的原始横轴公式](STAGE1_SCORING.md#6-曲线与横轴)。

| 类别 | c | 类别上限 | `f(d)`，d 为游戏秒 |
|---|---:|---:|---|
| `BUILDER_IDLE` | 8 | 25 | `[0,8]` 自然指数 `0→0.3`；`(8,25]` 线性 `0.3→0.75`；`(25,60]` 自然对数 `0.75→1`；`>60` 为 1 |
| `FACTORY_IDLE` | 5 | 20 | `≤10` 为 0；`(10,30]` 线性 `0→0.3`；`(30,120]` 自然指数 `0.3→1`；`>120` 为 1 |

### 5.5 F 资金空余

上限 30，单区间 `7·f(d)`。对每秒资金做**居中 3 秒中值平滑**，边缘取可用样本的中位数。
阈值 `theta = K × 近30秒平均收入率`，默认 **K=30 秒**，不足 30 秒时使用已有样本。资金首次达到 theta 且 `>0` 时
启动区间；`D` 按经过的游戏秒累计。

一次花费为"连续下降到回升前"的总降幅 `s`（平台不拆段）；证据结束或跌破阈值也结算尚未回升的下降。
`M` 为本区间迄今最大平滑资金。**严格满足 `s > max(800, 0.25M)` 才计入**：

| 条件 | 处理 |
|---|---|
| `s/M ≤ 0.5` | `D ← 0.5D` |
| `0.5 < s/M ≤ 0.8` | `D ← 0.25D` |
| `s/M > 0.8` | 关闭区间 |

资金跌破 theta 同样关闭。区间时长 `d` 取区间内 `D` 的历史最大值——部分释放不抹去已累计的最大囤积。
`f(d)`：`≤15` 秒为 0，`(15,120]` 自然指数 `0→1`，`>120` 为 1。
完全释放后如资金仍达阈值，从下一个秒样本重新计时。资金事件保留峰值与花费明细；
经济日志缺失时 `excluded.FUNDS_EVIDENCE_MISSING=1`，有日志但格式错误直接报错。

### 5.6 覆盖配置与缺证据

```json
{
  "weights": {"AMBUSH_SINGLE_MAX": 18, "GRINDER_SINGLE_MAX": 15, "BUILDER_IDLE_SINGLE_MAX": 8, "FACTORY_IDLE_SINGLE_MAX": 5, "FUNDS_SINGLE_MAX": 7},
  "caps": {"AMBUSH": 40, "GRINDER": 20, "STALE": 15, "BUILDER_IDLE": 25, "FACTORY_IDLE": 20, "FUNDS": 30},
  "ambush": {"responseSeconds": 30, "deadZone": 1, "fullImpact": 12, "gamma": 3},
  "grinder": {"responseSeconds": 45, "deadZone": 3, "fullImpact": 18, "gamma": 1.5},
  "hp": {"unitLow": 500, "unitMedium": 1500, "buildingLow": 2000},
  "funds": {"K": 30, "incomeFieldPerSecond": 1.5625, "spendFloor": 800, "spendShare": 0.25, "rho1": 0.5, "rho2": 0.25}
}
```

`EXPLAINED`、`EXCLUDED`、`scoreable:false` 对所有评分族统一排除；`UNCERTAIN` 只对 D/E 计分，其余族仍排除——
在那里 `UNCERTAIN` 表示事件本身没被确认，与"资产确实闲着但说不出为什么"不是一回事。
逐项 `reason`、`items`、`unscored` 保留没有扣分的依据；**缺证据不能解释成没有问题**。

---

## 6. 人类对局：哪些扣分项算不出来

**定义**：人类对局 = 玩家直接用鼠标/键盘在 GUI 打完一局。这类局有两个硬伤：

1. **入口直接拒算**：`evaluate_stage_2.js` 与 `evaluate_replay.py` 都强制要求 `--commands`，
   人类对局没有 CLI 命令日志（鼠标操作不写命令日志）。
2. **即使绕过入口，证据结构也不认人类操作**：`command`/`result`/`native` 三类记录都由 CLI 服务写出。
   人类在 UI 上下的命令不会变成 `command` 行，也不会产生带 `agent`/`keep`/`auto` 来源的 `native` 行。

### 6.1 逐族结论

| 族 | 人类可统计 | 原因 |
|---|---|---|
| `AMBUSH` | **⚠️ 只有 I 可算** | 伤害与 `hpLoss` 是引擎证据，`I`/`S` 可算；但"有效响应"要 `applied=true` 的 B 命令，人类永远没有 → `R` 恒为 1，扣分被顶到最大 |
| `GRINDER` | ❌ | 判定第 2 步"投入"完全依赖 B 命令，人类根本不会有候选事件 |
| `STALE` | ❌ | 需要 `result.code`；人类没有 CLI 回执 |
| `BUILDER_IDLE` | ❌ | 排除规则读原生命令行；人类的施工发生在 UI，不在 `agent`/`keep`/`auto` 来源里 → 每一段空闲都被判 `DETECTED`，系统性高估 |
| `FACTORY_IDLE` | ❌ | 同上，且"别的工厂在排产"的排除也依赖原生命令 |
| `FUNDS` | **⚠️ 只依赖 `economy.log`** | 该族与命令无关，是六族里唯一"只要经济证据就能算"的；但现行入口仍要求 `--commands` |

### 6.2 结论

- **人类对局不能生成 `stage2-v2` 分数。** 硬跑出来的分数会比实际表现差得多：`R` 恒为 1、D/E 失去全部免责证据。
- 想拿人类做对照，只能另写一条"人类模式"入口：显式给队伍号、跳过命令相关族，并把 D/E 的排除判据换成
  UI 来源；**这需要新的证据格式，当前实现不支持**。
- 不要用"人类局没分"当作 100 分并列，也不要拿人类局的 AMBUSH `I` 与 Agent 的 `P` 直接比。
- 人类局的可用价值在**回放本身**（战术行为、决策顺序、单位损失过程），不在这套自动事件分。
- Stage1 同样拒算（见 [STAGE1_SCORING.md §8](STAGE1_SCORING.md#8-人类对局哪些扣分项算不出来)）：
  两轮都拿不出人类分数，不要用"没算"去反推水平。

---

## 7. 验证与不确定性

```powershell
node --test evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js evaluate/test/test-score.js evaluate/test/test-score-evidence.js
python -W error::ResourceWarning evaluate/test/test_replay.py
```

引擎侧测试：`core/src/test/kotlin/evaluate/utils/EngineEvidenceTest.kt`（暂停重复帧、整分钟/最后一行、
时间跳跃不造数据、收包对象状态（含 `--builder`）、跨线程关联、死亡去重、连续多局）；
`EngineEvidenceTrimTest.kt`（frames 行合并、变速/跳号分段、单帧原样保留）；`RwxEventsTest.kt`
（`new`/`info` 的发现确认、区域聚类与生产聚合）。

**来源与顺序**：`RwxCliTool` 在执行前冻结接收记录，原始参数不丢弃；上下文经既有模拟线程派发传递。
MANUAL 的原生 Command 带局内关联，KEEP/PENDING 与引擎/UI/Replay 未知来源分别标识。原生 Command 前后任务/队列
确实改变才记 `applied=true`；重复设置相同状态等无法通过快照证明的情况为 null，不能伪造"成功"。
旧日志保留第一轮解析的人工接管截止位置；日志覆盖外不确认全局无 B。旧整秒时间保留 1s 不确定区间，
空窗检查保守地考虑可能落入区间的 B。

**采样**：事件 JSONL 分两遍流式读取——先索引较少的 CLI/原生命令，后执行检测。采集器复用实体更新与伤害/死亡通知，
按"状态跃变才写"采样：我方建造者与所有建筑每 tick 比对状态，受击单位随后 1 秒内每 tick 观察，位置变化最多每秒记一次；
危险区用引擎空间索引，只记成员身份且仅成员变化时写一行。**没有每秒 CLI 查询，也没有评测器发出的游戏操作。**

`summary.json` 的 `diagnostics` 记录每种输入记录数、各检测器的候选数与淘汰原因，用来区分
"严格过滤后确实没有事件"和"证据没有被采集或没有被读到"。

---

## 8. 附录

### A. 面向 Agent 的事件流（与第二轮判定无关，但会影响 Agent 表现）

`rwx-game-events` 的 `new` 与 `info` 是 Agent 的情报通道，不是第二轮判定材料：

- **`new`**：敌方单位必须连续可观察满 1 秒才被记录，且同一 ID 一局只报一次（进出迷雾不重复上报）。
  渲染时按 5 秒窗口 + **距离 ≤160** 聚成一个区域，输出构成与平均位置 `around (x,y)`；不同区域不会混进同一条。
- **`info`**：`produced` 的产物放在事件的 `attacker` 字段，渲染时按 5 秒窗口聚合成一条：
  `produced Tank x2 (#99 #100) by #15 #87`。
- 聚合发生在**渲染层**而不是采集层，所以 `--since <seq>` 的游标语义不变，Agent 也不会因为缓冲多等 5 秒。

### B. 旧检测器对照

| 旧 | 新 |
|---|---|
| `FEED` | `GRINDER`（B） |
| `STALE_COMMAND`、`COMMAND_FAILURE` | `STALE_BURST`（C） |
| `BUILDER_GAP` | `BUILDER_IDLE`（D） |
| `PRODUCTION_GAP` | `FACTORY_IDLE`（E） |

旧事件名仍能映射到新评分族，但不再沿用旧的死亡计数/按分钟扣分替代新公式；复现旧报告需显式
`--config '{"legacyDetectors":true}'`。
