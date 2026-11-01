package com.corrodinggames.rts.game.ai.custom.ai_s;

import com.corrodinggames.rts.game.ai.custom.util.AiUnits;
import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.Utility;

import java.util.ArrayList;
import java.util.List;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Composition-only production layer. Native transport handling remains enabled by BaseZone. */
final class AiSProductionPolicy {
    private static final int MINUTE_MILLIS = 60_000;
    private static final int STRATEGY_REFRESH_MILLIS = 5 * MINUTE_MILLIS;
    private int lastLandStrategyRefreshMillis = -STRATEGY_REFRESH_MILLIS;
    private int lastAirStrategyRefreshMillis = -STRATEGY_REFRESH_MILLIS;
    private LandStrategy landStrategy = LandStrategy.A;
    private AirStrategy airStrategy = AirStrategy.A;
    private CustomAiSStrategy.LandMode lastSelectedLandMode;
    private UnitType lastProducedType;

    private enum LandStrategy { A, B, C, D, E }
    private enum AirStrategy { A, B, C }

    String update(AiContext context, boolean airMacro, CustomAiSStrategy.LandMode landMode) {
        List<OrderableUnit> units = context.ownUnits();
        List<UnitType> composition = airMacro ? airComposition(units, context) : landComposition(units, context, landMode);
        String strategy = airMacro ? "air-" + airStrategy : "land-" + landMode + "-" + landStrategy;
        lastProducedType = null;
        if (produceTransportIfRequested(context, units)) return strategy + " selected=transport result=" + producedResult();
        if (produceSupportUnits(context, units)) return strategy + " selected=support result=" + producedResult();
        if (navalStrategyUnlocked(units, airMacro)) composition.addAll(navalComposition(units));
        if (composition.isEmpty()) return strategy + " selected=none result=no candidates";
        // This intentionally mirrors tryBuildFromStrategy: keep the selected priority order and
        // let the first currently usable, empty factory receive one queue item.
        for (UnitType type : composition) {
            if (tryProduceFromAnyFactory(context, units, type)) {
                return strategy + " selected=" + type.getUnitTypeDescriptionShort() + " result=" + producedResult();
            }
        }
        return strategy + " selected=" + composition.get(0).getUnitTypeDescriptionShort() + " result=no affordable/available factory";
    }

    private String producedResult() {
        return lastProducedType == null ? "none" : "queued " + lastProducedType.getUnitTypeDescriptionShort();
    }

    /** Keep the original transport candidate set and its shuffled tryBuildFromStrategy ordering. */
    private boolean produceTransportIfRequested(AiContext context, List<OrderableUnit> units) {
        if (!context.team().hasTransportCapacityRequest()) return false;
        for (UnitType type : context.team().getShuffledTransportUnitTypes()) {
            if (tryProduceFromAnyFactory(context, units, type)) {
                context.team().markTransportCapacityQueued();
                return true;
            }
        }
        return false;
    }

    private boolean produceSupportUnits(AiContext context, List<OrderableUnit> units) {
        // The original production pass is still responsible for transports. Recreate only the
        // non-combat builder/engineer side so taking over combat composition cannot starve growth.
        if (AiUnits.count(units, UnitTypeEnum.builder) < 5
                && tryProduceFromAnyFactory(context, units, UnitTypeEnum.builder)) return true;
        UnitType combatEngineer = AiUnits.combatEngineer();
        UnitType mechEngineer = AiUnits.mechEngineer();
        if (context.credits() > 6000 && context.team().getScaledIncomeRate() > 70
                && AiUnits.countBoth(units, combatEngineer, mechEngineer) < 3) {
            UnitType preferred = AiUnits.count(units, combatEngineer) <= AiUnits.count(units, mechEngineer)
                    ? combatEngineer : mechEngineer;
            if (tryProduceFromAnyFactory(context, units, preferred)) return true;
            if (tryProduceFromAnyFactory(context, units, preferred == combatEngineer ? mechEngineer : combatEngineer)) return true;
        }
        return false;
    }

