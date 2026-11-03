# CASE4-Retreat 评价材料与指标定义

本文件回答两件事：**一局打完能拿到哪些材料**，以及**每个指标是怎么从这些材料算出来的**。
怎么启动、场景怎么布、完整评测流程，见 [README.md](README.md)。

引擎写事实，外部 `evaluate.py` 加载 `metrics/*.py` 计算指标（不加权），并调用 `score.py` 产出**独立的**
扣分文档 `score`（`case4-score-v1`，见 §13）。重点分析**战损、炮塔摧毁顺序、撤退时机、维修效率、重新投入时间**。

> "超时损失会被放大"在本 CASE 的实现里指：任务越拖延，单位越可能持续暴露在火力下，真实 HP 损失与阵亡风险随时间累积。
> 草稿没有定义额外超时倍率，**metrics 不乘任何惩罚系数**；如以后要加时间惩罚，必须作为独立、公开、可配置的规则。

`schema = rwx-micro-retreat-metrics/1`；每个 metric 含 `tier`/`sources`/`value`，`value.status` 为 `OK/N/A/ERROR`。

---

## 1. 这个 CASE 能拿到什么

**能**

- 任务结果：`success/defeat/timeout/error`、目标是否清空、总耗时、炮塔摧毁数、坦克存活/阵亡、终态 HP。
- 炮塔进度：逐塔的 HP 时间线、受击时刻、攻击者、摧毁时刻、摧毁顺序与间隔。
- 战损：逐坦克的受击次数、HP/护盾损失、首个/末个受击时刻、攻击来源类型、未知来源计数。
- 击杀快照：每摧毁一座塔时我方全员的 HP/护盾/位置快照。
- 维修：正式维修会话（> 5 游戏秒且实际回血）的起止、时长、恢复量、每秒治疗率。
- 撤退响应：受击 → 下一条 `move` 的延迟；维修结束 → 下一次攻击命令的延迟；治疗覆盖率。
- 时序（T2）：A→B、B→下一个 A、决策周期。
- 合规：违规次数与类型。
- 扣分（`score`）：坦克损失、坦克空闲（就绪/滞留窗口）、整体时长三项封顶扣分（§13）。

**能看到的隐藏信息（仅评测方）**：`meta.turrets` 的完整配置（类型、坐标、射程、role）、`fogMode`、
`achievedTurretSpacingTiles`。它们**只用于离线分析**，不能反馈给 Agent。

**不能**

- **没有独立复算**：本 CASE 不跑 re-sim。
- 没有"超时惩罚"：拖延只通过真实战损体现。
- 攻击来源不可确认时只能写 `unknown`，不能按最近炮塔/最近单位猜测。
- `unit-list` 的 JSON 快照**不含护盾**，所以 Agent 与基线都只能用 HP 判断战损；护盾只出现在
  `result.json` 的汇总与 `post_kill_snapshots` 里。
- 不比较跨 `task+level+seed` 的战损绝对值。

---

## 2. 产物目录

```
<run>/
  meta.json                       布景完成时写（炮塔完整配置、维修湾、坦克、区域、迷雾模式）
  gateway.jsonl                   薄网关：请求行 + 生命周期行
  result.json                     authoritative success/defeat/timeout/error
  match.replay                    自动录像（另有 map.tmx）
  delivery.jsonl                  投递链路延迟（env/command/deliver/warning）
  violations.jsonl                执行前被拒的越权请求
  replay/                         离线解析：match.summary.txt / match.txt / match.jsonl
  engine/
    turret_damage.jsonl           每座炮塔的每次 HP 变化            ★
    unit_damage.jsonl             B 命令下发、原生命令关联、坦克受击 ★
    turret_destroyed.jsonl        摧毁事件 + 当时我方全员快照        ★
    repair_sessions.jsonl         正式维修会话（>5 游戏秒且实际回血）★
    tank_state.jsonl              250 game-ms 的坦克就绪/滞留时间线  ★ 评分用
  eval/
    metrics.json                  9 个指标 + 顶层 score（扣分）+ diagnostics + evidence
    report.txt                    与 metrics.json 同内容的纯文本
    report.html                   同上，包一层 <pre>
```

