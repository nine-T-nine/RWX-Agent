# CASE1-duel 指标定义

所有指标由 `evaluate/micro/` 的外部脚本计算；引擎只写证据，不写分数。单位：世界坐标、游戏毫秒（`gameMs`，1 tick = 16 game-ms）、墙钟毫秒（`wallMs`）。没有折算总分。

## metrics.json 结构

`evaluate.py` 为每个 run 写 `eval/metrics.json`，顶层字段：

| 字段 | 含义 |
|---|---|
| `schema` | `rwx-micro-metrics/1` |
| `run` | `case`、`task`、`seed`、`track`、`mode` |
| `metrics` | 每个插件一项：`{id: {tier, sources, value}}`；`value.status` 为 `OK/N/A/ERROR` |
| `mapping` | gateway B 请求 ↔ replay 底层命令的匹配：`matched`/`ambiguous`/`unmatched` 及逐条 `rows` |
| `diagnostics` | `resimError`、`playbackChecksumWarnings`、`playbackWarnings`、`baselineError`、`unknownNativeActions`、`replay is incomplete` 等 |
| `reference` | 人类基线（仅 T1）；`kind`、`path`、`metrics` |
| `evidence` | 快照/事件/原生命令条数、`gateway` 是否存在、`playback` 内容、`source`（`re-sim` 或 `live/unverified`）、`verified` |

- 每个 metric 的 `sources` 是声明的来源（T1 为 `replay`+`engine`，T2/T3 为 `gateway`+`meta`），不是本次一定用到的文件。
- 单个插件异常只写 `status=ERROR` + `reason`，不影响其他指标。
- `mapping` 只在有 gateway 时计算；replay-only/human 为 `N/A`。匹配窗口 4 tick；同签名多候选记 `ambiguous`，不强行归属。
- `--metrics-config` 目前只覆盖 `lateMs`/`fireTauMs`。

## 指标清单

| ID | tier | 一句话 |
|---|---|---|
| `outcome` | T1 | 结局、存活、HP/护盾净损失、delta |
| `cadence` | T1 | 我方命令间隔、交战期命令密度 |
| `ENGAGE_LAG` | T1 | 首次交火 → 首条响应命令 |
| `LOWHP_LAG` | T1 | HP 下穿 50%/25% → 首条命令 |
| `IDLE_IN_COMBAT` | T1 | 交战期 idle 且不做事 |
| `STALE_COMMAND` | T1 | 引用死/不存在单位的坏命令 |
| `FIRE_UPTIME` | T1 | 射程内有敌时的开火占比 |
| `FREE_FIRE` | T1 | 在敌人射程外造成的伤害占比 |
| `agent_timing` | T2 | A/B/C 计数、墙钟决策周期、拒绝/跳过 |
| `turns` | T3 | pause 回合数、每回合墙钟/命令数、回合延迟 |

## 数据来源

| 层 | 来源 | 用途 |
|---|---|---|
| T1 人类可比 | Replay 底层命令 + re-sim `snapshots.jsonl`/`events.jsonl` | 只依赖我方命令和状态，人类回放同样可算 |
| T2 Agent 独有 | `gateway.jsonl` + `meta.json` | A/B/C 计数、墙钟决策周期、被拒/跳过 |
| T3 pause | `gateway.jsonl` lifecycle | 回合墙钟、每回合命令数；pause 下底层 tick 间隔无意义 |

`evaluate.py` 默认先做 re-sim（`--resim auto`，仅当 `engine/snapshots.jsonl` 不存在时）；把重放终态与 `result.json` 比对写入 `engine/playback.json`。`metrics.json.evidence.source` 为 `re-sim` 时才有独立复算闸门；`liveSnapshots=true` 或 `--resim skip` 会显示 `live/unverified`。终态 HP/护盾、outcome、击杀**顺序与身份**必须完全一致；每个击杀的 tick 允许 **≤2 tick** 偏差并记 warning（pause 下命令记录在 tick 之间、执行在 tick 之内，一发伤害可提前一两 tick 结算，终态仍一致），偏差 >2 或顺序/身份不同判失败。命令时间优先取 replay 的 `game_time_ms`，缺失时才按 tick 估算。

## 交战窗口

从「任一方中心距 ≤ 12 格（240 世界单位）」或「首次 damage 事件」中**较早者**开始，到 `result.gameMs` 结束。240 是预警线，大于双方射程（210/190），不代表已经开火。没有交战则相关指标 N/A。

