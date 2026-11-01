package com.corrodinggames.rts.game.units.buildings;

import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.actions.ActionId;
import com.corrodinggames.rts.gameFramework.utility.FastArrayList;
import io.github.rwx.geometry.PointF;

/* JADX INFO: renamed from: com.corrodinggames.rts.game.units.d.l */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/units/d/l.class */
public interface FactoryQueueInterface {
    boolean dA();

    void b(Projectile projectile);

    void a(Projectile projectile);

    /** How many of that unit type are already waiting in the factory queue. */
    int h(UnitType unitType);

    int f(boolean z);

    int a(ActionId actionId, boolean z);

    /** True when the build queue is empty, so the factory can take a new order right away. */
    boolean dy();

    void a(PointF pointF);

    /** Current rally point for produced units, or null when none was set. */
    PointF getRallyPoint();

    void dz();

    Projectile dw();

    FastArrayList dx();

    boolean c(Projectile projectile);
}
