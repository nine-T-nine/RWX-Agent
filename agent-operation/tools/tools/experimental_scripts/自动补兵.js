// ============================================================
// 自动补兵 — v9
// 无日志版
// ============================================================

var EventHook = Packages.cn.tesseract.union.hook.EventHook;
var GH        = Packages.cn.tesseract.union.util.GameHelper;
var UC        = Packages.com.corrodinggames.rts.strategy.game.units.class_426;

var SCRIPT_ID = (typeof id !== "undefined" && id) ? String(id) : "自动补兵.js";
var TICK_GAP = 12;
var PENDING_TIMEOUT = TICK_GAP * 15;
var CHECK_RESOURCES = true;

var n = 0;
var factoryMemory = {};        // { factoryId: { unitType, pending, pendingSince } }
var _queueFieldCache = {};     // { className: fieldName | null }

function getUnitId(u) { try { return String(u.field_4220); } catch (e) { return null; } }
function isOwnUnit(u, me) { try { return u.field_1927 === me; } catch (e) { return false; } }
function isAlive(u) { try { return u && !u.field_1925 && !u.field_4222; } catch (e) { return false; } }
function isUnitAllowed(u) {
    if (!EventHook) return true;
    try { return EventHook.isUnitStrategyEnabled(u.field_4220, SCRIPT_ID); }
    catch (e) { return true; }
}
function getQueueItem(queue, i) { try { return queue.get(i); } catch (e) { return null; } }
function getItemUnitType(item) { try { return item.field_3770; } catch (e) { return null; } }
function internalName(t) { try { return String(t.method_1670()); } catch (e) { return null; } }

// ---------- 队列字段探测 ----------
function looksLikeQueueManager(v) {
    try {
        if (v == null) return false;
        if (v.field_3780 === undefined) return false;
        var q = v.field_3780;
        if (q == null) return true;
        if (q.field_7310 === undefined) return false;
        return true;
    } catch (e) { return false; }
}

function detectQueueField(u) {
    try {
        var clsName = u.getClass().getName();
        if (_queueFieldCache.hasOwnProperty(clsName)) {
            return _queueFieldCache[clsName];
        }

        // 快路径
        try {
            var v3763 = u.field_3763;
            if (v3763 != null && looksLikeQueueManager(v3763)) {
                _queueFieldCache[clsName] = "field_3763";
                return "field_3763";
            }
        } catch (e) {}

        // 慢路径：反射扫描
        var c = u.getClass();
        while (c && c.getName() !== "java.lang.Object") {
            var fields;
            try { fields = c.getDeclaredFields(); } catch (e) { fields = null; }
            if (fields) {
                for (var i = 0; i < fields.length; i++) {
                    var f = fields[i];
                    try { f.setAccessible(true); } catch (e) { continue; }
                    var v;
                    try { v = f.get(u); } catch (e) { continue; }
                    if (looksLikeQueueManager(v)) {
                        var fn = f.getName();
                        _queueFieldCache[clsName] = fn;
                        return fn;
                    }
                }
            }
            c = c.getSuperclass();
        }

        _queueFieldCache[clsName] = null;
        return null;
    } catch (e) {
        return null;
    }
}

function getQueueManager(u) {
    var f = detectQueueField(u);
    if (!f) return null;
    try { return u[f]; } catch (e) { return null; }
}

function isFactory(u) {
    return detectQueueField(u) !== null;
}

function getQueue(factory) {
    try {
        var mgr = getQueueManager(factory);
        if (!mgr) return null;
        var q = mgr.field_3780;
        return (q && q.field_7310 !== undefined) ? q : null;
    } catch (e) { return null; }
}

// ---------- 资源检查 ----------
function getUnitPrice(unitType) {
    try {
        if (!unitType) return null;
        try {
            if (unitType.field_3308) {
                var p = unitType.field_3308;
                if (typeof p === "number") return p;
                if (p && typeof p.field_2933 === "number") return p.field_2933;
            }
        } catch (e) {}
        var methods = ["method_1496", "method_1104", "method_1454", "method_1059"];
        for (var i = 0; i < methods.length; i++) {
            if (typeof unitType[methods[i]] === "function") {
                try {
                    var ref = unitType[methods[i]]();
                    if (ref && ref.field_3308) {
                        var p2 = ref.field_3308;
                        if (typeof p2 === "number") return p2;
                        if (p2 && typeof p2.field_2933 === "number") return p2.field_2933;
                    }
                } catch (e) {}
            }
        }
    } catch (e) {}
    return null;
}

function hasEnoughCredits(me, unitType) {
    if (!CHECK_RESOURCES) return true;
    try {
        var price = getUnitPrice(unitType);
        if (price === null || price === undefined) return true;
        var credits = me.field_1461;
        if (typeof credits !== "number") return true;
        return credits >= price;
    } catch (e) { return true; }
}

// ---------- ActionId 查找 ----------
function findBuildActionId(factory, targetType) {
    try {
        var actions = factory.method_895();
        if (!actions) return null;
        var sz = actions.size();
        var targetInternal = internalName(targetType);
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

// ---------- 主循环 ----------
function onTick(tick) {
    n++;
    if (n % TICK_GAP !== 0) return;

    try {
        var game = GH.game; if (!game) return;
        var me = game.field_6373; if (!me) return;
        var ul = UC.field_1908; if (!ul) return;
        var sz = ul.size();

        var aliveFactories = {};

        for (var i = 0; i < sz; i++) {
            var u = ul.get(i);
            if (!isAlive(u)) continue;
            if (!isOwnUnit(u, me)) continue;
            if (!isFactory(u)) continue;

            var fid = getUnitId(u);
            if (!fid) continue;
            aliveFactories[fid] = true;

            if (!isUnitAllowed(u)) continue;

            var queue = getQueue(u);
            if (!queue) continue;

            var qLen = queue.field_7310;

            // 队列非空 → 记录 + 清 pending
            if (qLen > 0) {
                var lastItem = getQueueItem(queue, qLen - 1);
                if (lastItem) {
                    var lastType = getItemUnitType(lastItem);
                    if (lastType) {
                        factoryMemory[fid] = {
                            unitType: lastType,
                            pending: false,
                            pendingSince: 0
                        };
                    }
                }
                continue;
            }

            // 队列空 + 有记忆
            if (qLen === 0 && factoryMemory[fid]) {
                var mem = factoryMemory[fid];
                if (!mem.unitType) continue;

                if (mem.pending) {
                    if (n - mem.pendingSince > PENDING_TIMEOUT) {
                        mem.pending = false;
                    } else {
                        continue;
                    }
                }

                if (!hasEnoughCredits(me, mem.unitType)) continue;

                var actionId = findBuildActionId(u, mem.unitType);
                if (!actionId) continue;

                try {
                    var act = game.field_6412.method_2058(u.field_1927);
                    if (!act) continue;
                    act.method_2139(u);
                    act.method_2138(actionId, null);
                    mem.pending = true;
                    mem.pendingSince = n;
                } catch (e) {}
            }
        }

        for (var id in factoryMemory) {
            if (!aliveFactories[id]) delete factoryMemory[id];
        }

    } catch (e) {}
}

function init() {}