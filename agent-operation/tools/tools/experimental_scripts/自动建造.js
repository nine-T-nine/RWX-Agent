// v34 — 修复跨局不重置 + 30 秒窗口改用 gameTimeMs 绝对值
var GH = Packages.cn.tesseract.union.util.GameHelper;
var UC = Packages.com.corrodinggames.rts.strategy.game.units.class_426;

// ==================== 可调参数 ====================
var BUILDER_SEARCH_RANGE = 100;
var EXTRACTOR_CHECK_RANGE = 30;
var COOLDOWN_TICKS = 60;
var TICK_INTERVAL = 10;
var BUILD_COMPLETE_THRESHOLD = 1.0;
var ASSIGNMENT_TIMEOUT_TICKS = 300;
var BUILDER_ABANDON_RANGE = 200;

var BASE_BUILDER_COUNT = 2;
var BASE_BUILDER_WINDOW_MS = 30000;   // gameTimeMs <= 这个值时才补 builder
var STOP_AFTER_BUILDERS_DONE = 2;
// ==================================================

var cachedPools = null;
var assignments = {};
var cooldown = {};
var elCache = {};
var lastGame = null;
var lastPlayerRef = null;
var lastMapRef = null;
var lastTickSeen = -1;
var lastGameMs = -1;
var myTeam = -1;
var tickCounter = 0;

var achieved = false;
var globalPending = false;
var stopped = false;
var builderDoneOnce = {};

function unitName(u) { try { return String(u.field_1988.method_1660()); } catch(e){ return "?"; } }
function internalName(t) { try { return String(t.method_1670()); } catch(e){ return "?"; } }
function dist2(ax,ay,bx,by){ var dx=ax-bx,dy=ay-by; return dx*dx+dy*dy; }
function getUid(u) { try { var id = u.field_4220; return id == null ? null : ("" + id); } catch(e){ return null; } }
function getTeam(p) { try { return p ? p.field_1464 : -1; } catch(e){ return -1; } }

function resetState() {
    cachedPools = null;
    assignments = {};
    cooldown = {};
    elCache = {};
    achieved = false;
    globalPending = false;
    stopped = false;
    builderDoneOnce = {};
}

// ★ 新局检测：多信号，兼容 game 单例
function isNewGame(game, me, mapRef, now, gameMs) {
    if (lastGame === null) return true;                        // 首次
    if (game !== lastGame) return true;                        // game 换引用
    if (lastPlayerRef && me && me !== lastPlayerRef) return true;  // playerData 换引用
    if (lastMapRef && mapRef && mapRef !== lastMapRef) return true; // map 换引用
    if (lastTickSeen >= 0 && now >= 0 && now < lastTickSeen - 100) return true; // tick 大幅回退
    if (lastGameMs >= 0 && gameMs >= 0 && gameMs < lastGameMs - 5000) return true; // gameMs 回退
    return false;
}

function findEl(unit, pat) {
    try {
        var acts = unit.method_895();
        if (!acts) return null;
        var n = acts.size(); pat = pat.toLowerCase();
        for (var i = 0; i < n; i++) {
            var a = acts.get(i), el = null;
            try { el = a.method_642(); } catch(e){}
            if (!el) continue;
            var nm = "";
            try { nm = String(el.method_1660()).toLowerCase(); } catch(e){ continue; }
            if (nm.indexOf(pat) >= 0) return el;
        }
    } catch (e) {}
    return null;
}

function findElCached(u, uid) {
    var c = elCache[uid];
    if (c) return c;
    var el = findEl(u, "extractor");
    if (el) elCache[uid] = el;
    return el;
}

function issueBuildAction(game, builder, px, py, el) {
    try {
        var cc = game.field_6412;
        if (!cc) return false;
        var pd = builder.field_1927;
        if (!pd) return false;
        var act = cc.method_2058(pd);
        if (!act) return false;
        act.method_2139(builder);
        act.method_2135(px, py, el, 1);
        return true;
    } catch (e) { return false; }
}

