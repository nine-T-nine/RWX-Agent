# AI 策略扩展接口

所有可选 AI 统一登记到 `AiRegistry`，界面、名称、收入倍率、策略调度和存档读取相同的定义。原版 `-2..3` 保持原算法及旧存档布局；自定义 AI 使用 `>=4` 的稳定编号。每支队伍各有一个策略实例。

当前 `CustomAiAuto_PilotStrategy` 运行原版 Impossible 的全部阶段；建造者、工程师、战斗单位由 `onUnitProduction` 决定，建筑由 `onBuildingProduction` 决定（选型沿用原版打分，再按策略偏好微调；选址与选人默认沿用原版，并加了同类建筑间距），运输单位继续使用原版生产逻辑。AI-high 的基础收入倍率可在 `definition()` 中配置，房间收入倍率仍然生效。

AI-high 的存档扩展需要支持此功能的 RWX 版本读取；旧难度仍使用原来的 AI 序列化版本 `9`。本功能是源码级 Java/Kotlin 扩展，不是 ini `[ai]` 配置，也不是现有 `mod-api` 的 external-agent 绑定。

## 从哪里写策略

自定义 AI 的实现和定义统一放在 [ai/custom](../core/src/main/java/com/corrodinggames/rts/game/ai/custom/)。`AI-high` 的入口是 [CustomHighAiStrategy.java](../core/src/main/java/com/corrodinggames/rts/game/ai/custom/high/CustomHighAiStrategy.java)。

新增 AI 时，实现 [AiStrategy](../core/src/main/java/com/corrodinggames/rts/game/ai/strategy/AiStrategy.java)，把 `definition()` 和实现放在同一个类中：

```java
public static AiDefinition definition() {
    return AiDefinition.custom(5, "my.ai", "My AI", "我的AI", MyHighStrategy::new)
            .withIncomeMultiplier(1.0f)
            .withCadence(new AiCadence(25, 80, 250));
}
```

然后仅在同目录的 [CustomAiCatalog.java](../core/src/main/java/com/corrodinggames/rts/game/ai/custom/CustomAiCatalog.java) 的 `List.of(...)` 中添加 `MyHighStrategy.definition()`。通常只需新增一个实现文件、改一处清单；不再修改界面、网络名称、收入结算、存档或 `AIController`。

编号和字符串 ID 都必须唯一，注册表拒绝覆盖。编号会被存档及房间设置持久化，不要重新编号或复用。名称的英中版本直接与策略定义放在一起，不要求为新增 AI 修改全局 i18n 文件。注册表同时提供 `definitions()`、`find(int)`、`find(String)` 供全局查询。

工厂每次必须返回新实例。注册与注销仅用于开局/读档前的初始化阶段，不支持局中修改清单或热替换。通过 `context.team().teamId` 区分队伍，通过 `context.definition()` 读取本队定义。

下面是一个可编译的独立策略示例：每个战术节拍让可攻击的单位攻击移动到第一个可见敌人的位置。它只演示观察和发令，不包含经济逻辑，也没有安装为默认策略。

```java
package com.corrodinggames.rts.game.ai.custom.myai;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.ai.strategy.*;
import java.util.List;

public final class MyHighStrategy implements AiStrategy {
    public static AiDefinition definition() {
        return AiDefinition.custom(5, "my.ai", "My AI", "我的AI", MyHighStrategy::new);
    }

    @Override
    public String id() {
        return "my.ai-high.v1";
    }

    @Override
    public void onTactics(AiContext context, float elapsedFrames) {
        List<BaseUnit> enemies = context.visibleEnemies();
        if (enemies.isEmpty()) return;
        BaseUnit target = enemies.get(0);
        for (OrderableUnit unit : context.ownUnits()) {
            if (unit.canUnitAttack() && !unit.bI()
                    && unit.transportContainer == null
                    && !unit.canNotBeGivenOrdersByPlayer()) {
                context.orders().attackMove(target.posX, target.posY, unit);
            }
        }
    }
}
```

## 如何复用原版 AI

`AiDefinition.custom(...)` 默认完整接管，不执行原版决策。需要在原版基础上增强时，在同一份定义中声明：

```java
// 继续运行原版 Impossible 的全部阶段，并叠加自定义回调。
return AiDefinition.custom(5, "my.ai", "My AI", "我的AI", MyHighStrategy::new)
        .extendOriginal(3);
```

也可以仅保留部分阶段：

