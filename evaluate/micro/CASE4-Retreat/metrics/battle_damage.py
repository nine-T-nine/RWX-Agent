"""Tank damage taken, sources and survival (T1)."""
from _shared import stats

ID, TIER, SOURCES = "battle_damage", "T1", ["engine/unit_damage", "result", "meta"]


def compute(run):
    rows = []
    for tank_id, spec in sorted(run.tanks.items()):
        hits = run.tank_hits(tank_id)
        losses = [max(0.0, -(row.get("delta") or 0.0)) for row in hits]
        final = next((row for row in (run.result.get("tanks") or []) if row.get("unitId") == tank_id), None)
        rows.append({"unitId": tank_id, "hits": len(hits), "hpLost": round(sum(losses), 1),
                     "shieldLost": round(sum(max(0.0, (row.get("shieldBefore") or 0) - (row.get("shieldAfter") or 0))
                                             for row in hits), 1),
                     "firstHitGameMs": hits[0].get("gameMs") if hits else None,
                     "lastHitGameMs": hits[-1].get("gameMs") if hits else None,
                     "alive": final.get("alive") if final else None,
                     "finalHp": final.get("hp") if final else None,
                     "attackers": sorted({row.get("attackerType") for row in hits if row.get("attackerType")}),
                     "unknownSourceHits": sum(1 for row in hits if row.get("attacker") is None)})
    hits = run.hits()
    return {"status": "OK", "tanks": rows,
            "totals": {"hits": len(hits), "hpLost": round(sum(max(0.0, -(r.get("delta") or 0)) for r in hits), 1),
                       "tanksLost": sum(1 for row in rows if row["alive"] is False)},
            "hitSpacing": stats([b.get("gameMs") - a.get("gameMs") for a, b in zip(hits, hits[1:])]),
            "note": "attacker ids come from the engine damage hook; a missing attacker is reported as unknown"}
