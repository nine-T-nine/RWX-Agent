package com.corrodinggames.rts.game.ai.custom.high;

import com.corrodinggames.rts.game.ai.BaseZone;
import com.corrodinggames.rts.game.ai.custom.util.AiEnemyIntel;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.ai.custom.util.AiPlacement;
import com.corrodinggames.rts.game.ai.custom.util.AiTerritory;
import com.corrodinggames.rts.game.ai.custom.util.AiUnits;
import com.corrodinggames.rts.game.ai.custom.util.AiUpgrades;
import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiStrategy;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.buildings.AirFactory;
import com.corrodinggames.rts.game.units.buildings.CommandCenter;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.buildings.LandFactory;
import com.corrodinggames.rts.game.units.buildings.SeaFactory;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.Utility;
import com.corrodinggames.rts.gameFramework.utility.GameViewUtils;
import io.github.rwx.geometry.Point;
import io.github.rwx.geometry.PointF;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CustomHighAiStrategy implements AiStrategy {
    /** AI 经济倍率，替换原版难度自带的值（原版 Impossible 为 3.7）；1.0 = 不额外加成。 */
    private static final float INCOME_MULTIPLIER = 1.0f;

    /**
     * 本 AI 的定义：编号、名称、策略工厂、经济倍率和保留的原版阶段都写在这一次调用里。
     * 界面选项、房间设置、名称、收入和存档读取都取自这里，新增/修改 AI 只需要动这个方法。
     *
     * <p>{@code AiDefinition.custom(code, id, english, chinese, factory)} 五个参数：
     * <ul>
     *   <li>{@code code}：稳定数字编号，必须 {@code >= 4}（{@code -2..3} 是原版六档）。
     *       界面下拉、房间设置、队伍难度（{@code teamPingTime}/{@code getTeamColorId()}）和存档都记这个数字；
     *       发布后不能改号，也不能被别的 AI 复用，{@code AiRegistry} 会拒绝重复编号或重复 ID。</li>
     *   <li>{@code id}：稳定字符串 ID，同样进存档；换掉它会让旧存档按“策略不匹配”报错
     *       （{@code AIController.updateTeam} 用它找回 {@code AiDefinition}）。</li>
     *   <li>{@code english}：英文名，必填（空白会被 {@code AiDefinition} 拒绝），也是其它语言的回退名。</li>
     *   <li>{@code chinese}：简体中文名；写在这里即可，不需要改全局 i18n 文件。</li>
     *   <li>{@code factory}：策略工厂，每支使用该 AI 的队伍各调用一次，必须每次返回新实例
     *       （不能返回单例，也不要共享可变状态）。</li>
     * </ul>
     *
     * <p>{@code withIncomeMultiplier(INCOME_MULTIPLIER)}：AI 收入倍率，替换而不是叠加原版难度的倍率。
     * 原版 Impossible 是 {@code 3.7}（{@code 1 + 3*0.4 + 1.5}）；最终收入 =
     * 本倍率 × 房间收入倍率（{@code roomSettings.incomeMultiplier}），房间设置仍然生效。
     *
     * <p>{@code extendOriginal(3)}：保留原版全部阶段（见 {@code AiPhase}），数字 {@code 3} 让原版算法走
     * Impossible 分支（编组配额、资金阈值、计时器速度、实验单位等），只影响原版分支，不改收入。
     * 只想保留部分阶段改用 {@code withOriginalPhases(3, AiPhase...)}；要完全接管就不要调它。
     *
     * <p>默认节拍 {@code AiCadence.STANDARD}：经济/战术/战略 = 25/80/250 帧（60 帧 = 1 秒），
     * 需要别的节奏再加 {@code .withCadence(new AiCadence(economy, tactics, strategy))}。
     *
     * <p>新增 AI 时复制本方法改参数，并把新类的 {@code definition()} 加进
     * {@code CustomAiCatalog.definitions()} 清单。
     */
    public static AiDefinition definition() {
        return AiDefinition.custom(
                        4,                          // 稳定编号：>=4，存档与房间设置都记它，不能改号或复用
                        "rwx.ai-high",              // 稳定字符串 ID：存档用它校验策略，改动后旧存档会报不匹配
                        "AI-high",                  // 英文名：必填，其它语言的回退名
                        "AI-high",                  // 简体中文名
                        CustomHighAiStrategy::new)  // 策略工厂：每队一个实例，必须每次 new
                .withIncomeMultiplier(INCOME_MULTIPLIER) // 经济倍率：替换原版难度倍率（Impossible 为 3.7）
                .extendOriginal(3);                      // 保留原版全部阶段，3 = 原版走 Impossible 分支
    }

    @Override
    public String id() {
        return "rwx.ai-high.custom.v1";
    }

    @Override
    public boolean overridesUnitProduction() {
        return true;
    }

    @Override
    public void onUnitProduction(AiContext context, float elapsedFrames) {
        List<OrderableUnit> units = context.ownUnits();
        double credits = context.credits();
        double income = context.team().getScaledIncomeRate(); // 每秒收入
        int gameMillis = GameEngine.getInstance().gameTimeMillis;

        enemyIntel.update(context);           // 敌方构成记忆：每个经济节拍刷新
        trackOreCounts(units, gameMillis);    // 矿脉数量变化时间戳（资源制造仪升级要用）

        UnitType combatEngineer = AiUnits.combatEngineer();
        UnitType mechEngineer = AiUnits.mechEngineer();
        UnitType plasmaTank = AiUnits.plasmaTank();
        UnitType heavyInterceptor = AiUnits.heavyInterceptor();
        UnitType heavyBattleship = AiUnits.heavyBattleship();
        UnitType heavySub = AiUnits.heavySub();
        UnitType mechMinigun = AiUnits.mechMinigun();
        UnitType mechFlame = AiUnits.mechFlame();
        UnitType mechMissile = AiUnits.mechMissile();

        // 建造者目标数：基础 5；15 分钟后 10；25 分钟后 15
        int builderTarget = 5;
        if (gameMillis > 25 * MINUTE_MILLIS) builderTarget = 15;
        else if (gameMillis > 15 * MINUTE_MILLIS) builderTarget = 10;

        int builderCount = AiUnits.count(units, UnitTypeEnum.builder);
        int engineerCount = AiUnits.countBoth(units, combatEngineer, mechEngineer);
        int heavyTankCount = AiUnits.count(units, UnitTypeEnum.heavyTank);
        int plasmaTankCount = AiUnits.count(units, plasmaTank);
        int battleShipCount = AiUnits.count(units, UnitTypeEnum.battleShip);
        int heavyBattleshipCount = AiUnits.count(units, heavyBattleship);
        int heavySubCount = AiUnits.count(units, heavySub);
        int gunShipCount = AiUnits.count(units, UnitTypeEnum.gunShip);
        int interceptorCount = AiUnits.count(units, heavyInterceptor);
        int antiAirMechCount = AiUnits.count(units, mechMissile);
        // 防空机甲目标数 = 敌方空军威胁权值（向上取整）
        int antiAirMechTarget = (int) Math.ceil(enemyIntel.airThreatWeight());
        boolean enemyAirTrigger = enemyIntel.hasHelicopterOrGunship();
        boolean hasMechFactoryT1 = AiUnits.firstOfType(units, AiUnits.mechFactory()) != null;

        // 陆军 / 空军二选一：该类别已经有战斗单位时就不开另一条线（建筑、运输、建造者、采集者不算）
        boolean hasLandArmy = AiUnits.hasCombatUnitsOfMovement(units, UnitMovementType.LAND);
        boolean hasAirArmy = AiUnits.hasCombatUnitsOfMovement(units, UnitMovementType.AIR);

        boolean builderQueued = false;
        boolean engineerQueued = false;
        boolean landTankQueued = false;
        boolean airFighterQueued = false;
        boolean antiAirMechQueued = false;
        boolean mechHeavyQueued = false;
        boolean mechFlameQueued = false;
        boolean heavyNavalQueued = false;
        boolean battleShipQueued = false;

        // 运输单位不在这里生产：overridesUnitProduction() 为 true 时，原版 BaseZone.tryBuildCombatUnits(true)
        // 仍会按原版的运输需求判断造运输单位。
        for (OrderableUnit factory : units) {
            if (!(factory instanceof FactoryQueueInterface)) continue;
            FactoryQueueInterface queue = (FactoryQueueInterface) factory;
            UnitType factoryType = factory.r();

            if (factory instanceof SeaFactory) {
                if (factory.getUpgradeLevel() >= 2) {
                    // 2 级海军基地：20 分钟 + 战列舰 < 3 + 收入 > 120 + 资金 > 6000
                    // → 重型战舰（不限量）/ 重型潜艇（< 4）
                    if (!heavyNavalQueued && gameMillis > 20 * MINUTE_MILLIS && battleShipCount < 3
                            && income > 120 && credits > 6000) {
                        heavyNavalQueued = heavySubCount < 4
                                ? tryEither(context, factory, heavyBattleship, heavyBattleshipCount,
                                        heavySub, heavySubCount)
                                : tryProduce(context, heavyBattleship, factory);
                    }
                } else {
                    // 1 级海军基地：收入 > 70、资金 > 1500 出 1 艘战列舰；
                    // 收入 > 100、资金 > 3000 且还没有重型战舰时，一次排 2 艘
                    int battleShipTarget = income > 100 && credits > 3000 && heavyBattleshipCount == 0 ? 2 : 1;
                    if (!battleShipQueued && income > 70 && credits > 1500
                            && queue.h(UnitTypeEnum.battleShip) < battleShipTarget) {
                        battleShipQueued = context.orders().tryQueueUnit(UnitTypeEnum.battleShip, factory);
                    }
                }
                continue;
            }

            if (factory instanceof AirFactory) {
                // 2 级空军基地：重型拦截机 : 武装直升机 保持 1:3 ~ 3:4；前提是还没有陆军战斗单位
                if (!airFighterQueued && factory.getUpgradeLevel() >= 2 && !hasLandArmy) {
                    airFighterQueued = tryProduce(context,
                            nextAirFighter(interceptorCount, gunShipCount, heavyInterceptor), factory);
                }
                continue;
            }

            // 指挥中心 / 陆军工厂 / 机甲工厂 / 实验工厂：先补建造者与工程师
            if (!builderQueued && credits > 500 && builderCount < builderTarget) {
                builderQueued = context.orders().tryProduceUnit(UnitTypeEnum.builder, factory);
                if (builderQueued) continue;
            }
            if (!engineerQueued && credits > 6000 && income > 70 && engineerCount < 3) {
                engineerQueued = tryEither(context, factory, combatEngineer,
                        AiUnits.count(units, combatEngineer), mechEngineer, AiUnits.count(units, mechEngineer));
                if (engineerQueued) continue;
            }

            if (factoryType == AiUnits.mechFactoryT2()) {
                // 2 级机甲工厂：收入 > 70 造机枪机甲；20 分钟 + 收入 > 200 + 资金 > 12000 造喷火机甲
                if (!mechFlameQueued && gameMillis > 20 * MINUTE_MILLIS && income > 200 && credits > 12000) {
                    mechFlameQueued = tryProduce(context, mechFlame, factory);
                    if (mechFlameQueued) continue;
                }
                if (!mechHeavyQueued && income > 70) {
                    mechHeavyQueued = tryProduce(context, mechMinigun, factory);
                    if (mechHeavyQueued) continue;
                }
                // 防空缺口：有 1 级机甲工厂时留给它造，没有时才由 2 级补
                if (!antiAirMechQueued && !hasMechFactoryT1 && enemyAirTrigger
                        && antiAirMechCount < antiAirMechTarget) {
                    antiAirMechQueued = tryProduce(context, mechMissile, factory);
                }
                continue;
            }

            if (factoryType == AiUnits.mechFactory()) {
                // 1 级机甲工厂：敌人有直升机/武装直升机时造防空机甲，数量不低于敌方空军权值
                if (!antiAirMechQueued && enemyAirTrigger && antiAirMechCount < antiAirMechTarget) {
                    antiAirMechQueued = tryProduce(context, mechMissile, factory);
                }
                continue;
            }

            // 2 级陆军工厂：重型坦克 / 等离子坦克；前提是还没有空军战斗单位
            if (!landTankQueued && factory instanceof LandFactory && factory.getUpgradeLevel() >= 2
                    && !hasAirArmy) {
                landTankQueued = tryEither(context, factory, UnitTypeEnum.heavyTank, heavyTankCount,
                        plasmaTank, plasmaTankCount);
            }
        }
    }

    // ===== 升级逻辑接管 =====

    /** 升级规则每轮 250 帧（≈4.2 秒）评估一次，与原版升级抽签同节奏。 */
    private static final float UPGRADE_ROUND_FRAMES = 250.0f;
    private static final int MINUTE_MILLIS = 60_000;
    private static final int POLICY_STATE_VERSION = 1;
    private static final long MINE_STALL_MILLIS = 10L * MINUTE_MILLIS;

    private final AiEnemyIntel enemyIntel = new AiEnemyIntel();
    private float upgradeTimer = UPGRADE_ROUND_FRAMES;
    private int lastOreCount;
    private int lastOreGrewMillis;

    @Override
    public boolean overridesUpgrades() {
        return true;
    }

    @Override
    public void onUpgrades(AiContext context, float elapsedFrames) {
        upgradeTimer += elapsedFrames;
        if (upgradeTimer < UPGRADE_ROUND_FRAMES) return;
        upgradeTimer = 0.0f;

        List<OrderableUnit> units = context.ownUnits();
        double credits = context.credits();
        double income = context.team().getScaledIncomeRate();
        int gameMillis = GameEngine.getInstance().gameTimeMillis;

        upgradeExtractors(context, units);
        upgradeFactories(context, units, credits, income, gameMillis);
        upgradeDefences(context, units, credits, income, gameMillis);
        refillAntiNukeAmmo(context, units);
    }

    /** 矿脉：按“先把一条顶到 3 级”的顺序轮询，升级成功一个就停止本轮；3 级永远选 8000 的超频分支。 */
    private void upgradeExtractors(AiContext context, List<OrderableUnit> units) {
        if (tryUpgradeLevel(context, units, AiUnits.extractorT3(),
                AiUnits.extractorT3Overclocked())) return;
        if (tryUpgradeLevel(context, units, AiUnits.extractorT2(), AiUnits.extractorT3())) return;
        tryUpgradeLevel(context, units, AiUnits.extractorT1(), AiUnits.extractorT2());
    }

    /** 工厂类升级。 */
    private void upgradeFactories(AiContext context, List<OrderableUnit> units, double credits,
                                  double income, int gameMillis) {
        // 1 级陆军工厂 / 空军基地：有就升
        if (tryUpgradeDefault(context, units, UnitTypeEnum.landFactory, 1)) return;
        if (tryUpgradeDefault(context, units, UnitTypeEnum.airFactory, 1)) return;
        // 1 级机甲工厂：10 分钟 + 收入 > 70
        if (gameMillis > 10 * MINUTE_MILLIS && income > 70
                && tryUpgradeLevel(context, units, AiUnits.mechFactory(), AiUnits.mechFactoryT2())) return;
        // 1 级海军基地：20 分钟 + 资金 > 5000 + 收入 > 150
        if (gameMillis > 20 * MINUTE_MILLIS && credits > 5000 && income > 150
                && tryUpgradeDefault(context, units, UnitTypeEnum.seaFactory, 1)) return;
        // 资源制造仪：资金 > 4000 + 10 分钟没有新增矿脉 + 收入 > 100 → 0.2 概率
        if (credits > 4000 && income > 100 && mineStalled(gameMillis) && AiUpgrades.roll(0.2f)) {
            if (tryUpgradeLevel(context, units, AiUnits.fabricatorT1(), AiUnits.fabricatorT2())) return;
            tryUpgradeLevel(context, units, AiUnits.fabricatorT2(), AiUnits.fabricatorT3());
        }
    }

    /** 防御建筑升级。 */
    private void upgradeDefences(AiContext context, List<OrderableUnit> units, double credits,
                                 double income, int gameMillis) {
        // 防空炮：防空机甲 < 5 且资金 > 1500 → 升 2 级
        if (credits > 1500 && AiUnits.count(units, AiUnits.mechMissile()) < 5
                && tryUpgradeLevel(context, units, AiUnits.antiAirTurret(), AiUnits.antiAirTurretT2())) return;
        // 1 级普通炮台：每轮整队 roll 一次，然后挑一个炮台执行
        UnitType branch = rollTurretBranch();
        if (branch != null && tryUpgradeLevel(context, units, AiUnits.turretT1(), branch)) return;
        // 2 级电击塔（源码里叫 c_turret_t1_lightning）→ 3 级：敌方有移动炮台/机枪机甲 + 资金 > 7000
        if (credits > 7000 && enemyIntel.hasAntiTurretThreat()
                && tryUpgradeLevel(context, units, AiUnits.turretT1Lightning(), AiUnits.turretT2Lightning())) return;
        // 2 级机枪（c_turret_t2_gun）→ 3 级：20 分钟 + 资金 > 12000 + 敌方机枪机甲 ≥ 2 或喷火机甲 ≥ 1
        if (gameMillis > 20 * MINUTE_MILLIS && credits > 12000
                && (enemyIntel.count(AiUnits.mechMinigun()) >= 2 || enemyIntel.count(AiUnits.mechFlame()) >= 1)
                && tryUpgradeLevel(context, units, AiUnits.turretT2Gun(), AiUnits.turretT3Gun())) return;
        // 极光防御（激光防御塔）：资金 > 3000 + 收入 > 100；敌方有机枪机甲/移动炮台时 0.6，否则 0.2
        if (credits > 3000 && income > 100) {
            float chance = enemyIntel.hasAntiTurretThreat() ? 0.6f : 0.2f;
            if (AiUpgrades.roll(chance)) {
                tryUpgradeDefault(context, units, UnitTypeEnum.laserDefence, 1);
            }
        }
    }

    /** 炮台分支：满足电击塔条件时先 0.5 概率走电击，否则 0.7 机枪 / 0.3 喷火（不含防空炮）。 */
    private UnitType rollTurretBranch() {
        if (enemyIntel.hasAntiTurretThreat() && AiUpgrades.roll(0.5f)) {
            return AiUnits.turretT1Lightning();
        }
        return AiUpgrades.roll(0.7f) ? AiUnits.turretT2Gun() : AiUnits.turretT2Flame();
    }

    /** 反核弹补弹沿用原版：接管升级抽签后必须自己补，否则 AI 会失去反导能力。 */
    private static void refillAntiNukeAmmo(AiContext context, List<OrderableUnit> units) {
        UnitType type = AiUnits.antiNukeLauncher();
        if (type == null) return;
        for (OrderableUnit unit : AiUnits.allOfType(units, type)) {
            AbstractUnitAction action = AiUpgrades.defaultUpgradeAction(unit);
            if (action != null) context.orders().tryUpgradeAction(unit, action);
        }
    }

    /** 把 fromType 里第一个能升级的单位升到 targetType，成功返回 true。 */
    private static boolean tryUpgradeLevel(AiContext context, List<OrderableUnit> units,
                                           UnitType fromType, UnitType targetType) {
        if (fromType == null || targetType == null) return false;
        for (OrderableUnit unit : AiUnits.allOfType(units, fromType)) {
            if (context.orders().tryUpgrade(unit, targetType)) return true;
        }
        return false;
    }

    /** 内置建筑（工厂、极光防御）只有一条升级路线，用原版 cm() 默认动作；levelAtLeast 用于跳过已升级的。 */
    private static boolean tryUpgradeDefault(AiContext context, List<OrderableUnit> units,
                                             UnitType type, int maxLevel) {
        if (type == null) return false;
        for (OrderableUnit unit : AiUnits.allOfType(units, type)) {
            if (unit.getUpgradeLevel() > maxLevel) continue;
            AbstractUnitAction action = AiUpgrades.defaultUpgradeAction(unit);
            if (action != null && context.orders().tryUpgradeAction(unit, action)) return true;
        }
        return false;
    }

    /** 记录矿脉数量变化的时间戳；10 分钟没有增加就允许升级资源制造仪。 */
    private void trackOreCounts(List<OrderableUnit> units, int gameMillis) {
        int oreCount = AiUnits.count(units, AiUnits.extractorT1())
                + AiUnits.count(units, AiUnits.extractorT2())
                + AiUnits.count(units, AiUnits.extractorT3())
                + AiUnits.count(units, AiUnits.extractorT3Overclocked());
        if (lastOreGrewMillis == 0) lastOreGrewMillis = gameMillis;
        if (oreCount > lastOreCount) {
            lastOreCount = oreCount;
            lastOreGrewMillis = gameMillis;
        }
    }

    private boolean mineStalled(int gameMillis) {
        return gameMillis - lastOreGrewMillis > MINE_STALL_MILLIS;
    }

    @Override
    public void writeState(DataOutputStream output) throws IOException {
        output.writeInt(POLICY_STATE_VERSION);
        enemyIntel.write(output);
        output.writeInt(lastOreCount);
        output.writeInt(lastOreGrewMillis);
    }

    @Override
    public void readState(DataInputStream input) throws IOException {
        if (input.available() == 0) return;   // 旧存档没有策略状态
        if (input.readInt() != POLICY_STATE_VERSION) {
            throw new IOException("Unsupported AI-high policy state");
        }
        enemyIntel.read(input);
        lastOreCount = input.readInt();
        lastOreGrewMillis = input.readInt();
    }

    /** 空军配比：重型拦截机 : 武装直升机 尽量保持在 1:3 到 3:4 之间。 */
    private static UnitType nextAirFighter(int interceptors, int gunships, UnitType heavyInterceptor) {
        if (heavyInterceptor == null) return UnitTypeEnum.gunShip;
        if (interceptors * 3 < gunships) return heavyInterceptor;             // 低于 1:3，先补拦截机
        if (interceptors * 4 > gunships * 3) return UnitTypeEnum.gunShip;     // 高于 3:4，先补武装直升机
        return interceptors * 2 <= gunships ? heavyInterceptor : UnitTypeEnum.gunShip; // 区间内朝 1:2 靠
    }

    /** 是否已有该路线的战斗单位：见 {@link AiUnits#hasCombatUnitsOfMovement}。 */

    // ===== 建筑生产接管：造什么、造在哪、谁去造 =====

    /** 两次建筑尝试之间的最小间隔（帧，60 帧 = 1 秒）。原版由基地 defensiveScore 周期（约 270~450 帧）控制。 */
    private static final float BUILDING_ATTEMPT_INTERVAL = 180.0f;

    /** 距离上次建造尝试累计的帧数；只影响节奏，不写存档也不会出错。 */
    private float buildingAttemptTimer = BUILDING_ATTEMPT_INTERVAL;
    /** 上一次尝试的基地，用来在多基地之间轮转。 */
    private int lastBuildingZoneId;

    //是否接管原版的逻辑
    @Override
    public boolean overridesBuildingProduction() {
        return true;
    }

    @Override
    public void onBuildingProduction(AiContext context, float elapsedFrames) {
        List<BaseZone> bases = context.originalBases();
        if (bases.isEmpty()) return;
        buildingAttemptTimer += elapsedFrames;
        if (buildingAttemptTimer < BUILDING_ATTEMPT_INTERVAL) return;
        buildingAttemptTimer = 0.0f;
        // 从上次造过的基地的下一个开始轮转，避免资金长期被同一个基地吃掉
        int start = 0;
        for (int i = 0; i < bases.size(); i++) {
            if (bases.get(i).strategyId == lastBuildingZoneId) {
                start = (i + 1) % bases.size();
                break;
            }
        }
        for (int i = 0; i < bases.size(); i++) {
            BaseZone base = bases.get((start + i) % bases.size());
            if (tryBuildInBase(context, base)) {
                lastBuildingZoneId = base.strategyId;
                return;
            }
        }
    }

    /** 单个基地的一次建造尝试：原版打分决定造哪个 → 资金门槛 → 在我的规则范围内选址 → 选人 → 发令。 */
    private boolean tryBuildInBase(AiContext context, BaseZone base) {
        if (base == null || base.isDestroyed || base.getStage() == null) return false;
        List<OrderableUnit> units = context.ownUnits();
        UnitType type = chooseBuildingType(context, base);
        if (type == null || !hasBudgetFor(context, base.getLastFoundBuildingPriority())) return false;
        return tryBuildType(context, base, units, type);
    }

    /** 按选定的类型建造一次：本策略只接管 5 类建筑的选址，其余建筑（工厂/指挥中心/提取器…）完全沿用原版。 */
    private boolean tryBuildType(AiContext context, BaseZone base, List<OrderableUnit> units, UnitType type) {
        if (!overridesSiting(type)) {
            return base.tryBuildUnit(type);
        }
        PointF location = findLocation(context, base, units, type);
        if (location == null) return false;
        OrderableUnit builder = base.findBuilderForBuilding(type, location.x, location.y);
        if (builder == null) return false;
        // 位置与建造者都由本策略决定；入队前会再校验一次放置合法性与费用
        return context.orders().tryBuildBuilding(type, location.x, location.y, builder);
    }

    /** 本策略只覆盖这 5 类建筑的选址：炮塔、防空炮塔、资源制造仪、极光防御、修复湾。 */
    private static boolean overridesSiting(UnitType type) {
        return AiUnits.isAnyOf(type, AiUnits.turretT1(), UnitTypeEnum.turret)
                || AiUnits.isAnyOf(type, AiUnits.antiAirTurret(), UnitTypeEnum.antiAirTurret)
                || AiUnits.isAnyOf(type, AiUnits.fabricatorT1(), UnitTypeEnum.fabricator)
                || AiUnits.isAnyOf(type, AiUnits.laserDefence(), UnitTypeEnum.laserDefence)
                || AiUnits.isAnyOf(type, AiUnits.repairBay(), UnitTypeEnum.repairbay);
    }

    /**
     * 5 类建筑的选址分派。炮塔与防空炮塔在本策略规则找不到位置时退回原版选址，
     * 极光防御/资源制造仪/修复湾的规则本身就是前提，不满足就不建。
     */
    private PointF findLocation(AiContext context, BaseZone base, List<OrderableUnit> units, UnitType type) {
        if (AiUnits.isAnyOf(type, AiUnits.repairBay(), UnitTypeEnum.repairbay)) {
            return findRepairBaySpot(context, units, type);
        }
        if (AiUnits.isAnyOf(type, AiUnits.laserDefence(), UnitTypeEnum.laserDefence)) {
            return findLaserDefenceSpot(context, units, type);
        }
        if (AiUnits.isAnyOf(type, AiUnits.fabricatorT1(), UnitTypeEnum.fabricator)) {
            return findFabricatorSpot(context, units, type);
        }
        if (AiUnits.isAnyOf(type, AiUnits.antiAirTurret(), UnitTypeEnum.antiAirTurret)) {
            PointF spot = findAntiAirSpot(context, base, units, type);
            return spot != null ? spot : base.findBuildLocation(type);
        }
        PointF spot = findOreTurretSpot(context, base, units, type);
        return spot != null ? spot : base.findBuildLocation(type);
    }

    // 微调点①：造什么。用原版打分（可注入分数覆盖），再叠加本策略的"换型"偏好。
    private UnitType chooseBuildingType(AiContext context, BaseZone base) {
        UnitType original = base.getBestBuildingToBuild(buildingScoreAdjuster);
        if (original == null) return null;
        return adjustBuildingType(context, base, original);
    }

    /**
     * 覆盖原版建造分数：只改两条，其余完全沿用原版打分表。
     * 机甲工厂 0.03 → 0.31（原版几乎永远轮不到它，导致一直没有机甲线）；
     * 修复湾 0.02 → 0.1（原版优先级太低，长期不出）。
     */
    private final BaseZone.ScoreAdjuster buildingScoreAdjuster = (type, score) -> {
        if (AiUnits.isAnyOf(type, AiUnits.mechFactory())) return 0.31f;
        if (AiUnits.isAnyOf(type, AiUnits.repairBay(), UnitTypeEnum.repairbay)) return 0.1f;
        return score;
    };

    /**
     * 微调点②：只做"换成另一个建筑"的偏好；**不再用条件把这一轮作废**。
     * 条件判断挪进各自的选址函数：先在规则允许的范围内找位置，再判断是否满足条件，
     * 这样只要原版打分选中了这个建筑，对话框里的规则就会尽量把它真的建出来。
     */
    private UnitType adjustBuildingType(AiContext context, BaseZone base, UnitType original) {
        List<OrderableUnit> units = context.ownUnits();
        if (base.isUnderAttack() && base.canBuild(UnitTypeEnum.laserDefence)
                && AiUnits.count(units, UnitTypeEnum.laserDefence) == 0) {
            return UnitTypeEnum.laserDefence;
        }
        if (AiUnits.isAnyOf(original, AiUnits.turretT1(), UnitTypeEnum.turret)
                && base.canBuild(AiUnits.antiAirTurret())
                && AiUnits.count(units, AiUnits.antiAirTurret()) * 2
                < AiUnits.count(units, AiUnits.turretT1()) + AiUnits.count(units, UnitTypeEnum.turret)
                && findAntiAirSpot(context, base, units, AiUnits.antiAirTurret()) != null) {
            return AiUnits.antiAirTurret();
        }
        return original;
    }

    /** 复刻原版资金门槛：优先级越低，要求的存款越高，避免低价值建筑吃掉一次建造机会。 */
    private static boolean hasBudgetFor(AiContext context, float priority) {
        double credits = context.credits();
        if (priority <= 0.01f) return credits >= 4800.0d;
        if (priority <= 0.05f) return credits >= 3100.0d;
        if (priority <= 0.1f) return credits >= 2800.0d;
        if (priority <= 0.2f) return credits >= 2100.0d;
        if (priority <= 0.4f) return credits >= 1700.0d;
        if (priority <= 0.8f) return credits >= 1300.0d;
        return true;
    }

    // 微调点③：造在哪。只有 5 类建筑走下面的规则（见 findLocation）；其余建筑选址完全沿用原版。

    /** 我方阵营 / 前线视图（按锚点缓存，锚点变了自动重算）。 */
    private AiTerritory territory(AiContext context) {
        return AiTerritory.of(context.team());
    }

    /**
     * 修复湾：找一块"覆盖半径内有 ≥1 矿 + ≥3 炮台，或 ≥6 炮台"的位置。
     * 覆盖半径用原版修复湾自己的纳米距离（230）。
     */
    private PointF findRepairBaySpot(AiContext context, List<OrderableUnit> units, UnitType type) {
        float coverage = 230f;
        for (BaseZone base : context.originalBases()) {
            for (int attempt = 0; attempt < 24; attempt++) {
                PointF point = base.getRandomPointInside();
                if (!context.canPlaceBuilding(type, point.x, point.y)) continue;
                int ores = AiPlacement.countWithin(units, point.x, point.y, coverage, AiPlacement::isOreBuilding);
                int towers = AiPlacement.countWithin(units, point.x, point.y, coverage, AiPlacement::isDefenceTower);
                if ((ores >= 1 && towers >= 3) || towers >= 6) return point;
            }
        }
        return null;
    }

    /** 极光防御：必须在前线；覆盖半径（160）内最多 1 个，敌方有机枪机甲时不限。 */
    private PointF findLaserDefenceSpot(AiContext context, List<OrderableUnit> units, UnitType type) {
        AiTerritory territory = territory(context);
        if (territory == null) return null;
        boolean unlimited = enemyIntel.count(AiUnits.mechMinigun()) > 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            PointF point = findFrontlinePoint(territory);
            if (point == null) return null;
            if (!context.canPlaceBuilding(type, point.x, point.y)) continue;
            if (!unlimited && AiPlacement.countWithin(units, point.x, point.y, 160f,
                    AiPlacement::isLaserDefence) > 0) continue;
            return point;
        }
        return null;
    }

    /** 资源制造仪：必须落在指挥中心覆盖（280）内，或者我方阵营后方（越过前线之后）；规格要求资金>2000、收入>100。 */
    private PointF findFabricatorSpot(AiContext context, List<OrderableUnit> units, UnitType type) {
        double credits = context.credits();
        double income = context.team().getScaledIncomeRate();
        if (!(credits > 2000.0d && income > 100.0d)) return null;
        AiTerritory territory = territory(context);
        for (BaseZone base : context.originalBases()) {
            for (int attempt = 0; attempt < 24; attempt++) {
                PointF point = base.getRandomPointInside();
                if (!context.canPlaceBuilding(type, point.x, point.y)) continue;
                if (AiPlacement.countWithin(units, point.x, point.y, 280f,
                        unit -> unit instanceof CommandCenter) > 0) return point;
                // D7 确认值：越过前线 50 格以上，或者距我方指挥中心 70 格以内
                if (territory != null && (territory.depthBehindFrontline(point.x, point.y) > 50
                        || territory.distanceFromOurCenter(point.x, point.y) < 70)) return point;
            }
        }
        return null;
    }

    /**
     * 防空炮台三条规则（按优先级）：
     * ① 前线炮塔后方 5 格内的 45 度扇区，防空 : 炮台 保持在 1:3 ~ 3:4；
     * ② 野生矿（远离指挥中心、周围没有我方生产建筑），同一矿覆盖内不超过 3 个；
     * ③ 我方到敌方路线上的液体渡口、靠我方一侧、距离可控，0.01 概率，且该处最多 1 个。
     */
    private PointF findAntiAirSpot(AiContext context, BaseZone base, List<OrderableUnit> units, UnitType type) {
        AiTerritory territory = territory(context);
        // ① 前线炮塔后方
        List<OrderableUnit> turrets = new ArrayList<>();
        turrets.addAll(AiUnits.allOfType(units, AiUnits.turretT1()));
        turrets.addAll(AiUnits.allOfType(units, AiUnits.turretT2Gun()));
        turrets.addAll(AiUnits.allOfType(units, AiUnits.turretT2Flame()));
        turrets.addAll(AiUnits.allOfType(units, AiUnits.turretT1Lightning()));
        int antiAirCount = AiPlacement.countWithin(units, base.posX, base.posY, 100000f,
                AiPlacement::isAntiAirTurret);
        if (!turrets.isEmpty() && antiAirCount * 3 < turrets.size()) {
            for (int attempt = 0; attempt < 12; attempt++) {
                OrderableUnit turret = turrets.get(Utility.getRandomIntInRange(0, turrets.size() - 1));
                com.corrodinggames.rts.game.units.BaseUnit enemy =
                        AiPlacement.nearestEnemy(turret.posX, turret.posY, context.team());
                if (enemy == null) break;
                // 炮台背离敌人的方向 ±45 度、5 格（100 世界单位）以内取点
                float away = (float) Math.atan2(turret.posY - enemy.posY, turret.posX - enemy.posX);
                float jitter = (float) Math.toRadians(Utility.randomFloatInRange(-45f, 45f));
                float distance = Utility.randomFloatInRange(30f, 100f);
                float x = turret.posX + ((float) Math.cos(away + jitter) * distance);
                float y = turret.posY + ((float) Math.sin(away + jitter) * distance);
                if (context.canPlaceBuilding(type, x, y)) return new PointF(x, y);
            }
        }
        // ② 野生矿（必须是我方陆地走得到的，否则建造者只会走到海岸线干等）
        for (PointF ore : AiPlacement.oreAnchors()) {
            if (territory == null || !territory.reachableForUs(ore.x, ore.y)) continue;
            if (AiPlacement.distanceToNearestCommandCenter(units, ore.x, ore.y)
                    < AiPlacement.WILD_ORE_MIN_COMMAND_CENTER_DISTANCE) continue;
            if (AiPlacement.countProductionBuildingsWithin(units, ore.x, ore.y, 300f) > 0) continue;
            if (AiPlacement.countWithin(units, ore.x, ore.y, AiPlacement.ORE_COVERAGE_RADIUS,
                    AiPlacement::isAntiAirTurret) >= 3) continue;
            PointF spot = findSpotNear(context, type, ore, AiPlacement.ORE_COVERAGE_RADIUS);
            if (spot != null) return spot;
        }
        // ③ 渡口（0.01 概率）
        if (territory != null && AiUpgrades.roll(0.01f)) {
            for (PointF crossing : territory.liquidCrossings()) {
                if (!territory.isOurSide(crossing.x, crossing.y)) continue;
                if (!territory.reachableForUs(crossing.x, crossing.y)) continue;
                if (territory.distanceFromOurCenter(crossing.x, crossing.y) > 60) continue; // 距离可控（格）
                if (AiPlacement.countWithin(units, crossing.x, crossing.y, 200f,
                        AiPlacement::isAntiAirTurret) > 0) continue;
                PointF spot = findSpotNear(context, type, crossing, 120f);
                if (spot != null) return spot;
            }
        }
        return null;
    }

    /**
     * 1 级普通炮塔：敌方指挥中心陆地可达的我方矿脉（到前线为止），面向敌方一侧、矿覆盖范围内，
     * 0.5 概率造一个；每处最多 2 个（前线矿脉 2 个之后 0.05 概率增加到最多 5 个）。
     * 没有合适矿脉时退回基地内随机撒点（原版微调）。
     */
    private PointF findOreTurretSpot(AiContext context, BaseZone base, List<OrderableUnit> units, UnitType type) {
        AiTerritory territory = territory(context);
        for (PointF ore : AiPlacement.contestedOres(units, territory)) {
            if (territory != null && !territory.isOurSide(ore.x, ore.y)) continue;      // 到前线为止
            int exists = AiPlacement.countWithin(units, ore.x, ore.y, AiPlacement.ORE_COVERAGE_RADIUS,
                    AiPlacement::isGunTurret);
            boolean frontline = territory != null && territory.isFrontline(ore.x, ore.y, 10);
            int cap = frontline ? 5 : 2;
            if (exists >= cap) continue;
            float chance = exists >= 2 ? 0.05f : 0.5f;
            if (!AiUpgrades.roll(chance)) continue;
            PointF spot = findSpotFacingEnemy(context, type, ore, territory);
            if (spot != null) return spot;
        }
        return null;   // 没有合适矿脉：由 findLocation 交回原版选址
    }

    /** 在某点周围随机取一个合法位置（用于矿脉/渡口这类固定锚点）。 */
    private static PointF findSpotNear(AiContext context, UnitType type, PointF center, float radius) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = Utility.randomFloatInRange(0f, (float) (Math.PI * 2));
            float distance = Utility.randomFloatInRange(0f, radius);
            float x = center.x + ((float) Math.cos(angle) * distance);
            float y = center.y + ((float) Math.sin(angle) * distance);
            if (context.canPlaceBuilding(type, x, y)) return new PointF(x, y);
        }
        return null;
    }

    /** 矿脉炮塔专用：只在"面向敌方势力"的半圆内取点。 */
    private static PointF findSpotFacingEnemy(AiContext context, UnitType type, PointF ore,
                                              AiTerritory territory) {
        float towards = 0f;
        boolean hasDirection = false;
        if (territory != null && territory.hasEnemyAnchor()) {
            towards = (float) Math.atan2(territory.enemyY() - ore.y, territory.enemyX() - ore.x);
            hasDirection = true;
        }
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = hasDirection
                    ? towards + Math.toRadians(Utility.randomFloatInRange(-90f, 90f))
                    : Utility.randomFloatInRange(0f, (float) (Math.PI * 2));
            float distance = Utility.randomFloatInRange(0f, AiPlacement.ORE_COVERAGE_RADIUS);
            float x = ore.x + ((float) Math.cos(angle) * distance);
            float y = ore.y + ((float) Math.sin(angle) * distance);
            if (context.canPlaceBuilding(type, x, y)) return new PointF(x, y);
        }
        return null;
    }

    /** 前线上的一点：取通往敌方路线里两侧距离最接近的一段，再抖动。 */
    private PointF findFrontlinePoint(AiTerritory territory) {
        List<Point> route = territory.routeToEnemy();
        if (route.isEmpty()) return null;
        List<Point> candidates = new ArrayList<>();
        for (Point tile : route) {
            float x = AiMapGraph.tileCenterX(tile.worldX);
            float y = AiMapGraph.tileCenterY(tile.worldY);
            if (territory.isFrontline(x, y, 6)) candidates.add(tile);
        }
        if (candidates.isEmpty()) candidates.add(route.get(route.size() / 2));
        Point tile = candidates.get(Utility.getRandomIntInRange(0, candidates.size() - 1));
        return new PointF(AiMapGraph.tileCenterX(tile.worldX) + Utility.randomFloatInRange(-30f, 30f),
                AiMapGraph.tileCenterY(tile.worldY) + Utility.randomFloatInRange(-30f, 30f));
    }

    // 微调点④：默认选址——基地半径内随机取点，要求原版放置检查通过，并与同类建筑保持间距。
    //尝试生产第一个单位或第二个单位
    private static boolean tryEither(AiContext context, OrderableUnit factory,
                                     UnitType first, int firstCount, UnitType second, int secondCount) {
        if (firstCount <= secondCount) {
            return tryProduce(context, first, factory) || tryProduce(context, second, factory);
        }
        return tryProduce(context, second, factory) || tryProduce(context, first, factory);
    }

    private static boolean tryProduce(AiContext context, UnitType type, OrderableUnit factory) {
        return type != null && context.orders().tryProduceUnit(type, factory);
    }
}
