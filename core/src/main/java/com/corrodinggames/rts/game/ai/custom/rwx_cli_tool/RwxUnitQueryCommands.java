package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.units.UnitType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code rwx-query-*} 单位/建筑查询命令的实现。
 *
 * <p>只读命令：不接触对局状态，也不需要游戏线程，直接组合
 * {@link RwxUnitQueryCatalog} 的实时数据与 {@link RwxUnitCatalog} 的手写描述。
 * 输出使用与其它 rwx 命令一致的“标题 + 每行一个对象 + 竖线分隔字段”格式。</p>
 */
public final class RwxUnitQueryCommands implements RwxCliCommandExecutor {
    private static final String[] NO_VALUES = new String[0];

    @Override
    public String execute(RwxCliCommands.Command command, List<String> arguments) {
        if (arguments == null) arguments = java.util.Collections.emptyList();
        switch (command) {
            case QUERY_UNIT:
                return unitQuery(arguments);
            case QUERY_BUILDING:
                return buildingQuery(arguments);
            case QUERY_PRODUCTION:
                return productionQuery(arguments);
            default:
                return "error: unknown query command";
        }
    }

    // ===== 入口 1：单位 =====

    private String unitQuery(List<String> arguments) {
        RwxCliArgs.validate(arguments, new String[] {"--name", "--category", "--level"},
                new String[] {"--detail", "--json"});
        String name = RwxCliArgs.value(arguments, "--name");
        String category = RwxCliArgs.value(arguments, "--category");
        if (name != null && category != null) {
            throw new IllegalArgumentException("Use either --name or --category, not both");
        }
        if (name != null) {
            if (RwxCliArgs.value(arguments, "--level") != null) {
                throw new IllegalArgumentException("--level only applies to --category land, air or sea");
            }
            return unitSingle(arguments);
        }
        if (RwxCliArgs.flag(arguments, "--detail") || RwxCliArgs.flag(arguments, "--json")) {
            throw new IllegalArgumentException("--detail/--json need --name");
        }
        if (category == null) {
            requireNoLevel(arguments, "--level");
            return nameIndex(RwxUnitQueryCatalog.buildIndex());
        }
        String key = category.trim().toLowerCase(Locale.ROOT);
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        switch (key) {
            case "builder":
                requireNoLevel(arguments, "--level");
                return categoryList(index, "工程单位", RwxUnitCatalog.Category.BUILDER);
            case "transport":
                requireNoLevel(arguments, "--level");
                return transportList();
            case "lab":
                requireNoLevel(arguments, "--level");
                return labList();
            case "land":
                return levelUnitList(arguments, "陆地攻击单位", RwxUnitCatalog.Category.LAND);
            case "air":
                return levelUnitList(arguments, "空军攻击单位", RwxUnitCatalog.Category.AIR);
            case "sea":
                return levelUnitList(arguments, "海军攻击单位", RwxUnitCatalog.Category.SEA);
            default:
                throw new IllegalArgumentException("--category must be builder, transport, land, air, sea or lab");
        }
    }

    private static void requireNoLevel(List<String> arguments, String flag) {
        if (RwxCliArgs.value(arguments, flag) != null) {
            throw new IllegalArgumentException("--level only applies to --category land, air or sea");
        }
    }

    // ===== 入口 2：建筑 =====

    private String buildingQuery(List<String> arguments) {
        RwxCliArgs.validate(arguments, new String[] {"--name", "--category"},
                new String[] {"--detail", "--json"});
        String name = RwxCliArgs.value(arguments, "--name");
        String category = RwxCliArgs.value(arguments, "--category");
        if (name != null && category != null) {
            throw new IllegalArgumentException("Use either --name or --category, not both");
        }
        if (name != null) return buildingSingle(arguments);
        if (RwxCliArgs.flag(arguments, "--detail") || RwxCliArgs.flag(arguments, "--json")) {
            throw new IllegalArgumentException("--detail/--json need --name");
        }
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        if (category == null) return buildingIndex(index);
        switch (category.trim().toLowerCase(Locale.ROOT)) {
            case "turret":
                return buildingList("防御炮塔", RwxUnitCatalog.Category.TURRET);
            case "defense":
                return buildingList("防御建筑", RwxUnitCatalog.Category.DEFENSE);
            case "economy":
                return buildingList("经济建筑", RwxUnitCatalog.Category.ECONOMY);
            case "nuke":
                return buildingList("核设施", RwxUnitCatalog.Category.NUKE);
            default:
                throw new IllegalArgumentException("--category must be turret, defense, economy or nuke");
        }
    }

