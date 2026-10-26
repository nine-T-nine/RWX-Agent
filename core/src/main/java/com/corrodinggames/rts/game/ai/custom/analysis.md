# 原版 AI 速查：建造、生产与空间数据

本文只描述原版 AI 的通用逻辑，以及 `custom` 层如何接入/覆盖它；不记录任何具体自定义 AI 的策略。

> 文中的 `文件:行号` 基于当前 checkout；重构后行号会漂移，优先按方法名/字段名搜索。

## 目录

1. 总览
2. 建筑建造：工程单位施工
3. 单位生产：工厂队列
4. custom 层接入边界
5. 原版两层调度与基地节点
6. 难度：不只是收入倍率
7. 连通性、可达性与空间概念
8. 跨海增援与运输链路
9. 尺寸与距离单位体系
10. 时间、帧与 fps
11. 放置与覆盖范围
12. 地图、寻路与空间数据
13. 打分与建造的精确数据
14. 升级机制细节
15. 构建、测试与验证
16. 单位目录（mod 实际生效）
17. 源码入口

## 1. 总览

```text
原版 AI 决策（AIController / BaseZone）
  ├─ 建筑：选建筑 → 选址 → 选工程单位 → build 命令 → 工程单位施工
  └─ 单位：选兵种 → 选工厂 → action/queue 命令 → 工厂队列 → 单位出厂
```

`AIController` 负责周期性调度；`BaseZone` 按基地分别管理经济、建筑与生产。AI 不直接改单位状态，而是和玩家一样通过 `CommandController.newCommandForTeam(...)` 下达命令。

## 2. 建筑建造：工程单位施工

### 2.1 调用链

```text
BaseZone 更新
  → getBestBuildingToBuild()
  → 资金/优先级门槛
  → tryBuildUnit(type)
  → findClosestResource() 或 findBuildLocation(type)
  → findBuilderForUnitType(type, position, false)
  → Command.setBuildTarget(x, y, type, queueSize)
  → OrderableUnit.updateBuildCommand()
  → 创建未完成建筑，转为 repair waypoint
  → 按进度付费并提升 buildProgress 至 1
```

### 2.2 选什么建筑

`BaseZone.getBestBuildingToBuild()` 遍历所有单位类型，只保留：

- `isBuildingUnit()`：建筑；
- `canBuild(type)`：此基地存在能建它的单位。

评分使用该类型的全局已下达数量、基地内已有数量和局势。原版对资源提取器、陆/海/空工厂、炮塔、防空、资源制造厂、维修厂、核防御、实验工厂等有硬编码优先级。优先级越低，要求的闲置资金越高；结果记在 `lastFoundBuildingPriority`。

`getBestBuildingToBuild(ScoreAdjuster)` 是可注入的打分钩子：自定义建筑策略拿到原版算好的分数后调整（返回负数即排除），无参版本与原版完全一致。`AiSBuildingPolicy` 就通过它叠加全局工厂上限和空地约束。

### 2.3 放在哪里

- 资源提取器：`findClosestResource()`，直接寻找资源点；
- 其他建筑：`AIStrategyNode.findBuildLocation(type)`，基地半径内随机尝试 15 次；
- 制造仪：前 6 次尝试会优先贴着已有制造仪放，而不是纯随机；
- 海军工厂：从更大的水域半径开始，失败时继续扩张，并检查水域连通区域；
- 最终必须通过地图边界、地形/水域连通与 `BaseBuilding.canBuildingBePlacedAt(...)` 校验。

### 2.4 谁来建、如何完成

工程单位必须属于己方/当前基地、可操作、空闲或回收中、未被运输，并拥有该建筑的动作。以距离优先，保留少量随机性。

收到 `build` waypoint 后，工程单位移动到施工距离内；动作会生成 `buildProgress=0` 的建筑实体，并将自身命令改为对该实体的 `repair`。施工期间按维修速率增加进度，资源按进度分段支付；资源不足则暂停。`buildProgress == 1` 才视为建筑完成。

## 3. 单位生产：工厂队列

### 3.1 调用链

```text
BaseZone.tryBuildCombatUnits()
  → 按局势从 UnitBuildStrategy 抽取候选单位
  → findFactoryForUnitType(type, ...)
  → buildUnit(type, ...)
  → Command.setActionId(action.getQueueId())
  → FactoryWithQueue / FactoryQueueManager 入队
  → 推进队头、分段支付资源
  → 完成：创建单位并执行出厂/集结移动
```

### 3.2 选什么单位

`tryBuildCombatUnits()` 先根据资金、资源压力、攻击准备状态、空闲战斗单位、敌方威胁、路径/水图条件等，决定本轮使用哪些策略表；候选来源包括陆军、悬浮、空军、海军、工程、侦察、基地防御、防核等。每张表随机抽少量类型入列表，再按列表逆序逐个尝试（后抽到的先试），一次成功入队后通常结束本轮。

自定义 AI 接管单位生产时，原版仍会调用 `tryBuildCombatUnits(true)`：这个 `transportOnly` 变体只保留运输候选，避免原版继续补战斗单位。

### 3.3 `UnitBuildStrategy`：原版候选池策略

`UnitBuildStrategy` 不是战术评分器，而是“单位类型筛选规则 + 候选池”的抽象。`AIController.initializeBuildStrategies()` 只对已经建好的策略调用 `rebuildUnitMix()`；真正遍历 `UnitTypeEnum.ae`、用 `canBuildUnit(type)` 筛选并生成 `UnitBuildPriority(type, priority)` 的是 `UnitBuildStrategy.rebuildUnitMix()`。原版没有覆写权重：`getUnitPriority()` 恒返回 `10.0f`，因此原版的轮盘抽签等价于从合格候选中均匀随机选择。

