package com.corrodinggames.rts.game.ai.custom.ai_automate;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.UnitCommandType;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.gameFramework.Command;
import com.corrodinggames.rts.gameFramework.GameEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 我方自动化执行层：把「实时反射」交给规则，把战略决策留给 Agent。
 *
 * <p>该类是整个自动化子系统的唯一入口，提供三件事：</p>
 * <ol>
 *   <li>规则注册表：固定规则各自定义开关、作用范围、优先级、冷却和恢复策略；</li>
 *   <li>统一控制权：同一单位同一时间只归一条规则，高优先级可接管低优先级，接管时沿用
 *       后者保存的原任务快照；</li>
 *   <li>安全恢复：恢复前校验指令版本（队列是否仍是本模块写入的那一份），版本变了说明
 *       Agent 已改令，此时只放弃接管，绝不把旧指令写回去。</li>
 * </ol>
 *
 * <p>所有改动都通过可回放的 {@code Command} 下发；本类不生产、不扩张、不选择战略目标。</p>
 */
public final class AiAutomate {
    /** 采样节流：每 N 个游戏 tick 才做一次决策。 */
    private static final int UPDATE_FRAMES = 10;
    /** 武器冷却低于该值就认为“即将就绪”，用于火炮类停留瞄准。 */
    static final float READY_FRAMES = 20;
    private static final int RECENT_LIMIT = 60;

    /** 地形可行性回调；测试可以替换它，避免依赖真实地图。 */
    @FunctionalInterface
    public interface Terrain {
        boolean canStand(OrderableUnit unit, float x, float y);
        default boolean canReach(OrderableUnit unit, float x, float y) {
            return canStand(unit, x, y);
        }
    }

    /** 编组查询回调：{@code --group} 的作用范围每次采样都按编组当前成员刷新。 */
    @FunctionalInterface
    public interface GroupLookup {
        /** 编组当前成员 id；编组不存在时返回 null。 */
        List<Long> membersOf(String name);
    }

    private GroupLookup groupLookup = name -> null;

    private final Map<Long, Claim> claims = new HashMap<>();
    // 手动发令后持续让权；只有显式 auto-set 才重新授权对应规则，不能下一轮又抢回来。
    private final Map<OrderableUnit, EnumSet<AiAutomateRuleId>> manualOverrides = new HashMap<>();
    // 包含动作、脱困重发和释放后的恢复批次，不能只追踪仍有 Claim 的命令。
    private final Map<Command, OrderableUnit> issuedCommands = new HashMap<>();
    private final Map<Command, AiAutomateRuleId> commandRules = new HashMap<>();
    private final Set<Command> restorations = new HashSet<>();
    private final Map<AiAutomateRuleId, Entry> entries = new LinkedHashMap<>();
    private final Map<AiAutomateRuleId, Map<Long, Integer>> stamps = new EnumMap<>(AiAutomateRuleId.class);
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Terrain terrain;

    private GameEngine world;
    private Object map;
    private PlayerTeam owner;
    private int lastTick = -1;
    private int lastUpdateTick = -1;
    private int commands;
    private int triggers;

    private final List<OrderableUnit> friends = new ArrayList<>();
    private final List<BaseUnit> enemies = new ArrayList<>();
    private final List<BaseUnit> visible = new ArrayList<>();
    private int passTick;
    private int passTimeMillis;

    public AiAutomate() {
        this(new Terrain() {
            @Override public boolean canStand(OrderableUnit unit, float x, float y) {
                return AiAutomateSupport.canStand(unit, x, y);
            }
            @Override public boolean canReach(OrderableUnit unit, float x, float y) {
                return AiAutomateSupport.canReach(unit, x, y);
            }
        });
    }

    AiAutomate(Terrain terrain) {
        this.terrain = terrain;
        entries.put(AiAutomateRuleId.ARTILLERY, new Entry(new ArtilleryRule()));
        entries.put(AiAutomateRuleId.TURRET, new Entry(new TurretRule()));
        entries.put(AiAutomateRuleId.DIVE, new Entry(new DiveRule()));
        entries.put(AiAutomateRuleId.RETREAT, new Entry(new RetreatRule()));
        entries.put(AiAutomateRuleId.MECH, new Entry(new MechRule()));
        entries.put(AiAutomateRuleId.GUARD, new Entry(new GuardRule()));
        entries.put(AiAutomateRuleId.REPAIR, new Entry(new RepairRule()));
        entries.put(AiAutomateRuleId.ASSIST_BUILD, new Entry(new AssistBuildRule()));
        entries.put(AiAutomateRuleId.UNSTUCK, new Entry(new UnstuckRule()));
        entries.put(AiAutomateRuleId.KITE, new Entry(new KiteRule()));
        entries.put(AiAutomateRuleId.FOCUS_FIRE, new Entry(new FocusFireRule()));
        entries.put(AiAutomateRuleId.IDLE_ALERT, new Entry(new IdleAlertRule()));
        entries.put(AiAutomateRuleId.ASSIST_COUNTER, new Entry(new AssistCounterRule()));
    }

