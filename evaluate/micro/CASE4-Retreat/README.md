# CASE4-Retreat — 战地撤退

> 设计稿 v0.1。核心是战损评估、撤退维修和再次投入。单位内部 ID、炮塔参数、维修湾治疗规则、区域半径单位、时间预算等必须在实现前核实/确认；本文明确标记未确定项，不把假设写成引擎事实。

## 1. 目标

给定少量高级屏蔽气垫坦克和维修湾，摧毁左上角区域内的全部敌方炮塔。Agent 不会收到敌军具体组成、数量或位置清单，只会知道任务目标是清除左上角区域的所有敌人，并妥善利用维修湾恢复部队。

评测重点：

- 是否能根据己方单位当前 HP、受击事件和敌方可见火力判断继续攻击还是撤退。
- 是否能及时把受损单位送回维修湾，而不是在火力覆盖区停留至阵亡。
- 是否能在治疗完成后重新投入战斗，减少维修空窗和无效往返。
- 是否能根据炮塔被逐个摧毁后的战损变化调整推进节奏。
- 是否能在实时延迟下合理安排观察和命令。

标题中的“超时损失会被放大”在 v1 中通过真实战损随时间累积、受击率、HP 损失和维修周期来衡量；草稿没有定义人为的超时倍率或额外扣分公式，因此不得擅自增加伤害倍率或加权惩罚。

## 2. 任务配置

任务 ID：`4_<type>_<level>`。本设计暂按 `level=a∈{1,2,3}`、敌方数量乘以 a 理解；这一点需在实现前确认，因为 CASE4 草稿没有单独重复声明 a 的取值范围。

| 类型 | 我方单位 | 维修湾 | 敌方炮塔 | 迷雾 |
|---|---|---:|---|---|
| 1 | 屏蔽气垫坦克 ×2 | ×2 | T2 机枪 ×5a；T1 电击塔 ×3a | 无 |
| 2 | 屏蔽气垫坦克 ×2 | ×4 | T2 机枪 ×3a；T1 火炮 ×4a；T2 电击塔 ×2a | 普通持续迷雾（非一次性） |
| 3 | 屏蔽气垫坦克 ×3 | ×4 | T3 机枪 ×a；T2 火炮 ×a；T2 机枪 ×5a | 普通持续迷雾（非一次性） |

表中单位名为语义名称。实现前必须核实精确内部 ID、射程、伤害、攻速、血量、占地、目标类别和维修湾内部 ID/治疗参数。

## 3. 地图与布景

- 固定地图 `180 × 180` 格。敌方炮塔集中在左上角；我方维修湾在右下角；屏蔽气垫坦克出生在维修湾附近。
- 敌方炮塔按实际武器射程从远到近布置，避免把射程参数写死在脚本中。
- 草稿中的区域记法暂解释为：以左上角 `(0,0)` 为中心，A 环带为半径 20～30 格，B 环带为半径 30～50 格；A 区放 T3 机枪、T2/T1 火炮，其余炮塔放 B 区。A 区炮塔中心间距至少 10 格，B 区至少 3 格。
- 上述 A/B 环带解释、半径是 tile 还是 world units、A/B 的边界是否包含端点，均为**待确认设计假设**。实现必须在 meta 中同时记录 tile 坐标和 world 坐标，不能混用单位。
- 维修湾按折角/L 形排列，单位出生点靠近维修湾且保留进出通道。这里的“角形”暂按折角布局理解；具体朝向和间距由确定性布景脚本生成并记录。
- 草稿未指定地形细节。为了避免额外干扰，建议先使用连续可通行陆地，不额外生成水域、岩浆或障碍；这是待确认的简化方案，不得未经确认加入复杂地形。
- type 1 无迷雾；type 2/3 使用普通持续迷雾。不得把普通迷雾误配置成一次性揭示。
- 同一 `(task, seed)` 地图、炮塔、维修湾、单位位置和朝向完全一致。

## 4. 目标、启动与胜负

