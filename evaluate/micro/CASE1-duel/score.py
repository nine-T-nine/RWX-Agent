"""CASE1-duel deduction score — the executable form of `score_design.md` (case1-score-v1).

Three separate deductions, each capped, then `100 - sum`:

1. **卡死行为/无关行为 (cap 30)** — every irrelevant behaviour by count, with the draft's
   severity tiers `7.5 / 4.5 / 1.8` mapped to the three evidence classes this case produces
   (rejected request, failed actionable request, action with no live unit to act on).
2. **机枪机甲承受伤害 (cap 60)** — `S = K*100 + A1/B1*20 + A2/B2*40`, where `K` is the share of
   mechs destroyed, `A1/A2` the HP/shield damage the mechs took and `B1/B2` their original
   maximum pools. The draft's "(不包括死亡)" is read literally: a mech that died is already
   covered by `K`, so it contributes neither damage nor pool size; set `DAMAGE_SCOPE = "all"`
   to include dead mechs in both sums instead.
3. **集体空闲 (cap 40)** — every window in which *all* mechs are idle, longer than 5 s, costs
   `25*f(seconds)` with `f:(5,20)` natural exponential `{0,1}`; the intervals are summed first
   and the item is capped once.

Every number is derived from one run directory; missing evidence gives `status="N/A"` and 0
points instead of a guess.
"""
from __future__ import annotations
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE.parent))
from common.scoring import document, piecewise, round1  # noqa: E402

VERSION = "case1-score-v1"
BASELINE = 100
MECH_TYPE = "mechMinigun"
WEIGHTS = {"violation": 7.5, "failed": 4.5, "noop": 1.8}
# `survivors` = the draft's literal "(不包括死亡)"; `all` keeps dead mechs in A1/A2 and B1/B2.
DAMAGE_SCOPE = "survivors"
DAMAGE_CAP = 60.0
IDLE_POINTS = 25.0
IDLE_MIN_SECONDS = 5.0
IDLE_SEGMENTS = ((5.0, 20.0, 0.0, 1.0, "exp"),)
# A mech ordered to fire but that has not fired for this long counts as idle (same tau as
# metrics/IDLE_IN_COMBAT). An `order="idle"` unit usually has no target at all.
FIRE_TAU_MS = 2000


def _mechs(run):
    mechs = [unit for unit in run.units.values()
             if unit.get("team") == run.self_team and unit.get("type") == MECH_TYPE]
    return sorted(mechs, key=lambda unit: unit["id"])


def _final_alive(run):
    """id -> alive from the authoritative final state, then the last snapshot."""
    final = run.result.get("units")
    if final:
        return {row["id"]: bool(row.get("alive")) for row in final}
    if run.snapshots:
        return {row["id"]: bool(row.get("alive")) for row in run.snapshots[-1]["units"]}
    return None


def _violations(run):
    path = Path(run.directory) / "violations.jsonl"
    if not path.is_file():
        return []
    with path.open(encoding="utf-8-sig") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def _live_request(row):
    """Top-level request made before the match ended; batch children fold into their parent."""
    return row.get("parent") is None and row.get("phase") != "ended"