**没有** `engine/snapshots.jsonl`、`engine/events.jsonl`、`engine/playback.json`：本 CASE 不做 re-sim。
`engine/tank_state.jsonl` 只服务 §13 的“坦克空闲”扣分，不是 Agent 可见的情报。

---

## 3. 材料清单

### 3.1 meta.json

通用字段同 [../README.md](../README.md) 的证据契约。本 CASE 特有：

| 字段 | 含义 |
|---|---|
| `type` / `level` | 由 task 解出（1..3 / 1..3） |
| `mapWidth` / `mapHeight` / `tileSize` | 65..69 格边长 / 20 世界单位 |
| `region` | 炮塔区描述：`centreTileX/centreTileY/centreDistanceFromCorner/radiusTiles/radiusMinTiles/radiusMaxTiles`、左上 3/5 矩形 `boxMinX/boxMinY/boxMaxX/boxMaxY/boxWidthTiles/boxHeightTiles/boxMapFraction`、`longRangeBiasDegrees/longRangeRandomShare/spacingLong/spacingShort/spacingCross` |
| `baseCornerTile` | 我方角（右下）的折角点 |
| `achievedCentreDistanceTiles` | 实际最小/最大"塔到种子中心"距离 `{min,max}`（格），都不超过 `region.radiusTiles` |
| `achievedTurretSpacingTiles` | 实际最小间距 `{long,short,overall}`（格）；同型只有 1 座时对应项为 0 |
| `fogMode` | `0` = 无迷雾；`2` = 普通持续迷雾 |
| `turrets[]` | 每座塔：`{id,alias,team,type,x,y,tileX,tileY,jitterX,jitterY,role,distanceFromCorner,angleDegrees,distanceFromCentre,attackRange}` |
| `turretsByType` | `{type: count}`，例如 `{"c_turret_t2_gun":3,"c_turret_t1_lightning":2}` |
| `repairBays[]` | `{id,alias,team,type,x,y,tileX,tileY}` |
| `tanks[]` | `{id,alias,team,type,x,y,tileX,tileY}` |
| `units[]` | 以上全部单位并集 |

- `role` 是 `long`/`short`（远程/近程），**不是** `ring`。指标里的 `turret_progress.turrets[].ring` 目前恒为 `null`。
- `attackRange` 是该塔的实际武器射程（世界单位），1 格 = 20。
- 敌方配置是**评测方私有数据**，不得经 `state`、地图、`unit-list`、`threat-list`、help、错误回执泄露。

### 3.2 gateway.jsonl

请求行字段同 CASE1。本 CASE 只跑 realtime，`turn` 不出现。
生命周期：`first-command`、`begin`（`rwx-micro-4-begin`）、`end`（如 `all-turrets-destroyed`）。

### 3.3 result.json

`outcome`、`endReason`、`task`、`seed`、`gameMs`、`tick`、`wallMs`、`remainingGameMs`、`remainingWallMs`、
`goal{description,reached}`、`goalReached`、`goalReachedGameMs`、`goalReachedWallMs`、
`turretsAlive`、`turretsTotal`、`tanksAlive`、`tanksTotal`、`tanks[{unitId,alive,hp,maxHp,shield,...}]`、
`credits`、`units`、`self`、`enemy`、`kills`、`turns`。

- `goal.description` 是**公开目标描述**（"destroy every enemy turret in the top-left region and keep the tanks repaired"），
  **不含**敌军数量/类型/坐标。
- `goalReached*` 由引擎判定，与 Agent 观察无关。

### 3.4 engine/turret_damage.jsonl

一行一次伤害：

