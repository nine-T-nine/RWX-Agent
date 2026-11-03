# CASE3-Transport 评价材料与指标定义

本文件回答两件事：**一局打完能拿到哪些材料**，以及**每个指标是怎么从这些材料算出来的**。
怎么启动、场景怎么布、完整评测流程，见 [README.md](README.md)。

引擎只记录事实，外部 `evaluate.py` 加载 `metrics/*.py` 插件离线计算，再调用 `score.py` 产出**独立的** `score`
扣分文档；指标本身不设加权总分（评分口径见 §13）。
核心问题是 Agent 是否理解**生产—装载—运输—卸载**的任务工期与并行关系，而不是它是否报告了一个 ETA。

`schema = rwx-micro-transport-metrics/1`；每个 metric 含 `tier`/`sources`/`value`，`value.status` 为 `OK/N/A/ERROR`。

---

## 1. 这个 CASE 能拿到什么

**能**

- 任务结果：`success/defeat/timeout/error`、目标首次达成时刻、运输机存活与终态、剩余资金。
- 生产：逐订单的受理/开工/完成、排队等待、工厂占用与并行度、按类型的完成里程碑。
- 投递：逐单位的"生产完成 → 首次装载 → 卸载 → 进入目标岛"时间线，逐类型送达率与缺口补齐时刻。
- 运力：逐运输机的真实席位占用、峰值装载、航次、空载移动、货舱变化。
- 风险：运输机受击次数、HP 损失、受击位置与最近防空塔距离。
- 阶段：生产/装载/航行/卸载/交付各阶段跨度与重叠时间。
- 时序（T2）：A→B、B→下一个 A、决策周期。
- 合规：违规次数与类型。

**不能**

- **没有独立复算**：本 CASE 不跑 re-sim；证据是 live 引擎记录 + replay 核对底层命令。
- 不能获得"模型内部预计完成时间"：Agent 不提供 forecast/ETA，评测只能从 AB 序列与真实生命周期推断其隐式调度。
- 攻击来源不可确认：运输机受伤事件里**没有攻击者 id**，`anti_air_exposure` 只能按位置做最近塔推断，不能声称"被哪座塔打"。
- 不比较跨 `task+level+seed` 的绝对时间。
- 运输机几何射程不等于"正在被攻击"：只有真实 damage 事件才算暴露。

---

## 2. 产物目录

```
<run>/
  meta.json              布景完成时写（岛格集合、目标组成、工厂/提取器/运输机/防空塔、预算）
  gateway.jsonl          薄网关：请求行 + 生命周期行
  result.json            authoritative success/defeat/timeout/error
  match.replay           自动录像（地图为程序生成时另有 map.tmx）
  delivery.jsonl         投递链路延迟（env/command/deliver/warning）
  violations.jsonl       执行前被拒的越权请求
  replay/                离线解析：match.summary.txt / match.txt / match.jsonl
  engine/
    production.jsonl     订单受理、原生命令、开工、完成里程碑  ★
    transport.jsonl      装载、货舱变化、移动、卸载、运输机受伤 ★
    goal.jsonl           逐类型目标岛计数变化与首次达成      ★
  eval/
    metrics.json         9 个指标 + diagnostics + evidence
    report.txt           与 metrics.json 同内容的纯文本
    report.html          同上，包一层 <pre>
```

**没有** `engine/snapshots.jsonl`、`engine/events.jsonl`、`engine/playback.json`：本 CASE 不做 re-sim。
运输机受伤与货舱变化合并在 `transport.jsonl`（`kind=damage` / `kind=cargo`），**不再**单独产出 `engine/damage.jsonl`。

---

## 3. 材料清单

### 3.1 meta.json

通用字段同 [../README.md](../README.md) 的证据契约。本 CASE 特有：

