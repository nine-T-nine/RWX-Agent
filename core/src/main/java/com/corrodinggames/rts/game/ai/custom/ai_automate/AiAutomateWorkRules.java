package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import io.github.rwx.geometry.PointF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行保障类规则：自动修理、协助施工、脱困、空闲提醒。
 *
 * <p>这些规则不改变战术意图：修理/施工只在单位空闲时接管，脱困只重发已有指令，
 * 空闲提醒完全不发命令。</p>
 */
final class RepairRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.REPAIR;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        float radius = config.radius == null ? 220f : config.radius;
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.isDead || unit.r().isBuildingUnit()) continue;
            if (!unit.canExecuteMovementCommands()) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            if (visit.claim != null && visit.claim.rule == id()) {
                if (needsWork(visit.claim)) {
                    runtime.keep(unit);
                } else {
                    runtime.release(unit, true);
                }
                continue;
            }
            if (!config.matches(unit)) continue;
            eligible++;
            BaseUnit target = nearestTarget(runtime, unit, radius, true);
            if (target == null) continue;
            UnitCommand micro = new UnitCommand();
            micro.setRepairCommand(target);
            if (runtime.apply(unit, id(), micro)) {
                runtime.trigger(id(), "#" + unit.objectId + " 修理 #" + target.objectId);
            }
        }
        runtime.record(id(), eligible);
    }

    private static boolean needsWork(AiAutomate.Claim claim) {
        if (claim.micro == null) return false;
        BaseUnit target = claim.micro.getTargetUnit();
        if (target == null || target.isDead) return false;
        return target.currentHealth < target.maxHealth || target.buildProgress < 1f;
    }

    private static BaseUnit nearestTarget(AiAutomate runtime, OrderableUnit unit, float radius,
                                          boolean completedOnly) {
        BaseUnit best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (OrderableUnit candidate : runtime.friends()) {
            if (candidate == unit || candidate.isDead) continue;
            if (completedOnly && candidate.buildProgress < 1f) continue;
            if (candidate.currentHealth >= candidate.maxHealth) continue;
            if (!unit.canRepairTarget(candidate)) continue;
            float distance = AiAutomateSupport.distance(unit.posX, unit.posY, candidate.posX, candidate.posY);
            if (distance > radius) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}

/** 空闲建造者就近协助未完工建筑；不新建建筑、不改建造计划。 */
final class AssistBuildRule implements AiAutomateRule {
    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.ASSIST_BUILD;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        float radius = config.radius == null ? 220f : config.radius;
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.isDead || unit.r().isBuildingUnit()) continue;
            if (!unit.canExecuteMovementCommands()) continue;
            AiAutomate.Visit visit = runtime.visit(unit, id(), true);
            if (!visit.ready) continue;
            if (visit.claim != null && visit.claim.rule != id() && !runtime.mayUse(unit, id())) continue;
            if (visit.claim != null && visit.claim.rule == id()) {
                if (stillBuilding(visit.claim)) runtime.keep(unit);
                else runtime.release(unit, true);
                continue;
            }
            if (!config.matches(unit)) continue;
            eligible++;
            BaseUnit site = nearestSite(runtime, unit, radius);
            if (site == null) continue;
            UnitCommand micro = new UnitCommand();
            micro.setRepairCommand(site);
            if (runtime.apply(unit, id(), micro)) {
                runtime.trigger(id(), "#" + unit.objectId + " 协助施工 #" + site.objectId);
            }
        }
        runtime.record(id(), eligible);
    }

    private static boolean stillBuilding(AiAutomate.Claim claim) {
        if (claim.micro == null) return false;
        BaseUnit target = claim.micro.getTargetUnit();
        return target != null && !target.isDead && target.buildProgress < 1f;
    }

    private static BaseUnit nearestSite(AiAutomate runtime, OrderableUnit unit, float radius) {
        BaseUnit best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (OrderableUnit candidate : runtime.friends()) {
            if (candidate == unit || candidate.isDead) continue;
            if (candidate.buildProgress >= 1f) continue;
            if (!unit.canRepairTarget(candidate)) continue;
            float distance = AiAutomateSupport.distance(unit.posX, unit.posY, candidate.posX, candidate.posY);
            if (distance > radius) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}

/**
 * 卡住恢复：有移动指令却连续约 2 秒没有位移时，先原样重发当前队列（强制重新寻路），
 * 再侧移一次，仍失败就只上报，不再反复折腾。
 */
final class UnstuckRule implements AiAutomateRule {
    private static final int STUCK_TICKS = 120;
    private static final int RETRY_TICKS = 150;
    private static final int GIVE_UP_TICKS = 600;
    private static final float MOVED_ENOUGH = 10f;

    private static final class Track {
        float x;
        float y;
        int lastProgressTick;
        int attempts;
        int nextAttemptTick;

        Track(float x, float y, int tick) {
            this.x = x;
            this.y = y;
            this.lastProgressTick = tick;
        }
    }

    private final Map<Long, Track> tracks = new HashMap<>();

    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.UNSTUCK;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        int tick = runtime.passTick();
        Set<Long> alive = new HashSet<>();
        int eligible = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.isDead) continue;
            alive.add(unit.objectId);
            if (runtime.manuallyOverridden(unit, id())) {
                tracks.remove(unit.objectId);
                continue;
            }
            boolean moving = unit.getWaypointCount() > 0
                    && AiAutomateSupport.isRepathable(unit.getCurrentWaypoint())
                    && AiAutomateSupport.supportedQueue(unit);
            if (!moving) {
                tracks.remove(unit.objectId);
                continue;
            }
            AiAutomate.Claim claim = runtime.claim(unit);
            if (claim != null && claim.rule != id()) continue; // 其他规则正在微操，别抢
            eligible++;
            Track track = tracks.get(unit.objectId);
            if (track == null) {
                tracks.put(unit.objectId, new Track(unit.posX, unit.posY, tick));
                continue;
            }
            if (AiAutomateSupport.distance(unit.posX, unit.posY, track.x, track.y) > MOVED_ENOUGH) {
                track.x = unit.posX;
                track.y = unit.posY;
                track.lastProgressTick = tick;
                track.attempts = 0;
                continue;
            }
            if (tick - track.lastProgressTick < STUCK_TICKS || tick < track.nextAttemptTick) continue;
            if (!config.matches(unit)) {
                tracks.remove(unit.objectId);
                continue;
            }
            track.attempts++;
            if (track.attempts == 1) {
                if (runtime.repath(unit)) {
                    track.nextAttemptTick = tick + RETRY_TICKS;
                    runtime.trigger(id(), "#" + unit.objectId + " 无位移，重发原指令");
                } else {
                    tracks.remove(unit.objectId);
                }
            } else if (track.attempts <= 3) {
                PointF side = sidestep(runtime, unit);
                if (side == null) {
                    track.nextAttemptTick = tick + RETRY_TICKS;
                    continue;
                }
                UnitCommand micro = new UnitCommand();
                micro.setMoveTarget(side.x, side.y);
                if (runtime.apply(unit, id(), micro, true)) {
                    track.nextAttemptTick = tick + RETRY_TICKS;
                    runtime.trigger(id(), "#" + unit.objectId + " 侧移脱困");
                }
            } else {
                runtime.trigger(id(), "#" + unit.objectId + " 无法脱困（"
                        + Math.round((tick - track.lastProgressTick) / 60f) + " 秒无位移），暂时放弃");
                track.attempts = 0;
                track.lastProgressTick = tick;
                track.nextAttemptTick = tick + GIVE_UP_TICKS;
            }
        }
        tracks.keySet().retainAll(alive);
        runtime.record(id(), eligible);
    }

    /** 垂直到当前路径方向的一次短侧移，撞墙或水陆不通就换另一侧。 */
    private static PointF sidestep(AiAutomate runtime, OrderableUnit unit) {
        UnitCommand current = unit.getCurrentWaypoint();
        if (current == null) return null;
        float dx = current.getTargetX() - unit.posX;
        float dy = current.getTargetY() - unit.posY;
        float length = (float) Math.hypot(dx, dy);
        if (length < 1f) return null;
        float step = Math.max(40f, unit.radius * 3f);
        for (int sign : new int[] {1, -1}) {
            float x = unit.posX - dy / length * step * sign;
            float y = unit.posY + dx / length * step * sign;
            if (runtime.canStand(unit, x, y)) return new PointF(x, y);
        }
        return null;
    }
}

