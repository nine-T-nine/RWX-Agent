package com.corrodinggames.rts.game.ai.custom.impossible_1income;

import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiStrategy;

/**
 * AI-impossible-1income：<b>完全继承原版“不可能”AI</b>，只把经济倍率从 {@code 3.7} 改成 {@code 1.0}。
 *
 * <p>“完全继承”在接口层的含义（本类一个回调都不覆盖）：
 * <ul>
 *   <li>{@code overridesUnitProduction()} / {@code overridesBuildingProduction()} / {@code overridesUpgrades()}
 *       保持默认 {@code false}，所以原版的建造打分、选址、选人、工厂生产、升级抽签全部照常发令；</li>
 *   <li>{@code onFrame/onEconomy/onTactics/onStrategy} 保持默认空实现，不会与原版抢同一批单位；</li>
 *   <li>{@link AiDefinition#extendOriginal(int)} 以 {@code 3} 保留原版全部四个阶段
 *       （{@code INITIALIZATION / ECONOMY / TACTICS / STRATEGY}），原版算法走“不可能”分支：
 *       全局策略计时器更快、基地防御目标更高、编队容量更大、升级阈值与实验单位判定按 Impossible 走。</li>
 * </ul>
 *
 * <p>唯一改动是 {@link AiDefinition#withIncomeMultiplier(float)}：最终收入 =
 * 本倍率 × 房间收入倍率（{@code roomSettings.incomeMultiplier}），房间设置仍然生效。
 * 也就是说本档保留 Impossible 的“决策更凶”，但不给 3.7 倍的额外经济。
 */
public final class CustomImpossible1IncomeStrategy implements AiStrategy {
    /** 稳定数字编号：{@code >= 4}，房间设置与存档都记它；4..12 已被占用，发布后不能改号或复用。 */
    public static final int DEFINITION_CODE = 13;

    /** 原版难度分支：{@code 3} = 原版“不可能”。只改决策分支，不改收入。 */
    private static final int ORIGINAL_DIFFICULTY = 3;

    /** 经济倍率：统一写死 1.0（原版“不可能”为 3.7）。 */
    private static final float INCOME_MULTIPLIER = 1.0f;

    /**
     * 本 AI 的定义。经济倍率与保留的原版阶段都写在这一个方法里，
     * 界面选项、房间设置、名称、收入与存档读取都取自这里。
     *
     * <p>新增时把本类的 {@code definition()} 加进
     * {@code CustomAiCatalog.definitions()} 清单，注册后会自动出现在房间下拉框。
     */
    public static AiDefinition definition() {
        return AiDefinition.custom(
                        DEFINITION_CODE,
                        "rwx.ai-impossible-1income",       // 稳定字符串 ID：存档用它校验策略
                        "AI-impossible-1income",           // 英文名（必填，其它语言的回退名）
                        "AI-不可能-1倍收入",                 // 简体中文名
                        CustomImpossible1IncomeStrategy::new) // 每队一个实例，必须每次 new
                .withIncomeMultiplier(INCOME_MULTIPLIER)
                .extendOriginal(ORIGINAL_DIFFICULTY);
    }

    @Override
    public String id() {
        return "rwx.ai-impossible-1income.v1";
    }
}