    private List<UnitType> landComposition(List<OrderableUnit> units, AiContext context,
                                           CustomAiSStrategy.LandMode landMode) {
        double income = context.team().getScaledIncomeRate();
        int time = GameEngine.getInstance().gameTimeMillis;
        int landT2 = upgradedCount(units, UnitTypeEnum.landFactory);
        int mechT2 = AiUnits.count(units, AiUnits.mechFactoryT2());
        boolean mech = AiUnits.count(units, AiUnits.mechFactory()) + mechT2 > 0;
        boolean sea = AiUnits.count(units, UnitTypeEnum.seaFactory) > 0;
        if (lastSelectedLandMode != landMode || time - lastLandStrategyRefreshMillis >= STRATEGY_REFRESH_MILLIS) {
            lastLandStrategyRefreshMillis = time;
            lastSelectedLandMode = landMode;
            if (landMode == CustomAiSStrategy.LandMode.Y) {
                landStrategy = time >= 20 * MINUTE_MILLIS && income > 150 && mechT2 >= 1
                        ? weightedStrategy(1, LandStrategy.C, 2, LandStrategy.E) : LandStrategy.C;
            } else if (landMode == CustomAiSStrategy.LandMode.Z && income > 200 && mechT2 >= 1 && landT2 >= 1) {
                landStrategy = weightedStrategy(1, LandStrategy.A, 1, LandStrategy.B, 4, LandStrategy.E);
            } else if (landMode == CustomAiSStrategy.LandMode.X && time < 20 * MINUTE_MILLIS
                    && AiUnits.count(units, UnitTypeEnum.landFactory) > 0) {
                landStrategy = mech ? rollStrategy(LandStrategy.A, LandStrategy.C) : LandStrategy.A;
            } else if (landMode == CustomAiSStrategy.LandMode.X && income > 130 && income < 200 && landT2 >= 2) {
                landStrategy = weightedStrategy(5, LandStrategy.B, 3, LandStrategy.A);
            } else if (landMode == CustomAiSStrategy.LandMode.X && sea && landT2 >= 1 && income > 100) {
                landStrategy = income < 130 ? rollStrategy(LandStrategy.D, LandStrategy.A) : LandStrategy.D;
            } else landStrategy = landMode == CustomAiSStrategy.LandMode.Y ? LandStrategy.C : LandStrategy.A;
        }
        switch (landStrategy) {
            case B: return landB(units);
            case C: return landC(units);
            case D: return landD(units);
            case E: return landE(units);
            default: return landA(units);
        }
    }

    private List<UnitType> airComposition(List<OrderableUnit> units, AiContext context) {
        double income = context.team().getScaledIncomeRate();
        int airT2 = upgradedCount(units, UnitTypeEnum.airFactory);
        int time = GameEngine.getInstance().gameTimeMillis;
        if (time - lastAirStrategyRefreshMillis >= STRATEGY_REFRESH_MILLIS) {
            lastAirStrategyRefreshMillis = time;
            if (income < 120) airStrategy = AirStrategy.A;
            else if (income < 200 && airT2 >= 2) airStrategy = rollStrategy(AirStrategy.B, AirStrategy.A);
            else if (income > 200 && airT2 >= 3) airStrategy = weightedStrategy(1, AirStrategy.A, 1, AirStrategy.B, 3, AirStrategy.C);
            else airStrategy = AirStrategy.A;
        }
        switch (airStrategy) {
            case B: return airB(units);
            case C: return airC(units);
            default: return airA(units);
        }
    }

    /** Naval combat is a post-T2 branch; transports and sea builders retain their separate native demand paths. */
    private static boolean navalStrategyUnlocked(List<OrderableUnit> units, boolean airMacro) {
        if (airMacro) return upgradedCount(units, UnitTypeEnum.airFactory) >= 1;
        return upgradedCount(units, UnitTypeEnum.landFactory) >= 1
                || AiUnits.count(units, AiUnits.mechFactoryT2()) >= 1;
    }

    /** Keep the native sea line intentionally narrow; transport/sea-builder demand stays native. */
    private static List<UnitType> navalComposition(List<OrderableUnit> units) {
        List<UnitType> result = new ArrayList<>();
        int missileShips = AiUnits.count(units, UnitTypeEnum.missileShip);
        int battleships = AiUnits.count(units, UnitTypeEnum.battleShip);
        if (missileShips * 5 < battleships) result.add(UnitTypeEnum.missileShip);
        else if (missileShips * 2 > battleships) result.add(UnitTypeEnum.battleShip);
        else result.addAll(weighted(1, ordered(UnitTypeEnum.missileShip), 1, ordered(UnitTypeEnum.battleShip)));
        UnitType heavyBattleship = AiUnits.heavyBattleship();
        UnitType heavySub = AiUnits.heavySub();
        UnitType heavyAntiAir = AiUnits.resolve("heavyMissileShip", "heavyAntiAirShip");
        if (heavySub != null && AiUnits.count(units, heavySub) <= 3) result.add(heavySub);
        if (heavyAntiAir != null && AiUnits.count(units, heavyAntiAir) <= AiUnits.count(units, heavyBattleship) / 2) result.add(heavyAntiAir);
        if (heavyBattleship != null) result.add(heavyBattleship);
        return result;
    }

