from __future__ import annotations
from collections import Counter
from common.measurements import quantile

TERMINALS = {"completed", "failed", "cancelled", "superseded", "timeout", "rejected"}


def stats(samples):
    values = [r["ms"] for r in samples]
    q1, q3 = quantile(values, .25), quantile(values, .75)
    cutoff = q3 + 1.5 * (q3 - q1) if values else None
    return {"n": len(values), "mean": sum(values) / len(values) if values else None,
            "median": quantile(values, .5), "p50": quantile(values, .5), "p90": quantile(values, .9),
            "max": max(values) if values else None, "outliers": [r for r in samples if r["ms"] > cutoff], "samples": samples}


def match_native(run):
    records = [e for e in run.events if e["kind"] == "native"]
    requests = {r["seq"]: r for r in run.gateway}
    rows = []
    for e in records:
        def matches(a):
            if a["tick"] != e["tick"] or a["units"] != [e["actor"]] or a.get("team") != run.meta.get("controlledTeam", 0):
                return False
            if e["operation"] == "upgrade":
                return a.get("action") == e["actionId"] and a.get("type") is None
            node = next(n for n in run.meta["resourceNodes"] if n["resourceId"] == e["resourceId"])
            # Custom unit types are prefixed by the Replay reader ("custom:extractorT1").
            build = (a.get("build") or "").removeprefix("custom:")
            return a.get("type") == "build" and build == e["buildType"] and a.get("build_count") == 1 and abs(a["x"] - node["x"]) < .01 and abs(a["y"] - node["y"]) < .01
        candidates = [n for n, a in enumerate(run.actions) if matches(a)]
        # Index is assigned at the actual ReplayWriter insertion. Validate its signature too.
        index = e.get("nativeIndex")
        status = "matched" if index in candidates else "ambiguous" if len(candidates) > 1 else "unmatched"
        if e["requestId"] not in requests or requests[e["requestId"]]["status"] != "submitted":
            status = "unmatched"
        rows.append({"requestId": e["requestId"], "status": status, "nativeIndex": index if status == "matched" else None,
                     "tick": e["tick"], "candidates": candidates})
    counts = Counter(r["nativeIndex"] for r in rows if r["status"] == "matched")
    for r in rows:
        if r["status"] == "matched" and counts[r["nativeIndex"]] != 1:
            r["status"] = "ambiguous"
    matched = {r["nativeIndex"] for r in rows if r["status"] == "matched"}
    return {"rows": rows, "unexpectedNativeIndices": [n for n in range(len(run.actions)) if n not in matched]}


def lifecycle(run):
    mapping = {r["requestId"]: r for r in match_native(run)["rows"]}
    rows = []
    for q in run.gateway:
        if q["class"] != "B":
            continue
        events = [e for e in run.events if e["kind"] == "instruction" and e["requestId"] == q["seq"]]
        end = next((e for e in events if e["event"] in TERMINALS), None)
        start = next((e for e in events if e["event"] == "order_started"), None)
        phases = {}
        for i, e in enumerate(events):
            if e["event"] in {"moving", "pathing", "arrived", "warming_up", "constructing", "queued", "upgrading", "waiting_for_credits"}:
                after = next((x for x in events[i+1:] if x["event"] == e["event"] + "_end"), None)
                if after:
                    d = phases.setdefault(e["event"], {"gameMs": 0, "wallMs": 0})
                    for k in d:
                        d[k] += after[k] - e[k]
        rows.append({"requestId": q["seq"], "issued": {"gameMs": q["gameMs"], "tick": q["tick"], "wallMs": q["wallRecvMs"]},
                     "events": events, "result": end["event"] if end else "unresolved", "phases": phases,
                     "mapping": mapping.get(q["seq"], {"status": "not_submitted" if q["status"] != "submitted" else "unmatched"}),
                     "issueToCompleteGameMs": end["gameMs"] - q["gameMs"] if end else None,
                     "issueToCompleteWallMs": end["wallMs"] - q["wallRecvMs"] if end else None,
                     "executionGameMs": end["gameMs"] - start["gameMs"] if start and end else None,
                     "executionWallMs": end["wallMs"] - start["wallMs"] if start and end else None,
                     "actualCost": end.get("actualCost") if end else None,
                     "completed": end if end and end["event"] == "completed" else None})
    return rows


def accepted(run):
    return [q for q in run.gateway if q["class"] == "A" and q["status"] == "submitted" or q["class"] == "B" and q["status"] == "submitted"]


def timing(run):
    rows = accepted(run)
    a = [r for r in rows if r["class"] == "A"]
    b = [r for r in rows if r["class"] == "B"]
    def sample(x, y, reply=False):
        return {"from": x["seq"], "to": y["seq"], "ms": y["wallRecvMs"] - x["wallReplyMs" if reply else "wallRecvMs"]}
    ab, ba, cycles = [], [], []
    for q in b:
        before = next((r for r in reversed(a) if r["wallReplyMs"] <= q["wallRecvMs"]), None)
        after = next((r for r in a if r["wallRecvMs"] >= q["wallReplyMs"]), None)
        if before:
            ab.append(sample(before, q, True))
        if after:
            ba.append(sample(q, after, True))
        if before and after:
            cycles.append({"firstA": before["seq"], "B": q["seq"], "nextA": after["seq"], "ms": after["wallRecvMs"] - before["wallRecvMs"]})
    return {"status": "OK", "aToB": stats(ab), "bToNextA": stats(ba),
            "bToB": stats([sample(x,y) for x,y in zip(b,b[1:])]), "aToA": stats([sample(x,y) for x,y in zip(a,a[1:])]),
            "decisionCycles": stats(cycles), "note": "A-to-B includes model, runtime and tool scheduling; it is not internal ETA."}
