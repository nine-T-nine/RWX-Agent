"""CASE4-Retreat deduction score — the executable form of `score_design.md` (case4-score-v1).

Three separate deductions, each capped, then `100 - sum`:

1. **屏蔽器气垫坦克损失 (cap 20)** — `S = K1*30`, `K1` = destroyed tanks / tanks in the task.
2. **屏蔽器气垫坦克空闲 (cap 50)** — from the engine's 250 game-ms `tank_state` timeline: a tank is
   *ready* inside a repair bay at full HP while idle (> 3 s) and *loitering* outside every bay
   while idle (> 8 s). `w = ΣA*1.5 + ΣB*2`, `T` = summed alive time of all tanks and
   `S = 50*(w/T)/R`; `R` is the draft's hyper-parameter: 5 % unless `baselines.json` records a
   measured baseline `w/T`, in which case `R = 5 x (w/T)`.
3. **整体时长 (cap 30)** — quadratic through `(0.4T, 0)` and `(2T, 30)` with `a = -3`; `T` is the
   measured baseline duration for the task, else the per-type default, else `0.55 x` the game limit.

Nothing is invented when evidence is missing: the item then reports `status="N/A"` and 0 points.
"""
from __future__ import annotations
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE.parent))
from common.scoring import cap, curve, document, round1  # noqa: E402

VERSION = "case4-score-v1"
BASELINE = 100
LOSS_SCALE = 30.0
IDLE_SCALE = 50.0
IDLE_WEIGHTS = {"ready": 1.5, "loiter": 2.0}
IDLE_MIN_SECONDS = {"ready": 3.0, "loiter": 8.0}
# `R` when no measured baseline w/T exists. The draft calls it "initial 5 %".
IDLE_RATIO_DEFAULT = 0.05
IDLE_RATIO_MULTIPLIER = 5.0
QUADRATIC_A = -3.0
# The draft fixes only `a=-3` and the two endpoints. With `T` = the measured baseline (~50 s for
# 4_1_1) a quadratic solved in raw seconds is a step: every run slower than ~0.42T already scores
# 30, so the item would be constant. `normalized` maps [0.4T, 2T] to [0,1] and keeps the curve graded;
# `raw` (CASE3's current default) and `minutes` are available for comparison.
DURATION_QUADRATIC_SPACE = "normalized"
# Full HP is an engine float; 0.001 absorbs representation noise without treating a heal-in-progress
# tank as ready.
FULL_HP_EPSILON = 1e-3


def _tanks(run):
    return sorted((run.tanks or {}).values(), key=lambda row: row["id"])


def _final_alive(run):
    """unitId -> alive from the authoritative final state."""
    rows = {row.get("unitId"): bool(row.get("alive")) for row in (run.result.get("tanks") or [])}
    if rows:
        return rows
    final = run.result.get("units")
    if final:
        return {row["id"]: bool(row.get("alive")) for row in final}
    return None


def tank_loss(run):
    tanks = _tanks(run)
    if not tanks:
        return {"id": "tank_loss", "title": "屏蔽器气垫坦克损失", "status": "N/A",
                "reason": "no tanks in meta", "cap": 20, "raw": 0.0, "detail": {}}
    final = _final_alive(run)
    if final is None:
        return {"id": "tank_loss", "title": "屏蔽器气垫坦克损失", "status": "N/A",
                "reason": "missing final unit state", "cap": 20, "raw": 0.0, "detail": {}}
    destroyed = [row["id"] for row in tanks if not final.get(row["id"], True)]
    share = len(destroyed) / len(tanks)
    return {"id": "tank_loss", "title": "屏蔽器气垫坦克损失", "status": "OK", "cap": 20,
            "raw": share * LOSS_SCALE,
            "detail": {"tanks": len(tanks), "alive": len(tanks) - len(destroyed), "destroyed": len(destroyed),
                       "K1": round(share, 3), "scale": LOSS_SCALE, "destroyedIds": destroyed,
                       "units": [{"unitId": row["id"], "alias": row.get("alias"),
                                  "alive": final.get(row["id"], True)} for row in tanks],
                       "formula": "K1*30, K1 = destroyed/tanks"}}


def _samples(run):
    """unitId -> list of state rows, sorted by time."""
    samples = {}
    for row in run.tank_state or []:
        moment = row.get("gameMs")
        if moment is None:
            continue
        for tank in row.get("tanks") or []:
            unit = tank.get("unitId")
            if unit is None:
                continue
            samples.setdefault(unit, []).append({
                "ms": float(moment), "alive": bool(tank.get("alive")),
                "order": tank.get("order") or "idle",
                "hp": float(tank.get("hp") or 0.0), "maxHp": float(tank.get("maxHp") or 0.0),
                "bays": [bay for bay in (tank.get("repairBayIds") or []) if bay is not None],
            })
    for rows in samples.values():
        rows.sort(key=lambda row: row["ms"])
    return samples