| 字段 | 含义 |
|---|---|
| `type` / `level` | 由 task 解出（1..4 / 1..3） |
| `mapSize` / `tileSize` | 130 格 / 20 世界单位 |
| `fogMode` | `1` = 一次性迷雾（开局未探索、视野揭示、不再长回） |
| `targetIslandTiles[]` / `baseIslandTiles[]` | 岛格集合（`index = y*130 + x`）——**布景真值**，目标判定用它 |
| `targetIslandArea` / `baseIslandArea` | 格数（目标 550..675，基地 700..800） |
| `targetIslandBounds` | 见 §11 已知问题：当前是**基地岛**的包围盒 |
| `requirements[{type,required}]` | 目标组成；`required = 每级数量 × level` |
| `initialCredits` | 2000 / 4000 / 4000 / 20000（type 1..4） |
| `transportType` / `transportCapacity` | `dropship`/4（type 1..3）、`experimentalDropship`/12（type 4） |
| `factories[]` | `{id,alias,team,type,x,y,tileX,tileY,upgradeLevel}`；T2 工厂用原版类型 + `upgradeLevel:2` 表示 |
| `extractors[]` | `extractorT3_overclocked` × 5 或 10（矿区，只提供收入，不参与指标） |
| `transports[]` | `{id,alias,team,type,x,y,tileX,tileY}` |
| `antiAirTurrets[]` | `{id,alias,team,type,x,y,tileX,tileY,coastDepth,facing}`；**仅评测方可读** |
| `units[]` | 以上全部单位的并集（通用字段） |

`transportSlotsNeeded` 若出现在 meta，是每种单位的真实席位权重（见 §10）。

### 3.2 gateway.jsonl

请求行字段同 CASE1。本 CASE 只跑 realtime，`turn` 不出现。状态 `submitted`/`invalid_request`/`rejected`/`error`。
briefing 阶段有 `begin`（本 CASE 是 `startsRunning()`，进入即 1× 运行，无 begin/pause/step）。

### 3.3 result.json

`outcome`、`endReason`、`task`、`tier`、`gameMs`、`tick`、`wallMs`、`remainingGameMs`、`remainingWallMs`、
`credits`、`incomePerSecond`、`transportAliveCount`、`transportCount`、`targetReached`、`goalReached`、
`goalReachedGameMs`、`goalReachedWallMs`、`deliveredCounts`、`producedCounts`、`units`、`self`、`enemy`、`kills`、`turns`。

- `deliveredCounts` / `producedCounts` 是按类型的**计数**（不是列表）。
- `goalReachedGameMs` 由引擎判定首次满足，与 Agent 何时观察无关。

### 3.4 engine/production.jsonl

| `kind` | 行 | 字段 |
|---|---|---|
| `order` + `event=accepted` | 订单受理 | `requestId,factoryId,unitType,count,actionId,cost,credits,tick,gameMs,wallMs` |
| `order` + `event=started` | 开始生产 | `requestId,factoryId?,unitType,credits,tick,gameMs,wallMs` |
| `order` + `event=finished` | **逐单完成** | `requestId,factoryId`（工厂队列的活动项换人时写，确认该单真的产出了单位） |
| `order` + `event=completed` | 完成里程碑 | `unitType,count,tick,gameMs,wallMs` |
| `native` | 原生命令录制关联 | `requestId,nativeIndex,actor,actionId,...` |

`cost` 是该单位的真实价格，`credits` 是**写该行时**的阵营余额（`accepted` 为扣款前）。
`finished` 是逐订单的完成事实（按工厂队列活动项的身份变化检测），评分用它确定"工厂何时空出来"。
`--count N` 一次下 N 条原生命令，因此同一 `requestId` 会有 N 条 `started` 与 N 条 `finished`（每个单位一条）。

**完成里程碑（`completed`）按单位类型累计（当前存活数），不是"哪个工厂产了哪台"。** 需要逐单完成时看
`finished`，不要从 `completed` 反推工厂归属。

### 3.5 engine/transport.jsonl

