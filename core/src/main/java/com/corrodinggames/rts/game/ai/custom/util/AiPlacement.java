package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.map.MapTile;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.buildings.CommandCenter;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.geometry.PointF;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 建筑放置与"覆盖范围"工具。
 *
 * <p>引擎里**没有**任何"覆盖范围"机制，能用到的半径只有两种语义：
 * <ul>
 *   <li>{@code unit.m()} = 攻击距离（炮台/防空/激光防御的"火力圈"，UI 画的也是它）；</li>
 *   <li>{@code unit.y()} = 纳米距离（建造/维修/回收作用距离，修复湾是 230）。</li>
 * </ul>
 * 所以"矿覆盖范围""指令中心覆盖范围"都是本类用半径近似出来的，半径取 {@link #coverageRadius}。
 */
public final class AiPlacement {
    /** "矿覆盖范围"的近似半径：提取器/资源池周围这个距离内算同一个矿。 */
    public static final float ORE_COVERAGE_RADIUS = 200f;
    /** "野生矿"判定：距离我方指挥中心超过这个距离（D9 确认值 = 50 格 = 1000 世界单位）。 */
    public static final float WILD_ORE_MIN_COMMAND_CENTER_DISTANCE = 1000f;

    private AiPlacement() { }

    /** 建筑的覆盖半径：攻击距离与纳米距离取大者（修复湾 230、指挥中心 280、炮台 165…）。 */
    public static float coverageRadius(OrderableUnit unit) {
        if (unit == null) return 0f;
        return Math.max(unit.m(), unit.y());
    }

    /** 是不是"矿/资源点建筑"（提取器一类，unitType.p()）。 */
    public static boolean isOreBuilding(OrderableUnit unit) {
        return unit != null && unit.r() != null && unit.r().p();
    }

    /** 是不是普通炮台（同时接受 ini 覆盖版 c_turret_* 与 Java 内置 turret）。 */
    public static boolean isGunTurret(OrderableUnit unit) {
        if (unit == null || unit.r() == null) return false;
        return AiUnits.isAnyOf(unit.r(), AiUnits.turretT1(), AiUnits.turretT2Gun(), AiUnits.turretT2Flame(),
                AiUnits.turretT1Lightning(), AiUnits.turretT2Lightning(),
                com.corrodinggames.rts.game.units.UnitTypeEnum.turret);
    }

    /** 是不是防空炮台。 */
    public static boolean isAntiAirTurret(OrderableUnit unit) {
        if (unit == null || unit.r() == null) return false;
        return AiUnits.isAnyOf(unit.r(), AiUnits.antiAirTurret(), AiUnits.antiAirTurretT2(),
                com.corrodinggames.rts.game.units.UnitTypeEnum.antiAirTurret);
    }

    /** 是不是极光防御（激光防御塔）。 */
    public static boolean isLaserDefence(OrderableUnit unit) {
        if (unit == null || unit.r() == null) return false;
        return AiUnits.isAnyOf(unit.r(), AiUnits.laserDefence(),
                com.corrodinggames.rts.game.units.UnitTypeEnum.laserDefence);
    }

    /** 是不是防御塔（有攻击距离的建筑，排除指挥中心）。 */
    public static boolean isDefenceTower(OrderableUnit unit) {
        return unit != null && unit.bI() && unit.canAttack() && unit.m() > 0
                && !(unit instanceof CommandCenter);
    }

    /** 我方单位里，落在 (x,y) 半径 r 内且满足条件的数量。 */
    public static int countWithin(List<OrderableUnit> units, float x, float y, float r,
                                  Predicate<OrderableUnit> filter) {
        if (units == null) return 0;
        float limit = r * r;
        int total = 0;
        for (OrderableUnit unit : units) {
            if (unit == null || unit.isDead) continue;
            if (filter != null && !filter.test(unit)) continue;
            float dx = unit.posX - x;
            float dy = unit.posY - y;
            if ((dx * dx) + (dy * dy) <= limit) total++;
        }
        return total;
    }

    /** 我方单位里距离 (x,y) 最近的一个（可带过滤），没有则 null。 */
    public static OrderableUnit nearestWithin(List<OrderableUnit> units, float x, float y, float r,
                                              Predicate<OrderableUnit> filter) {
        if (units == null) return null;
        float limit = r * r;
        float best = Float.MAX_VALUE;
        OrderableUnit result = null;
        for (OrderableUnit unit : units) {
            if (unit == null || unit.isDead) continue;
            if (filter != null && !filter.test(unit)) continue;
            float dx = unit.posX - x;
            float dy = unit.posY - y;
            float distance = (dx * dx) + (dy * dy);
            if (distance <= limit && distance < best) {
                best = distance;
                result = unit;
            }
        }
        return result;
    }

    /** 我方生产建筑（指挥中心或任何带队列的工厂）数量，用于"野生矿周围没有我方生产建筑"。 */
    public static int countProductionBuildingsWithin(List<OrderableUnit> units, float x, float y, float r) {
        return countWithin(units, x, y, r,
                unit -> unit.bI() && (unit instanceof CommandCenter || unit instanceof FactoryQueueInterface));
    }

    /** 到最近我方指挥中心的距离（世界单位）；没有指挥中心时返回 -1。 */
    public static float distanceToNearestCommandCenter(List<OrderableUnit> units, float x, float y) {
        if (units == null) return -1f;
        float best = Float.MAX_VALUE;
        for (OrderableUnit unit : units) {
            if (unit == null || unit.isDead || !(unit instanceof CommandCenter)) continue;
            float dx = unit.posX - x;
            float dy = unit.posY - y;
            best = Math.min(best, (float) Math.sqrt((dx * dx) + (dy * dy)));
        }
        return best == Float.MAX_VALUE ? -1f : best;
    }

    /** 该世界点所在的格是不是资源池。 */
    public static boolean isResourcePool(float worldX, float worldY) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.tileMap == null) return false;
        MapTile tile = engine.tileMap.getPathingOverrideTileAt(
                AiMapGraph.worldToTileX(worldX), AiMapGraph.worldToTileY(worldY));
        return tile != null && tile.isResourcePool;
    }

    /** 全部资源池锚点（世界坐标，可安全持有）。 */
    public static List<PointF> oreAnchors() {
        return AiMapGraph.resourcePoolAnchors();
    }

    /**
     * 该点是否位于 (fromX,fromY) 的"背离敌人"方向 45 度扇区内，且距离在 [min,max]。
     * 用于"在炮台后方 45 度幅度内建造"。
     */
    public static boolean isBehindWithinCone(float x, float y, float fromX, float fromY,
                                             float enemyX, float enemyY,
                                             float minDistance, float maxDistance, float halfAngleDegrees) {
        float dx = x - fromX;
        float dy = y - fromY;
        float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
        if (distance < minDistance || distance > maxDistance) return false;
        double awayAngle = Math.atan2(fromY - enemyY, fromX - enemyX);
        double pointAngle = Math.atan2(dy, dx);
        double delta = pointAngle - awayAngle;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta < -Math.PI) delta += Math.PI * 2;
        return Math.abs(delta) <= Math.toRadians(halfAngleDegrees);
    }

    /** 我方某个资源池锚点上的提取器（用于"这个矿是不是我的"）。 */
    public static OrderableUnit ownExtractorAt(List<OrderableUnit> units, float x, float y) {
        return nearestWithin(units, x, y, 24f, unit -> unit.r() != null && unit.r().p());
    }

    /**
     * 收集"可用的我方矿脉"：我方有提取器、敌方从陆地走得到（可能被抢），**而且我方自己走得到**。
     * 最后一条很关键：原版 findClosestResource 只按距离挑矿，会把建造者派到水对岸的矿脉上，
     * 表现就是"建造者跑到海岸线干等"。
     */
    public static List<PointF> contestedOres(List<OrderableUnit> units, AiTerritory territory) {
        List<PointF> result = new ArrayList<>();
        if (territory == null) return result;
        for (PointF ore : oreAnchors()) {
            if (ownExtractorAt(units, ore.x, ore.y) == null) continue;
            if (!territory.reachableForUs(ore.x, ore.y)) continue;
            if (!territory.reachableForEnemy(ore.x, ore.y)) continue;
            result.add(ore);
        }
        return result;
    }

    /** 我方所有建筑（含建筑类单位），供覆盖判定使用。 */
    public static List<OrderableUnit> ownBuildings(List<OrderableUnit> units) {
        List<OrderableUnit> result = new ArrayList<>();
        if (units == null) return result;
        for (OrderableUnit unit : units) {
            if (unit == null || unit.isDead) continue;
            if (unit.bI()) result.add(unit);
        }
        return result;
    }

    /** 距离该点最近的敌方单位（用全图单位表，不做迷雾过滤），没有则 null。 */
    public static BaseUnit nearestEnemy(float x, float y, com.corrodinggames.rts.game.PlayerTeam team) {
        BaseUnit best = null;
        float bestDistance = Float.MAX_VALUE;
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit == null || unit.isDead || unit.team == null || !unit.team.c(team)) continue;
            float dx = unit.posX - x;
            float dy = unit.posY - y;
            float distance = (dx * dx) + (dy * dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = unit;
            }
        }
        return best;
    }
}