```java
// 保留原版初始化和基地经济，自行负责战术及战略。
return AiDefinition.custom(5, "my.ai", "My AI", "我的AI", MyHighStrategy::new)
        .withOriginalPhases(3, AiPhase.INITIALIZATION, AiPhase.ECONOMY);
```

| 原版阶段 | 执行内容 | 依赖及边界 |
|---|---|---|
| `INITIALIZATION` | 地图可达性、资源点连通性、危险边界检测 | 不负责创建初始基地 |
| `ECONOMY` | 原来的前两个基地轮转，执行 `BaseZone.updateZone/updateZoneStrategy` | 需要已有基地；基地内也包含生产与局部防御发令 |
| `TACTICS` | 完整的 `updateAILogic`，包括初始基地、编组、原生编组战术及运输 | 同时包含一些基地与空闲单位维护，并非纯战斗模块 |
| `STRATEGY` | 完整的 `updateCoreLogic`，包括单位记账、攻击模式、扩张和升级 | 会创建实验单位猎杀组，修改攻击模式 |

这些开关对应原项目真实的粗粒度阶段，没有拆写其内部算法。仅保留经济时，要由自己的 `onStart` 建立基地，或同时保留原版战术阶段。可以通过 `context.originalBases()` 和 `context.originalNodes()` 查询原版记账；创建基地时使用原来的 `new BaseZone(context.team(), x, y)`，设置其 `radius`、`stage`、`zoneType`，构造函数会完成注册。自建基地也是 AI 记账，不是绕过命令直接建造游戏建筑。

只接管非建筑单位生产时，覆盖 `overridesUnitProduction()` 返回 `true`，并在 `onUnitProduction(context, elapsedFrames)` 中发令。原版基地仍负责建筑、修理和回收；建造单位、采集单位及战斗单位交给自定义策略，运输单位沿用原版基地的运输需求判断。`onUnitProduction` 与 `onEconomy` 使用相同节拍，先于原版经济阶段执行。使用 `context.orders().tryProduceUnit(type, factory)` 可在工厂空闲、动作可用且可支付费用时排入一个非建筑单位，返回值表示已提交命令。

只接管建筑时，覆盖 `overridesBuildingProduction()` 返回 `true`，并在 `onBuildingProduction(context, elapsedFrames)` 中决定建筑种类与位置。此时原版不再发任何建筑命令，覆盖三条原版建筑通路：

| 原版建筑通路 | 关掉的位置 | 接管后可用的替代接口 |
|---|---|---|
| 基地建造打分 + 建造者放置（`BaseZone.getBestBuildingToBuild()` → `tryBuildUnit()`） | `BaseZone.updateZoneStrategy` 只计算优先级，不再发令 | `BaseZone.tryBuildUnit(type)`（原版选址与选人）、`context.orders().tryBuildBuilding(type, x, y, builder)`（自定义选址） |
| 工厂队列产出建筑类单位（`BaseZone.buildUnit`） | 建筑类型直接返回 false | `context.orders().tryBuildBuilding(...)`；非建筑单位仍由原版/`onUnitProduction` 负责 |
| 原版随机与高优先级动作抽签（`AIController.issueUnitAction` 中的放置建筑动作） | 放置建筑类动作被拒绝，升级动作不受影响 | 同上；升级仍由原版 `cm()` 抽签负责 |

`onBuildingProduction` 与 `onEconomy`、`onUnitProduction` 使用相同节拍，顺序为 `onEconomy → onUnitProduction → onBuildingProduction`。基地打分本身仍在运行，因此 `BaseZone.getLastFoundBuildingPriority()`、`reclaimScore` 与防御节奏保持原版行为；可以据此复刻原版的资金门槛再微调。原版的基地创建（Main / ResourceOutpost / ForwardOutpost）属于扩张阶段，不受本开关影响，需要时用 `withOriginalPhases` 保留或放弃对应阶段。

最小可用写法（AI-high 当前实现的部分）：节拍是 25 帧一次，所以自定义时要自己限速，别每拍都发令。