    // ------------------------------------------------------------------ 旧版兼容入口

    /** 编组查询注入点；服务层用它把 {@code --group} 解析成编组当前成员。 */
    public void setGroupLookup(GroupLookup lookup) {
        this.groupLookup = lookup == null ? name -> null : lookup;
    }

    /** Agent 的显式指令优先：立刻放弃这些单位上的接管，并取消尚未执行的微操。 */
    public void yieldToOrders(GameEngine engine, List<OrderableUnit> units) {
        yieldToOrders(engine, units, false);
    }

    /** 追加任务/纯动作先撤掉临时微操，恢复原任务；调用方把返回批次排在手动命令前。 */
    public List<Command> yieldToOrders(GameEngine engine, List<OrderableUnit> units, boolean restore) {
        if (!units.isEmpty()) synchronizeWorld(engine, units.get(0).team);
        List<Command> restored = new ArrayList<>();
        for (OrderableUnit unit : units) {
            Claim claim = claims.get(unit.objectId);
            if (restore && claim == null) {
                // 规则刚关闭时恢复批次可能尚未执行；排队手动任务应沿用这个原任务前缀。
                for (List<Command> queue : java.util.Arrays.asList(
                        engine.commandController.pendingCommands, engine.commandController.queuedCommands)) {
                    for (Command command : queue) {
                        if (restorations.contains(command) && issuedCommands.get(command) == unit) {
                            restored.add(command);
                            issuedCommands.remove(command);
                            commandRules.remove(command);
                            restorations.remove(command);
                        }
                    }
                }
            }
            cancelIssued(unit);
            claims.remove(unit.objectId);
            if (restore && claim != null && ownsOrders(unit, claim)) {
                if (claim.resume.isEmpty()) restored.add(clearOrders(unit, claim.rule));
                else {
                    issueBatch(claim, null);
                    restored.addAll(claim.pending);
                }
            }
            manualOverrides.put(unit, EnumSet.allOf(AiAutomateRuleId.class));
            trigger(claim == null ? null : claim.rule,
                    "#" + unit.objectId + " 收到 Agent 指令，持续交还控制权，auto-set 可重新授权");
        }
        // 这些恢复指令现已成为手动追加任务的前缀，后续 --queue 不能再撤销它们。
        for (Command command : restored) {
            issuedCommands.remove(command);
            commandRules.remove(command);
        }
        lastUpdateTick = engine.currentTick;
        return restored;
    }

    private void cancelIssued(OrderableUnit unit) {
        if (world == null) return;
        issuedCommands.entrySet().removeIf(entry -> {
            if (entry.getValue() != unit) return false;
            world.commandController.pendingCommands.remove(entry.getKey());
            world.commandController.queuedCommands.remove(entry.getKey());
            commandRules.remove(entry.getKey());
            restorations.remove(entry.getKey());
            return true;
        });
    }

    boolean manuallyOverridden(OrderableUnit unit, AiAutomateRuleId rule) {
        Set<AiAutomateRuleId> blocked = manualOverrides.get(unit);
        return blocked != null && blocked.contains(rule);
    }

    // ------------------------------------------------------------------ 规则配置

