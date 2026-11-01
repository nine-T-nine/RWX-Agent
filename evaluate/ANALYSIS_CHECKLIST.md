# 第二轮评估审计清单

本文件是 `evaluate/` 第二轮事件评估的长期审计台账，不是评估报告，也不替代 [事件规则](events/README.md) 与 [总 README](README.md)。它只记录：哪些规则已经确认不能随意改、哪些实现问题必须修、哪些差异是有意保留的保守性漏检、哪些判定点还需要人来决定。

原则：**宁可漏检，不可错判。** 清单里每一条都要能追溯到源码或回归测试；不贴原始 telemetry、完整 Replay 或完整命令日志。

## A. 不可随意改变的设计原则

以下决定已经讨论并确认。修改它们需要重新论证，不能作为“优化”顺手改掉。

1. **证据优先。** 只有证据能严格证明时才确认事件；证明不了就输出 `UNCERTAIN` 或不输出，不按推测补全。不为了提高检测率扩大时间窗、空间半径或放宽排除条件。
2. **不做意图推断。** 不引入 Agent 意图、战略规划、战术质量评分；战损本身不直接等于战术错误。
3. **复用现有证据。** 只用 telemetry / commands / economy / Replay；不新增每 tick 全量快照，不显著增加日志体积。
4. **结构兼容。** 保持五类检测器的基本结构与输出字段；新增内容只做统计，不改既有字段含义。

具体规则：

- **AMBUSH**：X 取运行时坦克/重型坦克/激光坦克射程中位数（通常 160），固定不随攻击者变化；圆集合按相交传递合并；同一条攻击关系跨满 5s、伤害记录间隔 ≤3s 才确认；攻击者受伤立即失活，受害者非 idle 达 3s 后失活；首次相关主动 B 后再收 3s 尾窗；不引入动态武器射程或高级意图。
- **BUILDER_GAP**：严格 `3s < gap < 10s`；必须证明前一 build/repair 任务结束、期间无其他任务、下一任务成功成为当前任务；用 t0 的资金、动作菜单、维修目标与放置证据判断可行性；下一任务在未来 3–10 秒的实际执行可反查同一动作在 t0 是否可执行；缺少可靠历史放置证明时 `UNCERTAIN`；全局主动 B 检查 `[t0,t1)`（含左端、不含右端）；不新增超过 10 秒的 `LONG_IDLE`。
- **PRODUCTION_GAP**：严格 `3s < gap < 10s`；必须有实际生产完成、队列彻底清空、下一次成功新增生产队列；升级不是生产端点；检查开区间 `(t_idle,t_order)` 的全局 B 与工厂状态；用 t0 的动作可用性、资金、单位上限和完整下单批次；自动化/提前安排的下单不算主动迟到响应；t0 连一个单位都下不了单时直接 `EXPLAINED`；多工厂共享预算无法重建时 `UNCERTAIN`。
- **FEED**：保留实际敌方伤害来源、附近敌人、无其他友军等条件；全部损失按时间排序，相邻间隔 ≤5s 才属于同一损失段，每段独立判断第三次损失；圆集合传递合并；战损不直接代表战术错误。
- **STALE_COMMAND**：仅主动直接 B；必须证明收包时对象已死亡、删除或失去控制权；按对象生命周期去重，同一命令同一对象最多一次；资金、队列、路径、射程不算过期；错误文本不作为状态依据。
- **暂不增加**：`ASSAULT_ATTRITION` 等进攻编队战损评价、`OBSERVED_NO_RESPONSE`、`LONG_IDLE`、高级战术意图匹配、按损失比例直接扣分、复杂动态威胁半径。本轮唯一新增类别是 `COMMAND_FAILURE` 统计。
- **全局 B 口径**：空窗检查使用 `directB`（`source==='agent'` 且不是间接配置的 `rwx-game-order-*`/`push`/`cancel`），统计范围是全局直接主动 B，不按目标相关性筛选；`keep-set`、`auto-set`、`group-*` 等间接配置不属于该集合。不要把它改成相关性匹配。

