"""Task outcome and end state (T1)."""
ID, TIER, SOURCES = "outcome", "T1", ["result", "meta", "goal"]

KEYS = ("outcome", "endReason", "task", "seed", "gameMs", "wallMs", "goalReachedGameMs", "goalReachedWallMs",
        "transportAliveCount", "transportCount", "credits")


def compute(run):
    if not run.result:
        return {"status": "N/A", "reason": "missing_result"}
    reached = run.reached() or {}
    return {"status": "OK", **{key: run.result.get(key) for key in KEYS},
            "requirements": run.requirements,
            "delivered": run.delivered(),
            "produced": run.produced(),
            "deliveryRate": {need["type"]: (run.delivered().get(need["type"], 0) / need["required"]
                                            if need.get("required") else None)
                             for need in run.requirements},
            "firstReachedTick": reached.get("tick"),
            "goalEvents": len(run.goal)}
