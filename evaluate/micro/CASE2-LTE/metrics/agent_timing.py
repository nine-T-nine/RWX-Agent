from collections import Counter
from _shared import stats, timing
ID, TIER, SOURCES = "agent_timing", "T2", ["gateway"]

def compute(run):
    n = len(run.gateway)
    return {"status": "OK", "counts": dict(Counter(q["class"] for q in run.gateway)),
            "statuses": dict(Counter(q["status"] for q in run.gateway)),
            "processingWallMs": stats([{"requestId": q["seq"], "ms": q["wallReplyMs"]-q["wallRecvMs"]} for q in run.gateway]),
            "errorRatio": sum(q["status"] == "invalid_request" for q in run.gateway)/n if n else None,
            "rejectedRatio": sum(q["status"] == "rejected" for q in run.gateway)/n if n else None,
            "intervals": timing(run)}
