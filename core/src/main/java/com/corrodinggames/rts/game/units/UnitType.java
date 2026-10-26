package com.corrodinggames.rts.game.units;

import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.SelectUnitTypeAction;
import com.corrodinggames.rts.game.units.custom.AnimationSet;
import com.corrodinggames.rts.game.units.custom.PlacementRules;
import com.corrodinggames.rts.game.units.custom.price.UnitPrice;
import com.corrodinggames.rts.gameFramework.graphics.Texture;

import java.util.ArrayList;

/* JADX INFO: renamed from: com.corrodinggames.rts.game.units.as */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/units/as.class */
public interface UnitType {
    boolean isAvailableInDemo();

    /** True while the room rules block this unit (e.g. nuke units with noNukes set). */
    boolean w();

    BaseUnit a();

    SelectUnitTypeAction d();

    /** Build price, as a plain credit number. */
    int c();

    int b(int i);

    UnitPrice u();

    UnitPrice d(int i);

    UnitPrice B();

    Texture z();

    boolean y();

    float D();

    int g();

    boolean isBuildingUnit();

    /** True for builders; [ai]useAsBuilder / [core]isBuilder on custom units. */
    boolean l();

    /** True when the unit is left out of the unit cap; custom key ignoreInUnitCapCalculation. */
    boolean k();

    /** True when the AI may use this unit to build; [ai]useAsBuilder. */
    boolean m();

    /** True when the AI may use this unit to harvest; [ai]useAsHarvester. */
    boolean n();

    /** Movement type of the unit (LAND / WATER / AIR / HOVER ...). */
    UnitMovementType o();

    /** True when the unit may only be placed on a resource pool; [ai]placeOnlyOnResPool. */
    boolean p();

    PlacementRules q();

    /* JADX INFO: renamed from: e */
    String getUnitName();

    String f();

    /* JADX INFO: renamed from: i */
    String getUnitTypeDescriptionShort();

    void h();

    ArrayList<AbstractUnitAction> a(int i);

    String v();

    AnimationSet x();

    int a(BaseUnit baseUnit);
}
