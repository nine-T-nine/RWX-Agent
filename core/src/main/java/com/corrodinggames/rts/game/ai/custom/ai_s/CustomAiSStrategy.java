package com.corrodinggames.rts.game.ai.custom.ai_s;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.AIUnitGroupBase;
import com.corrodinggames.rts.game.ai.AIStrategyNode;
import com.corrodinggames.rts.game.ai.custom.util.AiEnemyIntel;
import com.corrodinggames.rts.game.ai.custom.util.AiUnits;
import com.corrodinggames.rts.game.ai.custom.util.AiUpgrades;
import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiStrategy;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.buildings.Projectile;
import com.corrodinggames.rts.game.units.buildings.CommandCenter;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.Utility;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** AI_S keeps the original Impossible pipeline plus a locked land/air macro and AI-high upgrades. */
public final class CustomAiSStrategy implements AiStrategy {
    public static final int DEFINITION_CODE = 7;
    private static final float INCOME_MULTIPLIER = 1.0f;
    private static final float UPGRADE_ROUND_FRAMES = 250.0f;
    private static final int MINUTE_MILLIS = 60_000;
    private static final int POLICY_STATE_VERSION = 3;

    /** Identity keys prevent old matches' team IDs from affecting a new match. */
    private static final Map<PlayerTeam, MacroStrategy> MACROS_BY_TEAM = new IdentityHashMap<>();

    private final AiEnemyIntel enemyIntel = new AiEnemyIntel();
    private float upgradeTimer = UPGRADE_ROUND_FRAMES;
    /** Shared by every spending callback in one economy tick. */
    private boolean skipSpendingCycle;
    /** Issued upgrades block later rounds only until their queue entries appear. */
    private final List<UpgradeTarget> pendingUpgrades = new ArrayList<>();
    private MacroStrategy macroStrategy;
    private LandMode landMode = LandMode.X;
    private int lastLandModeRefreshMillis = -5 * MINUTE_MILLIS;
    private final AiSBuildingPolicy buildingPolicy = new AiSBuildingPolicy();
    private final AiSProductionPolicy productionPolicy = new AiSProductionPolicy();

    private enum MacroStrategy {
        LAND,
        AIR;

        /** Deliberate no-op until macro-specific production/tactical policies are authored. */
        void onEconomy(AiContext context, float elapsedFrames) { }
    }
    enum LandMode { X, Y, Z }

    public static AiDefinition definition() {
        return AiDefinition.custom(
                        DEFINITION_CODE,
                        "rwx.ai-s",
                        "AI_S",
                        "AI_S",
                        CustomAiSStrategy::new)
                .withIncomeMultiplier(INCOME_MULTIPLIER)
                .extendOriginal(3);
    }

    @Override
    public String id() {
        return "rwx.ai-s.v1";
    }

    @Override
    public void onStart(AiContext context, boolean restored) {
        if (macroStrategy == null) macroStrategy = chooseMacro(context);
        MACROS_BY_TEAM.put(context.team(), macroStrategy);
    }

    @Override
    public void onStop(AiContext context) {
        MACROS_BY_TEAM.remove(context.team());
    }

    /** The chosen macro has a stable dispatch point but intentionally issues no orders yet. */
    @Override
    public void onEconomy(AiContext context, float elapsedFrames) {
        List<OrderableUnit> units = context.ownUnits();
        int gameMillis = GameEngine.getInstance().gameTimeMillis;
        enemyIntel.update(context);
        macroStrategy.onEconomy(context, elapsedFrames);
        if (macroStrategy == MacroStrategy.LAND
                && gameMillis - lastLandModeRefreshMillis >= 5 * MINUTE_MILLIS) {
            lastLandModeRefreshMillis = gameMillis;
            int choices = context.team().getScaledIncomeRate() > 150 ? 3 : 2;
            landMode = LandMode.values()[Utility.getRandomIntInRange(0, choices - 1)];
        }
        // A skipped cycle is intentionally a complete save period: no upgrade, unit, or building
        // order follows it. Otherwise upgrades are evaluated before either production callback.
        skipSpendingCycle = AiUpgrades.roll(0.2f);
        upgradeTimer += elapsedFrames;
        if (upgradeTimer >= UPGRADE_ROUND_FRAMES) {
            upgradeTimer = 0.0f;
            if (skipSpendingCycle) AiSCycleLog.write(context.team().teamId, "upgrade", gameMillis,
                    "selected=save result=skipped upgrade, building and unit spending");
            else runUpgradeRound(context);
        }
    }

    @Override
    public boolean overridesBuildingProduction() {
        return true;
    }

