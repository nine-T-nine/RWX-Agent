"""Replay command semantics; unknown actions stay visible as other."""
from __future__ import annotations

KINDS = {"move": "move", "attackMove": "attack-move", "attack": "attack", "stop": "stop"}

def normalize(row: dict, tick_ms: float) -> dict:
    kind = KINDS.get(row.get("kind") or row.get("type") or row.get("waypoint_type") or row.get("order"))
    if kind is None:
        kind = "stop" if row.get("stop") or row.get("stop_current_action") or row.get("clear_existing_orders") else "mode" if row.get("attack_mode") else "other"
    # order_rows() carries the engine clock as game_time_ms; gameMs is the legacy fixture key.
    game_ms = row.get("game_time_ms")
    if game_ms is None:
        game_ms = row.get("gameMs")
    if game_ms is None:
        game_ms = (row["tick"] + 1) * tick_ms
    return {"tick": row["tick"], "gameMs": game_ms,
            "team": row.get("team"), "kind": kind, "units": row.get("units", []),
            "target": row.get("target_id", row.get("target_unit")), "x": row.get("x"), "y": row.get("y"), "raw": row}

def match_gateway(requests: list[dict], actions: list[dict], tick_window: int = 4) -> dict:
    """Only unique chronological matches are assigned; repeated competing requests stay ambiguous."""
    mapping = {"move": "move", "attack-move": "attack-move", "attack": "attack", "stop": "stop", "mode": "mode"}
    parents = {r.get("parent") for r in requests if r.get("parent") is not None}
    pending = []
    for request in requests:
        if request.get("class") != "B" or request.get("seq") in parents:
            continue
        kind = mapping.get(request.get("name", "").removeprefix("rwx-micro-1-"))
        ids = {int(str(u).rsplit(":", 1)[-1]) for u in request.get("units", [])}
        candidates = [i for i, a in enumerate(actions) if a["kind"] == kind and ids and set(a["units"]) == ids
                      and 0 <= a["tick"] - request.get("tick", 0) <= tick_window] if request.get("status") in {"submitted", "partial"} else []
        pending.append((request, candidates))
    competition = {}
    for _, candidates in pending:
        for i in candidates:
            competition[i] = competition.get(i, 0) + 1
    rows = []
    last = -1
    for request, candidates in pending:
        unique = len(candidates) == 1 and competition[candidates[0]] == 1
        status = "matched" if unique and candidates[0] > last else "ambiguous" if candidates else "unmatched"
        if status == "matched":
            last = candidates[0]
        rows.append({"seq": request["seq"], "status": status, "candidates": candidates})
    return {"rows": rows, "matched": sum(r["status"] == "matched" for r in rows),
            "ambiguous": sum(r["status"] == "ambiguous" for r in rows), "unmatched": sum(r["status"] == "unmatched" for r in rows)}
