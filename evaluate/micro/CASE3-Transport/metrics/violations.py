"""Rejected requests and native-authority diagnostics (T1)."""
ID, TIER, SOURCES = "violations", "T1", ["violations", "gateway", "meta"]


def compute(run):
    rows = []
    for row in run.violations:
        rows.append({"violationId": row.get("violationId"), "requestId": row.get("requestId"),
                     "violationType": row.get("violationType"), "reason": row.get("reason"),
                     "command": row.get("command"), "tick": row.get("tick"), "gameMs": row.get("gameMs"),
                     "wallMs": row.get("wallMs"), "rejected": row.get("rejected")})
    rejected = [row for row in run.requests() if row.get("status") == "rejected"]
    return {"status": "OK", "count": len(rows), "rows": rows,
            "rejectedRequests": len(rejected),
            "byType": {kind: sum(1 for row in rows if row["violationType"] == kind)
                       for kind in sorted({row["violationType"] for row in rows})},
            "note": "a rejected request never reaches the replay or the game state"}
