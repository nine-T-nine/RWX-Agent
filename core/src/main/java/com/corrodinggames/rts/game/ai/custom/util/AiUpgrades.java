package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionDisplayType;
import com.corrodinggames.rts.gameFramework.Utility;

import java.util.ArrayList;
import java.util.List;

/**
 * 升级动作工具：把"我想把这个建筑升级成 X"翻译成引擎里的升级动作，并用引擎自己的四道检查判断能不能发。
 *
 * <p>两条升级体系都要覆盖：
 * <ul>
 *   <li><b>Mod 自定义单位</b>（矿脉/制造仪/炮台/机甲工厂）：升级写成 {@code [action_x] convertTo : 目标}，
 *       动作的 {@link AbstractUnitAction#getUnitType()} 就是目标类型，所以能按目标类型精确匹配。</li>
 *   <li><b>Java 内置建筑</b>（陆/空/海工厂、极光防御）：升级动作的 {@code getUnitType()} 返回 null，
 *       只有一条升级路线，用 {@link #defaultUpgradeAction} 走原版的 {@code cm()} 语义。</li>
 * </ul>
 *
 * <p>不要硬编码 action id：{@code "110"}、{@code "102"} 这些 id 在多个建筑之间重复使用。
 */
public final class AiUpgrades {
    private AiUpgrades() { }

    /** 该单位当前所有的升级动作（内置 upgrade 显示类型 + 自定义 convertTo）。 */
    public static List<AbstractUnitAction> upgradeActions(OrderableUnit unit) {
        List<AbstractUnitAction> result = new ArrayList<>();
        if (unit == null) return result;
        ArrayList<?> available = unit.getAvailableActions();
        if (available == null) return result;
        for (Object item : available) {
            if (!(item instanceof AbstractUnitAction)) continue;
            AbstractUnitAction action = (AbstractUnitAction) item;
            if (isUpgradeAction(unit, action)) result.add(action);
        }
        return result;
    }

    /** 找到指向目标类型的升级动作；没有则 null。 */
    public static AbstractUnitAction findUpgradeAction(OrderableUnit unit, UnitType targetType) {
        if (unit == null || targetType == null) return null;
        ArrayList<?> available = unit.getAvailableActions();
        if (available == null) return null;
        for (Object item : available) {
            if (!(item instanceof AbstractUnitAction)) continue;
            AbstractUnitAction action = (AbstractUnitAction) item;
            if (action.getUnitType() != targetType) continue;
            if (!isUpgradeAction(unit, action)) continue;
            return action;
        }
        return null;
    }

    /** 是否是升级动作：内置看显示类型，自定义 convertTo 看 AI 动作类型。 */
    public static boolean isUpgradeAction(OrderableUnit unit, AbstractUnitAction action) {
        if (action == null) return false;
        if (action.getActionDisplayType() == ActionDisplayType.upgrade) return true;
        return action.getActionTypeForUnit(unit)
                == com.corrodinggames.rts.game.units.custom.logic.ActionType.upgrade;
    }

    /** 引擎自己的四道检查：AI 禁用 / 可用 / 可支付 / 房间规则。 */
    public static boolean canIssue(OrderableUnit unit, AbstractUnitAction action) {
        if (unit == null || action == null) return false;
        return !action.isAiDisabled(unit) && action.b(unit) && action.canAfford(unit, false)
                && !action.isNotAvailable(unit);
    }

    /** 原版 {@code cm()} 语义：该单位默认的升级动作（反核弹补弹、内置工厂升级用它）。 */
    public static AbstractUnitAction defaultUpgradeAction(OrderableUnit unit) {
        if (unit == null) return null;
        AbstractUnitAction action = unit.validateActionId(unit.cm());
        if (action == null || !isUpgradeAction(unit, action)) return null;
        return canIssue(unit, action) ? action : null;
    }

    /** 0~1 的概率掷骰，与原版 AI 共用一个随机源。 */
    public static boolean roll(float chance) {
        if (chance <= 0f) return false;
        if (chance >= 1f) return true;
        return Utility.randomFloatInRange(0f, 1f) < chance;
    }
}