```java
@Override public boolean overridesBuildingProduction() { return true; }

private float buildTimer = 180f;   // 帧；原版由基地 defensiveScore 周期控制节奏

@Override public void onBuildingProduction(AiContext context, float elapsed) {
    buildTimer += elapsed;
    if (buildTimer < 180f) return;
    buildTimer = 0f;
    for (BaseZone base : context.originalBases()) {
        UnitType type = base.getBestBuildingToBuild();   // 原版打分选型；被打时/侵略模式可能为 null
        if (type == null) continue;
        if (type == UnitTypeEnum.turret) type = UnitTypeEnum.antiAirTurret;   // 微调
        if (base.tryBuildUnit(type)) return;             // 原版选址 + 选人 + 发令
        // 想自己定位置时：
        // PointF spot = base.getRandomPointInside();
        // if (!context.canPlaceBuilding(type, spot.x, spot.y)) continue;
        // OrderableUnit builder = base.findBuilderForBuilding(type, spot.x, spot.y);
        // if (builder != null && context.orders().tryBuildBuilding(type, spot.x, spot.y, builder)) return;
    }
}
```

自定义回调先运行，然后才运行所选择的原版阶段。两者计时器互相独立；`withCadence` 只修改自定义回调周期。`.extendOriginal(3)` 会让原版决策按 Impossible 的分支执行，收入仍由本定义的 `withIncomeMultiplier` 单独控制，默认 `1.0`。

## 只接管升级（`overridesUpgrades` + `onUpgrades`）

覆盖 `overridesUpgrades()` 返回 `true` 后，**原版升级抽签整段停用**（`AIController.updateCoreLogic` 里的"高优先动作抽签 + `cm()` 抽签"），改由 `onUpgrades(context, elapsedFrames)` 决定。回调同样是经济节拍，因此概率规则要自己定节拍：AI-high 用 250 帧（≈4.2 秒）一轮，`0.2` 约等于每秒 4.8%。

两条升级体系都要照顾到：

| 单位 | 升级动作的识别方式 | 发令 |
|---|---|---|
| mod 自定义单位（矿脉/资源制造仪/炮台/机甲工厂） | `[action_x] convertTo : 目标` 生成的动作，`getUnitType()` 就是目标类型 | `context.orders().tryUpgrade(unit, targetType)` |
| Java 内置建筑（陆/空/海工厂、极光防御） | 升级动作 `getUnitType()` 为 `null`，只有一条路线 | `context.orders().tryUpgrade(unit, null)`（走原版 `cm()` 语义） |

```java
@Override public boolean overridesUpgrades() { return true; }

private float upgradeTimer = 250f;

@Override public void onUpgrades(AiContext context, float elapsed) {
    upgradeTimer += elapsed;
    if (upgradeTimer < 250f) return;
    upgradeTimer = 0f;
    for (OrderableUnit unit : context.ownUnits()) {
        if (context.orders().tryUpgrade(unit, myUpgradeTarget(unit))) return;   // 一轮只推一个
    }
}
```

- 不要硬编码 action id（`"110"`、`"102"` 被多个建筑复用）；用 `AiUpgrades` 或 `context.orders().tryUpgrade` 从单位身上解析。
- 接管后原版**反核弹补弹**（`AntiNukeLauncher.cm()` 那条）会一起停掉，需要自己补：AI-high 用 `AiUpgrades.defaultUpgradeAction(unit)` 对反核发射井逐个重发。核弹 `launchAmmo`（`NukeBehavior`）与单位/建筑生产不受影响。

## 工具库（`game/ai/custom/util/`）

策略类只放决策，查询与几何一律走工具类：

| 工具 | 提供什么 |
|---|---|
| `AiUnits` | 单位内部名常量与解析（含 `overrideAndReplace` 的多名字回退）、计数（含工厂队列）、战斗单位/兵种路线判定 |
| `AiMapGraph` | 地图骨架无向图（节点=可通行 tile，边=四邻接，只读静态地形所以整局可缓存）：区域 id/大小、BFS 距离场、最短路 tile 序列、水/岩浆/液体、资源池锚点 |
| `AiTerritory` | 我方阵营与前线：双方指挥中心各一张 BFS 距离场 → 我方一侧 / 前线带 / 后方深度；通往敌方路线与路线上的液体渡口；锚点变化自动重算 |
| `AiPlacement` | 覆盖半径（攻击距离与纳米距离取大）、圈内计数/最近搜索、生产建筑计数、到指挥中心距离、资源池判定、45° 后方扇区判定、最近敌人 |
| `AiEnemyIntel` | 敌方构成记忆（见过的记下、死亡减一、单位转换按当前类型重算；存档只写 id，读档自动重绑）与空军威胁权值 |
| `AiUpgrades` | 升级动作查询、引擎四道检查、概率掷骰 |

`AiMapGraph`/`AiTerritory` 会做 O(tile 数) 的 BFS，只在锚点变化时重算；不要在逐帧回调里反复构造。