```json
{"victim":1002,"attacker":1008,"attackerType":"experimentalHoverTank",
 "hpBefore":1100.0,"hpAfter":1098.07,"delta":-1.93,
 "shieldBefore":0.0,"shieldAfter":0.0,"x":595.04,"y":1033.46,"victimType":"c_turret_t2_gun",
 "tick":792,"gameMs":12672,"wallMs":15621}
```

- `attacker` / `attackerType` 来自引擎伤害钩子；拿不到时写 `null`（统计进 `unknownAttacker`）。
- `x,y` 是**受害者**坐标。

### 3.5 engine/unit_damage.jsonl

一个文件里有**三种行**，靠 `kind` 区分（注意：伤害行**没有** `kind` 字段）：

| 行 | 字段 | 含义 |
|---|---|---|
| `kind="order"` | `requestId,event="issued",operation("attack"/"attack-move"/"move"/"stop"),units[],target,tick,gameMs,wallMs` | 一条 B 命令实际下发（`retreat` 判定用 `operation=="move"`） |
| `kind="native"` | `requestId,nativeIndex,actor,operation,...` | 原生命令与请求的关联 |
| （无 `kind`） | `victim,attacker,attackerType,hpBefore,hpAfter,delta,shieldBefore,shieldAfter,x,y,victimType,tick,gameMs,wallMs` | 坦克受击 |

**`retreat` 只认 `operation=="move"`**（`repair_effectiveness.RETREAT_ORDERS`）；`attack-move` 不算撤退。

### 3.6 engine/turret_destroyed.jsonl

```json
{"turretId":1002,"turretType":"c_turret_t2_gun","x":595.04,"y":1033.46,
 "tanks":[{"unitId":1007,"alive":true,"hp":3500.0,"maxHp":3500.0,"shield":5000.0,"x":749.5,"y":1111.5}],
 "tick":1008,"gameMs":16128,"wallMs":19243}
```

另有 `{"kind":"reached","tick":...,"gameMs":...}` 一行表示目标首次达成。`destroyed()` 会自动排除这一行。

### 3.7 engine/repair_sessions.jsonl

```json
{"unitId":1007,"repairBayIds":[1005],"startGameMs":... ,"endGameMs":... ,"durationMs":...,
 "startHp":...,"endHp":...,"maxHp":...,"healedHp":...,"reason":"..."}
```

- **只有持续时间 > 5000 game-ms 且实际回血的区间才会写入**；短段与满血段在引擎侧就被丢弃，
  所以 `diagnostics` 里没有"被过滤数量"这个字段——过滤发生在写入前。
- 治疗由维修湾自动发起（每 40 帧挑范围内 HP 最低的我方单位），**不需要玩家命令**；治疗只加 `currentHealth`，不加护盾。
- 采样：引擎对本局 ≤3 辆坦克 + ≤4 座维修湾做**对象级每 tick 采样**，精度 1 tick（16 game-ms），不扫全世界。

### 3.8 violations.jsonl

`violationId`、`requestId`、`violationType`、`reason`、`command`、`tick`、`gameMs`、`wallMs`、`rejected`。

### 3.9 delivery.jsonl

同 CASE1 §3.5：`env` / `command` / `deliver` / `warning`。当前没有指标消费。

### 3.10 engine/tank_state.jsonl（评分证据）

每 250 game-ms 一行，只在 `phase=running` 时写（briefing 与结算后没有采样）：

```json
{"kind":"state","gameMs":250,"tick":15,"wallMs":1500,"tanks":[
  {"unitId":1007,"alias":"T1","x":1150.0,"y":1290.0,"alive":true,"hp":3500.0,"maxHp":3500.0,
   "shield":5000.0,"order":"idle","target":null,"repairBayIds":[1005]}]}
```

- `order` 是引擎当前意图（`idle`/`move`/`attack`/…），`target` 是当前攻击目标 id。
- `repairBayIds` 是**当时确实在该坦克 230 世界单位内的维修湾**（与治疗判定同一 `baysInRange`），
  空数组表示不在任何维修湾范围内。
