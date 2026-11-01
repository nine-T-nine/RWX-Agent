"""Per-turret HP timeline, destruction order and kill rate (T1)."""
ID, TIER, SOURCES = "turret_progress", "T1", ["engine/turret_damage", "engine/turret_destroyed", "meta"]


def compute(run):
    rows = []
    for turret_id, spec in sorted(run.turrets.items()):
        hits = run.turret_hits(turret_id)
        destroyed = next((row for row in run.destroyed() if row.get("turretId") == turret_id), None)
        rows.append({"turretId": turret_id, "type": spec.get("type"), "ring": spec.get("ring"),
                     "tileX": spec.get("tileX"), "tileY": spec.get("tileY"), "attackRange": spec.get("attackRange"),
                     "hits": len(hits), "damageTaken": round(sum(-(row.get("delta") or 0) for row in hits), 1),
                     "firstHitGameMs": hits[0].get("gameMs") if hits else None,
                     "destroyedGameMs": destroyed.get("gameMs") if destroyed else None,
                     "attackers": sorted({row.get("attacker") for row in hits if row.get("attacker") is not None})})
    destroyed_rows = run.destroyed()
    gaps = [b.get("gameMs") - a.get("gameMs") for a, b in zip(destroyed_rows, destroyed_rows[1:])]
    return {"status": "OK", "turrets": rows,
            "totals": {"turrets": len(run.turrets), "destroyed": len(destroyed_rows),
                       "damaged": sum(1 for row in rows if row["hits"] > 0)},
            "destroyOrder": [row.get("turretId") for row in destroyed_rows],
            "destroyGapsMs": gaps,
            "unknownAttacker": sum(1 for row in run.turret_damage if row.get("attacker") is None)}