- 公开目标仅为“摧毁左上角区域内全部敌人，并妥善利用维修湾”。不公开敌军类型组成、数量、精确坐标、隐藏 ID 或私有布景表。
- `unit-list --owner enemy` 只返回当前可见敌方单位；迷雾中的敌人不能通过 Case 专用查询、错误消息、统计字段或日志泄露。
- 建议沿用 CASE2 的 briefing→显式 begin 生命周期：briefing 阶段允许观察，`rwx-micro-4-begin` 后以 1× realtime 连续运行；不提供 pause/step/speed。若希望进入即运行，应在实现前统一改动 START/README/命令文档。
- 成功：目标区域内本局所有敌方炮塔被摧毁。失败条件除成功/超时/运行异常外，草稿未规定“我方单位全部阵亡”是否立即结束；v1 不应自行新增判负条件，须先确认。若无单位仍存活，可继续运行至 timeout 或目标状态自然终结。
- 草稿未给出游戏/墙钟时间上限。实现前必须向需求方确认，不能照搬 CASE2 或自行编造一个数字。接口应支持 `gameLimitSeconds`、`wallLimitSeconds`、`startTimeoutSeconds` 配置；墙钟预算建议沿用 CASE2 的同源单调时钟口径。

## 5. 维修湾与战损

维修湾的治疗方式必须以源码为准：确认是范围内自动治疗、需要下达修理命令，还是由其他条件触发；确认治疗半径、每 tick 治疗量、目标类别、治疗冷却和满血行为。

每个我方军队单位均需监控进入维修湾范围并实际受到治疗的连续区间。单段持续时间 **严格大于 5 游戏秒** 才作为正式维修记录。每条记录至少包含：

- `unitId`、`repairBayIds`；
- 开始治疗 tick/gameMs/wallMs、开始 HP/maxHP；
- 结束治疗 tick/gameMs/wallMs、结束 HP/maxHP；
- 离开维修范围时刻；
- 持续时间、实际恢复 HP、期间受到的伤害（可确认时）。

多个维修湾范围重叠时，按同一单位的连续治疗过程合并为一段，并保留涉及的维修湾 ID；单位离开所有有效维修范围后结束该段。满血但处于范围内不应伪报为“实际受到治疗”。若引擎无法提供治疗事件，应采用小规模、对象级监控并明确采样精度；不要每 tick 全量扫描世界。

同时记录每座敌方炮塔的 HP 变化/受伤事件、攻击来源（可确认时）、摧毁时刻，以及每摧毁一座炮塔后的我方战损快照。

## 6. 命令设计

- Case 观察：`rwx-micro-4-state`、`rwx-micro-4-help`。
- 地图观察：复用完整游戏 `rwx-game-map-show` 的坐标、窗口、ASCII 地形、自动迷雾遮罩和 `--unit-*` 实体编码；Case 只简化无关统计，不另造一套不兼容地图编码。
- 实体观察：`rwx-game-unit-list --owner self|enemy --full --json`、`rwx-game-unit-show`、`rwx-game-threat-list`、必要时 `rwx-game-map-path`。
- 战斗操作：只开放必要的 `rwx-game-order-move`、`rwx-game-order-attack`、`rwx-game-order-attack-move`、`rwx-game-order-stop`。若维修湾必须显式下达 repair 命令，再按源码增加受限接口。
- 不开放建造、生产、升级、回收、任意技能、全局自动化或 pause/step/speed。

具体参数和可见性规则见 [command.md](command.md)。

## 7. 基线

提供一个通用 `baseline.py`，覆盖 type 1～3 和 level 1～3。基线只读取公开地图、己方单位 HP、当前可见敌方、维修湾位置/公开能力，不读取隐藏敌军配置、meta 私有字段或 EngineEvidence。

建议采用确定性策略：优先攻击当前可见且可达的敌方炮塔；根据己方 HP 阈值和可见威胁决定撤回最近维修湾；确认真实治疗进度后再投入；目标选择与单位分配使用稳定 tie-break。阈值、目标排序、维修重入条件必须在 README 中写明，并且不能根据隐藏敌方数量调整。

## 8. 评测产物

建议每局目录至少包含：`meta.json`（评测方私有敌军配置）、`gateway.jsonl`、`match.replay`、`engine/turret_damage.jsonl`、`engine/unit_damage.jsonl`、`engine/repair_sessions.jsonl`、`engine/turret_destroyed.jsonl`、`violations.jsonl`、`result.json`。

`meta.json` 的敌军配置必须是评测方私有数据，不得被 Agent 的 state/map/unit-list/help/错误回执访问。metrics 由外部 Python 插件计算，不在引擎内计算总分。

## 9. 待确认事项

