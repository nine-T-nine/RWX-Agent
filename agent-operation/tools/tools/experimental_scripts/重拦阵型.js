// ============================================================
// 重型拦截机AI.js — v31
// 基于 v30 改动：加入单位策略白名单（默认关闭）
//   只有被明确勾选的重拦才会执行
// 其他保留 v30：ammo映射、residualBlood三段、拉残=输出型、极撤=引火型
// ============================================================

// ---------- 可调参数 ----------
var TICK_GAP            = 15;
var DEBUG               = true;

// 单位属性
var MAX_SHIELD          = 450;
var MAX_HP              = 150;
var ATTACK_RANGE        = 170;

// 状态阈值
var SHIELD_HEALTHY      = 150;
var CRIT_TOTAL          = 300;
var RESIDUAL_MID        = 400;
var COMBAT_BAD          = 8;

// 集火检测
var ATTACKER_SCAN       = 200;

// 主动接近范围
var APPROACH_RANGE      = 700;

// 角色分配
var BAIT_RATIO          = 0.4;
var ASSIGN_MIN          = 3;

// 拉残/极撤距离
var PULLBACK_DIST       = 100;
var RETREAT_DIST        = 100;

// 拉残冷却（两阶段）
var PULLBACK_SWITCH_CD  = 1;
var PULLBACK_LOCK_CD    = 8;

// 敌方数量阈值
var ENEMY_NEAR          = 170;
var ENEMY_BAIT_220      = 220;
var ENEMY_BAIT_120      = 120;
var ENEMY_SCAN_500      = 500;

// 护卫目标扫描
var GUARD_SCAN_RANGE    = 220;

// 战术判断
var TACTIC_THRESHOLD    = 13;
var T1_ORBIT_MAX        = 7;
var T1_MID_MAX          = 9;
var T3_ORBIT_MAX        = 21;
var T3_MID_MAX          = 35;

// 命令节流
var ORDER_COOLDOWN      = 20;
var ORDER_DEDUP_DIST    = 20;
var ATTACK_COOLDOWN     = 45;

// ---------- 全局桥接 ----------
var GH        = Packages.cn.tesseract.union.util.GameHelper;
var UC        = Packages.com.corrodinggames.rts.strategy.game.units.class_426;
var FH        = Packages.cn.tesseract.union.util.FileHelper;
var EventHook = Packages.cn.tesseract.union.hook.EventHook;

// ★ 改成磁盘上的实际文件名（含 .js），要和单位策略 UI 里显示的一字不差
var SCRIPT_ID = (typeof id !== "undefined" && id) ? String(id) : "重拦阵型.js";

function log(s) {
    if (!DEBUG) return;
    try { FH.log("[重拦v31] " + s); } catch (e) {}
}

// ===== 白名单：该单位是否勾选了本脚本。默认 false =====
function isUnitAllowed(u) {
    if (!EventHook) return false;
    try { return EventHook.isUnitStrategyEnabled(u.field_4220, SCRIPT_ID); }
    catch (e) { return false; }
}

// ---------- 单位状态 ----------
var unitStates = {};
var tickCount = 0;
var _branches = {};

function getState(u) {
    var h = unitHash(u);
    if (!unitStates[h]) {
        unitStates[h] = {
            direction: Math.random() < 0.5 ? "left" : "right",
            pullCooldown: false,
            pullbackCd: 0,
            pullbackSwitched: false,
            lastTx: undefined,
            lastTy: undefined,
            lastOrderTick: -99999,
            lastAttackTarget: null,
            lastAttackTick: -99999,
            guardTargetHash: -1
        };
    }
    return unitStates[h];
}

