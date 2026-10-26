package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一条自动化规则的配置：开关、作用范围、数值阈值和抢占属性。
 *
 * <p>字段语义固定，但每条规则只使用其中一部分（例如 {@code dive} 不看 {@code --hp}）。
 * {@code auto-list} 会把规则实际使用的字段标出来，避免 Agent 以为设置生效了。</p>
 */
public final class AiAutomateRuleConfig {
    public static final String SELECTOR_ALL_ARMY = "all-army";
    public static final String SELECTOR_ALL_UNITS = "all-units";

    public final AiAutomateRuleId id;

    public boolean enabled;
    /** {@code --units}：逗号分隔的 id、{@code all-army} 或 {@code all-units}；空表示按规则默认范围。 */
    public String units = "";
    public List<Long> excludedIds = new ArrayList<>();
    /** {@code --type}：单位类型内部 id 或名称片段。 */
    public List<String> includeTypes = new ArrayList<>();
    /** {@code --exclude} 里的非数字项：排除这些类型。 */
    public List<String> excludeTypes = new ArrayList<>();
    /** 血量阈值（百分比）。retreat/mech 当作触发线，其余规则当作范围筛选。 */
    public Float hpPercent;
    public boolean idleOnly;
    public boolean excludeBuilders;
    /** idle-alert 是否包含部队；默认只提醒空闲工厂。 */
    public boolean includeUnits;
    /** 规则作用区域中心；{@code guard} 同时把它当作集结点。 */
    public Float x;
    public Float y;
    /** 世界坐标半径：作用范围、搜索范围或回撤距离，由规则决定。 */
    public Float radius;
    public int priority;
    /** 同一单位两次发令的最小间隔，防止抖动和刷屏。 */
    public int cooldownMillis;
    /** 临时接管结束后是否恢复接管前的任务快照。 */
    public boolean resume = true;

    /** {@link #explicitUnitIds()} 的解析缓存，避免每个单位每轮都重新拆分字符串。 */
    private List<Long> unitsCache;
    private String unitsCacheKey;
    /** {@code group:<name>} 的当前成员；每次采样由运行时刷新。 */
    private List<Long> groupMembers = Collections.emptyList();

    private static final String GROUP_PREFIX = "group:";

    private AiAutomateRuleConfig(AiAutomateRuleId id) {
        this.id = id;
        this.priority = id.defaultPriority;
    }

    /** 规则的出厂默认值。 */
    public static AiAutomateRuleConfig defaults(AiAutomateRuleId id) {
        AiAutomateRuleConfig config = new AiAutomateRuleConfig(id);
        switch (id) {
            case ASSIST_COUNTER:
                config.units = SELECTOR_ALL_UNITS;
                config.cooldownMillis = 0;
                break;
            case RETREAT:
                config.hpPercent = 20f;
                config.radius = 300f;
                // 去重已经能挡住重复指令；冷却保持 0，避免拖慢真正的方向变化。
                config.cooldownMillis = 0;
                break;
            case UNSTUCK:
                config.radius = 900f;
                config.cooldownMillis = 2000;
                break;
            case DIVE:
                config.cooldownMillis = 1000;
                break;
            case MECH:
                config.hpPercent = 40f;
                config.radius = 320f;
                config.cooldownMillis = 0;
                break;
            case KITE:
                config.radius = 400f;
                config.cooldownMillis = 0;
                break;
            case ARTILLERY:
                config.cooldownMillis = 0;
                break;
            case FOCUS_FIRE:
                config.radius = 420f;
                config.cooldownMillis = 1000;
                break;
            case GUARD:
                config.radius = 300f;
                config.cooldownMillis = 500;
                break;
            case ASSIST_BUILD:
                config.radius = 220f;
                config.cooldownMillis = 500;
                config.idleOnly = true;
                break;
            case REPAIR:
                config.radius = 220f;
                config.cooldownMillis = 500;
                config.idleOnly = true;
                break;
            case IDLE_ALERT:
                config.cooldownMillis = 5000;
                break;
            default:
                break;
        }
        return config;
    }

    public AiAutomateRuleConfig copy() {
        AiAutomateRuleConfig copy = new AiAutomateRuleConfig(id);
        copy.enabled = enabled;
        copy.units = units;
        copy.excludedIds = new ArrayList<>(excludedIds);
        copy.includeTypes = new ArrayList<>(includeTypes);
        copy.excludeTypes = new ArrayList<>(excludeTypes);
        copy.hpPercent = hpPercent;
        copy.idleOnly = idleOnly;
        copy.excludeBuilders = excludeBuilders;
        copy.includeUnits = includeUnits;
        copy.x = x;
        copy.y = y;
        copy.radius = radius;
        copy.priority = priority;
        copy.cooldownMillis = cooldownMillis;
        copy.resume = resume;
        copy.groupMembers = groupMembers;
        return copy;
    }

    /** 规则是否把 {@code --hp} 当作触发阈值（而不是范围筛选）。 */
    public boolean hpIsTrigger() {
        return id == AiAutomateRuleId.RETREAT || id == AiAutomateRuleId.MECH;
    }

