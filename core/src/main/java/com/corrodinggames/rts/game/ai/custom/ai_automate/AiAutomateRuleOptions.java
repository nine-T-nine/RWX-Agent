package com.corrodinggames.rts.game.ai.custom.ai_automate;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code auto-set} 的补丁参数：为 null 的字段保持规则当前值，只有显式给出的选项才改动。
 *
 * <p>这是 CLI 参数与规则配置之间的唯一转换点，服务层不再各自解释 {@code --hp}/{@code --radius}。
 * 优先级、冷却与恢复策略是规则内部的固定属性，不对外开放。</p>
 */
public final class AiAutomateRuleOptions {
    public AiAutomateRuleId rule;
    /** null 表示默认开启；显式 false 才关闭。 */
    public Boolean enabled;
    /** 显式 id 列表、"all-army" 或 "all-units"；与 {@link #group} 二选一。 */
    public String units;
    /** 编组名（可以是数字编号）；服务层转成 {@code group:<name>}。 */
    public String group;
    public List<Long> excludedIds = new ArrayList<>();
    public List<String> includeTypes = new ArrayList<>();
    public List<String> excludeTypes = new ArrayList<>();
    public Float hpPercent;
    public Boolean idleOnly;
    public Boolean excludeBuilders;
    /** idle-alert 专用：显式开关优先；否则传入单位筛选时自动开启部队提醒。 */
    public Boolean includeUnits;
    public Float x;
    public Float y;
    public Float radius;

    boolean hasUnitFilter() {
        return (units != null && !units.isBlank()) || (group != null && !group.isBlank())
                || (excludedIds != null && !excludedIds.isEmpty())
                || (includeTypes != null && !includeTypes.isEmpty())
                || (excludeTypes != null && !excludeTypes.isEmpty())
                || hpPercent != null || idleOnly != null || excludeBuilders != null
                || x != null || y != null || radius != null;
    }
}
