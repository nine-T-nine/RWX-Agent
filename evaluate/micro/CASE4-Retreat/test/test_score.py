import importlib.util
import sys
from pathlib import Path
import unittest

CASE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(CASE), str(CASE.parent)]
spec = importlib.util.spec_from_file_location("case4_score", CASE / "score.py")
score = importlib.util.module_from_spec(spec)
sys.modules["case4_score"] = score
spec.loader.exec_module(score)

TANK_ID = 1007
MAX_HP = 3500.0


def state(ms, *, order="idle", bays=(), hp=MAX_HP, alive=True):
    return {"kind": "state", "gameMs": ms, "tick": ms // 16,
            "tanks": [{"unitId": TANK_ID, "alias": "T1", "x": 1150.0, "y": 1290.0, "alive": alive,
                       "order": order, "target": None, "hp": hp, "maxHp": MAX_HP, "shield": 5000.0,
                       "repairBayIds": list(bays)}]}


def ready(ms, hp=MAX_HP):
    return state(ms, bays=[1005], hp=hp)


def moving(ms):
    return state(ms, order="move")


class FakeRun:
    """Just the fields the scorer reads; no run directory needed."""

    def __init__(self, *, tanks=None, tank_state=None, final=None, game_ms=20000, task="4_1_1", type_=1, kills=None):
        tanks = tanks or [{ "id": TANK_ID, "alias": "T1", "team": 0, "type": "experimentalHoverTank"}]
        self.tanks = {row["id"]: row for row in tanks}
        final = final if final is not None else {row["id"]: True for row in tanks}
        self.result = {"gameMs": game_ms, "kills": kills or [],
                       "tanks": [{"unitId": unit_id, "alive": alive} for unit_id, alive in final.items()],
                       "tanksAlive": sum(1 for alive in final.values() if alive), "tanksTotal": len(final)}
        self.meta = {"task": task, "type": type_, "tickDurationMs": 16, "limits": {"gameSeconds": 900.0}}
        self.tank_state = tank_state or []
        self.gateway = []
        self.directory = Path("case4-score-fixture-does-not-exist")


class TankLossTests(unittest.TestCase):
    def test_destroyed_share(self):
        run = FakeRun(tanks=[{"id": 1007, "team": 0}, {"id": 1008, "team": 0}],
                      final={1007: True, 1008: False}, game_ms=1000)
        item = score.tank_loss(run)
        self.assertEqual(item["detail"]["K1"], 0.5)
        self.assertAlmostEqual(item["raw"], 15.0)

    def test_missing_final_state_is_not_guessed(self):
        run = FakeRun()
        run.result = {"gameMs": 1000}
        self.assertEqual(score.tank_loss(run)["status"], "N/A")


