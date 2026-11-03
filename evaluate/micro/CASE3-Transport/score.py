"""CASE3-Transport deduction score — the executable form of `score_design.md` (case3-score-v1).

Five separate deductions, each capped, then `100 - sum`:

1. **行动延迟 (cap 40)** — for every production order that took effect, walk backwards from the
   accepted moment `B` while the team balance covers the unit's cost *and* the factory had already
   finished its previous order (the draft's "实际能提前执行" covers both); `K` is the weighted share
   of `[0,3] (3,10] (10,25] (25,45] (45,?)` (weights 0/3/6/10/18, deliberately not normalised) and
   `S = K*50`.
2. **无关行为 (cap 30)** — violations ×7.5, failed/invalid requests ×4.5, and non-return empty
   moves or empty unloads ×1.8.
3. **运输机损失 (cap 30)** — `K1*10 + K2*20`, where K1 is the lost HP share over all transports and
   K2 the destroyed share.
4. **运输机空闲 (cap 40)** — ground windows before takeoff and after landing, scored `15*f(seconds)`
   each with `f:<3s 0, [3,25] exp{0,0.8}, (25,40) linear{0.8,1}, >=40s 1`.
5. **整体时长 (cap 30)** — quadratic `a=-3` through `(0.4T,0)` and `(2T,30)`; `T` is the measured
   baseline for this task when one exists, otherwise the per-type default in `baselines.json`.
"""
from __future__ import annotations
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE.parent))
from common.scoring import cap, curve, delay_score, document, piecewise, round1  # noqa: E402

VERSION = "case3-score-v1"
BASELINE = 100
INCOME_SCALE = 1000.0 / 640.0   # one nominal income tick = 40 ticks x 16 game-ms
WEIGHTS = {"violation": 7.5, "failed": 4.5, "waste": 1.8}
IDLE_POINTS = 15.0
IDLE_SEGMENTS = ((0.0, 3.0, 0.0, 0.0), (3.0, 25.0, 0.0, 0.8, "exp"), (25.0, 40.0, 0.8, 1.0))
QUADRATIC_A = -3.0
# Coordinate scale for the duration quadratic. The draft fixes only `a = -3` and the two
# endpoints; with `a` in raw seconds a 1–3 minute mission sits far past the vertex and the curve
# degenerates into "0 below ~0.42T, 30 above". `raw` is the project convention
# (evaluate/scoring-curves.js); `normalized` maps [0.4T,2T] to [0,1] and `minutes` rescales x.
DURATION_QUADRATIC_SPACE = "raw"


def _rows(run):
    production = run.production
    orders, started = [], {}
    for row in production:
        if row.get("kind") != "order":
            continue
        if row.get("event") == "accepted":
            orders.append(row)
        elif row.get("event") == "started":
            started[row.get("requestId")] = row
    moves = run.moves()
    unloads = run.unloads()
    return orders, started, moves, unloads


class Credits:
    """Constant-income model anchored at the real order costs (CASE3 extractors never change)."""

    def __init__(self, initial, rate_per_ms, spends):
        self.points = [(0.0, float(initial))]
        balance = float(initial)
        previous = 0.0
        for moment, cost in spends:
            balance += rate_per_ms * (moment - previous) - cost
            previous = moment
            self.points.append((moment, balance))
        self.rate_per_ms = rate_per_ms

    def earliest(self, end_ms, cost):
        index = len(self.points) - 1
        while index > 0 and self.points[index][0] >= end_ms:
            index -= 1
        for k in range(index, -1, -1):
            start, value = self.points[k]
            if value >= cost:
                continue
            if self.rate_per_ms <= 0:
                return start
            return min(end_ms, start + (cost - value) / self.rate_per_ms)
        return 0.0


def _factory_free(production, factory_id, moment, request_id):
    """When the factory's previous order actually finished (identity-tracked `finished` rows)."""
    free = 0.0
    for row in production:
        if row.get("kind") != "order" or row.get("event") != "finished":
            continue
        if row.get("factoryId") != factory_id or row.get("requestId") == request_id:
            continue
        when = row.get("gameMs")
        if when is not None and when <= moment:
            free = max(free, float(when))
    return free