    /** 应用一条 {@code auto-set} 补丁并返回规则列表。 */
    public AiAutomateReport set(GameEngine engine, PlayerTeam team, AiAutomateRuleOptions options) {
        synchronizeWorld(engine, team);
        if (options == null || options.rule == null) {
            throw new IllegalArgumentException("auto-set requires --rule <" + AiAutomateRuleId.listIds() + ">");
        }
        if (options.includeUnits != null && options.rule != AiAutomateRuleId.IDLE_ALERT) {
            throw new IllegalArgumentException("--include-units is only supported by --rule idle-alert");
        }
        if ((options.rule == AiAutomateRuleId.TURRET || options.rule == AiAutomateRuleId.ASSIST_COUNTER)
                && hasScopeOptions(options)) {
            // 智能炮塔默认作用于全部炮塔，只保留启用/关闭开关。
            throw new IllegalArgumentException("--rule " + options.rule.id + " only accepts --enabled true|false");
        }
        Entry entry = require(options.rule);
        AiAutomateRuleConfig previous = entry.config;
        AiAutomateRuleConfig config = previous.copy();

        // auto-set 的语义是“配置并开启”，只有显式 --enabled false 才关闭。
        config.enabled = options.enabled == null || options.enabled;
        if (options.units != null) config.units = options.units.trim();
        if (options.group != null && !options.group.trim().isEmpty()) {
            if (options.units != null && !options.units.trim().isEmpty()) {
                throw new IllegalArgumentException("--units and --group cannot be combined");
            }
            config.units = "group:" + options.group.trim();
        }
        if (options.excludedIds != null) config.excludedIds = new ArrayList<>(options.excludedIds);
        if (options.includeTypes != null) config.includeTypes = new ArrayList<>(options.includeTypes);
        if (options.excludeTypes != null) config.excludeTypes = new ArrayList<>(options.excludeTypes);
        if (options.hpPercent != null) {
            if (options.hpPercent <= 0f || options.hpPercent > 100f) {
                throw new IllegalArgumentException("--hp must be a percentage from 1 to 100");
            }
            config.hpPercent = options.hpPercent;
        }
        if (options.idleOnly != null) config.idleOnly = options.idleOnly;
        if (options.excludeBuilders != null) config.excludeBuilders = options.excludeBuilders;
        if (options.rule == AiAutomateRuleId.IDLE_ALERT) {
            if (options.includeUnits != null) config.includeUnits = options.includeUnits;
            else if (options.hasUnitFilter()) config.includeUnits = true;
        }
        if (options.x != null) config.x = options.x;
        if (options.y != null) config.y = options.y;
        if (options.radius != null) {
            if (options.radius < 0f) throw new IllegalArgumentException("--radius must not be negative");
            config.radius = options.radius;
        }
        // 编组筛选：立即解析一次，编组不存在就直接报错，并让 auto-list 马上能看到范围。
        if (config.usesGroup()) {
            List<Long> members = groupLookup.membersOf(config.groupName());
            if (members == null) {
                throw new IllegalArgumentException("Unknown unit group '" + config.groupName()
                        + "'; save it with " + "rwx-game-group-save --name <name> --units <ids>");
            }
            config.applyGroupMembers(members);
        }
        // 提前校验 --units，避免规则跑到一半才发现选择器写错。
        config.explicitUnitIds();
        if (options.rule == AiAutomateRuleId.GUARD && config.enabled
                && (config.x == null || config.y == null)) {
            throw new IllegalArgumentException(
                    "--rule guard requires --x and --y (the area to defend)");
        }
        if (options.rule == AiAutomateRuleId.RETREAT && config.hpPercent == null) {
            config.hpPercent = 20f;
        }
        entry.config = config;
        if (config.enabled) {
            // 空闲/血量是每轮的触发条件，不应阻止显式重新授权未来符合条件的单位。
            AiAutomateRuleConfig authorization = config.copy();
            authorization.idleOnly = false;
            if (!authorization.hpIsTrigger()) authorization.hpPercent = null;
            for (Map.Entry<OrderableUnit, EnumSet<AiAutomateRuleId>> override : manualOverrides.entrySet()) {
                if (authorization.matchesScope(override.getKey())) override.getValue().remove(options.rule);
            }
            manualOverrides.entrySet().removeIf(override -> override.getValue().isEmpty());
        }
        entry.state = config.enabled ? "运行中" : "未启用";
        if (!config.enabled || scopeChanged(previous, config)) {
            cancelRulePending(options.rule);
            entry.rule.release(this);
        }
        trigger(options.rule, (config.enabled ? "启用" : "关闭") + " " + describe(config));
        return report(engine, team, options.rule,
                options.rule.id + (config.enabled ? " 已启用" : " 已关闭"));
    }

    public AiAutomateReport list(GameEngine engine, PlayerTeam team) {
        synchronizeWorld(engine, team);
        int enabled = 0;
        for (Entry entry : entries.values()) if (entry.config.enabled) enabled++;
        return report(engine, team, null, "自动化规则：" + enabled + "/" + entries.size() + " 开启");
    }

    public AiAutomateReport show(GameEngine engine, PlayerTeam team, AiAutomateRuleId rule) {
        synchronizeWorld(engine, team);
        Entry entry = require(rule);
        return report(engine, team, rule,
                rule.id + (entry.config.enabled ? " 已启用" : " 未启用"));
    }

    /** {@code auto-clear --rule}：把一条规则恢复默认值并关闭。 */
    public AiAutomateReport clear(GameEngine engine, PlayerTeam team, AiAutomateRuleId rule) {
        synchronizeWorld(engine, team);
        if (rule == null) throw new IllegalArgumentException("auto-clear requires --rule <id>");
        Entry entry = require(rule);
        cancelRulePending(rule);
        entry.rule.release(this);
        entry.config = AiAutomateRuleConfig.defaults(rule);
        entry.state = "未启用";
        entry.eligible = 0;
        trigger(rule, "重置为默认值并关闭");
        return report(engine, team, rule, rule.id + " 已清除");
    }

    /**
     * {@code auto-reset}：释放所有受控单位、把所有规则恢复默认值（全部关闭）。
     *
     * <p>与 {@link #clear} 的区别是作用于全部规则，并且会清空每单位的冷却记录。</p>
     */
    public AiAutomateReport reset(GameEngine engine, PlayerTeam team) {
        synchronizeWorld(engine, team);
        for (OrderableUnit unit : new HashSet<>(issuedCommands.values())) cancelIssued(unit);
        for (Entry entry : entries.values()) {
            entry.rule.release(this);
            entry.config = AiAutomateRuleConfig.defaults(entry.config.id);
            entry.state = "未启用";
            entry.eligible = 0;
            entry.engaged = 0;
        }
        claims.clear();
        stamps.clear();
        trigger(null, "重置全部自动化规则并释放所有单位");
        return report(engine, team, null, "已重置全部自动化规则");
    }