def _kind(sample):
    """`ready` (in bay, full HP, idle), `loiter` (outside every bay, idle) or None."""
    if not sample["alive"] or sample["order"] != "idle":
        return None
    in_range = bool(sample["bays"])
    if in_range:
        full = sample["maxHp"] > 0 and sample["hp"] >= sample["maxHp"] - FULL_HP_EPSILON
        return "ready" if full else None
    return "loiter"


def _unit_windows(rows, begin_ms, end_ms):
    """Maximal ready/loiter intervals for one tank, clipped to [begin_ms, end_ms)."""
    windows, current = [], None
    times = [row["ms"] for row in rows]
    for index, row in enumerate(rows):
        start = max(row["ms"], begin_ms)
        if start >= end_ms:
            break
        following = min(times[index + 1], end_ms) if index + 1 < len(times) else end_ms
        if following <= start:
            continue
        kind = _kind(row)
        if kind is None:
            if current:
                windows.append(current)
                current = None
            continue
        if current and current["kind"] == kind:
            current["end"] = following
        else:
            if current:
                windows.append(current)
            current = {"kind": kind, "start": start, "end": following}
    if current:
        windows.append(current)
    return [window for window in windows if window["end"] > window["start"]]


def _begin_ms(run):
    """Task start: the first engine sample while `phase=running` (≈ the `begin` lifecycle moment)."""
    times = [rows[0]["ms"] for rows in _samples(run).values() if rows]
    return min(times) if times else 0.0


def _death_ms(run, unit_id):
    tick_ms = float(run.meta.get("tickDurationMs") or 16)
    for kill in run.result.get("kills") or []:
        if kill.get("unit") == unit_id and kill.get("tick") is not None:
            return float(kill["tick"]) * tick_ms
    return None