- 采样只覆盖本局 ≤3 辆坦克，精度 250 game-ms（所有“空闲时长”的下限）。
- 引擎只写事实，不写“就绪/滞留”判定；判定在 `score.py`（§13）。

---

## 4. 时间与时基

| 量 | 定义 | 坑 |
|---|---|---|
| `tick` | 引擎模拟步 | 1 tick = **16** game-ms |
| `gameMs` | 引擎时钟 | `tick × 16` |
| `wallMs` | 墙钟 | 全 run 同一个 `System.nanoTime` 原点（包含加载） |
| 维修会话时长 | `endGameMs − startGameMs` | 精度 1 tick |
| 目标达成 | 引擎首次 `goal.reached` | 不是 Agent 观察到清空的时刻 |

- 游戏上限按 type 为 900/1200/1500 s；墙钟 = 游戏 + 120 s；`startTimeoutSeconds` = 600 s。
- Replay 只有 tick、没有原生墙钟；native 事件在实际 `ReplayWriter` 插入处附加同源 `wallMs`，解析时必须核对 tick 与签名。

---

## 5. metrics.json

| 字段 | 含义 |
|---|---|
| `schema` | `rwx-micro-retreat-metrics/1` |
| `run` | `case`、`task`、`seed`、`track`、`mode` |
| `metrics` | 9 个插件，每项 `{tier, sources, value}` |
| `score` | 独立扣分文档（`case4-score-v1`）：`baseline`、`deduction`、`total`、3 项封顶扣分与明细，见 §13 |
| `diagnostics` | `severe/engine_error`、`severe/native_authority_mismatch`、`severe/score_error` 等 |
| `evidence` | `source`（固定字符串）、`gatewayRequests`、`turretDamage`、`unitDamage`、`destroyed`、`repairSessions`、`tankState`、`violations` |

`report.txt` 与 `report.html` 都是 `metrics.json` 的包装（HTML 是 `<pre>` 里的同一份 JSON），不是渲染图表。

---

## 6. 指标清单

| ID | tier | 一句话 | 来源 |
|---|---|---|---|
| `outcome` | T1 | 结局、目标是否清空、炮塔/坦克存活、终态 | result + meta + turret_destroyed |
| `turret_progress` | T1 | 逐塔 HP 时间线、摧毁顺序与间隔、攻击者 | turret_damage + turret_destroyed + meta |
| `battle_damage` | T1 | 逐坦克受击、HP/护盾损失、存活、来源类型 | unit_damage + result + meta |
| `post_kill_snapshots` | T1 | 每摧毁一座塔时的我方全员快照 | turret_destroyed |
| `repair_sessions` | T1 | 正式维修会话清单与统计 | repair_sessions + meta |
| `repair_effectiveness` | T2 | 受击→撤退、维修结束→再投入、治疗覆盖率 | repair_sessions + unit_damage + gateway |
| `ab_timing` | T2 | A→B、B→下一个 A、决策周期 | gateway |
| `violations` | T1 | 违规次数与类型、被拒请求数 | violations + gateway |

T1/T2 在本 CASE 表示"是否只依赖引擎事实"（T1 不依赖网关时序），不是"能不能和人类比"。

---

## 7. 指标定义

### 7.1 outcome

`outcome`、`endReason`、`task`、`seed`、`gameMs`、`wallMs`、`goalReached`、`goalReachedGameMs`、
`goalReachedWallMs`、`turretsAlive`、`turretsTotal`、`tanksAlive`、`tanksTotal`、`fogMode`、
`firstReachedTick`、`turretsDestroyed`。

- `success`：目标区域内本局全部敌方目标被摧毁（引擎判定）。
- `defeat`：我方坦克全部阵亡。
- `timeout`：游戏或墙钟预算先到。`error`：运行异常。

### 7.2 turret_progress