    @Override
    public void onBuildingProduction(AiContext context, float elapsedFrames) {
        String outcome = skipSpendingCycle ? "saved (20% roll)" :
                buildingPolicy.update(context, macroStrategy == MacroStrategy.AIR, landMode);
        AiSCycleLog.write(context.team().teamId, "building",
                GameEngine.getInstance().gameTimeMillis, "macro=" + macroStrategy + " mode=" + landMode + " " + outcome);
    }

    @Override
    public boolean overridesUnitProduction() {
        return true;
    }

    @Override
    public boolean overridesTransportWaiting() {
        return true;
    }

    /** Enforces the three-minute passenger timeout even when no new reinforcement is requested. */
    @Override
    public void onTactics(AiContext context, float elapsedFrames) {
        for (AIStrategyNode node : context.originalNodes()) {
            if (node instanceof AIUnitGroupBase) ((AIUnitGroupBase) node).getTransportWaiterCount();
        }
    }

    @Override
    public void onUnitProduction(AiContext context, float elapsedFrames) {
        String outcome = skipSpendingCycle ? "saved (20% roll)" :
                productionPolicy.update(context, macroStrategy == MacroStrategy.AIR, landMode);
        AiSCycleLog.write(context.team().teamId, "unit",
                GameEngine.getInstance().gameTimeMillis, "macro=" + macroStrategy + " mode=" + landMode + " " + outcome);
    }

    private static MacroStrategy chooseMacro(AiContext context) {
        if (anyEnemyCommandCenterIsLandUnreachable(context)) return MacroStrategy.AIR;

        int land = 0;
        int air = 0;
        for (int i = 0; i < PlayerTeam.TEAM_NEUTRAL; i++) {
            PlayerTeam teammate = PlayerTeam.k(i);
            if (teammate == null || teammate == context.team() || !context.team().d(teammate)) continue;
            MacroStrategy teammateMacro = MACROS_BY_TEAM.get(teammate);
            if (teammateMacro == MacroStrategy.LAND) land++;
            else if (teammateMacro == MacroStrategy.AIR) air++;
        }
        if (3 * land < 5 * air) return MacroStrategy.LAND;
        if (5 * air < 3 * land) return MacroStrategy.AIR;
        return Utility.getRandomIntInRange(1, 8) <= 5 ? MacroStrategy.LAND : MacroStrategy.AIR;
    }

    /** A land macro is invalid when any live hostile command centre lacks a land route from all ours. */
    private static boolean anyEnemyCommandCenterIsLandUnreachable(AiContext context) {
        List<OrderableUnit> ours = context.ownUnits();
        boolean hasOwnCommandCenter = false;
        for (OrderableUnit unit : ours) {
            if (unit instanceof CommandCenter) {
                hasOwnCommandCenter = true;
                break;
            }
        }
        if (!hasOwnCommandCenter) return false;

        for (BaseUnit enemy : BaseUnit.bE) {
            if (enemy == null || enemy.isDead || !(enemy instanceof CommandCenter)
                    || enemy.team == null || !context.team().c(enemy.team)) continue;
            boolean reachable = false;
            for (OrderableUnit oursUnit : ours) {
                if (oursUnit instanceof CommandCenter
                        && context.team().isPathPossibleBetweenPoints(oursUnit.posX, oursUnit.posY,
                        enemy.posX, enemy.posY, UnitMovementType.LAND)) {
                    reachable = true;
                    break;
                }
            }
            if (!reachable) return true;
        }
        return false;
    }

    @Override
    public boolean overridesUpgrades() {
        return true;
    }

    /** The upgrade work is deliberately run from onEconomy, before production callbacks. */
    @Override
    public void onUpgrades(AiContext context, float elapsedFrames) {
        // Kept as a no-op to retain ownership of the original upgrade lottery. See runUpgradeRound.
    }

    private void runUpgradeRound(AiContext context) {
        List<OrderableUnit> units = context.ownUnits();
        StringBuilder result = new StringBuilder();
        retryPendingUpgrades(context, units, result);
        if (!pendingUpgrades.isEmpty()) {
            AiSCycleLog.write(context.team().teamId, "upgrade",
                    GameEngine.getInstance().gameTimeMillis, result.toString());
            return;
        }
        double credits = context.credits();
        double income = context.team().getScaledIncomeRate();
        int gameMillis = GameEngine.getInstance().gameTimeMillis;

        if (AiUpgrades.roll(0.5f)) {
            result.append("selected=economy ");
            runEconomicUpgrade(context, units, credits, income, gameMillis, result);
        } else {
            result.append("selected=defence ");
            runDefenceUpgrades(context, units, credits, income, gameMillis, result);
            result.append(" antiNukeAmmoQueued=").append(refillAntiNukeAmmo(context, units));
        }
        AiSCycleLog.write(context.team().teamId, "upgrade", gameMillis, result.toString());
    }

