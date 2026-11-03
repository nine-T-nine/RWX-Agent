# CASE2-LTE 评价材料与指标定义

本文件回答两件事：**一局打完能拿到哪些材料**，以及**每个指标是怎么从这些材料算出来的**。
怎么启动、场景怎么布、完整评测流程，见 [README.md](README.md)。

引擎只写事实，`evaluate.py` 加载 `metrics/*.py` 插件离线计算，再调用 `score.py` 产出**独立的** `score` 文档；
指标不设加权总分，评分只消费同一份证据（口径见 §14）。
`schema = rwx-micro-lte-metrics/2`；每个 metric 含 `tier`/`sources`/`value`，`value.status` 为 `OK/N/A/ERROR`。

---

## 1. 这个 CASE 能拿到什么

**能**

- 目标达成：`outcome`、`endReason`、目标收入是否达到、达成时刻（引擎侧，与 Agent 何时观察无关）、剩余资金与未用资金。
- 收入曲线：初始 0 与每次实际收入变化（**含下降**），每条变化的时刻、增量、原因、相关建筑。
- 指令生命周期：每条 B 指令从下发到终态的分段耗时（寻路/移动/抵达/预热/施工/排队/升级/等待资金）与真实扣费。
- 时序：A→B、B→下一个 A、B→B、A→A、决策周期；完成指令的"完成后多久才被观察"。
- 合规：违规次数与类型、是否都在执行前被拒、是否有未授权原生命令进入 replay。

**不能**

- **没有独立复算**：本 CASE 不跑 re-sim。生命周期来自 live 引擎记录，replay 只用于核对底层命令。
  因此不能声称"结果经过独立重算"。
- 不能还原"模型内部预计完成时间"：Agent 没有 forecast/ETA 命令，只能从 AB 序列与真实生命周期推断其隐式调度。
- `incomePerSecond` 是**标称值**，不等于实际每游戏秒入账量（见 §4.4）。
- B 受理不代表执行成功；`superseded`/`failed`/`timeout` 都算终态。
- 不比较跨 `task+seed` 的绝对时间。

---

## 2. 产物目录

```
<run>/
  meta.json              布景完成时写（含完整经济表、允许/禁止动作、矿脉、预算）
  gateway.jsonl          薄网关：请求行 + 生命周期行
  result.json            结束时写（authoritative success/timeout/error）
  match.replay           自动录像
  delivery.jsonl         投递链路延迟（env/command/deliver/warning）
  violations.jsonl       执行前被拒的越权请求
  replay/                离线解析：match.summary.txt / match.txt / match.jsonl
  engine/
    lifecycle.jsonl      指令阶段、native 录制关联、收入锚点 ★ 本 CASE 的核心证据
  eval/
    metrics.json         全部指标 + 诊断
    report.txt           与 metrics.json 同内容的纯文本
    report.html          同上，包一层 <pre>
```

**没有** `engine/snapshots.jsonl`、`engine/events.jsonl`、`engine/playback.json`：本 CASE 不做 re-sim。

---

## 3. 材料清单

### 3.1 meta.json（布景事实）

通用字段（`schema`/`case`/`task`/`seed`/`track`/`mode`/`agent`/`map`/`limits`/`tickDurationMs`/
`simulationFrameMs`/`gameMsPerTick`/`nominalTickMs`/`engineVersion`/`commit`/`replay`/`enemyPolicy`/
`liveSnapshots`/`units`/`controlledTeam`/`wallClockOrigin`）同 [../README.md](../README.md) 的证据契约。

本 CASE 特有：

