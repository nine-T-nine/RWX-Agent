ID, TIER, SOURCES = "income_anchors", "T1", ["engine"]

def compute(run):
    rows = [dict(e, index=i) for i, e in enumerate(x for x in run.events if x["kind"] == "income")]
    begin = run.result.get("beginWallMs")
    for n, e in enumerate(rows):
        e["sinceBeginWallMs"] = e["wallMs"] - begin if begin is not None and e["wallMs"] >= begin else None
        e["sincePreviousGameMs"] = e["gameMs"] - rows[n-1]["gameMs"] if n else None
        e["sincePreviousWallMs"] = e["wallMs"] - rows[n-1]["wallMs"] if n else None
    return {"status": "OK" if rows else "N/A", "count": len(rows), "rows": rows,
            "targetAnchor": next((e["index"] for e in rows if e["incomePerSecond"] >= run.meta["targetIncomePerSecond"]), None)}
