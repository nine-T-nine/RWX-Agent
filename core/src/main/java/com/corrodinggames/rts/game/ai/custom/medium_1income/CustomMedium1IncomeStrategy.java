package com.corrodinggames.rts.game.ai.custom.medium_1income;

import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiStrategy;

/**
 * AI-medium-1income：<b>完全继承原版“中等”AI</b>，经济倍率同样为 {@code 1.0}。
 *
 * <p>原版“中等”的收入倍率本来就是 {@code 1.0}，所以这一档的经济数值与原版中等完全一致；
 * 保留下来的原版阶段与难度分支也一致，行为等同原版中等，只是作为一个独立的自定义编号存在
 * （方便和其它 {@code *_1income} 档位并列选择、统一管理）。
 *
 * <p>“完全继承”在接口层的含义（本类一个回调都不覆盖）：
 * <ul>
 *   <li>{@code overridesUnitProduction()} / {@code overridesBuildingProduction()} / {@code overridesUpgrades()}
 *       保持默认 {@code false}，所以原版的建造打分、选址、选人、工厂生产、升级抽签全部照常发令；</li>
 *   <li>{@code onFrame/onEconomy/onTactics/onStrategy} 保持默认空实现，不会与原版抢同一批单位；</li>
 *   <li>{@link AiDefinition#extendOriginal(int)} 以 {@code 0} 保留原版全部四个阶段
 *       （{@code INITIALIZATION / ECONOMY / TACTICS / STRATEGY}），原版算法走“中等”分支。</li>
 * </ul>
 *
 * <p>最终收入 = 本倍率 × 房间收入倍率（{@code roomSettings.incomeMultiplier}），房间设置仍然生效。
 */
public final class CustomMedium1IncomeStrategy implements AiStrategy {
    /** 稳定数字编号：{@code >= 4}，房间设置与存档都记它；4..9 已被占用，发布后不能改号或复用。 */
    public static final int DEFINITION_CODE = 10;

    /** 原版难度分支：{@code 0} = 原版“中等”。只改决策分支，不改收入。 */
    private static final int ORIGINAL_DIFFICULTY = 0;

    /** 经济倍率：统一写死 1.0（原版“中等”本来就是 1.0，此处显式固定，避免以后被难度倍率影响）。 */
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
                        "rwx.ai-medium-1income",          // 稳定字符串 ID：存档用它校验策略
                        "AI-medium-1income",              // 英文名（必填，其它语言的回退名）
                        "AI-中等-1倍收入",                  // 简体中文名
                        CustomMedium1IncomeStrategy::new)  // 每队一个实例，必须每次 new
                .withIncomeMultiplier(INCOME_MULTIPLIER)
                .extendOriginal(ORIGINAL_DIFFICULTY);
    }

    @Override
    public String id() {
        return "rwx.ai-medium-1income.v1";
    }
}
