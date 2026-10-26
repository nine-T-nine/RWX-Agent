package com.corrodinggames.rts.gameFramework;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.gameFramework.utility.FastArrayList;

import java.util.Iterator;

/* JADX INFO: renamed from: com.corrodinggames.rts.gameFramework.bl */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/gameFramework/bl.class */
public class UnitEventManager {
    FastArrayList a = new FastArrayList();
    private final java.util.concurrent.CopyOnWriteArrayList<UnitEventListener> observers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addObserver(UnitEventListener listener) { observers.addIfAbsent(listener); }
    public void removeObserver(UnitEventListener listener) { observers.remove(listener); }

    private void observe(java.util.function.Consumer<UnitEventListener> action) {
        for (UnitEventListener observer : observers) {
            try { action.accept(observer); }
            catch (RuntimeException error) {
                // Observation failures must never interrupt damage, death or production.
                System.err.println("Unit observation failed: " + error);
            }
        }
    }

    public void damaged(BaseUnit unit, BaseUnit attacker) {
        if (!observers.isEmpty()) observe(listener -> listener.damaged(unit, attacker));
    }
    public void removed(BaseUnit unit, BaseUnit attacker) {
        if (!observers.isEmpty()) observe(listener -> listener.removed(unit, attacker));
    }
    public void info(BaseUnit unit, BaseUnit related, String phase) {
        if (!observers.isEmpty()) observe(listener -> listener.info(unit, related, phase));
    }

    public void a(BaseUnit baseUnit, BaseUnit baseUnit2) {
        if (this.a.size > 0) {
            Iterator it = this.a.iterator();
            while (it.hasNext()) {
                ((UnitEventListener) it.next()).a(baseUnit, baseUnit2, null);
            }
        }
    }
}
