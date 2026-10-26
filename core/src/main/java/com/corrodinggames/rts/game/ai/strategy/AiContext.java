package com.corrodinggames.rts.game.ai.strategy;

import com.corrodinggames.rts.game.ai.AIController;
import com.corrodinggames.rts.game.ai.AIStrategyNode;
import com.corrodinggames.rts.game.ai.BaseZone;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.buildings.BaseBuilding;
import com.corrodinggames.rts.gameFramework.Command;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.geometry.Point;
import io.github.rwx.geometry.PointF;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Live game-thread view. Lists are snapshots of references, not copies of mutable game objects. */
public final class AiContext {
    private final AIController team;
    private final AiOrders orders = new AiOrders(this);
    private boolean issuingOrders;

    public AiContext(AIController team) {
        this.team = Objects.requireNonNull(team, "team");
    }

    public AIController team() { return team; }
    public double credits() { return team.credits; }
    public int tick() { return GameEngine.getInstance().currentTick; }

    public AiDefinition definition() { return AiRegistry.find(team.getTeamColorId()); }
    public AiOrders orders() { return orders; }

    /** Native AI bookkeeping, for policies retaining selected original phases. */
    public List<AIStrategyNode> originalNodes() { return team.getAiNodes(); }

    public List<BaseZone> originalBases() {
        List<BaseZone> bases = new ArrayList<>();
        for (AIStrategyNode node : originalNodes()) {
            if (node instanceof BaseZone) bases.add((BaseZone) node);
        }
        return bases;
    }

    public List<OrderableUnit> ownUnits() {
        List<OrderableUnit> result = new ArrayList<>();
        for (BaseUnit unit : BaseUnit.bE) {
            if (!unit.isDead && unit.team == team && unit instanceof OrderableUnit) {
                result.add((OrderableUnit) unit);
            }
        }
        return result;
    }

    /** Uses this team's fog, never the local human player's view. Excludes neutral teams. */
    public List<BaseUnit> visibleEnemies() {
        List<BaseUnit> result = new ArrayList<>();
        for (BaseUnit unit : BaseUnit.bE) {
            if (!unit.isDead && unit.team != null && unit.team.teamId >= 0
                    && team.c(unit.team) && unit.d(team) && !unit.t()) {
                result.add(unit);
            }
        }
        return result;
    }

    /** Built-in and mod unit types; room restrictions still apply when issuing build orders. */
    public List<UnitType> unitTypes() {
        return new ArrayList<>(UnitTypeEnum.ae);
    }

    /** Resource pool construction anchors in world coordinates, using the original AI offset. */
    public List<PointF> resourcePools() {
        GameEngine engine = GameEngine.getInstance();
        List<PointF> result = new ArrayList<>();
        for (Point pool : engine.tileMap.unitObjects) {
            PointF point = engine.tileMap.tileToWorldPoint(pool);
            result.add(new PointF(point.x, point.y + engine.tileMap.tileWorldSizeY));
        }
        return result;
    }

    public boolean canReach(OrderableUnit unit, float x, float y) {
        return team.isPathPossibleBetweenPoints(unit.posX, unit.posY, x, y, unit.getMovementType());
    }

    /**
     * Placement legality for a building type at a world point, using the original game check.
     * This is the building counterpart of {@link #canReach}: it is not a path or reachability test.
     */
    public boolean canPlaceBuilding(UnitType type, float x, float y) {
        Objects.requireNonNull(type, "type");
        return type.isBuildingUnit() && !type.w() && BaseBuilding.canBuildingBePlacedAt(type, x, y, team);
    }

    /**
     * Creates and enqueues a normal game Command. Configure it immediately in this callback.
     * Supports movement, attack/attackMove, build, repair/reclaim, guard/patrol, loading,
     * production/upgrades/abilities via action IDs, attack modes, rally points, and queued orders.
     * Empty selections and foreign/dead/non-orderable units are rejected before enqueueing.
     */
    public Command command(OrderableUnit... units) {
        if (!issuingOrders) {
            throw new IllegalStateException("AI commands are only allowed in active strategy callbacks");
        }
        Objects.requireNonNull(units, "units");
        if (units.length == 0) {
            throw new IllegalArgumentException("Select at least one unit");
        }
        for (OrderableUnit unit : units) {
            if (unit == null || unit.team != team || unit.isDead || unit.canNotBeGivenOrdersByPlayer()) {
                throw new IllegalArgumentException("AI can only command its own living orderable units");
            }
        }
        Command command = GameEngine.getInstance().commandController.newCommandForTeam(team);
        for (OrderableUnit unit : units) {
            command.addUnitToCommand(unit);
        }
        return command;
    }

    void setIssuingOrders(boolean enabled) { issuingOrders = enabled; }
}
