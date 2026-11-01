"""Auditable metric calculations. Milliseconds/world coordinates; no combined score."""
from __future__ import annotations
import math

def quantile(values, p):
    if not values:
        return None
    values = sorted(values)
    pos = (len(values) - 1) * p
    lo = int(pos)
    return values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (pos - lo)

def stats(values):
    return {"n": len(values), "p50": quantile(values, .5), "p90": quantile(values, .9), "max": max(values) if values else None}

def na(reason):
    return {"status": "N/A", "reason": reason}

def outcome(run):
    if not run.snapshots:
        return {"status": "OK", "outcome": run.result.get("outcome", "unknown"), "state": na("missing snapshots")}
    final = run.result.get("units", run.snapshots[-1]["units"])
    final_by_id = {u["id"]: u for u in final}
    losses, shields, alive = {}, {}, {}
    for side, ids in (("self", run.self_ids), ("enemy", run.enemy_ids)):
        initial = sum(run.units[i]["maxHp"] for i in ids)
        initial_shield = sum(run.units[i].get("maxShield", 0) for i in ids)
        remaining = sum(final_by_id.get(i, {}).get("hp", 0) for i in ids if final_by_id.get(i, {}).get("alive"))
        # Keep a dead unit's leftover shield: it died with that shield, so counting 0 would
        # overstate shield loss (a mech often dies with ~50/800 still up).
        remaining_shield = sum(final_by_id.get(i, {}).get("shield", 0) for i in ids)
        alive[side] = sum(bool(final_by_id.get(i, {}).get("alive")) for i in ids)
        losses[side] = (initial - remaining) / initial if initial else 0
        shields[side] = (initial_shield - remaining_shield) / initial_shield if initial_shield else 0
    result = run.result.get("outcome") or ("draw" if not any(alive.values()) else "loss" if not alive["self"] else "win" if not alive["enemy"] else "unknown")
    return {"status": "OK", "outcome": result, "alive": alive, "hpLossFractions": losses,
            "delta": losses["enemy"] - losses["self"], "shieldLossFractions": shields,
            "shieldDelta": shields["enemy"] - shields["self"],
            "clearMs": run.end_ms if result == "win" else None, "survivalMs": run.end_ms if result == "loss" else None}

def cadence(run):
    times = [a["gameMs"] for a in run.actions]
    gaps = [b - a for a, b in zip(times, times[1:])]
    start = run.engage_ms
    combat = [t for t in times if start is not None and start <= t <= run.end_ms]
    limits = [start, *combat, run.end_ms] if start is not None else []
    return {"status": "OK", "intervalMs": stats(gaps), "commands": len(times),
            "combatCommandsPerMinute": len(combat) * 60000 / (run.end_ms - start) if start is not None and run.end_ms > start else None,
            "combatMaxGapMs": max((b - a for a, b in zip(limits, limits[1:])), default=None),
            "pauseWarning": "tick gaps reflect fixed turns, not thinking speed" if run.meta.get("track") == "pause" else None}

def response(run, when, ids, preempt=False):
    # PREEMPTED means the unit was already executing a player command when the fight started.
    # A briefing command that has already finished (order is idle again) is history, not an answer.
    if preempt and any(
            a["kind"] != "mode" and a["gameMs"] <= when
            and any(i in ids and run.alive_at_action(i, a) and _busy_at(run, when, i) for i in a["units"])
            for a in run.actions):
        return {"status": "PREEMPTED", "latencyMs": 0}
    for action in run.actions:
        if action["gameMs"] >= when and any(i in ids and run.alive_at_action(i, action) for i in action["units"]):
            lag = action["gameMs"] - when
            return {"status": "LATE" if lag > run.settings["lateMs"] else "ANSWERED", "latencyMs": lag,
                    "responseTick": action["tick"]}
    return {"status": "UNANSWERED", "latencyMs": None}

def _busy_at(run, when, unit_id):
    unit = run.unit_at(unit_id, when)
    return bool(unit and unit.get("alive") and unit.get("order") not in (None, "idle"))

def engage_lag(run):
    if run.engage_ms is None:
        return na("no observed engagement")
    return {"engageMs": run.engage_ms, **response(run, run.engage_ms, run.self_ids, True)}