| 字段 | 含义 |
|---|---|
| `builder` | 建造者 id（固定 1000） |
| `initialCredits` | 初始资金（固定 2100） |
| `resourceCount` / `resourceNodes[]` | 矿脉数 Y 与每点 `{resourceId:"R1",tileX,tileY,x,y}`（世界坐标） |
| `tier` / `targetIncomePerSecond` / `targetComposition` | 经济档次 T、目标收入 X、目标组成描述（如 `8 x T3`） |
| `mapSize` / `poissonSpacingTiles` / `centerClearanceTiles` | 地图边长与采样参数 |
| `economy[]` | **完整经济表**：每级 `{level,type,incomePerSecond,buildCost,buildWorkTicks,upgradeActionId,upgradeCost,upgradeWorkTicks,upgradeRequires}` |
| `allowedActionIds` / `forbiddenActionIds` | 只开放 `b_extractorT1`、`extractorT2_0`、`extractorT3_0`；超频/护盾/降级等全部禁止 |
| `builderBuilds` | 原版可建造列表（仅作对照，实际只放行提取器） |
| `builderMoveSpeed` / `builderAcceleration` / `builderWarmupTicks` | 0.8 世界单位/tick、0.04/tick、30 tick |
| `incomeClock{generationPeriodTicks,nominalTicksPerSecond,gameMsPerTick}` | 40 / 60 / 16 |
| `initialIncomePerSecond` / `maximumIncomePerSecond` | 0 / `20×Y` |
| `units[]` | `{id,alias,team,type,x,y}`（布景单位；本 CASE 只有一个建造者） |

**经济表来自当前加载的自定义单位，不是旧 Java `ResourceExtractor` 的 8/12/18。** T3 收入是 **20**，不是 18。

### 3.2 gateway.jsonl

请求行字段同 CASE1（`seq/requestId/wallRecvMs/wallReplyMs/gameMs/tick/phase/turn/class/name/raw/status/error/units/skippedDead/parent`）。
生命周期行 `event` ∈ `first-command`、`begin`、`turn`、`end`、`engine-error`。

本 CASE 只跑 realtime，`turn` 不会出现；`phase` 是 `briefing`/`running`/`ended`。

状态取值：

| `status` | 含义 |
|---|---|
| `submitted` | 受理并已创建原生命令 |
| `invalid_request` | 参数错误或资金不足（如 `insufficient_credits`） |
| `rejected` | 未知/未注册/本 CASE 不允许 |
| `error` | 其他执行前错误 |

### 3.3 result.json（authoritative 结局）

`outcome`（`success|timeout|error`）、`endReason`、`task`、`tier`、`resourceCount`、`targetComposition`、
`targetIncomePerSecond`、`targetReached`、`gameMs`、`tick`、`wallMs`、`remainingGameMs`、`remainingWallMs`、
`economy`、`builderMoveSpeed`、`builderWarmupTicks`、`generationPeriodTicks`、
`initialIncomePerSecond`、`finalIncomePerSecond`、`unusedCredits`、`remainingResources`、
`beginWallMs`、`gameElapsedMs`、`wallElapsedMs`、`targetReachedGameMs`、`targetReachedWallMs`、
`units`、`self`、`enemy`、`credits`、`incomePerSecond`、`turns`、`kills`。

- `targetReachedGameMs/WallMs` 来自引擎 `afterTick`，**不是** Agent 观察到成功的时刻。
- `gameElapsedMs`/`wallElapsedMs` 从 `beginWallMs` 起算；`wallMs` 是相对首条命令的墙钟。

### 3.4 match.replay + replay/

同 CASE1：`.replay` 是引擎真正录制的原生命令流，被拒请求不在其中。`replay/` 由
`evaluate/utils/replay.py` 解析出 `match.summary.txt`/`match.txt`/`match.jsonl`。

本 CASE 的 replay 里只有建造与升级两类命令，`nativeIndex`（`ReplayWriter` 插入顺序，0 起）是
`engine/lifecycle.jsonl` 与 replay 对齐的主键。

### 3.5 engine/lifecycle.jsonl（核心证据）★

三种 `kind`：

**① `instruction`** — 每条 B 指令的阶段流水

```json
{"kind":"instruction","requestId":9,"event":"order_started","operation":"build","actor":1000,
 "resourceId":"R1","buildingId":null,"targetLevel":1,"actionId":"b_extractorT1",
 "cost":700.0,"actualCost":700.0,"credits":1400.0,"tick":58,"gameMs":928,"wallMs":2854}
```

`cost` 是这条指令的标准费用（建造 700 / T1→T2 1400 / T2→T3 4000），`actualCost` 是引擎真实扣款，
`credits` 是**写这一行时**的阵营余额（评分用它作为资金曲线的精确锚点，见 §14）。

`event` 取值：

- 一次性里程碑：`order_started`、`arrived`、`constructing`、`queued`、`upgrading`、`waiting_for_credits`
- 成对阶段：`pathing`/`pathing_end`、`moving`/`moving_end`、`warming_up`/`warming_up_end`、
  `constructing`/`constructing_end`、`queued`/`queued_end`、`upgrading`/`upgrading_end`、
  `waiting_for_credits`/`waiting_for_credits_end`
