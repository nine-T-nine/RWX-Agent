# 第二轮事件响应评估

第二轮读取真实引擎证据，输出五类事件及经济时间序列。第一轮 `evaluate.js` 和已有命令映射保持独立。

**仅有 `replay.py` 的命令 JSONL 无法完成第二轮。** 它没有完整的伤害、死亡、任务结束、工厂空闲或历史资金；必须用同版本引擎实际重放一次。报告不提供评分，不把战损直接解释为 Agent 迟钝。

## 使用

先构建项目的 `:core:test :desktop:headlessArchive`。下面假设 Headless 发行包位于 `desktop/build/headless/`，Java 为 25：

```powershell
python evaluate/utils/evaluate_replay.py 'replays/你的对局.replay' --team 0 --out 'evaluate/output/你的对局_events' --java 'A:/javas/java25/bin/java.exe'
```

对已有三场录像，建议同时传入第一轮使用的原始 CLI 文件和 Replay 命令 JSONL：

```powershell
python evaluate/utils/evaluate_replay.py 'replays/你的对局.replay' --team 0 --out 'evaluate/output/你的对局_events' --commands 'evaluate/output/commands-你的对局.txt' --jsonl 'evaluate/output/你的对局/你的对局.jsonl'
```

`--dist` 可指定隔离构建的 `agent-tmp/priority-build/RWX_desktop/headless`。`--speed` 为原生 Replay 播放速度，默认 64；它改变回放耗时，采样仍使用模拟时钟。脚本不向 CLI 发送状态查询或游戏操作，Replay 结束后关闭独立进程，核对输入 SHA-256。`playback.log` 保留原生校验信息，`playback.json` 记录输入哈希及校验告警数。

已有引擎证据可以直接重复分析：

```powershell
node evaluate/events.js 'evaluate/output/你的对局_events/engine/match-001-team0/telemetry.jsonl' --out 'evaluate/output/你的对局_events/analysis'
```

引擎证据每局约 0.3–1.5 GB。Claude 两局录像在玩家失去行动后仍由 AI 继续对局，对应的命令日志里用 `----以下是意外或者人为干涉情况----` 标出了边界；证据已按该边界截断，文件最后一行是 `reason=truncated_after_interference` 的结束记录，报告的限制里会注明这一点。截断后仍可按上面的命令重新分析；删除证据后只能按重放命令重新采集。

实时对局在启动游戏时加 `--evaluate`（等价于设置 `-Drwx.evaluate.dir=evaluate/output/events-live`，并同时打开 CLI 与命令录制）；要换目录或队伍仍可直接用 `-Drwx.evaluate.dir=…`、`-Drwx.evaluate.team=0` 覆盖。默认不启用采集。每局自动分文件夹；同目录已经存在的局不会覆盖。正常离开、退出和 Replay 结束都会 flush。实时证据关闭后运行同一 Node 入口。

## 输出与证据

| 文件 | 内容 |
|---|---|
| `engine/match-…/telemetry.jsonl` | 有序引擎证据：游戏毫秒、tick、局内递增 seq、状态变化、实际伤害/死亡、CLI 接收/展开/结果、原生命令前后变化 |
| `engine/match-…/economy.log` | 引擎直接采样的资金和 HUD 收入字段；每秒一项、60 项一行 |
| `analysis/events.jsonl` | 五类事件的结构化依据、响应命令、排除和不确定原因 |
| `analysis/events.txt` | `HH:MM:SS.mmm` 事件时间线 |
| `analysis/summary.json` | 事件总数、状态分布、严格空窗数、响应时间、建造者共同空窗、采样完整性及限制 |
| `analysis/economy.log` | 从证据重建的同格式经济序列，可与采集端核对 |

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
| AMBUSH | 敌人实际伤害 idle 我方单位；至少一条相同攻击关系的实际记录跨满 5s，连续记录间隔不超过 3s。单位周围半径 X 的圆按相交传递合并。首次相关主动 B 后再收集 3s 命令 | 自动反击不是主动响应；攻击者受伤立即失活，受害者非 idle 达 3s 后失活。无武器的普通建筑不无条件计为偷袭。区域分裂沿用稳定 ID，防止重复结算。仅命中/伤害证据，未命中和免伤射击不算 |
| BUILDER_GAP | 上一 build/repair 任务已结束、期间无其他任务，下一任务成功成为当前任务；严格 `3s < gap < 10s`；全局主动 B 检查 `[t0,t1)` | 寻路、移动、已有队列不是 idle。维修目标在 t0 必须可维修。建造须有当时动作/资金，缺少目标位置在 t0 的可靠放置证明时为 UNCERTAIN。存在其他 B 时仅 `BUILDER_INACTIVE` |
| FEED | 非 attackMove 的我方损失，实际近期敌方伤害来源可证、X 内有敌人且无其他友军。第一次 UNKNOWN、第二次 DANGER、第三次及以后形成正式重复损失；损失间隔 ≤5s 聚合 | 圆集合传递合并，不用日志平均半径做碰撞。最短保持 5s；友军进入或敌方威胁消失并满足保持条件后结束。明确 attack/attackMove/guard 与普通 move 进入分别记录；战损本身不构成战术错误结论。同一区域内与上一段相隔超过 5s 的后续损失另起一条事件，该段的确认时间与响应窗口都从段首损失开始，早于段首的命令不计为对该段的响应 |
| STALE_COMMAND | 仅主动直接 B；收包时对象已死亡、删除或失去操控归属，按对象生命周期去重，同条命令同对象最多一次 | 资金、队列、路径、射程、隐藏、后续死亡不是过期。仅在后续展开时无效而无法证明收包时已无效，或对象类型/生命周期未知，为 UNCERTAIN。Map 对局末输出 |
| PRODUCTION_GAP | 曾有实际生产的工厂生产结束并完全空闲；下一次成功新增单位生产队列时回查。严格 `3s < gap < 10s`，检查开区间 `(t_idle,t_order)` 的全局 B 和工厂状态 | 队列、升级、任务、移动工厂的移动/攻击均中止空闲。升级不是生产端点。核对 t0 的实际动作、资金、单位上限及整个该工厂下单批次；多工厂共享预算未完整重建时为 UNCERTAIN。自动化/提前安排的下单不作主动迟到响应。若 t0 连一个单位都下不了单（动作不可用、资金不足或已达单位上限），空窗直接判 EXPLAINED，不因下单来源未知改判 UNCERTAIN |