抽候选有两条路径：

- `tryBuildCombatUnits()`：按局势选表，对每张表调用 `addRandomUnitTypesForBuild(...)`，用 `getRandomUnitTypeByMovement(...)` 抽最多 N 个类型（去重），再逐个 `buildUnit`；第一个能由合格且负担得起的工厂入队的即结束。注意列表是逆序消费的。
- `tryBuildFromStrategy(strategy, ...)`：先随机洗牌整张候选表，再依次尝试；当前用于 fabricator 这类单策略生产，不经过 `addRandomUnitTypesForBuild`。

`getRandomUnitTypeWithPrice(movement, maxPrice)` 可按移动类型和 `type.c() <= maxPrice` 过滤；移动类型比较时，`OVER_CLIFF` 视为 `LAND`，`OVER_CLIFF_WATER` 视为 `HOVER`。

> 注意：字段名和传给构造函数的历史字符串名存在错位；下表按 `canBuildUnit()` 的实际判定描述，而非变量名或字符串名猜测语义。

| 策略字段 | 实际纳入条件 |
| --- | --- |
| `builderUnitBuildStrategy` | 可攻击的陆军单位 |
| `landUnitBuildStrategy` | 可攻击的悬浮单位 |
| `airUnitBuildStrategy` | 可攻击的空军单位 |
| `seaUnitBuildStrategy` | 可攻击的海军单位 |
| `experimentalUnitBuildStrategy` | 所有建筑（原型 `bI()`） |
| `antiNukeUnitBuildStrategy` | 非战斗单位，且移动方式为 `AIR`、`HOVER` 或 `OVER_CLIFF_WATER`；实际用作运输候选池 |
| `baseDefenseUnitBuildStrategy` | 上述运输候选池中的空中单位 |
| `scoutUnitBuildStrategy` | 上述运输候选池中的非空中单位 |
| `fabricatorUnitBuildStrategy` | `type.m()` 为真且非水面的工程/建造单位 |
| `extractorUnitBuildStrategy` | `type.n()` 为真且非水面的采集单位 |
| `landFactoryUnitBuildStrategy` | 建筑且 `type.p()` 为真的资源点建筑 |
| `airFactoryUnitBuildStrategy` | 建筑；其可用队列动作中至少有一个产出非建筑单位 |
| `seaFactoryUnitBuildStrategy` | 建筑；其可用队列动作中至少有一个产出非建筑工程单位（`producedType.m()`） |

四个攻击单位池都复用 `shouldIssueActionForUnitType(type)`：原型必须不是建筑、属于 `OrderableUnit`、不是 `isNonCombatCustomUnit(...)`，并满足原型攻击能力判定。自定义单位还必须同时满足 `disableUse == false` 与 `useAsAttacker == true`；否则不会加入陆军、悬浮、空军或海军攻击池。其余策略通常只排除 `disableUse == true` 的自定义单位。

相关源码入口：`game/ai/UnitBuildStrategy.java`（候选表、移动/价格过滤与抽签），`game/ai/AIController.java`（13 个匿名 `canBuildUnit()` 实现），`game/ai/BaseZone.java::tryBuildCombatUnits`（按局势选表、抽样并下单）、`BaseZone.java::tryBuildFromStrategy`（洗牌后逐个尝试）和 `BaseZone.java::addRandomUnitTypesForBuild`（按移动类型抽样）。

### 3.4 选哪个工厂

`findFactoryForUnitType()` 只接受：

- 己方、存活、可供 AI 操作且实现 `FactoryQueueInterface` 的工厂；
- 存在该单位的 `AbstractUnitAction`；
- 动作可用、未被 AI 禁用、当前资源足够；
- 通常要求工厂队列为空；
- 指挥中心生产工程单位还受提取器数量与基地受攻击状态限制。

`buildUnit()` 不生成单位，只用动作的 `queueId` 下发 `setActionId(...)`，由工厂接收后写入队列。

### 3.5 队列、支付与出厂

`FactoryQueueManager` 只推进队头项目：

- 进度增量 = 动作建造速度 × 工厂速度 × 帧时间；
- 资源按进度分段扣除；无法支付时进度停止；
- 达到 100% 后移除队头，回调工厂完成动作；
- 普通生产由 `FactoryWithQueue` 创建单位，设定阵营和出厂锁定时间，并给可移动单位追加离开工厂/集结点的移动 waypoint；升级等非单位动作由各具体建筑完成其自身回调。
- 支付按 1% 进度分块（`0.01`），不足一块不扣款；出厂锁定 `spawnExitLockTimer` 基础 30 帧，实验工厂 +40，移速低于 0.75 / 0.55 的单位再 +30 / +20。

## 4. custom 层接入边界

custom 运行时可以按需接管原版的不同阶段：三个生产/升级开关，加一个只影响运输等待的开关。

### 4.1 接管开关

