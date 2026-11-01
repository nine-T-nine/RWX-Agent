from collections import Counter
from _shared import match_native
ID, TIER, SOURCES = "violations", "T1", ["violations", "replay", "engine"]

def compute(run):
    mapping = match_native(run)
    unexpected = mapping["unexpectedNativeIndices"]
    invalid = [r for r in mapping["rows"] if r["status"] != "matched"]
    if run.has_replay and (unexpected or invalid):
        run.diagnostics.append({"severity": "severe", "reason": "native_authority_mismatch", "unexpectedNativeIndices": unexpected, "mapping": invalid})
    return {"status": "OK" if run.has_replay else "N/A", "count": len(run.violations),
            "byType": dict(Counter(v["violationType"] for v in run.violations)), "rows": run.violations,
            "allRejectedBeforeExecution": all(v["rejected"] for v in run.violations),
            "firstViolationWallMs": run.violations[0]["wallMs"] if run.violations else None,
            "unexpectedNativeCount": len(unexpected), "unmatchedMappings": len(invalid),
            "violationEnteredReplay": bool(unexpected) if run.has_replay else None}
