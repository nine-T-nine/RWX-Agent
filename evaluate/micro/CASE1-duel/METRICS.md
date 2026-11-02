# CASE1-duel 评价材料与指标定义

本文件回答两件事：**一局打完能拿到哪些材料**，以及**每个指标是怎么从这些材料算出来的**。
怎么启动、怎么跑完一整轮评测、场景长什么样，见 [README.md](README.md)。

引擎只写事实，不写分数。所有指标由 `evaluate/micro/` 的外部 Python 脚本离线计算，不折算总分。
单位约定：世界坐标、游戏毫秒（`gameMs`，1 tick = 16 game-ms）、墙钟毫秒（`wallMs`）。

---

## 1. 这个 CASE 能拿到什么

**能（可信）**

- 结局与战损：`outcome`、双方存活、HP/护盾净损失、`delta`、清场/存活时长。人类回放同样可算。
- 节奏：我方底层命令间隔 p50/p90/max、交战期命令密度、最大无令间隔。
- 反应：首次交火 → 首条命令的延迟；HP 下穿 50%/25% → 首条命令的延迟；两者分 `ANSWERED/LATE/UNANSWERED`。
- 执行质量：射程内开火占比、射程外输出占比、交战期 idle 时长、坏引用命令数。
- Agent 行为（T2，仅 Agent）：A/B/C 计数、A→B 思考墙钟、B→A 等待、观察陈旧度、被拒/跳过比例、敌方武器轮数。
- 回合行为（T3，仅 pause）：回合数、每回合思考墙钟、每回合命令数、空战斗回合占比、回合延迟。

**不能**

- 没有总分、没有跨指标归一化、没有跨 CASE 比较。`delta` 只在同一 `task+seed` 内可比。
- 没有独立复算时（`evidence.source=live/unverified`）不能声称"结果经过复核"。
- 没有 gateway 时（人类回放）T2/T3 全部 N/A，`decisionCycle` 一夜之间不存在。
- `pause` 的 `cadence` 反映固定回合，不是思考速度；`thinkingWallMs` 在 pause 下不代表思考时间。
- 没有"操作好坏"的绝对标尺：所有阈值都是本 CASE 的经验值（见 §10）。

---

## 2. 产物目录

```
<run>/
  meta.json             布景完成时写（评测方私有：敌方完整配置、初始属性）
  gateway.jsonl         薄网关：一行一条请求 + 生命周期
  result.json           结束时写（authoritative outcome）
  match.replay          自动录像（地图类型 1 另有 map.tmx）
  delivery.jsonl        投递链路延迟（env/command/deliver/warning 四类行）
  replay/               离线解析出的底层命令：match.summary.txt / match.txt / match.jsonl
  engine/
    snapshots.jsonl     状态快照（默认 250 game-ms 一帧）★ 仅 re-sim 产出
    events.jsonl        damage / kill / order 事件            ★ 仅 re-sim 产出
    playback.json       独立复算的裁决结果                    ★ 仅 re-sim 产出
    playback.log        re-sim 进程输出（校验和告警的原始出处）
  eval/
    metrics.json        全部指标 + 诊断 + 人类基线对照
    report.txt          纯文本一览（指标 × tier × 本轮 × 人类）
    report.html         表格 + 双方 HP/护盾时间线（内联 SVG）
```

★ 的三份只有走过 re-sim 才存在。`liveSnapshots=true` 时引擎在跑的过程中直接写 `snapshots.jsonl`/`events.jsonl`，
此时**没有** `playback.json`，报告标 `evidence=live/unverified`。

---

## 3. 材料清单

### 3.1 meta.json（布景事实，评测方私有）

