"""Task outcome and end state (T1)."""
ID, TIER, SOURCES = "outcome", "T1", ["result", "meta", "engine/turret_destroyed"]

KEYS = ("outcome", "endReason", "task", "seed", "gameMs", "wallMs", "goalReached", "goalReachedGameMs",
        "goalReachedWallMs", "turretsAlive", "turretsTotal", "tanksAlive", "tanksTotal")


def compute(run):
    if not run.result:
        return {"status": "N/A", "reason": "missing_result"}
    reached = run.reached() or {}
    return {"status": "OK", **{key: run.result.get(key) for key in KEYS},
            "fogMode": run.meta.get("fogMode"),
            "firstReachedTick": reached.get("tick"),
            "turretsDestroyed": len(run.destroyed())}