- `overridesUnitProduction()`：接管非建筑单位的工厂生产。原版 `tryBuildCombatUnits()` 不再补战斗单位，但会走 `tryBuildCombatUnits(true)` 只供应运输候选。
- `overridesBuildingProduction()`：接管建筑选择、选址与施工下单。`BaseZone.updateZoneStrategy` 仍会跑完 `getBestBuildingToBuild()`（`lastFoundBuildingPriority` 继续喂 `reclaimScore`），但 `buildUnit()` 对建筑直接返回 false，发令被跳过；自定义策略可用 `getBestBuildingToBuild(ScoreAdjuster)` 复用原版打分后微调。
- `overridesUpgrades()`：接管升级抽签。原版 `cm()` 抽签与高优先级动作不再执行，反核补弹也需要自行处理。
- `overridesTransportWaiting()`：只接管陆地增援的“等待运输/集结”阶段，不替换 `TransporterGroup` 的装载与卸载执行。开启后，不可达增援不再先把陆军移向目标点，而是 `requestTransportCapacity()` 并登记等待，避免“没有运输组就永远不请求运输”的死锁。

自定义逻辑应继续通过 `AiContext.orders()` / `context.command(...)` 下命令，复用原版的动作可用性、资源检查、网络同步、路径、施工进度与工厂队列；自定义层只替换“决策”，不要直接创建单位/建筑或改状态。

### 4.2 调度、存档与定义

- `AiStrategy` 回调：`onFrame` 每帧先执行；`onEconomy`（约 25 帧）内顺序 `onUnitProduction → onBuildingProduction → onUpgrades`；`onTactics`（约 80 帧）；`onStrategy`（约 250 帧）；离开策略时 `onStop`。`onStart` 在 `readState` 之后调用。
- 节拍来自 `AiDefinition.cadence()`（`AiCadence`，默认 `STANDARD = (25, 80, 250)`），可用 `withCadence(...)` 覆盖。每个档位在一次 update 中最多触发一次，拿到完整累计帧数后累加器清零；不是固定每 N 帧轮询。
- `AiRuntime.update(context, deltaFrames, selected)` 会校验 `definitionId` 与当前选择一致，不一致抛 `IllegalStateException`；策略异常向调用者抛出，不会自动退回原版。`AiRuntime` 只在模拟线程/房主侧运行，客户端与回放播放不执行 custom 回调。
- 若定义 `runsAnyOriginal() == false`，`AIController` 在 custom 更新后直接返回：原版整条流水线（含初始化）都不执行。只有选中的 `AiPhase`（`INITIALIZATION` / `ECONOMY` / `TACTICS` / `STRATEGY`）才跑对应原版段。
- `originalDifficulty` 决定 `difficultyLevel`；custom 也能借此复用原版的难度分支（Insane 阈值、编队容量等）。
- 存档 v2：`definitionId` + 三档累计帧 + 策略 `id()` + 策略私有字节；旧 v1 存档按 `rwx.ai-high` 读取。`readState` 必须能处理 `available() == 0`（旧档/空流）。
- 定义：`AiDefinition.custom(code, id, en, zh, factory)`，`code >= 4` 为自定义；`AiRegistry` 拒绝重复编号/ID。`withIncomeMultiplier` 替换难度倍率，最终收入 = 定义倍率 × `roomSettings.incomeMultiplier`；`extendOriginal(difficulty)` / `withOriginalPhases(difficulty, phases...)` 用于混跑原版。
- `AiContext` 提供 `ownUnits` / `visibleEnemies` / `unitTypes` / `resourcePools` / `canReach` / `canPlaceBuilding` / `originalBases` / `originalNodes` / `definition` / `credits` / `tick`；`AiOrders` 提供 `tryProduceUnit` / `tryQueueUnit` / `tryBuildBuilding` / `tryUpgrade` / `tryUpgradeAction` / `action` / `attackMode` 等。全部经 `context.command(...)` 下发；`command(...)` 只在活动回调内可用，回调外调用会抛 `IllegalStateException`。`canPlaceBuilding` 只接受建筑类型，底层仍是不可重入的 `canBuildingBePlacedAt`。

### 4.3 custom/util 工具库

| 类 | 提供 | 注意 |
| --- | --- | --- |
| `AiUnits` | 单位内部名常量与多名字解析、计数（含工厂队列）、`isAnyOf`、战斗单位/移动类型判定 | `overrideAndReplace` 后实际类型是 `c_*`，比较身份时两种都要接受 |
| `AiMapGraph` | 地图骨架无向图：区域、BFS 距离场与最短路、资源池锚点、液体判定 | 只读静态地形 → 可整局缓存 |
| `AiTerritory` | 阵营/前线视图：可达性、我方一侧、前线带、后方深度、通往敌方路线、液体渡口 | 距离场按锚点缓存；“前线/后方”是**近似定义**（原版没有，见 §7） |
| `AiPlacement` | 覆盖半径（`m()`/`y()` 取大）、圈内计数与最近搜索、生产建筑计数、到指挥中心距离、45° 后方扇区、最近敌人 | 半径一律世界单位 |
| `AiEnemyIntel` | 敌方构成记忆（见过就记、死亡减一、单位转换重算；存档只写 id，读档重绑） | 与 `BaseUnit.bE` 比对判死 |
| `AiUpgrades` | 升级动作查询（自定义按目标类型、内置走 `cm()`）、四道检查、概率掷骰 | 不要硬编码 action id |

## 5. 原版两层调度与基地节点

`AIController` 按不同时间尺度运行两类原版逻辑（custom 自己的节拍见 §4.2）：

```text
每帧更新
  ├─ 经济/生产相关周期：基地、建筑、工厂队列
  ├─ 战术层 updateAILogic：约每 80 帧
  └─ 高级策略层 updateCoreLogic：约每 250 帧
```

### 5.1 战术层：已有部队如何行动

`updateAILogic()` 主要维护当前的 `BaseZone`、`UnitGroup`、`RallyGroup` 和 `TransporterGroup`：

