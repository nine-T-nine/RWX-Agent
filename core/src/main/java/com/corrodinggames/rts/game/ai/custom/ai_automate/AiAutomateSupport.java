package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.units.AttackMode;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.UnitCommandType;
import io.github.rwx.geometry.PointF;

import java.util.List;
import java.util.Locale;

/** 规则共用的判定与几何工具：单位筛选、射程冷却、后撤点搜索、任务快照拷贝。 */
final class AiAutomateSupport {
    private AiAutomateSupport() { }

    /** 默认地形检查：与火炮微操一致，沿路径采样并检查四角余量。 */
    static boolean canStand(OrderableUnit unit, float x, float y) {
        if (unit.getMovementType() == com.corrodinggames.rts.game.units.UnitMovementType.AIR) {
            return canReach(unit, x, y);
        }
        AiMapGraph graph = AiMapGraph.of(unit.getMovementType());
        if (graph == null || !AiMapGraph.sameRegion(unit.posX, unit.posY, x, y, unit.getMovementType())) {
            return false;
        }
        int steps = Math.max(1, (int) (distance(unit.posX, unit.posY, x, y) / 10));
        for (int i = 1; i <= steps; i++) {
            float px = unit.posX + (x - unit.posX) * i / steps;
            float py = unit.posY + (y - unit.posY) * i / steps;
            if (!graph.passable(AiMapGraph.worldToTileX(px), AiMapGraph.worldToTileY(py))) return false;
            float margin = unit.radius * 0.7f;
            for (int dx : new int[] {-1, 1}) {
                for (int dy : new int[] {-1, 1}) {
                    if (!graph.passable(AiMapGraph.worldToTileX(px + dx * margin),
                            AiMapGraph.worldToTileY(py + dy * margin))) return false;
                }
            }
        }
        return true;
    }

    /** Static connectivity allows a route around obstacles, unlike a short retreat's straight ray. */
    static boolean canReach(OrderableUnit unit, float x, float y) {
        com.corrodinggames.rts.game.map.TileMap map = com.corrodinggames.rts.gameFramework.GameEngine.getInstance().tileMap;
        if (map == null || x < 0 || y < 0 || x >= map.getWorldWidth() || y >= map.getWorldHeight()) return false;
        if (unit.getMovementType() == com.corrodinggames.rts.game.units.UnitMovementType.AIR) return true;
        return AiMapGraph.sameRegion(unit.posX, unit.posY, x, y, unit.getMovementType());
    }

    /** 通用的“可被我方接管”的战斗单位。 */
    static boolean combatUnit(OrderableUnit unit, PlayerTeam team) {
        return unit.team == team && !unit.isDead && unit.buildProgress >= 1
                && unit.transportContainer == null && unit.transportedBy == null
                && !unit.bI() && unit.canAttack() && unit.canExecuteMovementCommands()
                && !unit.canNotBeGivenOrdersByPlayer() && unit.getMoveSpeed() > 0
                && unit.attackMode != AttackMode.holdFire && unit.attackMode != AttackMode.returnFire;
    }

    /** 火炮规则沿用的原版筛选：陆地/越崖移动且内部名包含 artillery。 */
    static boolean artilleryUnit(OrderableUnit unit, PlayerTeam team) {
        return combatUnit(unit, team)
                && (unit.getMovementType() == com.corrodinggames.rts.game.units.UnitMovementType.LAND
                || unit.getMovementType() == com.corrodinggames.rts.game.units.UnitMovementType.OVER_CLIFF)
                && unit.r().v().toLowerCase(Locale.ROOT).contains("artillery");
    }

    /** 火炮规则只接管 attack-move、guard-at 或空闲；巡逻与显式指令不碰。 */
    static boolean artilleryOrder(UnitCommand order) {
        return order == null || order.getCommandType() == UnitCommandType.attackMove
                || order.getCommandType() == UnitCommandType.guardAt;
    }