    private void runEconomicUpgrade(AiContext context, List<OrderableUnit> units, double credits,
                                    double income, int gameMillis, StringBuilder result) {
        List<UpgradeChoice> mines = new ArrayList<>();
        addLevelChoices(mines, units, AiUnits.extractorT1(), AiUnits.extractorT2());
        addLevelChoices(mines, units, AiUnits.extractorT2(), AiUnits.extractorT3());
        if (income > 130) addLevelChoices(mines, units, AiUnits.extractorT3(), AiUnits.extractorT3Overclocked());

        List<UpgradeChoice> factories = new ArrayList<>();
        addDefaultChoices(factories, units, UnitTypeEnum.landFactory, 1);
        addDefaultChoices(factories, units, UnitTypeEnum.airFactory, 1);
        if (gameMillis > 15 * MINUTE_MILLIS && income > 110) {
            addLevelChoices(factories, units, AiUnits.mechFactory(), AiUnits.mechFactoryT2());
            addDefaultChoices(factories, units, UnitTypeEnum.seaFactory, 1);
        }

        List<UpgradeChoice> fabricators = new ArrayList<>();
        if (gameMillis > 15 * MINUTE_MILLIS && credits > 4000 && income > 130) {
            addLevelChoices(fabricators, units, AiUnits.fabricatorT1(), AiUnits.fabricatorT2());
            addLevelChoices(fabricators, units, AiUnits.fabricatorT2(), AiUnits.fabricatorT3());
        }
        int mineWeight = gameMillis < 10 * MINUTE_MILLIS ? 1 : 3;
        int total = (mines.isEmpty() ? 0 : mineWeight)
                + (factories.isEmpty() ? 0 : 1)
                + (fabricators.isEmpty() ? 0 : mineWeight);
        if (total == 0) {
            result.append("result=no available upgrade");
            return;
        }
        int roll = Utility.getRandomIntInRange(0, total - 1);
        List<UpgradeChoice> selected;
        String category;
        if (!mines.isEmpty() && roll < mineWeight) {
            selected = mines; category = "mine";
        } else {
            if (!mines.isEmpty()) roll -= mineWeight;
            if (!factories.isEmpty() && roll < 1) {
                selected = factories; category = "factory";
            } else {
                selected = fabricators; category = "fabricator";
            }
        }
        // One factory type gets one vote, irrespective of how many copies exist.
        UpgradeChoice choice = selected.get(Utility.getRandomIntInRange(0, selected.size() - 1));
        result.append("category=").append(category).append(' ');
        issueUpgrade(context, choice, result);
    }

    private void runDefenceUpgrades(AiContext context, List<OrderableUnit> units, double credits,
                                    double income, int gameMillis, StringBuilder result) {
        boolean selected = false;
        if (credits > 1500 && AiUnits.count(units, AiUnits.mechMissile()) < 5 && AiUpgrades.roll(0.5f)) {
            selected = issueFirstLevel(context, units, AiUnits.antiAirTurret(), AiUnits.antiAirTurretT2(), result);
        }
        if (!selected) selected = issueFirstLevel(context, units, AiUnits.turretT1(), rollTurretBranch(), result);
        if (!selected) result.append("primary=no available upgrade ");

        // Independent of the primary defence roll: both groups may issue an order this round.
        boolean secondary = false;
        if (credits > 7000 && enemyIntel.hasAntiTurretThreat())
            secondary = issueFirstLevel(context, units, AiUnits.turretT1Lightning(), AiUnits.turretT2Lightning(), result);
        if (!secondary && gameMillis > 20 * MINUTE_MILLIS && credits > 12000
                && (enemyIntel.count(AiUnits.mechMinigun()) >= 2 || enemyIntel.count(AiUnits.mechFlame()) >= 1))
            secondary = issueFirstLevel(context, units, AiUnits.turretT2Gun(), AiUnits.turretT3Gun(), result);
        if (!secondary && credits > 3000 && income > 100
                && AiUpgrades.roll(enemyIntel.hasAntiTurretThreat() ? 0.6f : 0.2f))
            secondary = issueFirstDefault(context, units, UnitTypeEnum.laserDefence, 1, result);
        if (!secondary) result.append("secondary=no available upgrade ");
    }