// ---------- 工具函数 ----------
function dist(ax, ay, bx, by) { var dx = ax - bx, dy = ay - by; return Math.sqrt(dx * dx + dy * dy); }
function unitName(u) { try { return String(u.field_1988.method_1660()); } catch (e) { return "?"; } }
function unitHash(u) { try { return u.hashCode(); } catch (e) { return 0; } }
function getX(u) { try { return u.field_4227; } catch (e) { return 0; } }
function getY(u) { try { return u.field_4228; } catch (e) { return 0; } }
function getHp(u) { try { return u.field_1983 | 0; } catch (e) { return 0; } }
function getShield(u) { try { return u.field_1986 | 0; } catch (e) { return 0; } }
function isOwnUnit(u, me) { try { return u.field_1927 === me; } catch (e) { return false; } }
function isEnemy(u, myTeam) {
    try {
        var p = u.field_1927; if (!p) return false;
        var t = p.field_1464;
        return t !== myTeam && t >= 0;
    } catch (e) { return false; }
}
function isHeavyInterceptor(u) {
    var nm = unitName(u).toLowerCase();
    return (nm.indexOf("heavy") >= 0 &&
            (nm.indexOf("interceptor") >= 0 || nm.indexOf("intercept") >= 0)) ||
           nm.indexOf("重型拦截机") >= 0;
}
function tag(u, name) { _branches[unitHash(u)] = name; }

// ---------- 坐标计算 ----------
function modOffset(X, Y, dirDeg, OX, OY) {
    var r = dirDeg * Math.PI / 180;
    var s = Math.sin(r), c = Math.cos(r);
    return {
        x: X - OX * s - OY * c,
        y: Y + OX * c - OY * s
    };
}
function dirTo(fx, fy, tx, ty) {
    return Math.atan2(ty - fy, tx - fx) * 180 / Math.PI;
}

// ---------- 查询函数 ----------
function countEnemyInRange(x, y, enemies, range) {
    var c = 0;
    for (var i = 0; i < enemies.length; i++)
        if (dist(x, y, getX(enemies[i]), getY(enemies[i])) <= range) c++;
    return c;
}
function findNearestEnemyInRange(x, y, enemies, range) {
    var best = null, bd = Infinity;
    for (var i = 0; i < enemies.length; i++) {
        var d = dist(x, y, getX(enemies[i]), getY(enemies[i]));
        if (d <= range && d < bd) { bd = d; best = enemies[i]; }
    }
    return { unit: best, dist: bd };
}
function findWeakestEnemyInRange(x, y, enemies, range) {
    var best = null, minHealth = Infinity;
    for (var i = 0; i < enemies.length; i++) {
        var e = enemies[i];
        if (dist(x, y, getX(e), getY(e)) > range) continue;
        var health = getShield(e) + getHp(e);
        if (health < minHealth) { minHealth = health; best = e; }
    }
    return best;
}

// ========== 精确集火检测 ==========
function getAttackerCount(u, enemies, range) {
    var count = 0;
    var ux = getX(u), uy = getY(u);
    for (var i = 0; i < enemies.length; i++) {
        try {
            var e = enemies[i];
            if (e.field_1925) continue;
            if (dist(ux, uy, getX(e), getY(e)) > range) continue;
            var tgt = e.field_1706;
            if (tgt === u) count++;
        } catch (e2) {}
    }
    return count;
}

// ---------- 命令函数 ----------
function rawMove(u, tx, ty, game) {
    try {
        var act = game.field_6412.method_2058(u.field_1927);
        if (!act) return false;
        act.method_2139(u);
        act.method_2134(tx, ty);
        return true;
    } catch (e) { return false; }
}

function rawAttack(u, target, game) {
    try {
        var act = game.field_6412.method_2058(u.field_1927);
        if (!act) return false;
        act.method_2139(u);
        act.method_2140(target);
        return true;
    } catch (e) { return false; }
}

function rawGuard(u, target, game) {
    try {
        var act = game.field_6412.method_2058(u.field_1927);
        if (!act) return false;
        act.method_2139(u);
        act.method_2148(target);
        return true;
    } catch (e) { return false; }
}