    /**
     * 队列里是否有本模块无法安全重写的特殊标记。原生 appendWaypointCopy 不保留这些字段，
     * 因此脚本队列或过长的队列一律不接管。
     */
    static boolean supportedQueue(OrderableUnit unit) {
        if (unit.getWaypointCount() >= 29) return false;
        for (int i = 0; i < unit.getWaypointCount(); i++) {
            UnitCommand order = unit.getWaypointAt(i);
            if (order.isRepeating || order.isForceMove || order.attackMoveRange >= 0
                    || order.maxWayPointSurvivingTime >= 0) return false;
        }
        return true;
    }

    /** 与火炮微操一致的最近可攻击敌人搜索。 */
    static BaseUnit nearestEnemy(OrderableUnit unit, PlayerTeam team, List<BaseUnit> visible,
                                 float maxDistance) {
        return nearestEnemy(unit, team, visible, maxDistance, null, null, 0f);
    }

    /**
     * 带锚点的版本：驻守/待机单位只在锚点附近交战，绝不跨图追击（与原实现一致）。
     *
     * @param anchorX 为 null 表示不限制交战距离
     */
    static BaseUnit nearestEnemy(OrderableUnit unit, PlayerTeam team, List<BaseUnit> visible,
                                 float maxDistance, Float anchorX, Float anchorY, float anchorSlack) {
        BaseUnit best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (BaseUnit enemy : visible) {
            if (enemy.team == null || enemy.team.teamId < 0 || !team.c(enemy.team)
                    || !enemy.d(team) || enemy.t() || !unit.b(enemy, true)
                    || !unit.canEngageTargetWithMovement(enemy)) continue;
            float range = unit.getEngagementRange(enemy);
            float distance = distance(unit.posX, unit.posY, enemy.posX, enemy.posY);
            if (distance > range + 40 || distance > maxDistance) continue;
            if (anchorX != null && distance(anchorX, anchorY, enemy.posX, enemy.posY) > range + anchorSlack) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }

    static float cooldown(OrderableUnit unit, BaseUnit target) {
        float result = Float.POSITIVE_INFINITY;
        for (int i = 0; i < unit.getTechLevel() && i < unit.movementLevels.length; i++) {
            if (unit.r(i) && unit.a(i, target, true, false)) {
                result = Math.min(result, Math.max(0, unit.movementLevels[i].rotation));
            }
        }
        return result;
    }

    /**
     * 在敌人周围找一个“站得住、不挡别人、离敌人更远”的落点。原有火炮后撤逻辑，
     * 现在由 retreat / mech / kite 共用。
     */
    static PointF retreatPoint(AiAutomate runtime, OrderableUnit unit, BaseUnit enemy,
                               AiAutomate.Claim claim, List<BaseUnit> obstacles, float range,
                               float maxStep, float slack) {
        float leadX = Math.max(-40, Math.min(40, enemy.velocityX * 30));
        float leadY = Math.max(-40, Math.min(40, enemy.velocityY * 30));
        float ex = enemy.posX + leadX, ey = enemy.posY + leadY;
        double angle = Math.atan2(unit.posY - ey, unit.posX - ex);
        float standoff = Math.max(70f, range - Math.max(12, unit.radius));
        if (Math.abs(distance(unit.posX, unit.posY, ex, ey) - standoff) < 12) return null;
        for (int offset = 0; offset <= 75; offset += 15) {
            for (int sign : new int[] {1, -1}) {
                if (offset == 0 && sign == -1) continue;
                double candidateAngle = angle + Math.toRadians(offset * sign);
                float x = ex + (float) Math.cos(candidateAngle) * standoff;
                float y = ey + (float) Math.sin(candidateAngle) * standoff;
                float step = distance(unit.posX, unit.posY, x, y);
                if (step > maxStep) {
                    x = unit.posX + (x - unit.posX) * maxStep / step;
                    y = unit.posY + (y - unit.posY) * maxStep / step;
                }
                if (claim != null && claim.stationary
                        && distance(claim.anchorX, claim.anchorY, x, y) > slack) continue;
                if (distance(unit.posX, unit.posY, enemy.posX, enemy.posY) < standoff
                        && distance(x, y, enemy.posX, enemy.posY)
                        < distance(unit.posX, unit.posY, enemy.posX, enemy.posY) + 4) continue;
                if (distance(unit.posX, unit.posY, x, y) < 12 || !runtime.canStand(unit, x, y)) continue;
                boolean blocked = false;
                for (BaseUnit other : obstacles) {
                    if (other == unit || other.transportContainer != null || other.i()) continue;
                    float clearance = unit.radius + other.radius + 4;
                    if (distance(x, y, other.posX, other.posY) < clearance) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) return new PointF(x, y);
            }
        }
        return null;
    }

    /**
     * 可以“原样重发”的任务：重发不改变目的地或目标，只是让引擎重新寻路。
     * 建造、修理、回收、装载重复下发可能有副作用，因此排除。
     */
    static boolean isRepathable(UnitCommand order) {
        if (order == null) return false;
        UnitCommandType type = order.getCommandType();
        return type == UnitCommandType.move || type == UnitCommandType.attackMove
                || type == UnitCommandType.attack || type == UnitCommandType.guardAt
                || type == UnitCommandType.patrol;
    }

    /**
     * 两条微操是否指向同一个目标。单位目标按对象身份比较，避免两个相邻敌人因坐标太近
     * 被误判为同一目标而无法切换（炮塔切换目标时尤其明显）。
     */
    static boolean sameTarget(UnitCommand micro, UnitCommand current) {
        if (micro == null || current == null) return false;
        BaseUnit first = micro.getTargetUnit();
        BaseUnit second = current.getTargetUnit();
        if (first != null || second != null) return first == second;
        if (micro.getCommandType() == UnitCommandType.attackMove) return true;
        return distance(micro.getTargetX(), micro.getTargetY(),
                current.getTargetX(), current.getTargetY()) < 16f;
    }

    /** 是否为我方固定炮塔（建筑 + 有武器 + 名字像炮塔或只对空）。 */
    static boolean isTurret(OrderableUnit unit) {
        if (unit == null || !unit.bI() || !unit.canAttack()) return false;
        String id = unit.r().v() == null ? "" : unit.r().v().toLowerCase(Locale.ROOT);
        if (id.contains("turret")) return true;
        if (airOnlyTurret(unit)) return true;
        String name = unit.r().getUnitTypeDescriptionShort();
        return name != null && name.contains("炮塔");
    }

    /** 只对空、不对地也不对海的炮塔（防空炮塔及其变体）。 */
    static boolean airOnlyTurret(OrderableUnit unit) {
        return unit.canAttackFlyingUnits() && !unit.canAttackSurfaceUnits()
                && !unit.canAttackSubmergedUnits();
    }

    /** 炮塔等级：优先读单位 id 里的 {@code _t1/_t2/_t3}，读不到时退回科技等级。 */
    static int turretTier(OrderableUnit unit) {
        String id = unit.r().v() == null ? "" : unit.r().v().toLowerCase(Locale.ROOT);
        int index = id.lastIndexOf("_t");
        while (index >= 0 && index + 2 < id.length()) {
            char digit = id.charAt(index + 2);
            if (digit >= '1' && digit <= '3') return digit - '0';
            index = index <= 0 ? -1 : id.lastIndexOf("_t", index - 1);
        }
        return Math.max(1, Math.min(3, unit.getTechLevel()));
    }

    static boolean same(UnitCommand first, UnitCommand second) {
        return first != null && second != null && first.isSameCommand(second)
                && first.isQueued == second.isQueued;
    }

    /** 原生 copyFrom 会丢掉队列语义字段，这里显式补回。 */
    static UnitCommand copy(UnitCommand source) {
        UnitCommand copy = new UnitCommand();
        copy.copyFrom(source);
        copy.attackMoveRange = source.attackMoveRange;
        copy.maxWayPointSurvivingTime = source.maxWayPointSurvivingTime;
        copy.isRepeating = source.isRepeating;
        copy.isForceMove = source.isForceMove;
        return copy;
    }

    static String stamp(long gameTimeMillis) {
        long seconds = Math.max(0L, gameTimeMillis) / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        StringBuilder text = new StringBuilder();
        if (minutes < 10) text.append('0');
        text.append(minutes).append(':');
        if (rest < 10) text.append('0');
        text.append(rest);
        return text.toString();
    }

    static float distance(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }
}