应给每种发令职责确定唯一负责人，避免原版与自定义模块覆盖同一单位的命令。例如保留原版战术阶段时，不应同时用自定义模块接管原版编组中的同一批单位。

## 如何把 AI 拆成多个模块

经济、生产、扩张、战斗、侦察、运输、微操模块都可以分别实现 `AiStrategy`，只覆盖自己需要的回调，再通过 `CompositeAiStrategy` 按指定顺序组合。在定义工厂中使用：

```java
// EconomyModule / CombatModule 是你自己的 AiStrategy 实现。
return AiDefinition.custom(5, "my.ai", "My AI", "我的AI",
        () -> new CompositeAiStrategy("my.ai.state.v1", new EconomyModule(), new CombatModule()));
```

每个模块必须有独立且稳定的 `id()`。组合器依次分派回调，停止时逆序通知，并为每个模块保存独立的字节数据。新增/删除模块或修改状态布局时应编写显式迁移；模块清单不一致会报错，不会忽略状态。组合器负责组织调用，不包含自动生产、作战规划或冲突仲裁算法。

## 策略回调

所有回调在模拟线程上同步执行。时间单位统一为**游戏帧**，`60` 帧约等于 `1` 秒；游戏暂停时不按墙钟时间推进。不要在回调内等待网络、阻塞线程或执行耗时推理。

| 回调 | 时机 | 适合放置的规则 |
|---|---|---|
| `onStart(context, restored)` | 第一次有效更新；读档时先恢复策略状态，再调用 | 重建单位索引、恢复编组、初始化规划器 |
| `onFrame(context, deltaFrames)` | 每次有效更新 | 微操、主动选目标、技能、攻击模式 |
| `onEconomy(context, elapsedFrames)` | 默认累计至少 25 帧，约 0.42 秒 | 采集、建造、生产、升级、维修、回收 |
| `onUnitProduction(context, elapsedFrames)` | 与经济节拍相同 | 覆写原版非建筑单位生产时自行决定兵种和时机 |
| `onBuildingProduction(context, elapsedFrames)` | 与经济节拍相同，排在 `onUnitProduction` 之后 | 覆写原版建筑生产时自行决定建筑种类、位置与建造者 |
| `onUpgrades(context, elapsedFrames)` | 与经济节拍相同，排在 `onBuildingProduction` 之后 | 覆写原版升级抽签时自行决定谁升级、升级成什么 |
| `onTactics(context, elapsedFrames)` | 默认累计至少 80 帧，约 1.33 秒 | 编组、攻防、侦察、撤退、运输 |
| `onStrategy(context, elapsedFrames)` | 默认累计至少 250 帧，约 4.17 秒 | 扩张、科技、兵种配比、长期目标 |
| `onStop(context)` | 更新时检测到队伍离开当前自定义 AI | 释放临时资源；此回调不可发令 |
| `writeState(output)` / `readState(input)` | 保存/恢复本队策略私有数据 | 目标单位 ID、阶段、冷却、计划等 |

同次更新按 `onFrame → onEconomy → onUnitProduction → onBuildingProduction → onUpgrades → onTactics → onStrategy` 顺序执行。各阶段每次最多触发一次，参数包含完整累计时间，触发后对应计时器清零。首次经济决策在累计 25 帧后执行；需要开局立即决策时可在 `onStart` 发令。

只有本地主机运行决策，联机客户端接收游戏命令。录制回放时正常运行；播放回放时使用已记录命令，不运行策略。现有 AI 暂停标记和管理等待时间继续生效。策略异常向调用者抛出，不会自动切回原版 AI。

## 如何观察局势

| 上下文方法 | 返回内容 | 约束 |
|---|---|---|
| `team()` | 本队 `AIController` | 原生对象，仅用于读取；不要直接修改资源、单位或编组字段 |
| `credits()` / `tick()` | 当前资金 / 模拟 tick | 不是墙钟时间 |
| `ownUnits()` | 本队存活且可下指令类型的单位 | 包含建筑和运输中的单位，按实际能力再次筛选 |
| `visibleEnemies()` | 本队迷雾下可见的敌方单位 | 使用本队视野，排除中立方和不可选目标 |
| `unitTypes()` | 原版及 mod 单位类型表 | 是否解锁、房间禁用及建造能力要另外检查 |
| `resourcePools()` | 全图资源池的建造锚点 | 世界坐标，沿用原版 AI 的 Y 偏移；不做迷雾过滤 |
| `canReach(unit, x, y)` | 对应移动类型的区域连通性 | 不是完整路径，也不是建筑放置合法性检查 |
| `canPlaceBuilding(type, x, y)` | 建筑放在该世界坐标是否合法（原版放置检查） | 不做迷雾过滤，也不判断建造者能否走到；建筑类型与房间规则在此过滤 |
| `originalBases()` / `originalNodes()` | 原版的基地和策略节点快照 | 元素仍为原生对象；保留原版阶段时可以用于协作 |

