package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.AttackMode;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 智能炮塔：只接管我方已完工、能攻击的固定炮塔，按炮塔类型重新分配目标。
 *
 * <p>“智能炮塔”不是新的开火逻辑：炮塔仍由原版武器冷却、射程与弹道决定能否命中，
 * 规则只负责把炮塔的攻击目标改成更值得打的那一个，并在目标消失/关闭规则时把控制权
 * 交回引擎的自动索敌。</p>
 *
 * <p>优先级固定，不允许 Agent 配置。目标一律取当前 HP（不是百分比）。</p>
 */
final class TurretRule implements AiAutomateRule {
    /** 炮塔行为分类，决定“谁更值得打”。 */
    enum Kind {
        /** 只对空的防空塔。 */
        AIR_DEFENSE,
        /** 机枪 / 喷火 / 基础炮塔：直接打最低血。 */
        GUN,
        /** T2、T3 火炮塔：优先打正在攻击自己的单位。 */
        ARTILLERY,
        /** T1、T2 电击塔：先打机枪/火炮机甲，再打其他机甲，最后非机甲。 */
        LIGHTNING_LOW,
        /** T3 电击塔：先打最高血的机甲，再打其他单位中最高血的。 */
        LIGHTNING_HIGH,
        /** 不是可识别的炮塔。 */
        NONE
    }

    /** 一个候选目标及其用于排序的属性；拆出来便于单独测试排序逻辑。 */
    static final class Candidate {
        final BaseUnit unit;
        final float hp;
        final boolean mech;
        final boolean mechKey;
        final boolean transport;
        final boolean gunship;
        final boolean bomber;
        final boolean attacker;

        Candidate(BaseUnit unit, float hp, boolean mech, boolean mechKey, boolean transport,
                  boolean gunship, boolean bomber, boolean attacker) {
            this.unit = unit;
            this.hp = hp;
            this.mech = mech;
            this.mechKey = mechKey;
            this.transport = transport;
            this.gunship = gunship;
            this.bomber = bomber;
            this.attacker = attacker;
        }
    }

    @Override
    public AiAutomateRuleId id() {
        return AiAutomateRuleId.TURRET;
    }

    @Override
    public void update(AiAutomate runtime) {
        PlayerTeam team = runtime.team();
        int eligible = 0;
        for (OrderableUnit turret : runtime.friends()) {
            if (turret.isDead || turret.buildProgress < 1f) continue;
            if (!AiAutomateSupport.isTurret(turret)) continue;
            if (turret.attackMode == AttackMode.holdFire) continue;
            if (!AiAutomateSupport.supportedQueue(turret)) continue;
            AiAutomate.Visit visit = runtime.visit(turret, id(), true);
            if (!visit.ready || !runtime.mayUse(turret, id())) continue;
            eligible++;

            Kind kind = kindOf(turret);
            if (kind == Kind.NONE) continue;
            BaseUnit target = chooseTarget(runtime, turret, team, kind);
            if (target == null) {
                // 没有合法目标：撤掉我们加的锁定，交回原版索敌。
                CombatRules.releaseOwn(runtime, visit, turret, id());
                continue;
            }
            // 只和“我们自己上次锁定的目标”比较：原版索敌可能每帧换 attackTarget，
            // 若拿它做比较会误判成需要重新锁定。
            AiAutomate.Claim claim = runtime.claim(turret);
            BaseUnit locked = claim == null || claim.micro == null
                    ? turret.getCommandOrAttackTarget()
                    : claim.micro.getTargetUnit();
            if (locked == target) {
                runtime.keep(turret);
                continue;
            }
            UnitCommand micro = new UnitCommand();
            micro.setAttackTarget(target);
            if (runtime.apply(turret, id(), micro)) {
                runtime.trigger(id(), "#" + turret.objectId + " 锁定 #" + target.objectId
                        + "（" + kind.name().toLowerCase(Locale.ROOT) + "）");
            }
        }
        runtime.record(id(), eligible);
    }

    // ------------------------------------------------------------------ 目标分类

    static Kind kindOf(OrderableUnit turret) {
        String id = typeId(turret);
        String display = turret.r().getUnitTypeDescriptionShort();
        String haystack = display == null ? id : id + " " + display.toLowerCase(Locale.ROOT);
        return kindOf(haystack, AiAutomateSupport.airOnlyTurret(turret),
                AiAutomateSupport.turretTier(turret));
    }

