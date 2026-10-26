package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * 单位目录工具：把 mod 的内部单位名解析成 {@link UnitType}，并提供常用统计。
 *
 * <p>设计约定：
 * <ul>
 *   <li>名字全部来自 {@code assets/units/**}{@code .ini} 与 Java 枚举；解析失败返回 {@code null} / {@code 0}，
 *       绝不抛异常——mod 换版本时 AI 只会少用某个单位，不会崩。</li>
 *   <li>有些单位被 ini {@code overrideAndReplace} 覆盖（例如 <code>extractor</code>→<code>extractorT1</code>），
 *       所以解析统一走 {@link #resolve(String...)} 的多名字回退。</li>
 *   <li>统计一律包含工厂队列里在造的数量（与原版 {@code BaseZone.countUnitsOfType} 不同，后者只数在场单位）。</li>
 * </ul>
 */
public final class AiUnits {
    private AiUnits() { }

    // ===== 内部单位名（与 ini 的 name 字段一致）=====
    public static final String COMBAT_ENGINEER = "combatEngineer";
    public static final String MECH_ENGINEER = "mechEngineer";
    public static final String PLASMA_TANK = "plasmaTank";
    public static final String HEAVY_INTERCEPTOR = "heavyInterceptor";
    public static final String MECH_FACTORY = "mechFactory";
    public static final String MECH_FACTORY_T2 = "mechFactoryT2";
    public static final String MECH_MINIGUN = "mechMinigun";
    public static final String MECH_FLAME = "mechFlame";
    public static final String MECH_MISSILE = "mechMissile";
    public static final String MECH_GUN = "mechGun";
    public static final String MECH_ARTILLERY = "mechArtillery";
    public static final String MECH_BUNKER = "mechBunker";
    public static final String MECH_BUNKER_DEPLOYED = "mechBunkerDeployed";
    public static final String HEAVY_BATTLESHIP = "heavyBattleship";
    public static final String HEAVY_SUB = "heavySub";
    public static final String HELICOPTER = "c_helicopter";
    public static final String LIGHT_GUNSHIP = "lightGunship";
    public static final String BOMBER = "bomber";
    public static final String EXTRACTOR_T1 = "extractorT1";
    public static final String EXTRACTOR_T2 = "extractorT2";
    public static final String EXTRACTOR_T3 = "extractorT3";
    public static final String EXTRACTOR_T3_OVERCLOCKED = "extractorT3_overclocked";
    public static final String FABRICATOR_T1 = "fabricatorT1";
    public static final String FABRICATOR_T2 = "fabricatorT2";
    public static final String FABRICATOR_T3 = "fabricatorT3";
    public static final String TURRET_T1 = "c_turret_t1";
    public static final String TURRET_T2_GUN = "c_turret_t2_gun";
    public static final String TURRET_T3_GUN = "c_turret_t3_gun";
    public static final String TURRET_T2_FLAME = "c_turret_t2_flame";
    public static final String TURRET_T1_LIGHTNING = "c_turret_t1_lightning";
    public static final String TURRET_T2_LIGHTNING = "c_turret_t2_lightning";
    public static final String ANTI_AIR_TURRET = "c_antiAirTurret";
    public static final String ANTI_AIR_TURRET_T2 = "c_antiAirTurretT2";
    public static final String ANTI_NUKE_LAUNCHER = "antiNukeLauncherC";

    /** 按顺序尝试若干名字，返回第一个能解析到的类型；全部失败返回 null。 */
    public static UnitType resolve(String... names) {
        if (names == null) return null;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            UnitType type = UnitTypeEnum.getUnitTypeByName(name);
            if (type != null) return type;
        }
        return null;
    }

    // ===== 语义化快捷方法（Java 内置枚举直接取，自定义单位按名解析）=====
    public static UnitType builder() { return UnitTypeEnum.builder; }
    public static UnitType commandCenter() { return UnitTypeEnum.commandCenter; }
    public static UnitType landFactory() { return UnitTypeEnum.landFactory; }
    public static UnitType airFactory() { return UnitTypeEnum.airFactory; }
    public static UnitType seaFactory() { return UnitTypeEnum.seaFactory; }
    public static UnitType heavyTank() { return UnitTypeEnum.heavyTank; }
    public static UnitType gunShip() { return UnitTypeEnum.gunShip; }
    public static UnitType battleShip() { return UnitTypeEnum.battleShip; }
    public static UnitType repairBay() { return UnitTypeEnum.repairbay; }
    public static UnitType laserDefence() { return UnitTypeEnum.laserDefence; }
    public static UnitType amphibiousJet() { return UnitTypeEnum.amphibiousJet; }

    public static UnitType helicopter() { return resolve(HELICOPTER, "helicopter"); }
    public static UnitType combatEngineer() { return resolve(COMBAT_ENGINEER); }
    public static UnitType mechEngineer() { return resolve(MECH_ENGINEER); }
    public static UnitType plasmaTank() { return resolve(PLASMA_TANK); }
    public static UnitType heavyInterceptor() { return resolve(HEAVY_INTERCEPTOR); }
    public static UnitType mechFactory() { return resolve(MECH_FACTORY); }
    public static UnitType mechFactoryT2() { return resolve(MECH_FACTORY_T2); }
    public static UnitType mechMinigun() { return resolve(MECH_MINIGUN); }
    public static UnitType mechFlame() { return resolve(MECH_FLAME); }
    public static UnitType mechMissile() { return resolve(MECH_MISSILE); }
    public static UnitType mechGun() { return resolve(MECH_GUN); }
    public static UnitType mechArtillery() { return resolve(MECH_ARTILLERY); }
    public static UnitType heavyBattleship() { return resolve(HEAVY_BATTLESHIP); }
    public static UnitType heavySub() { return resolve(HEAVY_SUB); }
    public static UnitType extractorT1() { return resolve(EXTRACTOR_T1, "extractor"); }
    public static UnitType extractorT2() { return resolve(EXTRACTOR_T2); }
    public static UnitType extractorT3() { return resolve(EXTRACTOR_T3); }
    public static UnitType extractorT3Overclocked() { return resolve(EXTRACTOR_T3_OVERCLOCKED); }
    public static UnitType fabricatorT1() { return resolve(FABRICATOR_T1, "fabricator"); }
    public static UnitType fabricatorT2() { return resolve(FABRICATOR_T2); }
    public static UnitType fabricatorT3() { return resolve(FABRICATOR_T3); }
    public static UnitType turretT1() { return resolve(TURRET_T1, "turret"); }
    public static UnitType turretT2Gun() { return resolve(TURRET_T2_GUN); }
    public static UnitType turretT3Gun() { return resolve(TURRET_T3_GUN); }
    public static UnitType turretT2Flame() { return resolve(TURRET_T2_FLAME); }
    public static UnitType turretT1Lightning() { return resolve(TURRET_T1_LIGHTNING); }
    public static UnitType turretT2Lightning() { return resolve(TURRET_T2_LIGHTNING); }
    public static UnitType antiAirTurret() { return resolve(ANTI_AIR_TURRET, "antiAirTurret"); }
    public static UnitType antiAirTurretT2() { return resolve(ANTI_AIR_TURRET_T2); }
    public static UnitType antiNukeLauncher() { return resolve(ANTI_NUKE_LAUNCHER, "antiNukeLaucher"); }

    /** 类型是否命中候选之一：用于同时接受"ini 覆盖版（c_xxx）"与"Java 内置枚举版"两种身份。 */
    public static boolean isAnyOf(UnitType type, UnitType... candidates) {
        if (type == null || candidates == null) return false;
        for (UnitType candidate : candidates) {
            if (candidate != null && candidate == type) return true;
        }
        return false;
    }

    // ===== 统计 =====
    /** 该类型的数量，包含工厂队列里在造的。 */
    public static int count(List<OrderableUnit> units, UnitType type) {
        if (type == null || units == null) return 0;
        int total = 0;
        for (OrderableUnit unit : units) {
            if (unit == null) continue;
            if (unit.r() == type) total++;
            if (unit instanceof FactoryQueueInterface) total += ((FactoryQueueInterface) unit).h(type);
        }
        return total;
    }

    /** 两个类型合计数量（常用：战斗工程师 + 机械工程师）。 */
    public static int countBoth(List<OrderableUnit> units, UnitType first, UnitType second) {
        return count(units, first) + count(units, second);
    }

    /** 收集场上（不含队列）该类型的所有单位。 */
    public static List<OrderableUnit> allOfType(List<OrderableUnit> units, UnitType type) {
        List<OrderableUnit> result = new ArrayList<>();
        if (type == null || units == null) return result;
        for (OrderableUnit unit : units) {
            if (unit != null && unit.r() == type) result.add(unit);
        }
        return result;
    }

    /** 场上第一个该类型的单位，没有则 null。 */
    public static OrderableUnit firstOfType(List<OrderableUnit> units, UnitType type) {
        if (type == null || units == null) return null;
        for (OrderableUnit unit : units) {
            if (unit != null && unit.r() == type) return unit;
        }
        return null;
    }

    // ===== 战斗单位识别 =====
    /** 是否战斗单位：非建筑、非运输、非建造者/采集者，且有攻击能力。 */
    public static boolean isCombatUnit(BaseUnit unit) {
        if (unit == null || unit.isDead || unit.bI()) return false;
        UnitType type = unit.r();
        if (type.m() || type.n()) return false;              // useAsBuilder / useAsHarvester
        if (unit.canTransportUnits()) return false;
        return unit.canAttack();
    }

    /** 是否已经拥有该兵种路线（LAND / AIR …）的战斗单位，用于陆军/空军二选一。 */
    public static boolean hasCombatUnitsOfMovement(List<OrderableUnit> units, UnitMovementType movement) {
        if (units == null) return false;
        for (OrderableUnit unit : units) {
            if (!isCombatUnit(unit)) continue;
            UnitMovementType unitMovement = unit.r().o();
            if (unitMovement == movement) return true;
            if (movement == UnitMovementType.LAND && unitMovement == UnitMovementType.OVER_CLIFF) return true;
            if (movement == UnitMovementType.AIR && unitMovement == UnitMovementType.OVER_CLIFF_WATER) return true;
        }
        return false;
    }
}
