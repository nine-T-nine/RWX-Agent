import sys
from pathlib import Path
from types import SimpleNamespace
import unittest

CASE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(CASE), str(CASE.parent)]
import score  # noqa: E402


def build(first_complete=5000, second_accept=5416, second_complete=12000):
    events = []

    def instruction(seq, event, moment, **fields):
        events.append(dict(kind="instruction", requestId=seq, event=event, gameMs=moment,
                           tick=moment // 16, wallMs=moment, **fields))

    instruction(1, "accepted", 0, operation="build", actor=1000, cost=700, actualCost=0, credits=2100, targetLevel=1)
    instruction(1, "order_started", 16, operation="build", actor=1000, cost=700, actualCost=0, credits=2100, targetLevel=1)
    instruction(1, "completed", first_complete, operation="build", actor=1000, cost=700, actualCost=700, credits=1400, targetLevel=1)
    instruction(2, "accepted", second_accept, operation="build", actor=1000, cost=700, actualCost=0, credits=1400, targetLevel=1)
    instruction(2, "order_started", second_accept + 16, operation="build", actor=1000, cost=700, actualCost=0, credits=1400, targetLevel=1)
    instruction(2, "completed", second_complete, operation="build", actor=1000, cost=700, actualCost=700, credits=700, targetLevel=1)
    events += [dict(kind="native", requestId=1, nativeIndex=0, gameMs=16, actor=1000, operation="build"),
               dict(kind="native", requestId=2, nativeIndex=1, gameMs=second_accept + 16, actor=1000, operation="build"),
               dict(kind="income", gameMs=0, incomePerSecond=0, deltaIncome=0, cause=["initial"], buildingIds=[])]
    gateway = [{"seq": 1, "class": "B", "status": "submitted", "gameMs": 0, "wallRecvMs": 0, "wallReplyMs": 5},
               {"seq": 2, "class": "B", "status": "submitted", "gameMs": second_accept,
                "wallRecvMs": second_accept, "wallReplyMs": second_accept + 5}]
    meta = dict(initialCredits=2100, builder={"id": 1000}, targetComposition="2 x T1",
                controlledTeam=0, resourceNodes=[], economy=[dict(buildCost=700, upgradeCost=1400)])
    return SimpleNamespace(meta=meta, gateway=gateway, events=events, violations=[], result={"gameMs": second_complete},
                           actions=[], diagnostics=[], has_replay=True)


class ActionDelayTests(unittest.TestCase):
    def test_window_starts_when_credits_and_actor_are_both_ready(self):
        detail = score.action_delay(build(), score._instruction_rows(build()))["detail"]
        self.assertEqual([round(row["windowSeconds"], 3) for row in detail["instructions"]], [0.016, 0.432])
        self.assertEqual(detail["coefficient"], 0.0)
        self.assertEqual(detail["instructions"][1]["actorFreeGameMs"], 5000.0)

    def test_a_three_second_plus_window_enters_the_weighted_bucket(self):
        run = build(first_complete=5500, second_accept=9000, second_complete=20000)
        item = score.action_delay(run, score._instruction_rows(run))
        self.assertAlmostEqual(item["detail"]["coefficient"], 1.5)  # one of two windows in (3,10]
        self.assertEqual(item["raw"], 75.0)  # 1.5 * 50; the 50 cap is applied by the document
        document = score.compute(run)
        self.assertEqual([entry["points"] for entry in document["items"] if entry["id"] == "action_delay"], [50.0])

    def test_missing_cost_is_not_guessed(self):
        run = build()
        rows = score._instruction_rows(run)
        for row in rows:
            row["cost"] = None
        item = score.action_delay(run, rows)
        self.assertEqual(item["status"], "N/A")
        self.assertEqual(item["raw"], 0.0)


class IrrelevantTests(unittest.TestCase):
    def fixture(self):
        run = build()
        run.violations = [{"violationType": "forbidden_command", "reason": "command_not_allowed"}]
        instruction = dict(kind="instruction", requestId=3, event="superseded", gameMs=6000, tick=375, wallMs=6000,
                           operation="build", actor=1000, cost=700, actualCost=0, credits=1400, targetLevel=1)
        upgrade = [dict(kind="instruction", requestId=4, event=event, gameMs=9000, tick=562, wallMs=9000,
                        operation="upgrade", actor=1001, buildingId=1001, targetLevel=2, cost=1400, actualCost=0,
                        credits=1400) for event in ("accepted", "order_started", "completed")]
        run.events += [instruction] + upgrade
        run.gateway += [{"seq": 3, "class": "B", "status": "submitted", "gameMs": 6000},
                        {"seq": 4, "class": "B", "status": "submitted", "gameMs": 9000}]
        return run

    def test_violation_wasted_and_surplus_weights(self):
        run = self.fixture()
        item = score.irrelevant(run, score._instruction_rows(run))
        self.assertEqual(item["detail"]["counts"], {"violation": 1, "wasted": 1, "surplus": 1})
        self.assertAlmostEqual(item["raw"], 4.0 + 2.5 + 1.0)
        self.assertEqual(item["detail"]["composition"]["plannedLevels"], [1, 1])

    def test_instruction_cut_off_by_the_run_end_is_not_wasted(self):
        run = build()
        run.events += [
            dict(kind="instruction", requestId=5, event="accepted", gameMs=11000, tick=687, wallMs=11000,
                 operation="upgrade", actor=1001, buildingId=1001, targetLevel=2, cost=1400, actualCost=0, credits=1400),
            dict(kind="instruction", requestId=5, event="cancelled", gameMs=12000, tick=750, wallMs=12000,
                 operation="upgrade", actor=1001, buildingId=1001, targetLevel=2, cost=1400, actualCost=1400, credits=1400)]
        run.gateway.append({"seq": 5, "class": "B", "status": "submitted", "gameMs": 11000})
        item = score.irrelevant(run, score._instruction_rows(run))
        self.assertEqual(item["detail"]["counts"]["wasted"], 0)


class CadenceTests(unittest.TestCase):
    def test_series_is_adjacent_native_gaps(self):
        run = build()
        run.events = [dict(kind="native", gameMs=16), dict(kind="native", gameMs=65000)]
        item = score.cadence(run)
        self.assertEqual(item["detail"]["intervals"], [64.984])
        # the trimmed mean is clamped to the 60 s endpoint of the natural-exponential curve
        self.assertGreater(item["detail"]["overallRhythm"], 99.0)
        self.assertEqual(item["raw"], 20.0)  # 20% of the 100-point rhythm term

    def test_one_native_command_has_no_series(self):
        run = build()
        run.events = [dict(kind="native", gameMs=16)]
        self.assertEqual(score.cadence(run)["status"], "N/A")


class DocumentTests(unittest.TestCase):
    def test_compute_returns_a_capped_document(self):
        run = build(first_complete=5500, second_accept=9000, second_complete=20000)
        document = score.compute(run)
        self.assertEqual(document["version"], "case2-score-v1")
        self.assertEqual(document["baseline"], 100)
        self.assertEqual({item["id"] for item in document["items"]}, {"action_delay", "irrelevant", "cadence"})
        self.assertAlmostEqual(document["deduction"], sum(item["points"] for item in document["items"]))
        self.assertAlmostEqual(document["total"], 100 - document["deduction"])


if __name__ == "__main__":
    unittest.main()