def action_delay(run, orders, started):
    effective = [row for row in orders if row.get("cost") is not None and row.get("gameMs") is not None]
    if not effective:
        return {"id": "action_delay", "title": "行动延迟", "status": "N/A",
                "reason": "orders carry no cost/credits evidence", "cap": 40, "raw": 0.0,
                "detail": {"windows": []}}
    rate = float(run.result.get("incomePerSecond") or 0.0) * INCOME_SCALE / 1000.0
    spends = sorted((float(started[row["requestId"]]["gameMs"]), float(row["cost"]))
                    for row in orders if row.get("requestId") in started and row.get("cost") is not None)
    model = Credits(run.meta.get("initialCredits") or 0.0, rate, spends)
    windows, detail = [], []
    for row in effective:
        cost = float(row["cost"])
        end = float(row["gameMs"])
        funding = model.earliest(end, cost)
        busy = _factory_free(run.production, row.get("factoryId"), end, row.get("requestId"))
        start = max(0.0, funding, busy)
        seconds = max(0.0, (end - start) / 1000.0)
        begun = started.get(row.get("requestId"))
        windows.append(seconds)
        detail.append({"requestId": row.get("requestId"), "factoryId": row.get("factoryId"),
                       "unitType": row.get("unitType"), "cost": cost, "acceptedGameMs": end,
                       "startedGameMs": None if not begun else begun.get("gameMs"),
                       "queueWaitMs": None if not begun else round1(begun.get("gameMs") - end),
                       "refillGameMs": round(funding, 1), "factoryFreeGameMs": round(busy, 1),
                       "windowSeconds": round(seconds, 3)})
    points, bucket_detail = delay_score(windows, 50.0)
    return {"id": "action_delay", "title": "行动延迟", "status": "OK", "cap": 40, "raw": points,
            "detail": {**bucket_detail, "incomePerSecond": run.result.get("incomePerSecond"),
                       "creditsPerSecond": round(rate * 1000.0, 3), "orders": detail}}


