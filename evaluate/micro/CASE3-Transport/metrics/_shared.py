"""Shared helpers for the CASE3-Transport offline metrics plugins.

Everything here reads only the run's evidence files; the engine never scores a run.
"""
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


def stats(values, key="ms"):
    """n/mean/median/p50/p90/max plus Q3+1.5*IQR outliers, never inventing missing samples."""
    values = [v for v in values if v is not None]
    if not values:
        return {"n": 0, "mean": None, "median": None, "p50": None, "p90": None, "max": None,
                "outliers": [], "samples": []}
    q1, q3 = quantile(values, .25), quantile(values, .75)
    cutoff = q3 + 1.5 * (q3 - q1)
    return {"n": len(values), "mean": round(sum(values) / len(values), 1), "median": round(quantile(values, .5), 1),
            "p50": round(quantile(values, .5), 1), "p90": round(quantile(values, .9), 1), "max": max(values),
            "min": min(values), "outliers": [v for v in values if v > cutoff],
            "samples": [{"value": v} for v in values] if key is None else values}


def slots(meta, unit_type):
    """Real transport slot weight per unit type; the engine's getTransportSlotsNeeded values."""
    return (meta.get("transportSlotsNeeded") or {}).get(unit_type, 1)


class Case3Run:
    """Read-only view over one CASE3-Transport run directory."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.meta = read_json(self.directory / "meta.json", {}) or {}
        if self.meta.get("case") != "CASE3-Transport":
            raise ValueError("Expected a CASE3-Transport meta.json")
        self.result = read_json(self.directory / "result.json", {}) or {}
        self.gateway = [row for row in read_jsonl(self.directory / "gateway.jsonl") if row.get("kind") == "request"]
        self.production = read_jsonl(self.directory / "engine/production.jsonl")
        self.transport = read_jsonl(self.directory / "engine/transport.jsonl")
        self.goal = read_jsonl(self.directory / "engine/goal.jsonl")
        self.violations = read_jsonl(self.directory / "violations.jsonl")
        self.requirements = self.meta.get("requirements") or []
        self.capacity = self.meta.get("transportCapacity")
        self.transport_meta = {t["id"]: t for t in self.meta.get("transports") or []}
        self.turrets = self.meta.get("antiAirTurrets") or []

    # --- gateway -----------------------------------------------------------
    def requests(self, kind=None):
        rows = sorted(self.gateway, key=lambda r: (r.get("wallRecvMs", 0), r.get("seq", 0)))
        return [r for r in rows if kind is None or r.get("class") == kind]

    def submitted(self):
        return [r for r in self.requests() if r.get("status") == "submitted"]

    # --- production --------------------------------------------------------
    def orders(self):
        return [r for r in self.production if r.get("kind") == "order" and r.get("event") == "accepted"]

    def started(self):
        return [r for r in self.production if r.get("event") == "started"]

    def completed(self):
        return [r for r in self.production if r.get("event") == "completed"]

    # --- transport ---------------------------------------------------------
    def loads(self):
        return [r for r in self.transport if r.get("kind") == "load"]

    def moves(self):
        return [r for r in self.transport if r.get("kind") == "move"]

    def unloads(self):
        return [r for r in self.transport if r.get("kind") == "unload"]

    def damages(self):
        return [r for r in self.transport if r.get("kind") == "damage"]

    def cargo_rows(self):
        return [r for r in self.transport if r.get("kind") == "cargo"]

    def cargo_timeline(self, transport_id):
        """[(gameMs, [unitIds])] ordered by game time for one transport."""
        rows = [r for r in self.cargo_rows() if r.get("transportId") == transport_id]
        return [(r.get("gameMs"), list(r.get("cargo") or [])) for r in rows]

    def unit_history(self):
        """unitId -> {transportId, loadedGameMs, unloadedGameMs} from real cargo transitions."""
        history = {}
        for row in self.cargo_rows():
            transport_id = row.get("transportId")
            for unit_id in row.get("cargo") or []:
                entry = history.setdefault(unit_id, {"unitId": unit_id, "transportId": transport_id})
                if "loadedGameMs" not in entry:
                    entry["loadedGameMs"] = row.get("gameMs")
        previous = {}
        for row in self.cargo_rows():
            transport_id = row.get("transportId")
            now = set(row.get("cargo") or [])
            gone = previous.get(transport_id, set()) - now
            for unit_id in gone:
                entry = history.setdefault(unit_id, {"unitId": unit_id, "transportId": transport_id})
                entry.setdefault("unloadedGameMs", row.get("gameMs"))
            previous[transport_id] = now
        return history

    # --- goal --------------------------------------------------------------
    def reached(self):
        return next((r for r in self.goal if r.get("kind") == "reached"), None)

    def delivered_series(self, unit_type):
        return [(r.get("gameMs"), r.get("count")) for r in self.goal
                if r.get("kind") == "delivered" and r.get("unitType") == unit_type]

    def delivered(self):
        return self.result.get("deliveredCounts") or {}

    def produced(self):
        return self.result.get("producedCounts") or {}

    def outcome(self):
        return self.result.get("outcome")

    def final_hp(self):
        rows = [r for r in self.transport if r.get("kind") == "damage"]
        hp = {}
        for row in rows:
            hp[row.get("transportId")] = row.get("hp")
        return hp