`turrets[]`：`turretId`、`type`、`ring`（**恒为 null**，见 §11）、`tileX`、`tileY`、`attackRange`、
`hits`、`damageTaken`、`firstHitGameMs`、`destroyedGameMs`、`attackers[]`。

`totals`：`turrets`、`destroyed`、`damaged`。
`destroyOrder`：摧毁顺序（炮塔 id 列表）。
`destroyGapsMs`：相邻两次摧毁的间隔。
`unknownAttacker`：`turret_damage` 中 `attacker==null` 的行数。

**摧毁时刻由引擎死亡/销毁事件确定**，不用 Agent 下一次 `unit-list` 查询的时刻替代。
迷雾内未被发现的塔，证据仍记录其真实状态，但**仅供离线评测**，不能反馈给 Agent。

### 7.3 battle_damage

`tanks[]`：`unitId`、`hits`、`hpLost`、`shieldLost`、`firstHitGameMs`、`lastHitGameMs`、
`alive`、`finalHp`、`attackers[]`（**按类型**）、`unknownSourceHits`。

`totals`：`hits`、`hpLost`、`tanksLost`。
`hitSpacing`：相邻受击的间隔分布。

- `hpLost` 只累加负 delta 的绝对值；`shieldLost` 用 `shieldBefore − shieldAfter` 的正向差。
- 一发光束可能同时扣护盾与 HP，所以 `hpLost` 与 `shieldLost` 之和**不等于**标称单发伤害。

### 7.4 post_kill_snapshots

每座被摧毁的塔一行：`turretId`、`turretType`、`gameMs`、`tick`、`tanksAlive`、`totalHp`、`totalShield`、
`tanks[{unitId,alive,hp,maxHp,shield,x,y}]`。

用于比较**每次击杀后的战损**：`totalHp` 的下降速度就是推进节奏的质量。该快照不向 Agent 暴露。

### 7.5 repair_sessions

`sessions[]`：`unitId`、`repairBayIds`、`startGameMs`、`endGameMs`、`durationMs`、`startHp`、`endHp`、
`maxHp`、`healedHp`、`healPerSecond`（= healedHp / 秒）、`reason`。

`count`、`durations`（`stats`）、`healed`（`stats`）、`totalHealed`。

- 只统计 **> 5000 game-ms 且实际回血**的会话；被过滤的短段不计入 `count`。
- 多个维修湾范围重叠时按同一单位的连续治疗过程合并为一段，`repairBayIds` 保留全部。
- 满血但处于范围内**不算**实际治疗。

### 7.6 repair_effectiveness（T2）

- `hitToRetreat`：对每辆坦克的每次受击，找该坦克其后**第一条 `move` 命令**，取 `move.gameMs − hit.gameMs` 的分布。
  **测到的是"下令"不是"到达"**。
- `repairEndToReengage`：维修会话结束后，该坦克第一条 `attack`/`attack-move` 与 `endGameMs` 的差。
- `healedTotal`、`damageTakenTotal`、`repairCoverage`（= healed / damage，无伤害时为 `null`）、`sessions`。

**坑**：`hitToRetreat` 只在"确有受击且该坦克之后有 `move`"时成立；`attack-move` 不算撤退。
覆盖率 > 1 是可能的（护盾分担了伤害，而治疗只加 HP）。

### 7.7 ab_timing（T2）

仅对 `status=submitted` 的 A/B 排序：A→B、B→下一个 A、B→B、A→A、decisionCycles。
每类 `n/mean/median/p50/p90/max/outliers/samples`；分位数线性插值；首末空白记 `null`。

**A→B 包含模型推理、Runtime、工具调度**，不等于纯模型思考时间。

### 7.8 violations

`count`、`rows`、`rejectedRequests`、`byType`。违规请求不进 replay、不改状态。
发现未授权原生命令进入 replay → `severe/native_authority_mismatch`。

---

## 8. 归因规则