| 字段 | 含义 |
|---|---|
| `schema` | `rwx-micro-evidence/1` |
| `case` / `task` / `seed` / `track` / `mode` / `agent` | 身份；`task` 形如 `1_2_2`，`track`=realtime\|pause，`mode`=agent\|human |
| `mapType` / `level` | 由 task 解出 |
| `map{file,sha256,width,height,tileSize}` | 地图文件、哈希、格数、格宽（世界单位） |
| `limits{wallSeconds,gameSeconds,briefingSeconds,startTimeoutSeconds,enemyDelaySeconds}` | 四条预算 |
| `tickDurationMs` / `gameMsPerTick` | 16（实际 game-ms/tick） |
| `simulationFrameMs` / `nominalTickMs` | 16.667（名义值，**不要**用它算 gameMs） |
| `turnTicks` / `turnSeconds` | 回合长度（pause） |
| `engineVersion` / `commit` | 构建身份 |
| `replay` | 录像文件名 |
| `enemyPolicy` | 敌方 AI 策略 id，如 `attackMove+radialRangeRetreat/v1` |
| `liveSnapshots` | 是否 live 证据（不走过 re-sim） |
| `a` / `b` | 我方/敌方单位数 |
| `units[]` | **布景真值**：`id,alias,team,sourceTeam,type,x,y,maxHp,maxShield,weapons[{index,range,damage,reloadFrames,warmupFrames,warmupNoReset,reloadTransferFrames}],range` |
| `discardedUnits` / `deletedUnusedCenters` | 布景裁掉了哪些预设单位/中心（可复现性证据） |
| `controlledTeam` | 我方队号（本例 0） |

- `range`/`weapons[].range` 是**中心距**，不是格数。
- `reloadTransferFrames` 是**出生时**预热对应的装填减免；机甲/猛犸出生都是 0，机甲满预热最大 19。
- meta 是布景的纯函数：同 `task+seed` 重建出的初始状态完全一致。

### 3.2 gateway.jsonl（Agent 侧行为）

请求行：

```json
{"kind":"request","seq":14,"wallRecvMs":120,"wallReplyMs":125,"gameMs":0,"tick":0,
 "phase":"briefing","turn":0,"class":"B","name":"rwx-micro-1-move","raw":"...","status":"submitted",
 "error":null,"units":["M1:1000"],"skippedDead":[],"parent":null}
```

生命周期行：

```json
{"kind":"lifecycle","event":"turn","wallMs":900,"gameMs":960,"tick":60,"turn":1,"reason":"boundary"}
```

| 字段 | 语义 |
|---|---|
| `seq` | 请求序号（本 CASE 的行**没有** `requestId`；CASE2-4 才有，语义等同 `seq`） |
| `wallRecvMs` / `wallReplyMs` | 网关收到 / 回复的墙钟；差值即网关处理耗时 |
| `gameMs` / `tick` / `turn` / `phase` | 到达时的游戏时刻、tick、回合号、阶段（`briefing`/`running`/`ended`） |
| `class` | `A` 观察 / `B` 操作 / `C` 生命周期与帮助（唯一来源：命令注册表） |
| `status` | `submitted` / `partial` / `error` / `rejected` |
| `units` | 本命令**实际作用**的单位（`别名:ID`），已扣除跳过 |
| `skippedDead` | 被网关卡掉的已死单位 |
| `parent` | batch 子请求指向父请求 |

- `event` 取值：`first-command`、`begin`、`turn`、`end`、`engine-error`。
- 不记录解析后的参数和应用 tick（那些在 replay 里）。
- `status=partial` 表示部分单位被跳过，命令仍执行。

### 3.3 result.json（authoritative 结局）

`outcome`（`win|loss|draw|timeout|error`）、`endReason`、`gameMs`、`tick`、`wallMs`（相对首条命令）、
`turns`、`kills[]`（`{unit,tick}`，顺序即发生顺序）、`units[]`（终态 id/hp/shield/alive/x/y）、
`self`/`enemy`（存活数、hp、shield）、`schema`、`agent`。

- 不含任何基线；基线由评测器塞进 `eval/metrics.json.reference`。
- `kills` 的顺序与身份是复算闸门的一部分。

