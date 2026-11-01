// ============================================================
// 建造者协防 — v3（融合「挡塔」+「穿墙」，单一调度器，穿墙优先）
// 本版仅删日志输出，其余逻辑与 v3 完全一致。
// ============================================================

var EventHook = Packages.cn.tesseract.union.hook.EventHook;
var GH        = Packages.cn.tesseract.union.util.GameHelper;
var UC        = Packages.com.corrodinggames.rts.strategy.game.units.class_426;
var FH        = Packages.cn.tesseract.union.util.FileHelper;

var SCRIPT_ID = (typeof id !== "undefined" && id) ? String(id) : "建造者协防.js";

// 模块级状态（init 中初始化）
var passTrack;
var blockTrack;
var retreated;
var blockCooldown;
var mechLast;
var n = 0;


// ==================== 可调参数 ====================
var TICK_GAP        = 6;
var BUILDER_RANGE   = 160;

// ----- 穿墙参数（优先级高）-----
var P_MAMMOTH_RANGE = 260;
var WALL_RANGE      = 50;
var STUCK_MS        = 300;
var STUCK_BOX       = 10;
var STUCK_GRACE_MS  = 200;
var CIRCLE_RADIUS   = 10;
var RING2_RADIUS    = 26;
var CIRCLE_POINTS   = 8;
var PHASE_TIMEOUT_MS = 4000;
var SKIP_RADIUS     = 12;
var DONE_RADIUS     = 16;
var RESET_DIST      = 60;
var ORDER_KEEP_DIST = 45;
var TICK_MS         = 8.333;
var PASS_RETRY_MS   = 900;

// ----- 挡塔参数（优先级低）-----
var B_MAMMOTH_RANGE = 190;
var AVOID_RADIUS    = 70;
var BUILD_OFFSET    = 0.4;
var PENDING_TIMEOUT_MS = 8000;
var RETREAT_DIST    = 250;

// ----- 任务释放 / 冷却（v2 新增）-----
var BLOCK_COOLDOWN_MS = 1500;

// ----- 通用 -----
var BUILD_I_CANDIDATES = [1, 0, 2];
var PRICE_TURRET    = 500;
var TURRET_KEY      = "c_turret_t1";
var TURRET_MATCH    = ["c_turret_t1", "turret"];
// ==================================================

// ==================== 工具函数 ====================
function dist(ax, ay, bx, by) { var dx = ax - bx, dy = ay - by; return Math.sqrt(dx * dx + dy * dy); }
function unitName(u)  { try { return String(u.field_1988.method_1660()); } catch (e) { return "?"; } }
function internalName(u) { try { return String(u.field_1988.method_1670()); } catch (e) { return "?"; } }
function unitHash(u)  {
    try { var id = u.field_4220; if (id != null) return (typeof id === "number") ? id : String(id); } catch (e) {}
    try { return u.hashCode(); } catch (e) { return 0; }
}
function getX(u)      { try { return u.field_4227; } catch (e) { return 0; } }
function getY(u)      { try { return u.field_4228; } catch (e) { return 0; } }
function nowMs(game) {
    try {
        var ms = game.field_6379;
        if (ms != null && isFinite(ms) && ms >= 0) return ms;
    } catch (e) {}
    try {
        var tk = game.field_6378;
        if (tk != null && isFinite(tk)) return tk * TICK_MS;
    } catch (e2) {}
    return 0;
}

function unitRadius(u) {
    var r = null;
    try { var r1 = u.field_1972; if (r1 != null && isFinite(r1) && r1 > 0) r = r1; } catch (e) {}
    if (r == null) {
        try {
            var ty = u.field_1988;
            if (ty) { var r2 = ty.field_1972; if (r2 != null && isFinite(r2) && r2 > 0) r = r2; }
        } catch (e2) {}
    }
    if (r == null) return 10;
    if (r < 6) r = 6;
    if (r > 40) r = 40;
    return r;
}