- 终态：`completed`、`failed`、`cancelled`、`superseded`、`timeout`、`rejected`

**② `native`** — 原生命令的录制关联

```json
{"kind":"native","requestId":9,"nativeIndex":3,"tick":58,"gameMs":928,"wallMs":2854,
 "actor":1000,"operation":"build","buildType":"extractorT1","actionId":"b_extractorT1",
 "resourceId":"R1","credits":2100.0}
```

`nativeIndex` 是命令在 `ReplayWriter` 中的真实插入序号；`operation` 为 `build` 或 `upgrade`；
`credits` 是录制该命令前的余额。

**③ `income`** — 收入锚点

```json
{"kind":"income","tick":1032,"gameMs":16512,"wallMs":18044,"incomePerSecond":8.0,
 "deltaIncome":8.0,"cause":["construction"],"buildingIds":[1001]}
```

- 初始 0 也算一条锚点（`cause` 可能是 `initial`）。
- **收入下降也会记**（`deltaIncome` 为负）。
- 每 tick 读 O(1) 阵营收入缓存，只在变化时写；不每 tick 扫世界。

### 3.6 violations.jsonl

一行一条被拒的越权请求：`violationId`、`requestId`、`violationType`、`reason`、`command`、`tick`、
`gameMs`、`wallMs`、`rejected`。**违规请求不进 replay、不改游戏状态、不影响胜负。**

### 3.7 delivery.jsonl

同 CASE1 §3.5：`env`（环境标定）/ `command`（received→applied）/ `deliver`（produced→delivered）/ `warning`。
当前没有指标消费它，是诊断材料。

---

## 4. 时间与时基

| 量 | 定义 | 坑 |
|---|---|---|
| `tick` | 引擎模拟步 | 1 tick = **16** game-ms（int 截断），不是 16.667 |
| `gameMs` | 引擎时钟 | `tick × 16`；replay 有 `game_time_ms` 时优先用它 |
| `wallMs` | 墙钟 | 所有原始 `wallMs` 共享 run 初始化时的 `System.nanoTime` 原点（**包含加载**） |
| `beginWallMs` | 任务起点 | 第一条 CLI 请求触发预算；`beginWallMs` 触发 `gameElapsedMs` |
| `targetReachedGameMs` | 目标达成 | 引擎 `afterTick` 判定，与 Agent 观察无关 |

- 默认游戏上限 **20 分钟**（1200 s）、墙钟 **1320 s**（留出 1× 追平 20 分钟游戏时间的余量）、无 Agent 接入上限 600 s。
- 结算后两条路径都等待 10 秒再退出（`RWX_MICRO_EXIT_IN 10..1`）。
- Replay 只有 tick、没有原生墙钟；native 事件在实际 `ReplayWriter` 插入处附加同源 `wallMs`，解析时核对 tick 和签名。

### 4.4 `incomePerSecond` 到底是什么意思

与现有游戏 CLI 一致：`team.b(Resource.D) − team.getResourceDrainRate(Resource.D)`。

资源生成周期是 **40 tick**，所以标称 8/12/20 是**每 40 tick 发放一次**：

| 等级 | 标称 | 每墙钟秒（1× ≈ 60 tick/s） | 每游戏秒 |
|---|---:|---:|---:|
| T1 | 8 | ≈ 12 credits | 12.5 |
| T2 | 12 | ≈ 18 credits | 18.75 |
| T3 | 20 | ≈ 30 credits | 31.25 |

**不要把字段名理解成"每 1000 game-ms 入账量"。** 估算资金积累时用上表，不要用标称值。

---

## 5. metrics.json

| 字段 | 含义 |
|---|---|
| `schema` | `rwx-micro-lte-metrics/2` |
| `run` | `case`、`task`、`seed`、`track`、`mode` |
| `metrics` | 7 个插件，每项 `{tier, sources, value}` |
| `score` | 独立扣分文档（`case2-score-v1`）：`baseline`、`deduction`、`total`、3 项封顶扣分与明细，见 §14 |
| `diagnostics` | 本 CASE 自己产出的 + 插件追加的（如 `severe/native_authority_mismatch`、`incomplete_replay`、`missing_replay`） |
| `reference` | 恒为 `null`（本 CASE 不接人类基线） |
| `evidence` | `source`（固定字符串 `live lifecycle + native replay`）、`independentResimulation: false`、`gatewayRequests`、`events`、`nativeCommands` |

