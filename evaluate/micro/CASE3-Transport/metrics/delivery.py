"""Per-unit delivery timeline and per-type delivery rate (T1)."""
ID, TIER, SOURCES = "delivery", "T1", ["engine/transport", "engine/goal", "result", "meta"]


def compute(run):
    history = run.unit_history()
    produced = run.produced()
    delivered = run.delivered()
    rows = []
    for need in run.requirements:
        unit_type = need["type"]
        series = run.delivered_series(unit_type)
        first = series[0][0] if series else None
        last = series[-1][0] if series else None
        rows.append({"unitType": unit_type, "required": need.get("required"),
                     "produced": produced.get(unit_type, 0), "delivered": delivered.get(unit_type, 0),
                     "firstDeliveryGameMs": first, "lastDeliveryGameMs": last,
                     "milestones": series})
    loaded = [entry for entry in history.values() if "loadedGameMs" in entry]
    unloaded = [entry for entry in history.values() if "unloadedGameMs" in entry]
    return {"status": "OK", "types": rows, "units": sorted(history.values(), key=lambda e: e["unitId"]),
            "totals": {"unitsLoaded": len(loaded), "unitsUnloaded": len(unloaded),
                       "unitsNeverLoaded": len(history) - len(loaded)},
            "note": "a unit counts for the goal only while alive, not carried and standing on the target island"}
