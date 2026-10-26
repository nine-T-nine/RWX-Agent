// 重型拦截机 — 锁定目标版（复刻 ini 集火行为）
// ★ 关键：绕飞/后撤/攻击全部围绕"锁定的攻击目标"，而不是"最近敌人"
// ★ 目标死了或新目标明显更近（<70% 距离）才切换
var EventHook = Packages.cn.tesseract.union.hook.EventHook;
var GH        = Packages.cn.tesseract.union.util.GameHelper;
var UC        = Packages.com.corrodinggames.rts.strategy.game.units.class_426;

var SCRIPT_ID = (typeof id !== "undefined" && id) ? String(id) : "重型挖掘机2.js";

var UNIT_NAME = "heavyInterceptor";
var TARGET_NAMES = ["heavyInterceptor", "c_heavyInterceptor"];

// ========== 作战参数 ==========
var COMBAT_RANGE      = 170;   // 交战距离
var RETREAT_OUT_DIST  = 172;   // 后撤安全距离：必须撤到此距离外才解除后撤
var FORMATION_RANGE   = 500;   // 编队检测范围
var AMMO_RETREAT_TH   = 2;     // 挨打计数阈值
var SHIELD_FLANK_TH   = 129;   // 绕侧最低护盾
var FLANK_DISTANCE    = 160;   // 编队绕侧距离
var FLANK_ANGLE_DEG   = 100;   // 编队绕侧偏转角
var SOLO_ANGLE_DEG    = 135;   // 单机绕后角度
var SOLO_DISTANCE     = 30;    // 单机绕后距离
var RETREAT_DISTANCE  = 220;   // 单次后撤步长
var SCAN_RANGE        = 550;   // 最大探测范围
var TICK_GAP          = 6;     // 逻辑执行间隔

// ★ 目标切换滞回：新目标距离 < 当前锁定距离 * 此系数 才切换
var SWITCH_HYSTERESIS = 0.7;

var n = 0;
var unitStates = {};

// ========== 工具函数 ==========
function dist(ax, ay, bx, by) {
    var dx = ax - bx, dy = ay - by;
    return Math.sqrt(dx * dx + dy * dy);
}

function unitName(u) {
    try { return String(u.field_1988.method_1660()); } catch (e) { return "?"; }
}

function getUnitId(u) {
    try { return String(u.field_4220); } catch (e) { return null; }
}

function getHp(u) {
    try { return u.field_1983; } catch (e) { return 150; }
}

function getShield(u) {
    try { return u.field_1986; } catch (e) { return 450; }
}

function getTotalHp(u) {
    return getHp(u) + getShield(u);
}

function isOwnUnit(u, me) {
    try { return u.field_1927 === me; } catch (e) { return false; }
}

function isUnitAllowed(u) {
    if (!EventHook) return false;
    try { return EventHook.isUnitStrategyEnabled(u.field_4220, SCRIPT_ID); }
    catch (e) { return false; }
}

function isValidEnemy(u, myTeam) {
    try {
        var p = u.field_1927;
        if (!p) return false;
        return p.field_1464 !== myTeam && p.field_1464 >= 0;
    } catch (e) { return false; }
}

function isTarget(u) {
    try {
        var name = unitName(u);
        for (var i = 0; i < TARGET_NAMES.length; i++) {
            if (name === TARGET_NAMES[i]) return true;
        }
        return false;
    } catch (e) { return false; }
}

// ★ 单位是否还活着
function isAlive(u) {
    try { return u && !u.field_1925 && !u.field_4222; } catch (e) { return false; }
}

function countFormation(unit, interceptors) {
    var mx = unit.field_4227, my = unit.field_4228;
    var left = 0, right = 0, total = 0;
    var unitId = getUnitId(unit);

    for (var i = 0; i < interceptors.length; i++) {
        var other = interceptors[i];
        var otherId = getUnitId(other);
        if (!otherId || otherId === unitId) continue;
        if (dist(mx, my, other.field_4227, other.field_4228) > FORMATION_RANGE) continue;
        if (!unitStates[otherId]) continue;

        total++;
        if (unitStates[otherId].tag === 'left') left++;
        else right++;
    }
    return { total: total, left: left, right: right };
}

