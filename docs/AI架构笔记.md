# RWX（铁锈战争）AI 架构笔记

> 面向改 AI 的人。结论都带 `文件:行号` 出处，标 **[已核实]** 的表示我在源码里逐条验证过；
> 标 **[待确认]** 的表示只有间接证据。
>
> 基线：源码提交 `5f96579`（2026-10-17 整理）。行号会随代码改动漂移，符号名不会。
>
> 覆盖范围：`core/src/main/java/com/corrodinggames/rts/game/ai/`（高层 AI）、单位级 auto AI 的入口与配套、
> 以及 mod 影响 AI 的三条通道。反编译现状与混淆符号对照见第 5、6 节。

---

## 0. 速览

这个游戏的 AI 是**两套**，彼此只通过"命令"耦合：

| | 第一套：单位级 auto AI | 第二套：高层 AI |
|---|---|---|
| 是什么 | 每个单位自己的行为：寻路、转向、自动索敌、开火、碰撞避让、编队 | 玩家在房间里选的 简单/普通/困难/疯狂 AI：造什么、造多少、什么时候打 |
| 主要文件 | `game/units/OrderableUnit.java`（5460 行）、`gameFramework/FormationEngine`+`FormationGroup`、`gameFramework/path/PathEngine` | `game/ai/` 整个目录（19 文件 / 6018 行） |
| 谁触发 | 单位自己的 `update(f)`，每帧 | `AIController.updateTeam()`，错峰计时器 |
| 数据驱动 | 硬编码 + 自定义单位的 ini 钩子 | 13 张 `UnitBuildStrategy` 配比表 + `[ai]` ini 字段 |
| 交汇点 | `AttackMode`（高层设，单位执行）、`UnitCommand`（命令体） | 同左 |

**所有对外动作都走同一个入口**：`CommandController.newCommandForTeam(team)` → `Command` → 单位的 `UnitCommand`。
高层 AI 和玩家的鼠标点击走的是同一条路径，所以写 AI 时不要绕过它。

---

## 1. 两套 AI 的分界与数据流

```
玩家点击 ─┐                                              ┌─ OrderableUnit 每帧状态机（17 种 UnitCommandType）
          ├→ CommandController.newCommandForTeam() → Command ┤
高层 AI ──┘        （唯一发令入口，含网络/回放记录）        └─ AttackMode / 目标 / 路径点
```

