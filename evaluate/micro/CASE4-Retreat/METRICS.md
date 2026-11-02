# CASE4-Retreat 评价材料与指标定义

本文件回答两件事：**一局打完能拿到哪些材料**，以及**每个指标是怎么从这些材料算出来的**。
怎么启动、场景怎么布、完整评测流程，见 [README.md](README.md)。

引擎写事实，外部 `evaluate.py` 加载 `metrics/*.py` 计算指标，不设加权总分。
重点分析**战损、炮塔摧毁顺序、撤退时机、维修效率、重新投入时间**。

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
  eval/
    metrics.json                  9 个指标 + diagnostics + evidence
    report.txt                    与 metrics.json 同内容的纯文本
    report.html                   同上，包一层 <pre>
```

**没有** `engine/snapshots.jsonl`、`engine/events.jsonl`、`engine/playback.json`：本 CASE 不做 re-sim。

---

## 3. 材料清单

### 3.1 meta.json

通用字段同 [../README.md](../README.md) 的证据契约。本 CASE 特有：

| 字段 | 含义 |
|---|---|
| `type` / `level` | 由 task 解出（1..3 / 1..3） |
| `mapWidth` / `mapHeight` / `tileSize` | 75..79 格边长 / 20 世界单位 |
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
| `diagnostics` | `severe/engine_error`、`severe/native_authority_mismatch` 等 |
| `evidence` | `source`（固定字符串）、`gatewayRequests`、`turretDamage`、`unitDamage`、`destroyed`、`repairSessions`、`violations` |

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
| 地图边长 | `[75,80)` 格，20 单位/格 | `Case4Map` |
| 炮塔区 | 左上角与地图左上角重合、边长 3/5 的矩形 | `region.box*`，实际不可越界 |
| 炮塔半径 | 8..18 格（由中心到 3/5 矩形边缘的余量决定） | `region.radiusTiles/radiusMinTiles/radiusMaxTiles` |
| 炮塔间距 | 远程之间 10 格、近程之间 3 格、跨类 3 格；连续失败按 10% 放宽到 40% | `Case4Retreat`，实际值记 `achievedTurretSpacingTiles` |
| 远程偏置 | 225° ± 35°，80% 命中；20% 完全随机 | `region.longRangeBiasDegrees` / `longRangeRandomShare` |
| 抖动 | ±0.35 格 | `meta.turrets[].jitterX/Y` |
| 正式维修会话 | **> 5000 game-ms 且实际回血** | 引擎写入侧过滤 |
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

---

## 12. 新增一个指标

1. `metrics/` 下放 `.py`，导出 `ID`、`TIER`、`SOURCES`、`compute(run)`（`_` 开头的文件跳过）。
2. `run`（`Case4Run`）提供：`meta`、`result`、`gateway`、`requests()`、`submitted()`、
   `turret_damage`、`unit_damage`、`turret_destroyed`、`repairs`、`violations`、
   `turrets`、`tanks`、`bays`，以及 `hits()`/`orders()`/`destroyed()`/`reached()`/
   `turret_hits(id)`/`tank_hits(id)`/`outcome()`。
3. `_shared.py` 提供 `stats`、`quantile`、`read_json`、`read_jsonl`。
4. 插件异常由 `evaluate.py` 包成 `ERROR`，不影响其他指标。
5. 冒烟：`python -B evaluate/micro/CASE4-Retreat/test/smoke.py`。
