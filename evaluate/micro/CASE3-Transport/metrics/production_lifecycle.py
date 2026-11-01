"""Production orders, start/completion milestones and factory utilisation (T1)."""
from _shared import stats

ID, TIER, SOURCES = "production_lifecycle", "T1", ["engine/production", "gateway", "meta"]


def compute(run):
    orders = run.orders()
    started = run.started()
    completed = run.completed()
    rows = []
    for order in orders:
        request_id = order.get("requestId")
        start = next((row for row in started if row.get("requestId") == request_id), None)
        rows.append({"requestId": request_id, "factoryId": order.get("factoryId"), "unitType": order.get("unitType"),
                     "count": order.get("count"), "acceptedGameMs": order.get("gameMs"),
                     "acceptedWallMs": order.get("wallMs"), "startedGameMs": start.get("gameMs") if start else None,
                     "queueWaitGameMs": start.get("gameMs") - order.get("gameMs") if start else None})
    # Completion is recorded per type as a cumulative live-unit count, not per order: the engine
    # evidence deliberately avoids guessing which factory produced which unit.
    milestones = [{"unitType": row.get("unitType"), "count": row.get("count"), "gameMs": row.get("gameMs"),
                   "wallMs": row.get("wallMs")} for row in completed]
    factories = [row.get("factoryId") for row in run.meta.get("factories") or []]
    busy = {}
    for order in orders:
        busy[order.get("factoryId")] = busy.get(order.get("factoryId"), 0) + 1
    total = run.result.get("gameMs") or 0
    return {"status": "OK", "orders": rows, "completions": milestones,
            "totals": {"orders": len(orders), "started": len(started), "completed": len(completed),
                       "factories": len(factories)},
            "perFactory": {str(factory): busy.get(factory, 0) for factory in factories},
            "queueWait": stats([row["queueWaitGameMs"] for row in rows]),
            "parallelism": {"ordersPerFactory": round(len(orders) / len(factories), 2) if factories else None,
                            "ordersPerMinute": round(len(orders) / (total / 60000), 2) if total else None},
            "note": "completion milestones are per unit type (cumulative live count), not per order"}
