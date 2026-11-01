package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.units.BaseUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stable four-character type codes for the 2D map, plus the faction prefix that turns them into a
 * five-character cell.
 *
 * <p>Codes are written in their lowercase base form; the renderer uppercases them for allies and
 * enemies, so one mapping covers all four sides. Unit codes are {@code <source><three letters>}
 * with the source being the factory that builds them (L land, N navy, M mech, A air, E
 * experimental); buildings are {@code b<three semantic characters>}.</p>
 *
 * <p>The table lists the shipped content only: every type reachable from
 * {@link RwxUnitQueryCatalog#effectiveTypes()} plus the hidden conversion forms that stand in for
 * the same entity while it is flying, landed, diving or deployed. Walls and supply depots are mod
 * extensions and stay out. Types outside the table are not drawn.</p>
 */
final class RwxMapEntityCodes {
    /** Five-character cells: one faction character plus the four-character type code. */
    static final int CELL_LENGTH = 5;
    static final int CODE_LENGTH = 4;
    static final char OWN_PREFIX = '+';
    static final char HOSTILE_PREFIX = '-';

    /** Who owns an entity on the map; decides the prefix and the case of the type code. */
    enum Side {
        SELF, ALLY, ENEMY, NEUTRAL;

        char prefix() {
            return this == SELF || this == ALLY ? OWN_PREFIX : HOSTILE_PREFIX;
        }

        boolean uppercaseCode() {
            return this == ALLY || this == ENEMY;
        }
    }

    static final class Entry {
        final String code;
        final String typeId;
        final String label;
        final boolean building;
        /** 0 = any level, otherwise the exact upgrade level this code describes. */
        final int level;

        Entry(String code, String typeId, String label, boolean building, int level) {
            this.code = code;
            this.typeId = typeId;
            this.label = label;
            this.building = building;
            this.level = level;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, List<Entry>> BY_TYPE = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        // ===== L: land factory (T2) and the builders that share its role =====
        unit("Ltnk", "c_tank", "tank");
        unit("Lhov", "hoverTank", "hover_tank");
        unit("Lbld", "builder", "builder");
        unit("Lhvc", "hovercraft", "hovercraft");
        unit("Lsct", "scout", "scout");
        unit("Lhtk", "heavyTank", "heavy_tank");
        unit("Lart", "c_artillery", "artillery");
        unit("Lpls", "plasmaTank", "plasma_tank");
        unit("Lhht", "heavyHoverTank", "heavy_hover_tank");
        unit("Llsr", "c_laserTank", "laser_tank");
        unit("Lmsl", "missileTank", "missile_tank");
        unit("Lhar", "heavyArtillery", "heavy_artillery");
        unit("Leng", "combatEngineer", "combat_engineer");
        unit("Lmam", "c_mammothTank", "mammoth_tank");
        unit("Ltds", "tankDestroyer", "tank_destroyer");
        unit("Lmga", "megaTank", "mega_tank");
        unit("Lbug", "ladybug", "ladybug");

        // ===== N: navy factory (T2) =====
        unit("Ngbt", "gunBoat", "gun_boat");
        unit("Nlsb", "lightSub", "light_sub");
        unit("Nbld", "builderShip", "builder_ship");
        unit("Nsub", "attackSubmarine", "attack_submarine");
        unit("Nmsh", "missileShip", "missile_ship");
        unit("Nbtl", "battleShip", "battle_ship");
        unit("Nhaa", "heavyAAShip", "heavy_aa_ship");
        unit("Nhvs", "heavySub", "heavy_sub");
        unit("Nhbt", "heavyBattleship", "heavy_battleship");
        unit("Nhms", "heavyMissileShip", "heavy_missile_ship");
        unit("Nntl", "nautilusSubmarine", "nautilus_submarine");
        unit("Ncar", "experiementalCarrier", "experimental_carrier");

        // ===== M: mech factory (T2) =====
        unit("Mgun", "mechGun", "mech_gun");
        unit("Mmsl", "mechMissile", "mech_missile");
        unit("Mart", "mechArtillery", "mech_artillery");
        unit("Mbkr", "mechBunker", "mech_bunker");
        unit("Meng", "mechEngineer", "mech_engineer");
        unit("Mmgn", "mechMinigun", "mech_minigun");
        unit("Mltn", "mechLightning", "mech_lightning");
        unit("Mlsr", "mechLaser", "mech_laser");
        unit("Mhms", "mechHeavyMissile", "mech_heavy_missile");
        unit("Mflm", "mechFlame", "mech_flame");
        unit("Mfly", "mechFlyingLanded", "mech_flying");

        // ===== A: air base =====
        unit("Algs", "lightGunship", "light_gunship");
        unit("Aint", "c_interceptor", "interceptor");
        unit("Ahel", "c_helicopter", "helicopter");
        unit("Adrp", "dropship", "dropship");
        unit("Agun", "gunShip", "gunship");
        unit("Ahin", "heavyInterceptor", "heavy_interceptor");
        unit("Aspy", "spyDrone", "spy_drone");
        unit("Ajet", "amphibiousJet", "amphibious_jet");
        unit("Amsh", "missileAirship", "missile_airship");
        unit("Abmb", "bomber", "bomber");
        unit("Abem", "aaBeamGunship", "aa_beam_gunship");

        // ===== E: experimental factory =====
        unit("Ebee", "fireBee", "fire_bee");
        unit("Extk", "c_experimentalTank", "experimental_tank");
        unit("Exhv", "experimentalHoverTank", "experimental_hover_tank");
        unit("Exdp", "experimentalDropship", "experimental_dropship");
        unit("Exsp", "experimentalSpider", "experimental_spider");
        unit("Exgs", "experimentalGunship", "experimental_gunship");
        unit("Emod", "modularSpider", "modular_spider");

        // ===== Buildings: b + three semantic characters =====
        building("bcmd", "commandCenter", "command_center");
        buildingLevel("blf1", "landFactory", "land_factory_t1", 1);
        buildingLevel("blf2", "landFactory", "land_factory_t2", 2);
        buildingLevel("baf1", "airFactory", "air_factory_t1", 1);
        buildingLevel("baf2", "airFactory", "air_factory_t2", 2);
        buildingLevel("bsf1", "seaFactory", "sea_factory_t1", 1);
        buildingLevel("bsf2", "seaFactory", "sea_factory_t2", 2);
        building("bmf1", "mechFactory", "mech_factory_t1");
        building("bmf2", "mechFactoryT2", "mech_factory_t2");
        building("belf", "experimentalLandFactory", "experimental_factory");
        building("blab", "laboratory", "laboratory");
        // Extractor family: b + a-e level/mode + the fixed "ex" semantic code.
        building("baex", "extractorT1", "extractor_t1");
        building("bbex", "extractorT2", "extractor_t2");
        building("bcex", "extractorT3", "extractor_t3");
        building("bdex", "extractorT3_reinforced", "extractor_t3(shield)");
        building("beex", "extractorT3_overclocked", "extractor_t3(overclock)");
        building("bfa1", "fabricatorT1", "fabricator_t1");
        building("bfa2", "fabricatorT2", "fabricator_t2");
        building("bfa3", "fabricatorT3", "fabricator_t3");
        building("btur", "c_turret_t1", "turret_t1");
        building("btg2", "c_turret_t2_gun", "turret_t2_gun");
        building("btg3", "c_turret_t3_gun", "turret_t3_gun");
        building("bta1", "c_turret_t1_artillery", "turret_t1_artillery");
        building("bta2", "c_turret_t2_artillery", "turret_t2_artillery");
        building("btf2", "c_turret_t2_flame", "turret_t2_flame");
        building("btl1", "c_turret_t1_lightning", "turret_t1_lightning");
        building("btl2", "c_turret_t2_lightning", "turret_t2_lightning");
        building("baa1", "c_antiAirTurret", "anti_air_turret_t1");
        building("baa2", "c_antiAirTurretT2", "anti_air_turret_t2");
        building("baa3", "c_antiAirTurretT3", "anti_air_turret_t3");
        building("baaf", "antiAirTurretFlak", "anti_air_turret_flak");
        building("bop1", "outpostT1", "outpost_t1");
        building("bop2", "outpostT2", "outpost_t2");
        building("bldf", "laserDefence", "laser_defence");
        building("brpr", "repairbay", "repair_bay");
        building("bnuk", "nukeLauncherC", "nuke_launcher");
        building("banu", "antiNukeLauncherC", "anti_nuke_launcher");

        // Hidden conversion forms share their base type's code so a state change never changes it.
        alias("c_amphibiousJet", "amphibiousJet");
        alias("c_amphibiousJet_transition", "amphibiousJet");
        alias("c_amphibiousJet_underwater", "amphibiousJet");
        alias("aaBeamGunship_afterburn", "aaBeamGunship");
        alias("experimentalGunshipLanded", "experimentalGunship");
        alias("mechBunkerDeployed", "mechBunker");
        alias("mechFlyingTakeoff", "mechFlyingLanded");
        alias("nautilusSubmarineLand", "nautilusSubmarine");
        alias("nautilusSubmarineSurface", "nautilusSubmarine");
    }

    private RwxMapEntityCodes() { }

    private static void unit(String code, String typeId, String label) {
        add(new Entry(code, typeId, label, false, 0));
    }

    private static void building(String code, String typeId, String label) {
        add(new Entry(code, typeId, label, true, 0));
    }

    private static void buildingLevel(String code, String typeId, String label, int level) {
        add(new Entry(code, typeId, label, true, level));
    }

    private static void add(Entry entry) {
        checkCode(entry.code);
        ENTRIES.add(entry);
        BY_TYPE.computeIfAbsent(entry.typeId, key -> new ArrayList<>()).add(entry);
    }

    private static void checkCode(String code) {
        if (code.length() != CODE_LENGTH) {
            throw new IllegalStateException("Map code must be four characters: " + code);
        }
        for (Entry existing : ENTRIES) {
            if (existing.code.equalsIgnoreCase(code)) {
                throw new IllegalStateException("Duplicate map code: " + code);
            }
        }
    }

    private static void alias(String variant, String canonical) {
        ALIASES.put(variant.toLowerCase(Locale.ROOT), canonical);
    }

    /** All shipped entries, in declaration order (used by tests and the help table). */
    static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** Hidden conversion form or module variant to the type that owns the code. */
    static String canonicalTypeId(String typeId) {
        if (typeId == null) return null;
        String direct = ALIASES.get(typeId.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        String lowered = typeId.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("modularspider_")) return "modularSpider";
        return typeId;
    }

    /** The entry for a live entity, or null when the type is outside the shipped table. */
    static Entry entryFor(BaseUnit unit) {
        if (unit == null) return null;
        String typeId;
        try {
            typeId = unit.r() == null ? null : unit.r().v();
        } catch (RuntimeException error) {
            return null;
        }
        return entryFor(canonicalTypeId(typeId), unit.bI(), safeLevel(unit));
    }

    static Entry entryFor(String typeId, boolean building, int level) {
        List<Entry> candidates = BY_TYPE.get(canonicalTypeId(typeId));
        if (candidates == null) return null;
        Entry fallback = null;
        Entry lower = null;
        for (Entry entry : candidates) {
            if (entry.building != building) continue;
            if (entry.level == 0) {
                fallback = entry;
            } else if (entry.level == level) {
                return entry;
            } else if (entry.level < level && (lower == null || entry.level > lower.level)) {
                lower = entry;
            }
        }
        if (lower != null) return lower;
        if (fallback != null) return fallback;
        // A level below every listed variant: use the lowest one so the code never disappears.
        Entry lowest = null;
        for (Entry entry : candidates) {
            if (entry.building != building) continue;
            if (lowest == null || entry.level < lowest.level) lowest = entry;
        }
        return lowest;
    }

    private static int safeLevel(BaseUnit unit) {
        try {
            return Math.max(1, Math.min(3, unit.getUpgradeLevel()));
        } catch (RuntimeException error) {
            return 1;
        }
    }

    /** Five-character cell for an entity, e.g. {@code +lhtk} (own) or {@code -LHTK} (enemy). */
    static String cell(Entry entry, Side side) {
        if (entry == null || side == null) return null;
        String code = side.uppercaseCode()
                ? entry.code.toUpperCase(Locale.ROOT) : entry.code.toLowerCase(Locale.ROOT);
        return side.prefix() + code;
    }

    /** Legend line for one code: {@code LHTK/lhtk = heavy_tank}. */
    static String legend(Entry entry) {
        String upper = entry.code.toUpperCase(Locale.ROOT);
        String lower = entry.code.toLowerCase(Locale.ROOT);
        return upper + "/" + lower + " = " + entry.label;
    }
}
