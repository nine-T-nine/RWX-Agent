package com.corrodinggames.rts.game.ai.strategy;

import com.corrodinggames.rts.game.ai.custom.util.AiUpgrades;
import com.corrodinggames.rts.game.units.AttackMode;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionId;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.geometry.PointF;

import java.util.Objects;

/** Intent-level commands using the standard game command queue, never direct unit mutation. */
public final class AiOrders {
    private final AiContext context;

    AiOrders(AiContext context) { this.context = context; }

    public void move(float x, float y, OrderableUnit... units) { context.command(units).setMoveTarget(x, y); }
    public void attackMove(float x, float y, OrderableUnit... units) { context.command(units).setAttackMoveTarget(x, y); }
    public void attack(BaseUnit target, OrderableUnit... units) {
        Objects.requireNonNull(target, "target");
        context.command(units).setAttackTarget(target);
    }
    public void repair(BaseUnit target, OrderableUnit... units) {
        Objects.requireNonNull(target, "target");
        context.command(units).setRepairTarget(target);
    }
    public void reclaim(BaseUnit target, OrderableUnit... units) {
        Objects.requireNonNull(target, "target");
        context.command(units).setReclaimTarget(target);
    }
    public void build(UnitType type, float x, float y, OrderableUnit builder) {
        Objects.requireNonNull(type, "type");
        context.command(builder).setBuildTarget(x, y, type, 1);
    }
    public void produce(UnitType type, OrderableUnit factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        AbstractUnitAction action = factory.getUnitAction(type);
        if (action == null) throw new IllegalArgumentException("Unit has no production action for " + type);
        action(action.getActionId(), factory);
    }
    /** Queue one non-building unit when this factory can produce it now. */
    public boolean tryProduceUnit(UnitType type, OrderableUnit factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (!(factory instanceof FactoryQueueInterface)
                || !((FactoryQueueInterface) factory).dy()) return false;
        return tryQueueUnit(type, factory);
    }

    /**
     * Queue one non-building unit even when this factory already has orders waiting,
     * for multi-unit orders such as "two battleships in one push".
     */
    public boolean tryQueueUnit(UnitType type, OrderableUnit factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (type.isBuildingUnit() || type.w() || factory.team != context.team()
                || factory.isDead || factory.canNotBeGivenOrdersByPlayer()) return false;
        AbstractUnitAction action = factory.getUnitAction(type);
        if (action == null || action.isAiDisabled(factory) || !action.b(factory)
                || !action.canAfford(factory, false) || action.isNotAvailable(factory)
                || (action.isQueuable() && GameEngine.getInstance().isDemo)) return false;
        context.command(factory).setActionId(action.getQueueId());
        return true;
    }

    /**
     * Queue one building at an explicit spot when this builder can place it there now.
     * Placement legality is the original game check; choosing the spot is the caller's decision.
     */
    public boolean tryBuildBuilding(UnitType type, float x, float y, OrderableUnit builder) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(builder, "builder");
        if (builder.team != context.team() || builder.isDead || builder.canNotBeGivenOrdersByPlayer()
                || !context.canPlaceBuilding(type, x, y)) return false;
        AbstractUnitAction action = builder.findActionForUnitType(type, true);
        if (action == null || action.isAiDisabled(builder) || !action.b(builder)
                || !action.canAfford(builder, false) || action.isNotAvailable(builder)
                || (action.isQueuable() && GameEngine.getInstance().isDemo)) return false;
        if (action.usesActionTarget()) {
            context.command(builder).setActionTarget(action.getActionId(), new PointF(x, y), null);
        } else {
            context.command(builder).setBuildTarget(x, y, type, action.getQueueSize());
        }
        return true;
    }

    /**
     * Upgrade this unit into {@code targetType} when it owns a matching upgrade action that is available now.
     * Pass {@code null} as the target to use the unit's own default upgrade action (the original {@code cm()} draw),
     * which is what built-in buildings such as the land factory use.
     */
    public boolean tryUpgrade(OrderableUnit unit, UnitType targetType) {
        Objects.requireNonNull(unit, "unit");
        AbstractUnitAction action = targetType == null
                ? AiUpgrades.defaultUpgradeAction(unit)
                : AiUpgrades.findUpgradeAction(unit, targetType);
        return action != null && tryUpgradeAction(unit, action);
    }

    /** Issue one already-resolved upgrade action, after the same checks the production helpers use. */
    public boolean tryUpgradeAction(OrderableUnit unit, AbstractUnitAction action) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(action, "action");
        if (unit.team != context.team() || unit.isDead || unit.canNotBeGivenOrdersByPlayer()) return false;
        if (!AiUpgrades.canIssue(unit, action)) return false;
        context.command(unit).setActionId(action.getQueueId());
        return true;
    }

    public void action(ActionId action, OrderableUnit... units) {
        Objects.requireNonNull(action, "action");
        context.command(units).setActionId(action);
    }
    public void attackMode(AttackMode mode, OrderableUnit... units) {
        Objects.requireNonNull(mode, "mode");
        context.command(units).setAttackMode(mode);
    }
    public void stop(OrderableUnit... units) { context.command(units).setClearExistingOrders(); }
}
