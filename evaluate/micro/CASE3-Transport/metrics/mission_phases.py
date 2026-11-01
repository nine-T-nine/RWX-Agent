"""Observable mission phases: production, loading, transit, unload, delivery (T2)."""
ID, TIER, SOURCES = "mission_phases", "T2", ["engine/production", "engine/transport", "engine/goal", "result"]


def compute(run):
    orders = run.orders()
    started = run.started()
    completed = run.completed()
    loads = run.loads()
    moves = run.moves()
    unloads = run.unloads()
    cargo = run.cargo_rows()
    reached = run.reached() or {}
    phases = {}

    def span(name, times):
        times = sorted(t for t in times if t is not None)
        if not times:
            return None
        return {"startGameMs": times[0], "endGameMs": times[-1], "durationMs": times[-1] - times[0]}

    phases["production"] = span("production", [row.get("gameMs") for row in started] +
                                [row.get("gameMs") for row in completed])
    phases["loading"] = span("loading", [row.get("gameMs") for row in loads] +
                             [row.get("gameMs") for row in cargo])
    phases["transit"] = span("transit", [row.get("gameMs") for row in moves])
    phases["unload"] = span("unload", [row.get("gameMs") for row in unloads])
    phases["delivery"] = span("delivery", [row.get("gameMs") for row in run.goal])
    # Overlap: production still running while the first transport was already moving cargo.
    first_move = min((row.get("gameMs") for row in moves), default=None)
    last_completed = max((row.get("gameMs") for row in completed), default=None)
    overlap = None
    if first_move is not None and last_completed is not None and last_completed > first_move:
        overlap = last_completed - first_move
    return {"status": "OK", "phases": phases,
            "productionToFirstLoad": (min((row.get("gameMs") for row in loads), default=None) -
                                      max((row.get("gameMs") for row in completed), default=None)
                                      if loads and completed else None),
            "firstMoveGameMs": first_move,
            "productionOverlapMs": overlap,
            "reachedGameMs": reached.get("gameMs"),
            "orders": len(orders),
            "note": "phases come from real command/event timestamps; overlapping phases are reported as is"}
