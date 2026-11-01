"""Formal repair sessions (strictly longer than five game seconds) and their effect (T1)."""
from _shared import stats

ID, TIER, SOURCES = "repair_sessions", "T1", ["engine/repair_sessions", "meta"]


def compute(run):
    rows = []
    for row in run.repairs:
        duration = row.get("durationMs") or 0
        healed = row.get("healedHp") or 0
        rows.append({"unitId": row.get("unitId"), "repairBayIds": row.get("repairBayIds"),
                     "startGameMs": row.get("startGameMs"), "endGameMs": row.get("endGameMs"),
                     "durationMs": duration, "startHp": row.get("startHp"), "endHp": row.get("endHp"),
                     "maxHp": row.get("maxHp"), "healedHp": healed,
                     "healPerSecond": round(healed / (duration / 1000), 2) if duration else None,
                     "reason": row.get("reason")})
    return {"status": "OK", "sessions": rows, "count": len(rows),
            "durations": stats([row["durationMs"] for row in rows]),
            "healed": stats([row["healedHp"] for row in rows]),
            "totalHealed": round(sum(row["healedHp"] for row in rows), 1),
            "note": "a session is only written when it lasted more than 5000 game ms and actually healed"}
