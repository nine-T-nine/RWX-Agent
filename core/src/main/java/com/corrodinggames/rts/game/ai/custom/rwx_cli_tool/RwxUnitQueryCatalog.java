package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionDisplayType;
import com.corrodinggames.rts.game.units.buildings.BaseBuilding;
import com.corrodinggames.rts.game.units.buildings.turrets.TurretFactory;
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig;
import io.github.rwx.geometry.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单位/建筑查询命令共用的只读目录：命令名、单位解析、生产来源索引与统计快照。
 *
 * <p>数据来源分两层：手写语义（{@link RwxUnitCatalog}）与引擎实时数据
 * （{@code UnitType} / {@code BaseUnit} 原型）。价格、生命、射程、占地、建造用时、
 * 生产工厂与等级、可建造列表都实时读取，所以 mod 调整数值后查询结果自动跟随；
 * 手写层只负责“这个单位是干什么的、有什么特殊功能”。</p>
 *
 * <p>{@link Index} 在每条命令执行时构建一次，把 O(单位数 × 等级 × 动作数) 的
 * 生产来源扫描压到一次，避免列表命令里反复全表扫描。</p>
 */
public final class RwxUnitQueryCatalog {

    /** 非可玩/内部单位，永远不出现在查询列表里。 */
    private static final Set<String> INTERNAL_IDS = new LinkedHashSet<>(java.util.Arrays.asList(
            "tree", "crystalResource", "crystal_mid", "fogRevealer", "spreadingFire",
            "damagingBorder", "zoneMarker", "editorOrBuilder", "dummyNonUnitWithTeam",
            "creditsCrates", "flare_10s", "missing", "NULL"));

    /**
     * 虫族、侦查小机器人、模块化蜘蛛的挂件、以及“形态切换”用的隐藏单位
     * （着陆/上浮/水下/过渡形态）都只在引擎内部或特殊模式出现，查询列表不展示。
     */
    private static boolean isInternalId(String id) {
        if (id == null || INTERNAL_IDS.contains(id)) return true;
        String lowered = id.toLowerCase(Locale.ROOT);
        return lowered.startsWith("bug")
                || lowered.startsWith("robotcrab")
                || lowered.startsWith("modularspider_")
                || lowered.endsWith("deployed")
                || lowered.endsWith("landed")
                || lowered.endsWith("takeoff")
                || lowered.endsWith("_underwater")
                || lowered.contains("_transition")
                || lowered.startsWith("nautilussubmarineland")
                || lowered.startsWith("nautilussubmarinesurface");
    }

    private RwxUnitQueryCatalog() { }

    /** 一次查询期间的单位快照。 */
    public static final class Index {
        private final List<UnitStats> units;
        private final Map<UnitType, UnitStats> byType;
        private final Map<UnitType, List<Producer>> producers;
        private final Map<UnitType, List<Upgrade>> upgrades;

        private Index(List<UnitStats> units, Map<UnitType, UnitStats> byType,
                      Map<UnitType, List<Producer>> producers, Map<UnitType, List<Upgrade>> upgrades) {
            this.units = units;
            this.byType = byType;
            this.producers = producers;
            this.upgrades = upgrades;
        }

        public List<UnitStats> units() {
            return units;
        }

        public UnitStats get(UnitType type) {
            return type == null ? null : byType.get(type);
        }

        public List<Producer> producers(UnitType type) {
            List<Producer> result = type == null ? null : producers.get(type);
            return result == null ? Collections.emptyList() : result;
        }

        public List<Upgrade> upgrades(UnitType type) {
            List<Upgrade> result = type == null ? null : upgrades.get(type);
            return result == null ? Collections.emptyList() : result;
        }

        /** 指定的生产建筑在某一科技等级能生产的单位；level=0 表示全部等级。 */
        public List<UnitStats> producible(UnitType factory, int level) {
            List<UnitStats> result = new ArrayList<>();
            if (factory == null) return result;
            Set<UnitType> seen = new LinkedHashSet<>();
            int from = level <= 0 ? 1 : Math.min(level, 3);
            int to = level <= 0 ? 3 : Math.min(level, 3);
            for (int current = from; current <= to; current++) {
                for (AbstractUnitAction action : actionsOf(factory, current)) {
                    if (!isProductionAction(action)) continue;
                    UnitType produced = action.getUnitType();
                    if (produced == null || isInternalId(safeTypeId(produced)) || !seen.add(produced)) continue;
                    UnitStats stats = byType.get(produced);
                    if (stats != null) result.add(stats);
                }
            }
            return result;
        }