| `kind` | 字段 | 含义 |
|---|---|---|
| `load` | `requestId,event="issued",transportId,unitIds[],usedSlots,capacity,tick,gameMs,wallMs` | 装载指令下发（**不是**"已进入货舱"） |
| `cargo` | `transportId,cargo[],delta,tick,gameMs,wallMs` | 货舱成员变化的真实快照；`delta` 为变化量 |
| `move` | `requestId,event="issued",transportId,x,y,tick,gameMs,wallMs` | 移动指令下发 |
| `unload` | `requestId,event="issued",transportId,...` | 卸载动作下发 |
| `flight` | `transportId,event="takeoff"\|"landed",posZ,x,y,tick,gameMs,wallMs` | **真实起降跳变**（`posZ` 跨越 4.0 的空中/落地状态切换）；评分用它划分地面空闲窗口 |
| `damage` | `transportId,delta,hp,...`（可能带 `x,y`） | 运输机受击；**没有攻击者 id** |

- 判定"单位真的上船了"只能靠 `cargo` 序列，不能靠 `load` 回执。
- `unload` 是运输机的 unload **action**（`setActionId`），开启后停在非液体地形每 30 帧卸一个。

### 3.6 engine/goal.jsonl

| `kind` | 字段 |
|---|---|
| `delivered` | `unitType,count,required,tick,gameMs,wallMs`（计数变化时写） |
| `reached` | `tick,gameMs,wallMs`（首次满足全部类型） |

**计数口径**：目标岛草地格集合内、**存活、未装载、team 0** 的陆军单位。在货舱里、在基地岛、在岩浆上、已阵亡的都不算。

### 3.7 violations.jsonl

`violationId`、`requestId`、`violationType`、`reason`、`command`、`tick`、`gameMs`、`wallMs`、`rejected`。
违规不进 replay、不改状态。

### 3.8 delivery.jsonl

同 CASE1 §3.5：`env` / `command` / `deliver` / `warning`。当前没有指标消费。

---

## 4. 时间与时基

| 量 | 定义 | 坑 |
|---|---|---|
| `tick` | 引擎模拟步 | 1 tick = **16** game-ms |
| `gameMs` | 引擎时钟 | `tick × 16` |
| `wallMs` | 墙钟 | 全 run 同一个 `System.nanoTime` 原点（**包含加载**） |
| `beginWallMs` | 任务起点 | 本 CASE 进入即运行 |
| 目标完成 | 引擎首次满足的 tick | 不是 Agent 观察到成功的时刻 |

- 游戏上限按 type 为 900/1200/1500/1800 s；墙钟 = 游戏 + 120 s；`startTimeoutSeconds` = 600 s。
- Replay 只有 tick、没有原生墙钟；native 事件在实际 `ReplayWriter` 插入处附加同源 `wallMs`，解析时必须核对 tick 与签名。

---

## 5. metrics.json

| 字段 | 含义 |
|---|---|
| `schema` | `rwx-micro-transport-metrics/1` |
| `run` | `case`、`task`、`seed`、`track`、`mode` |
| `metrics` | 9 个插件，每项 `{tier, sources, value}` |
| `score` | 独立扣分文档（`case3-score-v1`）：`baseline`、`deduction`、`total`、5 项封顶扣分与明细，见 §13 |
| `diagnostics` | `severe/engine_error`、`severe/native_authority_mismatch` 等 |
| `evidence` | `source`（固定字符串）、`gatewayRequests`、`productionEvents`、`transportEvents`、`goalEvents`、`violations` |

`report.txt` 与 `report.html` 都是 `metrics.json` 的包装（HTML 是 `<pre>` 里的同一份 JSON），
**不是**渲染过的图表；要做图表自己读 `metrics.json`。

---

## 6. 指标清单

| ID | tier | 一句话 | 来源 |
|---|---|---|---|
| `outcome` | T1 | 结局、目标达成时刻、运输机存活、逐类型送达率 | result + meta + goal |
| `production_lifecycle` | T1 | 逐订单受理/开工、排队等待、工厂占用、并行度 | production + gateway + meta |
| `delivery` | T1 | 逐单位装载/卸载时间线、逐类型送达率与里程碑 | transport + goal + result + meta |
| `transport_utilization` | T1 | 真实席位占用、装载率、空载移动、航次 | transport + meta + gateway |
| `transport_damage` | T1 | 运输机受击、HP 损失、受击间隔 | transport + meta + result |
| `anti_air_exposure` | T2 | 受击点与最近防空塔的距离（位置推断） | transport + meta |
| `mission_phases` | T2 | 生产/装载/航行/卸载/交付阶段跨度与重叠 | production + transport + goal |
| `ab_timing` | T2 | A→B、B→下一个 A、决策周期 | gateway |
| `violations` | T1 | 违规次数与类型、被拒请求数 | violations + gateway + meta |