    /** 除启用开关外，这次请求是否还带了作用范围/阈值参数。 */
    private static boolean hasScopeOptions(AiAutomateRuleOptions options) {
        return options.units != null
                || options.group != null
                || !options.excludedIds.isEmpty()
                || !options.includeTypes.isEmpty()
                || !options.excludeTypes.isEmpty()
                || options.hpPercent != null
                || options.idleOnly != null
                || options.excludeBuilders != null
                || options.x != null
                || options.y != null
                || options.radius != null;
    }

    private static boolean scopeChanged(AiAutomateRuleConfig before, AiAutomateRuleConfig after) {        return !before.units.equals(after.units)
                || !before.excludedIds.equals(after.excludedIds)
                || !before.includeTypes.equals(after.includeTypes)
                || !before.excludeTypes.equals(after.excludeTypes)
                || before.idleOnly != after.idleOnly
                || before.excludeBuilders != after.excludeBuilders
                || before.includeUnits != after.includeUnits
                || !equal(before.radius, after.radius)
                || !equal(before.x, after.x)
                || !equal(before.y, after.y);
    }

    private static boolean equal(Float first, Float second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String describe(AiAutomateRuleConfig config) {
        StringBuilder text = new StringBuilder(config.id.id);
        if (config.units != null && !config.units.isEmpty()) text.append(" units=").append(config.units);
        if (config.hpPercent != null) text.append(" hp=").append(Math.round(config.hpPercent));
        if (config.radius != null) text.append(" radius=").append(Math.round(config.radius));
        if (config.id == AiAutomateRuleId.IDLE_ALERT) text.append(" includeUnits=").append(config.includeUnits);
        text.append(" priority=").append(config.priority);
        if (config.cooldownMillis > 0) text.append(" cooldown=").append(config.cooldownMillis / 1000f).append('s');
        if (!config.resume) text.append(" resume=off");
        return text.toString();
    }

    // ------------------------------------------------------------------ 采样循环

    public void update(GameEngine engine, PlayerTeam team) {
        update(engine, team, new ArrayList<>(BaseUnit.bE));
    }

    void update(GameEngine engine, PlayerTeam team, List<BaseUnit> units) {
        synchronizeWorld(engine, team);
        if (!anyEnabled()) return;
        if (!engine.hasLoadedLevel || engine.isStopped || engine.isPaused
                || engine.gameSpeed == 0 || engine.replayEngine.j()
                || team.isTeamVictory || team.isTeamWipedOut) return;
        if (lastUpdateTick >= 0 && engine.currentTick - lastUpdateTick < UPDATE_FRAMES) return;
        lastUpdateTick = engine.currentTick;

        manualOverrides.keySet().removeIf(unit -> unit.isDead || unit.team != team);
        issuedCommands.keySet().removeIf(command -> !engine.commandController.pendingCommands.contains(command)
                && !engine.commandController.queuedCommands.contains(command));
        commandRules.keySet().retainAll(issuedCommands.keySet());
        restorations.retainAll(issuedCommands.keySet());

        // 绝不覆盖玩家/CLI 尚未执行完的指令；本模块自己的批次不算。
        Set<Command> ownPending = new HashSet<>();
        ownPending.addAll(issuedCommands.keySet());
        for (Command command : engine.commandController.pendingCommands) {
            if (!ownPending.contains(command) && command.team == team
                    && (command.unitCommand != null || command.clearExistingOrders)) return;
        }
        for (Command command : engine.commandController.queuedCommands) {
            if (!ownPending.contains(command) && command.team == team
                    && (command.unitCommand != null || command.clearExistingOrders)) return;
        }

        collectUnits(team, units);
        passTick = engine.currentTick;
        passTimeMillis = engine.gameTimeMillis;
        for (Claim claim : claims.values()) claim.fresh = false;

        // 编组筛选：每次采样都按编组当前成员刷新，编组后来新增的单位也会自动纳入。
        for (Entry entry : entries.values()) {
            if (entry.config.enabled && entry.config.usesGroup()) {
                entry.config.applyGroupMembers(groupLookup.membersOf(entry.config.groupName()));
            }
        }

        Set<AiAutomateRuleId> failed = new HashSet<>();
        for (Entry entry : entries.values()) entry.eligible = 0;
        for (Entry entry : byPriority()) {
            if (!entry.config.enabled) continue;
            try {
                entry.rule.update(this);
            } catch (RuntimeException error) {
                failed.add(entry.config.id);
                entry.state = "失败：" + error;
                trigger(entry.config.id, "规则异常：" + error.getMessage());
            }
        }

        int[] engaged = new int[AiAutomateRuleId.values().length];
        for (Claim claim : claims.values()) engaged[claim.rule.ordinal()]++;
        for (Entry entry : entries.values()) {
            entry.engaged = engaged[entry.config.id.ordinal()];
            if (!entry.config.enabled) {
                entry.state = "未启用";
            } else if (failed.contains(entry.config.id)) {
                // 保留本轮失败原因，下一轮正常时覆盖。
            } else if (entry.engaged > 0) {
                entry.state = "接管 " + entry.engaged + " 个单位";
            } else {
                entry.state = "运行中";
            }
        }

        List<OrderableUnit> stale = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (!claim.fresh) stale.add(claim.unit);
        }
        for (OrderableUnit unit : stale) release(unit, true);
    }

