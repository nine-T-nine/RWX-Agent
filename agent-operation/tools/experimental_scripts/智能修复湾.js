// ============================================================
// 智能维修 v4 — 机枪绝对优先
// 有受损机枪时：所有修复湾只修机枪
// 无机枪受损时：才修其他受损单位（按百分比分散）
// ============================================================

var GH = Packages.cn.tesseract.union.util.GameHelper;
var UC = Packages.com.corrodinggames.rts.strategy.game.units.class_426;

// ==================== 可调参数 ====================
var TICK_INTERVAL       = 15;
var REPAIR_BAY_INTERNAL = "repairbay";
var PRIORITY_INTERNAL   = "mechMinigun";
var HP_THRESHOLD        = 0.99;
// ==================================================

var lastGame = null;
var lastTickSeen = -1;
var myTeam = -1;
var repairMemory = {};   // repairBayUid -> { targetUid, isPriority }

function internalName(u) { try { return String(u.field_1988.method_1670()); } catch(e){ return null; } }
function getUid(u) { try { var id = u.field_4220; return id == null ? null : ("" + id); } catch(e){ return null; } }
function getTeam(p) { try { return p ? p.field_1464 : -1; } catch(e){ return -1; } }
function getHpPct(u) {
    try {
        var hp = u.field_1983 || 0;
        var maxHp = u.field_1984 || 1;
        if (maxHp <= 0) return 1.0;
        return hp / maxHp;
    } catch (e) { return 1.0; }
}
function isAlive(u) { try { return u && !u.field_1925 && !u.field_4222; } catch(e){ return false; } }
function isOwn(u, me) { try { return u.field_1927 === me; } catch(e){ return false; } }

function issueRepair(game, repairer, target) {
    try {
        var cc = game.field_6412;
        if (!cc) return false;
        var pd = repairer.field_1927;
        if (!pd) return false;
        var act = cc.method_2058(pd);
        if (!act) return false;
        act.method_2139(repairer);
        act.method_2145(target);
        return true;
    } catch (e) { return false; }
}

function resetState() {
    repairMemory = {};
    lastGame = null;
    lastTickSeen = -1;
    myTeam = -1;
}

