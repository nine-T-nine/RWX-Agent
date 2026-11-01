"""Our force state right after each turret kill (T1)."""
ID, TIER, SOURCES = "post_kill_snapshots", "T1", ["engine/turret_destroyed"]


def compute(run):
    rows = []
    for row in run.destroyed():
        tanks = row.get("tanks") or []
        rows.append({"turretId": row.get("turretId"), "turretType": row.get("turretType"),
                     "gameMs": row.get("gameMs"), "tick": row.get("tick"),
                     "tanksAlive": sum(1 for tank in tanks if tank.get("alive")),
                     "totalHp": round(sum(tank.get("hp") or 0 for tank in tanks if tank.get("alive")), 1),
                     "totalShield": round(sum(tank.get("shield") or 0 for tank in tanks if tank.get("alive")), 1),
                     "tanks": tanks})
    return {"status": "OK", "kills": len(rows), "rows": rows}