T1/T2 的划分在本 CASE 表示"是否只依赖引擎事实"（T1 不依赖网关时序），不是"能不能和人类比"。

---

## 7. 指标定义

### 7.1 outcome

`outcome`、`endReason`、`task`、`seed`、`gameMs`、`wallMs`、`goalReachedGameMs`、`goalReachedWallMs`、
`transportAliveCount`、`transportCount`、`credits`、`requirements`、`delivered`、`produced`、`firstReachedTick`、`goalEvents`。

- `deliveryRate[type] = delivered[type] / required[type]`（`required` 为 0 时为 `null`）。
- `success`：目标岛上的存活、未装载我方陆军首次逐类型达到目标数量。
- `defeat`：全部本局运输机阵亡。
- `timeout`：游戏或墙钟预算先到。`error`：引擎/场景异常。

### 7.2 production_lifecycle

`orders[]`：`requestId`、`factoryId`、`unitType`、`count`、`acceptedGameMs`、`acceptedWallMs`、
`startedGameMs`、`queueWaitGameMs`（= 开工 − 受理）。

`completions[]`：`unitType`、`count`、`gameMs`、`wallMs`（按类型累计的完成里程碑）。

`totals`：`orders`、`started`、`completed`、`factories`。
`perFactory`：每个工厂接了多少单。
`queueWait`：`n/mean/median/p50/p90/max/outliers/samples`。
`parallelism`：`ordersPerFactory`、`ordersPerMinute`（用 `result.gameMs` 作分母）。

**坑**：`orders[]` 里没有"完成时刻"——引擎不猜订单↔单位对应；要算单笔工期只能看 `completions` 的累计曲线。

### 7.3 delivery

`types[]`：`unitType`、`required`、`produced`、`delivered`、`firstDeliveryGameMs`、`lastDeliveryGameMs`、
`milestones[(gameMs,count)]`。

`units[]`：`unitId`、`transportId`、`loadedGameMs`、`unloadedGameMs`（来自真实 `cargo` 变化，不是指令回执）。

`totals`：`unitsLoaded`、`unitsUnloaded`、`unitsNeverLoaded`。

**坑**：`unitsNeverLoaded` 包含"从未上船"和"生产后一直没被记录到货舱"两种情况；
`unloadedGameMs` 是"离开货舱"的时刻，不等于"踏上目标岛"。

### 7.4 transport_utilization

`transports[]`：`transportId`、`type`、`capacity`、`loadCommands`、`moveCommands`、`unloadCommands`、
`peakUnits`、`peakSlots`、`fillRate`（= 峰值席位 / 容量）、`cargoEvents`。

`totals`：`loadCommands`、`moveCommands`、`unloadCommands`、`emptyMoves`。
`cargoDelta`：货舱变化量的分布。

**空载移动判定**：一条 `move` 下发时，若该运输机当时的货舱为空，就记一次 `emptyMoves`。
席位按引擎真实权重算（`transportSlotsNeeded`），**不假设每单位占 1 席**。

### 7.5 transport_damage

`transports[]`：`transportId`、`type`、`maxHp`、受击次数、累计 HP 损失、HP 时间序列、是否阵亡。
`totals`：`hits`、`hpLost`、`alive`、`count`。
`hitSpacing`：相邻两次受击的间隔分布。

**攻击来源不可确认时写 `unknown`，不按最近塔猜测。** 位置在 `damage` 行里带有 `x,y` 时才可做几何推断（见 7.6）。

### 7.6 anti_air_exposure

- 只统计**真实 damage 事件**，不按几何射程推断"正在受攻击"。
- 每条受击行给 `transportId`、`gameMs`、`delta`、`nearestTurret`、`nearestDistance`。
- `inferredRange` = 250.0，来自 `assets/units/turrets/turret_antiair.ini`（写死在 `TURRET_RANGE`）。
- 没有防空塔的任务（type 4）→ `N/A`，理由 `task has no anti-air turrets`。
- 有塔但**没有一次受击** → `hits=0`、`hpLost=0`，note 明确写"没有受击，不能从几何声称暴露"。

