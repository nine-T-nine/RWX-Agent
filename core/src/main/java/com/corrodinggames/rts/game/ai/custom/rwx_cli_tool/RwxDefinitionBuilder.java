package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionDisplayType;
import com.corrodinggames.rts.game.units.actions.ActionType;
import com.corrodinggames.rts.game.units.custom.CustomProjectileTemplate;
import com.corrodinggames.rts.game.units.custom.CustomUnit;
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig;
import com.corrodinggames.rts.game.units.custom.TurretConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从 {@link UnitType}、原型 {@link BaseUnit} 与动作系统提取 {@link RwxUnitDefinition}。
 *
 * <p>提取层的分支（自定义配置 / 原版炮塔 / 原版单位）只出现在这里，渲染层不判断具体类型。</p>
 */
public final class RwxDefinitionBuilder {
    /** 世界坐标每格 20 单位。 */
    private static final float WORLD_UNITS_PER_TILE = 20.0f;
    /** gameLoop 的 f 以 60fps 为 1：moveSpeed 单位是“像素/帧”，换算成格/秒要 ×60÷20。 */
    private static final float MOVE_SPEED_TO_TILES_PER_SECOND = 3.0f;

    /** 伤害写死在 fire() 里、没有统一 getter 的原版单位；值是直接伤害。 */
    private static final java.util.Map<String, Float> DAMAGE_OVERRIDES = java.util.Map.of(
            "amphibiousJet", 140.0f,
            "tankDestroyer", 35.0f,
            "experimentalHoverTank", 380.0f,
            "megaTank", 50.0f,
            "ladybug", 14.0f);
    /** 原型在地面时读不到真实速度的单位（如直升机 posZ<15 时返回 0），值是格/秒。 */
    private static final java.util.Map<String, Float> SPEED_OVERRIDES = java.util.Map.of(
            "gunShip", 4.2f);

    private RwxDefinitionBuilder() { }

    public static RwxUnitDefinition build(UnitType type) {
        return build(RwxUnitQueryCatalog.buildIndex(), type);
    }

