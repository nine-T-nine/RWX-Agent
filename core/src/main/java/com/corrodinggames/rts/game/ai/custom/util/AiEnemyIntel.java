package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.ai.strategy.AiContext;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.UnitType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敌方构成记忆：见过的敌方单位记下来，直到它**死亡**（含被己方消灭）才减一。
 *
 * <p>语义：
 * <ul>
 *   <li>迷雾只限制"能否新增记忆"（用 {@code context.visibleEnemies()}），已经记住的单位即便暂时看不见仍然计入。</li>
 *   <li>减员靠比对全图单位表 {@code BaseUnit.bE}：记忆里的 id 不在存活表里就删掉，所以"被消灭"必然减一。</li>
 *   <li>单位转换（例如 mechBunker 部署成 mechBunkerDeployed）按**当前**类型重新计数，不会重复计数。</li>
 *   <li>存档只写 id，读档后第一次 update 自动重新绑定存活单位；旧存档没有这段数据时会安全跳过。</li>
 * </ul>
 *
 * <p>空军威胁权值（用户定义）：直升机 0.2、武装直升机 0.5、轻型武装直升机 0.05、轰炸机 5、两栖喷气机 1.5。
 */
public final class AiEnemyIntel {
    private static final int STATE_VERSION = 1;

    /** 空军威胁：每组名字共享一个权值（别名解析到同一类型时只保留一条）。 */
    private static final String[][] AIR_WEIGHT_NAMES = {
            {AiUnits.HELICOPTER, "helicopter"},
            {"gunShip"},
            {AiUnits.LIGHT_GUNSHIP},
            {AiUnits.BOMBER},
            {"amphibiousJet"},
    };
    private static final float[] AIR_WEIGHT_VALUES = {0.2f, 0.5f, 0.05f, 5.0f, 1.5f};

    /** 非空军但要记住的地面威胁（升级决策用）。 */
    private static final String[][] GROUND_THREAT_NAMES = {
            {AiUnits.MECH_MINIGUN},
            {AiUnits.MECH_FLAME},
            {AiUnits.MECH_BUNKER, AiUnits.MECH_BUNKER_DEPLOYED},
    };

    /** 关注类型表：下标是存档里写的稳定编号。 */
    private final List<UnitType> tracked = new ArrayList<>();
    private final List<Float> airWeights = new ArrayList<>();

    private final Set<Long> remembered = new HashSet<>();
    private final Set<Long> pending = new HashSet<>();
    private final int[] counts = new int[64];
    private float airThreatWeight;

    public AiEnemyIntel() {
        for (int group = 0; group < AIR_WEIGHT_NAMES.length; group++) {
            for (UnitType type : resolveGroup(AIR_WEIGHT_NAMES[group])) {
                addTracked(type, AIR_WEIGHT_VALUES[group]);
            }
        }
        for (String[] group : GROUND_THREAT_NAMES) {
            for (UnitType type : resolveGroup(group)) {
                addTracked(type, 0f);
            }
        }
    }

    private static List<UnitType> resolveGroup(String[] names) {
        List<UnitType> result = new ArrayList<>();
        for (String name : names) {
            UnitType type = AiUnits.resolve(name);
            if (type != null && !result.contains(type)) result.add(type);
        }
        return result;
    }

    private void addTracked(UnitType type, float airWeight) {
        for (UnitType existing : tracked) {
            if (existing == type) return;
        }
        tracked.add(type);
        airWeights.add(airWeight);
    }

    private int indexOf(UnitType type) {
        if (type == null) return -1;
        for (int i = 0; i < tracked.size(); i++) {
            if (tracked.get(i) == type) return i;
        }
        return -1;
    }

    /**
     * 每个经济节拍调用一次：先并入读档得到的 id，再剔除已死亡的，最后把迷雾里看到的敌方单位记进来。
     */
    public void update(AiContext context) {
        if (tracked.isEmpty()) return;

        // 1) 存活的可关注单位：objectId -> 关注下标
        Map<Long, Integer> alive = new HashMap<>();
        for (BaseUnit unit : BaseUnit.bE) {
            if (unit == null || unit.isDead) continue;
            int index = indexOf(unit.r());
            if (index >= 0) alive.put(unit.objectId, index);
        }

        // 2) 读档得到的历史 id 并入记忆
        if (!pending.isEmpty()) {
            remembered.addAll(pending);
            pending.clear();
        }

        // 3) 死亡的记忆减一
        for (Iterator<Long> iterator = remembered.iterator(); iterator.hasNext(); ) {
            if (!alive.containsKey(iterator.next())) iterator.remove();
        }

        // 4) 迷雾内新看到的敌方单位记下来
        if (context != null) {
            for (BaseUnit unit : context.visibleEnemies()) {
                if (unit == null) continue;
                if (indexOf(unit.r()) < 0) continue;
                remembered.add(unit.objectId);
            }
        }

        // 5) 重新计数（按当前类型，兼容单位转换）
        for (int i = 0; i < counts.length; i++) counts[i] = 0;
        airThreatWeight = 0f;
        for (Long id : remembered) {
            Integer index = alive.get(id);
            if (index == null || index >= counts.length) continue;
            counts[index]++;
            airThreatWeight += airWeights.get(index);
        }
    }

    /** 敌方空军威胁权值总和。 */
    public float airThreatWeight() {
        return airThreatWeight;
    }

    /** 敌方是否有直升机或武装直升机（防空机甲的生产触发条件）。 */
    public boolean hasHelicopterOrGunship() {
        return count(AiUnits.helicopter()) > 0 || count(AiUnits.gunShip()) > 0;
    }

    /** 记忆里该类型的数量。 */
    public int count(UnitType type) {
        int index = indexOf(type);
        return index < 0 ? 0 : counts[index];
    }

    /** 是否有任意一种“移动炮台”（未部署或已部署）。 */
    public boolean hasMobileTurret() {
        return count(AiUnits.resolve(AiUnits.MECH_BUNKER)) > 0
                || count(AiUnits.resolve(AiUnits.MECH_BUNKER_DEPLOYED)) > 0;
    }

    /** 敌方是否有针对炮台/升级决策的威胁：移动炮台或机枪机甲。 */
    public boolean hasAntiTurretThreat() {
        return hasMobileTurret() || count(AiUnits.mechMinigun()) > 0;
    }

    /** 记忆里的单位总数。 */
    public int size() {
        return remembered.size();
    }

    // ===== 存档 =====
    public void write(DataOutputStream output) throws IOException {
        output.writeInt(STATE_VERSION);
        output.writeInt(tracked.size());
        output.writeInt(remembered.size());
        for (Long id : remembered) {
            output.writeLong(id);
        }
    }

    public void read(DataInputStream input) throws IOException {
        if (input.available() == 0) return;               // 旧存档没有这段
        if (input.readInt() != STATE_VERSION) {
            throw new IOException("Unsupported enemy intel state version");
        }
        int trackedCount = input.readInt();
        for (int i = 0; i < trackedCount; i++) { }         // 关注表由本地配置决定，读到就跳过
        if (trackedCount != tracked.size()) {
            // 关注表变了（mod 版本不同）：仍然读掉数据，只是不再计数
            int ignored = input.readInt();
            for (int i = 0; i < ignored; i++) input.readLong();
            return;
        }
        int count = input.readInt();
        for (int i = 0; i < count; i++) {
            pending.add(input.readLong());
        }
    }
}
