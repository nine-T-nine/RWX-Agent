# 第二轮事件响应评估

本文件保存五类检测器的精确规则和采集机制。两轮目标、评测模式产物与完整运行顺序见[总 README](../README.md)，测试用途见[test/README.md](../test/README.md)。以下命令均从仓库根目录执行。

第二轮读取真实引擎证据，输出五类事件及经济时间序列。第一轮 `evaluate_stage_1.js` 和已有命令映射保持独立。

**仅有 `replay.py` 的命令 JSONL 无法完成第二轮。** 它没有完整的伤害、死亡、任务结束、工厂空闲或历史资金；必须用同版本引擎实际重放一次。报告不提供评分，不把战损直接解释为 Agent 迟钝。

## 使用

直接分析完整的实时 telemetry 只需 Node.js，并且必须同时给出本局的端口命令日志和引擎经济日志；缺任一个会直接报错，不产出报告：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/events-live/match-001-20261101-082618-team0/telemetry.jsonl' --commands 'evaluate/output/commands-你的对局.txt' --economy 'evaluate/output/events-live/match-001-20261101-082618-team0/economy.log'
```

输出默认写到 `evaluate/output/<对局名>/stage_2/`，`--out` 可覆盖；`--replay` 可在有第一轮唯一归属执行时补充生效证据。

下面是 Replay 补采示例，需先构建项目的 `:core:test :desktop:headlessArchive`；假设 Headless 发行包位于 `desktop/build/headless/`，Java 为 25。`--commands` 必填，脚本用它与补采出的 `economy.log` 一起驱动第二轮：

```powershell
python evaluate/utils/evaluate_replay.py 'replays/你的对局.replay' --team 0 --commands 'evaluate/output/commands-你的对局.txt' --java 'A:/javas/java25/bin/java.exe'
```

产物默认落在 `evaluate/output/<对局名>/`：`engine/match-…` 是原始证据，`engine/playback.log`、`engine/playback.json` 记录播放与输入校验，`replay/` 是自动解析的录像命令（省略 `--jsonl` 时生成，也可传入已有解析结果），`stage_2/` 是报告。`--dist` 可指定隔离构建的 `agent-tmp/priority-build/RWX_desktop/headless`。`--speed` 为原生 Replay 播放速度，默认 64；它改变回放耗时，采样仍使用模拟时钟。脚本不向 CLI 发送状态查询或游戏操作，Replay 结束后关闭独立进程，核对输入 SHA-256。

已有引擎证据可以直接重复分析（同样必须带 commands 和 economy）：

```powershell
node evaluate/evaluate_stage_2.js 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/telemetry.jsonl' --commands 'evaluate/output/commands-你的对局.txt' --economy 'evaluate/output/<对局名>/engine/match-001-20261101-082618-team0/economy.log'
```

引擎证据按状态跃变采样，一局 14 分钟 2 人对局约 13 MB（此前每帧全量快照为 244 MB，更早的旧局曾到 0.3–1.5 GB）。Claude 两局录像在玩家失去行动后仍由 AI 继续对局，对应的命令日志里用 `----以下是意外或者人为干涉情况----` 标出了边界；证据已按该边界截断，文件最后一行是 `reason=truncated_after_interference` 的结束记录，报告的限制里会注明这一点。截断后仍可按上面的命令重新分析；删除证据后只能按重放命令重新采集。

实时对局启动时加 `--evaluate`，它设置默认证据目录 `evaluate/output/events-live` 并打开 CLI 与命令录制；要换目录或队伍可用 `-Drwx.evaluate.dir=…`、`-Drwx.evaluate.team=0` 覆盖。该开关不自动开启本地原生Replay，需从开局录像时使用 `rwx-battleroom-start --record`。默认不启用采集。每局自动分文件夹，同目录已有的局不覆盖；正常离开、退出和Replay结束会flush，实时证据关闭后运行同一Node入口。

每局的文件夹名形如 `match-<局内序号>-<yyyyMMdd-HHmmss>-team<队伍>`，例如 `match-001-20261101-082618-team0`，时间戳是本机墙钟，因此从目录名就能看出是哪一局。想给某一局起名字，启动时加 `-Drwx.evaluate.label=<名字>`（如 `-Drwx.evaluate.label=case4-run3`），名字会被清洗成路径安全字符并接在末尾：`match-001-20261101-082618-team0-case4-run3`。同一个 `telemetry.jsonl` 的 `meta` 行也记了 `startedAt`（带时区的 ISO 时间）与 `label`，即使目录被复制、改名或并到别的树里也能对回原局。

## 输出与证据

| 文件 | 内容 |
|---|---|
| `engine/match-…/telemetry.jsonl` | 有序引擎证据：游戏毫秒、tick、局内递增 seq、状态变化、实际伤害/死亡、CLI 接收/展开/结果、原生命令前后变化 |
| `engine/match-…/economy.log` | 引擎直接采样的资金和 HUD 收入字段；每秒一项、60 项一行 |
| `engine/playback.log`、`engine/playback.json` | 仅补采方式：原生播放日志、输入 SHA-256 与校验告警数 |
| `replay/<录像名>.jsonl` | 补采方式自动解析的录像命令，也作为 `--replay` 证据 |
| `stage_2/events.jsonl` | 五类事件与 `COMMAND_FAILURE` 拒绝请求记录的结构化依据、响应命令、排除和不确定原因 |
| `stage_2/events.txt` | `HH:MM:SS.mmm` 事件时间线 |
| `stage_2/summary.json` | 事件总数、状态分布、严格空窗数、响应时间、建造者共同空窗、拒绝请求统计、过滤诊断、输入路径及限制 |
| `stage_2/economy.log` | 从证据重建的同格式经济序列，可与采集端核对 |

`telemetry.jsonl` 在每局结束时自动精简：连续的每 tick `frame` 行，只要 `tick` 和 `seq` 都恰好加 1、且游戏时钟按固定步长前进，就合并成一行
`{"kind":"frames","timeMs":…,"tick":…,"seq":…,"toTimeMs":…,"toTick":…,"toSeq":…,"stepMs":…}`。
读取端（`evaluate_stage_2.js` 的 `records()`）按 `timeMs + i*stepMs`、`tick + i`、`seq + i` 展开，事件结果与未精简证据完全一致；遇到其它行、tick/seq 跳号或变速就中断当前连续段。时钟步长由 `GameLogic.update` 的 `(int)(prev + f*16.666666f)` 决定（1x 为 16 ms、2x 为 33 ms、64x 为 1066 ms），因此段内步长恒定。需要原始逐 tick 行时用 `-Drwx.evaluate.trim=false` 启动。

结构化文件保留候选中的 `EXPLAINED`、`UNCERTAIN`，`strict` 才表示通过全部严格空窗条件。`DETECTED`、`UNANSWERED` 是证据属性；没有预设任意“迟到”阈值，因此不自动输出 `LATE_RESPONSE` 或分数。

原版单位 ID 由 `NetworkEngine.nextUnitId` 单调生成，在一局内不复用；新地图、游戏时钟回退及换队会开启独立证据局。记录同时含 `life`，离线模型能区分不同生命周期。普通回放中的 `life=0`；重同步或自定义代码直接替换同 ID 实体的特殊情况需单独审查。

## 源码机制与判定

### 数据从哪里来

| Evidence | 源码观察 | Finding 与实现路径 |
|---|---|---|
| E1 | `GameLogic.update` 先推进 `gameTimeMillis`，执行命令/Replay，再更新实体，最后统计；tick 与毫秒并非可以任意互换 | F1：直接记录引擎游戏毫秒和 tick，用 seq 区分同 tick 顺序；`EngineEvidence.beforeTick/observeObject/afterTick` |
| E2 | `BaseUnit.applyDamage` 在实际 HP/盾下降后发 `damaged`，`bv/removeFromGame` 在删除前发 `removed` | F2：攻击/死亡使用原始通知，去重后冻结单位状态；不根据攻击目标或附近敌人猜测伤害 |
| E3 | `Command.resolvePendingUnitIds` 丢弃死亡单位；`RwxEngineLocalGameService` 跳过死亡/缺失操控对象，将死亡和隐藏目标统一报错 | F3：收包时直接检查对象状态；响应错误文本不作为过期依据；展开组选择器后补充 intent 证据 |
| E4 | `OrderableUnit` 的 waypoint 是任务，寻路不代表 idle；队列 action 同时承载生产、升级与其他动作 | F4：快照检查任务、攻击、运输、建造进度、队列；生产端点必须是执行后新增的单位生产队列项 |
| E5 | `AbstractUnitAction.canAfford/b/isNotAvailable`、队列内容、队伍单位上限提供当时可行性 | F5：空闲起点保存原动作菜单检查、资金和上限；无法恢复历史选址、共享预算等条件时保留 `UNCERTAIN` |
| E6 | 原生坦克/重型坦克/激光坦克 `m()` 为 130/160/190，实际默认取运行时三者中位数；原生替换包也由引擎加载 | F6：全局 X 首次计算后固定，通常 160；不随当前攻击者动态改变 |
| E7 | `ResourceExtractor.update` 每 `resourceIncomeUpdateInterval` 加 `getCreditIncomeRate()*interval/resourceIncomeRatePeriod`；period=40；HUD 读 `getScaledIncomeRate()` | F7：经济日志原样记录 HUD 收入，不谎称是“每秒”或“每分钟”；余额直接读 `team.credits`，同一个模拟快照采样 |

可复核源码入口：`core/src/main/java/com/corrodinggames/rts/game/GameLogic.java`、`game/units/BaseUnit.java`、`gameFramework/Command.java`、`game/units/OrderableUnit.java`、`game/units/buildings/FactoryQueueManager.java`、`game/PlayerTeam.java`；采集实现集中在 `evaluate/utils/EngineEvidence.java`。

### 五个检测器

| 检测器 | 精确条件与时间窗口 | 排除与限制 |
|---|---|---|
| AMBUSH | 敌人实际伤害 idle 我方单位；至少一条相同攻击关系的实际记录跨满 5s，连续记录间隔不超过 3s。单位周围半径 X 的圆按相交传递合并。首次相关主动 B 后再收集 3s 命令 | 自动反击不是主动响应；攻击者受伤立即失活，受害者非 idle 达 3s 后失活。无武器的普通建筑不无条件计为偷袭。区域分裂沿用稳定 ID，防止重复结算。仅命中/伤害证据，未命中和免伤射击不算。`self`/`enemy` 是参战构成，`losses` 是参战单位里的死亡合计（敌我都有），`lossesSelf`/`lossesEnemy` 把己方战损与击杀分开 |
| BUILDER_GAP | 上一 build/repair 任务已结束、期间无其他任务，下一任务成功成为当前任务；严格 `3s < gap < 10s`；全局主动 B 检查 `[t0,t1)` | 寻路、移动、已有队列不是 idle。维修目标在 t0 必须可维修。建造须有当时动作/资金，缺少目标位置在 t0 的可靠放置证明时为 UNCERTAIN。存在其他 B 时仅 `BUILDER_INACTIVE`。`B_count` 是全局直接主动 B；`B_related`/`B_unknown`/`B_unrelated` 只说明其中多少条点名了该建造者（或选择器无法解析），判定仍按 `B_count` |
| FEED | 非 attackMove 的我方损失，实际近期敌方伤害来源可证、X 内有敌人且无其他友军。损失按时间排序后，相邻间隔 ≤5s 才属于同一损失段；每段独立判断，段内第三次损失才确认（`t_confirm`、`t_first_repeat` 均为该段第三次损失时间），段内不足三次只记诊断 | 圆集合传递合并，不用日志平均半径做碰撞。最短保持 5s；友军进入或敌方威胁消失并满足保持条件后结束。明确 attack/attackMove/guard 与普通 move 进入分别记录；战损本身不构成战术错误结论。每段的响应窗口都是 `[段首, 段末]`，早于段首或晚于段末的命令都不计为该段响应；不同损失段共用稳定 `region_id` 但各自结算，`losses` 只含本段损失、`extra` 只含本段第三次及以后的损失；`state` 只在段确认后输出，固定为 `REPEATED` |
| STALE_COMMAND | 仅主动直接 B；收包时对象已死亡、删除或失去操控归属，按对象生命周期去重，同条命令同对象最多一次。`--units`/`--unit` 与 `--builder` 命名的对象都按收包时状态取证 | 资金、队列、路径、射程、隐藏、后续死亡不是过期。仅在后续展开时无效而无法证明收包时已无效，或对象类型/生命周期未知，为 UNCERTAIN。Map 对局末输出 |
| PRODUCTION_GAP | 曾有实际生产的工厂生产结束并完全空闲；下一次成功新增单位生产队列时回查。严格 `3s < gap < 10s`，检查开区间 `(t_idle,t_order)` 的全局 B 和工厂状态 | 队列、升级、任务、移动工厂的移动/攻击均中止空闲。升级不是生产端点。核对 t0 的实际动作、资金、单位上限及整个该工厂下单批次；多工厂共享预算未完整重建时为 UNCERTAIN。自动化/提前安排的下单不作主动迟到响应。若 t0 连一个单位都下不了单（动作不可用、资金不足或已达单位上限），空窗直接判 EXPLAINED，不因下单来源未知改判 UNCERTAIN；缺少实际生产完成证明时整条候选为 UNCERTAIN。`B_count` 是全局直接主动 B；`B_related`/`B_unknown`/`B_unrelated` 只说明其中多少条点名了该工厂，判定仍按 `B_count` |
| COMMAND_FAILURE | 引擎明确 `accepted=false` 的高层请求，一条请求记一次；保留 commandId、时间、名称、原始参数与原始回复，分类为 INVALID_ARGUMENT/INVALID_ACTION/UNAVAILABLE_ACTION/STALE_OBJECT/PLACEMENT_BLOCKED/OTHER | 只统计不评分。查询失败只进诊断，操作与间接配置分开统计；与 STALE_COMMAND 重叠时标 `coveredByStaleCommand`，不重复计数。`accepted=true` 不代表原生已生效 |

建造者名单按原版实际类型：`builder`、`builderShip`、`mechEngineer`、`combatEngineer`、`experimentalSpider`、`experimentalGunship`/`experimentalGunshipLanded`、`modularSpider`/`modularSpider_nonEmpty`。中文“实验悬浮型气垫船”是 `experimentalGunship`，并不是 `experimentalHoverTank`。只会维修的单位不加入。

工厂能力按实际单位生产动作识别，包含飞行堡垒 `experimentalDropship`、航空母舰 `experiementalCarrier`、鹦鹉螺号的不同形态。内置的 CustomUnit 仍属于原版；仅排除 `CustomUnitConfig.modInfo != null` 的外部模组，以及虫族/生物单位，不会误排机械工程师等内置单位。

### 拒绝请求统计（COMMAND_FAILURE）

`COMMAND_FAILURE` 不是新的扣分项，只把引擎明确拒绝的请求变成可复核的统计。判据只有一条：`result.accepted=false`。`accepted=true` 只表示 CLI 收下了请求，不代表原生执行成功，两者不混用。

- 一条高层请求记一次，不按涉及单位数重复计数；`B-57` 跳过 3 个死亡单位仍只算 1 条失败。
- 每条保留 `commandId`、`timeMs`、`name`、原始 `args`/`raw` 和原始 `reply`。
- 分类按回复文本顺序匹配：`STALE_OBJECT`（跳过死亡/缺失对象、目标不可观察）、`PLACEMENT_BLOCKED`（无法放置、位置被阻挡）、`INVALID_ARGUMENT`（未知选项、缺少参数）、`UNAVAILABLE_ACTION`（动作“right now”不可用、正在升级）、`INVALID_ACTION`（动作名不存在），其余为 `OTHER`。分类只描述回复，不用来反推对象状态。
- `kind` 分 `operation`（主动直接 B）、`configuration`（keep/auto 等间接配置）、`query`（只读查询）和 `other`。只有前两类计入 `summary.commandFailures`，查询单独放进 `queries` 诊断，不产生事件。
- 与 `STALE_COMMAND` 用同一份收包引用判定（`events/model.js` 的 `staleRefState`）：重叠的请求标 `coveredByStaleCommand:true`，`additional` 只统计 STALE_COMMAND 未覆盖的部分，所以 `coveredByStaleCommand + additional` 等于操作+配置失败数，同一失误不会被扣两次。
- `summary.commandFailures` 给出 `total/operation/configuration/query/byCategory/coveredByStaleCommand/additional`；本阶段没有评分公式。
- `summary.commandFailures.filtered` 是**额外的过滤视图**，不改上面的任何计数：先去掉已由 `STALE_COMMAND` 表示的，再按 `signature()`（名称+参数）把同一条请求的重复失败只留第一次，最后把“同一签名在 `T_RETRY_WINDOW`（默认 10s）内被接受”的请求记为已恢复。剩下的进 `count`/`remaining`，每一步去掉多少条记在 `excluded`（`staleCommand`/`duplicateSignature`/`recoveredRetry`）。它同样不参与评分，事件上的 `duplicateSignature`/`recoveredRetry` 只是让 JSONL 自解释。
- `summary.commandFailures.actionable` 是**可扣分视图**：只保留“Agent 把命令写错、而且没纠正”的请求。逐条按顺序排除：`staleCommand`（已由 STALE_COMMAND 表示，跨指标不重复）、`duplicateSignature`、`recoveredRetry`、`timing`（`UNAVAILABLE_ACTION`，动作存在但此刻不可用，含升级/冷却）、`unprovenAvoidability`（`PLACEMENT_BLOCKED`、未被 STALE 覆盖的 `STALE_OBJECT`、`OTHER`：证不出 Agent 当时能否知道，只进复核）、`objectContinued`（失败涉及的对象在窗口内被同一类操作成功使用过，失败只损失一个回合）。剩下的进 `count`/`remaining`。单条拒绝的代价约一个回合，建议按“每 100 条命令”的比率读，不按条扣分。

### 来源、顺序和不确定性

`RwxCliTool` 在执行前冻结接收记录，原始参数不丢弃；上下文经既有模拟线程派发传递。MANUAL 的原生 Command 带局内关联，KEEP/PENDING 与引擎/UI/Replay 未知来源分别标识。关联仅是旁路弱引用，不写网络包和 Replay 协议。

原生 Command 前后任务/队列确实改变才记 `applied=true`；重复设置相同状态等无法通过快照证明的情况为 null，不能伪造“成功”。CLI 接受与原生生效分别记录。网络序列化产生新的 Command 对象时，不凭对象身份强行关联，生效时间可能保持未知。

旧日志保留第一轮解析的人工接管截止位置；日志覆盖外不确认全局无 B。旧整秒时间保留 1s 不确定区间，空窗检查保守地考虑可能落入区间的 B；响应中的 `t_first_B_isLowerBound` 和 `timeUncertaintyMs` 标明时间下界。只有唯一匹配的第一轮结果且 Replay ordinal、tick、team、原始单位名单与引擎事实一致，才补充原生应用证据；ambiguous 不强配。

事件中的视野记录只证明游戏层可见或有我方受伤/损失提示，不能证明 Agent 已查询、收到订阅或理解信息。`observability` 因此不会直接断言“Agent 已知道”。

事件 JSONL 分两遍流式读取：先索引较少的 CLI/原生命令，后执行检测。采集器复用实体更新与伤害/死亡通知，并按“状态跃变才写”采样：我方建造者与所有建筑每 tick 比对状态，受击单位随后 1 秒内每 tick 观察，位置变化最多每秒记一次；危险区用引擎空间索引，只记成员身份（id/side/alive）且仅成员变化时写一行。没有每秒 CLI 查询，也没有评测器发出的游戏操作。

命令按 `timeMs` 再按 `seq` 排序消费：来自端口日志的命令没有采集端序号，因此总在本时刻第一条 telemetry 记录之前注入，绝不吃到同一时刻尚未发生的应用结果；`t_first_applied` 只在应用时间不晚于当前记录（同毫秒还要 `seq` 更小）时才算数。报告结束时才结算事件窗口内的应用，未来执行结果不会回填。`summary.json` 的 `diagnostics` 记录每种输入记录数、各检测器的候选数与淘汰原因（如 `victim_not_idle`、`closed_before_confirmation`、`expired_at_upper_bound`），以及 `commands.synthesized/withExecution`，用来区分“严格过滤后确实没有事件”和“证据没有被采集或没有被读到”。

## 参数与验证

参数在 `events/config.js` 集中：X=运行时中位数（通常160），偷袭5s、失活3s、响应尾窗3s、空窗开界3/10s、危险区保持5s、损失聚合5s、经济1s。Node 的 `--config` 接收 JSON 覆盖。改变 X 或区域保持时间时用回放脚本重新采集，使引擎空间查询与检测配置一致。

```powershell
node --test evaluate/test/test-events.js evaluate/test/test-evaluate.js evaluate/test/test-stage2-fixes.js
python -W error::ResourceWarning evaluate/test/test_replay.py
```

引擎测试为 `core/src/test/kotlin/evaluate/utils/EngineEvidenceTest.kt`：暂停重复帧、整分钟/最后一行、时间跳跃不造数据、收包对象状态（含 `--builder`）、跨线程关联、死亡去重、连续多局；`EngineEvidenceTrimTest.kt` 覆盖 frames 行的合并、变速/跳号分段与单帧原样保留。事件测试覆盖五种检测器的边界、区域合并、自动/主动区别、费用/上限/升级、旧格式时间、生命周期和重跑一致性，以及 FEED 分段（100/108/112 秒、两个独立段）、PRODUCTION_GAP 的 EXPLAINED/UNCERTAIN 优先级、`--builder` 的 B-81 回归和 COMMAND_FAILURE 分类/去重。

初始单位采用脱离原事务列表的快照遍历。`BaseUnit.getGlobalUnitList()` 会处理待办并增加 `modCount`；建造者快照为枚举维修目标再次调用它，直接嵌套遍历会触发 `ConcurrentModificationException`、令采集只剩开局几行。DeepSeek Ice Island 的真实重放发现并修复了该问题；新增回归覆盖开局建造者和其后单位均被记录、经济采样继续推进。只有正常闭合且通过校验的采集才作为完整评估输入。

实际构建与对局验证记录保存在项目本地记忆 task-031/032/033；此文档描述可复现规则，生成报告中的原始记录用于逐条复核。