原版基地对象（`BaseZone`）在保留原版经济阶段时可用于“照抄原版再微调”：`getBestBuildingToBuild()` 给出原版打分选出的建筑，`getLastFoundBuildingPriority()` 给出对应优先级（原版资金门槛的依据），`canBuild(type)` / `hasBuilderFor(type)` / `countUnitsOfType(type)` / `getNumberOfFactories()` / `getNumberOfExtractors()` / `isUnderAttack()` / `isPrimary()` / `getStage()` 用于判断现状，`findBuildLocation(type)` 与 `findClosestResource()` 给出原版选址，`findBuilderForBuilding(type, x, y)` 与 `tryBuildUnit(type)` 给出原版选人与发令。

列表每次重新收集，内部元素仍是实时的游戏对象；不要跨线程访问，也不要把对象引用直接持久化。全图扫描建议放在经济、战术或战略节拍，逐帧只处理已经选出的少量单位。`visibleEnemies()` 做迷雾筛选，但原生对象接口不是沙箱，策略作者需要自行遵守读取边界。

## 如何发令

优先使用 `context.orders()` 的意图接口：`move`、`attackMove`、`attack`、`build`、`produce`、`tryProduceUnit`、`tryQueueUnit`、`tryBuildBuilding`、`tryUpgrade`、`tryUpgradeAction`、`repair`、`reclaim`、`action`、`attackMode`、`stop`。例如 `context.orders().produce(type, factory)` 自动查找该工厂对应的生产动作；没有相应动作时会在入队前报错。它们统一委托下面的标准命令入口，不会直接修改游戏对象。`tryBuildBuilding(type, x, y, builder)` 在入队前检查建筑类型、放置合法性、建造者能力、动作可用性与费用，返回值表示已提交命令。

`context.command(unit1, unit2, ...)` 返回已经进入标准命令队列的 `Command`，必须在当前回调内立即设置目标。它在入队前拒绝空选择、外队单位、死亡单位和禁止玩家发令的单位。单位能力、资源、科技、地形和房间规则仍由原游戏命令执行层处理；返回命令不代表动作已经成功。

以下表达式放在策略回调内使用；`unit`/`builder`/`factory` 是本队单位，`target` 是目标单位，`type` 是要建造的 `UnitType`，`action` 来自单位的 `getAvailableActions()`：

| 行为 | 设置命令 |
|---|---|
| 移动 / 攻击移动 | `context.command(unit).setMoveTarget(x, y)` / `.setAttackMoveTarget(x, y)` |
| 攻击指定单位 | `context.command(unit).setAttackTarget(target)` |
| 建造建筑 | `context.command(builder).setBuildTarget(x, y, type, 1)` |
| 工厂生产、升级、技能、卸载动作 | `context.command(factory).setActionId(action.getActionId())` |
| 带位置/单位目标的技能，例如核弹 | `context.command(unit).setActionTarget(action.getActionId(), point, target)` |
| 修理 / 回收 | `.setRepairTarget(target)` / `.setReclaimTarget(target)` |
| 护卫 / 巡逻 | `.setGuardTarget(target)` / `.setPatrolTarget(x, y)` |
| 装入运输机 / 装载目标 | `.setLoadIntoTarget(target)` / `.setLoadUpTarget(target)` |
| 攻击模式 / 工厂集结点 | `.setAttackMode(mode)` / `.setRallyPoint(point)` |
| 清除当前命令 | `.setClearExistingOrders()` |

排队命令需要对**同一个命令对象**设置队列标记和目标：

```java
// 在策略回调内，unit 为本队单位。
var command = context.command(unit);
command.isQueued = true;
command.setMoveTarget(100f, 200f);
```

高级策略可以通过原生 `Command` 表达游戏已有动作。不要调用 `executeCommand()` 强制立即执行，也不要直接改 `OrderableUnit` 的当前命令、位置、路径、生命或资源字段。

## 保存你的策略状态