// ========== 主循环 ==========
function onTick(tick) {
    n++;
    if (n % TICK_GAP !== 0) return;

    try {
        var game = GH.game;
        var me = game.field_6373;
        if (!me) return;
        var myTeam = me.field_1464;

        var ul = UC.field_1908;
        if (!ul) return;
        var sz = ul.size();

        var interceptors = [];
        var enemies = [];
        var aliveIds = {};

        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!u || u.field_1925 || u.field_4222) continue;
            var p = u.field_1927;
            if (!p) continue;

            if (isOwnUnit(u, me) && unitName(u) === UNIT_NAME) {
                if (isUnitAllowed(u)) {
                    interceptors.push(u);
                    var id = getUnitId(u);
                    if (id) aliveIds[id] = true;
                }
            } else if (isValidEnemy(u, myTeam) && isTarget(u)) {
                enemies.push(u);
            }
        }

        for (var id in unitStates) {
            if (!aliveIds[id]) delete unitStates[id];
        }
        if (interceptors.length === 0) return;

        // ===== 1. 受击检测：总生命下降就算受伤 =====
        for (var i = 0; i < interceptors.length; i++) {
            var unit = interceptors[i];
            var uid = getUnitId(unit);
            if (!uid) continue;

            var total = getTotalHp(unit);
            if (!unitStates[uid]) {
                unitStates[uid] = {
                    tag: 'left',
                    ammo: 0,
                    isRetreating: false,
                    lastTotal: total,
                    guardTargetId: null,
                    attackTargetRef: null,   // ★ 锁定的攻击目标（class_426）
                    attackTargetId: null     // ★ 锁定目标 ID
                };
            } else {
                if (total < unitStates[uid].lastTotal) {
                    unitStates[uid].ammo++;
                }
                unitStates[uid].lastTotal = total;
            }
        }

        var flankAngle = FLANK_ANGLE_DEG * Math.PI / 180;
        var soloAngle = SOLO_ANGLE_DEG * Math.PI / 180;

        for (var i = 0; i < interceptors.length; i++) {
            var m = interceptors[i];
            var mx = m.field_4227, my = m.field_4228;
            var uid = getUnitId(m);
            if (!uid || !unitStates[uid]) continue;
            var state = unitStates[uid];
            var shield = getShield(m);

            // ===== 找最近敌人（作为候选） =====
            var best = null, bestD = Infinity, bestId = null;
            for (var k = 0; k < enemies.length; k++) {
                var en = enemies[k];
                var d = dist(mx, my, en.field_4227, en.field_4228);
                if (d > SCAN_RANGE) continue;
                if (d < bestD) {
                    bestD = d;
                    best = en;
                    bestId = getUnitId(en);
                }
            }

            // ===== ★ 目标锁定逻辑 =====
            // 若锁定目标已死 → 清空
            if (state.attackTargetRef && !isAlive(state.attackTargetRef)) {
                state.attackTargetRef = null;
                state.attackTargetId = null;
                state.guardTargetId = null;
            }

            var attackTarget = null, attackTargetId = null, attackDist = Infinity;

            if (best) {
                if (state.attackTargetRef) {
                    // 已有锁定：计算锁定目标当前距离
                    var dLocked = dist(mx, my,
                        state.attackTargetRef.field_4227,
                        state.attackTargetRef.field_4228);
                    // ★ 只有新目标明显更近（<70%）才切换
                    if (bestD < dLocked * SWITCH_HYSTERESIS) {
                        state.attackTargetRef = best;
                        state.attackTargetId = bestId;
                        state.guardTargetId = null;   // 目标变了，清 guard 标记
                    }
                } else {
                    // 无锁定 → 选最近
                    state.attackTargetRef = best;
                    state.attackTargetId = bestId;
                }
                attackTarget = state.attackTargetRef;
                attackTargetId = state.attackTargetId;
                attackDist = dist(mx, my,
                    attackTarget.field_4227, attackTarget.field_4228);
            } else {
                // 无敌人 → 清空锁定
                state.attackTargetRef = null;
                state.attackTargetId = null;
                state.guardTargetId = null;
            }

            var formation = countFormation(m, interceptors);
            var act = game.field_6412.method_2058(m.field_1927);
            if (!act) continue;
            act.method_2139(m);

            // ===== 无敌人：脱战冷却 + 编队平衡 =====
            if (!attackTarget) {
                if (state.ammo > 3 && state.isRetreating) {
                    state.isRetreating = false;
                    state.tag = (state.tag === 'left') ? 'right' : 'left';
                    state.ammo = 0;
                }
                if (formation.left - formation.right > 1 && state.tag === 'left') {
                    state.tag = 'right';
                }
                continue;
            }

            // ★ 以下全部以锁定目标为参照
            var ex = attackTarget.field_4227, ey = attackTarget.field_4228;
            var baseAngle = Math.atan2(my - ey, mx - ex);
            var tx, ty;

            // --- 优先级1：后撤状态中，没到安全距离继续后撤 ---
            if (state.isRetreating) {
                if (attackDist < RETREAT_OUT_DIST) {
                    tx = ex + Math.cos(baseAngle) * RETREAT_DISTANCE;
                    ty = ey + Math.sin(baseAngle) * RETREAT_DISTANCE;
                    act.method_2134(tx, ty);
                    state.guardTargetId = null;
                    continue;
                } else {
                    state.isRetreating = false;
                    state.ammo = 0;
                }
            }

            // --- 优先级2：触发后撤 ---
            if (attackDist <= COMBAT_RANGE && state.ammo > AMMO_RETREAT_TH && !state.isRetreating) {
                tx = ex + Math.cos(baseAngle) * RETREAT_DISTANCE;
                ty = ey + Math.sin(baseAngle) * RETREAT_DISTANCE;
                act.method_2134(tx, ty);
                state.isRetreating = true;
                state.guardTargetId = null;
                continue;
            }

            // --- 优先级3：交战距离内 + 状态良好 → 绕侧 ---
            if (!state.isRetreating && attackDist <= COMBAT_RANGE && shield > SHIELD_FLANK_TH) {
                if (formation.total === 0) {
                    var angle = baseAngle - soloAngle;
                    tx = ex + Math.cos(angle) * SOLO_DISTANCE;
                    ty = ey + Math.sin(angle) * SOLO_DISTANCE;
                } else {
                    var angle = baseAngle;
                    if (state.tag === 'left') angle += flankAngle;
                    else angle -= flankAngle;
                    tx = ex + Math.cos(angle) * FLANK_DISTANCE;
                    ty = ey + Math.sin(angle) * FLANK_DISTANCE;
                }
                act.method_2134(tx, ty);
                state.guardTargetId = null;
                continue;
            }

            // --- 优先级4：超交战距离 → Guard 追击（锁定目标） ---
            if (!state.isRetreating && attackDist > COMBAT_RANGE) {
                if (state.guardTargetId !== attackTargetId) {
                    act.method_2148(attackTarget);
                    state.guardTargetId = attackTargetId;
                }
                continue;
            }

            // --- 保底：护盾不足 → 保持距离 ---
            if (!state.isRetreating && attackDist <= COMBAT_RANGE && shield <= SHIELD_FLANK_TH) {
                tx = ex + Math.cos(baseAngle) * COMBAT_RANGE;
                ty = ey + Math.sin(baseAngle) * COMBAT_RANGE;
                act.method_2134(tx, ty);
                state.guardTargetId = null;
            }
        }
    } catch (e) {}
}

function init() {}