        public List<UnitStats> inAnyCategory(RwxUnitCatalog.Category... categories) {
            List<UnitStats> result = new ArrayList<>();
            for (UnitStats stats : units) {
                for (RwxUnitCatalog.Category category : categories) {
                    if (stats.is(category)) {
                        result.add(stats);
                        break;
                    }
                }
            }
            return result;
        }
    }

    /** 一个可查询的单位/建筑及其实时统计。 */
    public static final class UnitStats {
        public final UnitType type;
        public final String id;
        public final String name;
        public final int price;
        public final int techLevel;
        public final float buildSeconds;
        public final boolean building;
        public final boolean builder;
        public final boolean transport;
        public final int transportCapacity;
        public final boolean canAttack;
        public final float maxHealth;
        public final float attackRange;
        public final float moveSpeed;
        public final boolean experimental;
        public final UnitMovementType movement;
        public final String footprint;
        public final RwxUnitCatalog.Entry entry;
        private final Set<RwxUnitCatalog.Category> categories;
        private final List<Producer> producers;

        private UnitStats(UnitType type, List<Producer> producers) {
            this.type = type;
            this.id = safeTypeId(type);
            this.name = displayName(type);
            this.price = type.c();
            this.techLevel = type.g();
            this.buildSeconds = buildSeconds(type.D());
            this.building = type.isBuildingUnit();
            this.builder = type.l() || type.m();
            BaseUnit prototype = prototype(type);
            this.maxHealth = prototype == null ? 0.0f : prototype.maxHealth;
            this.canAttack = prototype != null && prototype.canAttack();
            this.attackRange = prototype instanceof OrderableUnit ? ((OrderableUnit) prototype).m() : 0.0f;
            this.moveSpeed = prototype instanceof OrderableUnit ? ((OrderableUnit) prototype).getMoveSpeed() : 0.0f;
            this.transportCapacity = transportCapacity(type, prototype);
            this.transport = prototype != null && prototype.canTransportUnits();
            this.experimental = isExperimental(type, prototype);
            this.movement = safeMovement(type);
            this.footprint = footprint(type, prototype);
            this.entry = RwxUnitCatalog.find(java.util.Arrays.asList(
                    this.id, safeShortName(type), this.name));
            this.producers = producers;
            this.categories = deriveCategories(this);
        }

        public Producer primaryProducer() {
            Producer bestFactory = null;
            Producer bestAny = null;
            for (Producer candidate : producers) {
                if (bestAny == null || isBetterProducer(candidate, bestAny)) bestAny = candidate;
                if (!candidate.isFactory()) continue;
                if (bestFactory == null || isBetterProducer(candidate, bestFactory)) bestFactory = candidate;
            }
            // 优先报告真正的生产建筑；工程单位也能“造”一些单位，但不该抢工厂的位置。
            return bestFactory != null ? bestFactory : bestAny;
        }

        private static boolean isBetterProducer(Producer candidate, Producer current) {
            if (candidate.level != current.level) return candidate.level < current.level;
            int candidateRank = factoryRank(candidate.type);
            int currentRank = factoryRank(current.type);
            if (candidateRank != currentRank) return candidateRank < currentRank;
            return displayName(candidate.type).compareTo(displayName(current.type)) < 0;
        }

        public List<Producer> allProducers() {
            return Collections.unmodifiableList(producers);
        }

        public boolean is(RwxUnitCatalog.Category category) {
            return categories.contains(category);
        }

        public Set<RwxUnitCatalog.Category> categories() {
            return categories;
        }

        public String categoryLabel() {
            List<String> labels = new ArrayList<>();
            for (RwxUnitCatalog.Category category : categories) {
                if (category == RwxUnitCatalog.Category.DEFENSE && categories.contains(RwxUnitCatalog.Category.TURRET)) {
                    continue;
                }
                if (category == RwxUnitCatalog.Category.OTHER && categories.size() > 1) continue;
                labels.add(category.label);
            }
            return String.join("、", labels);
        }
    }

