from _shared import lifecycle, match_native, stats
ID, TIER, SOURCES = "instruction_lifecycle", "T1", ["gateway", "engine", "replay"]

def compute(run):
    rows = lifecycle(run)
    anchors = [e for e in run.events if e["kind"] == "income"]
    for row in rows:
        end = row["completed"]
        row["incomeDelta"] = sum(e["deltaIncome"] for e in anchors if end and e["tick"] == end["tick"] and end.get("buildingId") in e["buildingIds"]) if end else None
        row["incomeAttribution"] = "combined_tick" if end and any(e["tick"] == end["tick"] and len(e["buildingIds"])>1 for e in anchors) else "single_or_unknown"
    return {"status": "OK", "rows": rows, "mapping": match_native(run),
            "issueToCompleteWallMs": stats([{"requestId": r["requestId"], "ms": r["issueToCompleteWallMs"]} for r in rows if r["completed"]])}
