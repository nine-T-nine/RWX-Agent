ID, TIER, SOURCES = "outcome", "T1", ["engine", "meta"]

def compute(run):
    if not run.result:
        return {"status": "N/A", "reason": "missing_result"}
    keys = ("outcome", "endReason", "initialIncomePerSecond", "finalIncomePerSecond", "targetIncomePerSecond",
            "targetReachedGameMs", "targetReachedWallMs", "gameElapsedMs", "wallElapsedMs", "remainingResources", "unusedCredits")
    return {"status": "OK", **{k: run.result.get(k) for k in keys}}