## T1（人类可比）

### outcome

- `outcome`：`win|loss|draw|timeout|error`，取 `result.json`；缺失时按终局存活推导。
- `status` 恒为 `OK`（它只要求 `result.json`）；**没有 `snapshots.jsonl` 时其余字段都不出现，只多一个 `state: {status:"N/A", reason:"missing snapshots"}`**，不会伪造损失比例。
- `alive`：双方存活数。
- `hpLossFractions`：`(初始 maxHp − 终局存活单位 hp) / 初始 maxHp`，**只算 HP**；死亡单位按 0 HP 计，即损失 100%。
- `shieldLossFractions`：同样的算法，只算护盾；死亡单位按它死时的剩余护盾计（机甲通常还剩约 50/800），不是按 0 计。因为护盾会持续回复，**存活单位的护盾损失会被回复污染**（末盾接近满 → 损失比例接近 0）。它衡量的是“净损失”，不是“承受的护盾伤害”。
- `delta = 敌方 HP 损失比例 − 我方 HP 损失比例`；`shieldDelta` 同理。
- `delta` 依赖双方的 a/b 与初始总 HP，**只能在同一 `task+seed` 内比较**；跨 level、跨 seed、跨人数都不可比。
- `clearMs`（win）/`survivalMs`（loss）：结束时的游戏毫秒；其他结局为 `null`。
- 敌方初始护盾为 0 时 `shieldLossFractions.enemy` 记为 0（不是 N/A），`shieldDelta` 因此等于 `-我方护盾损失`，解释时要看出这一点。

### cadence

- 我方底层命令相邻间隔（**replay 生效 tick 的 `gameMs`**）的 p50/p90/max。
- `intervalMs` 是 `{n,p50,p90,max}`；`commands` 是命令总数。没有命令时 `n=0`，p50/p90/max 全为 `null`。
- `combatCommandsPerMinute`：交战窗口内命令数 / 窗口分钟数；窗口无效或长度为 0 时为 `null`。
- `combatMaxGapMs`：交战窗口内相邻命令（含窗口起点、结束）的最大间隔；没有交战窗口时为 `null`。
- pause 赛道附 `pauseWarning`：tick 间隔反映固定回合，不是思考速度；同一回合的暂停命令都落在下一回合首 tick，`p50` 会等于回合长度。

### ENGAGE_LAG

首次交火（交战窗口起点，见上）→ 首条包含任一**存活**我方单位的 B 类命令的延迟。

- `PREEMPTED`：交火前已有含该单位的 B 命令，**且交火时该单位 `order != idle`**（命令仍在执行）。briefing 里发过一条早已走完的 `move`，交火时 `order=idle`，不算 PREEMPTED；`latencyMs=0`。
- 延迟 = 命令的 replay 生效 `gameMs − 交战起点`；**不是**网关受理时刻。pause 下两者最多差一个回合。
- 延迟 ≤ `lateMs`（默认 1000）→ `ANSWERED`；否则 `LATE`；两者都带 `responseTick`。
- pause 的回合默认 `60×16=960 gameMs`，所以**一回合内的响应一定 < 1000 → ANSWERED**；要 `LATE` 至少跨 2 回合。pause 报告同时给 `turnLag`（跨了几个回合），以它为准。
- 结束前没有 → `UNANSWERED`，`latencyMs=null`。
- `value` 顶层还带 `engageMs`（交战窗口起点）。

### LOWHP_LAG

单位 HP 下穿 50%/25% 阈值 → 首条包含该单位的 B 类命令延迟。阈值按 `maxHp` 的百分比算，**只看 HP、不看护盾**。优先用 `damage` 事件精确定位穿越；没有事件时用 250ms 快照，精度标注为采样不确定。状态同上：`ANSWERED|LATE|UNANSWERED`（不做 PREEMPTED）。

- `value`：`status`、`crossingSource`（`damage events` 或 `sampled crossings...`）、`rows[]`。
- 每行：`unit`、`threshold`（0.5/0.25）、`gameMs`、`status`、`latencyMs`、`responseTick`；pause 下 T3 会再给 `turnLag`。

### IDLE_IN_COMBAT

交战窗口内、敌方仍有存活单位时，我方单位 `order=idle` 且（无当前攻击目标或最近 2 秒未开火）持续 ≥2 秒，记录每段 `unit/startMs/durationMs` 和 `totalUnitMs`。只打射程内、不追的 `onlyInRange` 单位在等敌人进入 210 时也会被计入，这是有意的“没在做事”口径。`value` 还带 `status` 和 `rows[]`。