1. level 是否确定为 a=1..3，且是否只放大敌军数量。
2. A/B 区域是否为半径 20～30/30～50；半径单位是格还是世界坐标。
3. “角形排列”是否按 L 形维修湾布局理解。
4. 地图是否统一连续陆地，是否有任何未写出的地形/障碍。
5. 游戏时间和墙钟预算；是否存在额外的超时惩罚倍率。当前只按真实战损随时间积累评估，不加人为倍率。
6. 我方单位全部阵亡是否立即判负。
7. 维修湾治疗是否自动、治疗范围/速率/目标条件。
8. 进入维修湾后是自动回血还是需要 repair/guard 等命令。
9. 炮塔生成时的精确布置算法、最小距离的单位、攻击射程与目标规则。


## 10. v1 实现核实（2026-10-31）

已按当前源码与引擎实测补入以下事实（`Case4Retreat` / `Case4Session` / `Case4Map`）。仍未确认项见 §11。

### 10.1 我方单位

| 项 | 核实结果 | 证据 |
|---|---|---|
| 屏蔽气垫坦克 | `experimentalHoverTank`（内置 Java 单位）：HP 3500、护盾 5000（每帧回复 0.25）、攻击 380 点瞬击光束、射程 180 世界单位（9 格）、移动速度 0.6/帧（≈36 单位/秒）、转向 1.1 | `ExperimentalHoverTank.java:121-127,236-253,267` |
| 维修湾 | `repairbay`（内置 Java 单位 `RepairBay`）：HP 1000、半径 30；治疗半径 `y()`=230 世界单位（11.5 格）；每 40 帧自动挑“范围内 HP 最低的我方单位”下达 repair 指令；治疗速率 `c()`=0.2/帧（≈12 HP/秒）；满血目标被跳过（`canRepairTarget` = `!q()`）；**无需玩家命令** | `RepairBay.java:136-184,248-252`、`OrderableUnit.java:2381`、`AutoRepairCallback.java` |
| 治疗判定 | 治疗只加 `currentHealth`，不加护盾；`unit-list` 的 `--json` 快照**不包含护盾字段**，因此基线只用 HP 判断撤退/重返 | `RwxLocalGameTypes.UnitSnapshot` |

### 10.2 敌方炮塔

| 内部 ID | 语义 | HP | 射程（世界单位/格） | 冷却 | 单发伤害 |
|---|---|---:|---:|---:|---:|
| `c_turret_t2_gun` | T2 机枪 | 1100 | 185 / 9.25 | 20 帧 | 44 |
| `c_turret_t1_lightning` | T1 电击塔 | 1500 | 210 / 10.5 | 2.00 s | 150 |
| `c_turret_t2_lightning` | T2 电击塔 | 2700 | 250 / 12.5 | — | — |
| `c_turret_t1_artillery` | T1 火炮 | 1000 | 350 / 17.5 | 3.67 s | 见 ini |
| `c_turret_t3_gun` | T3 机枪 | 3900 | 320 / 16 | 13 帧 | 40 |
| `c_turret_t2_artillery` | T2 火炮 | 1800 | 460 / 23 | 1.83 s | 见 ini |

全部 `canAttackLandUnits: true`、`canAttackFlyingUnits: false`。

### 10.3 地图与布景

- 180×180 格全草地（无水域/岩浆/悬崖/资源点），`Case4Map` 写出 stock TMX。
- 炮塔以左上角 `(0,0)` 为圆心：A 环 20–30 格，B 环 30–50 格。A 环放 `c_turret_t3_gun`/`c_turret_t2_artillery`/`c_turret_t1_artillery`（长射程），其余放 B 环。环内按射程从远到近依次取更小半径，A 环中心间距 10 格、B 环 3 格；放不下时按 1 格步长放宽并在 `meta.achievedTurretSpacingTiles` 记录实际值。
- 维修湾：右下角折角（L）布局，折角点 + 两条垂直臂，臂间距 4 格。type1 只有 2 座，退化为单臂直线（L 需要 ≥3 座）。
- 坦克在折角内侧 10 格处、纵向间隔 4 格，朝战场方向留出通道。
- seed 影响：旋转炮塔候选角度起点、平移右下角折角点；同 `(task, seed)` 完全复现。

### 10.4 迷雾