- 更新编队目标、攻击移动、直接攻击、回防和受损撤退；
- 为没有基地归属或没有命令的作战单位分配最近基地；
- 清除卡住过久的移动或建造命令；
- 在需要时创建陆军、攻击、海军/空中或运输编队；
- 将处于危险区的基地撤销、将编队移出危险区；
- 维护运输、集结与卸载流程。

`UnitGroup` 是实际发起攻击和防守命令的主体，而不是 `AIController` 对每个单位直接逐个决策。

### 5.2 高级策略层：下一阶段投入什么

`updateCoreLogic()` 负责更低频的全局再评估：

- 为单位刷新最近基地与移动可达性；
- 尝试建立资源前哨和前沿前哨；
- 更新全局单位/工厂统计；
- 执行原版升级抽签和高优先级动作；
- 设置单位攻击模式，给实验单位建立独立追猎组；
- 驱动特殊行为模块，例如核弹补弹与发射。

这两层不是严格的“宏观先于微操”流水线：它们共享同一批策略节点与状态，只是分别负责较快的执行维护和较慢的全局重算。

### 5.3 基地策略节点：Main、ResourceOutpost、ForwardOutpost

`Main`、`ResourceOutpost` 和 `ForwardOutpost` 都是 `BaseZone` 的 `zoneType`，不是三个独立的 AI 类。它们在激活后共用建筑、生产、资源、修理、防御和增援逻辑；差异主要来自创建条件、初始半径和全局调度用途。

| 类型 | 创建来源 | 初始阶段 | 初始半径 | 用途 |
| --- | --- | --- | --- | --- |
| `Main` | 指挥中心或可承担基地职能的起始单位 | `Active` | 420 | 主经济与防御中心 |
| `ResourceOutpost` | 安全的资源池/资源点 | `Pre` | 360 | 资源扩张候选区 |
| `ForwardOutpost` | 远离现有基地的闲置作战单位附近 | `Pre` | 310 | 前推建设或集结锚点 |

`Pre` 表示候选/建设期。区域满足工厂、提取器等发展条件并维持一段时间后，会转换为 `Active`。`Prepare` 虽然仍在枚举中，但当前实现没有形成独立的活跃流程。

前沿前哨不等同于空间意义的“前线”：它只是在远离现有基地、附近无敌且地形合格的位置建立候选 Zone，并没有基于敌我距离或防线计算真正的前线。

存档按 `createZoneByTypeId` 还原：`0/4` → `PlainZone`，`1` → `BaseZone`，`2` → `UnitGroup`，`3` → `TransporterGroup`，`5` → `RallyGroup`；读到未知 id 会直接抛异常。

## 6. 难度：不只是收入倍率

原版六档难度的收入倍率由 `AiRegistry` 统一计算。`-2..2` 分别为 `0.4 / 0.7 / 1.0 / 1.4 / 1.8`，Impossible（`3`）为 `3.7`。房间收入倍率仍会在此基础上生效。

但难度值也会进入原版行为分支。尤其是 Impossible：

- 全局策略计时器以额外速度递减，因而更快地尝试扩张；
- 基地防御目标和一次基地更新中的尝试预算更高；
- 陆军、攻击、海军/空中编队的目标容量更大；
- 原版的升级抽签、编队与前哨框架仍是同一套代码，只是阈值、容量和更新频率不同。

因此，难度不应被理解为“同一个 AI 加减资源”。低到中等难度大多共用决策框架，Impossible 则额外提高了决策频度、部队组织规模和防御投入。custom 通过 `originalDifficulty` 也能进入同一组难度分支，即便它自己接管了生产。

## 7. 连通性、可达性与空间概念

### 7.1 原版已有：移动类型相关的连通性

`AIController.isPathPossibleBetweenPoints(...)` 以移动类型为参数，比较两点是否位于可连通区域：

- 空军和 `NONE` 默认可达；
- 陆军、悬浮、海军等使用各自地形连通区；
- 编队攻击、增援、基地归属、资源扩张和敌我开局评估都会调用该判断；
- 对不可直达的任务，原版可能启用运输组，而不是让单位直接跨越地形。

原版也使用“安全点”判断来筛选资源前哨：候选点不能过于接近敌方指挥中心的可达范围，且附近敌军数量不能超过阈值。

### 7.2 原版没有：显式前线和大后方

原版没有统一的“我方控制区 → 前线带 → 敌方控制区”模型，也没有“点位距前线纵深”的内置值。它使用的是局部判断：

- 某个点或基地是否可达；
- 某区域附近是否有敌军；
- 某基地是否受攻击；
- 是否适合放置候选前哨。

因此，原版的 `ForwardOutpost` 不是严格的前线概念，`ResourceOutpost` 也不天然等同于后方安全区。

## 8. 跨海增援与运输链路

原版有可达性判断，但它不是完整的两栖任务规划器。典型的跨海增援流程为：

```text
目标 BaseZone 缺少兵力
  → 从支援 BaseZone 选闲置作战单位
  → 判断该单位能否直达目标基地内的随机点
  ├─ 可达：直接移动
  └─ 不可达但已有运输组：
       先下达移动到目标点的命令
       再标记为 unitsNeedingTransport / 创建 RallyGroup
       运输组随后轮询领取任务、飞往单位、装载、选点卸载
```

这一顺序会导致一个重要现象：不可达的陆军已经先收到向目标基地移动的命令，底层寻路会把它们推进到最接近目标的岸边；运输组则是异步轮询后才发现待运输列表，而非在任务创建时原子地绑定“乘客、运输机、登陆点”。原版还要求已存在运输组才允许登记等待；`overridesTransportWaiting()` 正是为 custom 打开“先请求运力、再等运输”的入口。