    private UnitType rollTurretBranch() {
        if (enemyIntel.hasAntiTurretThreat() && AiUpgrades.roll(0.5f)) return AiUnits.turretT1Lightning();
        return AiUpgrades.roll(0.7f) ? AiUnits.turretT2Gun() : AiUnits.turretT2Flame();
    }

    private static int refillAntiNukeAmmo(AiContext context, List<OrderableUnit> units) {
        UnitType type = AiUnits.antiNukeLauncher();
        if (type == null) return 0;
        int queued = 0;
        for (OrderableUnit unit : AiUnits.allOfType(units, type)) {
            AbstractUnitAction action = AiUpgrades.defaultUpgradeAction(unit);
            if (action != null && context.orders().tryUpgradeAction(unit, action)) queued++;
        }
        return queued;
    }

    private static void addLevelChoices(List<UpgradeChoice> choices, List<OrderableUnit> units,
                                        UnitType from, UnitType target) {
        if (from == null || target == null) return;
        for (OrderableUnit unit : AiUnits.allOfType(units, from)) {
            AbstractUnitAction action = AiUpgrades.findUpgradeAction(unit, target);
            if (AiUpgrades.canIssue(unit, action)) {
                choices.add(new UpgradeChoice(unit, action, target));
                break;
            }
        }
    }

    private static void addDefaultChoices(List<UpgradeChoice> choices, List<OrderableUnit> units,
                                          UnitType type, int maxLevel) {
        if (type == null) return;
        for (OrderableUnit unit : AiUnits.allOfType(units, type)) {
            if (unit.getUpgradeLevel() > maxLevel) continue;
            AbstractUnitAction action = defaultUpgradeAction(unit);
            if (AiUpgrades.canIssue(unit, action)) {
                choices.add(new UpgradeChoice(unit, action, null));
                break;
            }
        }
    }

    private static AbstractUnitAction defaultUpgradeAction(OrderableUnit unit) {
        AbstractUnitAction action = unit.validateActionId(unit.cm());
        return AiUpgrades.isUpgradeAction(unit, action) ? action : null;
    }

    private boolean issueFirstLevel(AiContext context, List<OrderableUnit> units,
                                    UnitType from, UnitType target, StringBuilder result) {
        List<UpgradeChoice> choices = new ArrayList<>();
        addLevelChoices(choices, units, from, target);
        return !choices.isEmpty() && issueUpgrade(context, choices.get(0), result);
    }

    private boolean issueFirstDefault(AiContext context, List<OrderableUnit> units,
                                      UnitType type, int maxLevel, StringBuilder result) {
        List<UpgradeChoice> choices = new ArrayList<>();
        addDefaultChoices(choices, units, type, maxLevel);
        return !choices.isEmpty() && issueUpgrade(context, choices.get(0), result);
    }

    private boolean issueUpgrade(AiContext context, UpgradeChoice choice, StringBuilder result) {
        boolean issued = context.orders().tryUpgradeAction(choice.unit, choice.action);
        result.append("unit=").append(choice.unit.objectId).append(" -> ")
                .append(choice.target == null ? "default upgrade" : choice.target.getUnitTypeDescriptionShort())
                .append(issued ? " result=order queued; " : " result=order rejected; ");
        if (issued && choice.unit instanceof FactoryQueueInterface) {
            pendingUpgrades.add(UpgradeTarget.fromChoice(choice));
        }
        return issued;
    }

    private void retryPendingUpgrades(AiContext context, List<OrderableUnit> units, StringBuilder result) {
        for (Iterator<UpgradeTarget> it = pendingUpgrades.iterator(); it.hasNext();) {
            UpgradeTarget pending = it.next();
            OrderableUnit unit = null;
            for (OrderableUnit candidate : units) {
                if (candidate.objectId == pending.unitId) { unit = candidate; break; }
            }
            if (unit == null || unit.isDead || unit.r() != pending.sourceType
                    || unit.getUpgradeLevel() > pending.sourceLevel) {
                result.append("unit=").append(pending.unitId).append(" result=finished or lost; ");
                it.remove();
                continue;
            }
            AbstractUnitAction action = pending.defaultAction
                    ? defaultUpgradeAction(unit) : AiUpgrades.findUpgradeAction(unit, pending.targetType);
            if (action == null) {
                result.append("unit=").append(pending.unitId).append(" result=no upgrade action; ");
                it.remove();
                continue;
            }
            if (unit instanceof FactoryQueueInterface) {
                FactoryQueueInterface queue = (FactoryQueueInterface) unit;
                Projectile active = queue.dw();
                if (active != null && active.j.equals(action.getQueueId()) && active.m >= 0.0f) {
                    result.append("unit=").append(pending.unitId).append(" result=upgrade started; ");
                    it.remove();
                    continue;
                }
                if (queue.a(action.getQueueId(), true) > 0) {
                    result.append("unit=").append(pending.unitId).append(" result=queued, waiting to start; ");
                    continue;
                }
            }
            if (!AiUpgrades.canIssue(unit, action)) {
                result.append("unit=").append(pending.unitId).append(" result=not upgradeable, skipped; ");
                it.remove();
                continue;
            }
            result.append("unit=").append(pending.unitId)
                    .append(context.orders().tryUpgradeAction(unit, action)
                            ? " result=order retried; " : " result=retry rejected; ");
        }
    }

