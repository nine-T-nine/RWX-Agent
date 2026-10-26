package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战术类规则：区域防御与集火/去溢出。
 *
 * <p>两者都只重排“已经在打的单位”的目标或集结点，不决定是否开战。</p>
 */
final class GuardRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.GUARD;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        if (config.x == null || config.y == null) {
            runtime.setState(id(), "缺少区域中心（--x/--y）");
            return;
        }
        PlayerTeam team = runtime.team();
        float centerX = config.x;
        float centerY = config.y;
        float radius = config.radius == null ? 300f : config.radius;
        int eligible = 0;
        // 牵引范围：被拉开到 2 倍半径以内仍然负责拉回来，再远就交给 Agent 的其他指令。
        float leash = radius * 2f;
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.combatUnit(unit, team) || !config.matchesScope(unit)) continue;
            if (AiAutomateSupport.distance(unit.posX, unit.posY, centerX, centerY) > leash) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            eligible++;
            BaseUnit intruder = nearestIntruder(runtime, team, unit, centerX, centerY, radius);
            UnitCommand micro = new UnitCommand();
            if (intruder != null) {
                micro.setAttackMoveTarget(intruder.posX, intruder.posY);
            } else if (AiAutomateSupport.distance(unit.posX, unit.posY, centerX, centerY) > 40f) {
                micro.setMoveTarget(centerX, centerY);
            } else {
                CombatRules.releaseOwn(runtime, visit, unit, id());
                continue;
            }
            runtime.apply(unit, id(), micro);
        }
        runtime.record(id(), eligible);
    }

    private static BaseUnit nearestIntruder(AiAutomate runtime, PlayerTeam team, OrderableUnit unit,
                                            float centerX, float centerY, float radius) {
        BaseUnit best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (BaseUnit enemy : runtime.enemies()) {
            if (enemy.isDead || enemy.team == null || !team.c(enemy.team)) continue;
            if (AiAutomateSupport.distance(centerX, centerY, enemy.posX, enemy.posY) > radius) continue;
            if (!unit.b(enemy, true) || !unit.canEngageTargetWithMovement(enemy)) continue;
            float distance = AiAutomateSupport.distance(unit.posX, unit.posY, enemy.posX, enemy.posY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }
}

/**
 * 集火 / 去溢出：把“3 个以上单位打同一个健康目标”的多余火力，转到附近还没人打的目标上。
 *
 * <p>判断刻意不依赖具体武器伤害：同目标攻击者超过 2 个且目标血量仍高于 40% 时，
 * 把距离最远（支援最慢）的那一个改派到别的敌人；残血目标不动，避免抢最后一击。</p>
 */
final class FocusFireRule implements AiAutomateRule {
    private static final int MAX_ATTACKERS_PER_TARGET = 2;
    private static final float FINISH_THRESHOLD_PERCENT = 40f;

    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.FOCUS_FIRE;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        PlayerTeam team = runtime.team();
        int eligible = 0;
        Map<Long, List<OrderableUnit>> attackers = new HashMap<>();
        Map<Long, BaseUnit> targets = new HashMap<>();
        for (OrderableUnit unit : runtime.friends()) {
            if (!AiAutomateSupport.combatUnit(unit, team) || !config.matches(unit)) continue;
            // 空闲单位的索敌交给原版；只调整已经有明确交战的单位，避免打断待机防守。
            if (unit.getWaypointCount() == 0) continue;
            BaseUnit target = unit.getCommandOrAttackTarget();
            if (target == null || target.isDead || target.team == null || team.c(target.team)) continue;
            eligible++;
            List<OrderableUnit> list = attackers.get(target.objectId);
            if (list == null) {
                list = new ArrayList<>();
                attackers.put(target.objectId, list);
                targets.put(target.objectId, target);
            }
            list.add(unit);
        }
        if (attackers.size() < 2) {
            runtime.record(id(), eligible);
            return;
        }
        for (Map.Entry<Long, List<OrderableUnit>> entry : attackers.entrySet()) {
            List<OrderableUnit> group = entry.getValue();
            BaseUnit target = targets.get(entry.getKey());
            if (group.size() <= MAX_ATTACKERS_PER_TARGET || target == null || target.isDead) continue;
            if (AiAutomateRuleConfig.healthPercent(target) <= FINISH_THRESHOLD_PERCENT) continue;
            // 距离最远的那个最可能还在赶路，改派它的代价最小。
            OrderableUnit peeled = farthest(runtime, group, target);
            if (peeled == null) continue;
            BaseUnit alternative = bestAlternative(runtime, team, peeled, targets.keySet());
            if (alternative == null) continue;
            if (!runtime.cooled(peeled.objectId, id())) continue;
            AiAutomate.Visit visit = runtime.visit(peeled, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(peeled, id())) continue;
            UnitCommand micro = new UnitCommand();
            micro.setAttackTarget(alternative);
            if (runtime.apply(peeled, id(), micro)) {
                runtime.stamp(peeled.objectId, id());
                runtime.trigger(id(), "#" + peeled.objectId + " 从 #" + target.objectId
                        + " 转向 #" + alternative.objectId + "（集火/去溢出）");
            }
        }
        runtime.record(id(), eligible);
    }

    private static OrderableUnit farthest(AiAutomate runtime, List<OrderableUnit> group, BaseUnit target) {
        OrderableUnit best = null;
        float bestDistance = -1f;
        for (OrderableUnit unit : group) {
            if (!runtime.cooled(unit.objectId, AiAutomateRuleId.FOCUS_FIRE)) continue;
            float distance = AiAutomateSupport.distance(unit.posX, unit.posY, target.posX, target.posY);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = unit;
            }
        }
        return best;
    }

    /** 选一个当前没人打、血量最低、并且这个单位够得着的敌人。 */
    private static BaseUnit bestAlternative(AiAutomate runtime, PlayerTeam team, OrderableUnit unit,
                                            java.util.Set<Long> alreadyTargeted) {
        BaseUnit best = null;
        float bestScore = Float.POSITIVE_INFINITY;
        for (BaseUnit enemy : runtime.enemies()) {
            if (enemy.isDead || enemy.team == null || !team.c(enemy.team)) continue;
            if (alreadyTargeted.contains(enemy.objectId)) continue;
            if (!unit.b(enemy, true) || !unit.canEngageTargetWithMovement(enemy)) continue;
            float distance = AiAutomateSupport.distance(unit.posX, unit.posY, enemy.posX, enemy.posY);
            if (distance > unit.getEngagementRange(enemy) + 80f) continue;
            float score = AiAutomateRuleConfig.healthPercent(enemy) + distance / 20f;
            if (score < bestScore) {
                bestScore = score;
                best = enemy;
            }
        }
        return best;
    }
}