**这是 T2**：它用 `meta.antiAirTurrets` 的私有坐标，只做离线归因，不向 Agent 反馈。

### 7.7 mission_phases

`phases`：`production`、`loading`、`transit`、`unload`、`delivery`，各自 `{startGameMs,endGameMs,durationMs}`。

- 一个阶段的起止取该阶段相关事件时间戳的最小/最大值（没有事件时该阶段缺省）。
- `productionToFirstLoad`：最后一个完成里程碑 → 首次装载的间隔。
- `firstMoveGameMs`、`productionOverlapMs`（生产仍在进行、运输机已在跑的重叠时长）、`reachedGameMs`、`orders`。
- 阶段重叠**如实报告**，不做归并。

**坑**：阶段无法可靠识别时该阶段缺省，不用固定工作量推算。

### 7.8 ab_timing（T2）

仅对 `status=submitted` 的 A/B 排序：

- **A→B**：B 之前最后一条已回复 A 到 B 接收。
- **B→下一个 A**：B 回复到下一条 A 接收。
- **B→B / A→A**：相邻同类。
- **decisionCycles**：每个 B 关联之前最后 A 与之后首 A；缺一不补造。

每类 `n/mean/median/p50/p90/max/outliers/samples`；分位数线性插值；首末空白记 `null`。

**A→B 包含模型推理、Runtime、工具调度**，不等于纯模型思考时间；`B→下一个 A` 也不必然表示在等这条指令完成。

### 7.9 violations

`count`、`rows`、`rejectedRequests`、`byType`。

- 违规请求不进入 replay，也不改变游戏状态。
- 发现未经授权的原生命令进入 replay → `severe/native_authority_mismatch`。

---

## 8. 归因规则

- 订单 ↔ 原生命令：优先用 `nativeIndex`（`ReplayWriter` 插入顺序）+ `tick` + `actor` + 动作签名；
  索引签名不一致、重复占用索引、候选不唯一分别判 `unmatched`/`ambiguous`。**没有强行最近匹配。**
- 单位 ↔ 运输机：只认 `cargo` 序列的成员变化，不认 `load` 回执。
- 目标达成：只认引擎 `goal.reached` / `result.goalReached*`，不认 Agent 观察时刻。
- 伤害归因：`damage` 行没有攻击者 id，写 `unknown`。

---

## 9. 可比性与基线

- 只比较**同 `task+level+seed`** 的 Agent 与对应 type 的 Greedy 基线：完成率、总耗时、关键路径、
  生产/运输重叠、平均装载率、空载航次、运输机 HP 损失、送达率、AB 时间分布。
- 不合并总分；不同 `type` 的绝对时间不可比（目标数量与工厂量级都不同）。
- 没有人类基线（本 CASE 未建立 replay-only 路径）。

---

## 10. 阈值与可配置项

| 项 | 值 | 来源 |
|---|---:|---|
| 地图 | 130 × 130 格，20 单位/格 | `Case3Map` |
| 目标岛面积 | 550..675 格 | 布景二分半径 |
| 基地岛面积 | 700..800 格 | 同上 |
| 防空塔射程 | 250 世界单位（12.5 格） | `turret_antiair.ini`，写死在 `anti_air_exposure.TURRET_RANGE` |
| 装载席位 | `dropship` 4、`experimentalDropship` 12 | `transportCapacity` |
| 席位权重 | mechFlame/mechLightning 2、experimentalTank 5、其余 1 | `BaseUnit.getTransportSlotsNeeded()` |
| 卸载节流 | 每 30 帧卸一个 | `Dropship` unload action |
| 离群判定 | `> Q3 + 1.5×IQR` | `_shared.stats` |
| 游戏上限 | 900/1200/1500/1800 s | `Case3Transport.spec` |
| 墙钟预算 | 游戏 + 120 s | 同上 |

游戏时间上限按 type 执行；`startTimeoutSeconds=600` 是"没有 Agent 接入"的上限，不是任务时间。