**`independentResimulation` 恒为 false**——这是本 CASE 与 CASE1 最重要的差别，不要在报告里写成"已复算"。

---

## 6. 指标清单

| ID | tier | 一句话 | 来源 |
|---|---|---|---|
| `outcome` | T1 | 结局、目标收入达成、剩余资源/未用资金 | engine + meta |
| `income_anchors` | T1 | 收入锚点序列与目标锚点 | engine |
| `instruction_lifecycle` | T1 | 每条 B 指令的阶段耗时、终态、真实扣费、native 匹配 | gateway + engine + replay |
| `violations` | T1 | 违规次数/类型/是否执行前拦截/是否漏进 replay | violations + replay + engine |
| `ab_timing` | T2 | A→B、B→A、B→B、A→A、决策周期 | gateway |
| `agent_timing` | T2 | A/B/C 与状态计数、网关处理耗时、错误/拒绝比例、AB 间隔 | gateway |
| `wake_alignment` | T2 | 完成指令的"完成后多久被观察" | gateway + engine |

T1 表示"只要有引擎证据就能算"（本 CASE 没有人类基线，但 T1/T2 的分层仍保留：T1 不依赖网关时序）。

---

## 7. 指标定义

### 7.1 outcome

`status`：有 `result.json` 为 `OK`，否则 `N/A` + `missing_result`。

字段：`outcome`、`endReason`、`initialIncomePerSecond`、`finalIncomePerSecond`、`targetIncomePerSecond`、
`targetReachedGameMs`、`targetReachedWallMs`、`gameElapsedMs`、`wallElapsedMs`、`remainingResources`、`unusedCredits`。

- `success` 由引擎判定：真实 `incomePerSecond >= targetIncomePerSecond`。
- `timeout`：游戏或墙钟预算先到。
- `unusedCredits`：结束时账上没用掉的资金——它和 `wallElapsedMs` 一起看才有意义（快速达标但剩一堆钱 ≠ 慢但花光）。

### 7.2 income_anchors

- `rows[]`：每条收入锚点加 `index`、`sinceBeginWallMs`、`sincePreviousGameMs`、`sincePreviousWallMs`。
- `targetAnchor`：第一个 `incomePerSecond >= target` 的锚点索引。
- `count`：锚点条数；没有锚点时为 `N/A`。

**坑**：同 tick 完成多个建筑时收入会合并成一条，`buildingIds` 里保留全部；`deltaIncome` 是合并值，
不能摊到单个建筑。未知原因写 `unknown`。

### 7.3 instruction_lifecycle

每条 B 请求一行：

| 字段 | 含义 |
|---|---|
| `requestId` | 网关序号 |
| `issued` | `{gameMs,tick,wallMs}`（`wallMs` = `wallRecvMs`） |
| `events` | 该指令的全部 `instruction` 事件，按写入顺序 |
| `result` | 终态事件名，未终结为 `unresolved` |
| `phases` | `{pathing,moving,arrived,warming_up,constructing,queued,upgrading,waiting_for_credits}` 各阶段累计 `{gameMs,wallMs}` |
| `mapping` | 对应的 native 记录匹配状态（`matched`/`ambiguous`/`unmatched`/`not_submitted`） |
| `issueToCompleteGameMs` / `issueToCompleteWallMs` | 下发 → 终态 |
| `executionGameMs` / `executionWallMs` | `order_started` → 终态 |
| `actualCost` | 终态事件带的真实扣费（引擎扣款前后差，不是资金总差推测） |
| `completed` | 终态事件对象（仅当 `result=completed`） |

汇总：`issueToCompleteWallMs` 的 `n/mean/median/p50/p90/max/outliers/samples`（**只取 completed**）。

- 移动结束（`moving_end`）表示"创建地基进入施工位置"，**不声称已到矿脉中心**。
- `build` 完成必须真实施工进度 ≥1 并使收入生效；`upgrade` 完成必须实际类型变为下一标准等级并使收入生效。
- 等待资金单列（`waiting_for_credits`），包含在端到端区间内。标准升级资金不足在网关就拒了，
  **没有伪造等待队列**；建造缺款由引擎原生重试并记 `waiting_for_credits`。