/** 默认只提醒空闲工厂；配置 includeUnits 后也提醒符合筛选的部队，不下达任何命令。 */
final class IdleAlertRule implements AiAutomateRule {
    private static final long GLOBAL_KEY = Long.MIN_VALUE;

    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.IDLE_ALERT;
    }

    @Override
    public void update(AiAutomate runtime) {
        AiAutomateRuleConfig config = runtime.config(id());
        int idleUnits = 0;
        for (OrderableUnit unit : runtime.friends()) {
            if (unit.isDead || unit.buildProgress < 1f) continue;
            if (unit.r().isBuildingUnit()) {
                if (!(unit instanceof FactoryQueueInterface)) continue;
                if (!((FactoryQueueInterface) unit).dy()) continue;
                if (!runtime.cooled(unit.objectId, id())) continue;
                runtime.stamp(unit.objectId, id());
                runtime.trigger(id(), "工厂 #" + unit.objectId + " 队列为空");
                continue;
            }
            if (!config.includeUnits || !config.matches(unit)) continue;
            if (unit.canAttack() && AiAutomateRuleConfig.isIdle(unit)
                    && !AiAutomateRuleConfig.isBuilder(unit)) idleUnits++;
        }
        if (idleUnits > 0 && runtime.cooled(GLOBAL_KEY, id())) {
            runtime.stamp(GLOBAL_KEY, id());
            runtime.trigger(id(), idleUnits + " 个攻击单位空闲");
        }
        runtime.record(id(), idleUnits);
    }
}
