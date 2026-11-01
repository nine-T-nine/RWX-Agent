package com.corrodinggames.rts.game.ai.custom.ai_automate;

import java.util.Locale;

/**
 * 预定义的自动化规则。Agent 只能开启/配置这些固定规则，不能注入自定义脚本。
 *
 * <p>{@code priority} 越大越优先：高优先级规则可以从低优先级规则手里接管同一个单位，
 * 接管时沿用后者保存的原任务快照，因此不会丢失 Agent 的原始指令。</p>
 */
public enum AiAutomateRuleId {
    ASSIST_COUNTER("assist-counter", 80,
            "智能辅助、反击",
            "空闲攻击单位高于40%血量时支援射程内受击友军；空闲单位受击时反击或每次后撤3格，脱险后恢复空闲"),
    RETREAT("retreat", 100,
            "低血保命回撤",
            "血量低于阈值且附近有敌人时后撤一小段；脱险后按指令版本决定是否恢复原任务"),
    UNSTUCK("unstuck", 90,
            "卡住恢复",
            "有移动指令却长时间没有位移时重新下发该指令，第二次失败则侧移并上报"),
    DIVE("dive", 70,
            "潜艇 / 两栖机上下潜",
            "按交战目标是否在水下切换 Dive / Surface 动作，只在射程内生效"),
    MECH("mech", 60,
            "机甲受击后撤",
            "机枪/等离子机甲被敌人攻击时远离最近的攻击者，脱战后恢复"),
    KITE("kite", 50,
            "通用风筝",
            "武器冷却期间退到射程边缘，就绪时停留开火；火炮微操是它的专用形态"),
    ARTILLERY("artillery", 40,
            "火炮微操",
            "陆地火炮就绪时停留瞄准，冷却期间调整距离并避障"),
    TURRET("turret", 35,
            "智能炮塔",
            "按炮塔类型分配目标：防空塔优先运输机/武装直升机/轰炸机，机枪与喷火打最低血，火炮塔先打攻击自己的，电击塔按机甲优先级选敌"),
    FOCUS_FIRE("focus-fire", 30,
            "集火 / 去溢出",
            "优先攻击能快速击杀的目标，避免多个单位同时打一个已经致死溢出的目标"),
    GUARD("guard", 20,
            "区域防御",
            "指定区域内主动接敌，区域外返回集结点；可被更高优先级的 retreat 接管"),
    ASSIST_BUILD("assist-build", 15,
            "协助施工",
            "空闲建造者就近协助未完工建筑，不自行决定新建建筑"),
    REPAIR("repair", 10,
            "空闲自动修理",
            "空闲建造者/工程师修理范围内受损的己方建筑或单位，然后回到原位置"),
    IDLE_ALERT("idle-alert", 0,
            "空闲提醒",
            "默认提醒工厂空队列；指定单位筛选或开启 include-units 后也提醒空闲部队，不下达指令");

    /** CLI 里使用的规则名。 */
    public final String id;
    /** 默认抢占优先级。 */
    public final int defaultPriority;
    /** 简短中文名。 */
    public final String label;
    /** 一句话说明规则做什么。 */
    public final String summary;

    AiAutomateRuleId(String id, int defaultPriority, String label, String summary) {
        this.id = id;
        this.defaultPriority = defaultPriority;
        this.label = label;
        this.summary = summary;
    }

    /** 解析 {@code --rule}：忽略大小写，允许去掉连字符（{@code focusfire}）。 */
    public static AiAutomateRuleId byId(String text) {
        if (text == null) return null;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        String compact = normalized.replace("-", "").replace("_", "");
        for (AiAutomateRuleId rule : values()) {
            if (rule.id.equals(normalized)) return rule;
            if (rule.id.replace("-", "").equals(compact)) return rule;
            if (rule.name().toLowerCase(Locale.ROOT).equals(compact)) return rule;
        }
        return null;
    }

    /** 所有规则名，用逗号连接；用于错误提示。 */
    public static String listIds() {
        StringBuilder text = new StringBuilder();
        for (AiAutomateRuleId rule : values()) {
            if (text.length() > 0) text.append(", ");
            text.append(rule.id);
        }
        return text.toString();
    }
}
