"""Retreat responsiveness and repair throughput (T2)."""
from _shared import stats

ID, TIER, SOURCES = "repair_effectiveness", "T2", ["engine/repair_sessions", "engine/unit_damage", "gateway"]

RETREAT_ORDERS = ("move",)


def compute(run):
    # Time from the last hit on a tank to the next move order that tank received.
    responses = []
    for tank_id in run.tanks:
        hits = [row for row in run.tank_hits(tank_id) if row.get("gameMs") is not None]
        moves = [row for row in run.orders()
                 if row.get("operation") in RETREAT_ORDERS and tank_id in (row.get("units") or [])
                 and row.get("gameMs") is not None]
        for hit in hits:
            after = next((move for move in moves if move["gameMs"] >= hit["gameMs"]), None)
            if after is not None:
                responses.append(after["gameMs"] - hit["gameMs"])
    sessions = run.repairs
    healed = sum(row.get("healedHp") or 0 for row in sessions)
    damage = sum(max(0.0, -(row.get("delta") or 0)) for row in run.hits())
    rejoin = []
    for session in sessions:
        tank_id = session.get("unitId")
        end = session.get("endGameMs")
        after = next((row for row in run.orders()
                      if tank_id in (row.get("units") or [])
                      and row.get("operation") in ("attack", "attack-move")
                      and (row.get("gameMs") or 0) >= (end or 0)), None)
        if after is not None:
            rejoin.append(after["gameMs"] - end)
    return {"status": "OK",
            "hitToRetreat": stats(responses),
            "repairEndToReengage": stats(rejoin),
            "healedTotal": round(healed, 1), "damageTakenTotal": round(damage, 1),
            "repairCoverage": round(healed / damage, 3) if damage else None,
            "sessions": len(sessions),
            "note": "hit-to-retreat is measured to the next move order on the same tank, not to arrival"}