建造者名单按原版实际类型：`builder`、`builderShip`、`mechEngineer`、`combatEngineer`、`experimentalSpider`、`experimentalGunship`/`experimentalGunshipLanded`、`modularSpider`/`modularSpider_nonEmpty`。中文“实验悬浮型气垫船”是 `experimentalGunship`，并不是 `experimentalHoverTank`。只会维修的单位不加入。

工厂能力按实际单位生产动作识别，包含飞行堡垒 `experimentalDropship`、航空母舰 `experiementalCarrier`、鹦鹉螺号的不同形态。内置的 CustomUnit 仍属于原版；仅排除 `CustomUnitConfig.modInfo != null` 的外部模组，以及虫族/生物单位，不会误排机械工程师等内置单位。

### 来源、顺序和不确定性

`RwxCliTool` 在执行前冻结接收记录，原始参数不丢弃；上下文经既有模拟线程派发传递。MANUAL 的原生 Command 带局内关联，KEEP/PENDING 与引擎/UI/Replay 未知来源分别标识。关联仅是旁路弱引用，不写网络包和 Replay 协议。

原生 Command 前后任务/队列确实改变才记 `applied=true`；重复设置相同状态等无法通过快照证明的情况为 null，不能伪造“成功”。CLI 接受与原生生效分别记录。网络序列化产生新的 Command 对象时，不凭对象身份强行关联，生效时间可能保持未知。

旧日志保留第一轮解析的人工接管截止位置；日志覆盖外不确认全局无 B。旧整秒时间保留 1s 不确定区间，空窗检查保守地考虑可能落入区间的 B；响应中的 `t_first_B_isLowerBound` 和 `timeUncertaintyMs` 标明时间下界。只有唯一匹配的第一轮结果且 Replay ordinal、tick、team、原始单位名单与引擎事实一致，才补充原生应用证据；ambiguous 不强配。

事件中的视野记录只证明游戏层可见或有我方受伤/损失提示，不能证明 Agent 已查询、收到订阅或理解信息。`observability` 因此不会直接断言“Agent 已知道”。

事件 JSONL 分两遍流式读取：先索引较少的 CLI/原生命令，后执行检测。采集器复用实体更新与伤害/死亡通知，持续采样建造者、队列单位及交战参与者；危险区使用引擎空间索引。没有每秒 CLI 查询，也没有评测器发出的游戏操作。

## 参数与验证

参数在 `events/config.js` 集中：X=运行时中位数（通常160），偷袭5s、失活3s、响应尾窗3s、空窗开界3/10s、危险区保持5s、损失聚合5s、经济1s。Node 的 `--config` 接收 JSON 覆盖。改变 X 或区域保持时间时用回放脚本重新采集，使引擎空间查询与检测配置一致。

```powershell
node --test evaluate/test-events.js evaluate/test-evaluate.js
python -W error::ResourceWarning evaluate/utils/test_replay.py
```

引擎测试为 `core/src/test/kotlin/evaluate/utils/EngineEvidenceTest.kt`：暂停重复帧、整分钟/最后一行、时间跳跃不造数据、收包对象状态、跨线程关联、死亡去重、连续多局。事件测试覆盖五种检测器的边界、区域合并、自动/主动区别、费用/上限/升级、旧格式时间、生命周期和重跑一致性。

实际构建与三局验证结果在任务完成时写入项目记忆 `HISTORY/task-031.md`；此文档描述可复现规则，生成报告中的原始记录用于逐条复核。