    private List<Entry> byPriority() {
        List<Entry> ordered = new ArrayList<>(entries.values());
        ordered.sort((first, second) -> {
            int byPriority = Integer.compare(second.config.priority, first.config.priority);
            return byPriority != 0 ? byPriority
                    : Integer.compare(first.config.id.ordinal(), second.config.id.ordinal());
        });
        return ordered;
    }

    private void collectUnits(PlayerTeam team, List<BaseUnit> units) {
        friends.clear();
        enemies.clear();
        visible.clear();
        for (BaseUnit unit : units) {
            if (unit == null || unit.isDead || unit.team == null) continue;
            if (unit.team == team) {
                if (unit instanceof OrderableUnit) {
                    friends.add((OrderableUnit) unit);
                    visible.add(unit);
                }
                continue;
            }
            // 只把“当前视野内的敌对单位”当敌人；盟友与中立（树、岩石）只作为避让障碍。
            if (!team.c(unit.team)) {
                if (unit.d(team)) visible.add(unit);
                continue;
            }
            if (!unit.d(team) || unit.t()) continue;
            if (unit.team.teamId < 0) continue;
            enemies.add(unit);
            visible.add(unit);
        }
    }

    // ------------------------------------------------------------------ 规则运行时接口

    AiAutomateRuleConfig config(AiAutomateRuleId id) {
        return require(id).config;
    }

    PlayerTeam team() {
        return owner;
    }

    List<OrderableUnit> friends() {
        return friends;
    }

    List<BaseUnit> enemies() {
        return enemies;
    }

    /** 视野内的全部单位（我方 + 敌方），用于避开其他单位。 */
    List<BaseUnit> visible() {
        return visible;
    }

    /**
     * 标准候选检查：处理“上一批还没执行完”“队列被 Agent 改写”“任务类型不允许接管”。
     *
     * @param allowPatrol 巡逻是否可被接管；火炮规则保持原行为（不接管巡逻）
     */
    Visit visit(OrderableUnit unit, AiAutomateRuleId rule, boolean allowPatrol) {
        if (manuallyOverridden(unit, rule)) return new Visit(null, false);
        Claim claim = claims.get(unit.objectId);
        if (claim == null && hasIssuedPending(unit)) return new Visit(null, false);
        if (claim != null) {
            if (hasPending(claim)) {
                claim.fresh = true;
                return new Visit(claim, false);
            }
            if (!ownsOrders(unit, claim)) {
                cancelPending(claim);
                claims.remove(unit.objectId);
                trigger(claim.rule, "#" + unit.objectId + " 指令被改写，停止接管");
                yieldToOrders(world, Collections.singletonList(unit));
                return new Visit(null, false);
            }
        }
        UnitCommand current = unit.getCurrentWaypoint();
        boolean managed = current == null
                || current.getCommandType() == UnitCommandType.attackMove
                || current.getCommandType() == UnitCommandType.guardAt
                || (allowPatrol && current.getCommandType() == UnitCommandType.patrol);
        if (claim == null && (!managed || !AiAutomateSupport.supportedQueue(unit))) {
            return new Visit(null, false);
        }
        return new Visit(claim, true);
    }