def _baselines():
    try:
        return json.loads((CASE / "baselines.json").read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _idle_reference(run):
    """(R, source): 5× the measured baseline w/T when one exists, else the 5 % default."""
    table = _baselines()
    task = run.meta.get("task") or ""
    ratio = (table.get("idleRatios") or {}).get(task)
    source = "measured baseline"
    if ratio is None:
        ratio = (table.get("idleRatioDefaults") or {}).get(str(run.meta.get("type")))
        source = "per-type default"
    # A baseline that never idles gives R = 0, which cannot normalise anything; keep the 5 % default.
    if ratio and float(ratio) > 0:
        return IDLE_RATIO_MULTIPLIER * float(ratio), source
    if ratio is not None:
        source = f"{source} w/T=0 -> default"
    else:
        source = "default R=5%"
    return IDLE_RATIO_DEFAULT, source


def tank_idle(run):
    samples = _samples(run)
    tanks = _tanks(run)
    if not run.tank_state or not samples:
        return {"id": "tank_idle", "title": "屏蔽器气垫坦克空闲", "status": "N/A",
                "reason": "missing engine/tank_state.jsonl (run predates the 250 ms tank timeline)",
                "cap": 50, "raw": 0.0, "detail": {"windows": []}}
    if not tanks:
        return {"id": "tank_idle", "title": "屏蔽器气垫坦克空闲", "status": "N/A",
                "reason": "no tanks in meta", "cap": 50, "raw": 0.0, "detail": {"windows": []}}
    end_ms = float(run.result.get("gameMs") or 0)
    begin_ms = _begin_ms(run)
    if end_ms <= begin_ms:
        return {"id": "tank_idle", "title": "屏蔽器气垫坦克空闲", "status": "N/A",
                "reason": "task timeline is empty", "cap": 50, "raw": 0.0, "detail": {"windows": []}}
    windows, alive = [], {}
    for tank in tanks:
        rows = samples.get(tank["id"]) or []
        if not rows:
            continue
        death = _death_ms(run, tank["id"])
        unit_end = min(end_ms, death) if death is not None else end_ms
        start = max(begin_ms, rows[0]["ms"])
        alive[tank["id"]] = max(0.0, (unit_end - start) / 1000.0)
        for window in _unit_windows(rows, begin_ms, unit_end):
            windows.append({"unitId": tank["id"], "alias": tank.get("alias"), **window})
    total_seconds = sum(alive.values())
    reference, source = _idle_reference(run)
    if not windows:
        return {"id": "tank_idle", "title": "屏蔽器气垫坦克空闲", "status": "OK", "cap": 50, "raw": 0.0,
                "detail": {"windows": [], "beginGameMs": round(begin_ms, 1),
                           "aliveSeconds": round(total_seconds, 3),
                           "aliveByUnit": {str(key): round1(value) for key, value in alive.items()},
                           "weightedSeconds": 0.0, "ratio": 0.0,
                           "R": round(reference, 4), "RSource": source,
                           "weights": IDLE_WEIGHTS, "minimumSeconds": IDLE_MIN_SECONDS,
                           "samplingMs": 250,
                           "formula": "50*(w/T)/R, w = sum(A)*1.5 + sum(B)*2"}}
    rows, weighted = [], 0.0
    for window in windows:
        seconds = (window["end"] - window["start"]) / 1000.0
        weight = IDLE_WEIGHTS[window["kind"]]
        counted = seconds > IDLE_MIN_SECONDS[window["kind"]]
        points = seconds * weight if counted else 0.0
        weighted += points
        rows.append({"unitId": window["unitId"], "alias": window.get("alias"), "kind": window["kind"],
                     "startMs": round(window["start"], 1), "endMs": round(window["end"], 1),
                     "seconds": round(seconds, 3), "weight": weight,
                     "minimumSeconds": IDLE_MIN_SECONDS[window["kind"]], "counted": counted,
                     "points": round1(points)})
    ratio = weighted / total_seconds if total_seconds > 0 else None
    raw = IDLE_SCALE * ratio / reference if ratio is not None and reference > 0 else 0.0
    return {"id": "tank_idle", "title": "屏蔽器气垫坦克空闲", "status": "OK", "cap": 50, "raw": raw,
            "detail": {"windows": rows, "beginGameMs": round(begin_ms, 1),
                       "aliveSeconds": round(total_seconds, 3),
                       "aliveByUnit": {str(key): round1(value) for key, value in alive.items()},
                       "weightedSeconds": round(weighted, 3),
                       "ratio": None if ratio is None else round(ratio, 4),
                       "R": round(reference, 4), "RSource": source,
                       "weights": IDLE_WEIGHTS, "minimumSeconds": IDLE_MIN_SECONDS,
                       "samplingMs": 250,
                       "formula": "50*(w/T)/R, w = sum(A)*1.5 + sum(B)*2"}}


def _baseline_seconds(run):
    table = _baselines()
    task = run.meta.get("task") or ""
    seconds = (table.get("seconds") or {}).get(task)
    source = "measured baseline"
    if not seconds:
        seconds = (table.get("defaults") or {}).get(str(run.meta.get("type")))
        source = "per-type default"
    if not seconds:
        limit = float((run.meta.get("limits") or {}).get("gameSeconds") or 0.0)
        if limit:
            seconds = 0.55 * limit
            source = "0.55 x game limit"
    return seconds, source


def duration_score(run, begin_ms=None):
    end = run.result.get("gameMs")
    if end is None:
        return {"id": "duration", "title": "整体时长", "status": "N/A", "reason": "missing result.gameMs",
                "cap": 30, "raw": 0.0, "detail": {}}
    end_ms = float(end)
    start_ms = float(begin_ms or 0.0)
    duration_s = max(0.0, (end_ms - start_ms) / 1000.0)
    seconds, source = _baseline_seconds(run)
    if not seconds:
        return {"id": "duration", "title": "整体时长", "status": "N/A", "reason": "no baseline duration",
                "cap": 30, "raw": 0.0, "detail": {"durationSeconds": round(duration_s, 3)}}
    low, high = 0.4 * float(seconds), 2.0 * float(seconds)
    if duration_s < low:
        points = 0.0
    elif duration_s > high:
        points = 30.0
    elif DURATION_QUADRATIC_SPACE == "normalized":
        points = curve((duration_s - low) / (high - low), 0.0, 1.0, 0.0, 30.0, "quadratic", QUADRATIC_A)
    elif DURATION_QUADRATIC_SPACE == "minutes":
        points = curve(duration_s / 60.0, low / 60.0, high / 60.0, 0.0, 30.0, "quadratic", QUADRATIC_A)
    else:
        points = curve(duration_s, low, high, 0.0, 30.0, "quadratic", QUADRATIC_A)
    return {"id": "duration", "title": "整体时长", "status": "OK", "cap": 30, "raw": cap(points, 30.0),
            "detail": {"durationSeconds": round(duration_s, 3), "endGameMs": round(end_ms, 1),
                       "startGameMs": round(start_ms, 1), "baselineSeconds": round(float(seconds), 3),
                       "baselineSource": source, "ratio": round(duration_s / float(seconds), 3),
                       "rangeSeconds": [round(low, 1), round(high, 1)], "quadraticA": QUADRATIC_A,
                       "quadraticSpace": DURATION_QUADRATIC_SPACE}}


def compute(run):
    begin_ms = _begin_ms(run)
    entries = [tank_loss(run), tank_idle(run), duration_score(run, begin_ms)]
    notes = ["每一项独立封顶后求和；缺证据的项记 N/A 且不扣分。",
             "坦克空闲来自引擎 250 game-ms 的 tank_state 时间线：就绪 = 在维修湾范围内 + 满血 + idle，"
             "滞留 = 不在任何维修湾范围内 + idle；阈值分别 >3s / >8s。",
             "R 默认 5%；baselines.json 有该 task 的基线 w/T 时取 5 倍基线。",
             f"整体时长横坐标取 {DURATION_QUADRATIC_SPACE}；raw 秒在真实基线下会退化成阶跃。"]
    diagnostics = [{"severity": "info", "reason": item["reason"]} for item in entries if item["status"] != "OK"]
    return document(VERSION, BASELINE, entries, notes, diagnostics)