    /** 纯函数版本：{@code typeText} 可以是内部 id，也可以带上本地化名称。 */
    static Kind kindOf(String typeText, boolean airOnly, int tier) {
        String text = typeText == null ? "" : typeText.toLowerCase(Locale.ROOT);
        if (airOnly) return Kind.AIR_DEFENSE;
        if (text.contains("lightning") || text.contains("闪电") || text.contains("特斯拉")) {
            return tier >= 3 ? Kind.LIGHTNING_HIGH : Kind.LIGHTNING_LOW;
        }
        if (text.contains("artillery") || text.contains("火炮")) return Kind.ARTILLERY;
        if (text.contains("flame") || text.contains("喷火")) return Kind.GUN;
        if (text.contains("turret") || text.contains("炮塔")) return Kind.GUN;
        return Kind.NONE;
    }

    // ------------------------------------------------------------------ 目标选择

    private static BaseUnit chooseTarget(AiAutomate runtime, OrderableUnit turret, PlayerTeam team,
                                         Kind kind) {
        List<Candidate> candidates = new ArrayList<>();
        BaseUnit recent = turret.q(5f);
        for (BaseUnit enemy : runtime.enemies()) {
            if (!validTarget(turret, enemy, team)) continue;
            candidates.add(candidate(enemy, enemy == recent || isAttacking(enemy, turret)));
        }
        return best(candidates, kind);
    }

    static Candidate candidate(BaseUnit enemy, boolean attacker) {
        String id = typeId(enemy);
        boolean mech = id.contains("mech");
        boolean mechKey = mech && (id.contains("minigun") || id.contains("artillery"));
        boolean transport = id.contains("dropship") || id.contains("transport");
        boolean gunship = id.contains("gunship");
        boolean bomber = id.contains("bomber");
        return new Candidate(enemy, enemy.currentHealth, mech, mechKey, transport, gunship, bomber,
                attacker);
    }

    /** 是否可以合法攻击：可见、敌对、存活、在射程内且武器现在真的能打它。 */
    private static boolean validTarget(OrderableUnit turret, BaseUnit enemy, PlayerTeam team) {
        return enemy != null && !enemy.isDead && enemy.team != null && team.c(enemy.team)
                && enemy.d(team) && !enemy.t()
                && turret.b(enemy, true)
                && turret.isWithinEngagementRange(enemy)
                && turret.canEngageTargetNow(enemy);
    }

    private static boolean isAttacking(BaseUnit enemy, OrderableUnit turret) {
        if (!(enemy instanceof OrderableUnit)) return false;
        return ((OrderableUnit) enemy).getCommandOrAttackTarget() == turret;
    }

    static BaseUnit best(List<Candidate> candidates, Kind kind) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate == null) continue;
            if (best == null || compare(candidate, best, kind) < 0) best = candidate;
        }
        return best == null ? null : best.unit;
    }

    /** 返回负数表示 {@code a} 更值得打。 */
    static int compare(Candidate a, Candidate b, Kind kind) {
        switch (kind) {
            case AIR_DEFENSE: {
                int rankA = airRank(a);
                int rankB = airRank(b);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                return Float.compare(a.hp, b.hp);
            }
            case ARTILLERY: {
                if (a.attacker != b.attacker) return a.attacker ? -1 : 1;
                return Float.compare(a.hp, b.hp);
            }
            case LIGHTNING_LOW: {
                int rankA = mechRank(a);
                int rankB = mechRank(b);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                return Float.compare(a.hp, b.hp);
            }
            case LIGHTNING_HIGH: {
                if (a.mech != b.mech) return a.mech ? -1 : 1;
                return Float.compare(b.hp, a.hp);
            }
            case GUN:
            case NONE:
            default:
                return Float.compare(a.hp, b.hp);
        }
    }

    /** 防空塔：运输机 → 武装直升机 → 轰炸机 → 其他空中单位。 */
    private static int airRank(Candidate candidate) {
        if (candidate.transport) return 0;
        if (candidate.gunship) return 1;
        if (candidate.bomber) return 2;
        return 3;
    }

    /** 电击塔：机枪/火炮机甲 → 其他机甲 → 非机甲。 */
    private static int mechRank(Candidate candidate) {
        if (candidate.mechKey) return 0;
        if (candidate.mech) return 1;
        return 2;
    }

    static String typeId(BaseUnit unit) {
        String id = unit.r().v();
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }
}
