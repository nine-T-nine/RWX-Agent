package com.corrodinggames.rts.game.ai;

import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.gameFramework.GameEngine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.IdentityHashMap;

/* JADX INFO: renamed from: com.corrodinggames.rts.game.a.h */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/a/h.class */
public abstract class AIUnitGroupBase extends AIStrategyNode {
    private static final int TRANSPORT_WAIT_TIMEOUT_MILLIS = 3 * 60_000;
    /* JADX INFO: renamed from: F */
    ArrayList<OrderableUnit> units;
    /* JADX INFO: renamed from: G */
    ArrayList unitsNeedingTransport;
    private final IdentityHashMap<OrderableUnit, Integer> transportWaitStartedAt = new IdentityHashMap<>();

    /* JADX INFO: renamed from: c */
    public abstract void updateAI(float f);

    /* JADX INFO: renamed from: l */
    public int getUnitCount() {
        return this.units.size();
    }

    /* JADX INFO: renamed from: a */
    public boolean isActive() {
        return false;
    }

    /* JADX INFO: renamed from: b */
    public boolean isDefensive() {
        return false;
    }

    public AIUnitGroupBase(AIController aIController) {
        super(aIController);
        this.units = new ArrayList();
        this.unitsNeedingTransport = new ArrayList();
    }

    /* JADX INFO: renamed from: m */
    public boolean isAssignedToTransporter() {
        for (AIStrategyNode aIStrategyNode : this.aiController.strategyNodes) {
            if ((aIStrategyNode instanceof TransporterGroup) && ((TransporterGroup) aIStrategyNode).unitGroup == this) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: renamed from: n */
    public void removeDeadUnits() {
        Iterator it = this.units.iterator();
        while (it.hasNext()) {
            OrderableUnit orderableUnit = (OrderableUnit) it.next();
            if (orderableUnit == null || orderableUnit.isDead) {
                if (orderableUnit != null && orderableUnit.aB == this) {
                    orderableUnit.aB = null;
                }
                if (orderableUnit != null) {
                    this.unitsNeedingTransport.remove(orderableUnit);
                }
                it.remove();
            }
        }
    }

    /* JADX INFO: renamed from: o */
    public void pruneUnitsNeedingTransport() {
        Iterator it = this.unitsNeedingTransport.iterator();
        while (it.hasNext()) {
            OrderableUnit orderableUnit = (OrderableUnit) it.next();
            boolean timedOut = this.aiController.overridesTransportWaiting()
                    && GameEngine.getInstance().gameTimeMillis - transportWaitStartedAt.getOrDefault(orderableUnit,
                    GameEngine.getInstance().gameTimeMillis) >= TRANSPORT_WAIT_TIMEOUT_MILLIS;
            if (orderableUnit == null || orderableUnit.isDead || orderableUnit.transportContainer != null || orderableUnit.parentEntity != null || timedOut) {
                it.remove();
                transportWaitStartedAt.remove(orderableUnit);
                if (timedOut && orderableUnit != null && orderableUnit.aB == this) {
                    this.units.remove(orderableUnit);
                    orderableUnit.aB = null;
                }
            }
        }
    }

    public int getTransportWaiterCount() {
        pruneUnitsNeedingTransport();
        return this.unitsNeedingTransport.size();
    }

    public void addUnitNeedingTransport(OrderableUnit unit) {
        if (!this.unitsNeedingTransport.contains(unit)) {
            this.unitsNeedingTransport.add(unit);
            transportWaitStartedAt.put(unit, GameEngine.getInstance().gameTimeMillis);
        }
    }

    @Override // com.corrodinggames.rts.game.ai.AIStrategyNode
    /* JADX INFO: renamed from: p */
    public void destroy() {
        clearUnits();
        this.unitsNeedingTransport.clear();
        super.destroy();
    }

    /* JADX INFO: renamed from: a */
    protected void addUnit(OrderableUnit orderableUnit) {
        if (orderableUnit.aB != null) {
            orderableUnit.aB.removeUnit(orderableUnit);
        }
        if (orderableUnit.team != null && orderableUnit.team != this.aiController) {
            GameEngine.logWarningAndStack("unit.team:" + orderableUnit.team.teamId + ", ai:" + this.aiController.teamId);
        }
        this.units.add(orderableUnit);
        orderableUnit.aB = this;
    }

    /* JADX INFO: renamed from: b */
    public void removeUnit(OrderableUnit orderableUnit) {
        this.units.remove(orderableUnit);
        this.unitsNeedingTransport.remove(orderableUnit);
        this.transportWaitStartedAt.remove(orderableUnit);
        if (orderableUnit.aB == this) {
            orderableUnit.aB = null;
        }
    }

    /* JADX INFO: renamed from: q */
    public void clearUnits() {
        for (OrderableUnit orderableUnit : this.units) {
            if (orderableUnit != null && orderableUnit.aB == this) {
                orderableUnit.aB = null;
            }
        }
        this.units.clear();
    }

    /* JADX INFO: renamed from: b */
    public void updateCore(float f) {
    }
}
