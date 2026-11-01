package com.corrodinggames.rts.game.ai.custom.ai_s;

import com.corrodinggames.rts.game.ai.BaseZone;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.ai.custom.util.AiPlacement;
import com.corrodinggames.rts.game.ai.custom.util.AiUnits;
import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.UnitCommandType;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.buildings.CommandCenter;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.Utility;
import com.corrodinggames.rts.gameFramework.utility.GameViewUtils;
import io.github.rwx.geometry.PointF;
import io.github.rwx.geometry.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Building selection delegates to BaseZone; only macro filters and defensive siting live here. */
final class AiSBuildingPolicy {
    private static final int RECENT_ATTACK_MILLIS = 5 * 60_000;
    private static final int FACTORY_CAP_CHECK_MILLIS = 5 * 60_000;
    private static final int DEFENCE_BUILD_INTERVAL_MILLIS = 60_000;
    private static final float COMMAND_CENTER_COVERAGE_RADIUS = 280f;
    private final Map<Integer, List<RecentBuilding>> recentByZone = new HashMap<>();
    private final Map<Integer, Map<Long, Float>> buildingHealthByZone = new HashMap<>();
    private FactoryCaps globalFactoryCaps;
    private final List<PendingBuild> pendingBuilds = new ArrayList<>();
    /** Once-per-minute land routes from every own command centre to every reachable hostile one. */
    private final Map<Long, List<Point>> commandCenterRoutes = new HashMap<>();
    private int lastDefenceBuildMillis = -DEFENCE_BUILD_INTERVAL_MILLIS;

    String update(AiContext context, boolean airMacro, CustomAiSStrategy.LandMode landMode) {
        List<OrderableUnit> units = context.ownUnits();
        StringBuilder cycle = new StringBuilder();
        int now = GameEngine.getInstance().gameTimeMillis;
        reconcilePendingBuilds(units, now);
        boolean runDefencePass = now - lastDefenceBuildMillis >= DEFENCE_BUILD_INTERVAL_MILLIS;
        if (runDefencePass) {
            lastDefenceBuildMillis = now;
            updateCommandCenterRoutes(context, units);
        }
        // Land macro may build one air factory for transport/limited air support. Reserve a
        // selection immediately because this callback visits several BaseZones per update.
        boolean landAirFactoryReserved = !airMacro
                && AiUnits.count(units, UnitTypeEnum.airFactory) + pendingCount(null, UnitTypeEnum.airFactory) >= 1;
        int levelOneLandFactories = countLevelOneFactories(units, UnitTypeEnum.landFactory)
                + pendingCount(null, UnitTypeEnum.landFactory);
        int levelOneAirFactories = countLevelOneFactories(units, UnitTypeEnum.airFactory)
                + pendingCount(null, UnitTypeEnum.airFactory);
        boolean hasT2Factory = airMacro
                ? countUpgradedFactories(units, UnitTypeEnum.airFactory) > 0
                : countUpgradedFactories(units, UnitTypeEnum.landFactory) > 0
                        || countCompletedFactories(units, AiUnits.mechFactoryT2()) > 0;
        FactoryCaps caps = factoryCaps(context.team().getScaledIncomeRate(), now, airMacro);
        for (BaseZone base : context.originalBases()) {
            if (base == null || base.isDestroyed || base.getStage() == null) continue;
            List<RecentBuilding> recent = updateRecentAttacks(base, units, now);
            final boolean airFactoryBlockedForLand = landAirFactoryReserved;
            final boolean landFactoryGlobalCapReached = levelOneLandFactories >= 4;
            final boolean airFactoryGlobalCapReached = levelOneAirFactories >= 3;
            UnitType selected = base.getBestBuildingToBuild((type, score) -> adjustedScore(type, score, airMacro,
                    airFactoryBlockedForLand, landFactoryGlobalCapReached, airFactoryGlobalCapReached,
                    base, units, caps, hasT2Factory, landMode));
            // A successful mine/factory order must not starve the once-per-minute defensive pass.
            if (selected != null) {
                PendingBuild issued = trySelectedBuilding(context, base, units, selected, now, hasT2Factory);
                cycle.append(" zone=").append(base.strategyId).append(" selected=")
                        .append(selected.getUnitTypeDescriptionShort()).append(" result=")
                        .append(issued != null ? "order queued" : "failed or unaffordable").append(';');
                if (issued != null) {
                    pendingBuilds.add(issued);
                    if (!airMacro && selected == UnitTypeEnum.airFactory) landAirFactoryReserved = true;
                    if (selected == UnitTypeEnum.landFactory) levelOneLandFactories++;
                    if (selected == UnitTypeEnum.airFactory) levelOneAirFactories++;
                }
            } else {
                cycle.append(" zone=").append(base.strategyId)
                        .append(" selected=none result=no building candidate;");
            }
            if (runDefencePass) {
                boolean gun = tryDefensiveBuild(context, base, units, recent, AiUnits.turretT1(), false);
                boolean antiAir = tryDefensiveBuild(context, base, units, recent, AiUnits.antiAirTurret(), true);
                cycle.append(" zone=").append(base.strategyId).append(" defence=gun:")
                        .append(gun ? "queued" : "none").append(",antiAir:")
                        .append(antiAir ? "queued" : "none").append(';');
            }
        }
        return cycle.length() == 0 ? "selected=none result=no eligible base" : cycle.toString();
    }

