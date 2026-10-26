package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单位/建筑的手写描述表：简化级、详细级与可用功能。
 *
 * <p>查询命令不直接把这些文字当成唯一数据源，而是把这里当“人工补充层”：
 * 价格、生命、射程、占地、建造用时、生产工厂、可建造列表等仍然从
 * {@code UnitType} / {@code BaseUnit} 原型实时读取，mod 换版本时数值不会过期；
 * 这里只补上机器读不出来的语义（一句话定位、玩法说明、特殊功能）。</p>
 *
 * <p>{@code id} 使用本整合包（{@code assets/units}）里 {@code overrideAndReplace}
 * 之后的有效单位名；{@code aliases} 用来兼容 Java 枚举名、旧版单位名与常见中文别名。
 * 查不到的单位会自动回退到游戏自带的本地化 description，因此自定义/mod 单位不会缺失。</p>
 */
public final class RwxUnitCatalog {
    private RwxUnitCatalog() { }

    /** 查询命令使用的粗分类。一个单位可能同时属于多个分类（例如实验单位也是陆地单位）。 */
    public enum Category {
        BUILDER("工程单位"),
        TRANSPORT("运输单位"),
        LAND("陆地攻击单位"),
        AIR("空军攻击单位"),
        SEA("海军攻击单位"),
        LAB("实验单位"),
        PRODUCTION("生产建筑"),
        TURRET("防御炮塔"),
        DEFENSE("防御建筑"),
        ECONOMY("经济建筑"),
        NUKE("核设施"),
        OTHER("其他");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** 一条不可变的单位描述。 */
    public static final class Entry {
        public final String id;
        public final List<String> aliases;
        public final Category category;
        /** 简化级：一句话说明这个单位是什么。 */
        public final String summary;
        /** 详细级：可空；为空时改用游戏本地化 description。 */
        public final String detail;
        /** 可用功能：建造、运输、特殊能力等无法从数据推断的说明。 */
        public final List<String> features;

        Entry(String id, String[] aliases, Category category, String summary,
              String detail, String[] features) {
            this.id = id;
            this.aliases = Collections.unmodifiableList(java.util.Arrays.asList(aliases));
            this.category = category;
            this.summary = summary;
            this.detail = detail;
            this.features = Collections.unmodifiableList(java.util.Arrays.asList(features));
        }

        public boolean matches(String loweredName) {
            if (id.equalsIgnoreCase(loweredName)) return true;
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(loweredName)) return true;
            }
            return false;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static void add(String id, String[] aliases, Category category, String summary,
                            String detail, String... features) {
        ENTRIES.add(new Entry(id, aliases, category, summary, detail, features));
    }

    private static void add(String id, Category category, String summary, String... features) {
        add(id, new String[0], category, summary, null, features);
    }