## B. 必须修复清单

严重度：**P0** 会静默改变结果或产生错误事件；**P1** 会漏检或证据不完整但方向保守；**P2** 字段/文档口径不一致；**P3** 可能的实现瑕疵，当前无观测影响。

| ID | 问题 | 影响检测器 | 源文件 | 状态 | 回归测试 | 最后审查 |
|---|---|---|---|---|---|---|
| FIX-01 | 精简后的 `frames` 行必须被流式展开；跨度、步长、tick/seq 不自洽时报错，不能静默丢帧 | 全部（读取层） | `evaluate/evaluate_stage_2.js`、`evaluate/utils/EngineEvidence.java` | 已修复（P0，2026-10-31） | `test-events.js`：raw/trimmed 结果一致、5 种坏 frames 行被拒、惰性展开；`EngineEvidenceTrimTest` | 2026-10-31 |
| FIX-02 | `--builder` 未纳入收包对象提取，已死亡 builder 的建造请求没有生命周期证据，STALE_COMMAND 漏检（本局 B-81 / #218） | STALE_COMMAND | `evaluate/utils/EngineEvidence.java`、`evaluate/evaluate_stage_2.js` | 已修复（P0，2026-10-31） | `EngineEvidenceTest` 的 builder 收包测试；`test-stage2-fixes.js` 的 B-81 端到端 | 2026-10-31 |
| FIX-03 | FEED 从 `losses.slice(2)` 开始分段，第一、二次损失不参与 5s 分段，可能把 100/108/112 秒误判为连续三次损失 | FEED | `evaluate/events/detectors.js` | 已修复（P0，2026-10-31） | `test-events.js`：100/108/112 秒、两个独立段、确认与响应窗口 | 2026-10-31 |
| FIX-04 | PRODUCTION_GAP 用 `!feasible` 判断，`feasible=null`（共享预算）被当成 `false` 判成 `EXPLAINED` | PRODUCTION_GAP | `evaluate/events/detectors.js` | 已修复（P0，2026-10-31） | `test-events.js`：共享预算、历史动作缺失、完成证明优先于 t0 可行性 | 2026-10-31 |
| FIX-05 | 精简在 `close()` 里用 `readAllLines` 一次读入，超长对局的 telemetry（数百 MB）可能在收尾时 OOM 并放弃精简 | 全部（采集层） | `evaluate/utils/EngineEvidence.java` | 已修复（P1，2026-10-31，改为流式） | `EngineEvidenceTrimTest`（输出与旧实现逐字节相同） | 2026-10-31 |
| FEAT-01 | 新增 `COMMAND_FAILURE` 统计：只统计 `accepted=false` 的请求，分类、按操作/配置/查询分开，与 STALE_COMMAND 去重 | STALE_COMMAND（关联）、新增统计 | `evaluate/events/failures.js`、`evaluate/events/detectors.js` | 已实现（2026-10-31） | `test-stage2-fixes.js`：9 条拒绝的完整回归、查询不进事件、去重计数 | 2026-10-31 |
| FEAT-02 | 新增 `COMMAND_FAILURE` 过滤视图 `filtered`：去掉 STALE 已覆盖、同签名重复、`T_RETRY_WINDOW` 内已恢复的请求；原始计数一个不动 | STALE_COMMAND（关联）、COMMAND_FAILURE | `evaluate/events/{failures,detectors}.js`、`evaluate/events/config.js` | 已实现（2026-10-31） | `test-stage2-fixes.js`：重复/已恢复/窗口外/原始计数不变 | 2026-10-31 |
| FEAT-03 | 新增 `COMMAND_FAILURE.actionable` 可扣分视图：逐条排除 `staleCommand`/`duplicateSignature`/`recoveredRetry`/`timing`/`unprovenAvoidability`/`objectContinued`，只留“写错且没纠正”的请求；事件加 `objectContinued` | COMMAND_FAILURE | `evaluate/events/{failures,detectors}.js` | 已实现（2026-10-31） | `test-stage2-fixes.js`：修正后重试、timing、未证明、对象续用四种排除 | 2026-10-31 |