    /** 生产来源：哪个单位/建筑在哪个科技等级能生产它。 */
    public static final class Producer {
        public final UnitType type;
        public final int level;

        Producer(UnitType type, int level) {
            this.type = type;
            this.level = level;
        }

        public String label() {
            return displayName(type);
        }

        public boolean isFactory() {
            return type.isBuildingUnit() && !type.l();
        }
    }

    /** 建筑的升级分支（原地升级或转换）。 */
    public static final class Upgrade {
        public final String name;
        public final int price;
        public final UnitType target;

        Upgrade(String name, int price, UnitType target) {
            this.name = name;
            this.price = price;
            this.target = target;
        }

        public String targetId() {
            return target == null ? null : safeTypeId(target);
        }
    }

    /** 构建一次查询快照。 */
    public static Index buildIndex() {
        List<UnitType> types = effectiveTypes();

        // 先扫描生产/升级动作，再构建 UnitStats，保证分类用到完整来源信息。
        Map<UnitType, Map<UnitType, Integer>> producerLevels = new LinkedHashMap<>();
        Map<UnitType, List<Upgrade>> upgrades = new LinkedHashMap<>();
        for (UnitType source : types) {
            for (int level = 1; level <= 3; level++) {
                for (AbstractUnitAction action : actionsOf(source, level)) {
                    if (isProductionAction(action)) {
                        UnitType produced = action.getUnitType();
                        if (produced == null) continue;
                        int effectiveLevel = Math.max(level, safeTechLevel(source));
                        Map<UnitType, Integer> byProducer =
                                producerLevels.computeIfAbsent(produced, key -> new LinkedHashMap<>());
                        Integer previous = byProducer.get(source);
                        if (previous == null || effectiveLevel < previous) byProducer.put(source, effectiveLevel);
                    }
                }
            }
            // 部分内置建筑（炮塔、激光防御塔等）的升级动作挂在原型实例上，
            // 不在 UnitType.a(level) 里，所以原型动作表也要扫一遍。
            List<AbstractUnitAction> upgradeCandidates = new ArrayList<>();
            for (int level = 1; level <= 3; level++) upgradeCandidates.addAll(actionsOf(source, level));
            BaseUnit prototype = prototype(source);
            if (prototype != null) {
                try {
                    List<AbstractUnitAction> prototypeActions = prototype.getAvailableActions();
                    if (prototypeActions != null) upgradeCandidates.addAll(prototypeActions);
                } catch (RuntimeException ignored) {
                    // 原型动作不可用时只用类型级动作表
                }
            }
            for (AbstractUnitAction action : upgradeCandidates) {
                if (action == null || action.getActionDisplayType() != ActionDisplayType.upgrade) continue;
                List<Upgrade> list = upgrades.computeIfAbsent(source, key -> new ArrayList<>());
                String name = action.getDisplayName();
                boolean duplicate = false;
                for (Upgrade existing : list) {
                    if (existing.name.equals(name)) duplicate = true;
                }
                if (!duplicate) {
                    UnitType target = action.getUnitType();
                    if (target == null) target = action.getAttachedUnitType();
                    list.add(new Upgrade(name, action.getCostAmount(), target));
                }
            }
        }

        List<UnitStats> units = new ArrayList<>();
        Map<UnitType, UnitStats> byType = new LinkedHashMap<>();
        Map<UnitType, List<Producer>> producers = new LinkedHashMap<>();
        for (UnitType type : types) {
            List<Producer> sources = new ArrayList<>();
            Map<UnitType, Integer> levels = producerLevels.get(type);
            if (levels != null) {
                for (Map.Entry<UnitType, Integer> entry : levels.entrySet()) {
                    if (isInternalId(safeTypeId(entry.getKey()))) continue;
                    sources.add(new Producer(entry.getKey(), entry.getValue()));
                }
                sources.sort((left, right) -> {
                    int comparison = Integer.compare(left.level, right.level);
                    return comparison != 0 ? comparison : displayName(left.type).compareTo(displayName(right.type));
                });
            }
            producers.put(type, Collections.unmodifiableList(sources));
            UnitStats stats = new UnitStats(type, Collections.unmodifiableList(sources));
            units.add(stats);
            byType.put(type, stats);
        }

        return new Index(Collections.unmodifiableList(units),
                Collections.unmodifiableMap(byType),
                Collections.unmodifiableMap(producers),
                Collections.unmodifiableMap(upgrades));
    }

