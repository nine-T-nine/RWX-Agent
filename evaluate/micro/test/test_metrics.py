from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

MICRO = Path(__file__).resolve().parents[1]
ROOT = MICRO.parents[1]
sys.path.insert(0, str(MICRO))
from common.run import Run
from common import measurements as m
from common.native_actions import normalize, match_gateway

spec = importlib.util.spec_from_file_location("micro_evaluate", MICRO / "CASE1-duel/evaluate.py")
evaluator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluator)

class MetricsTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "agent-tmp").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="micro-fixture-", dir=ROOT / "agent-tmp")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for name in ("engine", "replay"):
            (self.directory / name).mkdir()
        self.meta = {"schema": "rwx-micro-evidence/1", "case": "CASE1-duel", "task": "1_2_2", "seed": 1,
                     "mode": "agent", "track": "realtime", "tickDurationMs": 16,
                     "units": [{"id": i, "team": 2 if i == 2 else 1, "alias": f"U{i}", "type": "fixture",
                                "maxHp": 100, "maxShield": 0, "range": 190 if i == 2 else 210,
                                "weapons": [{"reloadFrames": 50, "warmupFrames": 30}]} for i in (1, 2, 3)]}
        self.states = []
        for ms in (0, 1000, 2000, 3000):
            units = []
            for i in (1, 2, 3):
                hp = (100 if ms == 0 else 40 if ms == 1000 else 0) if i == 1 else (100 if ms == 0 else 90 if ms == 1000 else 80) if i == 2 else 100
                units.append({"id": i, "x": 200 if i == 2 else 0, "y": 0, "hp": hp, "shield": 0,
                              "alive": hp > 0, "order": "attack" if i == 1 else "idle", "target": 2 if i == 1 else None,
                              "lastFireMs": 0, "weapons": [{"target": 2 if ms == 0 else None, "cooldownFrames": 1 if ms == 0 else 0, "warmupFrames": 0}]})
            self.states.append({"gameMs": ms, "tick": round(ms / 16), "units": units})
        self.actions = [{"type": "move", "tick": 30, "gameMs": 500, "team": 1, "units": [1]},
                        {"type": "attack", "tick": 68, "gameMs": 1100, "team": 1, "units": [1]},
                        {"type": "stop", "tick": 156, "gameMs": 2500, "team": 1, "units": [1]}]
        self.events = [{"kind": "damage", "gameMs": 1000, "tick": 63, "victim": 1, "attacker": 2, "hp": 40},
                       {"kind": "damage", "gameMs": 2000, "tick": 125, "victim": 1, "attacker": 2, "hp": 0},
                       {"kind": "kill", "gameMs": 2000, "tick": 125, "unit": 1}]
        self.gateway = []

    def write(self):
        def write_json(path, value):
            path.write_text(json.dumps(value), encoding="utf-8")
        def lines(path, values):
            path.write_text("".join(json.dumps(v) + "\n" for v in values), encoding="utf-8")
        write_json(self.directory / "meta.json", self.meta)
        lines(self.directory / "engine/snapshots.jsonl", self.states)
        lines(self.directory / "engine/events.jsonl", self.events)
        lines(self.directory / "replay/actions.jsonl", self.actions)
        lines(self.directory / "gateway.jsonl", self.gateway)
        write_json(self.directory / "result.json", {"outcome": "timeout", "gameMs": 3000, "wallMs": 9000})
        return Run(self.directory)

    def request(self, seq, kind, recv, reply, game, name=None, turn=0, phase="running"):
        return {"kind": "request", "seq": seq, "class": kind, "wallRecvMs": recv, "wallReplyMs": reply, "gameMs": game,
                "tick": round(game / 16), "phase": phase, "turn": turn, "name": name or {"A": "rwx-micro-1-state", "B": "rwx-micro-1-move", "C": "rwx-ping"}[kind],
                "status": "submitted", "units": ["U1:1"], "skippedDead": []}

    def test_hand_calculated_outcome_cadence_thresholds_and_stale(self):
        run = self.write()
        self.assertAlmostEqual(m.outcome(run)["delta"], -.3)
        cadence = m.cadence(run)
        self.assertEqual(cadence["intervalMs"]["p50"], 1000)
        self.assertEqual(cadence["intervalMs"]["p90"], 1320)
        self.assertEqual(cadence["combatMaxGapMs"], 1400)
        self.assertEqual(cadence["combatCommandsPerMinute"], 60)
        self.assertEqual(m.engage_lag(run), {"engageMs": 0, "status": "ANSWERED", "latencyMs": 500, "responseTick": 30})
        low = m.lowhp_lag(run)["rows"]
        self.assertEqual([(r["threshold"], r["status"], r["latencyMs"]) for r in low], [(.5, "ANSWERED", 100), (.25, "UNANSWERED", None)])
        self.assertEqual(m.stale_command(run)["count"], 1)
        self.assertEqual(m.idle_in_combat(run)["totalUnitMs"], 3000)
        self.assertAlmostEqual(m.fire_uptime(run)["units"]["3"]["ratio"], 1 / 3)

    def test_no_commands_and_briefing_only_and_late(self):
        self.actions = []
        self.gateway = [self.request(1, "A", 0, 10, 0, phase="briefing"), self.request(2, "B", 20, 30, 0, phase="briefing")]
        run = self.write()
        self.assertEqual(m.engage_lag(run)["status"], "UNANSWERED")
        self.assertIsNone(m.cadence(run)["intervalMs"]["p50"])
        self.assertEqual(m.cadence(run)["combatMaxGapMs"], 3000)
        self.assertEqual(m.agent_timing(run)["briefing"], {"A": 1, "B": 1, "C": 0})
        self.actions = [{"type": "move", "tick": 110, "gameMs": 1760, "team": 1, "units": [3]}]
        self.assertEqual(m.engage_lag(self.write())["status"], "LATE")

    def test_preempted(self):
        self.states[0]["units"][1]["x"] = 1000
        self.assertEqual(m.engage_lag(self.write())["status"], "PREEMPTED")

    def test_finished_briefing_move_is_not_preempted(self):
        self.states[0]["units"][1]["x"] = 1000
        for state in self.states[:2]:
            state["units"][0]["order"] = "idle"
        self.assertEqual(m.engage_lag(self.write())["status"], "ANSWERED")

    def test_shield_loss_keeps_dead_units_leftover(self):
        self.meta["units"][0]["maxShield"] = 100
        self.states[-1]["units"][0]["shield"] = 50
        self.write()
        self.assertAlmostEqual(m.outcome(Run(self.directory))["shieldLossFractions"]["self"], .5)

    def test_replay_game_time_is_used_verbatim(self):
        action = normalize({"tick": 60, "game_time_ms": 960, "type": "move", "units": [1], "team": 1}, 16)
        self.assertEqual(action["gameMs"], 960)

    def test_win_and_total_loss(self):
        self.states[-1]["units"][1].update(hp=0, alive=False)
        self.write()
        (self.directory / "result.json").unlink()
        self.assertEqual(m.outcome(Run(self.directory))["outcome"], "win")
        for u in self.states[-1]["units"]:
            u.update(hp=0, alive=False)
        self.write()
        (self.directory / "result.json").unlink()
        self.assertEqual(m.outcome(Run(self.directory))["outcome"], "draw")
        self.states[-1]["units"][1].update(hp=10, alive=True)
        self.write()
        (self.directory / "result.json").unlink()
        self.assertEqual(m.outcome(Run(self.directory))["outcome"], "loss")

    def test_damage_time_free_fire_and_missing_evidence(self):
        for i, x in ((1, 0), (3, 40)):
            self.events.append({"kind": "damage", "gameMs": 1000, "tick": 63, "attacker": i, "victim": 2,
                                "hpDamage": 10, "shieldDamage": 0, "hp": 80, "attackerX": x, "attackerY": 0,
                                "opponents": [{"id": 2, "x": 200, "y": 0, "range": 190}]})
        self.assertEqual(m.free_fire(self.write())["ratio"], .5)
        del self.events[-1]["opponents"]
        self.assertEqual(m.free_fire(self.write())["status"], "N/A")

    def test_thinking_clock_excludes_execution_and_C(self):
        self.gateway = [self.request(1, "A", 0, 20, 0), self.request(2, "A", 100, 120, 100),
                        self.request(3, "B", 300, 320, 300), self.request(4, "B", 400, 420, 400),
                        self.request(5, "C", 500, 600, 500), self.request(6, "A", 800, 820, 600)]
        value = m.agent_timing(self.write())
        self.assertEqual(value["counts"], {"A": 3, "B": 2, "C": 1})
        self.assertEqual(value["cycles"][0]["thinkingWallMs"], 340)
        self.assertEqual(value["waitWallMs"]["p50"], 380)

    def test_pause_turns(self):
        self.meta["track"] = "pause"
        self.gateway = [self.request(1, "C", 0, 100, 0, "rwx-micro-1-begin"),
                        self.request(2, "B", 500, 510, 1000, turn=1),
                        self.request(3, "C", 1000, 1100, 1000, "rwx-micro-1-next", turn=1)]
        self.gateway += [{"kind": "lifecycle", "event": "turn", "turn": i, "gameMs": i * 1000} for i in (1, 2)]
        value = m.turns(self.write())
        self.assertEqual(value["turns"], 2)
        self.assertEqual(value["thinkingWallMs"]["p50"], 900)
        self.assertEqual(value["actionsPerTurn"], {1: 0, 2: 1})

    def test_replay_only_and_auto_human_column(self):
        self.write()
        baseline = evaluator.evaluate(self.directory, "skip", "none")
        index = self.directory / "index.json"
        index.write_text(json.dumps({"runs": {"1_2_2:1": "eval/metrics.json"}}), encoding="utf-8")
        result = evaluator.evaluate(self.directory, "skip", "auto", baseline_index=index)
        self.assertEqual(result["reference"]["metrics"]["outcome"], baseline["metrics"]["outcome"])
        self.assertTrue(all(v["tier"] == "T1" for v in result["reference"]["metrics"].values()))
        (self.directory / "gateway.jsonl").unlink()
        result = evaluator.evaluate(self.directory, "skip", "none")
        self.assertEqual(result["metrics"]["agent_timing"]["value"]["status"], "N/A")
        self.assertTrue((self.directory / "eval/report.html").is_file())

    def test_unknown_action_and_ambiguous_gateway(self):
        action = normalize({"type": "unknownFutureOrder", "tick": 1, "units": [1], "team": 1}, 16)
        self.assertEqual(action["kind"], "other")
        cleared = normalize({"type": None, "clear_existing_orders": True, "tick": 0, "units": [1], "team": 0}, 16)
        self.assertEqual(cleared["kind"], "stop")
        requests = [self.request(i, "B", i, i, 0) for i in (1, 2)]
        result = match_gateway(requests, [{"tick": 0, "kind": "move", "units": [1]}])
        self.assertEqual(result["ambiguous"], 2)
        self.assertEqual(result["matched"], 0)

    def test_plugin_failure_does_not_hide_other_metrics_and_missing_state_is_na(self):
        self.write()
        (self.directory / "engine/snapshots.jsonl").unlink()
        plugins = self.directory / "metrics"
        plugins.mkdir()
        (plugins / "outcome.py").write_text('from common.measurements import outcome as compute\nID="outcome"\nTIER="T1"\n', encoding="utf-8")
        (plugins / "broken.py").write_text('ID="broken"\nTIER="T1"\ndef compute(run): raise RuntimeError("fixture failure")\n', encoding="utf-8")
        with patch.object(evaluator, "CASE", self.directory):
            result = evaluator.evaluate(self.directory, "skip", "none")
        self.assertEqual(result["metrics"]["broken"]["value"]["status"], "ERROR")
        self.assertEqual(result["metrics"]["outcome"]["value"]["outcome"], "timeout")

if __name__ == "__main__":
    unittest.main()
