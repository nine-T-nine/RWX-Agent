package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.AttackMode;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.UnitCommandType;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import io.github.rwx.geometry.PointF;

import java.util.HashMap;
import java.util.Map;

/** Idle-only reactions; explicit missions are never replaced. */
final class AssistCounterRule implements AiAutomateRule {
    private static final float RECENT_SECONDS = 5f;
    private static final float HEALTH = 40f;
    private static final float STEP = 60f;
    private final Map<Long, BaseUnit> fleeing = new HashMap<>();

    @Override public AiAutomateRuleId id() { return AiAutomateRuleId.ASSIST_COUNTER; }

    @Override public void release(AiAutomate runtime) {
        fleeing.clear();
        AiAutomateRule.super.release(runtime);
    }

    @Override public void update(AiAutomate runtime) {
        int eligible = 0;
        PlayerTeam team = runtime.team();
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.buildProgress < 1f || unit.transportContainer != null || unit.transportedBy != null
                    || unit.canNotBeGivenOrdersByPlayer()) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), false);
            if (!visit.ready) continue;
            AiAutomate.Claim claim = visit.claim;
            if (claim != null && claim.rule != id()) continue;
            if (claim == null) {
                fleeing.remove(unit.objectId);
                if (unit.getWaypointCount() != 0) continue;
                if (unit instanceof FactoryQueueInterface && !((FactoryQueueInterface) unit).dy()) continue;
            }
            eligible++;
            BaseUnit attacker = hostile(unit.q(RECENT_SECONDS), team);
            if (claim != null && fleeing.containsKey(unit.objectId)) {
                if (attacker == null) attacker = hostile(fleeing.get(unit.objectId), team);
                if (attacker == null || escaped(unit, attacker)) {
                    fleeing.remove(unit.objectId);
                    runtime.release(unit, true);
                } else {
                    flee(runtime, unit, attacker, claim);
                }
                continue;
            }
            if (attacker != null) {
                if (AiAutomateRuleConfig.healthPercent(unit) >= HEALTH && attackable(runtime, unit, attacker)) {
                    attack(runtime, unit, attacker);
                } else if (movable(unit)) {
                    flee(runtime, unit, attacker, claim);
                } else if (claim != null) {
                    runtime.release(unit, true);
                }
                continue;
            }
            // Keep a retaliation already underway until its target is gone or becomes inaccessible.
            if (claim != null && claim.micro != null && claim.micro.getCommandType() == UnitCommandType.attack) {
                BaseUnit target = hostile(claim.micro.getTargetUnit(), team);
                if (target != null && attackable(runtime, unit, target)
                        && AiAutomateRuleConfig.healthPercent(unit) >= HEALTH) {
                    runtime.keep(unit);
                    continue;
                } else if (target != null && movable(unit)) {
                    flee(runtime, unit, target, claim);
                    continue;
                }
            }
            BaseUnit aidTarget = null;
            if (unit.canAttack() && unit.attackMode != AttackMode.holdFire
                    && AiAutomateRuleConfig.healthPercent(unit) > HEALTH) {
                for (BaseUnit friend : runtime.visible()) {
                    if (friend == unit || friend.team == null || team.c(friend.team)
                            || friend.team.teamId < 0 || friend.isDead) continue;
                    // The ally must be in this helper's weapon range; the attacker may need pursuit.
                    float range = unit.getEngagementRange(friend);
                    if (AiAutomateSupport.distance(unit.posX, unit.posY, friend.posX, friend.posY) > range) continue;
                    BaseUnit threat = hostile(friend.q(RECENT_SECONDS), team);
                    if (threat != null && attackable(runtime, unit, threat)) {
                        aidTarget = threat;
                        break;
                    }
                }
            }
            if (aidTarget != null) attack(runtime, unit, aidTarget);
            else if (claim != null) runtime.release(unit, true);
        }
        fleeing.keySet().removeIf(id -> runtime.friends().stream().noneMatch(u -> u.objectId == id));
        runtime.record(id(), eligible);
    }

    private static BaseUnit hostile(BaseUnit unit, PlayerTeam team) {
        return unit != null && !unit.isDead && unit.team != null && unit.team.teamId >= 0
                && team.c(unit.team) ? unit : null;
    }

    private static boolean movable(OrderableUnit unit) {
        return !unit.bI() && unit.canExecuteMovementCommands() && unit.getMoveSpeed() > 0;
    }

    private static boolean attackable(AiAutomate runtime, OrderableUnit unit, BaseUnit target) {
        if (!unit.canAttack() || unit.attackMode == AttackMode.holdFire || !target.d(runtime.team())
                || target.t() || !unit.b(target, true) || !unit.canAttackUnitType(target)
                || !unit.canEngageTargetWithMovement(target)) return false;
        float distance = AiAutomateSupport.distance(unit.posX, unit.posY, target.posX, target.posY);
        if (distance <= unit.getEngagementRange(target)) return true;
        if (!movable(unit)) return false;
        // Test firing positions instead of the target tile (ships, buildings and cliffs may not
        // be standable). The terrain callback validates the complete route, not just the endpoint.
        float range = Math.max(1f, unit.getEngagementRange(target) - 10f);
        double angle = Math.atan2(unit.posY - target.posY, unit.posX - target.posX);
        for (int offset = 0; offset <= 180; offset += 30) {
            for (int sign : new int[] {1, -1}) {
                double direction = angle + Math.toRadians(offset * sign);
                if (runtime.canReach(unit, target.posX + (float) Math.cos(direction) * range,
                        target.posY + (float) Math.sin(direction) * range)) return true;
            }
        }
        return false;
    }

    private void attack(AiAutomate runtime, OrderableUnit unit, BaseUnit target) {
        UnitCommand command = new UnitCommand();
        command.setAttackTarget(target);
        if (runtime.apply(unit, id(), command)) {
            runtime.trigger(id(), "#" + unit.objectId + " 支援/反击 #" + target.objectId);
        }
    }

    private static boolean escaped(OrderableUnit unit, BaseUnit attacker) {
        if (unit.q(RECENT_SECONDS) != null) return false;
        if (!(attacker instanceof OrderableUnit)) return true;
        OrderableUnit armed = (OrderableUnit) attacker;
        if (!armed.canAttack() || !armed.canAttackUnitType(unit)) return true;
        return AiAutomateSupport.distance(unit.posX, unit.posY, attacker.posX, attacker.posY)
                > armed.getEngagementRange(unit) + unit.radius + 20f;
    }

    private void flee(AiAutomate runtime, OrderableUnit unit, BaseUnit attacker, AiAutomate.Claim claim) {
        if (!movable(unit)) return;
        fleeing.put(unit.objectId, attacker);
        if (claim != null && claim.micro != null && claim.micro.getCommandType() == UnitCommandType.move
                && unit.getCurrentWaypoint() != null
                && AiAutomateSupport.distance(unit.posX, unit.posY,
                claim.micro.getTargetX(), claim.micro.getTargetY()) > 8f) {
            runtime.keep(unit);
            return;
        }
        PointF point = fleePoint(runtime, unit, attacker);
        if (point == null) {
            if (claim != null) runtime.keep(unit);
            runtime.setState(id(), "后撤方向被地形阻挡");
            return;
        }
        UnitCommand command = new UnitCommand();
        command.setMoveTarget(point.x, point.y);
        if (runtime.apply(unit, id(), command)) {
            runtime.trigger(id(), "#" + unit.objectId + " 后撤3格，远离 #" + attacker.objectId);
        }
    }

    private static PointF fleePoint(AiAutomate runtime, OrderableUnit unit, BaseUnit attacker) {
        double away = Math.atan2(unit.posY - attacker.posY, unit.posX - attacker.posX);
        float current = AiAutomateSupport.distance(unit.posX, unit.posY, attacker.posX, attacker.posY);
        for (int offset = 0; offset <= 90; offset += 15) {
            for (int sign : new int[] {1, -1}) {
                double direction = away + Math.toRadians(offset * sign);
                float x = unit.posX + (float) Math.cos(direction) * STEP;
                float y = unit.posY + (float) Math.sin(direction) * STEP;
                if (AiAutomateSupport.distance(x, y, attacker.posX, attacker.posY) <= current + 1f) continue;
                if (runtime.canStand(unit, x, y) && !CombatRules.blocked(unit, runtime.visible(), x, y)) {
                    return new PointF(x, y);
                }
            }
        }
        return null;
    }
}