- 炮塔受击：`victim` = 塔 id；`attacker` 来自引擎钩子，缺失写 `unknown`，**不按最近单位猜**。
- 坦克受击：同上；`battle_damage.attackers` 按 `attackerType` 汇总。
- 维修：只认引擎写入的 `repair_sessions.jsonl`，不从位置或 HP 曲线反推。
- 摧毁：只认 `turret_destroyed.jsonl`，不用 `turret_damage` 的最后一行推断。
- 目标达成：只认 `result.goalReached*` / `turret_destroyed.kind=reached`。

---

## 9. 可比性

- 同 `task+level+seed` 比较 Agent 与 `baseline.py`：清空用时、摧毁顺序、单位 HP 损失、阵亡、
  维修次数/总治疗量/维修时长、撤退响应延迟、维修后重入战斗延迟、命令间隔。
- 不同随机种子不混为同一对局比较；不合并总分。
- 没有人类基线。

---

## 10. 阈值与可配置项

| 项 | 值 | 来源 |
|---|---:|---|
| 地图边长 | `[65,70)` 格，20 单位/格 | `Case4Map` |
| 炮塔区 | 左上角与地图左上角重合、边长 3/5 的矩形 | `region.box*`，实际不可越界 |
| 场地椭圆 | 长半轴 8..18 格（由中心到 3/5 矩形边缘的余量决定），短半轴 = 一半，长轴在 `y=-x` | `region.radiusTiles` / `ellipseRatio` / `ellipseAxisDegrees` |
| 炮塔间距 | 远程整组先尝试 10 格；整组候选全部失败才按 10% 放宽到 40%；近程之间 3 格、跨类 3 格 | `Case4Retreat`，实际值记 `achievedTurretSpacingTiles` |
| 远程偏置 | 候选方向 80% 按 225° ± 35° 抽样、20% 均匀抽样；完整组按偏置方向重心投影择优 | `region.longRangeBiasDegrees` / `longRangeRandomShare` |
| 布局版本 | 3（2:1 椭圆场地、地图 `[65,70)`）；每个间距档最多比较 32 组完整远程布局 | `layoutVersion` / `region.longRangePlacement` / `longRangeLayoutTrials` |
| 抖动 | ±0.35 格 | `meta.turrets[].jitterX/Y` |
| 正式维修会话 | **> 5000 game-ms 且实际回血** | 引擎写入侧过滤 |
| 坦克状态采样 | 250 game-ms，仅 running 阶段 | `Case4Session.sampleTanks` |
| 就绪窗口阈值 A | **> 3 s** | `score.IDLE_MIN_SECONDS["ready"]` |
| 滞留窗口阈值 B | **> 8 s** | `score.IDLE_MIN_SECONDS["loiter"]` |
| 空闲权重 | A ×1.5、B ×2，`S=50*(w/T)/R` | `score.IDLE_WEIGHTS` / `IDLE_SCALE` |
| `R`（空闲基线） | 默认 5%；有基线 w/T 时取 5 倍 | `baselines.json.idleRatios` / `IDLE_RATIO_DEFAULT` |
| 整体时长基线 `T` | `baselines.json.seconds[task]` → `defaults[type]` → `0.55 × 游戏上限` | `baselines.json` |
| 时长二次函数 | `a=-3`，横坐标 `normalized`（见 §13.1） | `score.DURATION_QUADRATIC_SPACE` |
| 维修湾治疗半径 | 230 世界单位（11.5 格） | `RepairBay.y()` |
| 治疗速率 | 0.2 HP/帧 ≈ 12 HP/秒 | `RepairBay.c()` |
| 撤退判定 | 只认 `operation=="move"` | `repair_effectiveness.RETREAT_ORDERS` |
| 游戏上限 | 900/1200/1500 s | `Case4Retreat.spec` |
| 墙钟预算 | 游戏 + 120 s | 同上 |

---

## 11. 已知问题与局限（不声称什么）