## C. 待权衡事项

### C.1 已确认的错误（已关闭，保留记录）

- FIX-01 ~ FIX-05：见 B 表。关闭标准是“有回归测试且真实样本前后对比无新增误判”，不是“代码看起来对”。
- C-02（原 P2）→ 已关闭：AMBUSH 新增 `lossesSelf`/`lossesEnemy`，`losses` 保持敌我合计，字段形状不变。
- C-03（原 P2）→ 已关闭：空窗判定保持全局直接主动 B，事件新增 `B_related`/`B_unknown`/`B_unrelated` 只作说明；README 已写明“全局、不按目标相关性筛选”。

### C.2 可能的实现缺陷（未确认，暂不改）

| ID | 现象 | 判断 | 处理 |
|---|---|---|---|
| C-01 | `ProductionGapDetector` 的 `sharedFunds=(linked?.units.length??row.results.length)>1`：`linked` 存在但 `units` 为空时 `??` 不会回退到 `results.length` | P3。当前 `--builder` 类命令只有 1 个结果，`units` 非空时长度也不小于结果数，未观测到实际影响 | 记录；若将来出现多结果且 `units` 为空的命令再修 |
| C-04 | `commands.withExecution=0`：高层命令到原生执行的关联不完整（本局 0 条） | P1。不能证明唯一对应时保留 unknown，禁止模糊强匹配 | 保持现状；需要更强证据时另立任务 |
| C-05 | 旧 telemetry 没有 `builders`/builder refs 时，评估端用收包时刻的单位/死亡记录补一条 `synthesized:true` 的 controlled ref | P1。这是收包时生命周期证据，不是从错误文本反推；新采集不再需要 | 保留兜底；报告里可据 `synthesized` 区分来源 |

### C.3 有意接受的保守性漏检

见 D 表。

### C.4 需要用户决策的模糊判定点

1. **全局 B 是否要按目标相关性筛选**：已决定保持全局（见 C.1 C-03）。若将来要改，先看 `B_related/B_unknown/B_unrelated` 的分布再定，不直接动 `strict`。
2. **AMBUSH 的 `losses` 是否拆分敌我**：已按增量字段实现（C-02），`losses` 形状未变。
3. **是否给 `COMMAND_FAILURE` 设权重或评分**：仍只输出统计，但已给出 `filtered`（上界）和 `actionable`（可扣分视图）。`actionable` 的口径是“Agent 把命令写错且没纠正”，对象失效归 STALE_COMMAND、时机不计、位置被占因缺少结构化阻挡证据只进 `unprovenAvoidability` 复核。仍需你定：按条扣还是按“每 100 条命令”的比率；以及要不要让引擎在 `result` 行里额外记录 `blockedBy`，把位置类也纳入可证明范围。

## D. 已确认的保守性遗漏

这些逻辑目前**允许漏检**，因为修复会引入更大的误判风险或需要新证据。