function onTick(tick) {
    try {
        var game = GH.game;
        if (!game) return;
        var now = game.field_6378;

        if (game !== lastGame || (lastTickSeen >= 0 && now < lastTickSeen)) {
            resetState();
            lastGame = game;
            lastTickSeen = now;
        } else {
            if (lastTickSeen >= 0 && now - lastTickSeen < TICK_INTERVAL) return;
            lastTickSeen = now;
        }

        var me = game.field_6373;
        if (!me) return;
        if (myTeam < 0) myTeam = getTeam(me);

        var ul = UC.field_1908;
        var sz = ul.size();

        var repairBays = [];
        var miniguns = [];      // 受损机枪
        var others = [];        // 其他受损
        var unitById = {};

        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!isAlive(u)) continue;
            if (!isOwn(u, me)) continue;
            var internal = internalName(u);
            if (!internal) continue;

            var uid = getUid(u);
            if (uid) unitById[uid] = u;

            if (internal === REPAIR_BAY_INTERNAL) {
                repairBays.push(u);
                continue;
            }

            var pct = getHpPct(u);
            if (pct >= HP_THRESHOLD) continue;

            if (internal === PRIORITY_INTERNAL) {
                miniguns.push({ uid: uid, u: u, pct: pct });
            } else {
                others.push({ uid: uid, u: u, pct: pct });
            }
        }

        if (repairBays.length === 0) {
            repairMemory = {};
            return;
        }

        // 按血量百分比升序（最残在前）
        miniguns.sort(function(a, b){ return a.pct - b.pct; });
        others.sort(function(a, b){ return a.pct - b.pct; });

        // 清理死掉的修复湾
        var aliveRepairBays = {};
        for (var ri = 0; ri < repairBays.length; ri++) {
            var rb = repairBays[ri];
            var ruid = getUid(rb);
            if (ruid) aliveRepairBays[ruid] = true;
        }
        for (var k in repairMemory) {
            if (!aliveRepairBays[k]) delete repairMemory[k];
        }

        // ============ 判断走哪条分支 ============
        if (miniguns.length > 0) {
            // ========== 分支 A：有受损机枪 → 优先修“最残血”，不锁定某台修完 ==========
            // 规则：
            //  1) 已在修有效机枪的湾保持当前目标（稳定，避免横跳）；
            //  2) 每台“还没人修”的孤儿机枪（最残血优先）找湾：优先用空闲湾，
            //     无空闲时从“正在修更轻(血更多)机枪”的湾上抢人（只抢更残血孤儿需要的）；
            //  3) 仍有空闲湾时，全部补到最残血机枪（允许多湾修同一台，避免闲置）。
            //  只有目标真正变化才发指令 → 状态不变不重发，不会反复横跳。
            var bayInfo = [];   // { uid, unit, target(uid|null), targetPct }
            var covered = {};   // 机枪 uid -> true（已有湾在修）

            // 登记每个修复湾当前状态
            for (var ra = 0; ra < repairBays.length; ra++) {
                var rba = repairBays[ra];
                var rua = getUid(rba);
                if (!rua) continue;
                var curTarget = null, curPct = 1.0;
                var memA = repairMemory[rua];
                if (memA) {
                    var oldA = unitById[memA.targetUid];
                    if (oldA && isAlive(oldA) && isOwn(oldA, me)
                            && getHpPct(oldA) < HP_THRESHOLD
                            && internalName(oldA) === PRIORITY_INTERNAL) {
                        curTarget = memA.targetUid;
                        curPct = getHpPct(oldA);
                    } else {
                        delete repairMemory[rua];
                    }
                }
                bayInfo.push({ uid: rua, unit: rba, target: curTarget, targetPct: curPct });
                if (curTarget) covered[curTarget] = true;
            }

            // 第二趟：最残血在前，给每台孤儿机枪安排修复湾
            for (var mi = 0; mi < miniguns.length; mi++) {
                var mg = miniguns[mi];
                if (covered[mg.uid]) continue;   // 已有人修，保持

                var pick = -1;
                // 1) 优先空闲湾
                for (var bi = 0; bi < bayInfo.length; bi++) {
                    if (!bayInfo[bi].target) { pick = bi; break; }
                }
                // 2) 无空闲 → 从“正在修更轻机枪”的湾中抢最轻的那台
                if (pick < 0) {
                    var worstPct = -1, worstIdx = -1;
                    for (var bj = 0; bj < bayInfo.length; bj++) {
                        var bjT = bayInfo[bj].target;
                        if (bjT && bayInfo[bj].targetPct > mg.pct
                                && bayInfo[bj].targetPct > worstPct) {
                            worstPct = bayInfo[bj].targetPct;
                            worstIdx = bj;
                        }
                    }
                    pick = worstIdx;
                }
                if (pick < 0) continue;   // 没有可用湾，这台暂等

                var b = bayInfo[pick];
                if (b.target === mg.uid) continue;
                if (issueRepair(game, b.unit, mg.u)) {
                    if (b.target) delete covered[b.target];
                    b.target = mg.uid;
                    b.targetPct = mg.pct;
                    covered[mg.uid] = true;
                    repairMemory[b.uid] = { targetUid: mg.uid, isPriority: true };
                }
            }

            // 第三趟：若仍有空闲湾，全部补到最残血机枪（避免修复湾闲置）
            for (var bi2 = 0; bi2 < bayInfo.length; bi2++) {
                if (bayInfo[bi2].target) continue;
                var top = miniguns[0];
                if (issueRepair(game, bayInfo[bi2].unit, top.u)) {
                    bayInfo[bi2].target = top.uid;
                    bayInfo[bi2].targetPct = top.pct;
                    repairMemory[bayInfo[bi2].uid] = { targetUid: top.uid, isPriority: true };
                }
            }

        } else {
            // ========== 分支 B：无机枪受损 → 修其他单位（按百分比分散） ==========
            var occupied2 = {};

            // 第一趟：保留旧目标（非机枪、未满血）
            for (var rb3i = 0; rb3i < repairBays.length; rb3i++) {
                var rb3 = repairBays[rb3i];
                var ru3 = getUid(rb3);
                if (!ru3) continue;

                var memC = repairMemory[ru3];
                if (!memC) continue;

                var oldC = unitById[memC.targetUid];
                if (!oldC || !isAlive(oldC) || !isOwn(oldC, me) || getHpPct(oldC) >= HP_THRESHOLD) {
                    delete repairMemory[ru3];
                    continue;
                }
                // 旧目标是机枪（现在满血了）→ 清掉
                if (internalName(oldC) === PRIORITY_INTERNAL) {
                    delete repairMemory[ru3];
                    continue;
                }
                occupied2[memC.targetUid] = true;
            }

            // 第二趟：给无目标的修复湾分配其他受损单位
            for (var rb4i = 0; rb4i < repairBays.length; rb4i++) {
                var rb4 = repairBays[rb4i];
                var ru4 = getUid(rb4);
                if (!ru4) continue;
                if (repairMemory[ru4]) continue;

                var target2 = null;
                for (var oi = 0; oi < others.length; oi++) {
                    if (occupied2[others[oi].uid]) continue;
                    target2 = others[oi];
                    break;
                }
                if (!target2) continue;

                if (issueRepair(game, rb4, target2.u)) {
                    occupied2[target2.uid] = true;
                    repairMemory[ru4] = { targetUid: target2.uid, isPriority: false };
                }
            }
        }

    } catch (e) {}
}

function init() {
    resetState();
}