function isAlive(u) { try { return u && !u.field_1925 && !u.field_4222; } catch (e) { return false; } }
function isOwnUnit(u, me) { try { return u.field_1927 === me; } catch (e) { return false; } }
function isEnemy(u, myTeam) {
    try {
        var p = u.field_1927; if (!p) return false;
        var t = p.field_1464;
        return t !== myTeam && t >= 0;
    } catch (e) { return false; }
}
function isUnitAllowed(u) {
    if (!EventHook) return true;
    try { return EventHook.isUnitStrategyEnabled(u.field_4220, SCRIPT_ID); }
    catch (e) { return true; }
}
function isMech(u) {
    try {
        var nm = unitName(u).toLowerCase();
        return nm === "mechminigun" || nm === "c_mechminigun";
    } catch (e) { return false; }
}
function isMammoth(u) {
    try {
        var nm = unitName(u).toLowerCase();
        return nm.indexOf("mammoth") >= 0;
    } catch (e) { return false; }
}
function isBuilder(u) {
    try { return internalName(u).toLowerCase() === "builder"; } catch (e) { return false; }
}
function isBuildingType(u) {
    try {
        var t = u.field_1988;
        if (t) {
            try { if (t.field_3175 === true) return true; } catch (e1) {}
            try { if (t.method_967 && t.method_967() === true) return true; } catch (e2) {}
        }
    } catch (e) {}
    try {
        var nm = internalName(u).toLowerCase();
        if (!nm || nm === "?") return false;
        return nm.indexOf("turret") >= 0
            || nm.indexOf("factory") >= 0
            || nm.indexOf("extractor") >= 0
            || nm.indexOf("repairbay") >= 0
            || nm.indexOf("laserdefence") >= 0
            || nm.indexOf("fabricator") >= 0
            || nm.indexOf("nukelauncher") >= 0
            || nm.indexOf("antinukelauncher") >= 0
            || nm.indexOf("wall") >= 0
            || nm.indexOf("commandcenter") >= 0
            || nm.indexOf("supplydepot") >= 0
            || nm.indexOf("building") >= 0;
    } catch (e3) { return false; }
}
function isMammothAttacking(mammoth, mech) {
    try { return mammoth.field_1706 === mech; } catch (e) { return false; }
}
function canAfford(me, price) {
    try { return me.field_1461 >= price; } catch (e) { return false; }
}
function creditsOf(me) {
    try { var c = me.field_1461; return isFinite(c) ? c : null; } catch (e) { return null; }
}

// ==================== 命令下发 ====================
function issueBuild(game, me, builder, x, y, el, buildI) {
    try {
        var pd = builder.field_1927;
        var act = game.field_6412.method_2058(pd);
        act.method_2139(builder);
        act.method_2135(x, y, el, buildI);
        return true;
    } catch (e) { return false; }
}
function issueMove(game, me, builder, x, y) {
    try {
        var pd = builder.field_1927;
        var act = game.field_6412.method_2058(pd);
        act.method_2139(builder);
        act.method_2134(x, y);
        return true;
    } catch (e) { return false; }
}

function findElByKeys(builder, keys) {
    try {
        var list = builder.method_895();
        if (!list) return null;
        var sz = list.size();
        for (var i = 0; i < sz; i++) {
            var a = list.get(i);
            var el = a.method_642();
            if (!el) continue;
            var nm = "";
            try { nm = String(el.method_1660()).toLowerCase(); } catch (e) { continue; }
            for (var k = 0; k < keys.length; k++) {
                if (nm === keys[k] || nm.indexOf(keys[k]) >= 0) return el;
            }
        }
    } catch (e) {}
    return null;
}

// ==================== 建筑距离判定 ====================
function nearbyStructure(x, y, ownStructures) {
    for (var i = 0; i < ownStructures.length; i++) {
        if (dist(x, y, ownStructures[i].x, ownStructures[i].y) < AVOID_RADIUS) return true;
    }
    return false;
}
function nearestStructDist(x, y, structures) {
    var best = Infinity;
    for (var i = 0; i < structures.length; i++) {
        var d = dist(x, y, structures[i].x, structures[i].y);
        if (d < best) best = d;
    }
    return best;
}
function insideAnyStruct(x, y, structures) {
    for (var i = 0; i < structures.length; i++) {
        var s = structures[i];
        var rr = (s.r == null ? 10 : s.r);
        if (rr < 6) rr = 6;
        if (dist(x, y, s.x, s.y) < rr) return true;
    }
    return false;
}
function structNear(x, y, structures, r) {
    for (var i = 0; i < structures.length; i++) {
        if (dist(x, y, structures[i].x, structures[i].y) < r) return true;
    }
    return false;
}
function structCount(x, y, structures, r) {
    var c = 0;
    for (var i = 0; i < structures.length; i++) {
        if (dist(x, y, structures[i].x, structures[i].y) < r) c++;
    }
    return c;
}
function circleCandidates(cx, cy) {
    var pts = [];
    for (var i = 0; i < CIRCLE_POINTS; i++) {
        var ang = (Math.PI * 2 * i) / CIRCLE_POINTS;
        pts.push({ x: cx + Math.cos(ang) * CIRCLE_RADIUS,
                   y: cy + Math.sin(ang) * CIRCLE_RADIUS,
                   name: "内" + i });
    }
    pts.push({ x: cx, y: cy, name: "圆心" });
    for (var j = 0; j < CIRCLE_POINTS; j++) {
        var ang2 = (Math.PI * 2 * j) / CIRCLE_POINTS;
        pts.push({ x: cx + Math.cos(ang2) * RING2_RADIUS,
                   y: cy + Math.sin(ang2) * RING2_RADIUS,
                   name: "外" + j });
    }
    return pts;
}