    private static List<UnitType> landA(List<OrderableUnit> units) {
        UnitType heavy = UnitTypeEnum.heavyTank;
        UnitType plasma = AiUnits.plasmaTank();
        int h = AiUnits.count(units, heavy), p = AiUnits.count(units, plasma);
        if (h * 3 < p * 5) return ordered(heavy); // heavy:plasma >= 5:3
        if (h > p * 3) return ordered(plasma);    // heavy:plasma <= 3:1
        return weighted(2, ordered(plasma), 1, ordered(heavy));
    }
    private static List<UnitType> landB(List<OrderableUnit> units) {
        UnitType heavy = UnitTypeEnum.heavyTank;
        UnitType artillery = UnitTypeEnum.artillery;
        UnitType mammoth = UnitTypeEnum.experimentalTank;
        int h = AiUnits.count(units, heavy), a = AiUnits.count(units, artillery), m = AiUnits.count(units, mammoth);
        boolean needHeavy = h * 10 < m * 3;
        boolean needArtillery = a * 5 < m;
        boolean needMammoth = h * 10 > m * 7 || a * 2 > m;
        if (needHeavy || needArtillery) {
            return weightedRequired(needHeavy, 3, heavy, needArtillery, 1, artillery);
        }
        if (needMammoth) return ordered(mammoth);
        return weighted(3, ordered(heavy), 1, ordered(artillery), 2, ordered(mammoth));
    }
    /** C = 防空机甲 : 普通机甲 : 火炮机甲 = 1 : 5 : 2；防空超过普通机甲的 1/4 时不再候选。 */
    private static List<UnitType> landC(List<OrderableUnit> units) {
        UnitType missile = AiUnits.mechMissile();
        UnitType gun = AiUnits.mechGun();
        UnitType artillery = AiUnits.mechArtillery();
        int guns = AiUnits.count(units, gun);
        int artilleryCount = AiUnits.count(units, artillery);
        int missiles = AiUnits.count(units, missile);
        boolean needMissile = missiles * 6 < guns;
        boolean needArtillery = guns > artilleryCount * 15;
        boolean needGun = guns < artilleryCount * 5 || missiles * 4 > guns;
        if (needMissile && guns < artilleryCount * 5) return ordered(missile);
        if (needArtillery && missiles * 4 > guns) return ordered(artillery);
        if (needMissile || needGun || needArtillery) {
            return weightedRequired(needMissile, 1, missile, needGun, 5, gun, needArtillery, 2, artillery);
        }
        return weighted(1, ordered(missile), 5, ordered(gun), 2, ordered(artillery));
    }
    private static List<UnitType> landD(List<OrderableUnit> units) {
        UnitType hover = UnitTypeEnum.heavyHoverTank;
        UnitType scout = AiUnits.resolve("scout");
        if (AiUnits.count(units, scout) * 6 > AiUnits.count(units, hover)) return ordered(hover);
        return weighted(6, ordered(hover), 1, ordered(scout));
    }
    private static List<UnitType> landE(List<OrderableUnit> units) {
        UnitType gun = AiUnits.mechGun();
        UnitType mammoth = UnitTypeEnum.experimentalTank;
        UnitType missile = AiUnits.mechMissile();
        int guns = AiUnits.count(units, gun), mammoths = AiUnits.count(units, mammoth), missiles = AiUnits.count(units, missile);
        boolean needGun = guns < mammoths * 3 || guns * 3L < missiles;
        boolean needMammoth = guns > mammoths * 5;
        boolean needMissile = guns > missiles;
        if (needGun && needMissile) return ordered(missile);
        if (needGun && needMammoth) return ordered(mammoth);
        if (needGun || needMammoth || needMissile) {
            return weightedRequired(needGun, 3, gun, needMammoth, 1, mammoth, needMissile, 1, missile);
        }
        return weighted(3, ordered(gun), 1, ordered(mammoth), 1, ordered(missile));
    }
    private static List<UnitType> airA(List<OrderableUnit> units) {
        UnitType interceptor = AiUnits.heavyInterceptor();
        UnitType helicopter = UnitTypeEnum.gunShip;
        UnitType jet = UnitTypeEnum.amphibiousJet;
        int h = AiUnits.count(units, helicopter), i = AiUnits.count(units, interceptor), j = AiUnits.count(units, jet);
        boolean needHelicopter = h < i * 2;
        boolean needInterceptor = h > i * 4;
        boolean needJet = h > j * 6;
        if (needHelicopter && needJet) return ordered(jet);
        if (needHelicopter || needInterceptor || needJet) {
            return weightedRequired(needHelicopter, 5, helicopter, needInterceptor, 3, interceptor, needJet, 1, jet);
        }
        return weighted(3, ordered(interceptor), 5, ordered(helicopter), 1, ordered(jet));
    }
    private static List<UnitType> airB(List<OrderableUnit> units) {
        UnitType helicopter = UnitTypeEnum.gunShip;
        UnitType jet = UnitTypeEnum.amphibiousJet;
        if (AiUnits.count(units, helicopter) * 5 > AiUnits.count(units, jet)) return ordered(jet);
        return weighted(1, ordered(helicopter), 2, ordered(jet));
    }
    private static List<UnitType> airC(List<OrderableUnit> units) {
        UnitType airship = AiUnits.resolve("missileAirship");
        UnitType jet = UnitTypeEnum.amphibiousJet;
        UnitType bomber = AiUnits.resolve("bomber");
        int ships = AiUnits.count(units, airship);
        if (AiUnits.count(units, jet) * 3 > ships || AiUnits.count(units, bomber) * 2 > ships) {
            return ordered(airship);
        }
        return weighted(6, ordered(airship), 5, ordered(jet), 1, ordered(bomber));
    }

