# CASE3-Transport 离线指标

## 1. 评测原则

引擎记录事实，外部 `evaluate.py` 加载 `metrics/*.py` 插件计算指标。不设置加权总分。核心问题是 Agent 是否理解生产—装载—运输—卸载的任务工期与并行关系，而不是模型是否报告了一个 ETA。

Agent 不提供 forecast/ETA 命令时，评测只能从 AB 指令序列、实际生产/运输生命周期和观察时机估计其隐式调度行为。不得声称获得模型内部预计完成时间。

## 2. 证据文件

| 文件 | 内容 |
|---|---|
| `meta.json` | task/seed、地图哈希、岛屿格集合与面积、目标配置、初始工厂/运输单位/提取器、运输容量、防空炮塔布景、预算。含隐藏字段时只能由评测器读取 |
| `gateway.jsonl` | A/B/C 请求、requestId、命令、接收/回复 wallMs、gameMs/tick、结果/错误 |
| `match.replay` | 引擎实际录制的原生命令 |
| `engine/production.jsonl` | 订单受理、原生命令、开工、按类型累计的完成里程碑、被拒请求 |
| `engine/transport.jsonl` | 装载、cargo 成员变化、运输移动、卸载动作、运输单位受伤与 HP 变化 |
| `engine/goal.jsonl` | 逐类型目标岛计数变化、目标首次达成 |
| `violations.jsonl` | 执行前拒绝的越权请求 |
| `result.json` | authoritative outcome、endReason、完成时间、运输单位终态 |

> 运输机受伤与货舱变化合并在 `engine/transport.jsonl`（`kind=damage` / `kind=cargo`），不再单独产出 `engine/damage.jsonl`；离线指标只依赖实际写出的文件。

所有 `wallMs` 使用 run 初始化时同一个 `System.nanoTime` 原点；`gameMs/tick` 使用引擎时间。Replay 只有 tick 时，应在实际 ReplayWriter 插入点补充 native index 与同源 wallMs，离线匹配必须保留 ambiguous/unmatched。

## 2.1 插件

`evaluate.py` 只做读取与调度，指标由 `metrics/*.py` 计算，每个插件声明 `ID / TIER / SOURCES` 并提供 `compute(run)`：

| 插件 | tier | 证据 |
|---|---|---|
| `outcome` | T1 | result + meta + goal |
| `production_lifecycle` | T1 | engine/production + gateway |
| `delivery` | T1 | engine/transport + engine/goal + result |
| `transport_utilization` | T1 | engine/transport + meta |
| `transport_damage` | T1 | engine/transport + meta |
| `anti_air_exposure` | T2 | engine/transport + meta（炮塔坐标仅离线使用） |
| `ab_timing` | T2 | gateway |
| `mission_phases` | T2 | production + transport + goal |
| `violations` | T1 | violations + gateway |

输出写到 `<run>/eval/metrics.json`、`report.txt`、`report.html`。生产完成里程碑按单位类型累计（引擎不猜“哪个工厂产了哪台单位”），插件在 `note` 字段里明说这一点。

## 3. T1：任务结果与阶段耗时

### 3.1 `outcome`

输出 `success/defeat/timeout/error`、endReason、task/seed、目标组成、实际送达组成、目标首次达成 tick/gameMs/wallMs、总耗时、运输单位存活数、最终 HP、剩余资金。

- `success`：目标岛上存活且未装载的我方陆军首次达到所有类型目标数量。
- `defeat`：全部本局运输单位阵亡。
- `timeout`：游戏或墙钟预算先到。
- `error`：引擎/场景异常。

### 3.2 `mission_phases`

按可观测事件拆分：生产等待、生产执行、装载、起飞/离开基地岛、航行、抵达目标岛、卸载、目标确认。报告各阶段总耗时、均值/中位数/p90、关键路径和阶段重叠时间。若阶段无法可靠识别，标记 N/A，不用固定工作量推算。