`TransporterGroup` 的实际行为是：`findGroupNeedingTransport(z)` 按 `activeStrategies` 顺序取第一个有待运输单位的组（`z = true` 时只接受 `RallyGroup`），不比较距离；运输机接近单位后才发装载命令，装载后在目标组坐标附近随机寻找合法陆地点再卸载。它不评估岸边风险、没有预先锁定登陆场，也不会在创建增援任务时保证空闲运力。

所以，原版“陆地不可达”判断可以避免一部分错误的直接进攻，但在“基地请求增援且存在运输组”的路径中，仍可能出现陆军先聚到岸边、等待运输期间遭受攻击的情况。

## 9. 尺寸与距离单位体系

### 9.1 三套单位

```text
1 格 (tile) = 20 世界单位 (world unit) = 20 像素 (zoom = 1)
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| `tileWorldSizeX/Y` 默认 | 20 / 20 | `TileMap.java:715-716` |
| space 模式 | 60 / 60 | `TileMap.java:717-719`（`spaceGameMode` 只有声明初始化 `false` 与 getter，没有运行期置 `true` 的赋值点 → 实际走不到） |
| `halfTileWorldSizeX/Y` | 10 | `TileMap.java:721-722` |
| `tileScaleX/Y` | `1/20 = 0.05`（world→tile） | `TileMap.java:723-724` |
| `tileCountX/Y` | `.tmx` 的 `width/height`（格） | `TileMap.java:1043-1047` |
| `getWorldWidth/Height` | `tileCount × tileWorldSize` | `TileMap.java:1369-1376` |
| world ↔ tile | `tile=(int)(world*tileScale)`；格心 `=tile*20+10` | `TileMap.java:621-686` |

### 9.2 哪些量是格、哪些是世界单位

| 格（整数，加到 tile 索引上） | 世界单位（float） |
| --- | --- |
| `footprint` / `constructionFootprint` / `displayFootprint`（`IniFile.getRect` 强制 4 个 int，`CustomUnitConfigParser.java:1705-1710`） | `radius` / `displayRadius`（×`globalScale`，`:1696-1712`） |
| `fogOfWarSightRange`（默认 15，比较在 tile 空间，`TileMap.java:1524-1559`） | `maxAttackRange`（`:2033-2039`）、`nanoRange*`（`:1752-1772`）、`moveSpeed`、`turretSize` |
| 位置场/区域 id 的一切运算 | `posX/posY` |

两个例外：

- `buildingToFootprintOffsetX/Y` 是 **float 但语义是世界单位**（默认 10 = 半格，`CustomUnitConfigParser.java:1709-1710`）。
- 抛射物 `areaRadius` 是世界单位但**不乘 `globalScale`**（`CustomProjectileTemplate.java:50-53`；对比 `CustomUnitConfigParser.java:2078-2085`）。

### 9.3 建筑：两个矩形 + 一个圆

- `cc()` = footprint（命中/清障）；`cd()` = construction footprint（**放置框**，`OrderableUnit.drawPlacementOverlay` 用 `cd()`）；`radius` = 推挤圆。
- Java 建筑：`cc()→buildingTargetRect`、`cd()→buildingVelocityRect`（`BaseBuilding.java:135-142`）；自定义：`cc()→footprint`、`cd()→constructionFootprint`（`CustomUnit.java:3978-3992`）。
- 单位是**格**的铁证：放置判定把 `rectCd.a..c` 直接加到 tile 索引（`OrderableUnit.java:5020-5034`）、`PathCostMap.java:426-436`、`BaseUnit.java:2373-2379`；ini 注释见 `assets/units/extractor/extractor_common.ini:8-12`。

| 建筑 | 命中框 `cc()` | 放置框 `cd()` | radius |
| --- | --- | --- | --- |
| LandFactory | 3×3 | **3×5** | 30（`LandFactory.java:88,93,94`） |
| AirFactory | 3×3 | 3×4 | 30（`AirFactory.java:88,92,93`） |
| SeaFactory | 3×4 | 5×6 | 45（`SeaFactory.java:62,67,68`） |
| CommandCenter | 3×3 | 3×4 | 30（`CommandCenter.java:91,96,97`） |
| RepairBay | 3×3 | 3×3 | 30（`RepairBay.java:84,88,89`） |
| LaserDefense | 2×2 | 2×2 | 19（`LaserDefense.java:183,188,189`） |
| ResourceExtractor | 1×2 | 1×2 | 18（`ResourceExtractor.java:255,259,260`） |
| `c_turret_t1`（ini） | 2×2 | 2×2（offset 20） | 16（`turret_common_land.ini:10-20`） |

### 9.4 单位只有圆

- 碰撞 `isCollidingWith = distanceSq < (r1+r2)²`（`BaseUnit.java:1705-1713`）；`isWithinRect`（`:1687-1693`，半边长=radius 的 AABB）只用于“是否在某矩形内”。
- 放置阻挡 `hasBlockingUnitNearby → isUnitOverlappingRadius`（`OrderableUnit.java:5059-5096`，下限 11 世界单位）；蓝图重叠走 footprint（`BuildPreview.java:170-178`）。
- 实测 radius：builder 10、`c_tank` 11、lightGunship 7、heavyTank/plasmaTank/gunShip 15、heavyInterceptor 14、mechMissile 13、mechMinigun 23、battleShip 20、heavySub 25、heavyBattleship 40、`c_turret_t1`/`c_antiAirTurret` 16、extractorT1 18、laserDefence 19、fabricatorT1 20、landFactory/commandCenter/repairbay 30。

### 9.5 贴图与像素

- zoom = 1 时 **1 世界单位 = 1 像素**（`GameLogic.java:2064-2066`）；唯一全局缩放是相机（`GameEngine.java:1017-1028`）；单位无自身缩放（`getRenderScale()` 恒 1.0，`BaseUnit.java:2027-2029`）。
- 贴图是**水平多帧动画条**：`frameWidth = 贴图宽 / total_frames`（`CustomUnitConfigParser.java:1351-1362`；Java 单位是构造器字面量 → `SizedObject.java:20-24`）。
- `scaleImagesTo : N` = 整图绘成 N 世界单位宽，**只改绘制**（`:1410-1413`）；`imageScale` 默认 1.0。mod 里所有 `#scaleImagesTo` 都被注释（当前 50 处），实际 1:1。
- 实测：`heavy_tank.png` 66×36/3 帧 = 22×36；`tank2.png` 48×30/3 帧 = 16×30；`plasma_tank.png` 52×29/2 帧；`land_factory_front.png` 50×74（`land_factory.png` 50×64 **无引用**）；`air_factory.png` 200×63/5 帧；`sea_factory.png` 90×99。

