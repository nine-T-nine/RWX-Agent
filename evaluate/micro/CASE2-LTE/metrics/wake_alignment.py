from _shared import lifecycle, stats
ID, TIER, SOURCES = "wake_alignment", "T2", ["gateway", "engine"]

def compute(run):
    observations = [q for q in run.gateway if q["class"] == "A" and q["status"] == "submitted"]
    requests = {q["seq"]: q for q in run.gateway}
    rows = []
    for instruction in lifecycle(run):
        end = instruction["completed"]
        if not end:
            continue
        q = requests[instruction["requestId"]]
        after = [a for a in observations if a["wallRecvMs"] >= q["wallReplyMs"]]
        first = after[0] if after else None
        wake = next((a for a in after if a["wallRecvMs"] >= end["wallMs"]), None)
        rows.append({"requestId": q["seq"], "firstA": first["seq"] if first else None,
                     "bToNextAMs": first["wallRecvMs"]-q["wallReplyMs"] if first else None,
                     "completeFromReplyMs": end["wallMs"]-q["wallReplyMs"],
                     "wakeBeforeCompleteMs": end["wallMs"]-first["wallRecvMs"] if first and first["wallRecvMs"] < end["wallMs"] else None,
                     "wakeAfterCompleteMs": wake["wallRecvMs"]-end["wallMs"] if wake else None,
                     "pollsBeforeComplete": sum(a["wallRecvMs"] < end["wallMs"] for a in after)})
    return {"status": "OK", "rows": rows,
            "wakeAfterCompleteMs": stats([{"requestId": r["requestId"], "ms": r["wakeAfterCompleteMs"]} for r in rows if r["wakeAfterCompleteMs"] is not None]),
            "wakeBeforeCompleteMs": stats([{"requestId": r["requestId"], "ms": r["wakeBeforeCompleteMs"]} for r in rows if r["wakeBeforeCompleteMs"] is not None])}