    /**
     * 解析查询参数里的单位名。
     *
     * <p>游戏内部其实有四套名字，这里全部接受：</p>
     * <ol>
     *   <li><b>内部 id</b>：{@code UnitType.v()}，即 ini 的 {@code [core] name}（如 {@code c_tank}）
     *       或 Java 枚举常量名（如 {@code landFactory}）；</li>
     *   <li><b>短名</b>：{@code getUnitTypeDescriptionShort()}，也是
     *       {@code overrideAndReplace} / {@code copyFrom} / {@code builtFrom} 里写的名字（如 {@code tank}）；</li>
     *   <li><b>本地化显示名</b>：{@code getUnitName()}，即 {@code units.<displayLocaleKey|name>.name}
     *       的中文/英文文本（如 {@code 坦克}、{@code Tank}）；</li>
     *   <li><b>描述文本</b>：自定义单位 ini 里手写的 {@code displayText}（没有本地化翻译时的兜底名）。</li>
     * </ol>
     *
     * <p>匹配顺序：引擎查找 → 归一化后的精确匹配（忽略大小写、空格、下划线、连字符）→
     * 唯一的部分匹配。同名/部分匹配到多个时不猜，交给调用方列出候选。</p>
     */
    public static UnitType resolve(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        UnitType direct = null;
        try {
            direct = UnitTypeEnum.getUnitTypeByName(trimmed);
        } catch (RuntimeException ignored) {
            // 交给下面的回退逻辑
        }
        if (direct != null) return effectiveType(direct);

        String normalized = normalizeName(trimmed);
        if (normalized.isEmpty()) return null;

        List<UnitType> exact = collectByName(effectiveTypes(), normalized, true);
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return canonicalVariant(exact);

        if (normalized.length() >= 2) {
            List<UnitType> partial = collectByName(effectiveTypes(), normalized, false);
            if (partial.size() == 1) return partial.get(0);
        }
        return null;
    }

    /**
     * 多个单位共用同一个显示名时（例如资源抽取器的 T1/T2/T3 都叫“资源抽取器”），
     * 选最低科技、最低价格的那一个作为默认结果；仍然并列就返回 null，让调用方列候选。
     */
    private static UnitType canonicalVariant(List<UnitType> types) {
        UnitType best = null;
        int bestTech = Integer.MAX_VALUE;
        int bestPrice = Integer.MAX_VALUE;
        for (UnitType type : types) {
            int tech = safeTechLevel(type);
            int price = priceOf(type);
            if (best == null || tech < bestTech || (tech == bestTech && price < bestPrice)) {
                best = type;
                bestTech = tech;
                bestPrice = price;
            }
        }
        int sameScore = 0;
        for (UnitType type : types) {
            if (safeTechLevel(type) == bestTech && priceOf(type) == bestPrice) sameScore++;
        }
        return sameScore == 1 ? best : null;
    }