function issueMove(u, tx, ty, game, st) {
    if (st.lastTx !== undefined) {
        var dx = tx - st.lastTx, dy = ty - st.lastTy;
        if (dx * dx + dy * dy < ORDER_DEDUP_DIST * ORDER_DEDUP_DIST) return false;
    }
    if ((tickCount - st.lastOrderTick) < ORDER_COOLDOWN) return false;
    var ok = rawMove(u, tx, ty, game);
    if (ok) {
        st.lastTx = tx; st.lastTy = ty;
        st.lastOrderTick = tickCount;
        st.guardTargetHash = -1;
    }
    return ok;
}

function issueAttack(u, target, game, st) {
    if (st.lastAttackTarget === target && (tickCount - st.lastAttackTick) < ATTACK_COOLDOWN) return false;
    var ok = rawAttack(u, target, game);
    if (ok) {
        st.lastAttackTarget = target;
        st.lastAttackTick = tickCount;
    }
    return ok;
}

function issueGuard(u, target, game, st) {
    var th = unitHash(target);
    if (st.guardTargetHash === th) return false;
    var ok = rawGuard(u, target, game);
    if (ok) {
        st.guardTargetHash = th;
        st.lastTx = undefined; st.lastTy = undefined;
    }
    return ok;
}

// ========== 主循环 ==========
function onTick(tick) {
    tickCount++;
    if (tickCount % TICK_GAP !== 0) return;

    try {
        var game = GH.game; if (!game) return;
        var me = game.field_6373; if (!me) return;
        var myTeam = me.field_1464;
        var ul = UC.field_1908; if (!ul) return;
        var sz = ul.size();

        var myInterceptors = [];
        var enemyInterceptors = [];

        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!u || u.field_1925 || u.field_4222) continue;
            if (!isHeavyInterceptor(u)) continue;
            if (isOwnUnit(u, me)) {
                // ★ 白名单过滤：只有勾选了的重拦才进候选
                if (isUnitAllowed(u)) myInterceptors.push(u);
            } else if (isEnemy(u, myTeam)) {
                enemyInterceptors.push(u);
            }
        }

        if (myInterceptors.length === 0 || enemyInterceptors.length === 0) return;

        // ===== 找接敌飞机 =====
        var activeList = [];
        for (var i = 0; i < myInterceptors.length; i++) {
            var u = myInterceptors[i];
            var nd = findNearestEnemyInRange(getX(u), getY(u), enemyInterceptors, APPROACH_RANGE);
            if (nd.unit) {
                activeList.push({ unit: u, dist: nd.dist });
            }
        }

        // ===== 己方质心 =====
        var mcx = 0, mcy = 0;
        if (activeList.length > 0) {
            for (var i = 0; i < activeList.length; i++) {
                mcx += getX(activeList[i].unit);
                mcy += getY(activeList[i].unit);
            }
            mcx /= activeList.length;
            mcy /= activeList.length;
        } else {
            for (var i = 0; i < myInterceptors.length; i++) {
                mcx += getX(myInterceptors[i]);
                mcy += getY(myInterceptors[i]);
            }
            mcx /= myInterceptors.length;
            mcy /= myInterceptors.length;
        }

        // ===== 敌方质心 =====
        var enemyCx = 0, enemyCy = 0;
        for (var i = 0; i < enemyInterceptors.length; i++) {
            enemyCx += getX(enemyInterceptors[i]);
            enemyCy += getY(enemyInterceptors[i]);
        }
        enemyCx /= enemyInterceptors.length;
        enemyCy /= enemyInterceptors.length;

        // ===== 角色分配 =====
        var shouldAssign = (activeList.length >= ASSIGN_MIN);
        var roleMap = {};
        if (shouldAssign) {
            activeList.sort(function(a, b) { return a.dist - b.dist; });
            var baitCount = Math.max(1, Math.ceil(activeList.length * BAIT_RATIO));
            for (var i = 0; i < activeList.length; i++) {
                roleMap[unitHash(activeList[i].unit)] = (i < baitCount) ? "bait" : "dps";
            }
        }

        // 左右标签动态平衡
        var leftCnt = 0, rightCnt = 0;
        for (var i = 0; i < myInterceptors.length; i++) {
            var st0 = getState(myInterceptors[i]);
            if (st0.direction === "left") leftCnt++; else rightCnt++;
        }
        for (var i = 0; i < myInterceptors.length; i++) {
            var st0 = getState(myInterceptors[i]);
            var ux0 = getX(myInterceptors[i]), uy0 = getY(myInterceptors[i]);
            var cnt170_0 = countEnemyInRange(ux0, uy0, enemyInterceptors, ENEMY_NEAR);
            if (cnt170_0 === 0) {
                if (leftCnt > rightCnt + 1 && st0.direction === "left" && Math.random() < 0.3) {
                    st0.direction = "right"; leftCnt--; rightCnt++;
                } else if (rightCnt > leftCnt + 1 && st0.direction === "right" && Math.random() < 0.3) {
                    st0.direction = "left"; rightCnt--; leftCnt++;
                }
            }
        }

        _branches = {};

        for (var i = 0; i < myInterceptors.length; i++) {
            try {
                var u = myInterceptors[i];
                var role = roleMap[unitHash(u)] || "none";
                processUnit(u, myInterceptors, enemyInterceptors,
                            mcx, mcy, enemyCx, enemyCy, game, role);
            } catch (e) { log("unit err: " + e); }
        }

        if (tickCount % (TICK_GAP * 6) === 0) {
            var summary = {};
            for (var k in _branches) {
                var b = _branches[k];
                summary[b] = (summary[b] || 0) + 1;
            }
            var baitN = 0, dpsN = 0;
            for (var k in roleMap) {
                if (roleMap[k] === "bait") baitN++; else dpsN++;
            }
            log("我=" + myInterceptors.length + " 敌=" + enemyInterceptors.length +
                " 接敌=" + activeList.length +
                (shouldAssign ? (" 引火=" + baitN + " 输出=" + dpsN) : " 等待大部队") +
                " 分支=" + JSON.stringify(summary));
        }

    } catch (e) { log("ERR: " + e); }
}