def lowhp_lag(run):
    if not run.snapshots:
        return na("missing snapshots")
    rows = []
    previous = {i: u["maxHp"] for i, u in run.units.items() if i in run.self_ids}
    damage = [e for e in run.events if e["kind"] == "damage" and e["victim"] in run.self_ids]
    if damage:
        samples = [(e["gameMs"], e["victim"], e["hp"]) for e in damage]
        source = "damage events"
    else:
        samples = [(s["gameMs"], u["id"], u["hp"]) for s in run.snapshots for u in s["units"] if u["id"] in run.self_ids]
        source = "sampled crossings (up to 250ms uncertainty)"
    for ms, unit, hp in samples:
        for fraction in (.5, .25):
            threshold = run.units[unit]["maxHp"] * fraction
            if previous[unit] >= threshold > hp:
                rows.append({"unit": unit, "threshold": fraction, "gameMs": ms, **response(run, ms, {unit})})
        previous[unit] = hp
    return {"status": "OK", "crossingSource": source, "rows": rows}

def idle_in_combat(run):
    if not run.snapshots:
        return na("missing snapshots")
    durations, starts, rows = {}, {}, []
    for state, dt in run.intervals():
        ms = state["gameMs"]
        combat = run.engage_ms is not None and ms >= run.engage_ms and any(u.get("alive") and u["id"] in run.enemy_ids for u in state["units"])
        by_id = {u["id"]: u for u in state["units"]}
        for unit in run.self_ids:
            u = by_id.get(unit, {})
            idle = combat and u.get("alive") and u.get("order") == "idle" and (u.get("target") is None or ms - u.get("lastFireMs", -100000) >= 2000)
            if idle:
                starts.setdefault(unit, ms)
                durations[unit] = durations.get(unit, 0) + dt
            else:
                if durations.get(unit, 0) >= 2000:
                    rows.append({"unit": unit, "startMs": starts[unit], "durationMs": durations[unit]})
                durations[unit] = 0
                starts.pop(unit, None)
    for unit, duration in durations.items():
        if duration >= 2000:
            rows.append({"unit": unit, "startMs": starts[unit], "durationMs": duration})
    return {"status": "OK", "rows": rows, "totalUnitMs": sum(r["durationMs"] for r in rows)}

def stale_command(run):
    if not run.snapshots:
        return na("missing state evidence")
    rows = []
    for action in run.actions:
        dead = [i for i in action["units"] if not run.alive_at_action(i, action)]
        if dead:
            rows.append({"tick": action["tick"], "units": dead})
    return {"status": "OK", "count": len(rows), "unitReferences": sum(len(r["units"]) for r in rows), "rows": rows}

def fire_uptime(run):
    if not run.snapshots:
        return na("missing snapshots")
    totals = {i: {"eligibleMs": 0, "firingMs": 0, "interruptions": 0} for i in run.self_ids}
    was_firing = {}
    inferred = False
    for state, dt in run.intervals():
        enemy = [u for u in state["units"] if u["id"] in run.enemy_ids and u.get("alive")]
        for u in state["units"]:
            i = u["id"]
            if i not in totals:
                continue
            eligible = u.get("alive") and any(math.hypot(u["x"] - e["x"], u["y"] - e["y"]) <= run.units[i]["range"] for e in enemy)
            if not eligible:
                was_firing[i] = False
                continue
            weapons = u.get("weapons")
            if weapons is not None:
                firing = any(w.get("target") is not None and (w.get("cooldownFrames", 0) > 0 or w.get("warmupFrames", 0) > 0) for w in weapons)
            else:
                inferred = True
                firing = any(e["kind"] == "damage" and e.get("attacker") == i and 0 <= state["gameMs"] - e["gameMs"] <= run.settings["fireTauMs"] for e in run.events)
            totals[i]["eligibleMs"] += dt
            totals[i]["firingMs"] += dt if firing else 0
            if was_firing.get(i) and not firing:
                totals[i]["interruptions"] += 1
            was_firing[i] = firing
    return {"status": "OK", "method": "weapon state; damage fallback" if inferred else "weapon state", "tauMs": run.settings["fireTauMs"],
            "units": {str(i): {**v, "ratio": v["firingMs"] / v["eligibleMs"] if v["eligibleMs"] else None} for i, v in totals.items()}}

def free_fire(run):
    damage = [e for e in run.events if e["kind"] == "damage" and e.get("attacker") in run.self_ids]
    if not damage:
        return na("no friendly damage events")
    total = free = 0
    for e in damage:
        if "opponents" not in e or e.get("attackerX") is None:
            return na("damage-time positions of all surviving opponents are unavailable")
        amount = e["hpDamage"] + e["shieldDamage"]
        total += amount
        safe = all(math.hypot(e["attackerX"] - enemy["x"], e["attackerY"] - enemy["y"]) > enemy["range"] for enemy in e["opponents"])
        if safe:
            free += amount
    return {"status": "OK", "damage": total, "freeDamage": free, "ratio": free / total if total else None}