    private boolean tryProduceFromAnyFactory(AiContext c, List<OrderableUnit> units, UnitType type) {
        if (type == null) return false;
        for (OrderableUnit unit : units) {
            if (unit instanceof FactoryQueueInterface && c.orders().tryProduceUnit(type, unit)) {
                lastProducedType = type;
                return true;
            }
        }
        return false;
    }
    private static int upgradedCount(List<OrderableUnit> units, UnitType type) {
        int result = 0; for (OrderableUnit unit : units) if (unit.r() == type && unit.getUpgradeLevel() >= 2) result++; return result;
    }
    private static <T> T rollStrategy(T a, T b) { return Utility.getRandomIntInRange(0, 1) == 0 ? a : b; }
    private static <T> T weightedStrategy(Object... parts) {
        int total = 0;
        for (int i = 0; i < parts.length; i += 2) total += (Integer) parts[i];
        int pick = Utility.getRandomIntInRange(1, total);
        for (int i = 0; i < parts.length; i += 2) {
            pick -= (Integer) parts[i];
            if (pick <= 0) {
                @SuppressWarnings("unchecked") T strategy = (T) parts[i + 1];
                return strategy;
            }
        }
        throw new IllegalStateException("No strategy candidates");
    }
    /** Builds a weighted, shuffled correction pool from every simultaneously underrepresented unit. */
    private static List<UnitType> weightedRequired(Object... parts) {
        List<UnitType> result = new ArrayList<>();
        for (int i = 0; i < parts.length; i += 3) {
            if (!(Boolean) parts[i]) continue;
            int weight = (Integer) parts[i + 1];
            UnitType type = (UnitType) parts[i + 2];
            for (int copy = 0; copy < weight; copy++) if (type != null) result.add(type);
        }
        for (int i = result.size() - 1; i > 0; i--) {
            int j = Utility.getRandomIntInRange(0, i);
            UnitType type = result.get(i); result.set(i, result.get(j)); result.set(j, type);
        }
        return result;
    }
    private static List<UnitType> ordered(UnitType... types) { List<UnitType> r = new ArrayList<>(); for (UnitType t : types) if (t != null) r.add(t); return r; }
    private static List<UnitType> weighted(Object... parts) { List<UnitType> r = new ArrayList<>(); for (int i=0;i<parts.length;i+=2) { int n=(Integer)parts[i]; @SuppressWarnings("unchecked") List<UnitType> p=(List<UnitType>)parts[i+1]; for(int j=0;j<n;j++) r.addAll(p); } for (int i=r.size()-1;i>0;i--) { int j=Utility.getRandomIntInRange(0,i); UnitType t=r.get(i); r.set(i,r.get(j)); r.set(j,t); } return r; }

    void writeState(DataOutputStream output) throws IOException {
        output.writeInt(lastLandStrategyRefreshMillis);
        output.writeInt(lastAirStrategyRefreshMillis);
        output.writeByte(lastSelectedLandMode == null ? -1 : lastSelectedLandMode.ordinal());
        output.writeByte(landStrategy.ordinal());
        output.writeByte(airStrategy.ordinal());
    }

    void readState(DataInputStream input) throws IOException {
        lastLandStrategyRefreshMillis = input.readInt();
        lastAirStrategyRefreshMillis = input.readInt();
        int mode = input.readByte();
        int land = input.readByte();
        int air = input.readByte();
        lastSelectedLandMode = mode >= 0 && mode < CustomAiSStrategy.LandMode.values().length
                ? CustomAiSStrategy.LandMode.values()[mode] : null;
        landStrategy = land >= 0 && land < LandStrategy.values().length ? LandStrategy.values()[land] : LandStrategy.A;
        airStrategy = air >= 0 && air < AirStrategy.values().length ? AirStrategy.values()[air] : AirStrategy.A;
    }
}
