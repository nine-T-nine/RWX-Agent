# CASE4-Retreat 离线指标

## 1. 评测原则

引擎写事实，外部 `evaluate.py` 加载 `metrics/*.py` 计算指标；不设置加权总分。重点分析战损、炮塔摧毁顺序、撤退时机、维修效率和重新投入时间。

“超时损失会被放大”在当前设计中解释为：任务越拖延，单位可能持续暴露在敌方火力下，真实 HP 损失和阵亡风险随时间累积。草稿没有定义额外超时倍率，metrics 不得自行乘以惩罚系数；如以后增加时间惩罚，必须作为独立、公开、可配置的规则。

## 2. 证据文件

| 文件 | 内容 |
|---|---|
| `meta.json` | task/seed、地图哈希、我方单位/维修湾、敌方完整配置、区域参数、迷雾、预算。敌军字段仅评测方可读 |
| `gateway.jsonl` | 所有 A/B/C 请求、requestId、接收/回复时间、结果/错误 |
| `match.replay` | 引擎实际原生命令 |
| `engine/turret_damage.jsonl` | 每座敌方炮塔 HP 变化、受击时刻、攻击来源（可确认时） |
| `engine/turret_destroyed.jsonl` | 每座炮塔被摧毁时刻及摧毁前后战局快照 |
| `engine/unit_damage.jsonl` | 我方单位 HP 变化、受击来源/位置（可确认时）、死亡 |
| `engine/repair_sessions.jsonl` | 每个我方单位持续治疗区间；只正式记录持续时间 >5 游戏秒的区间 |
| `violations.jsonl` | 执行前拒绝的违规请求 |
| `result.json` | authoritative success/timeout/error、目标清空时刻和终态 |

敌方配置不得经 state、map、unit-list、threat-list、help、错误回执或其他 Agent 可访问接口泄露。所有 wallMs 使用 run 初始化时同一 `System.nanoTime` 原点；gameMs/tick 以引擎为准。

## 3. T1：任务结果与炮塔进度

### 3.1 `outcome`

输出 outcome、endReason、task/level/seed、开始/结束 tick/gameMs/wallMs、总耗时、目标区是否清空、摧毁炮塔数量（评测方统计）、我方存活数/阵亡数、最终 HP 总量。

`success` 由引擎确认目标区域内本局所有敌方目标被摧毁；`timeout` 由已确认的时间预算触发；`error` 为运行异常。是否增加“我方全灭即 defeat”必须由需求方确认后才能实现。

### 3.2 `turret_progress`

逐炮塔记录内部 ID、类型、初始 HP、每次受伤 tick/gameMs/wallMs、HP before/after、deltaHP、攻击来源/攻击单位（可确认时）、摧毁时刻、从首次被发现到摧毁的时间、首次受击到摧毁的时间。

对迷雾内未被发现的炮塔，评测证据仍可记录其真实状态，但这些记录仅供离线评测；不能反馈给 Agent。摧毁时间由引擎死亡/销毁事件确定，不以 Agent 的下一次 unit-list 查询时间替代。

### 3.3 `battle_damage`

逐我方单位输出初始/最终 HP、最大 HP、累计受到伤害、累计治疗量、阵亡时刻、受击次数、处于敌方实际攻击下的时间（只有能从引擎事件可靠识别时才统计）。按每座炮塔被摧毁前后划分战斗阶段，比较单位 HP 损失和阵亡。

### 3.4 `post_kill_snapshots`

每摧毁一座敌方炮塔，生成一条快照：被摧毁炮塔 ID/类型/时刻、当前剩余敌方炮塔数（仅离线）、我方每个单位 HP/maxHP/位置/任务、累计阵亡、累计损失 HP、已完成维修次数。该快照用于比较每次击杀后的战损，不向 Agent 暴露。

## 4. T1：维修会话

### 4.1 `repair_sessions`

按用户要求，监控每个我方军队单位进入维修湾范围并实际受到治疗的连续区间。持续时间严格 `> 5,000 game-ms` 才作为正式 session。每条记录至少包含：

- `unitId`、`repairBayIds[]`；
- `startTick/startGameMs/startWallMs`、`startHp/startMaxHp`；
- `endTick/endGameMs/endWallMs`、`endHp/endMaxHp`；
- `leaveTick/leaveGameMs/leaveWallMs`；
- `durationGameMs/durationWallMs`、`healedHp`、期间 damage（若可确认）。

若单位连续处于多个重叠维修湾范围，合并为一段并记录全部 bay ID；离开所有有效治疗范围后结束。满血但没有 HP 增长/治疗事件不算实际治疗。短于或等于 5 秒的区间不进入正式 session 列表，可在 diagnostics 统计被过滤数量。

如果引擎有明确 repair/heal 事件，优先订阅；否则仅对少量本局坦克做对象级位置/HP 监控，记录采样间隔和误差。不得每 tick 扫描整个世界。

### 4.2 `repair_effectiveness`

汇总维修 session 数量、每单位 session 数、总维修时长、平均/中位时长、恢复 HP、单位离开时 HP、维修后再次投入时间、维修湾利用率、空置时间和重叠占用。报告短 session 过滤数量，但不把它们算入用户要求的正式 session 数。

## 5. T2：Agent 时间行为

对成功受理的 A/B 请求计算：

- `A→B`、`B→next A`、`B→B`、`A→A`、decisionCycles。
- 受击/HP 明显下降到下一条有效撤退命令的响应延迟（只在确有受击事件且命令 actor 对应受损单位时计算）。
- 进入维修湾范围到首次治疗、治疗结束到重新投入攻击、离开维修湾到下一次交战的间隔。
- 炮塔首次可见/首次受击/被摧毁之间的观察与决策间隔。
- 目标切换频率、重复命令、对已摧毁目标下达的过期命令。

每类间隔输出 `n/mean/median/p50/p90/max/samples/outliers`。分位数线性插值；离群值按 `Q3+1.5×IQR`。A→B 包含模型、Runtime 和工具调度开销，不等于纯模型思考时间。

## 6. 基线比较

同 task+level+seed 比较 Agent 与通用 baseline：清空用时、炮塔摧毁顺序、单位 HP 损失、阵亡、维修次数/总治疗量/维修时长、撤退响应延迟、维修后重入战斗延迟、命令间隔。不同随机种子不混为同一对局比较。

## 7. 诊断与边界

- Replay 是原生指令主要证据；引擎事件补充 HP、伤害、维修和摧毁事实。
- 事件来源无法可靠识别时写 `unknown`，不根据最近炮塔或最近单位猜测攻击者。
- Gateway/Replay/EngineEvidence 匹配必须处理 `ambiguous/unmatched`，不强行归因。
- 违规命令不得进入 Replay；出现未授权 native command 时标记 `severe/native_authority_mismatch`。
- 不用固定采样值代替真实伤害/治疗事件；无法精确记录时明确给出采样误差。
- 不添加加权总分或未定义的超时倍率。