| ID | 现象 | 为什么暂不改 | 影响 |
|---|---|---|---|
| M-01 | AMBUSH 区域关闭后，首次相关 B 的 3s 响应尾窗可能不完整（区域已结算，后续命令不再回填） | 回填需要保留已关闭区域并与后续命令重算，容易把无关命令算成响应 | 可能少记一次响应，不会多记 |
| M-02 | STALE_COMMAND 排除 `attackMode`、`rally` | 两者是配置/模式切换，不直接作用于对象；纳入会把正常配置算成过期命令 | 少记，不会多记 |
| M-03 | PRODUCTION_GAP 对“已生效但未新增队列”的原生命令提前清除候选（`queue_changed_by_command`） | 无法区分“命令取消队列”和“命令与生产无关”，保守地不结算 | 少记 |
| M-04 | 同一 FEED 区域内两段损失相隔 >5s 时，第二段必须自己凑满三次损失才确认 | 这是 FIX-03 的直接结果：不再用第一段的两次损失给第二段凑数 | 少记，避免把不连续损失算成重复损失 |
| M-05 | 区域保持期结束后才结算事件，`t_end` 取段末而非结算时刻 | 结算时刻受保持期影响，用段末更接近事实；保持期本身是既有规则 | 无（`region_end` 单独记录结算时刻） |
| M-06 | 旧整秒命令时间保留 1s 不确定区间，可能把边界 B 算进空窗 | 保守方向：宁可把空窗判成 EXPLAINED | 少记严格空窗 |
| M-07 | `COMMAND_FAILURE` 只统计端口日志覆盖到的请求；日志截断后仍被拒绝的请求没有收包记录，不做匹配 | 没有日志就没有“这条请求是谁发的”的归属，宁可漏记 | 少记 |
| M-08 | 精简后的 `frames` 行只保留游戏时钟，tick 与 seq 由端点重建；若将来出现时钟步长非恒定的变速实现，段会自动断开而不是猜 | 断段只影响体积，不影响判定 | 无（体积略增） |
| M-09 | 空窗的 B 相关性只统计不判定：选择器无法解析时记 `B_unknown`，不当作 `B_unrelated` | 把 unknown 当 unrelated 等于把“不知道选了谁”当成“没选这个对象”，会直接制造 `UNANSWERED` | 少记严格空窗 |

## E. 审计历史

### 2026-10-31

- **Git**：HEAD `5ab4acb`；工作区含其他 Agent 未提交改动（`micro/`、`RwxCliCommands.java` 等），本次只改 `evaluate/` 与 `core/src/test/kotlin/evaluate/utils/`。
- **检查的文件/模块**：`evaluate/evaluate_stage_2.js`、`evaluate/events/{detectors,model,failures,economy,config}.js`、`evaluate/utils/EngineEvidence.java`、`evaluate/test/{test-events,test-stage2-fixes}.js`、`core/src/test/kotlin/evaluate/utils/{EngineEvidenceTest,EngineEvidenceTrimTest}.kt`、两轮 README。
- **新发现**：FEED 分段从 `slice(2)` 开始（FIX-03）；PRODUCTION_GAP 的 `null` 被当 `false`（FIX-04）；`--builder` 未取证（FIX-02）；精简读取缺少步长/整数校验，且写入端整文件读入（FIX-01/FIX-05）；`sharedFunds` 的 `??` 细节（C-01）。
- **已关闭**：FIX-01、FIX-02、FIX-03、FIX-04、FIX-05、FEAT-01。
- **执行的测试**：
  - `node --test evaluate/test/test-evaluate.js evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js` → 115 通过、0 失败。
  - `:core:test --tests evaluate.utils.EngineEvidenceTest --tests evaluate.utils.EngineEvidenceTrimTest` → 10 通过（8 + 2）。
  - Ice Island 真实样本回归：原始 telemetry 与 `trimTelemetry` 精简后（6,298,640 B / 48,486 行 → 4,419,586 B / 13,150 行）的 `events.jsonl`、`events.txt`、`economy.log` 逐字节一致，`summary.json` 仅输入路径不同。
  - 修复前后对比：原有 4 条 AMBUSH、4 条 STALE_COMMAND 的字段完全一致（仅事件 id 因新增事件顺延）；STALE_COMMAND 4→5（新增 B-81 / #218）；新增 7 条 COMMAND_FAILURE；`commands.withExecution` 仍为 0。

### 2026-10-31（追加：口径确认后的三项调整）