### 3.4 match.replay + replay/

`.replay` 是引擎真正录制的原生命令流，**被拒绝的命令不在里面**。

`replay/` 是 `evaluate/utils/replay.py` 解析出的三份视图：

| 文件 | 内容 |
|---|---|
| `match.summary.txt` | 对局摘要（时长、帧数、命令计价） |
| `match.txt` | 逐条命令的人类可读文本 |
| `match.jsonl` | 机器可读，一行一条 order 记录 |

order 记录的关键键：`tick,time_s,team,units,unit_types,type,action,target_unit,target_unit_type,
target_point,rally,attack_mode,build,build_count,command_queued,stop_current_action,clear_existing_orders,
system_action,noop,x,y`。

评测器把它归一化成 `{tick,gameMs,team,kind,units,target,x,y,raw}`：

- `kind` ∈ `move` / `attack-move` / `attack` / `stop` / `mode` / `other`（无法识别的一律 `other`，**不丢**）。
- `gameMs` 优先取 replay 的 `game_time_ms`；缺失才按 `(tick+1)×16` 估算。
- 只保留 `team == meta.controlledTeam` 且 `noop` 为假的记录。
- `noop` 行是引擎写的占位，不参与任何指标。

### 3.5 delivery.jsonl（投递链路延迟）

每局都会写（`MicroRuntime` 把 `rwx.trace.dir` 指到 run 目录）。四类行：

| `kind` | 内容 |
|---|---|
| `env` | 速度档、诊断开关、投递档位、tier、pid、地图名——本局的**环境标定** |
| `command` | 每条命令：`received{wall,game}`、`applied{wall,game}`、`appliedBound="reply"`、`failed` |
| `deliver` | 告警投递：`produced{wall,game}` → `delivered{wall,game}`、`events` 合并条数 |
| `warning` | `session-shared` 等异常 |

三段可归因：`produced→delivered`（环境管线）、`delivered→received`（Agent 往返）、`received→applied`（传输 + 游戏线程交接）。
`applied` 是**上界**（交接同步，回复就绪即视为生效）；阻塞命令（如 step）会高估自己的时长。
当前**没有指标消费它**，它是诊断材料。

### 3.6 engine/snapshots.jsonl（状态快照）★

默认每 250 game-ms 一帧：

```json
{"gameMs":1250,"tick":78,"units":[{"id":1000,"x":...,"y":...,"hp":...,"shield":...,"order":"idle",
 "target":null,"weapons":[...],"alive":true}]}
```

- `order` 是引擎的当前意图（`idle`/`move`/`attack`…），`target` 是当前攻击目标 id。
- `weapons[]` 带 `cooldownFrames`/`warmupFrames`，是 `FIRE_UPTIME` 首选的"正在开火"证据。
- 250ms 是采样精度：所有"持续时长"类指标的下限是 250ms。

### 3.7 engine/events.jsonl（事件流）★

三种 `kind`：

| kind | 字段 | 用途 |
|---|---|---|
| `damage` | attacker/victim、HP 与护盾伤害、之后数值、双方坐标、当时所有对手位置与射程 | 交火起点、护盾伤害、`FREE_FIRE` |
| `kill` | unit、tick | 死亡时刻，判断命令是否引用死人 |
| `order` | `source=player\|ai\|engine` | 归因：谁下的命令 |

`damage` 事件同时给出攻击者与受害者坐标以及**当时所有对手的位置与射程**，所以 `FREE_FIRE` 不需要再猜几何。

### 3.8 engine/playback.json（独立复算裁决）★

```json
{"schema":"rwx-micro-playback/1","consistent":true,"outcome":"win","finalStateChecked":true,
 "killSequenceChecked":true,"checksumWarnings":0,"checksumFrames":[],"warnings":[],"sha256":"...",
 "kills":[{"unit":1001,"tick":412}],"errors":[]}
```

判定规则：