- **`turret_progress.turrets[].ring` 恒为 `null`**：meta 里的字段名是 `role`（`long`/`short`），
  指标读的是 `ring`。要区分远程/近程请从 `meta.turrets[].role` 或 `attackRange` 判断。
- 没有独立复算。
- 没有超时惩罚倍率；拖延只通过真实战损体现。
- `repair_sessions` 的过滤发生在写入前，`metrics.json` 里没有"被过滤了多少段"。
- `hitToRetreat` 测的是下令时刻，不是到达维修湾的时刻，也不是开始回血的时刻。
- `unit-list` 快照不含护盾，Agent/基线只能用 HP 决策；护盾只出现在 `result` 汇总与 `post_kill_snapshots`。
- 攻击来源缺失时统一 `unknown`，不做几何推断。
- `report.html` 不是渲染报告，只是 JSON 的 `<pre>`。
- `delivery.jsonl` 未进任何指标。
- `tank_state.jsonl` 是后补的评分证据：**2026-11-03 之前**的 run 没有它，“坦克空闲”项记 `N/A`（0 分），
  不要用旧局反推空闲时长。采样精度 250 game-ms，所以 3 s / 8 s 阈值附近的窗口最多有 250 ms 误差。

---

## 12. 新增一个指标

1. `metrics/` 下放 `.py`，导出 `ID`、`TIER`、`SOURCES`、`compute(run)`（`_` 开头的文件跳过）。
2. `run`（`Case4Run`）提供：`meta`、`result`、`gateway`、`requests()`、`submitted()`、
   `turret_damage`、`unit_damage`、`turret_destroyed`、`repairs`、`tank_state`、`violations`、
   `turrets`、`tanks`、`bays`，以及 `hits()`/`orders()`/`destroyed()`/`reached()`/
   `turret_hits(id)`/`tank_hits(id)`/`outcome()`。
3. `_shared.py` 提供 `stats`、`quantile`、`read_json`、`read_jsonl`。
4. 插件异常由 `evaluate.py` 包成 `ERROR`，不影响其他指标。
5. 冒烟：`python -B evaluate/micro/CASE4-Retreat/test/smoke.py`；评分单测：`test/test_score.py`。

---

## 13. 评分（score）

`evaluate.py` 在写指标的同时调用 `score.py`，产出**独立**的 `score` 文档（`version = case4-score-v1`）：
基线 100，三项扣分各自先裁到 `[0,cap]`、保留 1 位小数再求和，`total = max(0, 100 − Σ)`。
指标仍不加权；`outcome` 与 `metrics` 不受评分影响。

| 项 | 上限 | 口径 |
|---|---:|---|
| `tank_loss` 坦克损失 | 20 | `S = K1*30`，`K1` = 被摧毁坦克 / 本局坦克数（2 辆坦克时 1 辆阵亡 = 15，2 辆 = 30 → 封顶 20） |
| `tank_idle` 坦克空闲 | 50 | 就绪窗口 A（在维修湾范围内 + 满血 + idle，**>3 s**）与滞留窗口 B（不在任何维修湾范围内 + idle，**>8 s**）；`w = ΣA×1.5 + ΣB×2`，`T` = 所有坦克存活时间之和，`S = 50×(w/T)/R`；`R` 默认 5%，`baselines.json` 有基线 w/T 时取 **5 倍基线** |
| `duration` 整体时长 | 30 | `T` = `baselines.json.seconds[task]` → `defaults[type]` → `0.55×游戏上限`；实测 level=1 基线：4_1_1 = 50.24 s、4_2_1 = 51.312 s、4_3_1 = 118.944 s；`[0.4T, 2T]` 内二次函数 `a=-3`（`normalized` 横坐标，见下），`<0.4T` 扣 0，`>2T` 扣 30 |

### 13.1 口径细节