    public static RwxUnitDefinition build(RwxUnitQueryCatalog.Index index, UnitType type) {
        RwxUnitQueryCatalog.UnitStats stats = index.get(type);
        if (stats == null) return null;
        BaseUnit prototype = RwxUnitQueryCatalog.prototype(type);
        boolean building = stats.building;

        RwxUnitDefinition.Cost cost = new RwxUnitDefinition.Cost(
                stats.price > 0 ? stats.price : null,
                stats.buildSeconds > 0 ? stats.buildSeconds : null);

        float shield = prototype == null ? 0.0f : prototype.unitShieldMax;
        float armor = prototype == null ? 0.0f : prototype.unitArmor;
        RwxUnitDefinition.Hp hp = new RwxUnitDefinition.Hp(
                stats.maxHealth, Math.max(0.0f, shield), Math.max(0.0f, armor));

        Float speed = null;
        if (!building && stats.moveSpeed > 0.0f
                && stats.movement != UnitMovementType.NONE
                && stats.movement != UnitMovementType.BUILDING) {
            speed = stats.moveSpeed * MOVE_SPEED_TO_TILES_PER_SECOND;
        }
        if (!building && SPEED_OVERRIDES.containsKey(stats.id)) {
            speed = SPEED_OVERRIDES.get(stats.id);
        }

        Integer vision = null;
        if (prototype instanceof OrderableUnit) {
            int sight = ((OrderableUnit) prototype).s();
            if (sight > 0) vision = sight;
        }

        RwxUnitNotes.Note note = findNote(stats);
        RwxUnitDefinition.Weapon weapon = weapon(type, stats, prototype, note);

        String placement = placement(type, stats);
        String about = note != null && note.about != null && !note.about.trim().isEmpty()
                ? note.about
                : about(stats);
        about = RwxUnitNotes.stripDash(about);
        int tier = note != null && note.tier != null ? note.tier : tierFromId(stats.id, stats.techLevel);
        String englishName = note != null && note.name != null && !note.name.trim().isEmpty()
                ? note.name.trim()
                : RwxUnitNames.english(type, stats.name);
        String chineseName = note != null && note.nameZh != null && !note.nameZh.trim().isEmpty()
                ? note.nameZh.trim()
                : RwxUnitNames.chinese(type);
        if (chineseName != null && chineseName.equals(englishName)) chineseName = null;
        RwxUnitDefinition definition = new RwxUnitDefinition(
                stats.id, englishName, chineseName, building ? "building" : "unit", tier,
                movementLabel(stats.movement), cost, hp, speed, vision, weapon, placement, about);

        if (note != null && !note.features.isEmpty()) {
            definition.features.addAll(note.features);
        } else if (stats.entry != null) {
            definition.features.addAll(stats.entry.features);
        }
        definition.description.addAll(RwxUnitQueryCatalog.descriptionLines(type));
        definition.description.replaceAll(RwxUnitNotes::stripDash);
        definition.description.removeIf(line -> line == null || definition.features.contains(line));
        for (RwxUnitQueryCatalog.Producer producer : index.producers(type)) {
            definition.producers.add(new RwxUnitDefinition.ProducerDef(
                    RwxUnitQueryCatalog.safeTypeId(producer.type), producer.level, producer.isFactory()));
        }
        definition.producers.sort((left, right) -> Integer.compare(
                producerRank(left), producerRank(right)));
        for (RwxUnitQueryCatalog.Upgrade upgrade : index.upgrades(type)) {
            definition.upgrades.add(new RwxUnitDefinition.UpgradeDef(
                    upgrade.name, upgrade.targetId(), upgrade.price > 0 ? upgrade.price : null));
        }
        inferUpgradeTargets(index, stats, definition);
        collectActions(definition, type, stats);
        if (note != null) {
            for (String extra : note.actionsExtra) {
                boolean exists = false;
                for (RwxUnitDefinition.ActionDef action : definition.actions) {
                    if (action.id.equalsIgnoreCase(extra) || action.kind.equalsIgnoreCase(extra)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    definition.actions.add(new RwxUnitDefinition.ActionDef(
                            extra, extra, extra, null, null, false));
                }
            }
        }
        collectRequires(definition);
        return definition;
    }

    // ===== 武器 =====

    private static RwxUnitDefinition.Weapon weapon(UnitType type, RwxUnitQueryCatalog.UnitStats stats,
                                                   BaseUnit prototype, RwxUnitNotes.Note note) {
        boolean canAttack = type instanceof CustomUnitConfig
                ? ((CustomUnitConfig) type).canAttack
                : prototype instanceof OrderableUnit && ((OrderableUnit) prototype).canAttack();
        if (!canAttack) return null;

        Float range = stats.attackRange > 0.0f ? stats.attackRange / WORLD_UNITS_PER_TILE : null;
        Float damage = null;
        String damageKind = null;
        Float reload = null;
        List<String> targets = new ArrayList<>();

        if (prototype instanceof OrderableUnit) {
            OrderableUnit orderable = (OrderableUnit) prototype;
            if (orderable.canAttackFlyingUnits()) targets.add("air");
            if (orderable.canAttackSurfaceUnits()) targets.add("ground");
            if (orderable.canAttackSubmergedUnits()) targets.add("sea");
        }

        // 自定义单位：伤害与射速来自 ini 解析后的运行时配置。
        if (type instanceof CustomUnitConfig) {
            CustomUnitConfig config = (CustomUnitConfig) type;
            if (range == null && config.maxAttackRange > 0.0f) {
                range = config.maxAttackRange / WORLD_UNITS_PER_TILE;
            }
            if (config.shootDelay > 0.0f) reload = config.shootDelay / 60.0f;
            if (config.projectileTemplatesById != null) {
                List<TurretConfig> turrets = new ArrayList<>();
                if (config.mainTurret != null) turrets.add(config.mainTurret);
                if (config.turrets != null) turrets.addAll(java.util.Arrays.asList(config.turrets));
                float best = 0.0f;
                String bestKind = null;
                for (TurretConfig turret : turrets) {
                    if (turret == null || turret.projectileId < 0) continue;
                    if (turret.projectileId >= config.projectileTemplatesById.length) continue;
                    CustomProjectileTemplate template =
                            config.projectileTemplatesById[turret.projectileId];
                    if (template == null) continue;
                    // 主伤害可能写在 areaDamage 上（如自行火炮 directDamage=1、areaDamage=80）。
                    float value = Math.max(template.b, template.c);
                    if (value <= best) continue;
                    best = value;
                    bestKind = template.c > template.b ? "area" : "direct";
                }
                if (best > 0.0f) {
                    damage = best;
                    damageKind = bestKind;
                }
            }
        }

        // 原版可覆写单位：q(level) 是伤害；更早的类把伤害写死在 fire() 里，读不到。
        // 自定义单位的 q() 依赖炮塔实例，原型上没有，直接跳过。
        if (damage == null && prototype instanceof OrderableUnit
                && !(prototype instanceof CustomUnit)
                && !(type instanceof CustomUnitConfig)) {
            try {
                float value = ((OrderableUnit) prototype).q(Math.max(1, stats.techLevel));
                if (value > 0.0f) {
                    damage = value;
                    damageKind = "direct";
                }
            } catch (RuntimeException ignored) {
                // 原型没有炮塔/等级数组时读不到，保持未知。
            }
        }

        if (damage == null && DAMAGE_OVERRIDES.containsKey(stats.id)) {
            damage = DAMAGE_OVERRIDES.get(stats.id);
            damageKind = "direct";
        }

        String damageText = note == null ? null : note.weaponDamage;
        if (range == null && damage == null && damageText == null && targets.isEmpty()) return null;
        return new RwxUnitDefinition.Weapon(range, damage, damageKind, damageText, reload, targets);
    }

    /** id 里的 T2/T3 比类型的 techLevel 更接近玩家认知（升级建筑常见）。 */
    private static int tierFromId(String id, int fallback) {
        if (id != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[Tt]([1-3])(?![0-9])")
                    .matcher(id);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return fallback;
    }

    // ===== 动作 =====

    private static void collectActions(RwxUnitDefinition definition, UnitType type,
                                       RwxUnitQueryCatalog.UnitStats stats) {
        Set<String> seen = new LinkedHashSet<>();
        for (int level = 1; level <= 3; level++) {
            for (AbstractUnitAction action : RwxUnitQueryCatalog.actionsOf(type, level)) {
                String id = action.getActionIdString();
                if (id == null || id.trim().isEmpty()) continue;
                // 旧工厂的升级动作会生成 c_1 / 110 这类内部占位 id，不属于可展示动作。
                if (id.matches("\\d+") || id.matches("c_\\d+")) continue;
                ActionDisplayType displayType = action.getActionDisplayType();
                if (displayType == ActionDisplayType.infoOnly
                        || displayType == ActionDisplayType.infoOnlyNoBox
                        || displayType == ActionDisplayType.infoOnlyStockpile) {
                    continue;
                }
                if (!seen.add(id)) continue;

                UnitType produced = action.getUnitType();
                String producedId = produced == null ? null : RwxUnitQueryCatalog.safeTypeId(produced);
                String producedName = produced == null ? null
                        : RwxUnitNames.english(produced, RwxUnitQueryCatalog.displayName(produced));
                boolean self = producedId == null ? false
                        : sameFamily(stats, definition.name, producedId, producedName);
                boolean production = (displayType == ActionDisplayType.queueUnit
                        || displayType == ActionDisplayType.building) && produced != null && !self;
                String kind;
                if (production) {
                    kind = displayType == ActionDisplayType.queueUnit ? "produce" : "build";
                } else if (displayType == ActionDisplayType.queueUnit
                        || displayType == ActionDisplayType.building) {
                    // 变形/加力/建造核弹这类动作不产出独立单位类型。
                    kind = produced == null ? camelCase(id) : "transform";
                } else {
                    kind = actionKind(action);
                    // popupQueue 类动作（建造核弹/反核弹）没有产出类型，用 action id 更准确。
                    if (produced == null && "produce".equals(kind)) kind = camelCase(id);
                }
                if (kind == null || kind.isEmpty() || kind.matches("\\d+")) continue;
                Integer cost = action.getCostAmount() > 0 ? action.getCostAmount() : null;
                String target = action.usesActionTarget() ? "target" : null;
                definition.actions.add(new RwxUnitDefinition.ActionDef(
                        id, action.getDisplayName(), kind, cost, target, action.isCancel()));

                if (!production) continue;
                if (displayType == ActionDisplayType.queueUnit
                        && !definition.produces.contains(producedName)) {
                    definition.produces.add(producedName);
                } else if (displayType == ActionDisplayType.building
                        && !definition.builds.contains(producedName)) {
                    definition.builds.add(producedName);
                }
            }
        }
    }

    /** 同一单位的不同形态（_afterburn、_land 等）不算“生产”。 */
    private static boolean sameFamily(RwxUnitQueryCatalog.UnitStats stats, String sourceName,
                                      String producedId, String producedName) {
        if (producedId != null && stats.id != null && producedId.equalsIgnoreCase(stats.id)) return true;
        String source = stats.id == null ? null : stats.id.toLowerCase(Locale.ROOT);
        String produced = producedId == null ? null : producedId.toLowerCase(Locale.ROOT);
        if (source != null && produced != null
                && (produced.startsWith(source + "_") || source.startsWith(produced + "_"))) {
            return true;
        }
        return producedName != null && sourceName != null
                && producedName.equalsIgnoreCase(sourceName);
    }

    /** 把动作归并成能力动词；映射不到时用 camelCase 的 action id。 */
    private static String actionKind(AbstractUnitAction action) {
        if (action.isCancel()) return "cancel";
        ActionDisplayType display = action.getActionDisplayType();
        if (display != null) {
            switch (display) {
                case queueUnit: return "produce";
                case building: return "build";
                case upgrade: return "upgrade";
                case rally: return "setRally";
                case infoOnly:
                case infoOnlyNoBox:
                case infoOnlyStockpile:
                    return "info";
                default:
                    break;
            }
        }
        ActionType type = action.getActionType();
        if (type != null) {
            switch (type) {
                case repairTarget: return "repair";
                case reclaimTarget: return "reclaim";
                case patrol: return "patrol";
                case guardUnit: return "guard";
                case attackMove: return "attack-move";
                case targetGround: return "strike";
                case placeBuilding: return "build";
                case setRally: return "setRally";
                case popupQueue: return "produce";
                default:
                    break;
            }
        }
        return camelCase(action.getActionIdString());
    }

    private static String camelCase(String value) {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split("[_\\-\\s]+");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].isEmpty()) continue;
            out.append(Character.toUpperCase(parts[index].charAt(0)))
                    .append(parts[index].substring(1));
        }
        return out.toString();
    }

    // ===== 前置与放置 =====

    private static void collectRequires(RwxUnitDefinition definition) {
        RwxUnitDefinition.ProducerDef primary = null;
        for (RwxUnitDefinition.ProducerDef producer : definition.producers) {
            if (primary == null || (producer.factory && !primary.factory)) primary = producer;
        }
        if (primary == null) return;
        if (primary.factory && primary.level > 1) {
            definition.requires.add(primary.name + " tier " + primary.level);
        }
    }

    /** 主来源排序：工厂优先（按等级），建筑则优先指挥中心/基础建造者。 */
    private static int producerRank(RwxUnitDefinition.ProducerDef producer) {
        if (producer.factory) return producer.level;
        String id = producer.name == null ? "" : producer.name.toLowerCase(Locale.ROOT);
        if (id.contains("commandcenter")) return 10;
        if (id.equals("builder")) return 11;
        if (id.contains("builder")) return 12;
        if (id.contains("engineer")) return 13;
        return 20;
    }

    /** 人工层可能按 id、显示名或别名书写，逐个尝试。 */
    private static RwxUnitNotes.Note findNote(RwxUnitQueryCatalog.UnitStats stats) {
        RwxUnitNotes.Note note = RwxUnitNotes.find(stats.id);
        if (note != null) return note;
        note = RwxUnitNotes.find(stats.name);
        if (note != null) return note;
        if (stats.entry == null) return null;
        note = RwxUnitNotes.find(stats.entry.id);
        if (note != null) return note;
        for (String alias : stats.entry.aliases) {
            note = RwxUnitNotes.find(alias);
            if (note != null) return note;
        }
        return null;
    }

    /** 升级动作只带显示名时不指向具体类型，用同族的高一级建筑补上目标。 */
    private static void inferUpgradeTargets(RwxUnitQueryCatalog.Index index,
                                            RwxUnitQueryCatalog.UnitStats source,
                                            RwxUnitDefinition definition) {
        if (definition.upgrades.isEmpty() || !source.building) return;
        String base = source.id == null ? "" : source.id.toLowerCase(Locale.ROOT);
        for (int position = 0; position < definition.upgrades.size(); position++) {
            RwxUnitDefinition.UpgradeDef upgrade = definition.upgrades.get(position);
            if (upgrade.to != null) continue;
            String best = null;
            for (RwxUnitQueryCatalog.UnitStats candidate : index.units()) {
                if (!candidate.building || candidate.techLevel <= source.techLevel) continue;
                String id = candidate.id == null ? "" : candidate.id.toLowerCase(Locale.ROOT);
                if (id.equals(base) || !id.startsWith(base)) continue;
                if (best == null || id.length() < best.length()) best = id;
            }
            if (best != null) {
                definition.upgrades.set(position, new RwxUnitDefinition.UpgradeDef(
                        upgrade.name, best, upgrade.cost));
            }
        }
    }

    private static String placement(UnitType type, RwxUnitQueryCatalog.UnitStats stats) {
        if (!stats.building) return null;
        List<String> parts = new ArrayList<>();
        if (type.p()) parts.add("resource pool only");
        if (stats.footprint != null) parts.add(stats.footprint + " footprint");
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String about(RwxUnitQueryCatalog.UnitStats stats) {
        if (stats.entry != null && stats.entry.summary != null && !stats.entry.summary.trim().isEmpty()) {
            return stats.entry.summary;
        }
        List<String> lines = RwxUnitQueryCatalog.descriptionLines(stats.type);
        return lines.isEmpty() ? null : lines.get(0);
    }

    private static String movementLabel(UnitMovementType movement) {
        if (movement == null) return "none";
        switch (movement) {
            case LAND: return "ground";
            case AIR: return "air";
            case WATER: return "sea";
            case HOVER: return "hover";
            case OVER_CLIFF: return "over-cliff";
            case OVER_CLIFF_WATER: return "over-cliff-water";
            case BUILDING: return "building";
            default: return "none";
        }
    }

    /** 全部有效单位与建筑。 */
    public static List<RwxUnitDefinition> buildAll() {
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        List<RwxUnitDefinition> result = new ArrayList<>();
        for (RwxUnitQueryCatalog.UnitStats stats : index.units()) {
            RwxUnitDefinition definition = build(index, stats.type);
            if (definition != null) result.add(definition);
        }
        return result;
    }
}