### 9.6 两个常见误判

| 目测 | 代码真实值 | 贴图真实值 |
| --- | --- | --- |
| 陆军工厂「3×4」 | 放置框 3×5 格、命中框 3×3、圆 r=30 | 前后两层各 50×74 px = 2.5×3.7 格 |
| 重型坦克「1×2.5」 | 无矩形，圆 r=15（直径 1.5 格） | 车身 22×36 = 1.1×1.8 格；含炮塔约 1.1×2.1 格 |

完整换算表另见 `docs/尺寸与距离单位.md`。

## 10. 时间、帧与 fps

```text
SlickGame.kt:361-367  每渲染帧：deltaMillis → gameLoop(legacySlickDeltaSpeed(deltaMillis), deltaMillis)
SlickGame.kt:172      legacySlickDeltaSpeed = deltaMillis * 0.06      // 16.67ms ⇒ 1.0
GameLogic.java:1472-1491
        f = deltaSpeed * gameSpeed * speedMultiplier
        gameTimeMillis += f * 16.6667
        currentTick++
        PlayerTeam.g(f)  → 各队 updateTeam(f) → AIController.updateTeam(f)
```

结论：**游戏速度与 fps 无关**（300 fps 时每帧 `deltaSpeed=0.2`，一帧推进 3.33ms，300 帧仍 = 1 秒）。

| 与 fps 无关（乘了 `f`） | 随 fps 变化（按更新次数） |
| --- | --- |
| 位移/转向/加速（`OrderableUnit.java:905,915-916,1326-1328,3038,3255,5170`） | `currentTick` |
| 原版 AI 节拍 `resourceMultiplierEasy/attackTimerEasy/attackTimerHard += f`（`AIController.java:1463,1498,1511`，阈值 25/80/250） | 任何 `counter++` 式的新逻辑 |
| custom AI 节拍（`AiRuntime.java:81-102` 累加 `deltaFrames`） | 渲染侧每 update 一次的行为（特效等） |

唯一的“加速”来源是 `gameSpeed` / `speedMultiplier`（`GameLogic.java:1476-1479`）；`GameLogic.speedMultiplier` 在 `:237` 初始化为 1.0 且没有其它赋值点（其他类里的同名字段互不相干）。**所以“60 帧 = 1 秒”在任何 fps 下都成立**，AI 回调参数就是这种归一帧。

## 11. 放置与覆盖范围

- 唯一权威入口 `BaseBuilding.canBuildingBePlacedAt(type,x,y,team)`（`BaseBuilding.java:69-77`）：吸附到格、**会改共享原型与 `tileMap.cursorX/Y`** → **不可重入**（回调里嵌套调用会互相污染）。批量试放要串行、逐点调用。
- 逐格链：`OrderableUnit.canPlaceAtCurrentPosition` → `getPlacementFailureReason`（`:5002-5037`）→ `BaseBuilding.a(...)`（`:177-232`）。
- `getTileOffsetX/Y`：从 `posX` 回到基准格原点的世界单位偏移；`BaseUnit` 默认 10，`CustomUnit` 用 ini 值（`CustomUnit.java:4095-4103`）。
- **已知怪点**：`canBuildingBePlacedAt` 计算 `posY` 时也调用了 `getTileOffsetX()`（`BaseBuilding.java:74`）；当自定义建筑的 `buildingToFootprintOffsetX` 与 `...Y` 不相等时，静态放置检查的 Y 会偏。真正的逐格放置路径（`OrderableUnit` 的光标换算）用的是 `getTileOffsetY()`。
- **引擎没有“覆盖范围”机制**：可用半径只有 `unit.m()`（攻击距离）与 `unit.y()`（纳米/维修距离，修复湾 230）；“N 格覆盖”必须自己按圆形范围数单位。
- `type.p()`（`placeOnlyOnResPool`）= 只能建在资源池格上，放置判定跳过 footprint 检查。

## 12. 地图、寻路与空间数据