def irrelevant(run):
    violations = _violations(run)
    requests = [row for row in run.gateway if row.get("kind") == "request"]
    rejected = [row for row in requests if row.get("status") == "rejected"]
    # A request cannot both be a recorded violation and a plain rejection: dedupe by requestId.
    seen = {row.get("requestId") for row in violations if row.get("requestId") is not None}
    rejected = [row for row in rejected if row.get("seq") not in seen]
    failed = [row for row in requests
              if row.get("class") == "B" and row.get("status") in {"invalid_request", "error"}
              and _live_request(row) and "match has ended" not in str(row.get("error") or "")]
    stale = [action for action in run.actions
             if any(not run.alive_at_action(unit, action) for unit in action["units"])]
    # A submitted command whose every unit was already dead acted on nobody. Units skipped by
    # the gateway never reach the replay, so this does not double count `stale`.
    noop = [row for row in requests
            if row.get("class") == "B" and row.get("status") in {"submitted", "partial"}
            and _live_request(row) and not (row.get("units") or []) and row.get("skippedDead")]
    points = (len(violations) + len(rejected)) * WEIGHTS["violation"] \
        + len(failed) * WEIGHTS["failed"] + (len(stale) + len(noop)) * WEIGHTS["noop"]
    return {"id": "irrelevant", "title": "卡死行为/无关行为", "status": "OK", "cap": 30, "raw": points,
            "detail": {"weights": WEIGHTS,
                       "counts": {"violation": len(violations), "rejected": len(rejected),
                                  "failed": len(failed), "staleCommand": len(stale), "noEffect": len(noop)},
                       "violations": [{"violationType": row.get("violationType"), "reason": row.get("reason")}
                                      for row in violations],
                       "rejected": [{"seq": row.get("seq"), "name": row.get("name")} for row in rejected],
                       "failed": [{"seq": row.get("seq"), "name": row.get("name"), "status": row.get("status"),
                                   "error": row.get("error")} for row in failed],
                       "staleCommands": [{"tick": action["tick"], "units": action["units"]} for action in stale],
                       "noEffect": [{"seq": row.get("seq"), "name": row.get("name"),
                                     "skippedDead": row.get("skippedDead")} for row in noop],
                       "formula": "sum(count * weight); weights 7.5 violation/rejected, 4.5 failed, 1.8 no-effect"}}


def mech_damage(run):
    mechs = _mechs(run)
    if not mechs:
        return {"id": "mech_damage", "title": "机枪机甲承受伤害", "status": "N/A",
                "reason": f"no {MECH_TYPE} units in meta", "cap": 60, "raw": 0.0, "detail": {}}
    if not run.snapshots and not run.events:
        return {"id": "mech_damage", "title": "机枪机甲承受伤害", "status": "N/A",
                "reason": "missing snapshots/events evidence", "cap": 60, "raw": 0.0, "detail": {}}
    final = _final_alive(run)
    if final is None:
        return {"id": "mech_damage", "title": "机枪机甲承受伤害", "status": "N/A",
                "reason": "missing final unit state", "cap": 60, "raw": 0.0, "detail": {}}
    survivors = [unit for unit in mechs if final.get(unit["id"], True)]
    included = survivors if DAMAGE_SCOPE == "survivors" else mechs
    included_ids = {unit["id"] for unit in included}
    max_hp = sum(float(unit.get("maxHp") or 0.0) for unit in included)
    max_shield = sum(float(unit.get("maxShield") or 0.0) for unit in included)
    rows = [event for event in run.events
            if event.get("kind") == "damage" and event.get("victim") in included_ids]
    missing = sum(1 for event in rows if event.get("hpDamage") is None and event.get("shieldDamage") is None)
    hp_damage = sum(float(event.get("hpDamage") or 0.0) for event in rows)
    shield_damage = sum(float(event.get("shieldDamage") or 0.0) for event in rows)
    destroyed = [unit["id"] for unit in mechs if not final.get(unit["id"], True)]
    share = len(destroyed) / len(mechs)
    hp_points = hp_damage / max_hp * 20.0 if max_hp else 0.0
    shield_points = shield_damage / max_shield * 40.0 if max_shield else 0.0
    points = share * 100.0 + hp_points + shield_points
    per_unit = []
    for unit in mechs:
        own = [event for event in run.events
               if event.get("kind") == "damage" and event.get("victim") == unit["id"]]
        per_unit.append({"unitId": unit["id"], "alias": unit.get("alias"), "alive": final.get(unit["id"], True),
                         "hpDamage": round1(sum(float(event.get("hpDamage") or 0.0) for event in own)),
                         "shieldDamage": round1(sum(float(event.get("shieldDamage") or 0.0) for event in own)),
                         "counted": unit["id"] in included_ids})
    return {"id": "mech_damage", "title": "机枪机甲承受伤害", "status": "OK", "cap": 60, "raw": points,
            "detail": {"scope": DAMAGE_SCOPE, "mechs": len(mechs), "destroyed": len(destroyed),
                       "K": round(share, 3), "hpDamageA1": round1(hp_damage), "shieldDamageA2": round1(shield_damage),
                       "maxHpB1": round1(max_hp), "maxShieldB2": round1(max_shield),
                       "hpRatio": round(hp_damage / max_hp, 3) if max_hp else None,
                       "shieldRatio": round(shield_damage / max_shield, 3) if max_shield else None,
                       "damageEvents": len(rows), "rowsWithoutAmounts": missing,
                       "units": per_unit,
                       "formula": "K*100 + A1/B1*20 + A2/B2*40, K = destroyed/mechs"}}