def agent_timing(run):
    if not run.has_gateway:
        return na("replay-only/human input has no gateway wall clock")
    requests = run.requests()
    counts = {k: sum(r["class"] == k for r in requests) for k in "ABC"}
    valid = [r for r in requests if r["class"] in "AB"]
    blocks = []
    for row in valid:
        if not blocks or blocks[-1][0]["class"] != row["class"]:
            blocks.append([])
        blocks[-1].append(row)
    cycles = []
    for i, block in enumerate(blocks[:-1]):
        if block[0]["class"] != "A":
            continue
        following = blocks[i + 1]
        sequence = block + following
        think = sum(max(0, b["wallRecvMs"] - a["wallReplyMs"]) for a, b in zip(sequence, sequence[1:]))
        cycles.append({"gameMs": following[-1]["gameMs"] - block[0]["gameMs"], "thinkingWallMs": think,
                       "firstA": block[0]["seq"], "lastB": following[-1]["seq"]})
    waits = [max(0, b["wallRecvMs"] - a["wallReplyMs"]) for a, b in zip(valid, valid[1:]) if a["class"] == "B" and b["class"] == "A"]
    stale, last_a = [], None
    for row in valid:
        if row["class"] == "A":
            last_a = row
        elif last_a:
            stale.append({"seq": row["seq"], "gameMs": row["gameMs"] - last_a["gameMs"],
                          "wallMs": max(0, row["wallRecvMs"] - last_a["wallReplyMs"])})
    weapons = [w for i in run.enemy_ids for w in run.units[i].get("weapons", [])]
    periods = [(w.get("reloadFrames", 0) + (0 if w.get("warmupNoReset") else w.get("warmupFrames", 0))) * run.meta.get("tickDurationMs", 16) for w in weapons]
    period = min((p for p in periods if p > 0), default=None)
    for cycle in cycles:
        cycle["enemyWeaponCycles"] = cycle["gameMs"] / period if period else None
    n = len(requests)
    return {"status": "OK", "counts": counts, "cycles": cycles, "thinkingWallMs": stats([c["thinkingWallMs"] for c in cycles]),
            "waitWallMs": stats(waits), "observationStaleness": stale, "wallUsedMs": run.result.get("wallMs"),
            "errorRatio": sum(r["status"] == "error" for r in requests) / n if n else None,
            "rejectedRatio": sum(r["status"] == "rejected" for r in requests) / n if n else None,
            "skippedDeadRatio": sum(bool(r.get("skippedDead")) for r in requests) / n if n else None,
            "briefing": {k: sum(r["class"] == k and r["phase"] == "briefing" for r in requests) for k in "ABC"},
            "enemyWeaponPeriodMs": period}

def turns(run):
    if run.meta.get("track") != "pause":
        return na("only defined for pause track")
    if not run.has_gateway:
        return na("turn boundaries/thinking times require gateway")
    requests = run.requests()
    advances = [r for r in requests if r["name"] in {"rwx-micro-1-begin", "rwx-micro-1-next"} and r["status"] in {"submitted", "partial"}]
    wall = [max(0, b["wallRecvMs"] - a["wallReplyMs"]) for a, b in zip(advances, advances[1:])]
    boundaries = [r for r in run.gateway if r["kind"] == "lifecycle" and r["event"] == "turn"]
    b_counts = {r["turn"]: sum(q["class"] == "B" and q["turn"] + 1 == r["turn"] for q in requests) for r in boundaries}
    combat = [r for r in boundaries if run.engage_ms is not None and r["gameMs"] >= run.engage_ms]
    def turn_for(ms):
        return next((r["turn"] for r in boundaries if r["gameMs"] >= ms), boundaries[-1]["turn"] if boundaries else None)
    engagement = engage_lag(run)
    if engagement.get("latencyMs") is not None and run.engage_ms is not None:
        engagement = {**engagement, "turnLag": turn_for(run.engage_ms + engagement["latencyMs"]) - turn_for(run.engage_ms)}
    lowhp = lowhp_lag(run)
    for row in lowhp.get("rows", []):
        if row.get("latencyMs") is not None:
            row["turnLag"] = turn_for(row["gameMs"] + row["latencyMs"]) - turn_for(row["gameMs"])
    return {"status": "OK", "turns": len(boundaries), "thinkingWallMs": stats(wall), "actionsPerTurn": b_counts,
            "emptyCombatTurnRatio": sum(b_counts[r["turn"]] == 0 for r in combat) / len(combat) if combat else None,
            "engage": engagement, "lowhp": lowhp}
