import importlib.util
import sys
from pathlib import Path
import unittest

CASE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(CASE), str(CASE.parent)]
spec = importlib.util.spec_from_file_location("case1_score", CASE / "score.py")
score = importlib.util.module_from_spec(spec)
sys.modules["case1_score"] = score
spec.loader.exec_module(score)

MECH_IDS = (1000, 1001, 1002)
DEAD_ACTIONS = {1000}


def mech(unit_id):
    return {"id": unit_id, "alias": f"M{unit_id - 999}", "team": 0, "type": "mechMinigun",
            "maxHp": 600.0, "maxShield": 800.0}


def snapshot(ms, idle_ids):
    units = []
    for unit_id in MECH_IDS:
        units.append({"id": unit_id, "alive": True, "order": "idle" if unit_id in idle_ids else "move",
                      "target": None, "lastFireMs": -1, "x": 0.0, "y": 0.0})
    return {"gameMs": ms, "tick": ms // 16, "units": units}


class FakeRun:
    """Just the fields the scorer reads; no run directory needed."""

    def __init__(self, *, final=None, events=None, gateway=None, snapshots=None, end_ms=20000, engage_ms=None):
        self.self_team = 0
        rows = [mech(unit_id) for unit_id in MECH_IDS]
        rows.append({"id": 2000, "team": 1, "type": "c_mammothTank", "maxHp": 2600.0, "maxShield": 0.0})
        self.meta = {"units": rows, "controlledTeam": 0}
        self.units = {row["id"]: row for row in rows}
        final = final if final is not None else {unit_id: True for unit_id in MECH_IDS}
        self.result = {"gameMs": end_ms,
                       "units": [{"id": unit_id, "alive": alive} for unit_id, alive in final.items()]}
        self.events = events or []
        self.gateway = gateway or []
        self.snapshots = snapshots or []
        self.actions = []
        self.end_ms = end_ms
        self.engage_ms = engage_ms
        self.directory = Path("case1-score-fixture-does-not-exist")

    def intervals(self):
        for before, after in zip(self.snapshots, self.snapshots[1:]):
            if after["gameMs"] > before["gameMs"]:
                yield before, after["gameMs"] - before["gameMs"]

    def alive_at_action(self, unit_id, action):
        return unit_id not in DEAD_ACTIONS


class IrrelevantTests(unittest.TestCase):
    def test_weights_rejected_failed_and_no_effect_actions(self):
        gateway = [
            {"kind": "request", "seq": 1, "class": "B", "status": "rejected", "phase": "running", "parent": None},
            {"kind": "request", "seq": 2, "class": "B", "status": "invalid_request", "phase": "running",
             "parent": None, "error": "invalid unit id"},
            {"kind": "request", "seq": 3, "class": "B", "status": "error", "phase": "ended", "parent": None,
             "error": "match has ended: win"},
            {"kind": "request", "seq": 4, "class": "B", "status": "error", "phase": "running", "parent": 228},
            {"kind": "request", "seq": 5, "class": "A", "status": "invalid_request", "phase": "running", "parent": None},
            {"kind": "request", "seq": 6, "class": "B", "status": "partial", "phase": "running", "parent": None,
             "units": [], "skippedDead": ["M1:1000"]},
        ]
        run = FakeRun(gateway=gateway)
        run.actions = [{"tick": 10, "gameMs": 160, "units": [1000]}, {"tick": 12, "gameMs": 192, "units": [1001]}]
        item = score.irrelevant(run)
        self.assertEqual(item["detail"]["counts"],
                         {"violation": 0, "rejected": 1, "failed": 1, "staleCommand": 1, "noEffect": 1})
        self.assertAlmostEqual(item["raw"], 7.5 + 4.5 + 1.8 + 1.8)


class DamageTests(unittest.TestCase):
    def events(self):
        return [
            {"kind": "damage", "victim": 1000, "hpDamage": 600.0, "shieldDamage": 800.0},
            {"kind": "damage", "victim": 1001, "hpDamage": 300.0, "shieldDamage": 400.0},
            {"kind": "damage", "victim": 2000, "hpDamage": 55.0, "shieldDamage": 0.0},
        ]

    def test_survivors_only_by_default(self):
        run = FakeRun(final={1000: False, 1001: True, 1002: True}, events=self.events(),
                      snapshots=[snapshot(0, MECH_IDS)])
        item = score.mech_damage(run)
        self.assertEqual(item["detail"]["scope"], "survivors")
        self.assertEqual(item["detail"]["mechs"], 3)
        self.assertEqual(item["detail"]["K"], 0.333)
        self.assertAlmostEqual(item["detail"]["hpDamageA1"], 300.0)
        self.assertAlmostEqual(item["detail"]["shieldDamageA2"], 400.0)
        self.assertAlmostEqual(item["raw"], 100 / 3 + 300 / 1200 * 20 + 400 / 1600 * 40, places=6)

    def test_all_scope_counts_dead_mech_pools(self):
        original = score.DAMAGE_SCOPE
        score.DAMAGE_SCOPE = "all"
        try:
            run = FakeRun(final={1000: False, 1001: True, 1002: True}, events=self.events(),
                          snapshots=[snapshot(0, MECH_IDS)])
            item = score.mech_damage(run)
        finally:
            score.DAMAGE_SCOPE = original
        self.assertEqual(item["detail"]["maxHpB1"], 1800.0)
        self.assertAlmostEqual(item["raw"], 100 / 3 + 900 / 1800 * 20 + 1200 / 2400 * 40, places=6)

    def test_missing_evidence_is_not_guessed(self):
        run = FakeRun(final={1000: False, 1001: True, 1002: True})
        self.assertEqual(score.mech_damage(run)["status"], "N/A")


class CollectiveIdleTests(unittest.TestCase):
    def test_all_mechs_idle_window_and_exp_severity(self):
        states = [snapshot(ms, MECH_IDS) for ms in range(0, 19000, 1000)]
        states.append(snapshot(19000, {1001, 1002}))       # 1000 starts moving at 19 s
        item = score.collective_idle(FakeRun(snapshots=states))
        windows = item["detail"]["windows"]
        self.assertEqual(len(windows), 1)
        self.assertEqual((windows[0]["startMs"], windows[0]["endMs"]), (0, 19000))
        self.assertAlmostEqual(windows[0]["seconds"], 19.0)
        # f(19) = (exp(-1) - exp(-15)) / (1 - exp(-15))
        self.assertAlmostEqual(windows[0]["severity"], 0.3679, places=4)
        self.assertAlmostEqual(item["raw"], 25 * 0.367879, places=3)

    def test_windows_shorter_than_five_seconds_score_zero(self):
        states = [snapshot(ms, MECH_IDS) for ms in range(0, 4000, 1000)]
        states.append(snapshot(4000, {1001, 1002}))
        item = score.collective_idle(FakeRun(snapshots=states))
        self.assertAlmostEqual(item["raw"], 0.0)

    def test_missing_snapshots_are_n_a(self):
        self.assertEqual(score.collective_idle(FakeRun())["status"], "N/A")


class DocumentTests(unittest.TestCase):
    def test_compute_returns_three_capped_items(self):
        run = FakeRun(final={1000: False, 1001: True, 1002: True},
                      events=[{"kind": "damage", "victim": 1000, "hpDamage": 600.0, "shieldDamage": 800.0}],
                      snapshots=[snapshot(0, MECH_IDS)])
        document = score.compute(run)
        self.assertEqual(document["version"], "case1-score-v1")
        self.assertEqual([item["id"] for item in document["items"]],
                         ["irrelevant", "mech_damage", "collective_idle"])
        self.assertEqual([item["cap"] for item in document["items"]], [30, 60, 40])
        self.assertAlmostEqual(document["deduction"], sum(item["points"] for item in document["items"]))
        self.assertEqual(document["total"], max(0.0, 100 - document["deduction"]))


if __name__ == "__main__":
    unittest.main()
