import sys
from pathlib import Path
import unittest

CASE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(CASE), str(CASE.parent)]
import score  # noqa: E402


class FakeRun:
    """Just the fields the scorer reads; no run directory needed."""

    def __init__(self, production=None, transport=None, gateway=None, violations=None, units=None, game_ms=110000):
        tiles = [y * 130 + x for y in range(96, 101) for x in range(96, 101)]
        self.meta = {"task": "3_1_1", "type": 1, "initialCredits": 2000, "mapSize": 130, "tileSize": 20,
                     "baseIslandTiles": tiles, "limits": {"gameSeconds": 900},
                     "transports": [{"id": 1007}, {"id": 1008}, {"id": 1009}]}
        self.result = {"gameMs": game_ms, "incomePerSecond": 150,
                       "units": units if units is not None else [
                           {"id": 1007, "hp": 0, "maxHp": 500, "alive": False},
                           {"id": 1008, "hp": 200, "maxHp": 500, "alive": True},
                           {"id": 1009, "hp": 320, "maxHp": 500, "alive": True}]}
        self.production = production or []
        self.transport = transport or []
        self.gateway = gateway or []
        self.violations = violations or []

    def moves(self):
        return [row for row in self.transport if row.get("kind") == "move"]

    def unloads(self):
        return [row for row in self.transport if row.get("kind") == "unload"]

    def cargo_timeline(self, transport_id):
        return [(row.get("gameMs"), list(row.get("cargo") or [])) for row in self.transport
                if row.get("kind") == "cargo" and row.get("transportId") == transport_id]


def production_fixture():
    return [
        {"kind": "order", "requestId": 9, "event": "accepted", "factoryId": 1000, "unitType": "heavyTank",
         "cost": 800, "credits": 2000, "gameMs": 1000},
        {"kind": "order", "requestId": 9, "event": "started", "factoryId": 1000, "unitType": "heavyTank", "gameMs": 1016},
        {"kind": "order", "requestId": 9, "event": "finished", "factoryId": 1000, "gameMs": 16000},
        {"kind": "order", "requestId": 10, "event": "accepted", "factoryId": 1000, "unitType": "heavyTank",
         "cost": 800, "credits": 1438, "gameMs": 20000},
        {"kind": "order", "requestId": 10, "event": "started", "factoryId": 1000, "unitType": "heavyTank", "gameMs": 20016},
    ]


def flight_fixture():
    return [
        {"kind": "move", "transportId": 1007, "gameMs": 1000, "x": 630, "y": 630},
        {"kind": "flight", "transportId": 1007, "event": "takeoff", "gameMs": 1200},
        {"kind": "flight", "transportId": 1007, "event": "landed", "gameMs": 20000},
        {"kind": "move", "transportId": 1007, "gameMs": 60000, "x": 1930, "y": 1960},
        {"kind": "flight", "transportId": 1007, "event": "takeoff", "gameMs": 60200},
        {"kind": "flight", "transportId": 1007, "event": "landed", "gameMs": 98000},
    ]


class ActionDelayTests(unittest.TestCase):
    def test_factory_availability_and_funding_both_bound_the_window(self):
        run = FakeRun(production=production_fixture())
        orders, started, _, _ = score._rows(run)
        item = score.action_delay(run, orders, started)
        windows = [row["windowSeconds"] for row in item["detail"]["orders"]]
        self.assertEqual(windows, [1.0, 4.0])          # first order from run start, second after `finished`
        self.assertEqual(item["detail"]["orders"][1]["factoryFreeGameMs"], 16000.0)
        self.assertAlmostEqual(item["detail"]["coefficient"], 1.5)
        self.assertEqual(item["raw"], 75.0)             # capped to 40 only when the document is built