- 时长按相邻 250ms 快照的间隔累加，所以持续时间精度是 250ms；`startMs` 是第一帧 idle 的 `gameMs`。
- 只在「我方单位仍存活」的帧里计；死亡后不再产生 idle 段。

### STALE_COMMAND

底层命令引用了已死亡/不存在的单位：`count`（命令条数）、`unitReferences`（单位引用数）、逐条 `rows[{tick,units}]`。

- Agent 模式下网关会先跳过已死单位（回执 `partial: skipped dead=...`），这些单位不会进 replay；所以 agent run 的 `STALE_COMMAND` 通常为 0。
- 只有 pending/queue 中被选中后死亡、或 replay-only（人类点击）才会留下引用。它是“坏引用”审计，不是反应速度指标。

### FIRE_UPTIME

每台存活机甲在「射程内有存活敌人」的时间里的开火占比与中断次数。

- “射程内”= 中心距 `d ≤ 该单位 range`（210），与开火判定同口径。
- 开火判定优先用武器状态：`target != null && (cooldownFrames > 0 || warmupFrames > 0)`。
- 武器字段缺失时退化为「该单位最近 `fireTauMs`（默认 1000）内造成过伤害」，并在 `method` 标注。
- `ratio = firingMs / eligibleMs`；`interruptions` 为开火→不开火的切换次数。
- `value`：`status`、`method`（`weapon state` 或含 fallback）、`tauMs`、`units{id: {eligibleMs, firingMs, interruptions, ratio}}`。

### FREE_FIRE

我方造成的伤害中，命中时攻击者在**所有存活敌人**射程之外的占比（伤害事件已带攻击者/受害者坐标与对手射程）。`ratio = freeDamage / damage`；坐标缺失时 N/A，不猜。

- `value`：`status`、`damage`、`freeDamage`、`ratio`。
- 距离同样是中心距；`opponents` 是命中时刻所有存活敌人的位置与 `range`，包含之后才死的单位。
- `damage = hpDamage + shieldDamage`；`shieldDamage` 是护盾实际减少量。猛犸单发在护盾充足且剩余 HP 足够时，实际同时扣 130 护盾与 117 HP，因此这里记录 247 点双池扣减；不是将标称 130 守恒分配到两个池。结算为 `shieldDamage=min(S,130)`、`hpDamage=min(剩余HP,130−0.1×min(S,130))`，`S` 为命中前护盾。辅助指标按实际状态差计算，不改变胜负。

## T2（Agent 独有）

`agent_timing`：

- A/B/C 请求计数；briefing 期分列。`C` 包含 `begin`/`next`/`help`/`ping`，以及拼错/未注册命令（网关对未知命令默认 class=C），所以 C 不是“生命周期次数”的纯计数。
- **A 块 + B 块**：按时间顺序把连续的同类请求切成块（batch 子请求按 `parent` 去重，只保留 batch 本身）；只看 `A` 块后接 `B` 块。
- `thinkingWallMs`：一个 A 块 + 其后的 B 块内，相邻请求的 `wallRecvMs − 上一条 wallReplyMs` 之和（最大化 0），即去掉引擎/传输耗时后的思考墙钟；统计 p50/p90/max。**pause 的主要思考发生在 B→A（暂停期），不在 A→B，所以 T2 的 thinking 在 pause 下不代表思考时间，应看 T3。**
- B→A 等待 `waitWallMs`：相邻 B、A 之间的同一差值。
- 只有 `A→B` 配对的周期才进 `cycles`；一次 run 里全是 B 或全是 A，`cycles=[]`，`thinkingWallMs` 的 `n=0`。
- 观察陈旧度：每条 B 距其前最近 A 的 `gameMs`/`wallMs`。
- `wallUsedMs`：`result.wallMs`（相对首条命令）。
- `errorRatio`/`rejectedRatio`/`skippedDeadRatio`：按请求条数；`rejected` 是未知/未注册/赛道不允许，`error` 是参数/briefing 禁止等。
- `enemyWeaponCycles`：每个决策周期内敌方武器理论可开火轮数。公式：对每个敌方武器 `period = (reloadFrames + (0 if warmupNoReset else warmupFrames)) × tickDurationMs`，取所有武器的最小 `period`，`cycles = cycle.gameMs / period`。本 case 猛犸 = `(50+30)×16 = 1280ms`，与实测每发 1280 game-ms 一致；它忽略射程/转向/目标切换，是上限估计。`cycles[]` 每项含 `gameMs`、`thinkingWallMs`、`firstA`、`lastB`、`enemyWeaponCycles`；顶层另有 `enemyWeaponPeriodMs`。
- replay-only/human 无 gateway → 整体 N/A。