### 7.4 violations

`count`、`byType`、`rows`、`allRejectedBeforeExecution`、`firstViolationWallMs`、
`unexpectedNativeCount`、`unmatchedMappings`、`violationEnteredReplay`。

- 没有 replay 时整体 `N/A`——**不能声称"没有违规流入"**。
- 任何不能对应授权记录的 replay 命令 → `diagnostics: severe/native_authority_mismatch`。

### 7.5 ab_timing（T2）

仅对 `status=submitted` 的 A/B 请求排序：

- **A→B**：该 B 之前最后一条已回复 A 到 B 接收。
- **B→下一个 A**：B 回复到下一条 A 接收。
- **B→B / A→A**：相邻同类请求接收间隔。
- **decisionCycles**：每个 B 关联之前最后 A 与之后首 A；缺一不补造。

每类输出 `n/mean/median/p50/p90/max/samples/outliers`；分位数线性插值；离群值 > `Q3+1.5×IQR`；
样本保留 request ID；空样本统计为 `null`（不是 0）。

**A→B 包含模型推理、Runtime、工具调度**，不等于纯模型思考时间。

### 7.6 agent_timing（T2）

`counts`（A/B/C）、`statuses`（按 `status` 计数）、`processingWallMs`（网关 `wallReplyMs−wallRecvMs` 的分布）、
`errorRatio`（`invalid_request` 占比）、`rejectedRatio`、`intervals`（= `ab_timing` 的全套结果）。

**B 受理不代表执行成功**：看 `instruction_lifecycle.result`。

### 7.7 wake_alignment（T2）

对每条**已完成**的指令，取该 B 回复之后的首个 A 观察：

- `wakeBeforeCompleteMs`：首个 A 早于完成时刻的提前量。
- `wakeAfterCompleteMs`：完成后首个 A 的延迟。
- 另列 `pollsBeforeComplete`、`bToNextAMs`、`completeFromReplyMs`。

**坑**：结束后自动退出可能没有"完成后的 A"，此时值为 `null`——**不能当 0 延迟**。

---

## 8. 归因与匹配规则

**gateway ↔ native（`instruction_lifecycle.mapping`）**

按以下顺序联合匹配：

1. `nativeIndex`（`ReplayWriter` 真实插入顺序，0 起）必须命中候选；
2. `tick` 必须一致；
3. `actor` 必须一致；
4. 建造：`buildType` + 坐标（与 meta 的 `resourceNodes[].x/y` 比对，容差 0.01）+ `build_count == 1`；
   升级：`actionId` 一致且 replay 行 `type` 为空。

结果：

| 状态 | 含义 |
|---|---|
| `matched` | 唯一且签名一致 |
| `ambiguous` | 候选不唯一，或同一 `nativeIndex` 被多条记录占用 |
| `unmatched` | 索引/签名对不上，或请求本身未被受理 |

**没有强行最近匹配。** 成功受理但没有原生命令必须显示 `unmatched`（例如执行前被新请求覆盖）。

**`unexpectedNativeIndices`**：replay 中存在但没有任何授权记录认领的下标——这是越权流入的直接证据。

---

## 9. 可比性

- 只比较**同 `task+seed`** 的 Agent 与 Greedy 基线；不合并总分。
- 同一份证据重复离线计算得到**相同**指标（纯函数，无时间依赖）。
- `task` 决定 Y（矿脉数）与 T（档次），`seed` 决定地图与矿脉位置；固定 task 的资金、数量和目标不随 seed 变化。
- 没有人类基线（`reference` 恒 `null`）；本 CASE 不设 T3。

---

## 10. 阈值与可配置项

| 项 | 值 | 来源 |
|---|---:|---|
| 游戏上限 | 1200 s | `Case2Lte.taskInfo` |
| 墙钟预算 | 1320 s | 同上（+120 追平余量） |
| 无 Agent 接入上限 | 600 s | `startTimeoutSeconds` |
| 初始资金 | 2100 | 固定 |
| 资源生成周期 | 40 tick | `incomeClock.generationPeriodTicks` |
| 建造/升级工作 tick | T1 建 1000；T1→T2 1666.667；T2→T3 3333.333 | `meta.economy`（速率倒数） |
| 匹配坐标容差 | 0.01 世界单位 | `_shared.match_native` |
| 离群判定 | `> Q3 + 1.5×IQR` | `_shared.stats` |