运行时保存定义 ID、三个节拍计时器、策略 `id()` 和策略私有字节数据。加载时先暂存字节，在世界加载结束后的首次有效更新才创建策略、执行 `readState`，然后调用 `onStart(context, true)`。因此 `readState` 只读标量/ID，单位引用应在 `onStart` 里重新查找。

在策略实现中加入以下成员即可持久化自己的阶段编号：

```java
private int phase;

@Override
public void writeState(java.io.DataOutputStream output) throws java.io.IOException {
    output.writeInt(1); // 你的策略数据版本
    output.writeInt(phase);
}

@Override
public void readState(java.io.DataInputStream input) throws java.io.IOException {
    if (input.readInt() != 1) throw new java.io.IOException("Unsupported policy state");
    phase = input.readInt();
}
```

缺少策略注册、定义 ID 不匹配或策略状态 ID 不一致时会明确报错。保持 ID 稳定，并在私有数据中维护版本及迁移逻辑。切换难度离开某个自定义 AI 会丢弃该实例，下一次进入会从新实例开始。早期 AI-high 的运行时版本 `1` 仍可读取，新的通用运行时使用版本 `2`；两者都位于 AI 队伍的版本 `10` 扩展内。

## 与原项目接口的关系

本接口允许整体接管或分阶段复用高层 AI。`BaseZone`、`UnitGroup`、运输队、`AIBehavior` 和原来的升级抽签代码保留在原位置，只有在相应阶段启用时运行。现有 `api.ai` 的 external-agent 绑定是另一条控制通道，不要让它与自定义 AI 同时控制同一队伍。

单位级的寻路、避让、阵型、索敌和武器执行继续由原游戏处理。可以通过命令目标和 `AttackMode` 控制其行为；如果后续要更换路径求解器、碰撞算法或武器内部状态机，需要在相应执行层另外设计扩展，当前接口没有替换这些算法。

AI-high 新增了自定义存档字节，混用旧 RWX/原版客户端的联机或中途同步不受支持；参与者应使用相同的功能版本。策略代码及状态格式也应保持一致。

## 验证入口与代码依据

运行针对本功能的集成测试：

```powershell
rtk .\gradlew.bat :core:test --tests '*AiIntegrationTest' --console=plain
```

测试使用真实队伍、策略调度、命令控制器与序列化路径，在无地图的模拟环境验证接管行为；完整对局的经济强度需要实现策略后再评估。

| 依据（Evidence） | 行为结论（Finding） | 调用路径（Path） |
|---|---|---|
| `AiRegistry`、`AiDefinition` 与动态注册测试 | 新增注册项自动进入房间/玩家表单、名称、收入和存档 | 自定义清单 → 注册表 → 各消费方 |
| `AIController.updateTeam` 与 `AiIntegrationTest` | 自定义 AI 独立调度，可选择原版阶段；客户端及回放播放不决策 | 队伍更新 → 注册项 → `AiRuntime.update` → 策略回调/所选原版阶段 |
| `AIController.overridesBuildingProduction`、`BaseZone.updateZoneStrategy`/`buildUnit` 与 `AiIntegrationTest` | 接管建筑时原版三条建筑通路都不再发令，基地打分与 `reclaimScore` 节奏不变 | 策略开关 → 队伍查询 → `BaseZone` 建造/生产/动作抽签分支 |
| `AIController.overridesUpgrades`、`AIController.updateCoreLogic` 抽签段与 `AiUpgrades` | 接管升级后原版两段抽签都不再发令；升级动作按目标类型或 `cm()` 解析，四条引擎检查不变 | 策略开关 → 队伍查询 → `onUpgrades` → `AiOrders.tryUpgrade` |
| `AiContext.command` 与命令入队测试 | 发令经过归属校验及标准网络/回放命令队列 | 策略 → 上下文 → `CommandController.newCommandForTeam` → `Command` |
| `AIController.a/readExtendedTeamState` 与存档往返测试 | 原版六档保留版本 9；自定义 AI 延迟恢复状态 | 存档版本 10 → `AiRuntime.readFrom` → 首次更新时 `readState/onStart` |

通用接口位于 [ai/strategy](../core/src/main/java/com/corrodinggames/rts/game/ai/strategy/)，新增 AI 的工作集中在 [ai/custom](../core/src/main/java/com/corrodinggames/rts/game/ai/custom/)，测试位于 [AiIntegrationTest.kt](../core/src/test/kotlin/com/corrodinggames/rts/game/ai/strategy/AiIntegrationTest.kt)。
