"""Agent request timing: A->B, B->next A, decision cycles (T2)."""
from _shared import stats

ID, TIER, SOURCES = "ab_timing", "T2", ["gateway"]


def compute(run):
    requests = run.submitted()
    a = [row for row in requests if row.get("class") == "A"]
    b = [row for row in requests if row.get("class") == "B"]

    def sample(first, second, reply=False):
        return second.get("wallRecvMs") - first.get("wallReplyMs" if reply else "wallRecvMs")

    ab, ba, cycles = [], [], []
    for row in b:
        before = next((item for item in reversed(a) if item.get("wallReplyMs", 0) <= row.get("wallRecvMs", 0)), None)
        after = next((item for item in a if item.get("wallRecvMs", 0) >= row.get("wallReplyMs", 0)), None)
        if before:
            ab.append(sample(before, row, True))
        if after:
            ba.append(sample(row, after, True))
        if before and after:
            cycles.append(after.get("wallRecvMs") - before.get("wallRecvMs"))
    errors = [row for row in run.requests() if row.get("status") not in ("submitted", "rejected")]
    return {"status": "OK", "aToB": stats(ab), "bToNextA": stats(ba),
            "bToB": stats([sample(x, y) for x, y in zip(b, b[1:])]),
            "aToA": stats([sample(x, y) for x, y in zip(a, a[1:])]),
            "decisionCycles": stats(cycles),
            "counts": {"A": len(a), "B": len(b), "C": len([r for r in requests if r.get("class") == "C"]),
                       "errors": len(errors), "rejected": len([r for r in run.requests() if r.get("status") == "rejected"])},
            "note": "A->B includes model inference, runtime and tool scheduling; it is not internal ETA"}
