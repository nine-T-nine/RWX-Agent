package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.buildings.CommandCenter;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.geometry.Point;
import io.github.rwx.geometry.PointF;

import java.util.ArrayList;
import java.util.List;

/**
 * 我方阵营范围与前线判定：以「我方指挥中心 ↔ 最近敌方指挥中心」为两个锚点，
 * 在 {@link AiMapGraph} 的陆地图上各做一张 BFS 距离场。
 *
 * <p>引擎里没有任何领土/控制区系统（代价图也完全不按队伍区分），所以这里的定义是：
 * <ul>
 *   <li><b>我方一侧</b>：我方指挥中心能走到，且到我们的步数 ≤ 到敌方的步数（敌方走不到也算我方一侧）。</li>
 *   <li><b>前线</b>：双方都能走到，且两侧步数差在给定带宽内。</li>
 *   <li><b>通往敌方的路线</b>：最短路上的 tile 序列，路上贴着液体（河/海/岩浆河）的格子就是渡口。</li>
 * </ul>
 *
 * <p>注意：锚点取的是全图 {@code BaseUnit.bE} 里的指挥中心，不受迷雾限制——这一点与原版
 * {@code AIController.isSafePointForOutpost} 的取法一致（那里面也直接读敌方指挥中心坐标）。
 * 需要"公平"版本时请自行用 {@code BaseUnit.d(team)} 过滤。
 *
 * <p>距离场是 O(tile 数) 的 BFS，只在锚点变化时重算；整局缓存。
 */
public final class AiTerritory {
    /** 单个槽位缓存：同一队伍的锚点没变就直接复用。 */
    private static AiTerritory cached;

    private final PlayerTeam team;
    private final AiMapGraph graph;
    private final BaseUnit ourCenter;
    private final BaseUnit enemyCenter;
    private final AiMapGraph.DistanceField fromUs;
    private final AiMapGraph.DistanceField fromEnemy;
    private final float ourX;
    private final float ourY;
    private final float enemyX;
    private final float enemyY;

    private List<Point> route;
    private List<PointF> crossings;
    /** 我方最外侧防御线到敌方的步数（格）；-1 = 还没有防御线。 */
    private int frontlineEnemyDistance = -1;
    /** 上面的值是否来自真正的防御建筑（false = 退回任意建筑或双方等值带）。 */
    private boolean frontlineFromDefence;

    private AiTerritory(PlayerTeam team, AiMapGraph graph, BaseUnit ourCenter, BaseUnit enemyCenter,
                        AiMapGraph.DistanceField fromUs, AiMapGraph.DistanceField fromEnemy,
                        float ourX, float ourY, float enemyX, float enemyY) {
        this.team = team;
        this.graph = graph;
        this.ourCenter = ourCenter;
        this.enemyCenter = enemyCenter;
        this.fromUs = fromUs;
        this.fromEnemy = fromEnemy;
        this.ourX = ourX;
        this.ourY = ourY;
        this.enemyX = enemyX;
        this.enemyY = enemyY;
    }

    /**
     * 取该队伍的阵营/前线视图；没有地图时返回 null。
     * 锚点（我方指挥中心、最近敌方指挥中心）发生变化时会自动重算；
     * 前线（我方最外侧防御线）每次调用都会刷新（只是一次线性扫描，不做 BFS）。
     */
    public static AiTerritory of(PlayerTeam team) {
        if (team == null) return null;
        AiMapGraph graph = AiMapGraph.of(UnitMovementType.LAND);
        if (graph == null) return null;
        BaseUnit ourCenter = findOurAnchor(team);
        if (ourCenter == null) return null;
        BaseUnit enemyCenter = findEnemyAnchor(team, ourCenter);
        AiTerritory current = cached;
        if (current != null && current.team == team && current.ourCenter == ourCenter
                && current.enemyCenter == enemyCenter) {
            current.refreshFrontline();
            return current;
        }
        AiMapGraph.DistanceField fromUs = graph.distanceFieldFrom(ourCenter.posX, ourCenter.posY);
        AiMapGraph.DistanceField fromEnemy = enemyCenter == null
                ? null : graph.distanceFieldFrom(enemyCenter.posX, enemyCenter.posY);
        AiTerritory territory = new AiTerritory(team, graph, ourCenter, enemyCenter, fromUs, fromEnemy,
                ourCenter.posX, ourCenter.posY,
                enemyCenter == null ? 0f : enemyCenter.posX,
                enemyCenter == null ? 0f : enemyCenter.posY);
        territory.refreshFrontline();
        cached = territory;
        return territory;
    }