// ========== 单单位决策 ==========
function processUnit(u, myInterceptors, allEnemies,
                     mcx, mcy, enemyCx, enemyCy, game, role) {
    var st = getState(u);
    var ux = getX(u), uy = getY(u);

    if (st.pullCooldown && st.pullbackCd > 0) st.pullbackCd--;

    var shield = getShield(u);
    var hp = getHp(u);
    var totalHealth = shield + hp;

    var attackerCount = getAttackerCount(u, allEnemies, ATTACKER_SCAN);
    var ammo = attackerCount === 0 ? 0 :
               attackerCount === 1 ? 2 :
               attackerCount === 2 ? 4 : 6;

    var residualBlood = (totalHealth >= RESIDUAL_MID) ? 0 :
                        (totalHealth >= CRIT_TOTAL) ? 2 : 4;
    var combatState = ammo + residualBlood;

    var isHealthy = (shield > SHIELD_HEALTHY - 1);
    var isCrit = (totalHealth < CRIT_TOTAL);
    var isLeft = (st.direction === "left");
    var friendCount = myInterceptors.length;

    var nearest = findNearestEnemyInRange(ux, uy, allEnemies, APPROACH_RANGE);
    var tgt = nearest.unit, tgtD = nearest.dist;
    if (!tgt) { tag(u, "超出范围"); return; }

    var tx = getX(tgt), ty = getY(tgt);
    var D = dirTo(ux, uy, tx, ty);

    var cnt170 = countEnemyInRange(ux, uy, allEnemies, ENEMY_NEAR);
    var cnt220 = countEnemyInRange(ux, uy, allEnemies, ENEMY_BAIT_220);
    var cnt120 = countEnemyInRange(ux, uy, allEnemies, ENEMY_BAIT_120);
    var cnt200 = countEnemyInRange(ux, uy, allEnemies, 200);
    var cnt500 = countEnemyInRange(ux, uy, allEnemies, ENEMY_SCAN_500);

    var lastHitter = null;
    try { lastHitter = u.field_1930; } catch (e) {}
    var D_hitter = lastHitter ? dirTo(ux, uy, getX(lastHitter), getY(lastHitter)) : D;

    // ===== ① 极撤（引火型专属）=====
    if (role === "bait" && isCrit && combatState >= COMBAT_BAD && cnt170 > 0) {
        tag(u, "极撤(血盾" + totalHealth + "集火" + attackerCount + ")");
        var rdx = ux - enemyCx, rdy = uy - enemyCy;
        var rl = Math.sqrt(rdx * rdx + rdy * rdy) || 1;
        issueMove(u, ux + rdx / rl * RETREAT_DIST,
                     uy + rdy / rl * RETREAT_DIST, game, st);
        return;
    }

    // ===== ② 拉残（输出型专属）=====
    if (role === "dps" && combatState >= COMBAT_BAD && cnt170 > 0 && cnt200 > 2 && !st.pullCooldown) {
        tag(u, "拉残" + (isLeft ? "L" : "R") + "(血盾" + totalHealth + "集火" + attackerCount + ")");
        var sideOX = isLeft ? 70 : -70;
        var ptPull = modOffset(ux, uy, D, sideOX, 70);
        if (issueMove(u, ptPull.x, ptPull.y, game, st)) {
            st.pullCooldown = true;
            st.pullbackCd = PULLBACK_SWITCH_CD;
            st.pullbackSwitched = false;
        }
        return;
    }

    // ===== ③ 拉残冷却 =====
    if (st.pullCooldown && st.pullbackCd <= 0) {
        if (!st.pullbackSwitched) {
            tag(u, "拉残切向-" + (isLeft ? "L→R" : "R→L"));
            st.direction = isLeft ? "right" : "left";
            st.pullbackSwitched = true;
            st.pullbackCd = PULLBACK_LOCK_CD;
        } else {
            st.pullCooldown = false;
            st.pullbackSwitched = false;
        }
        return;
    }

    // ===== ④ 输出型：射程内护卫 =====
    if (role === "dps" && tgtD < ATTACK_RANGE) {
        var weakTarget = findWeakestEnemyInRange(ux, uy, allEnemies, GUARD_SCAN_RANGE);
        if (weakTarget) {
            tag(u, "输出-护卫(" + Math.round(tgtD) + ")");
            issueGuard(u, weakTarget, game, st);
        }
        return;
    }

    // ===== ⑤ 战术绕圈/缠斗 =====
    if (role !== "dps") {
        var enemyAtCenter = countEnemyInRange(mcx, mcy, allEnemies, ENEMY_SCAN_500);

        if (enemyAtCenter <= TACTIC_THRESHOLD) {
            if (cnt170 > 0 && cnt220 <= 2 && isHealthy) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-缠斗");
                var pt = modOffset(tx, ty, D - 135, 0, -60);
                issueMove(u, pt.x, pt.y, game, st);
                return;
            }
            if (combatState < COMBAT_BAD && isHealthy && friendCount > 1 && cnt170 > 2 &&
                cnt500 <= T1_ORBIT_MAX) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-绕圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt2 = modOffset(tx, ty, D + (isLeft ? 100 : -100), 0, -160);
                issueMove(u, pt2.x, pt2.y, game, st);
                return;
            }
            if (combatState < COMBAT_BAD && isHealthy && cnt170 > 0 &&
                cnt500 > T1_ORBIT_MAX && cnt500 <= T1_MID_MAX) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-中圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt3 = modOffset(tx, ty, D + (isLeft ? 60 : -60), 0, -200);
                issueMove(u, pt3.x, pt3.y, game, st);
                return;
            }
            if (combatState < COMBAT_BAD && isHealthy && cnt170 > 0 &&
                cnt500 > T1_MID_MAX && cnt500 <= TACTIC_THRESHOLD) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-大圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt4 = modOffset(tx, ty, D + (isLeft ? 50 : -50), 0, -200);
                issueMove(u, pt4.x, pt4.y, game, st);
                return;
            }
        } else {
            if (combatState < COMBAT_BAD && isHealthy && cnt170 > 0 &&
                cnt500 > TACTIC_THRESHOLD && cnt500 <= T3_ORBIT_MAX) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-绕圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt = modOffset(tx, ty, D + (isLeft ? 100 : -100), 0, -160);
                issueMove(u, pt.x, pt.y, game, st);
                return;
            }
            if (combatState < COMBAT_BAD && isHealthy && cnt170 > 0 &&
                cnt500 > T3_ORBIT_MAX && cnt500 <= T3_MID_MAX) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-中圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt2 = modOffset(tx, ty, D + (isLeft ? 60 : -60), 0, -200);
                issueMove(u, pt2.x, pt2.y, game, st);
                return;
            }
            if (combatState < COMBAT_BAD && isHealthy && cnt170 > 0 &&
                cnt500 > T3_MID_MAX) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-大圈" + (isLeft ? "L" : "R") + "(c500=" + cnt500 + ")");
                var pt3 = modOffset(tx, ty, D + (isLeft ? 50 : -50), 0, -200);
                issueMove(u, pt3.x, pt3.y, game, st);
                return;
            }
            if (ammo > 1 && cnt170 > 0) {
                tag(u, (role === "bait" ? "引火" : "等待") + "-急撤(集火" + attackerCount + ")");
                var pt4 = modOffset(ux, uy, D, 0, 10);
                issueMove(u, pt4.x, pt4.y, game, st);
                return;
            }
        }
    }

    // ===== ⑥ 默认：后退/接近/弱锁定 =====
    if (tgtD < 150 && cnt170 > 0) {
        tag(u, "默认-后退(" + Math.round(tgtD) + ")");
        var rx2 = ux - tx, ry2 = uy - ty;
        var rl2 = Math.sqrt(rx2 * rx2 + ry2 * ry2) || 1;
        issueMove(u, ux + rx2 / rl2 * 60, uy + ry2 / rl2 * 60, game, st);
        return;
    }

    if (tgtD > 165) {
        tag(u, "默认-接近(" + Math.round(tgtD) + ")");
        var dxA2 = tx - ux, dyA2 = ty - uy;
        var LA2 = Math.sqrt(dxA2 * dxA2 + dyA2 * dyA2) || 1;
        var targetDist = 145;
        var step = Math.min(150, LA2 - targetDist);
        if (step > 0) {
            issueMove(u, ux + dxA2 / LA2 * step, uy + dyA2 / LA2 * step, game, st);
        }
        return;
    }

    tag(u, "默认-弱锁定(" + Math.round(tgtD) + ")");
    issueAttack(u, tgt, game, st);
}

function init() {
    log("=== 重型拦截机AI v31 初始化 ===");
    log("SCRIPT_ID=" + SCRIPT_ID);
    log("白名单：默认关闭，需在单位策略里勾选才会执行");
    log("ammo: 0→0,1→2,2→4,3+→6");
    log("residualBlood: >=300→0, 200~300→2, <200→4");
    log("拉残=输出型专属: combatState>=8 && 170内有敌 && 200内3+敌，侧后方100px");
    log("极撤=引火型专属: 血盾<200 && combatState>=8 && 170内有敌，远离敌方质心100px");
    log("拉残冷却: 0.25秒切向 + 切向后锁2秒");
    log("角色分配: 接敌>=3架即分配，40%引火+60%输出");
}