- **Git**：同前（HEAD `5ab4acb`，工作区含他人未提交改动）。
- **决策**：① 空窗判定保持全局直接主动 B，只增加 `B_related`/`B_unknown`/`B_unrelated` 说明性计数；② AMBUSH 增加 `lossesSelf`/`lossesEnemy`，`losses` 形状不变；③ `COMMAND_FAILURE` 增加过滤视图，原始计数不变，仍不评分。
- **检查的文件/模块**：`evaluate/events/{model,detectors,failures,config}.js`、`evaluate/execution-mapping.js`、`evaluate/test/{test-events,test-stage2-fixes}.js`、`evaluate/events/README.md`、`evaluate/README.md`、`evaluate/test/README.md`。
- **新发现**：`T_RETRY_WINDOW` 需要成为显式参数（默认 10s，只影响过滤统计）；选择器类命令（`--units all-army`）没有 `intent` 行时相关性只能记 unknown（本局 3/37 条 direct B）。
- **已关闭**：C-02、C-03；新增 FEAT-02。
- **执行的测试**：
  - `node --test evaluate/test/test-evaluate.js evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js` → 118 通过、0 失败（新增 AMBUSH 战损拆分、空窗 B 相关性、失败过滤 3 项）。
  - Ice Island 真实样本：`filtered.count=4`（`excluded.staleCommand=3`、`duplicateSignature=0`、`recoveredRetry=0`）；4 条 AMBUSH 的 `lossesSelf`/`lossesEnemy` 与 `losses` 拆分一致（如 AMBUSH-000038：`losses={scout:2,missileTank:1}` → 己方 `missileTank:1`、击杀 `scout:2`）。
  - `summary.config` 新增 `T_RETRY_WINDOW`；其余事件字段与判定不变。

### 2026-10-31（追加：可扣分视图 `actionable`）

- **背景**：用户问“怎么筛出真正值得扣分的过期/无效命令”。复核发现按回复分类不够：B-18 在 3.2 秒后被 `u_builder` 修正，B-98 的阻挡物（敌方 extractor #1530）在命令前 **0.848 秒**才首次可见。
- **决策**：新增 `actionable` 视图，逐条给出排除理由（`staleCommand`/`duplicateSignature`/`recoveredRetry`/`timing`/`unprovenAvoidability`/`objectContinued`）；原始计数与 `filtered` 不变；仍不自动扣分，建议按比率读。
- **检查的文件/模块**：`evaluate/events/{failures,detectors}.js`、`evaluate/test/test-stage2-fixes.js`、`evaluate/events/README.md`、`evaluate/README.md`、`evaluate/test/README.md`。
- **新发现**：同签名恢复不足以覆盖“改参数修正”（B-18）；`objectContinued` 用“同对象 + 同操作类型被接受”近似，证不出“同一意图”；位置类缺少结构化 `blockedBy`，只能复核。
- **已关闭**：FEAT-03。
- **执行的测试**：
  - `node --test evaluate/test/test-evaluate.js evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js` → 119 通过、0 失败（新增 `actionable` 用例）。
  - Ice Island 真实样本：`filtered.count=4`（上界），`actionable.count=0`，`excluded={staleCommand:3,timing:2,objectContinued:1,unprovenAvoidability:1}`；`counts` 仍为 AMBUSH 4 / STALE_COMMAND 5 / COMMAND_FAILURE 7。

## F. 更新机制

出现以下变化时，更新本清单对应条目，而不是重跑全部历史对局：

- `evaluate/` 下的评估代码（`evaluate_stage_2.js`、`events/*`）；
- `evaluate/utils/EngineEvidence.java`、`CommandRecorder.java`（telemetry 写入端）；
- telemetry schema（新增/改名/删除字段、`frames` 行格式）；
- 两轮 README 中已确认的规则；
- 对应回归测试（新增、删除、断言变化）。

流程：

1. 每次改动后更新 B 表的状态、回归测试与“最后审查”日期，并在 E 追加一条记录（日期、commit、检查范围、新发现、已关闭、执行的测试）。
2. 每轮批量评估开始前，检查 B 表中未关闭的 P0/P1；有未关闭 P0 时先修再评估。
3. 没有相关变化时，只更新状态与日期，不重新分析历史对局。
4. 新条目必须先判断属于 C.1（确认错误）、C.2（可能缺陷）、C.3/D（有意保守）还是 C.4（待决策），不要把所有潜在优化都标成 BUG。