- 高层 AI **只发命令**，不碰单位内部状态。
- 唯一的例外是高层 AI 回写的几个"记账字段"（都在 `OrderableUnit` 上）：
  `aB`（所属编组）、`aC`（所属基地）、`aD`（到基地的可达性缓存）、`isVisible`。见 [6.1](#61-方法与字段)。
- 寻路是共用的：高层只用它做"能不能到"的布尔判断（`AIController.isPathPossibleBetweenPoints`），
  真正的路径由单位层 `OrderableUnit.navigateToPosition` 调 `PathEngine` 算。

---

## 2. 第二套：高层 AI（`core/.../game/ai/`）

### 2.1 类与入口

- `AIController extends PlayerTeam` —— 一个 AI 玩家**就是**一支队伍对象，不是挂在队伍上的组件。
- 创建点：`GameLogic.java:659`、`GameLogic.java:2275`、`GameSaver.java:526/532`。
- 每帧入口：`PlayerTeam.updateAllTeams()`（`PlayerTeam.java:1703`）遍历所有队伍调 `updateTeam(f)`，
  落到 `AIController.updateTeam`（`AIController.java:1281`）。
- **时间单位是 1/60 秒（帧）**：证据 `SlickGame.legacySlickDeltaSpeed(deltaMillis) = deltaMillis * 0.06`，
  以及 `AIController.secondsToMilliseconds(f) = f * 16.67`。所以代码里的 `80 / 250 / 5000` 要按帧换秒理解。

### 2.2 节拍（`AIController.updateTeam`）

| 计时器 | 阈值 | 动作 | 层 |
|---|---|---|---|
| `attackTimerEasy` | > 80 帧（≈1.3s） | `updateAILogic()` | 战术 |
| `attackTimerHard` | > 250 帧（≈4.2s） | `updateCoreLogic()` | 战略/经济 |
| `resourceMultiplierEasy` | > 25 帧（≈0.4s） | 给**所有** `BaseZone.resourceScore` 累加，取分数最高且 ≥50 的**前 2 个**基地执行 `updateZone()` + `updateZoneStrategy()`，然后清零 | 基地经济（错峰轮转） |

> 注：基地经济不是每帧全量跑的，而是"攒分 → 拍卖 → 前两名更新"。改 AI 经济节奏就动这三个阈值。

### 2.3 战术层 `updateAILogic`（`AIController.java:1862`）

1. 所有 `aiBehaviors.updateAI()`（见 [4.3](#43-jvm-mod-api-apiai接管高层)）。
2. 所有 `AIUnitGroupBase.updateAI()`（编组的战术行为）。
3. 躲 `DamageZone`：区域落在危险边界上就销毁基地 / 把编组推开。
4. **维护编组配额**（这是"AI 为什么不出兵/不扩张"最常出问题的地方）：
   - `builderGroupCount < 1~3`（随难度、`isAggressiveMode`、`advancedBaseCount` 变）→ `new UnitGroup(false)`（工程组，`maxUnits` 8，疯狂 10）
   - `waterBuilderGroupCount >= N || totalBuilderUnits > 6` 且 `landAttackGroupCount < 1` → 开进攻组（`maxUnits` 按 `mapWidth` 计数 3/5/7，疯狂 14/18）
   - `isPathfindingOverloaded()` 且 `airAttackGroupCount < 1` → 开海军组（`isSeaGroup = true`）
   - `isAttackBlockedByConditions()` 且 `transporterGroupCount < 3` → 开运输队（`capacity = 1`）
5. 给空闲战斗单位下发"去最近基地"的移动命令；清理卡住 2400 帧的命令。

### 2.4 战略层 `updateCoreLogic`（`AIController.java:1513`）

1. 清建造缓存；所有 `aiBehaviors.updateCore()`。
2. 遍历全图单位：补 `isVisible`、重新绑定最近 `BaseZone`（`aC`）、缓存可达性（`aD`）。
3. `updateSpecialUnits()`：潜艇/两栖 jet 的潜浮切换、轰炸机的强制攻击目标。
4. 逐单位设 `AttackMode`（`getAttackModeForUnit`）；实验单位自动 `UnitGroup.createHuntGroup()`。
5. `globalTimer`（≈58s）到点 → 尝试新建 **ResourceOutpost** 基地（要过 `isSafePointForOutpost`）。
   `attackTimersUpdateCounter`（≈1.7s）到点 → 尝试新建 **ForwardOutpost**（要过 `assignTaskToUnitType`，全图最多 3 个）。
6. 经济/升级决策：`credits` 阶梯阈值 + 随机数抽签，决定给谁下"升级/建造"动作（`issueCommand(unit, actionIdCm)`），
   成功后把 `resourceMultiplierHard` 置 900（≈15s 冷却）。疯狂难度 `globalTimer` 每帧多扣 4 倍。

### 2.5 逐文件职责

| 文件 | 行数 | 职责 |
|---|---|---|
| `ai/AIController.java` | 2352 | AI 玩家本体：计时器、编组配额、基地创建、经济与升级抽签、存档序列化、调试浮层 |
| `ai/BaseZone.java` | 1467 | 基地/前哨：统计、修理、采集调度、求援、建造打分、出兵 |
| `ai/UnitGroup.java` | 815 | 作战编组：招募、集结→冲锋、撤退、解散、集结点选址 |
| `ai/TransporterGroup.java` | 419 | 运输队：找等运输的编组、装载/卸载、投送、超时销毁 |
| `ai/AIStrategyNode.java` | 213 | 节点基类：`strategyId` + 圆心/半径 + 几何判定 + `findBuildLocation()` |
| `ai/UnitBuildStrategy.java` | 135 | 单位配比表 + 按权重轮盘抽签（`getRandomUnitType*`） |
| `ai/AIUnitGroupBase.java` | 121 | 带单位列表的节点基类（与单位的 `aB` 双向绑定） |
| `ai/RallyGroup.java` | 92 | 等运输的集结点，`lifetime > 5000` 帧（≈83s）自动解散 |
| `ai/behaviors/NukeBehavior.java` | 87 | 核弹行为（**目前无人注册**，见 [7.1](#71-behaviors-是只读回不产生)） |
| `ai/AIUnitActionUtils.java` | 73 | 静态工具：空闲判定、随机取元素、`aiTags` 判定、按 `ActionType` 找动作 |
| `ai/BuildPreferenceCache.java` | 61 | 一轮 AI 内的计数缓存（全队 / 含队列 / 建筑） |
| `ai/behaviors/UnitAIBehavior.java` | 57 | 带托管单位列表、可存档的行为基类 |
| `ai/behaviors/AIBehavior.java` | 38 | 行为钩子接口（updateAI / updateCore / onUnitAdded…） |
| `ai/behaviors/AIBehaviorType.java` | 25 | `unknown` / `nuking` + `createBehavior()` 工厂（给回读用） |
| `ai/UnitBuildPriority.java` | 23 | `{unitType, priority}` |
| `ai/BaseZoneStage.java` | 14 | `Pre` / `Prepare` / `Active`（`Prepare` 实际没被赋值过） |
| `ai/PlainZone.java` | 9 | 空壳节点，兼容旧存档 zone type 0/4 |
| `ai/BaseZoneType.java` | 9 | `Main` / `ResourceOutpost` / `ForwardOutpost` |
| `ai/UnitFilterMode.java` | 8 | `include` / `exclude` |

### 2.6 节点家族

```
AIStrategyNode（id + posX/posY/radius + isPointInside/isUnitInside/findBuildLocation）
├── BaseZone           基地/前哨：经济、建造、修理、采集
├── PlainZone          空壳（存档兼容）
└── AIUnitGroupBase    带 units / unitsNeedingTransport
    ├── UnitGroup         作战编组
    ├── RallyGroup        等运输的集结点
    └── TransporterGroup  运输队
```

节点靠 `strategyId` 做存档引用：`createZoneByTypeId(int)`（`AIController.java:643`）↔ `findNodeById(int)`。

### 2.7 13 张建造配比表（字段名不可信，看语义）

构造函数（`AIController.java:381-551`）里用 13 个匿名 `UnitBuildStrategy` 定义"哪些单位算哪一类"。
**字段名和它自己的调试字符串是错位的**，语义只能看 `canBuildUnit` 实现：

| 字段名 | 内部调试名 | 实际语义（按 `canBuildUnit`） |
|---|---|---|
| `builderUnitBuildStrategy` | `attackingUnitsLand` | 陆军攻击单位（`shouldIssueActionForUnitType` + LAND） |
| `landUnitBuildStrategy` | `attackingUnitsHover` | 悬浮攻击单位 |
| `airUnitBuildStrategy` | `attackingUnitsAir` | 空军攻击单位 |
| `seaUnitBuildStrategy` | `attackingUnitsWater` | 海军攻击单位 |
| `experimentalUnitBuildStrategy` | `buildingUnits` | **所有建筑**（`findAttackDamageSource(t).bI()`） |
| `antiNukeUnitBuildStrategy` | `transportUnits` | 非战斗运输单位（空中/悬浮/越崖水面） |
| `baseDefenseUnitBuildStrategy` | `transportUnitsFlying` | 空中运输 |
| `scoutUnitBuildStrategy` | `transportUnitsNonFlying` | 地面/水面运输 |
| `fabricatorUnitBuildStrategy` | `builderUnits` | 工程车（`UnitType.m()` = useAsBuilder，非水面） |
| `extractorUnitBuildStrategy` | `harvesterUnits` | 采集车（`UnitType.n()` = useAsHarvester，非水面） |
| `landFactoryUnitBuildStrategy` | `extractorUnits` | 资源点建筑（`UnitType.p()` = placeOnlyOnResPool） |
| `airFactoryUnitBuildStrategy` | `buildingFactories` | 会生产单位的工厂（有 `PopupQueueAction` 且产出非建筑） |
| `seaFactoryUnitBuildStrategy` | `buildingFactoriesForBuilders` | 会生产工程车的工厂 |

`rebuildUnitMix()`（在 `initializeBuildStrategies()` 里调用，编辑器改动单位表时也会重跑，见
`EditorOrBuilder.java:2799/2820`）按 `UnitTypeEnum.ae` 重建表；`getRandomUnitTypeByMovement()` 按权重抽签。

### 2.8 `BaseZone` 关键机制

- 生命周期：`Pre → Active`；`stage` 决定 `UnitGroup`/`TransporterGroup` 愿不愿意认这个基地。
- **分数是节奏器**：`resourceScore`（上层拍卖）、`defensiveScore`、`reclaimScore`、`landUnitsScore`、
  `airUnitsScore`、`waterUnitsScore`、`extractorScore`，都用 `Utility.moveTowardsZero(t, f)` 递减。
  `reclaimScore` **随存款阶梯上涨**（800/1600/2200/2600/5000/8000/9000/10100/30000 各加一档），
  钱少且没被打时被压回 1.2，上限 3.5。
- 建造打分 `getBestBuildingToBuild()`（`BaseZone.java:321`）：给每种可造建筑算 0~1 优先级，
  硬编码了对陆工厂(0.8/0.46)、海工厂(0.3/0.1)、空工厂(0.48/0.29)、炮塔(0.47)、防空(0.42)、
  激光防御(0.018)、维修湾(0.02)、工程车(0.11/0.07)、反核(0.06/0.05)、实验工厂(0.04/0.03) 的表；
  自定义单位用 mod 的 `buildPriority`/`maxEachBase`/`maxGlobal`/`recommendedInEachBase*`/`non*ExtraPriority`。
  结果存进 `lastFoundBuildingPriority`，上层用它判断"现在钱够不够造好东西"。
- 出兵 `tryBuildCombatUnits()`（`BaseZone.java:1362`）：按 `isReadyForAttack` / `hasSufficientUnitsForAttack`
  调陆/悬浮/空三种比例（默认 0.4/0.3），按 `reclaimScore` 门槛决定"造攻击单位"还是"造防御/反核单位"，
  成功一次就把 `reclaimScore -= 1`。
- 后勤：`buildRecommendedHarvesters` / `sendHarvesterToReclaim` / `retargetHarvestersToBetterBuilding` /
  `repairDamagedBuildings` / `requestReinforcement`（从最近的支援基地借一个闲置战斗单位）。
- `fixOverlaps()`：两个基地距离 <20 世界单位就按 `stage` 淘汰一个。

### 2.9 `UnitGroup` / `TransporterGroup` / `RallyGroup`

- **UnitGroup**：`recruitNearbyUnits()` 按 `maxUnits` 收编闲置战斗单位；`updateAttackAI` 里
  **先集结 17s 再冲锋**、血低于 50% 触发撤退（`retreatWhenDamaged`）、11s 啃不动就解散；
  `repositionNearAttackTarget()` 选集结点时会算路径，走不到的单位进 `unitsNeedingTransport` 转交运输队。
  `createHuntGroup()` 给实验单位单独拉一个猎杀组。
- **TransporterGroup**：`recruitTransportUnits()` 收运输单位 → `findGroupNeedingTransport()`（80% 优先 RallyGroup）
  → 飞到集合点用 `setLoadIntoTarget` / `getUnloadActionId` 装卸 → `completeDelivery()` 后重新选点；
  空置 1500 帧销毁。
- **RallyGroup**：定期剔除已走到位的单位；组空或 `lifetime > 5000` 帧销毁。

### 2.10 难度从哪来

```java
// GameLogic.java:752-755（读档/开局时写入队伍）
playerTeam.teamPingTime = teamAIDifficultyOverride != null
        ? teamAIDifficultyOverride : roomSettings.aiDifficulty;
// AIController.java:1293
this.difficultyLevel = getTeamColorId();   // PlayerTeam.getTeamColorId() 最终返回 teamPingTime
```

`isInsaneDifficulty()` = `3 || >300`；`isHardOrAboveDifficulty()` = `>= 2`。
难度**只调配额和概率**，不改单位层行为：放大 `maxUnits`、放宽 credits 阈值、加快计时器
（`updateCoreLogic` 里疯狂难度 `globalTimer` 每帧扣 5 倍）。

---

## 3. 第一套：单位级 auto AI（不在 `ai/` 目录）

### 3.1 单位行为总成：`OrderableUnit.java`（5460 行）

| 能力 | 方法（行号） |
|---|---|
| 每帧主循环 | `update(f)` @846 |
| 命令状态机 | move/attackMove/patrol @1710、attack @1999、guard/follow @1465、build @1818、repair/reclaim @2146、loadUp @1670、loadInto @2056、touchTarget @1606、triggerAction @1431、setPassiveTarget @1453 |
| 移动驱动 | `updateMovementLogic` @2485 → `navigateToPosition` @2571 → `applyMovementToward` @2881；转向受 `getMaxTurnSpeed()` 限制 |
| 自动索敌/开火 | `canAutoAcquireTargets` @3133、`updateAttackTargeting` @3145、`updateAttackTargetState` @1910、`syncAttackTargetToMovementLevels` @3097 |
| 碰撞避让 | `updateAllUnitCollisions` @476、`resolveSoftCollisionWithUnit` @1068 |

命令种类就是 `UnitCommandType`（17 种）：`move / attack / build / repair / loadInto / unloadAt / reclaim /
attackMove / loadUp / patrol / guard / guardAt / touchTarget / follow / triggerAction /
triggerActionWhenInRange / setPassiveTarget`。

### 3.2 寻路

- `gameFramework/path/PathEngine.java`：全局代价图 + `PathSolver` 多线程求解（日志 "We have 16 cores, creating extra solvers"）。
- `game/units/PathfindingUtils.java`：单位的可达性判定（`PathfindingUtils.a(unit, target)`，AI 也用它筛目标）。
- `game/units/UnitPathPoints.java`：路径点存储。
- 连通性靠 `GameViewUtils` 的"孤立区域 id"（`GameViewUtils.b(x, y, movementType)`），
  代价图里 `PathCostMap.i` 是孤立区域最大值，`AIController.isPathfindingOverloaded()` 用它判断地图碎片化。

### 3.3 阵型（群体移动自动排阵）

- `gameFramework/FormationEngine.java` + `FormationGroup.java`；槽位由
  `FormationEngine.a(count, radius, angle)`（`FormationEngine.java:146`）按 6 列算出偏移。
- **实现上是复用"运输/挂载"关系**：领队被 `setTransportParent()`，其他单位
  `isTransportAttached` + `waypointSyncGroupId` + `UnitCommand.transportTarget` 挂在同一组上，
  所以"编队"本质是一条虚拟运输链。相关单位字段：`shouldMaintainFormation`、`formationSlotIndex`、
  `formationSlotAngle`、`formationSlotDistanceSq`、`inFormation`、`isFormationMember`。

### 3.4 两套 AI 的唯一策略交汇点

`AttackMode`（`game/units/AttackMode.java`）：`outOfRange / onlyInRange / returnFire / holdFire /
guardArea / aggressive / mixed`。高层 AI 只负责挑一个模式下发（`AIController.getAttackModeForUnit`），
单位层负责执行；玩家 UI 也能改（`AttackModeAction`）。

### 3.5 其他配套

- 空间查询：`units/management/UnitSpatialIndex.java`、`NearestUnitFinder`、`SingleTargetPassiveCallback`、
  `MultiTurretPassiveTargetCallback`。
- 命令数据结构：`UnitCommand.java`（目标点/目标单位/`actionId`/建造类型/队列/`transportTarget`）。
- mod 可控的单位级钩子：`units/custom/hooks/RandomMovementHook`、`RepelFromUnitsMovementHook`（见第 4 节）。

---

## 4. mod 影响 AI 的三条通道

| 通道 | 载体 | 作用层 | 现状 |
|---|---|---|---|
| ① ini `[movement_*]` + LogicBoolean 表达式 | `units/custom/hooks/*` → `CustomUnitConfig.onCreateListeners` | **第一套（单位级）** | `RandomMovementHook` 有效；`RepelFromUnitsMovementHook` **空实现** |
| ② ini `[ai]` 段 | `CustomUnitConfig` 字段（`mod-api/.../specs/AiSpec.kt`） | **多数是第二套，两个字段是第一套** | 完整生效 |
| ③ JVM mod API `api.ai` | `mod-api/.../AiBehavior.kt`，实现 `core/.../mod/impl/ApiImpl.kt:756` | **接管第二套** | 已实现 |

### 4.1 通道①：`[movement_*]` 钩子（单位级）

挂载链路：`CustomUnitConfigParser.java:1473-1474` 解析 ini 段 → `Hook.a(config, ini)` →
`customUnitConfig.a(hook)`（进 `onCreateListeners`）→ `CustomUnit.java:1813-1815` **每帧**遍历调 `hook.b(unit, f)`。

- `RandomMovementHook`（`[movement_random]`）：滑行单位改 `velocityX/Y`，其他改 `rotation/rotationSpeed`；
  用 `Utility.getDeterministicRandomFloatForUnit`（**确定性随机**，联机/回放一致）；`awayFromEdge` 内做边缘回推。**有效**。
- `RepelFromUnitsMovementHook`（`[movement_repelFromUnits]`）：`b()` 里只有 `if (!enabled.read(unit)) { }`，
  `speed` / `maxSpeed` / `otherUnitHasTag` / `onlySameTeam` 四个字段**只赋值从不读取** → **配了不生效**。
- `enabled` 是 LogicBoolean 表达式，所以 ini mod 也能写条件（custom 的脚本语言，`logicBooleans/` 10 文件 408KB + `logic/` 144KB）。

### 4.2 通道②：ini `[ai]` 段（跨两套）

- 给第二套（高层）看的：`useAsBuilder`/`useAsAttacker`/`useAsTransport`/`useAsHarvester`/`disableUse`/
  `ai_upgradePriority`（→ `AIController.updateCoreLogic` 的升级抽签）/`buildPriority`/`maxEachBase`/`maxGlobal`/
  `recommendedInEachBase*`/`non*ExtraPriority`/`whenUsingAsHarvester_*`（→ `BaseZone` 的打分与采集）。
- 其实给**第一套（单位）**看的：`notPassivelyTargetedByOtherUnits`、`lowPriorityTargetForOtherUnits`
  （影响被动索敌优先级，`CustomUnitConfigParser.java:2322-2323`；给能攻击的单位配前者会直接抛异常）、
  `aiTags`（被 `AIUnitActionUtils.hasAiTag` 读取，`nukeLauncher` 就是它）。
- 键的权威文档是官方 `Rusted_Warfare__Beta_Modding_Reference.xlsx`（**不在仓库里**，`AiSpec.kt` 由它生成）。

### 4.3 JVM mod API `api.ai`（接管高层）

- 接口：`registerAgent` / `unregisterAgent` / `bindAgent` / `unbindAgent` / `listPlayers` / `getPlayerState` /
  `configurePlayer` / `setPlayerEnabled` / `currentObservation` / `submitActions`。
- 模式：`AiPlayerMode = HUMAN / GAME_AI / EXTERNAL_AGENT`。
- `submitActions` 会先校验 scope（`AiControlScope`：teamIds / unitIds / unitTypeIds）、动作空间
  （`allowedTypes` / `maxUnitsPerAction` / `maxActionsPerTick` / `allowQueuedCommands`），再走 `commands.submit(action)`，
  最终和内置 AI、玩家点击一样落到 `UnitCommand`。
- **注意点 [待确认]**：`configurePlayer` 只写 `team.isTeamControlledByAI` / `teamAIHint` 并记录 config；
  `AIController.updateTeam` 里**没有**读取这两个字段提前让位的分支（`isTeamControlledByAI` 在别处是
  "这队已经有高级单位了"的 UI 标记，`GameUI.java:1441`）。也就是说：队伍实例本身是 `AIController` 时，
  注册 external agent 并不自动让内置 AI 停手 —— 真要接管得从队伍类型层面处理。

### 4.4 `[ai]` 段与第一套的接口面（`CustomUnitConfig`）

`useAsAttacker` / `useAsTransport` / `aiTags` / `disableUse` / `buildPriority` / `maxGlobal` / `maxEachBase` /
`recommendedInEachBaseNum` / `recommendedInEachBasePriorityIfUnmet` / `nonInBaseExtraPriority` /
`nonGlobalExtraPriority` / `whenUsingAsHarvester_*` / `upgradedFrom` —— 改 AI 行为最省事的入口。

---

## 5. 反编译与映射现状

### 5.1 结论：这是"反编译 + 部分反混淆"，不是原始源码

证据：

1. 每个文件头都有 `/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/a/a.class */`
   —— 源头是官方发布 jar（原始类名就是 `com.corrodinggames.rts.game.a.a` 这种混淆名）。
2. 每个可读成员头上留着一行 `/* JADX INFO: renamed from: a */` —— 改名工具保留的原名，手写代码不会有。
3. commit `3648e0f "sync mapping"`（2026-09-27）同时改了 `ai/`、`Projectile`、`Tree`、`OrderableUnit` 等一大批文件，
   而 diff 是**纯改名、逻辑一行没动**（例：`BuildPreferenceCache` 的 `HashMap a/b/c` →
   `unitCountWithQueueCache/unitCountCache/buildingUnitCountCache`）。
4. 映射表本身**不在仓库里**（`git ls-files` 里没有 mapping/deobf 文件），是"仓库外维护 → 定期 sync 进源码"。

### 5.2 `ai/` 目录的量化（19 文件 / 6018 行）

| 项目 | 数量 |
|---|---|
| 字段声明 | 184 个，**混淆名只剩 1 个**：`AIController.bI`（内部临时 `UnitList`） |
| 方法名（去重） | **224 个可读 / 8 个混淆**，那 8 个全是同一个 `a(GameOutputStream)`（`Serializable` 序列化方法，7 个还带 `@Override`） |
| `renamed from` 注释 | 439 条 |
| **jadx 局部变量残留** | `z/z2/z3` 195 次（3.2/百行）、`f2/i3/n12` 类 634 次（10.5/百行）、`varN` 89 次、空 `if` 残块 6 处 |

**结论**：`ai/` 内部已经基本可读；真正的摩擦是它调用的**外部 API**，而且高度集中（见第 6 节）。
不可恢复的那部分（局部变量名）只能靠读代码，没有捷径。

---

## 6. 混淆符号对照表（`ai/` 依赖的外部 API）

### 6.1 方法与字段

| 符号 | 语义 | 证据 |
|---|---|---|
| `BaseUnit.bE` | **全图存活单位列表**（`UnitList`），AI 到处遍历 | `BaseUnit.java:91`，`bE.add(this)` @420 / `bE.remove` @914,925 |
| `BaseUnit.bI()` | **是否建筑** | `BaseBuilding.java:370` `return true`；`CustomUnit.java:3619` 返回 `unitConfig.isBuildingUnit` |
| `BaseUnit.bs` | **最近一次受伤的时间戳**（game millis，初始 -9999） | `BaseUnit.java:372` 初始化、`BaseUnit.java:1612` 受伤分支赋值 |
| `BaseUnit.u()` | `isExcludedFromDefeatCheck()` 的底层 | `BaseUnit.java:2267` + 同名可读 wrapper 转发 |
| `BaseUnit.t()` | `isUnselectableAsTarget()` 的底层 | `BaseUnit.java:2282`；`DamageZone.java:246` 覆写为 true |
| `BaseUnit.Q()` | **是否位于水面以下**（`posZ <= -1`） | `BaseUnit.java:354` 抽象；`CustomUnit.java:641` `posZ <= -1.0f`；`BaseBuilding.java:274` false |
| `BaseUnit.cm()` | **当前执行的动作 id**（`ActionId`） | `BaseUnit.java:1812` 默认 `NONE_ACTION_ID`；各建筑覆写 |
| `OrderableUnit.aB` | AI 记账：所属 `UnitGroup` | `OrderableUnit.java:218` |
| `OrderableUnit.aC` | AI 记账：所属 `BaseZone` | `OrderableUnit.java:220` |
| `OrderableUnit.aD` | AI 记账：到 `aC` 的可达性缓存 | `OrderableUnit.java:222` |
| `OrderableUnit.aN` | **是否正锁着修理/回收目标** | `OrderableUnit.java:264`；`BuilderUnit.java:162` `aN = getCurrentRepairOrReclaimTarget() != null` |
| `OrderableUnit.I()` | **本体是否参与移动与碰撞**（建筑/生成器/特效=false） | `MovableUnit.java:11` true；`BaseBuilding.java:259` false；`CustomUnit.java:598` 条件 false |
| `OrderableUnit.aS()` | `canExecuteMovementCommands()`（`I()` 的可读别名） | `OrderableUnit.java:4493` |
| `UnitType.m()` | **useAsBuilder**（AI 可用于建造） | `CustomUnitConfig.java:1562` `return this.useAsBuilder;` |
| `UnitType.n()` | **useAsHarvester**（AI 可用于采集） | `CustomUnitConfig.java:1567` |
| `UnitType.p()` | **placeOnlyOnResPool**（只能造在资源点） | `CustomUnitConfig.java:1577` |
| `UnitType.l()` | **isBuilder** | `CustomUnitConfig.java:1572` |
| `UnitType.k()` | **ignoreInUnitCapCalculation**（不计入人口） | `CustomUnitConfig.java:1557` |
| `UnitType.o()` | **移动方式** LAND/WATER/AIR/HOVER… | `CustomUnitConfig.java:1507`；`UnitTypeEnum.java:2379` |
| `UnitType.c()` | **价格**（信用点数值） | `CustomUnitConfig.java:1472` `return this.price.a();` |
| `UnitType.w()` | **当前被房间规则禁用**（如 `noNukes` → 核弹类不可用） | `CustomUnitConfig.java:1451`；`UnitTypeEnum.java:2427` 默认 false；AI 写法 `isDemo && !isAvailableInDemo() \|\| t.w()` |
| `UnitTypeEnum.ae` | **全部单位原型注册表** `ArrayList<UnitType>` | `UnitTypeEnum.java:1911`；`UnitBuildStrategy.rebuildUnitMix` 遍历 |
| `FactoryQueueInterface.h(UnitType)` | **队列中该类型的待造数量** | `FactoryWithQueue.java:120` `queueManager.a(unitType)` |
| `FactoryQueueInterface.dy()` | **建造队列为空**（可立即接新单） | `FactoryWithQueue.java:125` |
| `PathCostMap.i` | **孤立区域分组统计的最大值**（地图碎片化信号） | `PathCostMap.java:253-258` `if (this.i < a) this.i = a;`；`AIController.java:317` 判 `> 3000` |
| `PlayerTeam.c(t)` / `d(t)` | **非友方 / 友方** | `PlayerTeam.java:1315` / `:1319`（`teamColorId` 比较 + `TEAM_ALL` 特例） |

### 6.2 使用频次（`ai/` 内部）

> 口径：`.name(` 的方法调用次数。`a/b/c/d/l/k/m` 这些名字里有相当一部分来自**其他** API
> （`UnitList.a()`、`Paint.l()/m()`、`GraphicsEngine.i()/j()/k()/l()`、`GameViewUtils.a()/b()/c()/d()`、
> `PathfindingUtils.a()`），不要当成单符号计数。

高频：`.a()` 80、`.b()` 20、`.c()` 17、`.r()` 15、`.bI()` 10、`.d()` 10、`.u()` 9、`.o()` 8、`.p()` 8、
`.m()` 6、`.n()` 5、`.I()` 4、`.Q()` 3、`.cm()` 2、`.dy()` 2、`.h()` 3、`.w()` 2、`.t()/.aS()/.be()` 各 1。

字段访问：`.bE` 65、`.aB` 27、`.aC` 17、`.aD` 5、`.bs` 3、`.ae` 2
（`.x/.y` 是 `PointF` 的真实字段名，`.ai/.io` 是包名/import 误命中，都不算混淆）。

### 6.3 待确认

1. **`Q()` 的极性**：状态定义确凿（水下/潜航），但 `AmphibiousJet` 里出现 `isSubmerged = !Q()`，
   AI 里又用 `!Q()` 决定潜浮 —— 可能读档路径串行。建议按中性语义理解。
2. **`PathCostMap.i` 的物理含义**：是"区域编号上限"还是"区域规模"，需要跑一次调试确认。
3. `UnitType.u()` / `B()` / `d(int)` 三个 `UnitPrice` 变体（基础价 / 升级价 / 队列价）没细查，AI 未用到。

---

## 7. 踩坑清单

### 7.1 `behaviors/` 是"只读回、不产生"

`addAIBehavior` 全仓库唯一调用点是 `AIController.java:736-738` 的存档回读路径（协议 ≥9）。
游戏内**没有任何地方** `new NukeBehavior()`，所以核弹 AI 行为当前**不生效**，只在加载旧存档/回放时被还原。
要启用得自己注册。

### 7.2 其他

- **`UnitBuildStrategy` 字段名与语义错位**：见 [2.7](#27-13-张建造配比表字段名不可信看语义)，判断语义只能看 `canBuildUnit`。
- **`airAttackGroupCount` 数的是海军编组**：`updateUnitCounts` 在 `isSeaGroup` 分支自增，
  `updateAILogic` 用它 gate 创建 sea group。自洽但名字骗人。
- **`airCostMap` 是水面（WATER）代价图**：`PathEngine.java:199` `new PathCostMap(this, UnitMovementType.WATER, …)`。
- **纯调试计数器**：`BaseZone.numberOfLandUnits / numberOfAirUnits / numberOfWaterUnits / numberOfBuildings`
  只增不清零，唯一读取点是 `AIController.renderDebugOverlay` 的字符串。
- **反编译残留**：大量空 `if (x) { }`、`if (obj == null) { }` 分支不是逻辑；局部变量全是 `z/z2/f/f3/i3`。
- **单位上的 `aB/aC/aD` 是 AI 私有回写字段**，别当业务字段用。

---

## 8. 想改什么 → 去哪

| 目标 | 落点 |
|---|---|
| 单位索敌范围 / 开火策略 / 自动攻击 | `OrderableUnit.updateAttackTargeting` @3145 + `BaseUnit`/`FireUnit` + `AttackMode` |
| 寻路质量 / 性能 | `gameFramework/path/PathEngine` + `PathfindingUtils` + `GameViewUtils` 的孤立区域 |
| 群体移动阵型 | `FormationEngine` / `FormationGroup` + `OrderableUnit.formation*` |
| 简单/困难 AI 出兵与扩张节奏 | `AIController.updateTeam` 的三个阈值 + `updateAILogic` 的编组配额 + `BaseZone` 分数 |
| AI 的建筑选择倾向 | `BaseZone.getBestBuildingToBuild` @321 的硬编码优先级表 |
| AI 的进攻/防守行为 | `UnitGroup.updateAttackAI` @532 / `updateDefensiveAI` @462（集结 17s、撤退阈值、11s 解散） |
| AI 的运输投送 | `TransporterGroup.updateAI` @151（`capacity`、等待/卸载节奏） |
| 让 AI 会用某个自定义单位 | mod 的 `[ai]` 段 + `AIController` 里 13 个 `UnitBuildStrategy.canBuildUnit` |
| 给 AI 加新行为模块 | `ai/behaviors/*`，但记得在生成端 `addAIBehavior` |
| 用外部程序接管一支队伍 | JVM mod `api.ai`（注意 [4.3](#43-jvm-mod-api-apiai接管高层) 的让位前提） |

## 9. 调试手段

- **AI 调试浮层**：`AIController.renderDebugOverlay`，需要 `GameEngine.isDebugTempMode`；
  官方 modding 文档提到"Debug Mode + Shift+F3"看 AI base 划分。
- **`ai_debug(n)` 日志**：`AIController.setDebugKeyState()` 打出来的；日志里能看到
  `ai_debug(3):firstRun: no command center found` 这类首次运行诊断。
- **`RWX_AGENT_CONTROL_READY` + `--agent-port=<port>`**：游戏自带的本地控制端点，
  可读 `brief` / `units own|enemy` 并下发移动/攻击命令（见 `.agents/skills/rwx-control/`）。
  注意它只支持**本地单人局**，且不含视觉信息。
- **性能打印**：`renderDebugOverlay` 里的 `lastAttemptedBuildingCount` / `lastAttemptedBuildingFailed` /
  `lastUnitAttempt` 对应 `BaseZone` 那几个调试计数器。

---

## 10. 本次已加的注释（代码内锚点）

为便于就地查表，已在下列声明处加注释（纯注释，零逻辑改动）：

- `game/ai/AIController.java` —— 类头总览（两套 AI 边界、节拍、外部符号速查表）+ `bI` 字段
- `game/units/BaseUnit.java` —— `bE` / `bI()` / `u()` / `t()` / `bs` / `Q()` / `cm()`
- `game/units/OrderableUnit.java` —— `aB` / `aC` / `aD` / `aN` / `I()` / `aS()`
- `game/units/UnitType.java` —— `w()` / `l()` / `k()` / `m()` / `n()` / `o()` / `p()` / `c()`
- `game/units/UnitTypeEnum.java` —— `ae` / `o()`
- `game/units/buildings/FactoryQueueInterface.java` —— `h(UnitType)` / `dy()`
- `gameFramework/path/PathEngine.java` + `pathfinding/PathCostMap.java` —— `airCostMap` 误映射、`PathCostMap.i`