    /** 原样重发当前整条队列：强制引擎重新寻路，不改变任务本身。 */
    boolean repath(OrderableUnit unit) {
        if (manuallyOverridden(unit, AiAutomateRuleId.UNSTUCK)) return false;
        int count = unit.getWaypointCount();
        if (count == 0 || world == null) return false;
        List<UnitCommand> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) orders.add(AiAutomateSupport.copy(unit.getWaypointAt(i)));
        issue(unit, orders.get(0), false, AiAutomateRuleId.UNSTUCK);
        for (int i = 1; i < orders.size(); i++) issue(unit, orders.get(i), true, AiAutomateRuleId.UNSTUCK);
        return true;
    }

    /** {@link #visit} 的结果：{@code ready} 为 false 表示本轮不要为该单位做决策。 */
    static final class Visit {
        final Claim claim;
        final boolean ready;

        Visit(Claim claim, boolean ready) {
            this.claim = claim;
            this.ready = ready;
        }
    }

    int passTick() {
        return passTick;
    }

    int passTimeMillis() {
        return passTimeMillis;
    }

    Claim claim(OrderableUnit unit) {
        return unit == null ? null : claims.get(unit.objectId);
    }

    boolean hasPending(Claim claim) {
        if (claim == null || claim.pending.isEmpty() || world == null
                || world.commandController == null) return false;
        for (Command command : claim.pending) {
            if (command == null) continue;
            if (world.commandController.pendingCommands.contains(command)
                    || world.commandController.queuedCommands.contains(command)) return true;
        }
        return false;
    }

    private boolean hasIssuedPending(OrderableUnit unit) {
        if (world == null) return false;
        for (Map.Entry<Command, OrderableUnit> entry : issuedCommands.entrySet()) {
            if (entry.getValue() == unit
                    && (world.commandController.pendingCommands.contains(entry.getKey())
                    || world.commandController.queuedCommands.contains(entry.getKey()))) return true;
        }
        return false;
    }

    boolean ownsOrders(OrderableUnit unit, Claim claim) {
        return claim != null && (matchesQueue(unit, claim, true) || matchesQueue(unit, claim, false));
    }

    /** 本规则是否可以处置这个单位：没人接管、自己接管，或自己的优先级更高可以抢。 */
    boolean mayUse(OrderableUnit unit, AiAutomateRuleId rule) {
        if (manuallyOverridden(unit, rule)) return false;
        Claim claim = claims.get(unit.objectId);
        return claim == null || claim.rule == rule
                || config(rule).priority > config(claim.rule).priority;
    }

    /** 保持接管但本轮不发新命令（例如正在执行的批次还没落地）。 */
    void keep(OrderableUnit unit) {
        Claim claim = claims.get(unit.objectId);
        if (claim != null) claim.fresh = true;
    }

    /** 放弃接管且不恢复（外部指令已经改写队列时使用）。 */
    void drop(OrderableUnit unit) {
        Claim claim = claims.get(unit.objectId);
        if (claim == null) return;
        cancelPending(claim);
        claims.remove(unit.objectId);
    }

    boolean apply(OrderableUnit unit, AiAutomateRuleId rule, UnitCommand micro) {
        return apply(unit, rule, micro, false);
    }

    /**
     * @param allowUnmanaged 允许为 move/attack 等“非受管任务”建立接管快照。只有 unstuck 使用：
     *                       它重发的是完全相同的队列，不改变目的地，属于执行修复而不是意图覆盖。
     */
    boolean apply(OrderableUnit unit, AiAutomateRuleId rule, UnitCommand micro, boolean allowUnmanaged) {
        if (micro == null || manuallyOverridden(unit, rule)) return false;
        Claim claim = claims.get(unit.objectId);
        if (claim == null && hasIssuedPending(unit)) return false;
        if (claim != null) {
            if (hasPending(claim)) {
                claim.fresh = true;
                return false;
            }
            if (!ownsOrders(unit, claim)) {
                // 队列被 Agent/GUI 改写：放弃接管，绝不恢复过期任务。
                cancelPending(claim);
                claims.remove(unit.objectId);
                trigger(claim.rule, "#" + unit.objectId + " 指令被改写，停止接管");
                yieldToOrders(world, Collections.singletonList(unit));
                return false;
            } else if (claim.rule != rule) {
                AiAutomateRuleConfig mine = config(rule);
                AiAutomateRuleConfig theirs = config(claim.rule);
                if (theirs.priority >= mine.priority) return false;
                // 接管：保留原任务快照，只换执行者，避免恢复时丢失 Agent 指令。
                cancelPending(claim);
                claim.rule = rule;
                claim.micro = null;
                trigger(rule, "#" + unit.objectId + " 从 " + theirs.id.id + " 接管");
            }
        }
        if (claim == null) {
            if (!allowUnmanaged && !isManagedOrder(unit.getCurrentWaypoint())) return false;
            if (!AiAutomateSupport.supportedQueue(unit)) return false;
            claim = new Claim(unit, passTick, rule != AiAutomateRuleId.ASSIST_COUNTER);
            claim.rule = rule;
            claims.put(unit.objectId, claim);
        }
        claim.fresh = true;
        AiAutomateRuleConfig config = config(rule);
        if (claim.micro != null && claim.micro.getCommandType() == micro.getCommandType()
                && AiAutomateSupport.sameTarget(claim.micro, micro)) return false;
        if (!cooled(unit.objectId, rule)) return false;
        issueBatch(claim, micro);
        return true;
    }

    /**
     * 下发一个纯动作（例如潜艇 Dive/Surface）。动作不碰任务队列，因此不需要快照；
     * 冷却按单位记录，避免每轮重复下发同一个动作。
     */
    boolean action(OrderableUnit unit, AiAutomateRuleId rule, AbstractUnitAction action) {
        if (action == null || manuallyOverridden(unit, rule) || !cooled(unit.objectId, rule)) return false;
        issueAction(unit, rule, action);
        stamp(unit.objectId, rule);
        return true;
    }

    void release(OrderableUnit unit, boolean restore) {
        Claim claim = claims.get(unit.objectId);
        if (claim == null) return;
        cancelPending(claim);
        claims.remove(unit.objectId);
        if (!restore || !config(claim.rule).resume) return;
        if (unit.isDead || unit.team != owner || !(matchesQueue(unit, claim, true)
                || claim.rule == AiAutomateRuleId.ASSIST_COUNTER && matchesQueue(unit, claim, false))) return;
        // 建筑和原地恢复空闲的规则没有后续队列：撤掉微操，交回原版索敌。
        if (claim.resume.isEmpty()) {
            restorations.add(clearOrders(unit, claim.rule));
            return;
        }
        issueBatch(claim, null);
        restorations.addAll(claim.pending);
    }

    /** 清空单位的 waypoint 与攻击目标，用于把建筑交还给引擎的自动索敌。 */
    private Command clearOrders(OrderableUnit unit, AiAutomateRuleId rule) {
        if (world == null) return null;
        Command command = world.commandController.newCommandForTeam(unit.team);
        if (world.networkEngine.networkGameActive) command.sourceTeam = unit.team;
        command.addUnitToCommand(unit);
        command.setClearExistingOrders();
        issuedCommands.put(command, unit);
        commandRules.put(command, rule);
        commands++;
        return command;
    }

    void releaseAll(AiAutomateRuleId rule, boolean restore) {
        List<OrderableUnit> owned = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.rule == rule) owned.add(claim.unit);
        }
        for (OrderableUnit unit : owned) release(unit, restore);
    }

    void record(AiAutomateRuleId rule, int eligible) {
        require(rule).eligible = eligible;
    }

    void trigger(AiAutomateRuleId rule, String message) {
        Entry entry = rule == null ? null : entries.get(rule);
        if (entry != null) entry.triggers++;
        triggers++;
        String label = rule == null ? "auto" : rule.id;
        long time = world == null ? 0L : world.gameTimeMillis;
        String line = AiAutomateSupport.stamp(time) + " [" + label + "] " + message;
        recent.addFirst(line);
        while (recent.size() > RECENT_LIMIT) recent.removeLast();
    }

    void setState(AiAutomateRuleId rule, String state) {
        Entry entry = entries.get(rule);
        if (entry != null) entry.state = state;
    }

    /** 该单位在这条规则上是否已经过了冷却。 */
    boolean cooled(long unitId, AiAutomateRuleId rule) {
        Map<Long, Integer> ruleStamps = stamps.get(rule);
        if (ruleStamps == null) return true;
        Integer last = ruleStamps.get(unitId);
        return last == null || passTick - last >= config(rule).cooldownTicks();
    }

    void stamp(long unitId, AiAutomateRuleId rule) {
        Map<Long, Integer> ruleStamps = stamps.get(rule);
        if (ruleStamps == null) {
            ruleStamps = new HashMap<>();
            stamps.put(rule, ruleStamps);
        }
        ruleStamps.put(unitId, passTick);
    }

    // ------------------------------------------------------------------ 队列快照 / 发令

    private void issueBatch(Claim claim, UnitCommand micro) {
        claim.pending.clear();
        if (micro != null) claim.pending.add(issue(claim.unit, micro, false, claim.rule));
        for (int i = 0; i < claim.resume.size(); i++) {
            claim.pending.add(issue(claim.unit, claim.resume.get(i), micro != null || i > 0, claim.rule));
        }
        claim.micro = micro;
        claim.lastIssueTick = passTick;
    }

    private void issueAction(OrderableUnit unit, AiAutomateRuleId rule, AbstractUnitAction action) {
        if (world == null) return;
        Command command = world.commandController.newCommandForTeam(unit.team);
        if (world.networkEngine.networkGameActive) command.sourceTeam = unit.team;
        command.addUnitToCommand(unit);
        command.setActionTarget(action.getActionId(), null, null);
        issuedCommands.put(command, unit);
        commandRules.put(command, rule);
        commands++;
    }

    private Command issue(OrderableUnit unit, UnitCommand order, boolean queued, AiAutomateRuleId rule) {
        Command command = world.commandController.newCommandForTeam(unit.team);
        if (world.networkEngine.networkGameActive) command.sourceTeam = unit.team;
        command.addUnitToCommand(unit);
        command.unitCommand = AiAutomateSupport.copy(order);
        command.isQueued = queued;
        issuedCommands.put(command, unit);
        commandRules.put(command, rule);
        commands++;
        return command;
    }

    private void cancelPending(Claim claim) {
        if (world != null && world.commandController != null) {
            world.commandController.pendingCommands.removeAll(claim.pending);
            world.commandController.queuedCommands.removeAll(claim.pending);
        }
        claim.pending.clear();
    }

    private void cancelRulePending(AiAutomateRuleId rule) {
        if (world == null) return;
        for (Map.Entry<Command, AiAutomateRuleId> entry : new ArrayList<>(commandRules.entrySet())) {
            if (entry.getValue() != rule) continue;
            Command command = entry.getKey();
            // 恢复批次仍应完成；只撤销微操动作和脱困等尚未执行的决策。
            if (restorations.contains(command)) continue;
            world.commandController.pendingCommands.remove(command);
            world.commandController.queuedCommands.remove(command);
            issuedCommands.remove(command);
            commandRules.remove(command);
        }
    }

    static boolean matchesQueue(OrderableUnit unit, Claim claim, boolean micro) {
        if (micro && claim.micro == null) return false;
        int offset = micro ? 1 : 0;
        if (unit.getWaypointCount() != claim.resume.size() + offset) return false;
        if (micro && !AiAutomateSupport.same(claim.micro, unit.getCurrentWaypoint())) return false;
        for (int i = 0; i < claim.resume.size(); i++) {
            if (!AiAutomateSupport.same(claim.resume.get(i), unit.getWaypointAt(i + offset))) return false;
        }
        return true;
    }

    /** 该任务类型允许自动化临时接管；显式 move/attack/follow 等一律不碰。 */
    static boolean isManagedOrder(UnitCommand order) {
        if (order == null) return true;
        UnitCommandType type = order.getCommandType();
        return type == UnitCommandType.attackMove || type == UnitCommandType.guardAt
                || type == UnitCommandType.patrol;
    }

    boolean canStand(OrderableUnit unit, float x, float y) {
        return terrain.canStand(unit, x, y);
    }

    boolean canReach(OrderableUnit unit, float x, float y) {
        return terrain.canReach(unit, x, y);
    }

    // ------------------------------------------------------------------ 报告

    private AiAutomateReport report(GameEngine engine, PlayerTeam team, AiAutomateRuleId only, String message) {
        List<AiAutomateReport.RuleView> views = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (only != null && entry.config.id != only) continue;
            List<Long> engagedIds = new ArrayList<>();
            for (Claim claim : claims.values()) {
                if (claim.rule == entry.config.id) engagedIds.add(claim.unit.objectId);
            }
            Collections.sort(engagedIds);
            views.add(new AiAutomateReport.RuleView(entry.config, entry.eligible, entry.engaged,
                    entry.triggers, entry.state, engagedIds));
        }
        List<String> log = new ArrayList<>();
        String needle = only == null ? null : "[" + only.id + "]";
        for (String line : recent) {
            if (needle == null || line.contains(needle)) log.add(line);
        }
        return new AiAutomateReport(engine == null ? 0 : engine.currentTick,
                engine == null ? 0 : engine.gameTimeMillis, views, log, message);
    }

    private int engagedCount(AiAutomateRuleId rule) {
        int count = 0;
        for (Claim claim : claims.values()) if (claim.rule == rule) count++;
        return count;
    }

    // ------------------------------------------------------------------ 世界同步

    private void synchronizeWorld(GameEngine engine, PlayerTeam team) {
        if (world != engine || map != engine.tileMap || owner != team || engine.currentTick < lastTick) {
            // 新地图/新阵营不能收到上一局留下的恢复指令。
            claims.clear();
            manualOverrides.clear();
            issuedCommands.clear();
            commandRules.clear();
            restorations.clear();
            stamps.clear();
            for (Entry entry : entries.values()) {
                entry.config = AiAutomateRuleConfig.defaults(entry.config.id);
                entry.eligible = 0;
                entry.engaged = 0;
                entry.state = "未启用";
            }
            commands = 0;
            triggers = 0;
            recent.clear();
            lastUpdateTick = -1;
        }
        world = engine;
        map = engine.tileMap;
        owner = team;
        lastTick = engine.currentTick;
    }

    private boolean anyEnabled() {
        for (Entry entry : entries.values()) if (entry.config.enabled) return true;
        return false;
    }

    private Entry require(AiAutomateRuleId id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown automation rule: " + id);
        return entry;
    }

    // ------------------------------------------------------------------ 内部结构

    private static final class Entry {
        AiAutomateRuleConfig config;
        final AiAutomateRule rule;
        int eligible;
        int engaged;
        int triggers;
        String state = "未启用";

        Entry(AiAutomateRule rule) {
            this.rule = rule;
            this.config = AiAutomateRuleConfig.defaults(rule.id());
        }
    }

    /** 一个单位上的临时接管：原任务快照、本轮微操、以及尚未执行的命令。 */
    static final class Claim {
        final OrderableUnit unit;
        AiAutomateRuleId rule;
        final List<UnitCommand> resume = new ArrayList<>();
        final List<Command> pending = new ArrayList<>();
        final boolean stationary;
        final float anchorX;
        final float anchorY;
        final int tookOverTick;
        UnitCommand micro;
        int lastIssueTick = -1;
        boolean fresh;

        Claim(OrderableUnit unit, int tick) {
            this(unit, tick, true);
        }

        Claim(OrderableUnit unit, int tick, boolean returnHome) {
            this.unit = unit;
            this.tookOverTick = tick;
            UnitCommand first = unit.getCurrentWaypoint();
            this.stationary = first == null || first.getCommandType() == UnitCommandType.guardAt;
            this.anchorX = first == null ? unit.posX : first.getTargetX();
            this.anchorY = first == null ? unit.posY : first.getTargetY();
            for (int i = 0; i < unit.getWaypointCount(); i++) {
                resume.add(AiAutomateSupport.copy(unit.getWaypointAt(i)));
            }
            // 建筑没有“回到原位”的概念：空快照就保持空，释放时改为清空攻击目标。
            if (returnHome && resume.isEmpty() && !unit.bI()) {
                UnitCommand home = new UnitCommand();
                home.setMoveTarget(anchorX, anchorY);
                resume.add(home);
            }
        }
    }
}