### 3.3 `production_lifecycle`

逐订单输出 requestId、factoryId、unitType、count、接收/受理、入队、开始、完成、生成 unitId、实际扣费、终态和阶段耗时。汇总工厂利用率、队列空窗、并行生产比例、订单等待时间、生产完成到首次装载的等待时间。

### 3.4 `delivery`

逐目标单位记录 `unitId/type`、生产完成、首次装载、运输单位、卸载、首次进入目标岛、离开目标岛、阵亡和最终是否计入目标。汇总各类型送达率、首次送达时刻、最后一个缺口补齐时刻、未送达原因。

## 4. T1：运力与风险

### 4.1 `transport_utilization`

逐运输单位统计真实容量、占用席位时间、空闲席位时间、每次航次装载量/容量、平均装载率、航次数、空载航次、等待货物时间、等待卸载时间和往返时间。席位按引擎实际权重计算，不得把单位数量直接当作席位数，除非源码证明每个目标单位权重均为 1。

### 4.2 `transport_damage`

逐运输单位输出初始/最终 HP、受击次数、累计 HP 损失、伤害时间序列、攻击来源（可确认时）、受击时位置、cargo 数量、是否阵亡。若来源不可确认写 `unknown`。

### 4.3 `anti_air_exposure`

type 1～3 按真实防空塔攻击事件统计：运输单位进入/离开有效威胁范围、被攻击时长、攻击次数、损失 HP、塔 ID/坐标/类型（引擎可见时）。不要仅按几何射程推断“正在受攻击”。type 4 没有草稿指定的防空塔，若实际无此类敌人则为 N/A。

## 5. T2：Agent 时间行为

对成功受理的 A/B 请求按 `wallRecvMs` 排序：

- `A→B`：B 接收前最后一条已回复 A 到 B 接收的间隔。
- `B→next A`：B 回复到下一条 A 接收的间隔。
- `B→B`、`A→A`：相邻同类请求接收间隔。
- `decisionCycles`：每条 B 关联之前最后一条 A 和之后首条 A；缺失不补造。
- `wake_alignment`：将 B→next A 与该 B 对应生产/运输事件完成时刻比较，报告提前观察、完成后唤醒延迟、完成前轮询次数。
- `phase_timing`：按生产、装载、航行、卸载、受击/规避等阶段分组报告观察间隔与命令间隔。

每类间隔至少输出 `n/mean/median/p50/p90/max/samples/outliers`。分位数采用线性插值；离群值按 `Q3 + 1.5×IQR`。同时报告请求处理耗时、错误率、拒绝率和违规数。

A→B 包含模型推理、Agent Runtime 和工具调度，不等于纯模型思考时间。B→next A 也不必然表示 Agent 在等待该命令完成。

## 6. 基线对比

每个 type 独立运行 Greedy baseline。只比较同 task+level+seed 的 Agent 与对应基线：完成率、总耗时、关键路径、生产/运输重叠、平均装载率、空载航次、运输单位 HP 损失、目标送达率和 AB 时间分布。不合并成总分。

## 7. 诊断与边界

- 违规请求不进入 Replay；若发现未经授权的原生命令进入 Replay，诊断必须标记 `severe/native_authority_mismatch`。
- Replay/Gateway/EngineEvidence 匹配歧义时保留 `ambiguous/unmatched`，不得按最近时间强行归因。
- 不通过每 tick 全量扫描所有单位来构建证据。优先挂接生产、装载、移动、卸载、伤害、死亡、地形区域进入/离开等引擎事件；无法事件化的少量对象状态监控必须说明开销和采样精度。
- `incomePerSecond`、生产时间、运输速度和席位容量都必须按当前加载单位定义/实际引擎数据记录，不能套用 CASE2-LTE 的数值。
- 目标完成时间来自引擎首次满足条件的 tick，不是 Agent 观察到成功的时刻。
