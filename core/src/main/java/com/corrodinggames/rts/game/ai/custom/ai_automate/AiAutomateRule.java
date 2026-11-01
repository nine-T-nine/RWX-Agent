package com.corrodinggames.rts.game.ai.custom.ai_automate;

/**
 * 一条自动化规则：只读取当前战况，通过 {@link AiAutomate} 的接管接口下达微操命令。
 *
 * <p>实现必须满足三条约束：</p>
 * <ul>
 *   <li>只接管 {@link AiAutomate#isManagedOrder} 认可的任务类型（attack-move、guard-at、
 *       巡逻或空闲），Agent 的显式 move / attack / follow 等指令永不被覆盖；</li>
 *   <li>只通过标准 {@code Command} 发令，不直接改单位状态，保证回放一致；</li>
 *   <li>同一单位同时只归一条规则所有，冲突由优先级决定，接管时沿用对方的原任务快照。</li>
 * </ul>
 */
interface AiAutomateRule {
    AiAutomateRuleId id();

    /** 每个采样周期调用一次；规则自行判断候选单位、接管或释放。 */
    void update(AiAutomate runtime);

    /** 关闭规则时调用：恢复所有仍由该规则接管的单位。 */
    default void release(AiAutomate runtime) {
        runtime.releaseAll(id(), true);
    }
}