    private static final class UpgradeChoice {
        final OrderableUnit unit;
        final AbstractUnitAction action;
        final UnitType target;
        UpgradeChoice(OrderableUnit unit, AbstractUnitAction action, UnitType target) {
            this.unit = unit; this.action = action; this.target = target;
        }
    }

    private static final class UpgradeTarget {
        final long unitId;
        final UnitType sourceType;
        final int sourceLevel;
        final UnitType targetType;
        final boolean defaultAction;

        private UpgradeTarget(OrderableUnit unit, UnitType targetType) {
            this.unitId = unit.objectId;
            this.sourceType = unit.r();
            this.sourceLevel = unit.getUpgradeLevel();
            this.targetType = targetType;
            this.defaultAction = targetType == null;
        }

        static UpgradeTarget fromChoice(UpgradeChoice choice) {
            return new UpgradeTarget(choice.unit, choice.target);
        }

        void write(DataOutputStream output) throws IOException {
            output.writeLong(unitId);
            output.writeUTF(sourceType.getUnitTypeDescriptionShort());
            output.writeInt(sourceLevel);
            output.writeBoolean(defaultAction);
            if (!defaultAction) output.writeUTF(targetType.getUnitTypeDescriptionShort());
        }

        static UpgradeTarget read(DataInputStream input) throws IOException {
            long id = input.readLong();
            UnitType source = AiUnits.resolve(input.readUTF());
            int level = input.readInt();
            boolean isDefault = input.readBoolean();
            UnitType target = isDefault ? null : AiUnits.resolve(input.readUTF());
            if (source == null || (!isDefault && target == null)) return null;
            return new UpgradeTarget(id, source, level, target, isDefault);
        }

        private UpgradeTarget(long id, UnitType source, int level, UnitType target, boolean isDefault) {
            unitId = id;
            sourceType = source;
            sourceLevel = level;
            targetType = target;
            defaultAction = isDefault;
        }
    }

    @Override
    public void writeState(DataOutputStream output) throws IOException {
        output.writeInt(POLICY_STATE_VERSION);
        output.writeByte(macroStrategy == null ? -1 : macroStrategy.ordinal());
        enemyIntel.write(output);
        output.writeInt(0); // Retain the two legacy ore-count slots for save compatibility.
        output.writeInt(0);
        output.writeByte(landMode.ordinal());
        output.writeInt(lastLandModeRefreshMillis);
        output.writeInt(pendingUpgrades.size());
        for (UpgradeTarget pending : pendingUpgrades) pending.write(output);
        productionPolicy.writeState(output);
    }

    @Override
    public void readState(DataInputStream input) throws IOException {
        if (input.available() == 0) return;
        int version = input.readInt();
        if (version < 1 || version > POLICY_STATE_VERSION) throw new IOException("Unsupported AI_S policy state");
        int macroOrdinal = input.readByte();
        if (macroOrdinal >= 0 && macroOrdinal < MacroStrategy.values().length) {
            macroStrategy = MacroStrategy.values()[macroOrdinal];
        }
        enemyIntel.read(input);
        input.readInt(); // Legacy ore-count fields.
        input.readInt();
        if (version >= 2) {
            int mode = input.readByte();
            if (mode >= 0 && mode < LandMode.values().length) landMode = LandMode.values()[mode];
            lastLandModeRefreshMillis = input.readInt();
            pendingUpgrades.clear();
            if (version == 2) {
                if (input.readBoolean()) {
                    UpgradeTarget target = UpgradeTarget.read(input);
                    if (target != null) pendingUpgrades.add(target);
                }
            } else {
                int count = input.readInt();
                if (count < 0 || count > 1000) throw new IOException("Invalid AI_S pending upgrade count");
                for (int i = 0; i < count; i++) {
                    UpgradeTarget target = UpgradeTarget.read(input);
                    if (target != null) pendingUpgrades.add(target);
                }
            }
            productionPolicy.readState(input);
        }
    }
}
