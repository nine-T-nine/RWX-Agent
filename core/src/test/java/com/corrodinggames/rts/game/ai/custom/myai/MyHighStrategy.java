package com.corrodinggames.rts.game.ai.custom.myai;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.ai.strategy.*;
import java.util.List;

/** Compiled copy of the complete example in docs/AI扩展接口.md; not installed in the game catalog. */
public final class MyHighStrategy implements AiStrategy {
    public static AiDefinition definition() {
        return AiDefinition.custom(5, "my.ai", "My AI", "我的AI", MyHighStrategy::new);
    }

    @Override
    public String id() {
        return "my.ai-high.v1";
    }

    @Override
    public void onTactics(AiContext context, float elapsedFrames) {
        List<BaseUnit> enemies = context.visibleEnemies();
        if (enemies.isEmpty()) return;
        BaseUnit target = enemies.get(0);
        for (OrderableUnit unit : context.ownUnits()) {
            if (unit.canUnitAttack() && !unit.bI()
                    && unit.transportContainer == null
                    && !unit.canNotBeGivenOrdersByPlayer()) {
                context.orders().attackMove(target.posX, target.posY, unit);
            }
        }
    }
}