function findBuildActionIdByName(factory, targetInternal) {
    try {
        var actions = factory.method_895();
        if (!actions) return null;
        var sz = actions.size();
        for (var i = 0; i < sz; i++) {
            var a = actions.get(i);
            if (!a) continue;
            try {
                var aType = a.field_1550;
                if (!aType) continue;
                if (internalName(aType) === targetInternal) {
                    return a.field_1556;
                }
            } catch (e) {}
        }
    } catch (e) {}
    return null;
}

function scanPools() {
    var out = [];
    try {
        var map = GH.game.field_6339;
        if (!map) return null;
        var ts = map.field_932, half = ts * 0.5;
        var layers = [map.field_939, map.field_943];
        for (var li = 0; li < layers.length; li++) {
            var L = layers[li]; if (!L) continue;
            var W = L.field_995, H = L.field_996;
            for (var x = 0; x < W; x++)
                for (var y = 0; y < H; y++) {
                    var t = L.method_363(x, y);
                    if (t && t.field_1014) out.push({px: x*ts+half, py: y*ts+half});
                }
        }
    } catch (e) {}
    return out;
}

function onTick(tick) {
    try {
        var game = GH.game;
        if (!game) return;
        var me = game.field_6373;
        var now = game.field_6378;
        var gameMs = -1;
        try { gameMs = game.field_6379; } catch (e) {}
        var mapRef = null;
        try { mapRef = game.field_6339; } catch (e) {}

        // ★ 多信号新局检测
        if (isNewGame(game, me, mapRef, now, gameMs)) {
            resetState();
            lastGame = game;
            lastPlayerRef = me;
            lastMapRef = mapRef;
            lastTickSeen = now;
            lastGameMs = gameMs;
            myTeam = -1;
            tickCounter = 0;
        } else {
            if (lastTickSeen >= 0 && now - lastTickSeen < TICK_INTERVAL) return;
            lastTickSeen = now;
            if (gameMs >= 0) lastGameMs = gameMs;
            if (mapRef) lastMapRef = mapRef;
            if (me) lastPlayerRef = me;
        }
        tickCounter++;

        if (stopped) return;
        if (!me) return;
        if (myTeam < 0) myTeam = getTeam(me);

        var ul = UC.field_1908;
        var sz = ul.size();
        var builders = [];
        var extractors = [];
        var bases = [];
        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!u || u.field_1925 || u.field_4222) continue;
            var nm = unitName(u);

            if (nm === "builder") {
                if (getTeam(u.field_1927) === myTeam) builders.push(u);
            } else if (nm === "commandCenter") {
                if (getTeam(u.field_1927) === myTeam) bases.push(u);
            } else if (nm.indexOf("extractor") >= 0) {
                var eC = false;
                try { eC = u.field_1975 >= BUILD_COMPLETE_THRESHOLD; } catch(e){}
                extractors.push({
                    x: u.field_4227,
                    y: u.field_4228,
                    team: getTeam(u.field_1927),
                    complete: eC
                });
            }
        }

        // 实际 builder 数 ≥ 2 → 永久达成
        if (!achieved && builders.length >= BASE_BUILDER_COUNT) {
            achieved = true;
        }

        // ===== 基地补 builder（仅未达成 + 开局 30 秒窗口） =====
        if (!achieved && bases.length > 0 && gameMs >= 0 && gameMs <= BASE_BUILDER_WINDOW_MS) {
            // 先清 pending：任一基地队列非空 → 命令已生效
            if (globalPending) {
                for (var bpi = 0; bpi < bases.length; bpi++) {
                    var qb = null;
                    try { qb = bases[bpi].field_3763.field_3780; } catch (e) {}
                    if (qb && (qb.field_7310 || 0) > 0) {
                        globalPending = false;
                        break;
                    }
                }
            }

            // 全局只有一单在途
            if (!globalPending) {
                for (var bi = 0; bi < bases.length; bi++) {
                    var bu = bases[bi];
                    var buid = getUid(bu);
                    if (!buid) continue;
                    var q = null;
                    try { q = bu.field_3763.field_3780; } catch (e) {}
                    if (!q) continue;
                    if ((q.field_7310 || 0) > 0) continue;

                    var actionId = findBuildActionIdByName(bu, "builder");
                    if (!actionId) continue;

                    var cc = game.field_6412;
                    if (!cc) continue;
                    var pd = bu.field_1927;
                    if (!pd) continue;
                    var act = cc.method_2058(pd);
                    if (!act) continue;
                    act.method_2139(bu);
                    act.method_2138(actionId, null);
                    globalPending = true;
                    break;   // 一轮只下一单
                }
            }
        }

        // ===== 建造者造矿 =====
        if (builders.length === 0) return;

        if (cachedPools == null) {
            cachedPools = scanPools();
            if (!cachedPools || cachedPools.length === 0) return;
        }

        var r2 = EXTRACTOR_CHECK_RANGE * EXTRACTOR_CHECK_RANGE;
        var poolOccupied = [];
        var poolOwnComplete = [];
        for (var pi = 0; pi < cachedPools.length; pi++) {
            var pool = cachedPools[pi];
            var occ = false;
            var ownDone = false;
            for (var ei = 0; ei < extractors.length; ei++) {
                var e = extractors[ei];
                if (dist2(pool.px, pool.py, e.x, e.y) >= r2) continue;
                if (e.team !== myTeam || e.complete) {
                    occ = true;
                    if (e.team === myTeam && e.complete) ownDone = true;
                }
            }
            poolOccupied[pi] = occ;
            poolOwnComplete[pi] = ownDone;
        }

        var newAssign = {};
        for (var ai = 0; ai < builders.length; ai++) {
            var ab = builders[ai];
            var auid = getUid(ab);
            if (!auid) continue;
            var asn = assignments[auid];
            if (!asn) continue;

            if (asn.pi >= 0 && poolOccupied[asn.pi]) {
                if (poolOwnComplete[asn.pi]) {
                    if (!builderDoneOnce[auid]) builderDoneOnce[auid] = true;
                }
                continue;
            }

            if (now - asn.at > ASSIGNMENT_TIMEOUT_TICKS) {
                var d2b = dist2(ab.field_4227, ab.field_4228, asn.px, asn.py);
                if (d2b > BUILDER_ABANDON_RANGE * BUILDER_ABANDON_RANGE) {
                    continue;
                }
                var el2 = findElCached(ab, auid);
                if (el2 && issueBuildAction(game, ab, asn.px, asn.py, el2)) {
                    asn.at = now;
                } else {
                    continue;
                }
            }
            newAssign[auid] = asn;
        }
        assignments = newAssign;

        var doneCount = 0;
        for (var dk in builderDoneOnce) {
            if (builderDoneOnce[dk]) doneCount++;
        }
        if (doneCount >= STOP_AFTER_BUILDERS_DONE) {
            stopped = true;
            return;
        }

        for (var di = 0; di < builders.length; di++) {
            var db = builders[di];
            var duid = getUid(db);
            if (!duid) continue;

            if ((cooldown[duid] || 0) > now) continue;
            if (assignments[duid]) continue;

            var del = findElCached(db, duid);
            if (!del) continue;

            var bx = db.field_4227, by = db.field_4228;
            var bestPi = -1;
            var bestD2 = BUILDER_SEARCH_RANGE * BUILDER_SEARCH_RANGE;
            for (var qi = 0; qi < cachedPools.length; qi++) {
                if (poolOccupied[qi]) continue;
                var qp = cachedPools[qi];
                var d2 = dist2(bx, by, qp.px, qp.py);
                if (d2 < bestD2) { bestD2 = d2; bestPi = qi; }
            }
            if (bestPi < 0) continue;

            var bp = cachedPools[bestPi];
            if (issueBuildAction(game, db, bp.px, bp.py, del)) {
                assignments[duid] = { pi: bestPi, px: bp.px, py: bp.py, at: now };
                cooldown[duid] = now + COOLDOWN_TICKS;
            }
        }
    } catch (e) {}
}

function init() {
    lastGame = null;
    lastPlayerRef = null;
    lastMapRef = null;
    lastTickSeen = -1;
    lastGameMs = -1;
    myTeam = -1;
    tickCounter = 0;
    resetState();
}