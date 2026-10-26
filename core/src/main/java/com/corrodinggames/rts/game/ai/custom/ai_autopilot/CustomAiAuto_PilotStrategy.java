package com.corrodinggames.rts.game.ai.custom.ai_autopilot;

import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiStrategy;

/**
 * AI-autopilot：新建 AI 的初始化骨架（原先是从 AI-high 复制来的整份实现，已删掉多余内容）。
 *
 * <p>已经占好的位置：
 * <ul>
 *   <li>编号 <b>6</b>：<b>发布后不能改</b>，房间设置与存档都记它；也不能和 4（AI-high）、5 重复。</li>
 *   <li>字符串 ID {@code rwx.ai-autopilot} 与策略状态 ID {@code rwx.ai-autopilot.v1}：同样进存档，
 *       改动会让旧存档按"策略不匹配"报错。</li>
 *   <li>{@link #definition()} 已登记进 {@code CustomAiCatalog}，所以房间里会直接出现这个选项。</li>
 * </ul>
 *
 * <p>当前默认让原版 Impossible 的全部阶段照常运行（{@code extendOriginal(3)}），所以选它不会让比赛空转；
 * 之后按下面的顺序接入自己的逻辑：
 * <ol>
 *   <li>要接管哪一块就覆盖对应的 {@code overridesXxx()} 返回 true，并在同名 {@code onXxx()} 里发令；
 *       现在已有 4 个开关：单位生产、建筑生产、升级抽签，以及只观察的 {@code onEconomy/onTactics/onStrategy}。</li>
 *   <li>要减少原版参与时，把 {@code extendOriginal(3)} 换成
 *       {@code withOriginalPhases(3, AiPhase...)}，或完全去掉（完全接管）。</li>
 *   <li>工具性质的功能请放到 {@code game.ai.custom.util}（已有 {@code AiUnits / AiMapGraph / AiTerritory /
 *       AiPlacement / AiEnemyIntel / AiUpgrades}），不要在策略类里堆查询代码。</li>
 * </ol>
 */
public final class CustomAiAuto_PilotStrategy implements AiStrategy {
    /** AI 经济倍率，替换原版难度自带的值（原版 Impossible 为 3.7）；1.0 = 不额外加成。 */
    private static final float INCOME_MULTIPLIER = 1.0f;

    /** 稳定编号：>=4 且不能与已注册的编号重复（4 = AI-high，5 被测试示例占用，这里用 6）。 */
    public static final int DEFINITION_CODE = 6;

    public static AiDefinition definition() {
        return AiDefinition.custom(
                        DEFINITION_CODE,
                        "rwx.ai-autopilot",             // 稳定字符串 ID
                        "AI-autopilot",                 // 英文名（必填，其它语言的回退名）
                        "AI-自动驾驶",                   // 简体中文名
                        CustomAiAuto_PilotStrategy::new)
                .withIncomeMultiplier(INCOME_MULTIPLIER)
                .extendOriginal(3);                     // 先跑原版全部阶段；实现自己的逻辑后按需收窄或去掉
    }

    @Override
    public String id() {
        return "rwx.ai-autopilot.v1";
    }

    // ===== 待实现的回调（需要哪个就取消注释；默认实现什么都不做，行为与"只跑原版"一致）=====
    //
    // @Override public void onStart(AiContext context, boolean restored) { }
    // @Override public void onFrame(AiContext context, float deltaFrames) { }
    // @Override public void onEconomy(AiContext context, float elapsedFrames) { }
    // @Override public boolean overridesUnitProduction() { return true; }
    // @Override public void onUnitProduction(AiContext context, float elapsedFrames) { }
    // @Override public boolean overridesBuildingProduction() { return true; }
    // @Override public void onBuildingProduction(AiContext context, float elapsedFrames) { }
    // @Override public boolean overridesUpgrades() { return true; }
    // @Override public void onUpgrades(AiContext context, float elapsedFrames) { }
    // @Override public void onTactics(AiContext context, float elapsedFrames) { }
    // @Override public void onStrategy(AiContext context, float elapsedFrames) { }
    // @Override public void onStop(AiContext context) { }
    // @Override public void writeState(DataOutputStream output) throws IOException { }
    // @Override public void readState(DataInputStream input) throws IOException { }
}