- 区域 id：`GameViewUtils.b(x,y,mt)`（`-1` 阻挡 / `-2` 越界 / `-3` 无表 / `>0` id）、`.c(x,y,mt)` 区域格数、`.d(x,y)` 液体（水∨岩浆）；只有 `LAND/WATER/HOVER/OVER_CLIFF/OVER_CLIFF_WATER` 有表。
- 静态地形 `PathCostMap.d`（索引 `tileX*height+tileY`）、`PathEngine.a(mt) → PathCostMap`。
- `isPathPossibleForUnit(unit,x,y,range)` 即使带 range 也是**纯欧氏距离**，不是路径（易误用）。
- `isPathPossibleBetweenPoints(...)` 比较两点所属的**地形连通区**（移动类型相关），也不是 A* 路径长度；两者别混用。
- 迷雾 `PlayerTeam.fogOfWarData`（`byte[tileX][tileY]`，10 = 从未见过、≥5 = 当前不可见）；读取前判 `tileMap.fogEnabled` 与非空。
- 资源池 `tileMap.unitObjects`（`ArrayList<Point>`，**tile 索引**）；转世界坐标按原版约定加 `tileWorldSizeY` 偏移。
- 占用 `BaseBuilding.b(tileX,tileY)`；最近空位 `PlacementFinder.findClosestOpenPlacement(x,y,r)`。
- 全局单位表 `BaseUnit.bE`（用 `.a()`/`.size()` 零分配）；`GameObject.objectId` 是 public long；按 id 查找的 `GameObject.a(long,boolean)`/`.b(long,boolean)` 是 O(N)。
- 队伍敌对：`PlayerTeam.c(other)`/`d(other)` 比较 `teamColorId`。
- 一次全图 BFS 是 O(tile 数)，按“锚点未变即复用”缓存。

## 13. 打分与建造的精确数据

### 13.1 打分表要点（`BaseZone.getBestBuildingToBuild()`，`:334-477`）

- 入口 `if (aiController.isAggressive) return null;`；候选需 `isBuildingUnit() && canBuild(type)`，其中 `canBuild`（`:291-318`）要求 demo 可用、`!type.w()`，且基地范围内**存在拥有该建筑动作的单位**。
- 资源点建筑（`type.p()`，非自定义）：`findClosestResource() != null` 且 90% 随机通过时，`i=0` → `0.98`（资金 <5000）/`0.58`；`i=1` → `0.55`；`i=2` → `0.4`；`i>=3` → `0.25/i`；`assignTask ≥ 3` → ×0.6（`:357-380`）。
- `antiAirTurret`：`i=0 → 0.42`；`i<3 → 0.3/i`；`i<4 → 0.02/i`（Insane：`i<4 → 0.3/i`）（`:418-430`）。
- `fabricator`：资金 >2000 且 `i<5` → `0.11` 或 `0.07/(0.2*assignTask+i)`；`AntiNukeLaucher` → `0.06`/`0.05/(...)`；`laserDefence`（`i=0`）→ `0.018`；`repairbay`（`i=0`）→ `0.02`；`experimentalLandFactory`（hard+ 且资金 >15000）→ `0.04`，>55000 → `0.03`。
- 自定义建筑（`:445-462`）：`f2 = buildPriority`；`i < recommendedInEachBaseNum` → `recommendedInEachBasePriorityIfUnmet`；`i == 0` → `+= nonInBaseExtraPriority`；`assignTask == 0` → `+= nonGlobalExtraPriority`；`relatedUnits` 的 assignTask/数量累加进同一个 `i`；`type.p() && findClosestResource() == null` → `-2`（排除）。
- 注入钩子：`adjuster.adjust(type, f2)` 在原版分数之后运行，返回负数即排除；无参版本行为不变。
- 选择：`f2 >= 0 && (f2 > f || random < 0.01)` → **平手时先遍历到的赢**（`ae` 中内置枚举通常在前）；结果写入 `lastFoundBuildingPriority`（`:480`，读取 `:1255`）。

### 13.2 建造节奏与资金阶梯（`BaseZone.updateZoneStrategy`）

- 只在 `defensiveScore <= 0` 时尝试，随后重置为 `270 + strategyId % 15`（Insane：`300 + strategyId % 15`），约 270~315 帧一次（`:1342-1357`）。
- 上一次建筑优先级过低会额外推迟：`lastFoundBuildingPriority < 0.2` 再 +180 帧，`< 0.08` 再 +180 帧（`:1347-1352`）。
- 资金阶梯（优先级 → 最低存款）：`0.8/0.4/0.2/0.1/0.05/0.01 → 1300/1700/2100/2800/3100/4800`（`:1357`）。
- 发令失败 `defensiveScore -= 120`（`:1360`）。
- `lastTimeAttackedByEnemy` **不是“最近受击”**：它在生产出一个非战斗 custom 单位（运输）时被置为 1000（`:1518`），作为运输补产的冷却闸门；平时只 `moveTowardsZero` 衰减。

## 14. 升级机制细节

与 §4 的接管说明配合阅读。

- 两套体系：
  - **Java 内置建筑**（陆/空/海工厂、极光防御）：升级动作 `getUnitType()` 为 `null`，只有一条路线 → 用 `unit.cm()` 的“默认升级动作”语义；
  - **mod 自定义单位**（矿脉/制造仪/炮台/机甲工厂）：`[action_x] convertTo : 目标`，`CustomAction.getUnitType()` 就是**目标类型**（`CustomUnitConfigParser.java` 的 `convertTo` 解析）。
- 动作类型：`units/actions/ActionType`（13 值）与 `units/custom/logic/ActionType`（`upgrade`/`launch`/`launchAmmo`，只有 `CustomAction` 实现 `getActionTypeForUnit`）；显示类型 `ActionDisplayType.upgrade`。
- 可用性四连：`!isAiDisabled(u)`、`b(u)`、`canAfford(u,false)`、`!isNotAvailable(u)`；不要硬编码 action id（同一 id 被多个建筑复用）。
- 反核补弹同样走 `cm()` 抽签 → 接管升级后必须自行补发；核弹 `launchAmmo` 走 `NukeBehavior`，不受影响。