// ==================== 建造者占用表 ====================
function builderOwnerOf(h) {
    var pk = Object.keys(passTrack);
    for (var i = 0; i < pk.length; i++) {
        var sp = passTrack[pk[i]];
        if (sp && sp.candIssued && sp.bhash != null && String(sp.bhash) === String(h)) {
            return { task: "pass", mh: String(pk[i]).replace(/^p:/, "") };
        }
    }
    var bk = Object.keys(blockTrack);
    for (var j = 0; j < bk.length; j++) {
        var bp = blockTrack[bk[j]];
        if (bp && bp.issued && bp.bhash != null && String(bp.bhash) === String(h)) {
            return { task: "block", mh: bk[j] };
        }
    }
    return null;
}
function checkBlockDone(ownStructures, t) {
    var bk = Object.keys(blockTrack);
    for (var i = 0; i < bk.length; i++) {
        var bp = blockTrack[bk[i]];
        if (!bp || !bp.issued) continue;
        if (nearbyStructure(bp.x, bp.y, ownStructures)) {
            blockCooldown[bk[i]] = t + BLOCK_COOLDOWN_MS;
            delete blockTrack[bk[i]];
        }
    }
}

// ==================== 主循环 ====================
var prevHadUnits = false;
var lastGameTime = -1;
function onTick(tick) {
    try {
        n++;
        if (n % TICK_GAP !== 0) return;

        var game = GH.game;

        var me = game.field_6373;
        var myTeam = me.field_1464;
        var ul = UC.field_1908;

        var mechs = [], builders = [], mammoths = [], structures = [], ownStructures = [];

        var sz = ul.size();
        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!isAlive(u)) continue;
            var p = u.field_1927;
            if (!p) continue;

            var own = isOwnUnit(u, me);
            var isBld = isBuildingType(u);

            if (isBld) structures.push({ x: getX(u), y: getY(u), own: own, r: unitRadius(u) });

            if (own) {
                if (isMech(u)) {
                    mechs.push(u);
                } else if (isBuilder(u) && isUnitAllowed(u)) {
                    builders.push({ u: u, x: getX(u), y: getY(u) });
                } else if (isBld) {
                    ownStructures.push({ x: getX(u), y: getY(u) });
                }
            } else if (isEnemy(u, myTeam) && isMammoth(u)) {
                mammoths.push(u);
            }
        }

        // ---- 跨局检测 + 残留清理 ----
        var haveUnits = mechs.length > 0;
        var gtime = nowMs(game);
        var newGame = false;
        if (gtime >= 0 && lastGameTime >= 0 && gtime < lastGameTime) newGame = true;
        if (!prevHadUnits && haveUnits) newGame = true;
        if (newGame) {
            passTrack = {}; blockTrack = {}; retreated = {};
            blockCooldown = {};
            mechLast = {};
        }
        prevHadUnits = haveUnits;
        lastGameTime = gtime;

        var liveMechHashes = {};
        for (var mi = 0; mi < mechs.length; mi++) liveMechHashes[unitHash(mechs[mi])] = true;

        // ---- 机枪从场上消失 → 建造者撤退 ----
        var deadHashes = [];
        var lastKeys = Object.keys(mechLast);
        for (var dk = 0; dk < lastKeys.length; dk++) {
            if (!liveMechHashes[lastKeys[dk]]) deadHashes.push(lastKeys[dk]);
        }
        for (var dd = 0; dd < deadHashes.length; dd++) {
            var dmh = deadHashes[dd];
            if (!retreated[dmh]) {
                var lastPos = mechLast[dmh];
                handleMechDead(game, me, dmh, lastPos ? lastPos.x : 0, lastPos ? lastPos.y : 0, builders, mammoths);
                retreated[dmh] = true;
            }
            delete mechLast[dmh];
            delete passTrack["p:" + dmh];
            delete blockTrack[dmh];
        }
        for (var mu = 0; mu < mechs.length; mu++) {
            var muh = unitHash(mechs[mu]);
            mechLast[muh] = { x: getX(mechs[mu]), y: getY(mechs[mu]) };
        }

        var allPKeys = Object.keys(passTrack);
        for (var pk2 = 0; pk2 < allPKeys.length; pk2++) {
            var realMh = allPKeys[pk2].replace(/^p:/, "");
            if (!liveMechHashes[realMh]) delete passTrack[allPKeys[pk2]];
        }
        var allBKeys = Object.keys(blockTrack);
        for (var bk2 = 0; bk2 < allBKeys.length; bk2++) {
            if (!liveMechHashes[allBKeys[bk2]]) delete blockTrack[allBKeys[bk2]];
        }
        var gtn = nowMs(game);
        var bcKeys = Object.keys(blockCooldown);
        for (var ck = 0; ck < bcKeys.length; ck++) {
            if (gtn >= blockCooldown[bcKeys[ck]]) delete blockCooldown[bcKeys[ck]];
        }

        // ① 结算：挡塔虚影一出，立刻释放建造者
        checkBlockDone(ownStructures, nowMs(game));

        // ② 穿墙（优先级最高）
        for (var pj = 0; pj < mechs.length; pj++) {
            var pm = mechs[pj];
            if (!isAlive(pm)) continue;
            processPass(game, me, unitHash(pm), pm, builders, structures, mammoths, ownStructures);
        }

        // ③ 挡塔（优先级低）
        for (var j = 0; j < mechs.length; j++) {
            var m = mechs[j];
            var mh = unitHash(m);

            var attacker = null, ad = Infinity;
            for (var k = 0; k < mammoths.length; k++) {
                var mm = mammoths[k];
                if (!isMammothAttacking(mm, m)) continue;
                var d = dist(getX(m), getY(m), getX(mm), getY(mm));
                if (d > B_MAMMOTH_RANGE) continue;
                if (d < ad) { ad = d; attacker = mm; }
            }
            if (!attacker) continue;

            var bestB = null, bestBD = BUILDER_RANGE;
            for (var b = 0; b < builders.length; b++) {
                var bh2 = unitHash(builders[b].u);
                var bd0 = dist(getX(m), getY(m), builders[b].x, builders[b].y);
                var ow = builderOwnerOf(bh2);
                if (ow) {
                    if (ow.task === "pass" || String(ow.mh) !== String(mh)) continue;
                }
                if (bd0 <= bestBD) { bestBD = bd0; bestB = builders[b]; }
            }

            var pend = blockTrack[mh];
            if (!bestB) {
                if (pend && pend.issued) {
                    if (nearbyStructure(pend.x, pend.y, ownStructures)) {
                        delete blockTrack[mh];
                    }
                }
                continue;
            }

            processBuild(game, me, mh, m, attacker, bestB, ownStructures);
        }
    } catch (e) {}
}