    private static int priceOf(UnitType type) {
        try {
            return type.c();
        } catch (RuntimeException error) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 找不到单位时给出的候选：先精确/部分匹配，再按编辑距离补充近似项。
     *
     * @return 形如 {@code 坦克（c_tank）} 的候选列表，最多 6 条
     */
    public static List<String> suggestNames(String name) {
        if (name == null) return Collections.emptyList();
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        String normalized = normalizeName(trimmed);
        List<UnitType> types = effectiveTypes();
        List<UnitType> matches = new ArrayList<>(collectByName(types, normalized, false));
        if (matches.isEmpty() && normalized.length() >= 2) {
            for (UnitType type : types) {
                if (closeEnough(normalized, normalizeName(safeTypeId(type)))
                        || closeEnough(normalized, normalizeName(displayName(type)))) {
                    matches.add(type);
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (UnitType type : matches) {
            if (result.size() >= 6) break;
            String label = displayName(type) + "（" + safeTypeId(type) + "）";
            if (!result.contains(label)) result.add(label);
        }
        return result;
    }

    /** 归一化：小写并去掉空白、下划线、连字符、点号与常见全角括号。 */
    public static String normalizeName(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            if (Character.isWhitespace(character)) continue;
            switch (character) {
                case '_':
                case '-':
                case '.':
                case '·':
                case '（':
                case '）':
                case '(':
                case ')':
                case '\u3000':
                    continue;
                default:
                    normalized.append(character);
            }
        }
        return normalized.toString();
    }

    /** 该单位所有可用于 --name 的写法（已归一化）。 */
    private static List<String> nameForms(UnitType type) {
        List<String> forms = new ArrayList<>(6);
        addForm(forms, safeTypeId(type));
        addForm(forms, safeShortName(type));
        addForm(forms, displayName(type));
        addForm(forms, customDisplayText(type));
        RwxUnitCatalog.Entry entry = RwxUnitCatalog.find(java.util.Arrays.asList(
                safeTypeId(type), safeShortName(type), displayName(type)));
        if (entry != null) {
            addForm(forms, entry.id);
            for (String alias : entry.aliases) addForm(forms, alias);
        }
        return forms;
    }

    private static void addForm(List<String> forms, String value) {
        String normalized = normalizeName(value);
        if (!normalized.isEmpty() && !forms.contains(normalized)) forms.add(normalized);
    }

    private static List<UnitType> collectByName(List<UnitType> types, String normalized, boolean exact) {
        List<UnitType> matches = new ArrayList<>();
        if (normalized.isEmpty()) return matches;
        for (UnitType type : types) {
            boolean hit = false;
            for (String form : nameForms(type)) {
                if (exact ? form.equals(normalized) : form.contains(normalized)) {
                    hit = true;
                    break;
                }
            }
            if (hit) matches.add(type);
        }
        return matches;
    }

    /** 自定义单位 ini 的 displayText，仅在没有本地化翻译时用于兜底和名字匹配。 */
    private static String customDisplayText(UnitType type) {
        if (!(type instanceof CustomUnitConfig)) return null;
        CustomUnitConfig config = (CustomUnitConfig) type;
        if (config.displayText == null) return null;
        try {
            return config.displayText.resolveText();
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 简单近似判断：前缀/包含，或长度接近时允许 1~2 个字符差异。 */
    private static boolean closeEnough(String query, String candidate) {
        if (query.isEmpty() || candidate.isEmpty()) return false;
        if (candidate.startsWith(query) || candidate.contains(query) || query.contains(candidate)) return true;
        if (Math.abs(query.length() - candidate.length()) > 2) return false;
        int limit = query.length() <= 4 ? 1 : 2;
        return editDistanceAtMost(query, candidate, limit);
    }

    private static boolean editDistanceAtMost(String left, String right, int limit) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            int rowMinimum = current[0];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        previous[rightIndex - 1] + cost);
                rowMinimum = Math.min(rowMinimum, current[rightIndex]);
            }
            if (rowMinimum > limit) return false;
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()] <= limit;
    }

    /** 名称未被 override 替换时返回自身，被替换时返回替换后的类型。 */
    public static UnitType effectiveType(UnitType type) {
        if (type == null) return null;
        UnitType replaced = CustomUnitConfig.unitTypeOverrides.get(type);
        return replaced == null ? type : replaced;
    }

    /** 参与查询的有效单位列表：过滤掉被 override 覆盖的旧类型与内部单位。 */
    public static List<UnitType> effectiveTypes() {
        List<UnitType> candidates = new ArrayList<>();
        List<UnitType> registry = UnitTypeEnum.ae;
        if (registry != null && !registry.isEmpty()) {
            candidates.addAll(registry);
        } else {
            Collections.addAll(candidates, UnitTypeEnum.values());
        }
        List<UnitType> result = new ArrayList<>();
        Set<UnitType> seen = new LinkedHashSet<>();
        for (UnitType type : candidates) {
            if (type == null || !seen.add(type)) continue;
            if (CustomUnitConfig.unitTypeOverrides.containsKey(type)) continue;
            // showInEditor=false 多为隐藏的形态/过渡单位；隐藏的生产建筑（如 T2 机械工厂）
            // 仍要保留，否则它生产的单位会丢失“所需工厂”。
            if (type instanceof CustomUnitConfig && !((CustomUnitConfig) type).showInEditor
                    && !(type.isBuildingUnit() && hasProductionActions(type))) {
                continue;
            }
            String id = safeTypeId(type);
            if (id == null || isInternalId(id)) continue;
            // 自定义单位只有在配置真正加载后才可查询；内置枚举始终保留，
            // 这样原型缓存暂时为空时也不会把所有单位都过滤掉。
            if (prototype(type) == null && type instanceof CustomUnitConfig) continue;
            result.add(type);
        }
        return result;
    }

    public static BaseUnit prototype(UnitType type) {
        if (type == null) return null;
        try {
            BaseUnit prototype = BaseUnit.findTurretPosition(type);
            if (prototype == null) prototype = BaseUnit.getPrototypeForUnitType(type);
            return prototype;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 单位在界面上的显示名，缺失时退回内部名。 */
    public static String displayName(UnitType type) {
        if (type == null) return "?";
        try {
            String name = type.getUnitName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (RuntimeException ignored) {
            // 本地化缺失时使用内部名
        }
        String shortName = safeShortName(type);
        return shortName == null ? safeTypeId(type) : shortName;
    }

    public static String safeTypeId(UnitType type) {
        if (type == null) return null;
        try {
            return type.v();
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static String safeShortName(UnitType type) {
        if (type == null) return null;
        try {
            return type.getUnitTypeDescriptionShort();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static UnitMovementType safeMovement(UnitType type) {
        try {
            return type.o();
        } catch (RuntimeException error) {
            return UnitMovementType.NONE;
        }
    }

    private static boolean isExperimental(UnitType type, BaseUnit prototype) {
        if (type instanceof CustomUnitConfig && ((CustomUnitConfig) type).experimental) return true;
        return prototype != null && prototype.isExperimental();
    }

    private static int transportCapacity(UnitType type, BaseUnit prototype) {
        if (type instanceof CustomUnitConfig) {
            return Math.max(0, ((CustomUnitConfig) type).maxTransportingUnits);
        }
        if (prototype == null) return 0;
        int weight = prototype.getMaxTransportWeight();
        return weight > 0 ? weight : 0;
    }

    /** 建造/生产用时（秒）：进度每秒增加 buildSpeed × 60。 */
    public static float buildSeconds(float buildSpeed) {
        if (!(buildSpeed > 0.0f) || !Float.isFinite(buildSpeed)) return -1.0f;
        return 1.0f / (buildSpeed * 60.0f);
    }

    /** 生产建筑的“推荐顺序”，同一等级下优先报告玩家最常用的工厂。 */
    private static int factoryRank(UnitType type) {
        String lowered = safeTypeId(type);
        if (lowered == null) return 20;
        lowered = lowered.toLowerCase(Locale.ROOT);
        if (lowered.contains("commandcenter")) return 0;
        // 注意顺序：实验工厂的名字里也含 "landfactory"，必须先判实验/实验室。
        if (lowered.contains("experimentallandfactory") || lowered.contains("laboratory")) return 5;
        if (lowered.contains("landfactory")) return 1;
        if (lowered.contains("airfactory")) return 2;
        if (lowered.contains("seafactory")) return 3;
        if (lowered.contains("mechfactory")) return 4;
        return 10;
    }

    /** 建筑占地（单位：格）。取放置用 footprint；自定义单位的 Rect 是闭区间，即 (0,0,0,0)=1x1。 */
    private static String footprint(UnitType type, BaseUnit prototype) {
        if (!type.isBuildingUnit() || prototype == null) return null;
        Rect rect = null;
        if (type instanceof CustomUnitConfig) {
            rect = ((CustomUnitConfig) type).constructionFootprint;
        } else if (prototype instanceof BaseBuilding) {
            rect = ((BaseBuilding) prototype).cc();
        }
        if (rect == null) return null;
        int width = Math.max(1, rect.c - rect.a + 1);
        int height = Math.max(1, rect.d - rect.b + 1);
        return width + "x" + height;
    }

    private static Set<RwxUnitCatalog.Category> deriveCategories(UnitStats stats) {
        Set<RwxUnitCatalog.Category> categories = new LinkedHashSet<>();
        if (stats.entry != null) categories.add(stats.entry.category);

        BaseUnit prototype = prototype(stats.type);
        boolean producesUnits = hasProductionActions(stats.type);
        boolean turret = prototype instanceof TurretFactory
                || (stats.building && stats.canAttack && !producesUnits);

        if (stats.building) {
            if (producesUnits) categories.add(RwxUnitCatalog.Category.PRODUCTION);
            if (turret) {
                categories.add(RwxUnitCatalog.Category.TURRET);
                categories.add(RwxUnitCatalog.Category.DEFENSE);
            }
            if (isEconomy(stats.type)) categories.add(RwxUnitCatalog.Category.ECONOMY);
            if (isNuke(stats.type)) categories.add(RwxUnitCatalog.Category.NUKE);
            if (isDefense(stats.type)) categories.add(RwxUnitCatalog.Category.DEFENSE);
        } else {
            if (stats.builder) categories.add(RwxUnitCatalog.Category.BUILDER);
            if (stats.transport) categories.add(RwxUnitCatalog.Category.TRANSPORT);
            if (stats.experimental) categories.add(RwxUnitCatalog.Category.LAB);
            if (stats.canAttack) {
                switch (stats.movement) {
                    case LAND:
                    case OVER_CLIFF:
                    case HOVER:
                        categories.add(RwxUnitCatalog.Category.LAND);
                        break;
                    case AIR:
                        categories.add(RwxUnitCatalog.Category.AIR);
                        break;
                    case OVER_CLIFF_WATER:
                        // 本整合包里 OVER_CLIFF_WATER 也用于“全地形步行单位”（实验蜘蛛、模块化蜘蛛），
                        // 因此按地面单位归类；真正的飞行单位由手写目录的 AIR 分类补齐。
                        categories.add(RwxUnitCatalog.Category.LAND);
                        break;
                    case WATER:
                        categories.add(RwxUnitCatalog.Category.SEA);
                        break;
                    default:
                        break;
                }
            }
        }
        if (categories.isEmpty()) categories.add(RwxUnitCatalog.Category.OTHER);
        return Collections.unmodifiableSet(categories);
    }

    private static boolean isEconomy(UnitType type) {
        String id = safeTypeId(type);
        if (id != null) {
            String lowered = id.toLowerCase(Locale.ROOT);
            if (lowered.contains("extractor") || lowered.contains("fabricator")) return true;
        }
        return type.p();
    }

    private static boolean isNuke(UnitType type) {
        String id = safeTypeId(type);
        return id != null && id.toLowerCase(Locale.ROOT).contains("nuke");
    }

    private static boolean isDefense(UnitType type) {
        String id = safeTypeId(type);
        if (id == null) return false;
        String lowered = id.toLowerCase(Locale.ROOT);
        return lowered.contains("laserdefence") || lowered.contains("laser_defense")
                || lowered.contains("repairbay");
    }

    private static boolean isProductionAction(AbstractUnitAction action) {
        if (action == null || action.getUnitType() == null) return false;
        ActionDisplayType displayType = action.getActionDisplayType();
        return displayType == ActionDisplayType.queueUnit || displayType == ActionDisplayType.building;
    }

    private static boolean hasProductionActions(UnitType type) {
        for (int level = 1; level <= 3; level++) {
            for (AbstractUnitAction action : actionsOf(type, level)) {
                if (isProductionAction(action)) return true;
            }
        }
        return false;
    }

    /** 安全读取某等级的动作列表；未初始化或已损坏的单位返回空表。 */
    public static List<AbstractUnitAction> actionsOf(UnitType type, int level) {
        if (type == null) return Collections.emptyList();
        try {
            List<AbstractUnitAction> actions = type.a(Math.max(1, Math.min(3, level)));
            return actions == null ? Collections.emptyList() : actions;
        } catch (RuntimeException error) {
            return Collections.emptyList();
        }
    }

    /** 单位的本地化详细描述（去掉 [[ ]] 标记后的分条文本）。 */
    public static List<String> descriptionLines(UnitType type) {
        if (type == null) return Collections.emptyList();
        String description;
        try {
            description = type.f();
        } catch (RuntimeException error) {
            return Collections.emptyList();
        }
        return splitDescription(description);
    }

    public static List<String> splitDescription(String description) {
        if (description == null || description.trim().isEmpty()) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        String normalized = description.replace("[[", "\n").replace("]]", "\n");
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    /** 单位自身的科技等级；unknown 时按 1 处理。 */
    public static int safeTechLevel(UnitType type) {
        if (type == null) return 1;
        try {
            return Math.max(1, Math.min(3, type.g()));
        } catch (RuntimeException error) {
            return 1;
        }
    }
}