1. 终态：每个单位的 `hp`/`shield` 与 `alive` 必须与 `result.json` 完全一致（容差 0.001）。
2. outcome 必须一致（`resim` 会按存活数重新推导：双方全灭=draw、我方全灭=loss、敌方全灭=win）。
3. 击杀：**身份与顺序**必须完全一致；每个击杀 tick 允许 **≤2 tick** 偏差，超出或顺序不符判失败。
4. 输入侧：replay 的 sha256 在复算前后必须不变。
5. `checksumWarnings` 是**帧校验和**告警数：只作为 warning 记录，不推翻结论（终态/结局/击杀都对齐时，瞬时帧差异不影响本 CASE 指标）。

`consistent=true` + `evidence.source=re-sim` + `evidence.verified=true` 才是"过了独立复算闸门"。

### 3.9 为什么 ≤2 tick 的击杀偏差是允许的

pause 下命令记录在 tick 之间、执行在 tick 之内，一发伤害可能提前一两 tick 结算，而终态仍完全一致。
把这种偏差判成失败会把正确实现误伤成 bug；放宽到 3 tick 又会掩盖真实的时序漂移。2 tick（32 game-ms）是本 CASE 的经验边界。

---

## 4. 时间与时基

| 量 | 定义 | 坑 |
|---|---|---|
| `tick` | 引擎模拟步 | 1 tick = 16 game-ms（int 截断），**不是** 16.667 |
| `gameMs` | 引擎时钟 | `tick × 16`；命令优先取 replay 的 `game_time_ms` |
| `wallMs` | 墙钟 | 请求行相对 run 初始化原点；`result.wallMs` 相对首条命令 |
| `turn` | 回合号 | 只有 pause 有意义；`begin` 是第 1 回合 |
| 交战起点 | 任一方中心距 ≤ 240 或首个 damage 事件，取**较早者** | 240 是预警线，大于双方射程（210/190），不代表已开火 |

**命令时间一律取 replay 生效的 `gameMs`，不取网关受理时刻。** pause 下两者最多差一个回合，
混用会让 `ENGAGE_LAG` 变成"网关排队时间"。

---

## 5. metrics.json

`evaluate.py` 为每个 run 写 `eval/metrics.json`，`schema = rwx-micro-metrics/1`：

| 字段 | 含义 |
|---|---|
| `run` | `case`、`task`、`seed`、`track`、`mode` |
| `metrics` | 每个插件一项：`{id: {tier, sources, value}}`；`value.status` 为 `OK/N/A/ERROR` |
| `mapping` | gateway B 请求 ↔ replay 底层命令：`matched`/`ambiguous`/`unmatched` + 逐条 `rows` |
| `diagnostics` | `resimError`、`playbackChecksumWarnings`、`playbackWarnings`、`baselineError`、`unknownNativeActions`、`replay is incomplete` 等 |
| `reference` | 人类基线（仅 T1）：`kind`、`path`、`metrics` |
| `evidence` | `snapshots`/`events`/`nativePlayerCommands` 条数、`gateway` 是否存在、`playback` 全文、`source`（`re-sim` \| `live/unverified`）、`verified` |

- `sources` 在 CASE1 里由 tier 决定（T1=`["replay","engine"]`，T2/T3=`["gateway","meta"]`），是**声明**而非本次一定用到的文件。
- 单个插件抛异常只写 `status=ERROR` + `reason`，不影响其他指标。
- `mapping` 只在有 gateway 时计算；replay-only/human 为 `N/A`。

---

## 6. 指标清单

