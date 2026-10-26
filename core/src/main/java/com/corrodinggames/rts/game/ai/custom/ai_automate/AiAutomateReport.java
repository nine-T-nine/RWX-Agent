package com.corrodinggames.rts.game.ai.custom.ai_automate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code auto-list} / {@code auto-show} / {@code auto-set} 的结构化结果。
 *
 * <p>由服务层转换成 CLI 的 {@code AutoPage}；这里不依赖 CLI 类型，便于单独测试。</p>
 */
public final class AiAutomateReport {
    public final int tick;
    public final int gameTimeMillis;
    public final List<RuleView> rules;
    /** 最新在前：规则触发、接管、失败、恢复等记录。 */
    public final List<String> recent;
    public final String message;

    public AiAutomateReport(int tick, int gameTimeMillis, List<RuleView> rules,
                            List<String> recent, String message) {
        this.tick = tick;
        this.gameTimeMillis = gameTimeMillis;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.recent = Collections.unmodifiableList(new ArrayList<>(recent));
        this.message = message == null ? "" : message;
    }

    /** 一条规则的当前配置与运行状态。 */
    public static final class RuleView {
        public final String id;
        public final String label;
        public final String summary;
        public final boolean enabled;
        public final int priority;
        public final int cooldownMillis;
        public final boolean resume;
        public final Float hpPercent;
        public final Float x;
        public final Float y;
        public final Float radius;
        public final String units;
        public final List<String> includeTypes;
        public final List<String> excludeTypes;
        public final List<Long> excludedIds;
        public final boolean idleOnly;
        public final boolean excludeBuilders;
        public final boolean includeUnits;
        /** 上次采样中符合条件的单位数。 */
        public final int eligible;
        /** 上次采样中正由该规则接管的单位数。 */
        public final int engaged;
        /** 该规则累计触发次数。 */
        public final int triggers;
        /** 人类可读状态：未启用、运行中、失败原因等。 */
        public final String state;
        public final List<Long> engagedUnitIds;

        public RuleView(AiAutomateRuleConfig config, int eligible, int engaged, int triggers,
                        String state, List<Long> engagedUnitIds) {
            this.id = config.id.id;
            this.label = config.id.label;
            this.summary = config.id.summary;
            this.enabled = config.enabled;
            this.priority = config.priority;
            this.cooldownMillis = config.cooldownMillis;
            this.resume = config.resume;
            this.hpPercent = config.hpPercent;
            this.x = config.x;
            this.y = config.y;
            this.radius = config.radius;
            this.units = config.units;
            this.includeTypes = Collections.unmodifiableList(new ArrayList<>(config.includeTypes));
            this.excludeTypes = Collections.unmodifiableList(new ArrayList<>(config.excludeTypes));
            this.excludedIds = Collections.unmodifiableList(new ArrayList<>(config.excludedIds));
            this.idleOnly = config.idleOnly;
            this.excludeBuilders = config.excludeBuilders;
            this.includeUnits = config.includeUnits;
            this.eligible = eligible;
            this.engaged = engaged;
            this.triggers = triggers;
            this.state = state == null ? "" : state;
            this.engagedUnitIds = Collections.unmodifiableList(new ArrayList<>(engagedUnitIds));
        }
    }
}
