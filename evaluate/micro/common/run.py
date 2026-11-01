from __future__ import annotations
import bisect
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "evaluate/utils"))
from replay import Replay, write_outputs
from .native_actions import normalize

def read_json(path: Path, default=None):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.is_file() else default

def read_lines(path: Path):
    if not path.is_file():
        return []
    with path.open(encoding="utf-8-sig") as stream:
        return [json.loads(line) for line in stream if line.strip()]

class Run:
    def __init__(self, directory: Path, settings: dict | None = None):
        self.directory = directory.resolve()
        self.meta = read_json(self.directory / "meta.json", {})
        if self.meta.get("schema") != "rwx-micro-evidence/1":
            raise ValueError("Expected meta.schema = rwx-micro-evidence/1")
        self.result = read_json(self.directory / "result.json", {})
        self.gateway = read_lines(self.directory / "gateway.jsonl")
        self.has_gateway = (self.directory / "gateway.jsonl").is_file() and self.meta.get("mode") != "human"
        self.snapshots = sorted(read_lines(self.directory / "engine/snapshots.jsonl"), key=lambda x: (x["gameMs"], x.get("tick", 0)))
        self.events = read_lines(self.directory / "engine/events.jsonl")
        self.playback = read_json(self.directory / "engine/playback.json", {})
        self.settings = {"lateMs": 1000, "fireTauMs": 1000, **(settings or {})}
        self.units = {u["id"]: u for u in self.meta.get("units", [])}
        teams = {u["team"] for u in self.units.values()}
        self.self_team = int(self.meta.get("controlledTeam", 1))
        self.enemy_team = next((t for t in teams if t != self.self_team), self.self_team + 1)
        self.self_ids = {i for i, u in self.units.items() if u["team"] == self.self_team}
        self.enemy_ids = {i for i, u in self.units.items() if u["team"] == self.enemy_team}
        self.kills = {e["unit"]: e for e in self.events if e["kind"] == "kill"}
        replay_file = self.directory / self.meta.get("replay", "match.replay")
        self.diagnostics = []
        if replay_file.is_file():
            parsed = Replay.load(str(replay_file), strict=True)
            if not parsed.ok:
                self.diagnostics.append("replay is incomplete")
            write_outputs(parsed, str(self.directory / "replay"), ("summary", "jsonl", "text"), clock=True)
            native = parsed.order_rows()
        else:
            native = read_lines(self.directory / "replay/actions.jsonl")
        tick_ms = self.meta.get("tickDurationMs", 16)
        self.actions = [normalize(r, tick_ms) for r in native if r.get("team") == self.self_team and not r.get("noop")]
        self.unknown_actions = [a for a in self.actions if a["kind"] == "other"]
        self.times = [s["gameMs"] for s in self.snapshots]
        self.end_ms = self.result.get("gameMs", self.times[-1] if self.times else 0)
        self.engage_ms = self._engagement()

    def state_at(self, ms):
        index = bisect.bisect_right(self.times, ms) - 1
        return self.snapshots[index] if index >= 0 else None

    def unit_at(self, unit_id, ms):
        state = self.state_at(ms)
        return next((u for u in state["units"] if u["id"] == unit_id), None) if state else None

    def alive_at_action(self, unit_id, action):
        if unit_id not in self.units:
            return False
        death = self.kills.get(unit_id)
        if death:
            return action["tick"] < death["tick"]
        unit = self.unit_at(unit_id, action["gameMs"])
        return unit.get("alive", True) if unit else True

    def intervals(self):
        for before, after in zip(self.snapshots, self.snapshots[1:]):
            if after["gameMs"] > before["gameMs"]:
                yield before, after["gameMs"] - before["gameMs"]

    def _engagement(self):
        import math
        candidates = [e["gameMs"] for e in self.events if e["kind"] == "damage"]
        for state in self.snapshots:
            own = [u for u in state["units"] if u["id"] in self.self_ids and u.get("alive")]
            enemy = [u for u in state["units"] if u["id"] in self.enemy_ids and u.get("alive")]
            if any(math.hypot(a["x"] - b["x"], a["y"] - b["y"]) <= 240 for a in own for b in enemy):
                candidates.append(state["gameMs"])
                break
        return min(candidates) if candidates else None

    def requests(self):
        rows = [r for r in self.gateway if r["kind"] == "request"]
        parents = {r.get("parent") for r in rows if r.get("parent") is not None}
        return sorted((r for r in rows if r.get("seq") not in parents), key=lambda r: (r["wallRecvMs"], r["seq"]))
