package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import io.github.rwx.geometry.PointF;

import java.util.List;
import java.util.Locale;

/**
 * 战斗类规则：火炮/风筝的「就绪停留、冷却后撤」，以及撤退、机甲受击后撤、潜艇上下潜。
 *
 * <p>它们都只调整站位或切换单位自身动作，不决定打谁、不决定什么时候进攻。</p>
 */
final class ArtilleryRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.ARTILLERY;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        PlayerTeam team = runtime.team();
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.artilleryUnit(unit, team) || !config.matches(unit)) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), false);
            if (!visit.ready) continue;
            eligible++;
            AiAutomate.Claim claim = visit.claim;
            if (claim != null && claim.rule != id()) {
                if (!runtime.mayUse(unit, id())) continue;
                claim = null;
            }
            float anchorX = claim != null ? claim.anchorX
                    : (unit.getCurrentWaypoint() == null ? unit.posX : unit.getCurrentWaypoint().getTargetX());
            float anchorY = claim != null ? claim.anchorY
                    : (unit.getCurrentWaypoint() == null ? unit.posY : unit.getCurrentWaypoint().getTargetY());
            boolean stationary = claim != null ? claim.stationary : unit.getCurrentWaypoint() == null;
            BaseUnit target = AiAutomateSupport.nearestEnemy(unit, team, runtime.enemies(),
                    Float.POSITIVE_INFINITY, stationary ? Float.valueOf(anchorX) : null,
                    stationary ? Float.valueOf(anchorY) : null, 20f);
            if (target == null) {
                if (claim != null) runtime.release(unit, true);
                continue;
            }
            CombatRules.holdOrReposition(runtime, unit, id(), target, claim);
        }
        runtime.record(id(), eligible);
    }
}

final class KiteRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.KITE;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        PlayerTeam team = runtime.team();
        float minimumRange = config.radius == null ? 400f : config.radius;
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.combatUnit(unit, team)) continue;
            if (AiAutomateSupport.artilleryUnit(unit, team)) continue;
            if (!config.matches(unit)) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            eligible++;
            AiAutomate.Claim claim = visit.claim;
            if (claim != null && claim.rule != id()) {
                if (!runtime.mayUse(unit, id())) continue;
                claim = null;
            }
            BaseUnit target = AiAutomateSupport.nearestEnemy(unit, team, runtime.enemies(),
                    Float.POSITIVE_INFINITY);
            if (target == null) {
                if (claim != null) runtime.release(unit, true);
                continue;
            }
            if (unit.getEngagementRange(target) < minimumRange) continue;
            CombatRules.holdOrReposition(runtime, unit, id(), target, claim);
        }
        runtime.record(id(), eligible);
    }
}

final class MechRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.MECH;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        PlayerTeam team = runtime.team();
        float searchRadius = config.radius == null ? 320f : config.radius;
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.combatUnit(unit, team) || !config.matches(unit)) continue;
            String type = unit.r().v().toLowerCase(Locale.ROOT);
            if (!type.contains("mech")) continue;
            if (type.contains("engineer")) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            eligible++;
            if (config.hpPercent != null && config.hpPercent < 100f
                    && AiAutomateRuleConfig.healthPercent(unit) > config.hpPercent) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            BaseUnit attacker = unit.q(2.0f);
            BaseUnit threat = attacker != null && !attacker.isDead && attacker.team != null
                    && !team.c(attacker.team) ? attacker
                    : AiAutomateSupport.nearestEnemy(unit, team, runtime.enemies(), searchRadius);
            if (threat == null) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            PointF point = CombatRules.fleePoint(runtime, unit, threat, 60f, runtime.visible());
            if (point == null) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            UnitCommand micro = new UnitCommand();
            micro.setMoveTarget(point.x, point.y);
            if (runtime.apply(unit, id(), micro)) {
                runtime.trigger(id(), "#" + unit.objectId + " 远离 #" + threat.objectId);
            }
        }
        runtime.record(id(), eligible);
    }
}

final class RetreatRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.RETREAT;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        PlayerTeam team = runtime.team();
        float threshold = config.hpPercent == null ? 20f : config.hpPercent;
        float searchRadius = config.radius == null ? 300f : config.radius;
        float step = Math.max(90f, Math.min(200f, searchRadius * 0.4f));
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.combatUnit(unit, team) || !config.matches(unit)) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            eligible++;
            if (AiAutomateRuleConfig.healthPercent(unit) > threshold) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            BaseUnit threat = AiAutomateSupport.nearestEnemy(unit, team, runtime.enemies(), searchRadius);
            if (threat == null) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            PointF point = CombatRules.fleePoint(runtime, unit, threat, step, runtime.visible());
            if (point == null) {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            UnitCommand micro = new UnitCommand();
            micro.setMoveTarget(point.x, point.y);
            if (runtime.apply(unit, id(), micro)) {
                runtime.trigger(id(), "#" + unit.objectId + " 血量 "
                        + Math.round(AiAutomateRuleConfig.healthPercent(unit)) + "% 后撤，远离 #" + threat.objectId);
            }
        }
        runtime.record(id(), eligible);
    }
}

