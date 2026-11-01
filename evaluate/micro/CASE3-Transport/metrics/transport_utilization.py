"""Real transport capacity use, trips and idle time (T1)."""
from _shared import stats

ID, TIER, SOURCES = "transport_utilization", "T1", ["engine/transport", "meta", "gateway"]


def compute(run):
    capacity = run.capacity or 0
    loads = run.loads()
    moves = run.moves()
    unloads = run.unloads()
    rows = []
    for transport_id, spec in sorted(run.transport_meta.items()):
        own_loads = [row for row in loads if row.get("transportId") == transport_id]
        own_moves = [row for row in moves if row.get("transportId") == transport_id]
        own_unloads = [row for row in unloads if row.get("transportId") == transport_id]
        timeline = run.cargo_timeline(transport_id)
        peak = max((len(cargo) for _, cargo in timeline), default=0)
        slots = max((row.get("usedSlots") or 0 for row in own_loads), default=0)
        departures = 0
        previous = None
        for game_ms, cargo in timeline:
            if cargo and previous == 0:
                departures += 1
            previous = len(cargo)
        rows.append({"transportId": transport_id, "type": spec.get("type"), "capacity": capacity,
                     "loadCommands": len(own_loads), "moveCommands": len(own_moves), "unloadCommands": len(own_unloads),
                     "peakUnits": peak, "peakSlots": slots,
                     "fillRate": round(slots / capacity, 3) if capacity else None,
                     "cargoEvents": len(timeline)})
    # A move issued while the hold is empty is an empty repositioning trip.
    empty_moves = 0
    for row in moves:
        transport_id = row.get("transportId")
        game_ms = row.get("gameMs") or 0
        carried = 0
        for when, cargo in run.cargo_timeline(transport_id):
            if when is not None and when <= game_ms:
                carried = len(cargo)
        if carried == 0:
            empty_moves += 1
    return {"status": "OK", "transports": rows, "totals": {"loadCommands": len(loads), "moveCommands": len(moves),
                                                           "unloadCommands": len(unloads), "emptyMoves": empty_moves},
            "cargoDelta": stats([row.get("delta") for row in run.cargo_rows()]),
            "note": "slots come from the engine's transport slot weights; units are not assumed to be 1:1"}
