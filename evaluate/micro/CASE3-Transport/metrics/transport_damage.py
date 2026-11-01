"""Transport HP loss and damage timeline (T1)."""
from _shared import stats

ID, TIER, SOURCES = "transport_damage", "T1", ["engine/transport", "meta", "result"]


def compute(run):
    damages = run.damages()
    rows = []
    for transport_id, spec in sorted(run.transport_meta.items()):
        own = [row for row in damages if row.get("transportId") == transport_id]
        losses = [max(0.0, -(row.get("delta") or 0.0)) for row in own]
        start_hp = own[0].get("maxHp") if own else None
        rows.append({"transportId": transport_id, "type": spec.get("type"), "maxHp": start_hp,
                     "hits": len(own), "hpLost": round(sum(losses), 1),
                     "firstHitGameMs": own[0].get("gameMs") if own else None,
                     "lastHitGameMs": own[-1].get("gameMs") if own else None,
                     "finalHp": own[-1].get("hp") if own else start_hp,
                     "alive": run.result.get("transportAliveCount", 0) > 0,
                     "source": "unknown", "timeline": own})
    return {"status": "OK", "transports": rows,
            "totals": {"hits": len(damages), "hpLost": round(sum(max(0.0, -(r.get("delta") or 0.0)) for r in damages), 1),
                       "alive": run.result.get("transportAliveCount"), "count": run.result.get("transportCount")},
            "hitSpacing": stats([b.get("gameMs") - a.get("gameMs") for a, b in zip(damages, damages[1:])]),
            "note": "the engine evidence records HP deltas per transport; the attacking unit is unknown"}