    static {
        // ===== 工程单位 =====
        add("builder", Category.BUILDER,
                "最基础的工程单位，负责建造与维修建筑，本身没有攻击能力。",
                "可建造建筑，可维修建筑与单位", "不能攻击", "价格低，适合开局扩张");
        add("builderShip", Category.BUILDER,
                "只能在水上作业的工程单位，负责海岸线附近的建筑。",
                "可建造建筑，可维修建筑与单位", "只能在水面移动", "不能攻击");
        add("combatEngineer", Category.BUILDER,
                "T2 重型工程单位，既能建造单位和 T2 建筑，也能对地作战。",
                "可建造单位与 T2 建筑", "对地攻击，在水上时可以攻击潜艇",
                "自我修复并内置资源制造仪", "可以在水上移动");
        add("mechEngineer", Category.BUILDER,
                "机甲的工程单位，负责建造机甲工厂与 T2 建筑，并带防空导弹。",
                "可建造机甲与 T2 建筑", "配有防空导弹", "全副武装");
        add("experimentalSpider", Category.BUILDER,
                "实验级工程巨兽：建造、群体维修、激光防御与核爆于一身。",
                "可建造单位与建筑", "自动修复附近的单位", "自带激光防御",
                "能在水上移动", "自我修复并内置资源制造仪", "死亡后核爆（禁核模式除外）");
        add("experiementalCarrier", Category.BUILDER,
                "实验级海上母舰：以 2 倍速度建造空军，并自带一整套防御与生产设施。",
                "可建造空军单位（2 倍建造速度）", "内置激光防御与修复湾",
                "自带高射炮防空", "内置资源制造仪", "可以制造反核导弹",
                "死亡后核爆（禁核模式除外）");
        add("nautilusSubmarine", Category.BUILDER,
                "可下潜/上浮的实验潜艇：能建造舰船、运输单位，并可在陆地上行动。",
                "可建造舰船与部分陆地单位", "可下潜躲避攻击，上浮后对地/对空",
                "运输容量 3，可运输轻型单位", "死亡后核爆（禁核模式除外）");

        // ===== 运输单位 =====
        add("dropship", Category.TRANSPORT,
                "空中运输机，用来把地面单位投送到战场任意位置。",
                "运输容量 4", "不能攻击");
        add("hovercraft", Category.TRANSPORT,
                "两栖登陆艇，能在陆地和水面移动，负责把部队送上海岸。",
                "运输容量 4", "能在陆地和水面移动", "不能攻击");
        add("experimentalDropship", Category.TRANSPORT,
                "实验级飞行堡垒：大容量空运、空中建造与对地/对空火力兼备。",
                "运输容量 12", "可建造单位", "可以对空，降落后可以对地攻击",
                "能自我修复并内置资源制造仪");
        add("nautilusSubmarine", Category.TRANSPORT,
                "可下潜的实验潜艇，同时也是水下运兵船。",
                "运输容量 3", "可建造舰船与部分陆地单位", "可下潜躲避攻击");

        // ===== 陆地攻击单位 =====
        add("c_tank", new String[] {"tank"}, Category.LAND,
                "廉价快速的基础坦克，只能攻击地面单位。",
                "只能攻击地面单位", "移动速度快、生命值偏低");
        add("hoverTank", Category.LAND,
                "轻型悬浮坦克，能越过水面并兼顾对空。",
                "轻装甲", "能在水上前进", "可以攻击空中和地面单位", "正面换血不划算");
        add("c_artillery", new String[] {"artillery"}, Category.LAND,
                "远程自行火炮，射程远但装甲薄、射速慢。",
                "只能攻击地面单位", "远距离打击");
        add("c_laserTank", new String[] {"laserTank"}, Category.LAND,
                "激光坦克，单发伤害极高但充能慢。",
                "可以攻击地面和空中单位", "不能同时对付多个敌人", "单发强、充能慢");
        add("c_mammothTank", new String[] {"mammothTank"}, Category.LAND,
                "猛犸坦克：T3 重型电击坦克，对护盾特别有效。",
                "重型装甲", "能电击地面单位", "对地伤害略低，但破盾效果拔群");
        add("tankDestroyer", Category.LAND,
                "坦克杀手，高单发反装甲单位。",
                "只能攻击地面单位", "单发伤害高、射速慢");
        add("heavyTank", Category.LAND,
                "重型坦克，火力与装甲均衡的主战单位。",
                "全副武装", "能攻击地面单位", "一定对空能力");
        add("heavyHoverTank", Category.LAND,
                "重型气垫坦克，能过水面的重装单位。",
                "全副武装", "能在水上移动", "可以攻击空中和地面单位");
        add("megaTank", Category.LAND,
                "超级坦克，全副武装的多用途重型单位。",
                "全副武装", "能攻击地面", "有一定对空能力");
        add("plasmaTank", Category.LAND,
                "等离子坦克，T2 级高伤害主战单位。",
                "对地高伤害", "装甲较厚");
        add("missileTank", Category.LAND,
                "防空导弹坦克，专打空中目标。",
                "防空导弹", "只能攻击空中单位");
        add("scout", Category.LAND,
                "侦察者，视野远、可下水，适合早期侦查与骚扰。",
                "能够透过迷雾看得更远", "能够在水面上移动", "能攻击空中和地面单位");
        add("mechGun", Category.LAND, "基础机甲，机甲的通用近战/对地型号。", "对地攻击");
        add("mechMinigun", Category.LAND, "机枪机甲，高射速的机甲型号。", "对地攻击", "射速高");
        add("mechFlame", Category.LAND, "喷火机甲，近距离范围伤害。", "对地范围伤害", "射程短");
        add("mechMissile", Category.LAND, "防空机甲，机甲的防空型号。", "对空攻击");
        add("mechLaser", Category.LAND, "等离子机甲，高单发伤害的机甲型号。", "对地与对空");
        add("mechLightning", Category.LAND, "特斯拉机甲，电击攻击可绕过护盾。", "电击攻击", "破盾");

        // ===== 空军攻击单位 =====
        add("c_helicopter", new String[] {"helicopter"}, Category.AIR,
                "基础直升机，对地对空都能打。",
                "可以攻击地面和空中单位", "正面作战性价比一般");
        add("c_interceptor", new String[] {"airShip", "interceptor"}, Category.AIR,
                "拦截机，速度快，专职空战。",
                "移动速度快", "只能攻击空中单位");
        add("lightGunship", Category.AIR,
                "轻型武装直升机，廉价但对地火力有限。",
                "非常弱", "只能攻击地面单位", "飞行速度慢");
        add("gunShip", Category.AIR,
                "武装直升机，重装对地攻击机。",
                "只能攻击地面单位", "攻击力强", "全副武装");
        add("heavyInterceptor", Category.AIR,
                "重型拦截机，带护盾的空优单位。",
                "移动速度快", "只能攻击空中单位", "全副武装并带有护盾", "电击可绕过护盾");
        add("bomber", Category.AIR,
                "轰炸机，负责大范围对地轰炸。",
                "对地轰炸", "对空能力弱");
        add("aaBeamGunship", Category.AIR,
                "AA 激光射束战机，专用的防空战机。",
                "对空攻击");
        add("fireBee", Category.AIR, "火蜂战机，轻型快速空中单位。", "空中作战");
        add("missileAirship", Category.AIR,
                "导弹飞艇，远程对地/对空支援。", "导弹攻击");
        add("spyDrone", Category.AIR, "间谍无人机，用于侦查。", "侦查", "不能攻击");
        add("c_amphibiousJet", new String[] {"amphibiousJet"}, Category.AIR,
                "两栖喷气机，能在空中或水下发射闪电。",
                "在空中或水下发射闪电攻击", "电击可绕过防护盾", "对接地的建筑伤害稍低");

        // ===== 海军攻击单位 =====
        add("gunBoat", Category.SEA, "机枪艇，便宜快速的小型舰艇。", "移动速度快", "能攻击地面单位");
        add("missileShip", Category.SEA, "导弹舰，对地对空兼顾，带反潜鱼雷。",
                "可以攻击地面和空中单位", "带有轻型反潜艇鱼雷");
        add("battleShip", Category.SEA, "战列舰，中程对地火力平台。", "中程对地攻击");
        add("attackSubmarine", Category.SEA, "潜水艇，可下潜规避攻击，也可上浮对地/对空。",
                "下潜时可以防止被大多数单位攻击", "攻击水面上的单位",
                "可以浮出水面以攻击地面和空中单位");
        add("lightSub", Category.SEA, "水下探测器，非常弱的轻型潜艇。", "非常弱", "只能使用轻型鱼雷攻击");
        add("heavySub", Category.SEA, "重型潜艇，火力与生存力更强。", "重型鱼雷", "对舰攻击");
        add("heavyBattleship", Category.SEA, "重型战舰，海上的重装火力平台。", "重型对地火力");
        add("heavyMissileShip", Category.SEA, "重型导弹舰。", "导弹攻击");
        add("heavyAAShip", Category.SEA, "重型防空舰。", "对空攻击");

        // ===== 实验单位 =====
        add("c_experimentalTank", new String[] {"experimentalTank"}, Category.LAB,
                "实验坦克，全副武装的重型实验单位。",
                "全副武装", "能攻击地面单位", "对空能力较弱");
        add("experimentalHoverTank", Category.LAB,
                "概念型悬浮坦克，带护盾的两栖实验单位。",
                "高度全副武装并带有护盾", "可以攻击地面和空中单位", "能在水上移动");
        add("experimentalGunship", Category.LAB,
                "实验悬浮型气垫船，可运输并建造。", "运输能力", "建造单位", "重型武器");

        // ===== 生产建筑 =====
        add("commandCenter", Category.PRODUCTION,
                "指挥中心：生产建造者、提供资金，并具备对地/对空防御火力。",
                "生产建造者", "攻击地面、空中单位", "生成资金");
        add("landFactory", Category.PRODUCTION,
                "陆军工厂：生产陆地单位，可升级到 T2 解锁高级兵种。",
                "生产陆地单位", "生产便宜并且行动快的坦克", "可升级（T2）");
        add("airFactory", Category.PRODUCTION,
                "空军基地：生产空军单位，可升级到 T2。",
                "生产空军单位", "可升级（T2）");
        add("seaFactory", Category.PRODUCTION,
                "海军基地：生产海上部队，只能建在水面上。",
                "生产海上部队", "只能建在水面上");
        add("experimentalLandFactory", Category.PRODUCTION,
                "实验工厂：生产实验级陆地单位，占地大、价格高。",
                "生产实验坦克/实验悬浮坦克", "占地 5x5");
        add("mechFactory", Category.PRODUCTION,
                "机械工厂：生产机甲类单位。", "生产机甲", "可升级为机械工厂 T2");
        add("mechFactoryT2", Category.PRODUCTION,
                "机械工厂 T2：生产更高级的机甲与机械师。", "生产高级机甲", "可生产机械师");
        add("laboratory", Category.PRODUCTION,
                "实验室：T2 高科技建筑，提供研究/特殊效果。",
                "高科技建筑", "占地大、价格高");

        // ===== 炮塔与防御 =====
        add("c_turret_t1", new String[] {"turret"}, Category.TURRET,
                "基础炮塔，攻击地面单位，可升级成多种分支。",
                "攻击地面单位", "可升级：机枪 T2 / 火炮 / 喷火 / 闪电分支");
        add("c_turret_t2_gun", new String[] {"turretT2"}, Category.TURRET,
                "机枪 T2：强化后的通用地面炮塔。", "攻击地面单位", "由基础炮塔升级");
        add("c_turret_t3_gun", new String[] {"turretT3"}, Category.TURRET,
                "重机枪塔 T3：地面炮塔的最终形态。", "攻击地面单位", "由机枪 T2 升级");
        add("c_turret_t1_artillery", new String[] {"turret_artillery"}, Category.TURRET,
                "火炮：远距离打击，射速较慢。", "远距离打击", "攻击速度较慢");
        add("c_turret_t2_artillery", Category.TURRET,
                "火炮 T2：进一步强化射程的火炮。", "额外的攻击距离", "攻击速度较慢");
        add("c_turret_t2_flame", new String[] {"turret_flamethrower"}, Category.TURRET,
                "火焰喷射器：短距离范围群伤，可自我修复。", "短距离范围群伤", "自我修复");
        add("c_turret_t1_lightning", Category.TURRET,
                "闪电炮塔：电击攻击可绕过护盾。", "用雷击攻击地面上的单位", "电击可以绕过护盾", "对建筑伤害较低");
        add("c_turret_t2_lightning", Category.TURRET,
                "闪电炮塔 T2：强化版闪电防御。", "用雷击攻击地面上的单位", "电击可以绕过护盾");
        add("c_antiAirTurret", new String[] {"antiAirTurret"}, Category.TURRET,
                "防空炮塔，专职攻击空中单位，可升级。", "攻击空中单位", "可升级");
        add("c_antiAirTurretT2", new String[] {"antiAirTurretT2"}, Category.TURRET,
                "T2 防空炮塔。", "攻击空中单位");
        add("c_antiAirTurretT3", new String[] {"antiAirTurretT3"}, Category.TURRET,
                "T3 防空炮塔，防空火力的最终形态。", "攻击空中单位");
        add("antiAirTurretFlak", Category.TURRET,
                "T2 高射炮：溅射伤害，但射程较短。", "攻击空中单位，溅射伤害", "距离较短");
        add("outpostT1", Category.TURRET, "瞭望塔：提供视野的轻防御建筑。", "提供视野");
        add("outpostT2", Category.TURRET, "瞭望塔 T2：强化视野与防御。", "提供视野");
        add("laserDefence", Category.DEFENSE,
                "激光防御塔：拦截敌方炮弹，过载后需要充能。",
                "拦截敌方炮弹", "过载后需要时间来充能", "可升级");
        add("repairbay", Category.DEFENSE,
                "修复湾：维修附近单位，也能辅助建造与回收。",
                "维修单位", "也可以辅助建造", "可以回收自己的单位");

        // ===== 经济建筑 =====
        add("extractorT1", new String[] {"extractor"}, Category.ECONOMY,
                "资源抽取器：建在资源池上持续产出资金，可升级到 T3。",
                "生成资金", "只能建造在资源池上", "可升级至 T3");
        add("extractorT2", Category.ECONOMY,
                "资源抽取器 T2：资金产出更高的升级形态。",
                "生成资金（高于 T1）", "只能建造在资源池上", "可继续升级至 T3");
        add("extractorT3", Category.ECONOMY,
                "资源抽取器 T3：资金产出最高的形态，可继续转为超频/强化。",
                "生成资金（最高）", "只能建造在资源池上");
        add("extractorT3_overclocked", Category.ECONOMY,
                "超频资源抽取器：牺牲生命值换取更高产出，升级可撤销。",
                "大幅减少生命值", "加快资金生成速度", "升级操作可以撤销");
        add("extractorT3_reinforced", Category.ECONOMY,
                "强化资源抽取器：血量、护盾与自修更强，升级可撤销。",
                "血量更多", "增加护盾", "可以自我修复", "升级操作可以撤销");
        add("fabricatorT1", new String[] {"fabricator"}, Category.ECONOMY,
                "资源制造仪：不依赖资源池、持续生成资金的经济建筑，可升级。",
                "生成资源比资源抽取器慢 4 倍", "可升级");
        add("fabricatorT2", Category.ECONOMY, "资源制造仪 T2：产出更高的升级形态。", "生成资金", "可升级");
        add("fabricatorT3", Category.ECONOMY, "资源制造仪 T3：最高级的经济建筑。", "生成资金");
        add("wall_v", Category.DEFENSE, "墙：廉价的阻挡物，用来迟滞地面部队。", "阻挡地面单位");
        add("supplyDepot", Category.OTHER, "供应站：提供人口/补给支持。", "提供补给");

        // ===== 核设施 =====
        add("nukeLauncherC", new String[] {"NukeLaucher"}, Category.NUKE,
                "核弹发射井：发射大范围核导弹，需要先建造核弹。",
                "发射威力巨大的核导弹", "需要建造核弹以发射", "最多建造 4 枚");
        add("antiNukeLauncherC", new String[] {"AntiNukeLaucher"}, Category.NUKE,
                "反核防御：自动拦截来袭核弹，需要先建造反核导弹。",
                "拦截核导弹", "需要建造反核导弹", "最多建造 12 枚");
    }

    public static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** 按单位 id / 别名查找手写描述；找不到返回 null。 */
    public static Entry find(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        for (Entry entry : ENTRIES) {
            if (entry.matches(trimmed)) return entry;
        }
        return null;
    }

    /** 按“单位类型的所有可能名字”查找手写描述；找不到返回 null。 */
    public static Entry find(java.util.Collection<String> names) {
        if (names == null) return null;
        for (String name : names) {
            if (name == null) continue;
            for (Entry entry : ENTRIES) {
                if (entry.id.equalsIgnoreCase(name)) return entry;
            }
        }
        for (String name : names) {
            Entry entry = find(name);
            if (entry != null) return entry;
        }
        return null;
    }
}