/** 潜艇 / 两栖机 / 自定义可下潜单位：按交战目标是否在水下切换 Dive / Surface。 */
final class DiveRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.DIVE;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.isDead || unit.buildProgress < 1 || !config.matches(unit)) continue;
            AbstractUnitAction dive = findAction(unit, "dive");
            AbstractUnitAction surface = findAction(unit, "surface");
            if (dive == null || surface == null) continue;
            eligible++;
            if (!AiAutomate.isManagedOrder(unit.getCurrentWaypoint())) continue;
            BaseUnit target = unit.getCommandOrAttackTarget();
            if (target == null || target.isDead || !unit.isWithinEngagementRange(target)) continue;
            // 目标在水下要用鱼雷，其余情况上浮以便对地/对空开火（与原版 AIController 一致）。
            boolean wantSurface = !target.isTouchingWater();
            boolean surfaced = !unit.Q();
            if (wantSurface == surfaced) continue;
            AbstractUnitAction action = wantSurface ? surface : dive;
            if (!action.b(unit) || !action.canAfford(unit, false)) continue;
            if (runtime.action(unit, id(), action)) {
                runtime.trigger(id(), "#" + unit.objectId + (wantSurface ? " 上浮" : " 下潜")
                        + "（目标 #" + target.objectId + "）");
            }
        }
        runtime.record(id(), eligible);
    }

    private static AbstractUnitAction findAction(OrderableUnit unit, String wanted) {
        for (AbstractUnitAction action : unit.getAvailableActions()) {
            String name = action.getDisplayName();
            if (name == null) continue;
            if (name.trim().toLowerCase(Locale.ROOT).equals(wanted)) return action;
        }
        return null;
    }
}

/** 战斗规则共享的站位计算。 */
final class CombatRules {
    private CombatRules() { }

    /**
     * 武器就绪就原地停留瞄准，冷却期间退到射程边缘。与火炮微操的原实现完全一致，
     * 现在由 artillery 和 kite 共用。
     */
    static void holdOrReposition(AiAutomate runtime, OrderableUnit unit, AiAutomateRuleId rule,
                                 BaseUnit target, AiAutomate.Claim claim) {
        float distance = AiAutomateSupport.distance(unit.posX, unit.posY, target.posX, target.posY);
        float range = unit.getEngagementRange(target);
        boolean ready = AiAutomateSupport.cooldown(unit, target) <= AiAutomate.READY_FRAMES;
        UnitCommand micro = new UnitCommand();
        if (ready && distance < range - 2 && distance > 60) {
            micro.setAttackMoveTarget(unit.posX, unit.posY);
        } else {
            PointF destination = AiAutomateSupport.retreatPoint(runtime, unit, target, claim,
                    runtime.visible(), range, 65f, 120f);
            if (destination == null) {
                // 没有安全落点时交给原版索敌自行开火。
                if (distance < range) {
                    micro.setAttackMoveTarget(unit.posX, unit.posY);
                } else {
                    runtime.release(unit, true);
                    return;
                }
            } else {
                micro.setMoveTarget(destination.x, destination.y);
            }
        }
        runtime.apply(unit, rule, micro);
    }

    /** 只释放本规则自己的接管，绝不替别的规则做恢复。 */
    static void releaseOwn(AiAutomate runtime, AiAutomate.Visit visit, OrderableUnit unit,
                           AiAutomateRuleId rule) {
        if (visit.claim != null && visit.claim.rule == rule) runtime.release(unit, true);
    }

    /** 朝远离威胁的方向找一个站得住、且确实比现在更远的落点。 */
    static PointF fleePoint(AiAutomate runtime, OrderableUnit unit, BaseUnit threat,
                            float step, List<BaseUnit> obstacles) {
        float home = AiAutomateSupport.distance(unit.posX, unit.posY, threat.posX, threat.posY);
        double away = Math.atan2(unit.posY - threat.posY, unit.posX - threat.posX);
        for (int offset = 0; offset <= 90; offset += 15) {
            for (int sign : new int[] {1, -1}) {
                if (offset == 0 && sign == -1) continue;
                double angle = away + Math.toRadians(offset * sign);
                for (float length : new float[] {step, step * 0.6f}) {
                    float x = unit.posX + (float) Math.cos(angle) * length;
                    float y = unit.posY + (float) Math.sin(angle) * length;
                    if (AiAutomateSupport.distance(x, y, threat.posX, threat.posY) <= home + 8f) continue;
                    if (!runtime.canStand(unit, x, y)) continue;
                    if (blocked(unit, obstacles, x, y)) continue;
                    return new PointF(x, y);
                }
            }
        }
        return null;
    }

    static boolean blocked(OrderableUnit unit, List<BaseUnit> obstacles, float x, float y) {
        for (BaseUnit other : obstacles) {
            if (other == unit || other.transportContainer != null || other.i()) continue;
            float clearance = unit.radius + other.radius + 4;
            if (AiAutomateSupport.distance(x, y, other.posX, other.posY) < clearance) return true;
        }
        return false;
    }
}