    // ===== 入口 3：生产能力 =====

    private String productionQuery(List<String> arguments) {
        RwxCliArgs.validate(arguments, new String[] {"--factory", "--level", "--maxcost"}, NO_VALUES);
        String name = RwxCliArgs.required(arguments, "--factory");
        int level = RwxCliArgs.integer(arguments, "--level", 0);
        if (level < 0 || level > 2) {
            throw new IllegalArgumentException("--level 只能是 0（全部）、1（1 级工厂）或 2（2 级工厂）");
        }
        Integer maxCost = RwxCliArgs.optionalInteger(arguments, "--maxCost");

        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        UnitType factory = resolveFactory(index, name);
        if (factory == null) {
            List<String> suggestions = RwxUnitQueryCatalog.suggestNames(name);
            StringBuilder message = new StringBuilder("找不到工厂: ").append(name)
                    .append("（可用 land / air / sea / lab / commander，或具体生产建筑的 id/名称）");
            if (!suggestions.isEmpty()) {
                message.append("；可能想找: ").append(String.join("、", suggestions));
            }
            throw new IllegalArgumentException(message.toString());
        }
        List<RwxUnitQueryCatalog.UnitStats> produced = index.producible(factory, level);
        if (maxCost != null) produced.removeIf(stats -> stats.price > maxCost);
        if (produced.isEmpty()) {
            throw new IllegalArgumentException(RwxUnitQueryCatalog.displayName(factory)
                    + " 不能生产单位或建筑；单位详情请用 "
                    + RwxCliCommands.Command.QUERY_UNIT.commandName() + " --name");
        }
        produced.sort((left, right) -> Integer.compare(left.price, right.price));

        String factoryName = RwxUnitQueryCatalog.displayName(factory);
        String levelSuffix = level == 0 ? "全部等级" : "T" + Math.max(1, level);
        boolean buildings = produced.get(0).building;
        StringBuilder output = new StringBuilder(factoryName)
                .append(buildings ? " 可建造建筑（" : " 可生产单位（")
                .append(levelSuffix).append("，共 ").append(produced.size()).append(" 个）");
        output.append(buildings
                ? "\nid | 名称 | 价格 | 类型 | 用时 | 占地"
                : "\nid | 名称 | 价格 | 类型 | 生命 | 射程 | 用时");
        for (RwxUnitQueryCatalog.UnitStats stats : produced) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.price)
                    .append(" | ").append(stats.categoryLabel());
            if (buildings) {
                output.append(" | ").append(seconds(stats.buildSeconds))
                        .append(" | ").append(footprint(stats));
            } else {
                output.append(" | ").append(number(stats.maxHealth))
                        .append(" | ").append(number(stats.attackRange))
                        .append(" | ").append(seconds(stats.buildSeconds));
            }
        }
        return output.toString();
    }

    // ===== 单单位/单建筑 =====

    private String unitSingle(List<String> arguments) {
        String name = RwxCliArgs.value(arguments, "--name");
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        if (name == null) {
            // 不传 --name 时输出完整索引，方便先查 id / 名称再查详情。
            return nameIndex(index);
        }
        RwxUnitQueryCatalog.UnitStats stats = requireUnit(index, name);
        if (stats.building) {
            throw new IllegalArgumentException(name + " 是建筑；建筑详情请用 "
                    + RwxCliCommands.Command.QUERY_BUILDING.commandName() + " --name " + stats.id);
        }
        return render(index, stats, arguments);
    }

    /** 统一渲染：同一个 Definition 输出 text 或 JSON，detail 决定展开程度。 */
    private static String render(RwxUnitQueryCatalog.Index index,
                                 RwxUnitQueryCatalog.UnitStats stats, List<String> arguments) {
        RwxUnitDefinition definition = RwxDefinitionBuilder.build(index, stats.type);
        if (definition == null) {
            throw new IllegalArgumentException("无法读取 " + stats.id + " 的定义（原型未加载？）");
        }
        boolean detail = RwxCliArgs.flag(arguments, "--detail");
        return RwxCliArgs.flag(arguments, "--json")
                ? RwxDefinitionRenderer.json(definition, detail)
                : RwxDefinitionRenderer.text(definition, detail);
    }

    private String buildingSingle(List<String> arguments) {
        String name = RwxCliArgs.required(arguments, "--name");
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        RwxUnitQueryCatalog.UnitStats stats = requireUnit(index, name);
        if (!stats.building) {
            throw new IllegalArgumentException(name + " 不是建筑；单位详情请用 "
                    + RwxCliCommands.Command.QUERY_UNIT.commandName() + " --name " + stats.id);
        }
        return render(index, stats, arguments);
    }

    // ===== 单位列表 =====

    /** 全部单位/建筑的名称索引：id、游戏内名称与分类，作为 --name 的查询入口。 */
    private String nameIndex(RwxUnitQueryCatalog.Index index) {
        List<RwxUnitQueryCatalog.UnitStats> units = new ArrayList<>(index.units());
        units.sort((left, right) -> left.name.compareTo(right.name));
        StringBuilder output = new StringBuilder("全部单位与建筑（共 " + units.size()
                + " 个）。--name 可直接填 id 或括号里的任何写法。");
        output.append("\nid | 名称 | 分类");
        for (RwxUnitQueryCatalog.UnitStats stats : units) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.categoryLabel());
        }
        return output.toString();
    }

    /** 全部建筑的名称索引，与单位的 nameIndex 对称。 */
    private String buildingIndex(RwxUnitQueryCatalog.Index index) {
        List<RwxUnitQueryCatalog.UnitStats> buildings = new ArrayList<>();
        for (RwxUnitQueryCatalog.UnitStats stats : index.units()) {
            if (stats.building) buildings.add(stats);
        }
        buildings.sort((left, right) -> left.name.compareTo(right.name));
        StringBuilder output = new StringBuilder("全部建筑（共 " + buildings.size()
                + " 个）。--name 可直接填 id 或括号里的任何写法。");
        output.append("\nid | 名称 | 分类");
        for (RwxUnitQueryCatalog.UnitStats stats : buildings) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.categoryLabel());
        }
        return output.toString();
    }

    private String transportList() {
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        List<RwxUnitQueryCatalog.UnitStats> units =
                index.inAnyCategory(RwxUnitCatalog.Category.TRANSPORT);
        units.sort((left, right) -> Integer.compare(left.price, right.price));

        StringBuilder output = new StringBuilder("运输单位（共 " + units.size() + " 个）");
        output.append("\nid | 名称 | 价格 | 工厂 | 等级 | 载人容量 | 用时");
        for (RwxUnitQueryCatalog.UnitStats stats : units) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.price)
                    .append(" | ").append(producerLabel(stats))
                    .append(" | ").append(levelLabel(stats))
                    .append(" | ").append(stats.transportCapacity > 0 ? stats.transportCapacity : "-")
                    .append(" | ").append(seconds(stats.buildSeconds));
        }
        return output.toString();
    }

    private String labList() {
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        List<RwxUnitQueryCatalog.UnitStats> units =
                index.inAnyCategory(RwxUnitCatalog.Category.LAB);
        units.sort((left, right) -> Integer.compare(left.price, right.price));
        return unitTable("实验单位", units);
    }

    private String levelUnitList(List<String> arguments, String title,
                                 RwxUnitCatalog.Category category) {
        int level = RwxCliArgs.integer(arguments, "--level", 0);
        if (level < 0 || level > 2) {
            throw new IllegalArgumentException("--level 只能是 0（全部）、1（1 级工厂）或 2（2 级工厂）");
        }
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        List<RwxUnitQueryCatalog.UnitStats> units = index.inAnyCategory(category);
        units.removeIf(stats -> !matchesFactoryLevel(stats, level));
        units.sort((left, right) -> Integer.compare(left.price, right.price));
        String suffix = level == 0 ? "全部等级" : (level == 1 ? "仅 1 级工厂" : "仅 2 级工厂");
        return unitTable(title + "（" + suffix + "）", units);
    }

    private String categoryList(RwxUnitQueryCatalog.Index index, String title,
                                RwxUnitCatalog.Category category) {
        List<RwxUnitQueryCatalog.UnitStats> units = index.inAnyCategory(category);
        units.sort((left, right) -> Integer.compare(left.price, right.price));
        return unitTable(title, units);
    }

    private String unitTable(String title, List<RwxUnitQueryCatalog.UnitStats> units) {
        StringBuilder output = new StringBuilder(title + "（共 " + units.size() + " 个）");
        output.append("\nid | 名称 | 价格 | 工厂 | 等级 | 用时");
        for (RwxUnitQueryCatalog.UnitStats stats : units) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.price)
                    .append(" | ").append(producerLabel(stats))
                    .append(" | ").append(levelLabel(stats))
                    .append(" | ").append(seconds(stats.buildSeconds));
        }
        return output.toString();
    }

    private boolean matchesFactoryLevel(RwxUnitQueryCatalog.UnitStats stats, int level) {
        if (level == 0) return true;
        // “1 级工厂”= 存在 1 级生产建筑就能造（实验工厂按 1 级算）；
        // “2 级工厂”= 必须 2 级生产建筑才能造。移动工程单位能造的路线不参与这个判定。
        boolean hasFactory = false;
        boolean levelOne = false;
        boolean levelTwo = false;
        for (RwxUnitQueryCatalog.Producer producer : stats.allProducers()) {
            if (!producer.isFactory()) continue;
            hasFactory = true;
            if (producer.level <= 1) levelOne = true;
            else levelTwo = true;
        }
        int tech = RwxUnitQueryCatalog.safeTechLevel(stats.type);
        if (!hasFactory) {
            return level == 1 ? tech <= 1 : tech >= 2;
        }
        return level == 1 ? levelOne : (!levelOne && levelTwo);
    }

    // ===== 工厂解析 =====

    private UnitType resolveFactory(RwxUnitQueryCatalog.Index index, String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "land":
                return RwxUnitQueryCatalog.resolve("landFactory");
            case "air":
                return RwxUnitQueryCatalog.resolve("airFactory");
            case "sea":
                return RwxUnitQueryCatalog.resolve("seaFactory");
            case "lab":
                UnitType lab = RwxUnitQueryCatalog.resolve("experimentalLandFactory");
                if (lab == null) lab = RwxUnitQueryCatalog.resolve("laboratory");
                return lab;
            case "commander":
            case "command":
                return RwxUnitQueryCatalog.resolve("commandCenter");
            default:
                UnitType resolved = RwxUnitQueryCatalog.resolve(name);
                if (resolved == null) return null;
                if (!index.producible(resolved, 0).isEmpty()) return resolved;
                return null;
        }
    }

    // ===== 建筑列表 =====

    private String buildingList(String title, RwxUnitCatalog.Category category) {
        RwxUnitQueryCatalog.Index index = RwxUnitQueryCatalog.buildIndex();
        List<RwxUnitQueryCatalog.UnitStats> units = new ArrayList<>();
        for (RwxUnitQueryCatalog.UnitStats stats : index.units()) {
            if (!stats.building || !stats.is(category)) continue;
            units.add(stats);
        }
        units.sort((left, right) -> Integer.compare(left.price, right.price));

        StringBuilder output = new StringBuilder(title + "（共 " + units.size() + " 个）");
        output.append("\nid | 名称 | 价格 | 占地 | 升级路径 | 用时");
        for (RwxUnitQueryCatalog.UnitStats stats : units) {
            output.append("\n").append(stats.id)
                    .append(" | ").append(stats.name)
                    .append(" | ").append(stats.price)
                    .append(" | ").append(footprint(stats))
                    .append(" | ").append(stats.is(RwxUnitCatalog.Category.ECONOMY)
                            ? deepUpgradePath(index, stats, 2)
                            : compactUpgradePath(index, stats))
                    .append(" | ").append(seconds(stats.buildSeconds));
        }
        return output.toString();
    }

    // ===== 文本组合 =====

    private RwxUnitQueryCatalog.UnitStats requireUnit(RwxUnitQueryCatalog.Index index, String name) {
        UnitType type = RwxUnitQueryCatalog.resolve(name);
        if (type == null) {
            throw new IllegalArgumentException(unknownNameMessage(name));
        }
        UnitType effective = RwxUnitQueryCatalog.effectiveType(type);
        RwxUnitQueryCatalog.UnitStats stats = index.get(effective);
        if (stats == null) {
            throw new IllegalArgumentException("该单位不可查询（内部单位或未加载）: " + name);
        }
        return stats;
    }

    /** 名称解析失败时的提示：列出最接近的候选，并说明可以用 id 或游戏内名称。 */
    private static String unknownNameMessage(String name) {
        List<String> candidates = RwxUnitQueryCatalog.suggestNames(name);
        StringBuilder message = new StringBuilder("找不到单位或建筑: ").append(name);
        if (!candidates.isEmpty()) {
            message.append("；可能想找: ").append(String.join("、", candidates));
        }
        message.append("。--name 可填列表里的 id（如 c_tank）、游戏内名称（如 坦克）或内部短名（如 tank）；")
                .append("可先用 rwx-query-unit --category land|air|sea|lab|builder|transport 查看完整列表。");
        return message.toString();
    }

    private static String aliases(RwxUnitQueryCatalog.UnitStats stats) {
        Set<String> aliases = new LinkedHashSet<>();
        String shortName = RwxUnitQueryCatalog.safeShortName(stats.type);
        if (shortName != null && !shortName.equalsIgnoreCase(stats.id)) aliases.add(shortName);
        if (stats.entry != null) aliases.addAll(stats.entry.aliases);
        return aliases.isEmpty() ? "-" : String.join("/", aliases);
    }

    private static String summary(RwxUnitQueryCatalog.UnitStats stats) {
        if (stats.entry != null && stats.entry.summary != null) return stats.entry.summary;
        List<String> lines = RwxUnitQueryCatalog.descriptionLines(stats.type);
        if (!lines.isEmpty()) return lines.get(0);
        return stats.categoryLabel();
    }

    private static void appendDetail(StringBuilder output, RwxUnitQueryCatalog.UnitStats stats) {
        List<String> lines;
        if (stats.entry != null && stats.entry.detail != null) {
            lines = RwxUnitQueryCatalog.splitDescription(stats.entry.detail);
        } else {
            lines = RwxUnitQueryCatalog.descriptionLines(stats.type);
        }
        output.append("\n详细描述:");
        if (lines.isEmpty()) {
            output.append("\n  · 无");
            return;
        }
        for (String line : lines) {
            output.append("\n  · ").append(line);
        }
    }

    private void appendFeatures(StringBuilder output, RwxUnitQueryCatalog.UnitStats stats) {
        Set<String> features = new LinkedHashSet<>();
        if (stats.entry != null) features.addAll(stats.entry.features);
        if (stats.builder) features.add("可建造 " + buildableCount(stats) + " 种单位/建筑");
        if (stats.transport) features.add("运输容量 " + stats.transportCapacity);
        if (stats.canAttack && stats.attackRange > 0) features.add("攻击射程 " + number(stats.attackRange));
        if (stats.experimental) features.add("实验级单位");
        output.append("\n可用功能:");
        if (features.isEmpty()) {
            output.append("\n  · 无特殊功能");
            return;
        }
        for (String feature : features) {
            output.append("\n  · ").append(feature);
        }
    }

    private int buildableCount(RwxUnitQueryCatalog.UnitStats stats) {
        Set<UnitType> produced = new LinkedHashSet<>();
        for (int level = 1; level <= 3; level++) {
            for (com.corrodinggames.rts.game.units.actions.AbstractUnitAction action
                    : RwxUnitQueryCatalog.actionsOf(stats.type, level)) {
                if (action.getUnitType() == null) continue;
                com.corrodinggames.rts.game.units.actions.ActionDisplayType displayType =
                        action.getActionDisplayType();
                if (displayType == com.corrodinggames.rts.game.units.actions.ActionDisplayType.queueUnit
                        || displayType == com.corrodinggames.rts.game.units.actions.ActionDisplayType.building) {
                    produced.add(action.getUnitType());
                }
            }
        }
        return produced.size();
    }

    private void appendBuildable(StringBuilder output, RwxUnitQueryCatalog.Index index,
                                 RwxUnitQueryCatalog.UnitStats stats) {
        List<RwxUnitQueryCatalog.UnitStats> produced = index.producible(stats.type, 0);
        if (produced.isEmpty()) {
            output.append("\n可建造: 无（该单位不是工程单位或生产建筑）");
            return;
        }
        produced.sort((left, right) -> Integer.compare(left.price, right.price));
        output.append("\n可建造/可生产（共 ").append(produced.size()).append(" 个）:");
        for (RwxUnitQueryCatalog.UnitStats unit : produced) {
            output.append("\n  · ").append(unit.name).append("（").append(unit.id).append("）")
                    .append(" 价格 ").append(unit.price)
                    .append(" 用时 ").append(seconds(unit.buildSeconds));
            if (unit.footprint != null) output.append(" 占地 ").append(unit.footprint);
        }
    }

    private static String producerText(RwxUnitQueryCatalog.UnitStats stats) {
        List<RwxUnitQueryCatalog.Producer> producers = stats.allProducers();
        if (producers.isEmpty()) {
            return stats.building ? "不可建造" : "不可生产";
        }
        RwxUnitQueryCatalog.Producer primary = stats.primaryProducer();
        StringBuilder text = new StringBuilder(primary.label()).append("（T").append(primary.level).append("）");
        int otherFactories = 0;
        for (RwxUnitQueryCatalog.Producer producer : producers) {
            if (producer != primary && producer.isFactory()) otherFactories++;
        }
        if (otherFactories > 0) {
            text.append("，另有 ").append(otherFactories).append(" 个工厂可生产");
        }
        return text.toString();
    }

    private static String builderText(RwxUnitQueryCatalog.UnitStats stats) {
        List<RwxUnitQueryCatalog.Producer> producers = stats.allProducers();
        if (producers.isEmpty()) return "不可建造";
        Set<String> names = new LinkedHashSet<>();
        for (RwxUnitQueryCatalog.Producer producer : producers) {
            names.add(producer.label());
        }
        return String.join("、", names);
    }

    private static String producerLabel(RwxUnitQueryCatalog.UnitStats stats) {
        RwxUnitQueryCatalog.Producer primary = stats.primaryProducer();
        return primary == null ? "-" : primary.label();
    }

    private static String levelLabel(RwxUnitQueryCatalog.UnitStats stats) {
        RwxUnitQueryCatalog.Producer primary = stats.primaryProducer();
        return primary == null ? "-" : "T" + primary.level;
    }

    private static String detailedStats(RwxUnitQueryCatalog.UnitStats stats) {
        List<String> parts = new ArrayList<>();
        parts.add("生命 " + number(stats.maxHealth));
        if (stats.canAttack) {
            parts.add("射程 " + number(stats.attackRange));
        }
        if (stats.transport) {
            parts.add("运输容量 " + stats.transportCapacity);
        }
        if (stats.moveSpeed > 0.0f) {
            parts.add("移速 " + number(stats.moveSpeed));
        }
        return String.join("，", parts);
    }

    private static String compactUpgradePath(RwxUnitQueryCatalog.Index index,
                                             RwxUnitQueryCatalog.UnitStats stats) {
        List<RwxUnitQueryCatalog.Upgrade> upgrades = index.upgrades(stats.type);
        if (upgrades.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        int count = Math.min(3, upgrades.size());
        for (int indexInList = 0; indexInList < count; indexInList++) {
            RwxUnitQueryCatalog.Upgrade upgrade = upgrades.get(indexInList);
            parts.add(upgrade.name + "(" + upgrade.price + ")");
        }
        if (upgrades.size() > count) parts.add("...");
        return String.join(" → ", parts);
    }

    /** 递归展开升级链：资源抽取器 T1 → T2 → T3 → 超频/强化。 */
    private static String deepUpgradePath(RwxUnitQueryCatalog.Index index,
                                          RwxUnitQueryCatalog.UnitStats stats, int maxDepth) {
        Set<UnitType> visited = new LinkedHashSet<>();
        visited.add(stats.type);
        String text = deepUpgradeText(index, stats.type, visited, maxDepth);
        return text.isEmpty() ? "无" : text;
    }

    private static String deepUpgradeText(RwxUnitQueryCatalog.Index index, UnitType type,
                                          Set<UnitType> visited, int remaining) {
        if (remaining <= 0) return "";
        List<String> parts = new ArrayList<>();
        for (RwxUnitQueryCatalog.Upgrade upgrade : index.upgrades(type)) {
            StringBuilder part = new StringBuilder(upgrade.name).append("（").append(upgrade.price).append("）");
            UnitType target = upgrade.target;
            if (target != null && visited.add(target)) {
                part.append(" → ").append(upgrade.targetId());
                String child = deepUpgradeText(index, target, visited, remaining - 1);
                if (!child.isEmpty()) part.append(" → ").append(child);
            }
            parts.add(part.toString());
        }
        String joined = String.join("；", parts);
        return joined.length() > 200 ? joined.substring(0, 200) + "…" : joined;
    }

    private static String footprint(RwxUnitQueryCatalog.UnitStats stats) {
        return stats.footprint == null ? "-" : stats.footprint;
    }

    private static String seconds(float value) {
        if (!(value > 0.0f) || !Float.isFinite(value)) return "未知";
        return String.format(Locale.ROOT, "%.1f秒", value);
    }

    private static String number(float value) {
        if (value == 0.0f) return "0";
        if (value == Math.round(value)) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