---

## 11. 已知问题与局限（不声称什么）

- **`meta.targetIslandBounds` 当前记录的是基地岛的包围盒**（布景代码用同一组 `min/max` 布置工厂与提取器）。
  判断目标岛范围请用 `targetIslandTiles` 自行求包围盒，不要用这个字段。
- 没有独立复算（`evidence.independentResimulation` 在本 CASE 不存在这一项，`evidence.source` 只是字符串说明）。
- 运输机受伤**没有攻击者 id**，`anti_air_exposure` 只能做最近塔的位置推断。
- `production_lifecycle` 的 `completions[]` 仍是按类型累计；逐单完成看 `production.jsonl` 的 `finished` 行。
- `unloadedGameMs` 是离开货舱，不等于进入目标岛；单位可能在岩浆上被卸下后死亡。
- **运输机在目标岛上空被击落时，货舱单位会有一过性 `delivered` 计数**：机身移除的那一 tick，货舱单位短暂满足
  “存活、未在货舱、位于目标岛”，下一 tick 随之消失（2026-11-03 迷雾实跑观察到 3 heavyTank + 1 plasmaTank 的一过性行）。
  目标判定与 `goal.jsonl` 读的是同一份内存状态，所以极端情况下可能在那一 tick 误判 `success`；目前未修。
- 生产上限按“已生产数”计：单位在交付前或交付后损失后，`rwx-micro-3-produce` 会以 `exceeds_goal_gap` 拒绝补产，
  迷雾下损失一次就可能再也无法达标。
- `delivery.unitsNeverLoaded` 不做原因区分。
- `report.html` 不是渲染报告，只是 JSON 的 `<pre>`。
- `delivery.jsonl` 未进任何指标。

---

## 12. 新增一个指标

1. `metrics/` 下放 `.py`，导出 `ID`、`TIER`、`SOURCES`、`compute(run)`（以 `_` 开头的文件会被跳过）。
2. `run`（`Case3Run`）提供：`meta`、`result`、`gateway`、`requests()`、`submitted()`、`production`、`transport`、
   `goal`、`violations`、`requirements`、`capacity`、`transport_meta`、`turrets`，
   以及 `orders()/started()/completed()/loads()/moves()/unloads()/damages()/cargo_rows()/cargo_timeline()/
   unit_history()/reached()/delivered_series()/delivered()/produced()`。
3. `_shared.py` 提供 `stats`、`quantile`、`slots`。
4. 插件异常由 `evaluate.py` 包成 `ERROR`，不影响其他指标。
5. 冒烟：`python -B evaluate/micro/CASE3-Transport/test/smoke.py`（或按该文件的入口跑）。

---

## 13. 评分（score）

`evaluate.py` 在写指标的同时调用 `score.py`，产出**独立**的 `score` 文档（`version = case3-score-v1`）：
基线 100，五项扣分各自先裁到 `[0,cap]`、保留 1 位小数再求和，`total = max(0, 100 − Σ)`。
指标与评分分开：指标只陈述事实，评分只消费同一份证据。

| 项 | 上限 | 口径 |
|---|---:|---|
| `action_delay` 行动延迟 | 40 | 每条**实际生效**的生产订单，从受理时刻 `B` 往回找"资金足够 `cost` **且工厂已空闲**"的最早时刻；窗口按 `[0,3](3,10](10,25](25,45](45,?)` 分档，`K = Σ(档占比 × 权重)`（`0/3/6/10/18`），`S = K×50` |
| `irrelevant` 无关行为 | 30 | 违规请求 ×7.5；`invalid_request`/`error` 请求 ×4.5；非返航的空舱移动、空舱卸载 ×1.8 |
| `transport_loss` 运输机损失 | 30 | `K1×10 + K2×20`，`K1 = 1 − Σ存活HP/Σ最大HP`，`K2 = 被摧毁数/总数` |
| `transport_idle` 运输机空闲 | 40 | 每个地面窗口 `15×f(秒)`：起飞前（上一次降落/货舱变化 → 起飞）与降落后（降落 → 下一次移动/货舱变化）；`f:<3s=0，(3,25)自然指数{0,0.8}，(25,40)线性{0.8,1}，>40s=1` |
| `duration` 整体时长 | 30 | `T` 取 `baselines.json` 里该 `task` 的实测基线，没有则用按 type 的默认值；`[0.4T,2T]` 内二次函数（`a=-3`，过 `(0.4T,0)` 与 `(2T,30)`），`<0.4T` 扣 0，`>2T` 扣 30 |