// ============================================================
// 穿墙流程
// ============================================================
function processPass(game, me, mh, mech, builders, structures, mammoths, ownStructures) {
    var key = "p:" + mh;
    var st = passTrack[key];
    if (!st) {
        st = { mx: getX(mech), my: getY(mech), since: nowMs(game), cand: null,
               candIssued: false, candIssuedAt: 0, bhash: null, baseCount: 0,
               tried: {}, done: false, doneX: 0, doneY: 0,
               lastMoveMs: 0, hasMammoth: false,
               crBefore: null, accepted: false };
        passTrack[key] = st;
    }

    var mx = getX(mech), my = getY(mech);
    var t = nowMs(game);

    // 订单监控
    if (st.cand && st.candIssued) {
        if (dist(mx, my, st.cand.x, st.cand.y) > ORDER_KEEP_DIST) {
            st.cand = null; st.candIssued = false; st.tried = {};
            st.since = t; st.mx = mx; st.my = my;
        } else {
            var nowCnt = structCount(st.cand.x, st.cand.y, structures, DONE_RADIUS);
            var curCr  = creditsOf(me);
            if (st.crBefore != null && curCr != null && curCr < st.crBefore) st.accepted = true;
            if (nowCnt > st.baseCount) {
                st.done = true; st.doneX = st.cand.x; st.doneY = st.cand.y;
                blockCooldown[mh] = t + BLOCK_COOLDOWN_MS;
                st.cand = null; st.candIssued = false; st.bhash = null;
                return;
            }
            var waited = t - st.candIssuedAt;
            var bArrived = false;
            for (var bb = 0; bb < builders.length; bb++) {
                if (unitHash(builders[bb].u) !== st.bhash) continue;
                if (dist(builders[bb].x, builders[bb].y, st.cand.x, st.cand.y) <= 22) bArrived = true;
                break;
            }
            var deadSpot = (waited > PASS_RETRY_MS) && bArrived && !st.accepted && (nowCnt <= st.baseCount);
            if (deadSpot || (waited > PHASE_TIMEOUT_MS)) {
                st.tried[st.cand.name] = true;
                st.cand = null; st.candIssued = false; st.candIssuedAt = 0;
                st.crBefore = null; st.accepted = false;
                st.since = t; st.mx = mx; st.my = my;
            } else {
                return;
            }
        }
    }

    if (st.done) {
        if (dist(mx, my, st.doneX, st.doneY) > RESET_DIST) {
            st.done = false; st.tried = {}; st.since = t; st.mx = mx; st.my = my;
        } else {
            return;
        }
    }

    // 卡住计时
    var moved = dist(mx, my, st.mx, st.my);
    if (moved > STUCK_BOX) {
        if (!st.lastMoveMs) st.lastMoveMs = t;
        if (t - st.lastMoveMs >= STUCK_GRACE_MS) {
            st.mx = mx; st.my = my; st.since = t; st.lastMoveMs = 0;
            if (!(st.cand && st.candIssued)) st.tried = {};
        }
    } else {
        st.lastMoveMs = 0;
    }
    var stuckMs = t - st.since;
    var stable = (moved <= STUCK_BOX);

    // 条件0：猛犸进入范围
    var mmNear = Infinity;
    if (mammoths) {
        for (var q = 0; q < mammoths.length; q++) {
            var dq = dist(mx, my, getX(mammoths[q]), getY(mammoths[q]));
            if (dq < mmNear) mmNear = dq;
        }
    }
    st.hasMammoth = (mmNear <= P_MAMMOTH_RANGE);

    // 条件1：附近有建筑
    var nsd = nearestStructDist(mx, my, structures);
    var hasWall = nsd <= WALL_RANGE;

    // 条件2：不在建筑内部
    var selfR = unitRadius(mech);
    var inside = insideAnyStruct(mx, my, structures);

    // 条件不满足 → return
    if (!st.hasMammoth) return;
    if (!hasWall) return;
    if (inside) return;
    if (!stable) return;
    if (stuckMs < STUCK_MS) return;

    if (!builders || builders.length === 0) return;

    // 选建造者
    var bestB = null, bestBD = BUILDER_RANGE;
    var preB = null, preBD = BUILDER_RANGE;
    for (var b = 0; b < builders.length; b++) {
        var bd = dist(mx, my, builders[b].x, builders[b].y);
        if (bd > BUILDER_RANGE) continue;
        var ow = builderOwnerOf(unitHash(builders[b].u));
        if (ow) {
            if (ow.task === "pass" && String(ow.mh) !== String(mh)) continue;
            if (ow.task === "block") {
                if (bd <= preBD) { preBD = bd; preB = builders[b]; }
                continue;
            }
        }
        if (bd <= bestBD) { bestBD = bd; bestB = builders[b]; }
    }
    if (!bestB && preB) {
        var preOwner = builderOwnerOf(unitHash(preB.u));
        if (preOwner && preOwner.task === "block") {
            delete blockTrack[preOwner.mh];
            blockCooldown[preOwner.mh] = t + BLOCK_COOLDOWN_MS;
        }
        bestB = preB; bestBD = preBD;
    }
    if (!bestB) return;

    // 选候选点
    var cands = circleCandidates(mx, my);
    var picked = null;
    for (var c = 0; c < cands.length; c++) {
        var cd = cands[c];
        if (st.tried[cd.name]) continue;
        if (structNear(cd.x, cd.y, structures, SKIP_RADIUS)) continue;
        picked = cd;
        break;
    }
    if (!picked) {
        st.tried = {};
        for (var c2 = 0; c2 < cands.length; c2++) {
            var cd2 = cands[c2];
            if (structNear(cd2.x, cd2.y, structures, SKIP_RADIUS)) continue;
            picked = cd2;
            break;
        }
    }
    if (!picked) return;

    // 金钱门控
    if (!canAfford(me, PRICE_TURRET)) return;

    // 取建造项 el
    var el = findElByKeys(bestB.u, TURRET_MATCH);
    if (!el) return;

    // 下单
    var bi = BUILD_I_CANDIDATES[0];
    var baseCnt = structCount(picked.x, picked.y, structures, DONE_RADIUS);
    var crB = creditsOf(me);
    var ok = issueBuild(game, me, bestB.u, picked.x, picked.y, el, bi);
    if (ok) {
        st.cand = { x: picked.x, y: picked.y, name: picked.name };
        st.candIssued = true;
        st.candIssuedAt = t;
        st.bhash = unitHash(bestB.u);
        st.baseCount = baseCnt;
        st.crBefore = crB;
        st.accepted = false;
    }
}