| ID | tier | 一句话 | 主要材料 |
|---|---|---|---|
| `outcome` | T1 | 结局、存活、HP/护盾净损失、delta | result + meta + snapshots |
| `cadence` | T1 | 我方命令间隔、交战期命令密度、最大空档 | replay + snapshots |
| `ENGAGE_LAG` | T1 | 首次交火 → 首条含存活我方单位的命令 | replay + snapshots + events |
| `LOWHP_LAG` | T1 | HP 下穿 50%/25% → 首条含该单位的命令 | replay + snapshots + events |
| `IDLE_IN_COMBAT` | T1 | 交战期 idle 且不做事 ≥2s | snapshots + events |
| `STALE_COMMAND` | T1 | 引用死/不存在单位的坏命令 | replay + snapshots + events |
| `FIRE_UPTIME` | T1 | 射程内有敌时的开火占比与中断次数 | snapshots |
| `FREE_FIRE` | T1 | 在敌人射程外造成的伤害占比 | events |
| `agent_timing` | T2 | A/B/C 计数、A→B 思考墙钟、B→A 等待、观察陈旧度、错误率、敌方武器轮数 | gateway + meta |
| `turns` | T3 | pause 回合数、每回合墙钟/命令数、回合延迟 | gateway lifecycle |

三个 tier 的含义：**T1 = 人类回放也能算**（这是它存在的理由）；T2 = 只有 Agent 有；T3 = 只有 pause 有。
不同 tier 的指标不能相加，也不能跨 tier 平均。

---

## 7. 指标定义

### 7.1 outcome

- `outcome`：`win|loss|draw|timeout|error`，取 `result.json`；缺失时按终局存活推导。
- `status` 恒为 `OK`（只要求 `result.json`）。**没有 `snapshots.jsonl` 时其余字段都不出现**，只多一个
  `state: {status:"N/A", reason:"missing snapshots"}`——不伪造损失比例。
- `alive`：双方存活数。
- `hpLossFractions`：`(初始 maxHp − 终局存活单位 hp) / 初始 maxHp`，**只算 HP**；死亡单位按 0 HP 计（损失 100%）。
- `shieldLossFractions`：同算法只算护盾；死亡单位按它死时的剩余护盾计（机甲通常还剩约 50/800），**不是**按 0 计。
- `delta = 敌方 HP 损失比例 − 我方 HP 损失比例`；`shieldDelta` 同理。
- `clearMs`（win）/`survivalMs`（loss）：结束时的 gameMs；其他结局为 `null`。
- 敌方初始护盾为 0 时 `shieldLossFractions.enemy` 记 0（不是 N/A），`shieldDelta` 因此等于 `−我方护盾损失`。

**坑**：护盾会持续回复，存活单位的护盾损失会被回复污染（末盾接近满 → 比例接近 0）。它衡量的是"净损失"，
不是"承受的护盾伤害"。后者要从 `events.jsonl` 累积 `damage.shieldDamage`（当前未做成指标）。

### 7.2 cadence

- `intervalMs {n,p50,p90,max}`：我方底层命令相邻间隔（**replay 生效 tick 的 `gameMs`**）。
- `commands`：命令总数；没有命令时 `n=0`，其余为 `null`。
- `combatCommandsPerMinute`：交战窗口内命令数 / 窗口分钟数。
- `combatMaxGapMs`：交战窗口内相邻命令（含窗口起点、结束）的最大间隔。
- pause 赛道附 `pauseWarning`：tick 间隔反映固定回合；同一回合的暂停命令都落在下一回合首 tick，`p50` 会等于回合长度。

**坑**：这是"引擎接受了多少条命令"，不是"Agent 下达了多少条"。被拒的命令、`--count N` 展开前的打包命令
都不在这里。跨 Agent 比较时要先对齐计数口径。

### 7.3 ENGAGE_LAG

首次交火（交战窗口起点）→ 首条包含任一**存活**我方单位的 B 类命令的延迟。

- `PREEMPTED`：交火前已有含该单位的 B 命令，**且交火时该单位 `order != idle`**（命令仍在执行）。`latencyMs=0`。
  briefing 里发过一条早已走完的 `move`、交火时 `order=idle` —— 不算 PREEMPTED。
