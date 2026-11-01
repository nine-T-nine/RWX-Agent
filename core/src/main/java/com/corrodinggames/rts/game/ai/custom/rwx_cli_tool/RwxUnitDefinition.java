package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个单位或建筑的百科定义（静态数据），与对局实时状态无关。
 *
 * <p>字段按“有数据才输出”设计：{@code produces/builds/speed/weapon/upgrade...} 在不适用或
 * 读不到时保持 null / 空表，渲染层据此跳过整行，而不是输出 {@code none}。</p>
 */
public final class RwxUnitDefinition {
    /** 建造价格与用时。 */
    public static final class Cost {
        public final Integer credits;
        public final Float seconds;

        Cost(Integer credits, Float seconds) {
            this.credits = credits;
            this.seconds = seconds;
        }
    }

    /** 生命、护盾与护甲值（不是“轻/中/重”类型，游戏没有该字段）。 */
    public static final class Hp {
        public final float max;
        public final float shield;
        public final float armor;

        Hp(float max, float shield, float armor) {
            this.max = max;
            this.shield = shield;
            this.armor = armor;
        }
    }

    /** 武器：伤害值只在能确定来源时给出。 */
    public static final class Weapon {
        public final Float rangeTiles;
        public final Float damage;
        public final String damageKind;
        public final String damageText;
        public final Float reloadSeconds;
        public final List<String> targets;

        Weapon(Float rangeTiles, Float damage, String damageKind, String damageText,
               Float reloadSeconds, List<String> targets) {
            this.rangeTiles = rangeTiles;
            this.damage = damage;
            this.damageKind = damageKind;
            this.damageText = damageText;
            this.reloadSeconds = reloadSeconds;
            this.targets = targets;
        }

        public boolean isEmpty() {
            return rangeTiles == null && damage == null && damageText == null && targets.isEmpty();
        }
    }

    /** 动作定义：静态百科与实时 actions 共用同一份。 */
    public static final class ActionDef {
        public final String id;
        public final String name;
        public final String kind;
        public final Integer cost;
        public final String target;
        public final boolean cancel;

        ActionDef(String id, String name, String kind, Integer cost, String target, boolean cancel) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.cost = cost;
            this.target = target;
            this.cancel = cancel;
        }
    }

    /** 建筑升级分支。 */
    public static final class UpgradeDef {
        public final String name;
        public final String to;
        public final Integer cost;

        UpgradeDef(String name, String to, Integer cost) {
            this.name = name;
            this.to = to;
            this.cost = cost;
        }
    }

    /** 生产来源。 */
    public static final class ProducerDef {
        public final String name;
        public final int level;
        public final boolean factory;

        ProducerDef(String name, int level, boolean factory) {
            this.name = name;
            this.level = level;
            this.factory = factory;
        }
    }

    public final String id;
    public final String name;
    public final String nameZh;
    public final String role;
    public final int tier;
    public final String movement;
    public final Cost cost;
    public final Hp hp;
    public final Float speed;
    public final Integer vision;
    public final Weapon weapon;
    public final List<String> abilities = new ArrayList<>();
    public final List<ActionDef> actions = new ArrayList<>();
    public final List<String> produces = new ArrayList<>();
    public final List<String> builds = new ArrayList<>();
    public final List<UpgradeDef> upgrades = new ArrayList<>();
    public final List<ProducerDef> producers = new ArrayList<>();
    public final List<String> requires = new ArrayList<>();
    public final String placement;
    public final List<String> features = new ArrayList<>();
    public final List<String> description = new ArrayList<>();
    public final String about;

    RwxUnitDefinition(String id, String name, String nameZh, String role, int tier, String movement,
                      Cost cost, Hp hp, Float speed, Integer vision, Weapon weapon,
                      String placement, String about) {
        this.id = id;
        this.name = name;
        this.nameZh = nameZh;
        this.role = role;
        this.tier = tier;
        this.movement = movement;
        this.cost = cost;
        this.hp = hp;
        this.speed = speed;
        this.vision = vision;
        this.weapon = weapon;
        this.placement = placement;
        this.about = about;
    }

    /** 能力摘要：把动作归并成 build/produce/upgrade 这类动词，去重保序。 */
    public List<String> actionKinds() {
        List<String> kinds = new ArrayList<>();
        for (ActionDef action : actions) {
            if (action.kind == null || action.kind.isEmpty()) continue;
            if (!kinds.contains(action.kind)) kinds.add(action.kind);
        }
        return kinds;
    }

    /** 通用指令：由能力推导，只在 --detail 输出。 */
    public List<String> genericCommands() {
        List<String> commands = new ArrayList<>();
        boolean building = "building".equals(role);
        if (!building) {
            commands.add("move");
            commands.add("stop");
        }
        if (weapon != null && !weapon.isEmpty()) {
            commands.add("attack");
            if (!building) commands.add("attack-move");
            commands.add("guard");
            if (!building) commands.add("patrol");
        }
        if (hasKind("build")) {
            commands.add("repair");
            commands.add("reclaim");
        }
        if (hasKind("produce") && building) commands.add("setRally");
        return commands;
    }

    private boolean hasKind(String kind) {
        for (ActionDef action : actions) {
            if (kind.equals(action.kind)) return true;
        }
        return false;
    }
}