class TankIdleTests(unittest.TestCase):
    def test_ready_and_loiter_windows_and_weights(self):
        rows = [ready(ms) for ms in range(0, 5000, 1000)]          # ready [0, 5000) = 5 s
        rows += [moving(5000)]
        rows += [state(ms) for ms in range(6000, 15000, 1000)]     # loiter [6000, 15000) = 9 s
        rows += [state(15000, order="attack")]
        rows += [state(ms, order="attack") for ms in range(16000, 20001, 1000)]
        item = score.tank_idle(FakeRun(tank_state=rows, game_ms=20000))
        windows = {row["kind"]: row for row in item["detail"]["windows"] if row["counted"]}
        self.assertAlmostEqual(windows["ready"]["seconds"], 5.0)
        self.assertAlmostEqual(windows["loiter"]["seconds"], 9.0)
        self.assertAlmostEqual(item["detail"]["weightedSeconds"], 5 * 1.5 + 9 * 2)
        self.assertAlmostEqual(item["detail"]["ratio"], (5 * 1.5 + 9 * 2) / 20)
        # R defaults to 5% (no baseline w/T yet), so the item saturates here.
        self.assertAlmostEqual(item["raw"], score.IDLE_SCALE * ((5 * 1.5 + 9 * 2) / 20) / score.IDLE_RATIO_DEFAULT)

    def test_thresholds_are_strict_and_short_windows_are_free(self):
        rows = [ready(ms) for ms in range(0, 4000, 1000)]          # 4 s: counted (> 3)
        rows += [moving(4000)]
        rows += [ready(4000 + 1000 * index) for index in range(1, 4)]   # 3 s: not counted at 3.0
        rows += [moving(8000)]
        rows += [state(9000 + 1000 * index) for index in range(0, 8)]   # 8 s: not counted at 8.0
        rows += [moving(17000)]
        rows += [state(18000 + 1000 * index, order="attack") for index in range(0, 3)]
        item = score.tank_idle(FakeRun(tank_state=rows, game_ms=20000))
        windows = [row for row in item["detail"]["windows"] if row["counted"]]
        self.assertEqual([row["kind"] for row in windows], ["ready"])
        self.assertAlmostEqual(windows[0]["seconds"], 4.0)

    def test_partial_hp_inside_a_bay_is_neither_ready_nor_loiter(self):
        rows = [state(ms, bays=[1005], hp=3000.0) for ms in range(0, 10000, 1000)]
        item = score.tank_idle(FakeRun(tank_state=rows, game_ms=20000))
        self.assertEqual(item["detail"]["windows"], [])
        self.assertEqual(item["raw"], 0.0)

    def test_missing_tank_state_is_n_a(self):
        self.assertEqual(score.tank_idle(FakeRun())["status"], "N/A")

    def test_death_ends_the_alive_time(self):
        rows = [ready(ms) for ms in range(0, 5000, 1000)]
        rows += [state(5000, alive=False, hp=0.0)]
        item = score.tank_idle(FakeRun(tank_state=rows, final={TANK_ID: False}, game_ms=20000,
                                       kills=[{"unit": TANK_ID, "tick": 312}]))
        self.assertAlmostEqual(item["detail"]["aliveSeconds"], 4992 / 1000)
        # The 4.992 s ready window before death is still counted (it is longer than 3 s).
        self.assertAlmostEqual(item["detail"]["windows"][0]["seconds"], 4.992)
        self.assertTrue(item["detail"]["windows"][0]["counted"])


class DurationTests(unittest.TestCase):
    def test_quadratic_in_normalized_space(self):
        item = score.duration_score(FakeRun(game_ms=50240), 0.0)
        self.assertEqual(item["detail"]["baselineSource"], "measured baseline")
        self.assertAlmostEqual(item["detail"]["baselineSeconds"], 50.24)
        # Exactly at T the normalized position is (1-0.4)/1.6 = 0.375.
        self.assertAlmostEqual(item["raw"], score.curve(0.375, 0, 1, 0, 30, "quadratic", -3), places=4)
        self.assertAlmostEqual(item["raw"], 11.953125, places=4)

    def test_fast_and_slow_ends(self):
        self.assertEqual(score.duration_score(FakeRun(game_ms=20000), 0.0)["raw"], 0.0)
        self.assertEqual(score.duration_score(FakeRun(game_ms=200000), 0.0)["raw"], 30.0)

    def test_unknown_type_falls_back_to_the_game_limit(self):
        item = score.duration_score(FakeRun(game_ms=300000, task="4_9_9", type_=9), 0.0)
        self.assertEqual(item["detail"]["baselineSource"], "0.55 x game limit")
        self.assertAlmostEqual(item["detail"]["baselineSeconds"], 495.0)

class DocumentTests(unittest.TestCase):
    def test_compute_returns_three_capped_items(self):
        rows = [state(ms) for ms in range(0, 5000, 1000)] + [moving(5000)]
        document = score.compute(FakeRun(tank_state=rows, game_ms=53616))
        self.assertEqual(document["version"], "case4-score-v1")
        self.assertEqual([item["id"] for item in document["items"]], ["tank_loss", "tank_idle", "duration"])
        self.assertEqual([item["cap"] for item in document["items"]], [20, 50, 30])
        self.assertAlmostEqual(document["deduction"], sum(item["points"] for item in document["items"]))
        self.assertEqual(document["total"], max(0.0, 100 - document["deduction"]))


if __name__ == "__main__":
    unittest.main()