    /** 冷却换算成游戏 tick（按 60 tick/s 估算，只用于限速，不需要绝对精确）。 */
    public int cooldownTicks() {
        if (cooldownMillis <= 0) return 0;
        return Math.max(1, Math.round(cooldownMillis * 60f / 1000f));
    }

    public List<Long> explicitUnitIds() {
        String raw = units == null ? "" : units.trim();
        // 编组筛选：成员由运行时按编组当前内容刷新，不在字符串里展开。
        if (raw.toLowerCase(Locale.ROOT).startsWith(GROUP_PREFIX)) return groupMembers;
        if (unitsCache != null && raw.equals(unitsCacheKey)) return unitsCache;
        if (raw.isEmpty() || raw.equalsIgnoreCase(SELECTOR_ALL_ARMY)
                || raw.equalsIgnoreCase(SELECTOR_ALL_UNITS)) {
            unitsCache = Collections.emptyList();
            unitsCacheKey = raw;
            return unitsCache;
        }
        List<Long> ids = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException notId) {
                throw new IllegalArgumentException(
                        "--units must be a comma-separated id list, all-army or all-units; got '"
                                + value + "'");
            }
        }
        unitsCache = ids;
        unitsCacheKey = raw;
        return ids;
    }

    /** {@code units} 是否用了 {@code group:<name>} 形式。 */
    public boolean usesGroup() {
        return units != null && units.trim().toLowerCase(Locale.ROOT).startsWith(GROUP_PREFIX);
    }

    /** {@code group:<name>} 里的编组名；未使用编组时返回空串。 */
    public String groupName() {
        String raw = units == null ? "" : units.trim();
        return usesGroup() ? raw.substring(GROUP_PREFIX.length()).trim() : "";
    }

    /** 用编组当前成员覆盖筛选范围；null 表示编组已不存在（该规则本轮不匹配任何单位）。 */
    public void applyGroupMembers(List<Long> members) {
        groupMembers = members == null
                ? Collections.<Long>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(members));
    }

    /**
     * 单位是否落在这条规则的作用范围内。只看静态条件，不读敌人或战斗状态。
     */
    public boolean matches(OrderableUnit unit) {
        return matchesScope(unit) && withinArea(unit);
    }

    /** 除区域半径以外的全部范围条件；{@code guard} 用它配合自己的“牵引范围”。 */
    public boolean matchesScope(OrderableUnit unit) {
        if (unit == null || unit.isDead) return false;
        if (usesGroup() && groupMembers.isEmpty()) return false;
        if (excludedIds.contains(unit.objectId)) return false;
        List<Long> ids = explicitUnitIds();
        if (!ids.isEmpty() && !ids.contains(unit.objectId)) return false;
        if (!unitsAllowedByKind(unit)) return false;
        if (!includeTypes.isEmpty() && !matchesTypes(unit, includeTypes)) return false;
        if (!excludeTypes.isEmpty() && matchesTypes(unit, excludeTypes)) return false;
        if (idleOnly && !isIdle(unit)) return false;
        if (excludeBuilders && isBuilder(unit)) return false;
        if (!hpIsTrigger() && hpPercent != null && healthPercent(unit) > hpPercent) return false;
        return true;
    }

    /** 是否位于配置的圆形区域（未配置中心或半径时恒为 true）。 */
    public boolean withinArea(OrderableUnit unit) {
        if (radius == null || radius <= 0 || x == null || y == null) return true;
        return distance(unit.posX, unit.posY, x, y) <= radius;
    }

    /** {@code all-army} 只取非建筑；{@code all-units} 允许建筑（例如修复湾）。 */
    private boolean unitsAllowedByKind(OrderableUnit unit) {
        String raw = units == null ? "" : units.trim().toLowerCase(Locale.ROOT);
        if (raw.equals(SELECTOR_ALL_UNITS)) return true;
        return !unit.r().isBuildingUnit();
    }

    static boolean matchesTypes(OrderableUnit unit, List<String> names) {
        if (names == null || names.isEmpty()) return false;
        String internal = unit.r().v().toLowerCase(Locale.ROOT);
        String display = unit.r().getUnitTypeDescriptionShort();
        String loweredDisplay = display == null ? "" : display.toLowerCase(Locale.ROOT);
        for (String name : names) {
            String needle = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) continue;
            if (internal.equals(needle) || internal.contains(needle)) return true;
            if (!loweredDisplay.isEmpty() && loweredDisplay.contains(needle)) return true;
        }
        return false;
    }

    public static boolean isIdle(OrderableUnit unit) {
        return unit.getWaypointCount() == 0 && unit.getCommandOrAttackTarget() == null;
    }

    /** 建造者判定与 CLI 的 {@code --exclude-builders} 保持一致：能放置建筑的动作。 */
    public static boolean isBuilder(BaseUnit unit) {
        if (!(unit instanceof OrderableUnit)) return false;
        for (AbstractUnitAction action : ((OrderableUnit) unit).getAvailableActions()) {
            if (action.getActionType() == ActionType.placeBuilding) return true;
        }
        return false;
    }

    public static float healthPercent(BaseUnit unit) {
        if (unit.maxHealth <= 0) return 100f;
        return unit.currentHealth * 100f / unit.maxHealth;
    }

    public static float distance(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }
}