def _idle(unit, ms):
    if unit.get("order") != "idle":
        return False
    target = unit.get("target")
    last = unit.get("lastFireMs")
    return target is None or last is None or ms - last >= FIRE_TAU_MS


def idle_windows(run, mech_ids):
    """Maximal runs of "every alive mech idle", as (startMs, endMs) pairs from the snapshots."""
    windows, start = [], None
    states = run.snapshots
    for index, state in enumerate(states):
        following = states[index + 1]["gameMs"] if index + 1 < len(states) else run.end_ms
        if following <= state["gameMs"]:
            continue
        alive = [unit for unit in state["units"] if unit.get("id") in mech_ids and unit.get("alive")]
        idle = bool(alive) and all(_idle(unit, state["gameMs"]) for unit in alive)
        if idle and start is None:
            start = state["gameMs"]
        elif not idle and start is not None:
            windows.append((start, state["gameMs"]))
            start = None
    if start is not None:
        windows.append((start, run.end_ms))
    return [(begin, end) for begin, end in windows if end > begin]


def collective_idle(run):
    if not run.snapshots:
        return {"id": "collective_idle", "title": "集体空闲", "status": "N/A",
                "reason": "missing snapshots", "cap": 40, "raw": 0.0, "detail": {"windows": []}}
    mechs = _mechs(run)
    if not mechs:
        return {"id": "collective_idle", "title": "集体空闲", "status": "N/A",
                "reason": f"no {MECH_TYPE} units in meta", "cap": 40, "raw": 0.0, "detail": {"windows": []}}
    mech_ids = {unit["id"] for unit in mechs}
    rows, points = [], 0.0
    for begin, end in idle_windows(run, mech_ids):
        seconds = (end - begin) / 1000.0
        severity = piecewise(seconds, IDLE_SEGMENTS)
        rows.append({"startMs": begin, "endMs": end, "seconds": round(seconds, 3),
                     "severity": round(severity, 4), "points": round1(IDLE_POINTS * severity),
                     "inCombat": run.engage_ms is not None and end >= run.engage_ms})
        points += IDLE_POINTS * severity
    return {"id": "collective_idle", "title": "集体空闲", "status": "OK", "cap": 40, "raw": points,
            "detail": {"windows": rows, "perWindowPoints": IDLE_POINTS, "minimumSeconds": IDLE_MIN_SECONDS,
                       "fireTauMs": FIRE_TAU_MS, "snapshotMs": 250,
                       "formula": "sum(25*f(seconds)); f<5s=0, (5,20) exp{0,1}, >=20s=1"}}


def compute(run):
    entries = [irrelevant(run), mech_damage(run), collective_idle(run)]
    notes = ["每一项独立封顶后求和；缺证据的项记 N/A 且不扣分。",
             "无关行为按 7.5/4.5/1.8 三档：被拒请求、失败的 B 请求、无存活单位可作用/引用已死单位的动作。",
             "承受伤害默认只统计终局存活机甲（草稿的“不包括死亡”），死亡由 K 单独计分；"
             "把 DAMAGE_SCOPE 改成 all 可改为把死亡机甲也计入 A/B。",
             "集体空闲来自 250 game-ms 快照：所有存活机甲 order=idle（无目标或 2s 内未开火）同时成立。"]
    diagnostics = [{"severity": "info", "reason": item["reason"]} for item in entries if item["status"] != "OK"]
    return document(VERSION, BASELINE, entries, notes, diagnostics)
