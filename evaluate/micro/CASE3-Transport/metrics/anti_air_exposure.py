"""Anti-air exposure derived from recorded damage, not from geometry alone (T2)."""
import math

ID, TIER, SOURCES = "anti_air_exposure", "T2", ["engine/transport", "meta"]

# The turret weapon range in world units, read from the loaded unit definition (assets/units/turrets/turret_antiair.ini).
TURRET_RANGE = 250.0


def compute(run):
    if not run.turrets:
        return {"status": "N/A", "reason": "task has no anti-air turrets"}
    damages = run.damages()
    if not damages:
        return {"status": "OK", "hits": 0, "hpLost": 0, "turrets": len(run.turrets),
                "inferredRange": TURRET_RANGE,
                "note": "no transport was hit; no exposure can be claimed from geometry alone"}
    rows = []
    for row in damages:
        nearest, distance = None, None
        for turret in run.turrets:
            span = math.dist((row.get("x", 0), row.get("y", 0)), (turret["x"], turret["y"])) if "x" in row else None
            if span is not None and (distance is None or span < distance):
                nearest, distance = turret, span
        rows.append({"transportId": row.get("transportId"), "gameMs": row.get("gameMs"),
                     "delta": row.get("delta"), "nearestTurret": nearest.get("id") if nearest else None,
                     "nearestDistance": round(distance, 1) if distance is not None else None})
    return {"status": "OK", "turrets": len(run.turrets), "inferredRange": TURRET_RANGE,
            "hits": len(damages), "hpLost": round(sum(max(0.0, -(r.get("delta") or 0.0)) for r in damages), 1),
            "rows": rows,
            "note": "turret positions come from the private meta and are only used offline; "
                    "damage events carry no attacker id, so attribution stays positional"}