工作 tick **不含**命令排队、寻路、转向、进入施工距离和建造者 30 tick 预热；实际完成时刻以引擎证据为准。

---

## 11. 已知局限（不声称什么）

- 没有独立 re-sim（`independentResimulation=false`）；生命周期是 live 记录，replay 只用于核对底层命令。
- 不声称 AB 序列能还原显式的 ETA 预测——Agent 没有提供 forecast。
- `A→B` 不是纯模型思考时间；`B→下一个 A` 也不必然表示 Agent 在等这条指令完成。
- 同 tick 多建筑完成的收入是合并值，不虚构每条指令的分摊金额。
- `incomePerSecond` 是标称值，实际入账按 40 tick 周期（§4.4）。
- `delivery.jsonl` 未进任何指标。

---

## 12. 新增一个指标

1. `metrics/` 下放 `.py`，导出 `ID`、`TIER`、`SOURCES`（**本 CASE 的插件声明真实来源**）、`compute(run)`。
2. `run`（`LteRun`）提供：`meta`、`result`、`gateway`、`events`（lifecycle 行）、`violations`、`actions`、`has_replay`、`diagnostics`。
3. `_shared.py` 里有 `stats(samples)`、`timing(run)`、`lifecycle(run)`、`match_native(run)`、`accepted(run)`、`TERMINALS`。
4. 插件异常由 `evaluate.py` 包成 `ERROR`，不会拖垮其他指标。
5. 测试：`python -B -m unittest discover -s evaluate/micro/CASE2-LTE/test -p test_lte.py`。

---

## 13. 附录：引擎事实核验（指标输入的来源）

表里的数字都是指标的分母/分子，所以这里记清楚它们各自从哪来、怎么核实的。旧 Java 实现的数值（8/12/18、
1200/2500）**不适用**。

| 源码 / 方法 | 核实结论 |
|---|---|
| `BuilderUnit.getMoveSpeed/addAvailableActions/e` | `builder`，草地 0.8，13 种建筑含提取器及回收，预热 30 |
| `PlaceBuildingAction` 构造器 | `overrideAndReplace` 把原 `extractor` 建造动作替换为 `b_extractorT1` |
| `assets/units/extractor/*.ini` | 表中费用/速率/分支定义；**T3 收入 20**，不是旧 Java 的 18 |
| `CustomUnitConfigParser.parseAction` / `CustomAction` 构造器 | 动作 ID 来自 convertTo 类型名 + 定义序号 |
| `MapTile` / `OrderableUnit.canPlaceAtCurrentPosition` | `res_pool` 格属性、放置与碰撞占用规则 |
| `OrderableUnit.a(UnitCommand,UnitType,...)` | 创建、放置校验、全额扣款、地基；缺款保留原生重试 |
| `OrderableUnit.updateRepairOrReclaimCommand` / `BaseUnit.r` | 预热后累积施工进度，完成时更新阵营收入统计 |
| `FactoryQueueManager.a(float)` / `CustomUnit` 动作转换 | 建筑队列自主进度；升级转换保持建筑 ID 并更新阵营收入 |
| `PlayerTeam.b/getResourceDrainRate` / `CustomUnit.update` | 标称收入与 40 tick 实际发放口径（§4.4 的依据） |
| `CommandController` / `ReplayEngine.a(Command,int)` / `Command.executeCommand` | 录制前、执行前授权；索引在真实 `ReplayWriter` 插入处记录 |
| `RwxCliTool` / `RwxEngineLocalGameService.issueOnGameThread` | CLI 白名单与通用服务直接入口拦截，含直接姿态修改路径 |
| `LteSession` | 只授权本 Case 创建的命令对象，并复核 actor/动作/类型/目标/队列；直接原生命令也被拒绝 |

边界：任意本地 Java 代码直接篡改引擎内存**不属于**命令权限边界；GUI、人类控制和独立重放复算不是本版
验收对象（窗口只用于观看与结算动画）。

---

## 14. 评分（score）

`evaluate.py` 在写指标的同时调用 `score.py`，产出**独立**的 `score` 文档（`version = case2-score-v1`）：
基线 100，三项扣分各自先裁到 `[0,cap]`、保留 1 位小数再求和，`total = max(0, 100 − Σ)`。
指标只陈述事实，评分只消费同一份证据；两者不要混读。