## 15. 构建、测试与验证

| 命令 | 说明 |
| --- | --- |
| `.\gradlew.bat :core:compileJava` | 主源码编译（最快反馈） |
| `.\gradlew.bat :core:test --tests '*AiIntegrationTest'` | AI 集成测试（当前 20 例：读档、注册、命名、UI 选项等） |
| `.\gradlew.bat :desktop:compileJava :mod-tools:compileKotlin` | 依赖 core 的模块编译（`:ui:classes` 任务不存在） |

- 报告路径：`core/build/test-results/test/TEST-com.corrodinggames.rts.game.ai.strategy.AiIntegrationTest.xml`。
- 测试环境**没有地图与单位表**（`UnitTypeEnum.ae` 会被清空）→ 涉及真实地图/单位的行为只能实机验证。

## 16. 单位目录（mod 实际生效）

| 单位 | 内部名 | 价格 | 生产来源 | radius |
| --- | --- | --- | --- | --- |
| 建造者 | `builder` | 500 | — | 10 |
| 战斗工程师 | `combatEngineer` | 3500 | landFactory / experimentalLandFactory | — |
| 机械工程师 | `mechEngineer` | 5000 | `mechFactoryT2` | — |
| 小坦克 | `c_tank`（覆盖 `tank`） | 350 | landFactory | 11 |
| 重型坦克 / 等离子坦克 | `heavyTank` / `plasmaTank` | 800 / 1000 | landFactory(L2) | 15 / 15 |
| 机枪机甲 / 喷火机甲 | `mechMinigun` / `mechFlame` | 5000 / 12000 | `mechFactoryT2` | 23 / — |
| 火炮机甲 / 基础机甲 / 防空机甲 | `mechArtillery` / `mechGun` / `mechMissile` | 1400 / 600 / 900 | `mechFactory`、`mechFactoryT2` | — / — / 13 |
| 移动炮台 | `mechBunker` / `mechBunkerDeployed` | 4500 / 0 | `mechFactory`、`mechFactoryT2` | — |
| 机甲工厂 | `mechFactory` / `mechFactoryT2` | 1000 / 5000 | `builder` / 升级 | — |
| 重型拦截机 / 武装直升机 | `heavyInterceptor` / `gunShip` | 1200 / 800 | airFactory | 14 / 15 |
| 微型小飞机 / 直升机 / 轰炸机 | `lightGunship` / `c_helicopter` / `bomber` | 250 / 700 / 4000 | airFactory | 7 / — / — |
| 两栖喷射机 / 拦截机 / 导弹飞艇 | `amphibiousJet` / `c_interceptor` / `missileAirship` | 2000 / — / — | airFactory | — |
| 战列舰 / 重型战舰 / 重型潜艇 | `battleShip` / `heavyBattleship` / `heavySub` | 1500 / 6000 / 5500 | seaFactory(L1/L2/L2) | 20 / 40 / 25 |
| 提取器 | `extractorT1`→`T2`→`T3`(`_overclocked`) | 升级 1400 / 4000 / 8000 | 资源池 | 18 |
| 资源制造仪 | `fabricatorT1`→`T2`→`T3` | 2200，升级 4400 / 7500 | — | 20 |
| 炮台 | `c_turret_t1`(500) → `c_turret_t2_gun`/`_flame`/`t1_artillery`/`t1_lightning`（升级 1000/700/1600/2200）→ `c_turret_t3_gun`/`c_turret_t2_lightning`（11000/8000） | — | 由 `c_turret_t1` 升级 | 16 |
| 防空炮台 | `c_antiAirTurret` → `c_antiAirTurretT2` | 600 / 升级 1200 | — | 16 |
| 极光防御 / 修复湾 / 指挥中心 | `laserDefence` / `repairbay` / `commandCenter`（均 Java） | 1200 / 1500 / 3000 | — | 19 / 30 / 30 |
| 反核发射井 | `antiNukeLauncherC` | 15000 | builder | — |

## 17. 源码入口

| 职责 | 入口 |
| --- | --- |
| AI 总调度、custom 覆盖闸门、难度分支、可达性、前哨、编队创建 | `game/ai/AIController.java` |
| 基地状态、建筑选择/下单、工厂选择、生产、增援与 Zone 生命周期 | `game/ai/BaseZone.java` |
| 候选池策略、移动/价格过滤与抽签 | `game/ai/UnitBuildStrategy.java` |
| 原版随机建筑选址 | `game/ai/AIStrategyNode.java::findBuildLocation` |
| 工程单位执行 build/repair 命令 | `game/units/OrderableUnit.java::updateBuildCommand` |
| 单位命令的 build 表达 | `game/units/UnitCommand.java::setBuildCommand` |
| 工厂队列、进度、分段付费 | `game/units/buildings/FactoryQueueManager.java` |
| 队列完成后创建和出厂 | `game/units/buildings/FactoryWithQueue.java` |
| 编队攻击、防守、目标选择、待运输单位标记 | `game/ai/UnitGroup.java` |
| 集结待运输单位 | `game/ai/RallyGroup.java` |
| 装载、运送、卸载与运输任务轮询 | `game/ai/TransporterGroup.java` |
| 特殊单位行为，例如核弹 | `game/ai/behaviors/NukeBehavior.java` |