- **坦克损失**：草稿的 `K1*30` 配上 20 的上限，2 辆坦克的局里 1 辆阵亡就 15 分、2 辆阵亡 30→20 分；
  “损失 2/3”与“全灭”同分是上限本身的结果。明细同时给 `destroyedIds` 与逐辆 `alive`。
- **就绪 A / 滞留 B 的判定**：只用引擎 `engine/tank_state.jsonl` 的事实字段——
  `alive && order=="idle"`，再用 `repairBayIds` 是否为空区分范围内/外，A 还要求 `hp >= maxHp - 1e-3`。
  维修中（在范围内但没满血）**既不是 A 也不是 B**（治疗本来就不该扣分）；范围外满血/残血都算 B。
- **时间原点**：第一个 `phase=running` 的采样（≈ `rwx-micro-4-begin` 时刻）。briefing 不计时，也不进 T。
  窗口在 `result.gameMs` 处截止；坦克阵亡时用 `result.kills[].tick × tickDurationMs` 截断它的存活时间。
- **阈值是严格大于**：4.0 s 的 A 计数、3.0 s 不计数；9.0 s 的 B 计数、8.0 s 不计数。
  采样 250 game-ms，所以阈值附近的窗口最多有 250 ms 误差。
- **`R` 与饱和**：当前三条基线局（4_1_1、4_2_1、4_3_1）都**没有任何**超过阈值的空闲窗口（w/T = 0），
  `R` 无法由 0 基线归一化，继续用 5%。于是 `S = 1000×(w/T)`：任何超过 5% 的空闲比例都会顶到 50 分。
  这是草稿公式的比例结果，不是漏算；以后有非零的基线 w/T 填进 `baselines.json.idleRatios` 后，
  `R` 会自动变成 5 倍基线（基线本身约扣 10 分）。`detail.RSource` 写明用的是哪一档。
- **level>1 的时长基线**：`seconds` 只按 task 记录，目前只实测了 level=1；`4_x_2` / `4_x_3` 会退到同
  type 的 `defaults[type]`（即 level=1 的时长）。敌军数量增加时这一项会系统性偏严，补跑 level 2/3 前不要用它
  做跨 level 比较。
- **时长横坐标**：默认 `normalized`——把 `[0.4T, 2T]` 映射到 `[0,1]` 再套 `a=-3`。
  若按 **raw 秒**求解，`a=-3` 在 50 秒量级上会把曲线压成“0.42T 处的阶跃”（任何正常局都直接 30 分），
  这一项就失去区分度；CASE3 目前仍是 `raw`。要完全对齐 CASE3 把 `score.DURATION_QUADRATIC_SPACE`
  改成 `"raw"`；`minutes` 也可选。`detail.quadraticSpace` 记录实际取值。
- **缺证据**：没有 `engine/tank_state.jsonl`（2026-11-03 之前的旧局）时 `tank_idle` 记 `N/A`、0 分；
  没有 `result.tanks`/`tanksAlive` 时 `tank_loss` N/A；连 `0.55×游戏上限` 都拿不到时 `duration` N/A。
  N/A 只是“没证据”，不要读成“表现满分”。

### 13.2 读分注意

- `score.items[].raw` 是封顶前的原始分，`points` 是裁到上限后的实际扣分；两者只在超上限时不同。
- `case4-score-v1` **只有扣分项、不含成败**：`timeout`/`defeat` 也可能因为坦克没损失而拿高分，
  必须和 `outcome`、`turret_progress` 一起读。
- 分数与指标分开呈现；同一份证据重复离线计算得到相同分数（纯函数）；换版本不可混榜。

**人类对局**：本 CASE 拒绝 `mode=human`（`MicroConfig` 走默认 `requiresAgentRealtime()`），启动即报错。
`tank_loss`/`tank_idle`/`duration` **三项都没有人类数据**——`tank_idle` 依赖 `engine/tank_state.jsonl`，
`duration` 的基线来自 agent 基线脚本，人类模式不会产生任何一局。不存在人类基线，也不要估算人类分。