// ============================================================
// 挡塔流程
// ============================================================
function processBuild(game, me, mh, mech, attacker, bestB, ownStructures) {
    var mhx = getX(mech), mhy = getY(mech);
    var tX = getX(attacker), tY = getY(attacker);
    var t = nowMs(game);
    var bx = mhx + (tX - mhx) * BUILD_OFFSET;
    var by = mhy + (tY - mhy) * BUILD_OFFSET;

    var pend = blockTrack[mh];

    if (blockCooldown[mh] != null) {
        if (t < blockCooldown[mh]) return;
        delete blockCooldown[mh];
    }

    if (pend && pend.issued) {
        if (nearbyStructure(pend.x, pend.y, ownStructures)) {
            blockCooldown[mh] = t + BLOCK_COOLDOWN_MS;
            delete blockTrack[mh];
            return;
        }
        if (t - pend.issueMs > PENDING_TIMEOUT_MS) {
            pend.iIdx = (pend.iIdx == null ? 0 : pend.iIdx) + 1;
            if (pend.iIdx >= BUILD_I_CANDIDATES.length) pend.iIdx = 0;
            pend.issued = false;
        } else {
            return;
        }
    }

    if (!pend) {
        pend = { issued: false, x: 0, y: 0, issueMs: 0, bhash: unitHash(bestB.u), iIdx: 0 };
        blockTrack[mh] = pend;
    }

    if (nearbyStructure(bx, by, ownStructures)) {
        blockCooldown[mh] = t + BLOCK_COOLDOWN_MS;
        delete blockTrack[mh];
        return;
    }

    if (!canAfford(me, PRICE_TURRET)) return;

    var el = findElByKeys(bestB.u, TURRET_MATCH);
    if (!el) return;

    var bi = BUILD_I_CANDIDATES[(pend.iIdx == null ? 0 : pend.iIdx)];
    var ok = issueBuild(game, me, bestB.u, bx, by, el, bi);
    if (ok) {
        pend.x = bx; pend.y = by;
        pend.issued = true; pend.issueMs = t; pend.bhash = unitHash(bestB.u);
    } else {
        pend.issued = false;
    }
}