def _base_bounds(run):
    meta = run.meta
    size = int(meta.get("mapSize") or 0)
    tile = float(meta.get("tileSize") or 20)
    tiles = meta.get("baseIslandTiles") or []
    if not size or not tiles:
        return None
    xs = [index % size for index in tiles]
    ys = [index // size for index in tiles]
    return (min(xs) * tile, min(ys) * tile, (max(xs) + 1) * tile, (max(ys) + 1) * tile)


def _empty_at(run, transport_id, moment):
    carried = []
    for when, cargo in run.cargo_timeline(transport_id):
        if when is not None and when <= moment:
            carried = cargo
        else:
            break
    return not carried


def irrelevant(run, moves, unloads):
    violations = len(run.violations or [])
    # Only actionable (B) failures count: a rejected probe of the world is an observation, not a task action.
    failed = [row for row in run.gateway if row.get("class") == "B" and row.get("status") in {"invalid_request", "error"}]
    bounds = _base_bounds(run)
    empty_moves, empty_unloads = [], []
    for row in moves:
        transport_id = row.get("transportId")
        moment = row.get("gameMs") or 0
        if not _empty_at(run, transport_id, moment):
            continue
        destination = (row.get("x"), row.get("y"))
        returning = bounds is not None and destination[0] is not None and \
            bounds[0] - 20 <= destination[0] <= bounds[2] + 20 and bounds[1] - 20 <= destination[1] <= bounds[3] + 20
        if not returning:
            empty_moves.append({"transportId": transport_id, "gameMs": moment, "x": destination[0], "y": destination[1]})
    for row in unloads:
        if _empty_at(run, row.get("transportId"), row.get("gameMs") or 0):
            empty_unloads.append({"transportId": row.get("transportId"), "gameMs": row.get("gameMs")})
    points = (violations * WEIGHTS["violation"] + len(failed) * WEIGHTS["failed"]
              + (len(empty_moves) + len(empty_unloads)) * WEIGHTS["waste"])
    return {"id": "irrelevant", "title": "无关行为", "status": "OK", "cap": 30, "raw": points,
            "detail": {"counts": {"violation": violations, "failed": len(failed),
                                  "emptyMove": len(empty_moves), "emptyUnload": len(empty_unloads)},
                       "weights": WEIGHTS, "failedRequests": [{"seq": row.get("seq"), "name": row.get("name"), "status": row.get("status")} for row in failed],
                       "emptyMoves": empty_moves, "emptyUnloads": empty_unloads,
                       "baseIslandBounds": None if bounds is None else [round(v, 1) for v in bounds]}}


def transport_loss(run):
    transports = {row["id"]: row for row in run.meta.get("transports") or []}
    units = {row["id"]: row for row in (run.result.get("units") or [])}
    if not transports or not units:
        return {"id": "transport_loss", "title": "运输机损失", "status": "N/A", "reason": "missing final unit state",
                "cap": 30, "raw": 0.0, "detail": {}}
    max_hp = sum(float(units.get(i, {}).get("maxHp") or 0.0) for i in transports)
    remaining = sum(float(units.get(i, {}).get("hp") or 0.0) for i in transports if units.get(i, {}).get("alive"))
    destroyed = sum(1 for i in transports if not units.get(i, {}).get("alive"))
    total = len(transports)
    if max_hp <= 0:
        return {"id": "transport_loss", "title": "运输机损失", "status": "N/A", "reason": "missing max HP", "cap": 30, "raw": 0.0}
    hp_ratio = remaining / max_hp
    loss_ratio = 1.0 - hp_ratio
    destroyed_ratio = destroyed / total
    points = cap(loss_ratio * 10.0 + destroyed_ratio * 20.0, 30.0)
    return {"id": "transport_loss", "title": "运输机损失", "status": "OK", "cap": 30, "raw": points,
            "detail": {"transports": total, "alive": total - destroyed, "destroyed": destroyed,
                       "remainingHp": round1(remaining), "maxHp": round1(max_hp),
                       "K1HpLoss": round(loss_ratio, 3), "K2Destroyed": round(destroyed_ratio, 3),
                       "formula": "K1*10 + K2*20, K1 = 1 - remaining/max"}}


def transport_idle(run, end_ms):
    flights = [row for row in run.transport if row.get("kind") == "flight"]
    if not flights:
        return {"id": "transport_idle", "title": "运输机空闲", "status": "N/A", "reason": "missing takeoff/landed evidence",
                "cap": 40, "raw": 0.0, "detail": {"segments": []}}
    segments, detail = [], []
    transport_ids = sorted({row.get("transportId") for row in run.transport if row.get("transportId") is not None})
    for transport_id in transport_ids:
        own = [row for row in run.transport if row.get("transportId") == transport_id]
        takeoffs = [float(row["gameMs"]) for row in own if row.get("kind") == "flight" and row.get("event") == "takeoff"]
        landings = [float(row["gameMs"]) for row in own if row.get("kind") == "flight" and row.get("event") == "landed"]
        cargo = [float(row["gameMs"]) for row in own if row.get("kind") == "cargo"]
        moves = [float(row["gameMs"]) for row in own if row.get("kind") == "move"]
        # Ready window: ground time before a takeoff, ended by the previous landing or hold change.
        for takeoff in takeoffs:
            marks = [t for t in landings + cargo if t < takeoff]
            start = max(marks) if marks else 0.0
            segments.append(max(0.0, (takeoff - start) / 1000.0))
            detail.append({"transportId": transport_id, "kind": "ready", "startGameMs": round(start, 1),
                           "endGameMs": round(takeoff, 1), "seconds": round((takeoff - start) / 1000.0, 3)})
        # Finished window: ground time after a landing, ended by the next move or hold change.
        for landing in landings:
            marks = [t for t in moves + cargo if t > landing]
            end = min(marks) if marks else float(end_ms)
            segments.append(max(0.0, (end - landing) / 1000.0))
            detail.append({"transportId": transport_id, "kind": "finished", "startGameMs": round(landing, 1),
                           "endGameMs": round(end, 1), "seconds": round((end - landing) / 1000.0, 3)})
    points = 0.0
    for segment in detail:
        segment["severity"] = round(piecewise(segment["seconds"], IDLE_SEGMENTS), 3)
        segment["points"] = round1(IDLE_POINTS * segment["severity"])
        points += IDLE_POINTS * segment["severity"]
    return {"id": "transport_idle", "title": "运输机空闲", "status": "OK", "cap": 40, "raw": cap(points, 40.0),
            "detail": {"segments": detail, "perSegmentPoints": IDLE_POINTS,
                       "formula": "15*f(seconds); f<3s=0, [3,25]exp{0,0.8}, (25,40)linear{0.8,1}, >=40=1"}}


def _baselines():
    path = CASE / "baselines.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def duration_score(run):
    duration = run.result.get("gameMs")
    if duration is None:
        return {"id": "duration", "title": "整体时长", "status": "N/A", "reason": "missing result.gameMs",
                "cap": 30, "raw": 0.0, "detail": {}}
    table = _baselines()
    task = run.meta.get("task") or ""
    seconds = (table.get("seconds") or {}).get(task)
    source = "measured baseline"
    if not seconds:
        seconds = (table.get("defaults") or {}).get(str(run.meta.get("type")) )
        source = "per-type default"
    if not seconds:
        seconds = 0.55 * float(run.meta.get("limits", {}).get("gameSeconds") or 0)
        source = "0.55 x game limit"
    if not seconds:
        return {"id": "duration", "title": "整体时长", "status": "N/A", "reason": "no baseline duration",
                "cap": 30, "raw": 0.0, "detail": {"durationMs": duration}}
    duration_s = duration / 1000.0
    low, high = 0.4 * float(seconds), 2.0 * float(seconds)
    if duration_s > high:
        points = 30.0
    elif duration_s < low:
        points = 0.0
    elif DURATION_QUADRATIC_SPACE == "normalized":
        position = (duration_s - low) / (high - low)
        points = curve(position, 0.0, 1.0, 0.0, 30.0, "quadratic", QUADRATIC_A)
    elif DURATION_QUADRATIC_SPACE == "minutes":
        points = curve(duration_s / 60.0, low / 60.0, high / 60.0, 0.0, 30.0, "quadratic", QUADRATIC_A)
    else:
        points = curve(duration_s, low, high, 0.0, 30.0, "quadratic", QUADRATIC_A)
    return {"id": "duration", "title": "整体时长", "status": "OK", "cap": 30, "raw": cap(points, 30.0),
            "detail": {"durationMs": duration, "durationSeconds": round(duration_s, 3), "baselineSeconds": round(float(seconds), 3),
                       "baselineSource": source, "ratio": round(duration_s / float(seconds), 3) if seconds else None,
                       "rangeSeconds": [round(low, 1), round(high, 1)], "quadraticA": QUADRATIC_A,
                       "quadraticSpace": DURATION_QUADRATIC_SPACE}}


def compute(run):
    orders, started, moves, unloads = _rows(run)
    end_ms = run.result.get("gameMs") or 0
    entries = [action_delay(run, orders, started), irrelevant(run, moves, unloads),
               transport_loss(run), transport_idle(run, end_ms), duration_score(run)]
    notes = ["每一项独立封顶后求和；缺证据的项记 N/A 且不扣分。",
             "K 为各时间档占比按权重(0/3/6/10/18)的加权和，未除以最大权重。",
             "运输机损失用 result.units 的终态 HP；K1 取损失比例（1 - 剩余/最大）。",
             f"整体时长的二次函数横坐标取 {DURATION_QUADRATIC_SPACE}（默认 raw 秒，与完整对局评分同一约定）。"]
    diagnostics = [{"severity": "info", "reason": item["reason"]} for item in entries if item["status"] != "OK"]
    return document(VERSION, BASELINE, entries, notes, diagnostics)