- 延迟 = 命令的 replay 生效 `gameMs − 交战起点`；**不是**网关受理时刻。pause 下两者最多差一个回合。
- ≤ `lateMs`（默认 1000）→ `ANSWERED`；否则 `LATE`；两者都带 `responseTick`。
- 结束前没有 → `UNANSWERED`，`latencyMs=null`。
- `value` 顶层还带 `engageMs`。
- pause 的回合默认 `60×16=960 gameMs`，所以**一回合内的响应一定 < 1000 → ANSWERED**；要 `LATE` 至少跨 2 回合。
  pause 报告因此额外给 `turnLag`（跨了几个回合），以它为准。

### 7.4 LOWHP_LAG

单位 HP 下穿 50%/25% 阈值 → 首条包含该单位的 B 类命令延迟。阈值按 `maxHp` 百分比算，**只看 HP、不看护盾**。

- 优先用 `damage` 事件精确定位穿越；没有事件时用 250ms 快照，`crossingSource` 标注为采样不确定。
- 状态 `ANSWERED|LATE|UNANSWERED`（**不做 PREEMPTED**）。
- `value`：`status`、`crossingSource`、`rows[]`；每行 `unit`、`threshold`（0.5/0.25）、`gameMs`、`status`、`latencyMs`、`responseTick`；
  pause 下 T3 会再给 `turnLag`。

### 7.5 IDLE_IN_COMBAT

交战窗口内、敌方仍有存活单位时，我方单位 `order=idle` 且（无当前攻击目标或最近 2 秒未开火）持续 ≥2 秒。

- 记录每段 `unit/startMs/durationMs` 与 `totalUnitMs`。
- 只打射程内、不追的 `onlyInRange` 单位在等敌人进入 210 时也会被计入——这是**有意的"没在做事"口径**。
- 时长按相邻 250ms 快照累加，精度 250ms；`startMs` 是第一帧 idle 的 `gameMs`。
- 只在"我方单位仍存活"的帧里计；死亡后不再产生 idle 段。

### 7.6 STALE_COMMAND

底层命令引用了已死亡/不存在的单位：`count`、`unitReferences`、逐条 `rows[{tick,units}]`。

- Agent 模式下网关会先跳过已死单位（回执 `partial: skipped dead=...`），这些单位不会进 replay，
  所以 **agent run 的 `STALE_COMMAND` 通常为 0**。
- 只有 pending/queue 中被选中后死亡、或 replay-only（人类点击）才会留下引用。
- 它是"坏引用"审计，**不是**反应速度指标。

### 7.7 FIRE_UPTIME

每台存活机甲在"射程内有存活敌人"的时间里的开火占比与中断次数。

- "射程内" = 中心距 `d ≤ 该单位 range`（210），与开火判定同口径。
- 开火判定优先用武器状态：`target != null && (cooldownFrames > 0 || warmupFrames > 0)`。
- 武器字段缺失时退化为"该单位最近 `fireTauMs`（默认 1000）内造成过伤害"，并在 `method` 标注。
- `ratio = firingMs / eligibleMs`；`interruptions` = 开火→不开火的切换次数。
- `value`：`status`、`method`（`weapon state` 或含 fallback）、`tauMs`、`units{id:{eligibleMs,firingMs,interruptions,ratio}}`。

### 7.8 FREE_FIRE

我方造成的伤害中，命中时攻击者在**所有存活敌人**射程之外的占比。

- `ratio = freeDamage / damage`；坐标缺失时 N/A，不猜。
- 距离同样是中心距；`opponents` 是命中时刻所有存活敌人的位置与 `range`，**包含之后才死的单位**。
- `damage = hpDamage + shieldDamage`；`shieldDamage` 是护盾实际减少量。
- 猛犸单发在护盾充足且剩余 HP 足够时，实际同时扣 130 护盾与 117 HP，因此记录 247 点双池扣减；
  不是把标称 130 守恒分配到两个池。结算为 `shieldDamage=min(S,130)`、`hpDamage=min(剩余HP,130−0.1×min(S,130))`，`S` 为命中前护盾。