| 项 | 上限 | 口径 |
|---|---:|---|
| `action_delay` 行动延迟 | 50 | 每条**实际生效**（有 `order_started`）的建造/升级指令，从生效时刻 `B` 往回找"资金足够 `cost` 且执行者空闲"的最早时刻 `i`；窗口 `(B−i)` 按 `[0,3](3,10](10,25](25,45](45,?)` 分档，`K = Σ(档占比 × 权重)`，权重 `0/3/6/10/18`，`S = K×50` |
| `irrelevant` 无关行为 | 30 | 违规请求 ×4；被替换/取消/失败/超时的 B 指令 ×2.5；超出目标组成的多余建造或升级 ×1 |
| `cadence` 底层命令执行间隔 | 50 | 序列 = **相邻 native 命令**的间隔（秒）；`S = 0.2·f(去离群均值) + 0.8·ΣP(离群值−均值)`，`f:[15,60]` 自然指数 `{0,100}`，`P:[25,120]` 自然指数 `{0,100}`、`ΣP` 最多 100 |

### 14.1 口径细节

- **K 不除以最大权重**：草稿写的是"按权重求和"，所以某一档占比一上去就会顶到上限（例如 5 条指令里
  1 条落在 `(3,10]`，`K=0.6`、`S=30`）。上限就是唯一的收敛机制，不要把它当成 [0,1] 的系数。
  想改成归一化只需把 `common/scoring.py` 的 `NORMALIZE_DELAY_COEFFICIENT` 置 `True`（两 CASE 共用）。
- **资金曲线**：新对局的 `instruction`/`native` 行带 `credits`（写该行时的余额），是精确锚点；旧证据没有
  `credits` 时，用 `initialCredits + 收入锚点（标称值 × 1000/640 credits/游戏秒）− 实际扣费` 重建。
  `detail.creditSource` 会写 `recorded credits` 或 `rebuilt from costs + income anchors`。
- **执行者空闲**：建造看建造者（`meta.builder.id`，通常是 1000），升级看被升级建筑；空闲起点取同一 actor
  上一条指令的终态时刻（`completed`/`failed`/`cancelled`/…）。升级建筑从 `queued` 起算占用。
  指令自己的扣款不算进自己的窗口（`Credits.earliest` 只看严格早于 `B` 的锚点）。
- **多余建造/升级**：把 `targetComposition`（`4 x T1`、`2 x T2 + 2 x T1` 这类）解析成各等级数量，与实际
  `completed` 的建造数、T2/T3 升级数比较，超出部分每条记 1 分。换一种等价但超出该组成的路线（例如
  用 T3 顶到同一收入）同样按"多余"计。
- **被对局结束截断的指令不算浪费**：`finish()` 在达标/超时时会把仍在执行的指令记成 `cancelled`/`timeout`，
  终态时刻与 `result.gameMs` 相同时不计入"被替换/取消/失败/超时"，否则每局收尾都会白扣 2.5。
- **离群值**：`> Q3 + 1.5×IQR`，与指标插件同一规则；去离群后的均值才是 `f` 的输入。
- **缺证据**：没有 `cost`、没有 native 命令、间隔样本不足两条时，该项记 `status = "N/A"`、扣 0，理由写进
  `score.diagnostics`；不按经验补数。

### 14.2 读分注意

- `score.items[].raw` 是封顶前的原始分，`points` 是裁到上限后的实际扣分；两者只在超上限时不同。
- 曲线与完整对局评分同一实现（`evaluate/micro/common/scoring.py` 对应 `evaluate/scoring-curves.js`）：
  自然指数在对数横坐标上极凸，`f(mean)` 在 `mean` 远低于 60 s 时几乎为 0，这是形状本身决定的，不是漏算。
- 同一份证据重复离线计算得到相同分数（纯函数）；换版本（`case2-score-v1` → 新版本）不可混榜。

**人类对局**：本 CASE 拒绝 `mode=human`（`MicroConfig` 走默认 `requiresAgentRealtime()`），启动即报错。
因此 `action_delay`/`irrelevant`/`cadence` **三项都没有人类数据**——既算不出人类分，也不存在人类基线；
讨论"这一局人类会得多少分"没有证据基础。
