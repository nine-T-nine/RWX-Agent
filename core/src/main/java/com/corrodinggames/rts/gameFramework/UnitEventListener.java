package com.corrodinggames.rts.gameFramework;

import com.corrodinggames.rts.game.units.BaseUnit;

/* JADX INFO: renamed from: com.corrodinggames.rts.gameFramework.bk */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/gameFramework/bk.class */
public abstract class UnitEventListener {
    public abstract void a(BaseUnit baseUnit, BaseUnit baseUnit2, Object obj);

    /** Read-only occurrence notifications; implementations must not issue simulation commands. */
    public void damaged(BaseUnit unit, BaseUnit attacker) { }
    public void removed(BaseUnit unit, BaseUnit attacker) { }
    public void info(BaseUnit unit, BaseUnit related, String phase) { }
}