// 机枪已死：建造者远离猛犸撤退
function handleMechDead(game, me, mh, mhx, mhy, builders, mammoths) {
    var ref = null, rd = Infinity;
    for (var i = 0; i < mammoths.length; i++) {
        var d = dist(mhx, mhy, getX(mammoths[i]), getY(mammoths[i]));
        if (d < rd) { rd = d; ref = mammoths[i]; }
    }
    if (!ref) return;

    var bu = null;
    var pend = blockTrack[mh];
    if (pend && pend.bhash) {
        for (var b = 0; b < builders.length; b++) {
            if (unitHash(builders[b].u) === pend.bhash) { bu = builders[b]; break; }
        }
    }
    if (!bu && builders.length > 0) {
        var bestD = Infinity;
        for (var b2 = 0; b2 < builders.length; b2++) {
            var dd = dist(mhx, mhy, builders[b2].x, builders[b2].y);
            if (dd < bestD) { bestD = dd; bu = builders[b2]; }
        }
    }
    if (!bu) return;

    var mx = getX(ref), my = getY(ref);
    var bx = getX(bu.u), by = getY(bu.u);
    var dx = bx - mx, dy = by - my;
    var len = Math.sqrt(dx * dx + dy * dy);
    if (len < 0.001) { dx = 1; dy = 0; len = 1; }
    var tx = bx + (dx / len) * RETREAT_DIST;
    var ty = by + (dy / len) * RETREAT_DIST;
    issueMove(game, me, bu.u, tx, ty);
}

function init() {
    passTrack = {};
    blockTrack = {};
    retreated = {};
    blockCooldown = {};
    mechLast = {};
    n = 0;
}