### 7.9 agent_timing（T2）

- A/B/C 请求计数；briefing 期分列。
  **C 不是"生命周期次数"**：它包含 `begin`/`next`/`help`/`ping`，以及拼错/未注册命令（网关对未知命令默认 class=C）。
- **A 块 + B 块**：按时间顺序把连续同类请求切块（batch 子请求按 `parent` 去重，只保留 batch 本身）；只看 `A` 块后接 `B` 块。
- `thinkingWallMs`：一个 A 块 + 其后 B 块内，相邻请求的 `wallRecvMs − 上一条 wallReplyMs` 之和（下限 0），
  即去掉引擎/传输耗时后的思考墙钟；给 p50/p90/max。
  **pause 的主要思考发生在 B→A（暂停期），不在 A→B，所以 pause 下这个字段不代表思考时间，应看 T3。**
- `waitWallMs`：相邻 B、A 的同一差值（B→A 等待）。
- 只有 `A→B` 配对的周期才进 `cycles`；一次 run 全是 B 或全是 A 时 `cycles=[]`、`thinkingWallMs.n=0`。
- 观察陈旧度：每条 B 距其前最近 A 的 `gameMs`/`wallMs`。
- `wallUsedMs`：`result.wallMs`（相对首条命令）。
- `errorRatio` / `rejectedRatio` / `skippedDeadRatio`：按请求条数。`rejected` = 未知/未注册/赛道不允许；`error` = 参数错误/briefing 禁止等。
- `enemyWeaponCycles`：每个决策周期内敌方武器理论可开火轮数。
  `period = (reloadFrames + (0 if warmupNoReset else warmupFrames)) × tickDurationMs`，取所有武器最小 `period`，
  `cycles = cycle.gameMs / period`。本例猛犸 `(50+30)×16 = 1280ms`，与实测每发 1280 game-ms 一致。
  **它忽略射程/转向/目标切换，是上限估计。**
- replay-only/human 无 gateway → 整体 N/A。

### 7.10 turns（T3）

- `turns`：`turn` lifecycle 条数（`begin` 第 1 回合，`next` 每推一次 +1）；超时截断的回合也记一条。
- `thinkingWallMs`：相邻 `begin`/`next` 之间（上一条回执到下一条请求，含中间观察/下令）的墙钟。这是 pause 下真正的思考+操作时间。
- `actionsPerTurn`：每回合开始时生效（上一暂停期下达）的 B 命令数；按请求的 `turn+1` 归到下一回合。
- `emptyCombatTurnRatio`：交战期内 0 条 B 的回合占比。
- `engage`/`lowhp` 额外给 `turnLag`。
- pause 下"底层命令 tick 间隔"无意义：同一回合的暂停命令都在下一回合首 tick 生效，文档与报告均标注。

---

## 8. 归因与匹配规则

**gateway ↔ replay（`mapping`）**

- 只匹配 B 请求，跳过 batch 子请求（按 `parent`）。
- 条件：`kind` 相同 + 单位集合完全相同 + `0 ≤ tick差 ≤ 4`。
- 同一底层命令被两个请求竞争 → 都记 `ambiguous`；没有唯一候选 → `unmatched`。**不按最近时间强行归属。**
- 只允许时间上单调递增的分配（后匹配到的索引必须大于前一个），避免"倒着认领"。
- `status` 不是 `submitted`/`partial` 的请求不参与匹配。

**为什么匹配窗口是 4 tick**：网关受理与 replay 生效之间隔着一次游戏线程交接；正常 1~2 tick，
4 tick 是留了余量的上限。更宽会把相邻命令误认成同一条。

**native authority（越权审计）**

- 每个进入 replay 的命令都必须能对应一条授权记录；对不上的写 `diagnostics: severe/native_authority_mismatch`。
- 违规请求不进入 replay，也不改变游戏状态。

---

## 9. 可比性与基线