    /**
     * 刷新"前线"：取我方**最外侧防御建筑**（有攻击距离的建筑，排除指挥中心）到敌方指挥中心的步数。
     * 没有防御建筑时退回任意建筑，再没有就退回双方指挥中心等值带（frontlineEnemyDistance = -1）。
     */
    private void refreshFrontline() {
        frontlineEnemyDistance = -1;
        frontlineFromDefence = false;
        if (fromEnemy == null) return;
        int defence = Integer.MAX_VALUE;
        int anyBuilding = Integer.MAX_VALUE;
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit.team != team || unit.isDead || !unit.bI()) continue;
            int distance = fromEnemy.distanceAtWorld(unit.posX, unit.posY);
            if (distance < 0) continue;
            if (isDefenceBuilding(unit)) defence = Math.min(defence, distance);
            anyBuilding = Math.min(anyBuilding, distance);
        }
        if (defence != Integer.MAX_VALUE) {
            frontlineEnemyDistance = defence;
            frontlineFromDefence = true;
        } else if (anyBuilding != Integer.MAX_VALUE) {
            frontlineEnemyDistance = anyBuilding;
        }
    }

    /** 防御建筑：有攻击距离的建筑（含防空/极光/普通炮台），排除指挥中心。 */
    private static boolean isDefenceBuilding(BaseUnit unit) {
        if (!(unit instanceof OrderableUnit)) return false;
        OrderableUnit orderable = (OrderableUnit) unit;
        return orderable.canAttack() && orderable.m() > 0 && !(unit instanceof CommandCenter);
    }

    /** 我方锚点：优先指挥中心，其次任意己方单位。 */
    private static BaseUnit findOurAnchor(PlayerTeam team) {
        BaseUnit fallback = null;
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit.team != team || unit.isDead) continue;
            if (unit instanceof CommandCenter) return unit;
            if (fallback == null && unit.bI()) fallback = unit;
        }
        if (fallback != null) return fallback;
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit.team == team && !unit.isDead) return unit;
        }
        return null;
    }

    /** 敌方锚点：离我方锚点最近的敌方指挥中心。 */
    private static BaseUnit findEnemyAnchor(PlayerTeam team, BaseUnit ourCenter) {
        BaseUnit best = null;
        float bestDistance = Float.MAX_VALUE;
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit.isDead || unit.team == null || !(unit instanceof CommandCenter)) continue;
            if (!unit.team.c(team)) continue; // c() == 敌对
            float dx = unit.posX - ourCenter.posX;
            float dy = unit.posY - ourCenter.posY;
            float distance = (dx * dx) + (dy * dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = unit;
            }
        }
        return best;
    }

    public AiMapGraph graph() { return graph; }

    public boolean hasEnemyAnchor() { return enemyCenter != null; }

    public float ourX() { return ourX; }

    public float ourY() { return ourY; }

    public float enemyX() { return enemyX; }

    public float enemyY() { return enemyY; }

    /** 到最近敌方指挥中心的步数；-1 表示我方走不到（或没有敌方锚点）。 */
    public int distanceFromOurCenter(float worldX, float worldY) {
        return fromUs.distanceAtWorld(worldX, worldY);
    }

    /** 敌方到该点的步数；-1 表示敌方走不到。 */
    public int distanceFromEnemyCenter(float worldX, float worldY) {
        return fromEnemy == null ? -1 : fromEnemy.distanceAtWorld(worldX, worldY);
    }

    /** 我方能否走到（陆地图）。 */
    public boolean reachableForUs(float worldX, float worldY) {
        return distanceFromOurCenter(worldX, worldY) >= 0;
    }

    /** 敌方能否走到（陆地图）："敌人陆地可达"的判据。 */
    public boolean reachableForEnemy(float worldX, float worldY) {
        return distanceFromEnemyCenter(worldX, worldY) >= 0;
    }

    /**
     * 我方一侧 / "到前线为止"：不比"我方最外侧防御线"更靠近敌方。
     * 还没有防御线时退回"我方比敌方更近"的判据。
     */
    public boolean isOurSide(float worldX, float worldY) {
        int ours = distanceFromOurCenter(worldX, worldY);
        if (ours < 0) return false;
        int theirs = distanceFromEnemyCenter(worldX, worldY);
        if (theirs < 0) return true;
        if (frontlineEnemyDistance < 0) return ours <= theirs;
        return theirs >= frontlineEnemyDistance;
    }

    /** 前线带：到敌方的步数在我方最外侧防御线 ±band 之内；没有防御线时退回双方等值带。 */
    public boolean isFrontline(float worldX, float worldY, int band) {
        int theirs = distanceFromEnemyCenter(worldX, worldY);
        if (theirs < 0) return false;
        if (frontlineEnemyDistance < 0) {
            int ours = distanceFromOurCenter(worldX, worldY);
            return ours >= 0 && Math.abs(ours - theirs) <= band;
        }
        return Math.abs(theirs - frontlineEnemyDistance) <= band;
    }

    /** 深入我方后方的程度（越大越靠后，单位格）：到敌方的步数 − 最外侧防御线的步数。 */
    public int depthBehindFrontline(float worldX, float worldY) {
        int ours = distanceFromOurCenter(worldX, worldY);
        int theirs = distanceFromEnemyCenter(worldX, worldY);
        if (ours < 0 || theirs < 0) return -1;
        if (frontlineEnemyDistance < 0) return theirs - ours;
        return theirs - frontlineEnemyDistance;
    }

    /** 我方最外侧防御线到敌方的步数；-1 = 还没有防御线（此时前线退回双方等值带）。 */
    public int frontlineEnemyDistance() {
        return frontlineEnemyDistance;
    }

    /** 前线是否来自真正的防御建筑（false = 退回任意建筑或等值带）。 */
    public boolean frontlineFromDefence() {
        return frontlineFromDefence;
    }

    /** 通往敌方指挥中心的最短路（tile 坐标，起点 → 终点）；不连通返回空表。 */
    public List<Point> routeToEnemy() {
        if (route == null) {
            if (enemyCenter == null) {
                route = List.of();
            } else {
                route = fromUs.pathTo(AiMapGraph.worldToTileX(enemyX), AiMapGraph.worldToTileY(enemyY));
            }
        }
        return route;
    }

    /**
     * 路线上的液体渡口：最短路两侧贴着水/岩浆的格子，按"从我方到敌方"的顺序返回。
     * 每个渡口返回该 tile 中心的世界坐标。路线不含液体本身（陆地图走不过去），
     * 所以这里取"紧邻液体的路线 tile"作为渡口。
     */
    public List<PointF> liquidCrossings() {
        if (crossings == null) {
            List<PointF> result = new ArrayList<>();
            int previousX = Integer.MIN_VALUE;
            int previousY = Integer.MIN_VALUE;
            for (Point tile : routeToEnemy()) {
                boolean touchesLiquid = false;
                for (int direction = 0; direction < 4 && !touchesLiquid; direction++) {
                    int tileX = tile.worldX + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                    int tileY = tile.worldY + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                    if (!graph.inBounds(tileX, tileY)) continue;
                    touchesLiquid = AiMapGraph.isLiquid(AiMapGraph.tileCenterX(tileX), AiMapGraph.tileCenterY(tileY));
                }
                if (!touchesLiquid) continue;
                if (tile.worldX == previousX && tile.worldY == previousY) continue;
                previousX = tile.worldX;
                previousY = tile.worldY;
                result.add(new PointF(AiMapGraph.tileCenterX(tile.worldX), AiMapGraph.tileCenterY(tile.worldY)));
            }
            crossings = result;
        }
        return crossings;
    }

    /** 调试/日志用：地图骨架规模。 */
    public String describe() {
        GameEngine engine = GameEngine.getInstance();
        int tiles = engine != null && engine.tileMap != null ? engine.tileMap.tileCountX * engine.tileMap.tileCountY : 0;
        return "AiTerritory[map=" + graph.width() + "x" + graph.height() + " tiles=" + tiles
                + " ourSide=" + (int) ourX + "," + (int) ourY
                + " enemy=" + (enemyCenter == null ? "none" : (int) enemyX + "," + (int) enemyY)
                + " route=" + routeToEnemy().size() + " crossings=" + liquidCrossings().size() + "]";
    }
}