| type | fog | 实现 |
|---|---|---|
| 1 | 无 | `fogEnabled=false`、`fogRenderActive=false`，fog 数据填 0 |
| 2/3 | 普通持续迷雾 | `fogEnabled=true`、`fogPeriodicMaintenanceEnabled=true`、`fogRenderActive=false`，fog 数据填 10（从未探索），由单位视野逐步揭示 |

敌方可见性走引擎原生判断（`BaseUnit.d(team)`）：迷雾中的炮塔不会出现在 `unit-list --owner enemy`、`threat-list` 或 `unit-show`。

### 10.5 预算与判定

- 游戏上限 type1/2/3 = 900/1200/1500 秒；墙钟 = 游戏 +120 秒；`startTimeoutSeconds` 默认 600。
- 目标：本局全部炮塔被摧毁 → `success`；我方坦克全部阵亡 → `defeat`；预算耗尽 → `timeout`；异常 → `error`。
- `state` 只给通用目标描述与 `goal.reached`，不给剩余敌军数量/类型/坐标。

### 10.6 命令与证据

| 类别 | 命令 |
|---|---|
| A | `rwx-micro-4-state`、`rwx-game-map-show`、`rwx-game-unit-list`、`rwx-game-unit-show`、`rwx-game-threat-list`、`rwx-game-map-path` |
| B | `rwx-game-order-move`、`-attack`、`-attack-move`、`-stop`（只允许本局坦克，逐条经 session 授权后生成原生命令） |
| C | `rwx-micro-4-begin`、`rwx-micro-4-help`、`rwx-ping` |

证据：`engine/turret_damage.jsonl`（每次炮塔 HP 变化 + attacker/attackerType + 前后 HP/护盾 + 坐标）、`engine/unit_damage.jsonl`（坦克受击与 B 命令回执）、`engine/turret_destroyed.jsonl`（摧毁时刻 + 我方全员快照）、`engine/repair_sessions.jsonl`（>5 游戏秒且实际回血的连续治疗段）、`violations.jsonl`。

治疗 session 由引擎侧对本局 ≤3 辆坦克 + ≤4 座维修湾做对象级采样（每 tick），精度为 1 tick（16 游戏毫秒），不扫描全世界。

### 10.7 基线策略（`baseline.py`）

只用公开命令：`rwx-micro-4-state`、`rwx-game-unit-list --owner self|enemy`。每 2 秒一个决策周期。

1. briefing 阶段调用 `rwx-micro-4-begin`；
2. HP < **40%** 且不在维修范围内的坦克：`order-move` 到最近的维修湾中心；
3. 已在维修范围（≤220 世界单位）且 HP < **85%** 的坦克：不派新命令，等待自动治疗；
4. 其余坦克对最近的**可见**敌人下达 `order-attack`（集火），无可攻击目标时对公开目标区中心 `(700,700)` 下达 `order-attack-move` 推进；
5. `state.goal.reached` 或 phase=ended 结束。

阈值、目标 tie-break（按距离再按 id）、重入条件即以上三条，均可复现。

### 10.8 迷雾类型的基线补充

type2/3 有普通持续迷雾：炮塔只有进入我方单位视野后才会出现在 `unit-list --owner enemy`。基线因此在“看不到任何敌人”时按固定网格巡逻目标区（半径 10/18/26/34/42/50 格 × 角度 0.1…1.5 弧度，共 48 个世界坐标点，按顺序循环），走到哪里点亮到哪里；看到敌人后立刻改为集火最近可见目标。

### 10.9 仍未确认/已知限制

1. `unit-list --json` 快照**不含护盾**字段，Agent 与基线都只能用 HP 判断战损（护盾只体现在 `result.json` 的 `shield` 汇总与 `post_kill_snapshots` 里）。若要按“护盾+HP”判断，需要给 `UnitSnapshot` 增加 `shield/maxShield` 字段（尚未做）。
2. 草稿的“按真实射程从远到近放置”与“A 环放 T3 机枪和 T2/T1 火炮”存在张力：本实现按用户确认的 A/B 归类放置，并在环内按射程从远到近分配更小半径。
3. type1 只有 2 座维修湾，无法构成 L 形，退化为单臂直线（L 形需要 ≥3 座）。
4. `threat-list` 复用完整游戏实现，只统计当前可见敌人；迷雾中不会泄露隐藏数量。