    private static PendingBuild trySelectedBuilding(AiContext context, BaseZone base,
                                                    List<OrderableUnit> units, UnitType type, int now,
                                                    boolean hasT2Factory) {
        if (type == UnitTypeEnum.seaFactory && !hasT2Factory) return null;
        boolean laser = AiUnits.isAnyOf(type, UnitTypeEnum.laserDefence, AiUnits.laserDefence());
        int attempts = laser ? 20 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            PointF spot = laser ? base.getRandomPointInside()
                    : type.p() ? base.findClosestResource() : base.findBuildLocation(type);
            if (spot == null || !context.canPlaceBuilding(type, spot.x, spot.y)) continue;
            if (laser && !validLaserCoverage(units, spot, coverageRange(type))) continue;
            if (type == UnitTypeEnum.seaFactory
                    && GameViewUtils.c(spot.x, spot.y, UnitMovementType.WATER) < 3000) continue;
            OrderableUnit builder = base.findBuilderForBuilding(type, spot.x, spot.y);
            if (builder != null && context.orders().tryBuildBuilding(type, spot.x, spot.y, builder)) {
                return new PendingBuild(type, builder.objectId, spot.x, spot.y, now);
            }
        }
        return null;
    }

    private static boolean validLaserCoverage(List<OrderableUnit> units, PointF spot, float range) {
        int ores = 0, towers = 0, lasers = 0;
        HashSet<UnitType> towerTypes = new HashSet<>();
        for (OrderableUnit unit : units) {
            if (!inside(unit.posX, unit.posY, spot, range)) continue;
            if (AiPlacement.isOreBuilding(unit)) ores++;
            if (AiPlacement.isDefenceTower(unit)) { towers++; towerTypes.add(unit.r()); }
            if (AiPlacement.isLaserDefence(unit)) lasers++;
        }
        return ores >= 1 && lasers <= 3 && (towerTypes.size() >= 3 || towers >= 5);
    }

    private float adjustedScore(UnitType type, float score, boolean airMacro, boolean landAirFactoryReserved,
                                       boolean landFactoryGlobalCapReached, boolean airFactoryGlobalCapReached,
                                       BaseZone base, List<OrderableUnit> units, FactoryCaps caps,
                                       boolean hasT2Factory,
                                       CustomAiSStrategy.LandMode landMode) {
        if (isTurret(type)) return -2f; // run the prompt's dedicated placement logic below.
        if (airMacro && AiUnits.isAnyOf(type, UnitTypeEnum.landFactory, AiUnits.mechFactory())) return -2f;
        if (!airMacro && landMode == CustomAiSStrategy.LandMode.X && type == AiUnits.mechFactory()) return -2f;
        if (!airMacro && landMode == CustomAiSStrategy.LandMode.Y && type == UnitTypeEnum.landFactory) return -2f;
        if (!airMacro && type == UnitTypeEnum.airFactory && landAirFactoryReserved) return -2f;
        if (type == UnitTypeEnum.landFactory && landFactoryGlobalCapReached) return -2f;
        if (type == UnitTypeEnum.airFactory && airFactoryGlobalCapReached) return -2f;
        if (pendingCount(base, type) > 0) return -2f;
        // This is deliberately a per-BaseZone T1 cap. Upgraded factories keep their normal
        // macro cap, while a zone cannot keep filling itself with fresh level-one factories.
        if (type == UnitTypeEnum.landFactory && countLevelOneFactories(base, units, type) + pendingCount(base, type) >= 2) return -2f;
        if (type == UnitTypeEnum.airFactory && countLevelOneFactories(base, units, type) + pendingCount(base, type) >= 2) return -2f;
        if (type == AiUnits.mechFactory() && countLevelOneFactories(base, units, type) + pendingCount(base, type) >= 2) return -2f;
        if (type == UnitTypeEnum.seaFactory && !hasT2Factory) return -2f;
        if (type == UnitTypeEnum.landFactory) {
            return countFactories(units, type) + pendingCount(null, type) < caps.landFactory
                    ? Math.max(score, 0.30f) : -2f;
        }
        if (type == AiUnits.mechFactory()) {
            return countMechFactories(units) + pendingCount(null, type) < caps.mechFactory ? Math.max(score, 0.30f) : -2f;
        }
        if (type == UnitTypeEnum.airFactory) {
            return countFactories(units, type) + pendingCount(null, type) < caps.airFactory
                    ? Math.max(score, 0.30f) : -2f;
        }
        if (type == UnitTypeEnum.seaFactory) {
            return countFactories(units, type) + pendingCount(null, type) < caps.seaFactory
                    ? Math.max(score, 0.20f) : -2f;
        }
        return score;
    }

    private static int countLevelOneFactories(BaseZone base, List<OrderableUnit> units, UnitType type) {
        int result = 0;
        for (OrderableUnit unit : units) {
            if (unit.r() == type && unit.getUpgradeLevel() == 1
                    && inside(unit.posX, unit.posY, base.posX, base.posY, base.radius)) result++;
        }
        return result;
    }

    private static int countLevelOneFactories(List<OrderableUnit> units, UnitType type) {
        int result = 0;
        for (OrderableUnit unit : units) {
            if (unit.r() == type && unit.getUpgradeLevel() == 1) result++;
        }
        return result;
    }

    private static int countFactories(List<OrderableUnit> units, UnitType type) {
        int result = 0;
        for (OrderableUnit unit : units) {
            if (unit.r() == type) result++;
        }
        return result;
    }

    private int pendingCount(BaseZone base, UnitType type) {
        int result = 0;
        for (PendingBuild pending : pendingBuilds) {
            if (pending.type == type && (base == null
                    || inside(pending.x, pending.y, base.posX, base.posY, base.radius))) result++;
        }
        return result;
    }

    private void reconcilePendingBuilds(List<OrderableUnit> units, int now) {
        for (Iterator<PendingBuild> it = pendingBuilds.iterator(); it.hasNext();) {
            PendingBuild pending = it.next();
            boolean placed = false;
            OrderableUnit builder = null;
            for (OrderableUnit unit : units) {
                if (unit.r() == pending.type && inside(unit.posX, unit.posY, pending.x, pending.y, 32f)) {
                    placed = true;
                    break;
                }
                if (unit.objectId == pending.builderId) builder = unit;
            }
            if (placed || builder == null || builder.isDead) {
                it.remove();
                continue;
            }
            UnitCommand order = builder.getLastWaypoint();
            boolean stillBuilding = order != null && order.getCommandType() == UnitCommandType.build
                    && order.getBuildUnitType() == pending.type
                    && inside(order.getTargetX(), order.getTargetY(), pending.x, pending.y, 8f);
            if (!stillBuilding && now - pending.issuedAt > 3_000) it.remove();
        }
        // Strategy state is recreated after loading a save, but builders keep their commands.
        // Recover those orders before evaluating any global or per-zone factory cap.
        for (OrderableUnit builder : units) {
            rememberBuildWaypoint(units, builder, builder.getCurrentWaypoint(), now);
            rememberBuildWaypoint(units, builder, builder.getLastWaypoint(), now);
        }
    }

    private void rememberBuildWaypoint(List<OrderableUnit> units, OrderableUnit builder,
                                       UnitCommand order, int now) {
        if (order == null || order.getCommandType() != UnitCommandType.build) return;
        UnitType type = order.getBuildUnitType();
        if (type == null) return;
        float x = order.getTargetX(), y = order.getTargetY();
        for (OrderableUnit unit : units) {
            if (unit.r() == type && inside(unit.posX, unit.posY, x, y, 32f)) return;
        }
        for (PendingBuild pending : pendingBuilds) {
            if (pending.builderId == builder.objectId && pending.type == type
                    && inside(pending.x, pending.y, x, y, 8f)) return;
        }
        pendingBuilds.add(new PendingBuild(type, builder.objectId, x, y, now));
    }

    private FactoryCaps factoryCaps(double income, int now, boolean airMacro) {
        if (globalFactoryCaps == null || globalFactoryCaps.airMacro != airMacro) {
            globalFactoryCaps = new FactoryCaps(now, airMacro);
            return globalFactoryCaps;
        }
        if (now - globalFactoryCaps.checkedAt < FACTORY_CAP_CHECK_MILLIS) return globalFactoryCaps;
        FactoryCaps caps = new FactoryCaps(now, airMacro);
        if (!airMacro) {
            if (income > 200d) {
                caps.landFactory = caps.mechFactory = Math.max((int) (income / 60d), 4);
            } else if (income > 150d) {
                caps.landFactory = caps.mechFactory = 3;
            } else if (income > 140d) {
                caps.landFactory = 2;
                caps.mechFactory = 3;
            } else if (income > 100d) {
                caps.landFactory = caps.mechFactory = 2;
            }
        } else if (Utility.getRandomIntInRange(1, 100) <= 80) {
            caps.airFactory = income < 100d ? 2
                    : income > 150d ? Math.max((int) (income / 40d), 5) : 3;
        } else {
            caps.airFactory = 2;
        }
        if (Utility.getRandomIntInRange(1, 100) <= 50) {
            caps.seaFactory = Math.max(1, Math.min((int) Math.ceil(income / 70d), 5));
        }
        globalFactoryCaps = caps;
        return caps;
    }

    private static int countMechFactories(List<OrderableUnit> units) {
        return countFactories(units, AiUnits.mechFactory())
                + countFactories(units, AiUnits.mechFactoryT2());
    }

    private static int countUpgradedFactories(List<OrderableUnit> units, UnitType type) {
        int count = 0;
        for (OrderableUnit unit : units) {
            if (unit.r() == type && unit.buildProgress >= 1.0f && unit.getUpgradeLevel() >= 2) count++;
        }
        return count;
    }

    private static int countCompletedFactories(List<OrderableUnit> units, UnitType type) {
        int count = 0;
        for (OrderableUnit unit : units) {
            if (unit.r() == type && unit.buildProgress >= 1.0f) count++;
        }
        return count;
    }

    private boolean tryDefensiveBuild(AiContext context, BaseZone base, List<OrderableUnit> units,
                                      List<RecentBuilding> recent, UnitType type, boolean antiAir) {
        if (type == null || !base.canBuild(type) || Utility.getRandomIntInRange(0, 1) == 0) return false;
        int baseCap = antiAir ? 3 : 2;
        if (base.countUnitsOfType(type) >= baseCap) return false;
        for (int attempt = 0; attempt < 5; attempt++) {
            PointF spot = base.getRandomPointInside();
            if (!context.canPlaceBuilding(type, spot.x, spot.y)) continue;
            if (!antiAir && isInCommandCenterCoverage(units, spot)) continue;
            float range = coverageRange(type);
            if (antiAir) {
                if (AiPlacement.countWithin(units, spot.x, spot.y, range, AiPlacement::isAntiAirTurret) >= 5) continue;
                if (!hasProtectedBuilding(units, recent, spot, range, true, base)) continue;
            } else if (!hasProtectedBuilding(units, recent, spot, range, false, base)) {
                continue;
            }
            OrderableUnit builder = base.findBuilderForBuilding(type, spot.x, spot.y);
            if (builder != null && context.orders().tryBuildBuilding(type, spot.x, spot.y, builder)) return true;
        }
        return false;
    }

    private static boolean isInCommandCenterCoverage(List<OrderableUnit> units, PointF spot) {
        for (OrderableUnit unit : units) {
            if (unit instanceof CommandCenter && inside(unit.posX, unit.posY, spot, COMMAND_CENTER_COVERAGE_RADIUS)) return true;
        }
        return false;
    }

    /** U in the policy: retain one shortest land route for each connected command-centre pair. */
    private void updateCommandCenterRoutes(AiContext context, List<OrderableUnit> units) {
        commandCenterRoutes.clear();
        AiMapGraph graph = AiMapGraph.of(UnitMovementType.LAND);
        if (graph == null) return;
        for (OrderableUnit own : units) {
            if (!(own instanceof CommandCenter)) continue;
            AiMapGraph.DistanceField field = graph.distanceFieldFrom(own.posX, own.posY);
            for (BaseUnit enemy : BaseUnit.bE) {
                if (!(enemy instanceof CommandCenter) || enemy.isDead || enemy.team == null
                        || !context.team().c(enemy.team)
                        || !context.team().isPathPossibleBetweenPoints(own.posX, own.posY,
                        enemy.posX, enemy.posY, UnitMovementType.LAND)) continue;
                long routeKey = own.objectId ^ Long.rotateLeft(enemy.objectId, 32);
                commandCenterRoutes.put(routeKey, field.pathTo(
                        AiMapGraph.worldToTileX(enemy.posX), AiMapGraph.worldToTileY(enemy.posY)));
            }
        }
    }

    private static boolean hasProtectedBuilding(List<OrderableUnit> units, List<RecentBuilding> recent,
                                                PointF spot, float range, boolean antiAir, BaseZone base) {
        for (RecentBuilding entry : recent) {
            if (inside(entry.x, entry.y, spot, range) && (antiAir || wideAngleFromCommand(base, spot, entry.x, entry.y))) return true;
        }
        for (OrderableUnit unit : units) {
            if (!inside(unit.posX, unit.posY, spot, range)) continue;
            boolean ordinaryTarget = AiPlacement.isOreBuilding(unit) || isProductionBuilding(unit)
                    || (antiAir ? AiPlacement.isGunTurret(unit) : AiPlacement.isDefenceTower(unit));
            if (ordinaryTarget) return true;
        }
        return false;
    }

    private static boolean wideAngleFromCommand(BaseZone base, PointF target, float x, float y) {
        double a = Math.atan2(target.y - base.posY, target.x - base.posX);
        double b = Math.atan2(y - target.y, x - target.x);
        double d = Math.abs(a - b); if (d > Math.PI) d = 2 * Math.PI - d;
        return Math.toDegrees(d) > 50d;
    }

    private List<RecentBuilding> updateRecentAttacks(BaseZone base, List<OrderableUnit> units, int now) {
        List<RecentBuilding> entries = recentByZone.computeIfAbsent(base.strategyId, ignored -> new ArrayList<>());
        for (Iterator<RecentBuilding> it = entries.iterator(); it.hasNext();) if (now - it.next().time > RECENT_ATTACK_MILLIS) it.remove();
        Map<Long, Float> previousHealth = buildingHealthByZone.computeIfAbsent(base.strategyId, ignored -> new HashMap<>());
        HashSet<Long> seen = new HashSet<>();
        for (OrderableUnit unit : units) {
            if (!unit.bI() || unit.isDead
                    || !inside(unit.posX, unit.posY, base.posX, base.posY, base.radius)) continue;
            seen.add(unit.objectId);
            Float previous = previousHealth.put(unit.objectId, unit.currentHealth);
            if (previous != null && unit.currentHealth < previous) {
                entries.add(new RecentBuilding(unit.posX, unit.posY, now));
            }
        }
        previousHealth.keySet().retainAll(seen);
        return entries;
    }

    private static boolean isTurret(UnitType type) { return AiUnits.isAnyOf(type, AiUnits.turretT1(), UnitTypeEnum.turret, AiUnits.antiAirTurret(), UnitTypeEnum.antiAirTurret); }
    private static boolean isProductionBuilding(OrderableUnit unit) { return unit.bI() && unit instanceof FactoryQueueInterface; }
    /**
     * Do not call UnitType.a() here. It creates a live BaseUnit; probing a range during an AI
     * decision therefore leaked a unit without a team into the simulation and crashed fog updates.
     * These are only siting radii, so use stable engine values without constructing anything.
     */
    private static float coverageRange(UnitType type) {
        if (AiUnits.isAnyOf(type, UnitTypeEnum.laserDefence, AiUnits.laserDefence())) return 160f;
        if (AiUnits.isAnyOf(type, UnitTypeEnum.antiAirTurret, AiUnits.antiAirTurret())) return 250f;
        return 165f; // T1 gun turret's normal attack radius; custom variants use the same placement envelope.
    }
    private static boolean inside(float x, float y, PointF p, float range) { return inside(x, y, p.x, p.y, range); }
    private static boolean inside(float x, float y, float cx, float cy, float range) { float dx=x-cx,dy=y-cy; return dx*dx+dy*dy<=range*range; }
    private static final class RecentBuilding { final float x,y; final int time; RecentBuilding(float x,float y,int time){this.x=x;this.y=y;this.time=time;} }
    private static final class PendingBuild {
        final UnitType type;
        final long builderId;
        final float x, y;
        final int issuedAt;
        PendingBuild(UnitType type, long builderId, float x, float y, int issuedAt) {
            this.type = type;
            this.builderId = builderId;
            this.x = x;
            this.y = y;
            this.issuedAt = issuedAt;
        }
    }
    private static final class FactoryCaps {
        final int checkedAt;
        final boolean airMacro;
        int landFactory = 1, mechFactory = 2, airFactory = 3, seaFactory = 1;
        FactoryCaps(int checkedAt, boolean airMacro) {
            this.checkedAt = checkedAt;
            this.airMacro = airMacro;
        }
    }
}