class TransportLossTests(unittest.TestCase):
    def test_lost_hp_and_destroyed_share(self):
        item = score.transport_loss(FakeRun())
        self.assertAlmostEqual(item["detail"]["K1HpLoss"], 1 - 520 / 1500, places=3)
        self.assertAlmostEqual(item["detail"]["K2Destroyed"], 1 / 3, places=3)
        self.assertAlmostEqual(item["raw"], (1 - 520 / 1500) * 10 + (1 / 3) * 20, places=3)

    def test_missing_final_state_is_not_guessed(self):
        run = FakeRun()
        run.result = {"gameMs": 1000, "incomePerSecond": 0}
        self.assertEqual(score.transport_loss(run)["status"], "N/A")


class TransportIdleTests(unittest.TestCase):
    def test_ready_and_finished_windows(self):
        item = score.transport_idle(FakeRun(transport=flight_fixture()), 110000)
        segments = [(row["kind"], row["seconds"]) for row in item["detail"]["segments"]]
        self.assertEqual(segments, [("ready", 1.2), ("ready", 40.2), ("finished", 40.0), ("finished", 12.0)])
        severity = {row["kind"] + str(row["seconds"]): row["severity"] for row in item["detail"]["segments"]}
        self.assertEqual(severity["ready40.2"], 1.0)
        self.assertEqual(severity["finished40.0"], 1.0)
        self.assertAlmostEqual(item["raw"], 30.0)

    def test_missing_flight_evidence_is_n_a(self):
        item = score.transport_idle(FakeRun(transport=[{"kind": "move", "transportId": 1007, "gameMs": 1000}]), 110000)
        self.assertEqual(item["status"], "N/A")
        self.assertEqual(item["raw"], 0.0)


class IrrelevantTests(unittest.TestCase):
    def test_violations_failures_and_non_return_empty_moves(self):
        transport = [{"kind": "move", "transportId": 1007, "gameMs": 1000, "x": 630, "y": 630},
                     {"kind": "move", "transportId": 1007, "gameMs": 5000, "x": 1960, "y": 1980},
                     {"kind": "cargo", "transportId": 1007, "gameMs": 500, "cargo": []},
                     {"kind": "unload", "transportId": 1008, "gameMs": 6000}]
        gateway = [{"seq": 1, "status": "invalid_request", "name": "rwx-micro-3-load", "class": "B"},
                   {"seq": 2, "status": "error", "name": "rwx-micro-3-move", "class": "B"},
                   {"seq": 3, "status": "submitted", "name": "rwx-micro-3-produce", "class": "B"}]
        run = FakeRun(transport=transport, gateway=gateway,
                      violations=[{"violationType": "non_target_production", "reason": "type_not_in_goal"}])
        item = score.irrelevant(run, run.moves(), run.unloads())
        self.assertEqual(item["detail"]["counts"], {"violation": 1, "failed": 2, "emptyMove": 1, "emptyUnload": 1})
        self.assertAlmostEqual(item["raw"], 7.5 + 2 * 4.5 + 2 * 1.8)


class DurationTests(unittest.TestCase):
    def test_measured_baseline_and_quadratic_cap(self):
        item = score.duration_score(FakeRun(game_ms=109760))
        self.assertEqual(item["detail"]["baselineSource"], "measured baseline")
        self.assertEqual(item["detail"]["baselineSeconds"], 111.6)   # median of the 3_1_1 baseline samples
        self.assertEqual(item["raw"], 30.0)

    def test_faster_than_fourty_percent_scores_zero(self):
        item = score.duration_score(FakeRun(game_ms=20000))
        self.assertEqual(item["raw"], 0.0)


class DocumentTests(unittest.TestCase):
    def test_compute_returns_all_five_items(self):
        run = FakeRun(production=production_fixture(), transport=flight_fixture(), game_ms=110000)
        document = score.compute(run)
        self.assertEqual(document["version"], "case3-score-v1")
        self.assertEqual([item["id"] for item in document["items"]],
                         ["action_delay", "irrelevant", "transport_loss", "transport_idle", "duration"])
        self.assertAlmostEqual(document["deduction"], sum(item["points"] for item in document["items"]))
        self.assertEqual(document["total"], max(0.0, 100 - document["deduction"]))


if __name__ == "__main__":
    unittest.main()