- 先看 `outcome`，再在**同一 `task+seed`、同一 `track`** 内比较 T1（`delta`、lag、fire 等）。
- `win` 优先；`timeout` 表示"没打完"，`loss` 表示"被打光"。**不要把 timeout 当成高于 loss**：
  一个躲着不打的 timeout 不应排在认真交战但 loss 的前面。报告里 timeout 单列。
- realtime 与 pause 不能同榜：命令到达 tick、回合量化、思考墙钟口径都不同。
- 人类基线：key 是 `<task>:<seed>`，每个组合要有自己的人类 run（登记在 `baselines/index.json`）。
  只有 T1 可比。pause 的 Agent 与 realtime 的人类相比时，`cadence`/`turns`/`thinkingWallMs` 不可比，
  `outcome`/`hpLossFractions`/`ENGAGE_LAG`/`LOWHP_LAG`/`FIRE_UPTIME` 可参考（仍受回合量化影响）。
- 人类只有 realtime 1×，且 GUI 人类流程**未实跑验证**；当前没有强制禁用 GUI 暂停/变速。

---

## 10. 阈值与可配置项

| 项 | 默认 | 说明 |
|---|---:|---|
| `lateMs` | 1000 | ENGAGE/LOWHP 的 LATE 阈值；pause 单回合 960ms，单回合响应必为 ANSWERED |
| `fireTauMs` | 1000 | 无武器状态时的开火推断窗口 |
| idle 判定 | 2000ms | 持续无目标/未开火 |
| 交战距离 | 240 世界单位 | 12 格，中心距 |
| 快照间隔 | 250 game-ms | `snapshots.jsonl` |
| tick 长度 | 16 game-ms | `tickDurationMs`/`gameMsPerTick` |
| gateway 匹配窗口 | 4 tick | `match_gateway(tick_window=4)` |
| 击杀 tick 容差 | ≤2 tick | 每杀单独比；身份/顺序/终态必须一致 |

`evaluate.py --metrics-config <json>` 只覆盖 `lateMs`/`fireTauMs`；匹配窗口与快照间隔是代码常量（改需改 `common/`）。
所有阈值都是从 11 个样本的经验值，**换批次前应重新标定**。

---

## 11. 已知局限（不声称什么）

- 没有总分，也没有跨指标归一化；不同 tier 的指标不能相加。
- `shieldLossFractions` 是净损失，被护盾回复污染；"承受的护盾伤害"只能从 `events.jsonl` 累积（未做成指标）。
- `IDLE_IN_COMBAT`、`FIRE_UPTIME` 受 250ms 快照粒度限制；`FIRE_UPTIME` 的武器状态判定还依赖
  `warmupFrames`/`cooldownFrames` 在采样点非零。
- T2 的 `thinkingWallMs` 只覆盖 A→B；pause 的真实思考看 T3。
- `enemyWeaponCycles` 是理论上限，不是实测火力。
- 人类基线只有 realtime 1×，且 GUI 人类流程未验证。
- `delivery.jsonl` 尚未进任何指标，只有人工诊断价值。
- `liveSnapshots=true` 的 run 没有独立复算，`evidence.verified=false`，不能与 re-sim run 混在一起比较。

---

## 12. 新增一个指标

1. 在 `metrics/` 放一个 `.py`，导出 `ID`、`TIER`、`compute(run)`；返回值必须带 `status`。
2. `run` 提供：`meta`、`result`、`gateway`、`requests()`、`actions`、`snapshots`、`events`、`playback`、
   `self_ids`/`enemy_ids`、`kills`、`engage_ms`、`state_at(ms)`、`unit_at(id,ms)`、`intervals()`。
3. 只读证据，不参与游戏；异常自己抛，`evaluate.py` 会包成 `ERROR`。
4. 若指标对人类也可算，标 `T1`；依赖 gateway 标 `T2`；依赖 turn lifecycle 标 `T3`。
5. 在 `test/test_metrics.py` 里加一个手算可核的 fixture。
