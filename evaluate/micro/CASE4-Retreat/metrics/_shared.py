"""Shared helpers for the CASE4-Retreat offline metrics plugins."""
from __future__ import annotations
import json
from pathlib import Path


def read_json(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def read_jsonl(path):
    path = Path(path)
    if not path.is_file():
        return []
    with path.open(encoding="utf-8-sig") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def quantile(values, p):
    ordered = sorted(values)
    if not ordered:
        return None
    index = (len(ordered) - 1) * p
    low = int(index)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (index - low)


def stats(values):
    values = [value for value in values if value is not None]
    if not values:
        return {"n": 0, "mean": None, "median": None, "p50": None, "p90": None, "max": None, "outliers": [], "samples": []}
    q1, q3 = quantile(values, .25), quantile(values, .75)
    cutoff = q3 + 1.5 * (q3 - q1)
    return {"n": len(values), "mean": round(sum(values) / len(values), 1), "median": round(quantile(values, .5), 1),
            "p50": round(quantile(values, .5), 1), "p90": round(quantile(values, .9), 1),
            "max": max(values), "min": min(values), "outliers": [value for value in values if value > cutoff],
            "samples": values}


class Case4Run:
    """Read-only view over one CASE4-Retreat run directory."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.meta = read_json(self.directory / "meta.json", {}) or {}
        if self.meta.get("case") != "CASE4-Retreat":
            raise ValueError("Expected a CASE4-Retreat meta.json")
        self.result = read_json(self.directory / "result.json", {}) or {}
        self.gateway = [row for row in read_jsonl(self.directory / "gateway.jsonl") if row.get("kind") == "request"]
        self.turret_damage = read_jsonl(self.directory / "engine/turret_damage.jsonl")
        self.unit_damage = read_jsonl(self.directory / "engine/unit_damage.jsonl")
        self.turret_destroyed = read_jsonl(self.directory / "engine/turret_destroyed.jsonl")
        self.repairs = read_jsonl(self.directory / "engine/repair_sessions.jsonl")
        # Tank readiness/loiter timeline sampled by the engine while the task runs (250 game-ms).
        # It is evidence for the deduction score only; no metric plugin consumes it.
        self.tank_state = read_jsonl(self.directory / "engine/tank_state.jsonl")
        self.violations = read_jsonl(self.directory / "violations.jsonl")
        self.turrets = {row["id"]: row for row in self.meta.get("turrets") or []}
        self.tanks = {row["id"]: row for row in self.meta.get("tanks") or []}
        self.bays = {row["id"]: row for row in self.meta.get("repairBays") or []}

    def requests(self, kind=None):
        rows = sorted(self.gateway, key=lambda row: (row.get("wallRecvMs", 0), row.get("seq", 0)))
        return [row for row in rows if kind is None or row.get("class") == kind]

    def submitted(self):
        return [row for row in self.requests() if row.get("status") == "submitted"]

    def hits(self):
        return [row for row in self.unit_damage if row.get("kind") is None]

    def orders(self):
        return [row for row in self.unit_damage if row.get("kind") == "order"]

    def destroyed(self):
        return [row for row in self.turret_destroyed if row.get("kind") != "reached"]

    def reached(self):
        return next((row for row in self.turret_destroyed if row.get("kind") == "reached"), None)

    def turret_hits(self, turret_id):
        return [row for row in self.turret_damage if row.get("victim") == turret_id]

    def tank_hits(self, tank_id):
        return [row for row in self.hits() if row.get("victim") == tank_id]

    def outcome(self):
        return self.result.get("outcome")