### 13.1 口径细节

- **K 不除以最大权重**：草稿写的是"按权重求和"，某一档占比上去后很快顶到上限；上限是唯一的收敛机制。
  想改成归一化只需把 `common/scoring.py` 的 `NORMALIZE_DELAY_COEFFICIENT` 置 `True`（两 CASE 共用）。
- **行动延迟的两个约束**：资金曲线用 `initialCredits + 收入速率 × 时间 − 已开始订单的 cost`（CASE3 提取器
  固定，收入速率取 `result.incomePerSecond` 的标称值，换算 `×1000/640` 每游戏秒）；工厂空闲时刻取同厂
  `finished` 行的 `gameMs`。草稿只写了"资金不足"，但 CASE3 的资金长期充裕，只用资金会让窗口退化成"距开局
  多久"，所以把"工厂真的空出来"一并作为约束，明细里 `refillGameMs` 与 `factoryFreeGameMs` 分开记录。
- **运输机损失**：草稿给的是"剩余HP/最大HP"，作为扣分系数时满血会白扣 10 分，因此 `K1` 取损失比例
  `1 − 剩余/最大`；明细同时保留 `remainingHp`/`maxHp`。
- **空舱移动**：只有目的地落在基地岛包围盒（`baseIslandTiles` 求包围盒，外扩 1 格）之外的才算无关；
  卸完返航不扣分。`irrelevant` 里的失败只统计 **B 类（可执行）请求**，失败的只读观察不算行为；
  同一条失败每次重试都各记一次（草稿按次数统计）。
- **整体时长**：二次函数按**原始秒数**求解 `b/c`（与 `evaluate/scoring-curves.js`、完整对局评分同一约定）。
  `a=-3` 在几百秒的量级上非常陡，等价于在 `0.4T~0.42T` 附近快速从 0 跳到 30；这是形状本身的结果。
  `score.py` 的 `DURATION_QUADRATIC_SPACE` 可切到 `normalized`（把 `[0.4T,2T]` 映射到 `[0,1]` 再套同一个 `a`）
  或 `minutes`（按分钟取横坐标），默认 `raw`；明细里的 `rangeSeconds`/`quadraticA`/`quadraticSpace` 记录实际参数。
- **基线随布景变化**：`baselines.json` 的 `T` 是 2026-11-03 之前"全图已揭示"布景的实测值。CASE3 改为一次性迷雾后
  需按新布景重测；未重测前，`duration` 与旧局不可直接比较。
- **缺证据**：没有 `cost`/`credits`、没有 `flight` 行、没有终态单位 HP 时，该项记 `status = "N/A"`、扣 0，
  理由写进 `score.diagnostics`；不按经验补数。旧（补证据之前）的 run 因此 `action_delay` 与
  `transport_idle` 会是 `N/A`。

### 13.2 读分注意

- `score.items[].raw` 是封顶前的原始分，`points` 是裁到上限后的实际扣分。
- 自然指数在对数横坐标上极凸：`f(mean)` 在远低于上限时几乎为 0，`P(delta)` 同理；这是曲线形状，不是漏算。
- 同一份证据重复离线计算得到相同分数（纯函数）；`case3-score-v1` 不与其它版本混榜。

**人类对局**：本 CASE 拒绝 `mode=human`（`MicroConfig` 走默认 `requiresAgentRealtime()`），启动即报错。
`action_delay`/`irrelevant`/`transport_loss`/`transport_idle`/`duration` **五项都没有人类数据**：
前两项依赖 `gateway.jsonl` 的 B 请求，后三项依赖 live 引擎证据，而人类模式不会产生这一局。
不存在人类基线，也不要用"人类大概会怎样"补数。