## T3（pause 赛道）

- `turns`：`turn` lifecycle 条数（`begin` 第 1 回合，`next` 每推一次 +1）；超时截断的回合也记一条。
- `thinkingWallMs`：相邻 `begin/next` 请求之间（从上一条回执到下一条请求，含中间观察/下令）的墙钟，p50/p90/max。这是 pause 下真正的思考+操作时间。
- `actionsPerTurn`：每回合开始时生效（上一暂停期下达）的 B 命令数；按请求的 `turn+1` 归到下一回合，键是回合号。
- `emptyCombatTurnRatio`：交战期内 0 条 B 的回合占比。
- `engage`/`lowhp` 额外给 `turnLag`（延迟跨越的回合数）。
- pause 下「底层命令 tick 间隔」无意义：同一回合的暂停命令都在下一回合首 tick 生效，文档与报告均标注。

## 排序与比较

- **不是总分**，也没有跨指标归一化。先看 `outcome`，再在**同一 task+seed、同一 track** 内比较 T1（delta、lag、fire 等）。
- `win` 优先；`timeout` 表示“没打完”，`loss` 表示“被打光”，两者不是强弱排序：**不要把 timeout 当成高于 loss**。一个躲着不打的 timeout 不应排在认真交战但 loss 的前面；报告里把 timeout 单列。
- realtime 与 pause 不能同榜：命令到达 tick、回合量化、思考墙钟口径都不同；人类基线只有 realtime 1×。
- 人类基线 key 为 `<task>:<seed>`，每个组合要有自己的 run；只比较 T1。pause 的 Agent 与 realtime 人类相比时，`cadence`/`turns`/`thinkingWallMs` 不可比，`outcome`/`hpLossFractions`/`ENGAGE_LAG`/`LOWHP_LAG`/`FIRE_UPTIME` 可参考（仍受回合量化影响）。

## 阈值与配置

| 项 | 默认 | 说明 |
|---|---:|---|
| `lateMs` | 1000 | ENGAGE/LOWHP 的 LATE 阈值；pause 单回合 960ms，单回合响应必为 ANSWERED |
| `fireTauMs` | 1000 | 无武器状态时的开火推断窗口 |
| idle 判定 | 2000ms | 持续无目标/未开火 |
| 交战距离 | 240 世界单位 | 12 格，中心距 |
| 快照间隔 | 250 game-ms | `snapshots.jsonl` |
| tick 长度 | 16 game-ms | `tickDurationMs`/`gameMsPerTick` |
| gateway 匹配窗口 | 4 tick | `match_gateway` 的 `tick_window` |
| 击杀 tick 容差 | ≤2 tick | 每杀单独比；身份/顺序/终态必须一致，记 warning 并列出偏差 |

通过 `evaluate.py --metrics-config <json>` 可覆盖 `lateMs`/`fireTauMs`；匹配窗口与快照间隔是代码常量，改需改 `common/`。

## 已知局限

- 没有总分，也没有跨指标归一化；不同 tier 的指标不能相加。
- `shieldLossFractions` 是净损失，会被护盾回复污染；要“承受的护盾伤害”只能从 `events.jsonl` 的 `damage.shieldDamage` 累积（暂未做成指标）。
- `IDLE_IN_COMBAT`、`FIRE_UPTIME` 受 250ms 快照粒度限制；`FIRE_UPTIME` 的武器状态判定还依赖 `warmupFrames`/`cooldownFrames` 在采样点非零。
- T2 的 `thinkingWallMs` 只覆盖 A→B；pause 的真实思考在看 T3 的 `thinkingWallMs`。
- 人类基线只有 realtime 1×，且 GUI 人类流程未验证（见 README §5）。

## 后续（暂不实现）

- 脚本基线与归一化、P−R 汇总。
- RETARGET_LAG（换目标反应）、集火度/多单位协同。
- 跨局聚合、置信区间、人类与 Agent 的配对比较。
- 把 `shieldLossFractions` 换成“承受的护盾伤害”（现在只有净损失，会被回复污染）。
