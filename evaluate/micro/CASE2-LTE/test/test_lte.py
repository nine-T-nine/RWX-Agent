import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

CASE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(CASE), str(CASE/"metrics"), str(CASE.parent)]
from _shared import lifecycle, match_native, stats, timing
from greedy import choose, next_event

def plugin(name):
    spec = importlib.util.spec_from_file_location("test_lte_"+name,CASE/"metrics"/(name+".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.compute

def request(seq, kind, recv, reply, status="submitted"):
    return dict(seq=seq,requestId=seq,**{"class":kind},wallRecvMs=recv,wallReplyMs=reply,status=status,gameMs=0,tick=0)

def fixture():
    events=[]
    for event, game, wall in [("accepted",0,101),("order_started",16,110),("waiting_for_credits",16,110),
                              ("waiting_for_credits_end",160,250),("constructing",160,250),
                              ("constructing_end",320,500),("completed",320,500)]:
        events.append(dict(kind="instruction",requestId=2,event=event,gameMs=game,tick=game//16,wallMs=wall,actualCost=700,buildingId=1001))
    events += [dict(kind="native",requestId=2,nativeIndex=0,tick=1,actor=1000,operation="build",buildType="extractorT1",resourceId="R1",actionId=None)]
    return SimpleNamespace(meta=dict(controlledTeam=0,resourceNodes=[dict(resourceId="R1",x=100,y=200)],targetIncomePerSecond=8),
        gateway=[request(1,"A",0,10),request(2,"B",100,105),request(3,"A",200,210),request(4,"A",600,610)],
        events=events,actions=[dict(tick=1,units=[1000],team=0,type="build",build="custom:extractorT1",build_count=1,x=100,y=200)],
        has_replay=True,violations=[],diagnostics=[],result={})

class MetricsTests(unittest.TestCase):
    def test_wait_is_in_end_to_end_but_separate_from_construction(self):
        r=lifecycle(fixture())[0]
        self.assertEqual(r["issueToCompleteWallMs"],400)
        self.assertEqual(r["phases"]["waiting_for_credits"]["wallMs"],140)
        self.assertEqual(r["phases"]["constructing"]["wallMs"],250)
        self.assertEqual(r["actualCost"],700)
    def test_mapping_checks_index_and_signature(self):
        run=fixture()
        self.assertEqual(match_native(run)["rows"][0]["status"],"matched")
        run.actions[0]["x"]=101
        self.assertEqual(match_native(run)["rows"][0]["status"],"unmatched")
    def test_ambiguity_and_duplicates_are_never_forced(self):
        run=fixture();run.actions*=2;run.events[-1]["nativeIndex"]=9
        self.assertEqual(match_native(run)["rows"][0]["status"],"ambiguous")
        run.events[-1]["nativeIndex"]=0;run.events.append(dict(run.events[-1]))
        self.assertTrue(all(r["status"]=="ambiguous" for r in match_native(run)["rows"]))
    def test_illegal_native_command_is_severe(self):
        run=fixture();run.actions.append(dict(tick=2,units=[1000],team=0,type="move"))
        r=plugin("violations")(run)
        self.assertTrue(r["violationEnteredReplay"])
        self.assertEqual(run.diagnostics[0]["severity"],"severe")
    def test_ab_intervals_and_cycle(self):
        r=timing(fixture())
        self.assertEqual(r["aToB"]["mean"],90)
        self.assertEqual(r["bToNextA"]["mean"],95)
        self.assertEqual(r["aToA"]["samples"][-1]["ms"],400)
        self.assertEqual(r["decisionCycles"]["mean"],200)
    def test_wake_measures_early_poll_and_late_wake(self):
        r=plugin("wake_alignment")(fixture())["rows"][0]
        self.assertEqual(r["pollsBeforeComplete"],1)
        self.assertEqual(r["wakeBeforeCompleteMs"],300)
        self.assertEqual(r["wakeAfterCompleteMs"],100)
    def test_rejected_and_superseded_are_not_successes(self):
        run=fixture();run.gateway[1]["status"]="rejected";run.events[-2]["event"]="rejected"
        self.assertEqual(lifecycle(run)[0]["result"],"rejected")
        self.assertEqual(plugin("wake_alignment")(run)["rows"],[])
        run.events[-2]["event"]="superseded"
        self.assertEqual(lifecycle(run)[0]["result"],"superseded")
    def test_income_keeps_decreases_and_unknown_causes(self):
        run=fixture();run.events=[dict(kind="income",tick=n,gameMs=16*n,wallMs=20*n,incomePerSecond=v,deltaIncome=d,cause=["unknown"],buildingIds=[]) for n,(v,d) in enumerate([(0,0),(8,8),(0,-8),(8,8)])]
        result=plugin("income_anchors")(run)
        self.assertEqual(result["count"],4);self.assertEqual(result["rows"][2]["deltaIncome"],-8)
        self.assertEqual(result["targetAnchor"],1)
    def test_all_outcomes_survive_offline_reporting(self):
        for outcome in ("success","timeout","error"):
            run=fixture();run.result={"outcome":outcome,"finalIncomePerSecond":8}
            self.assertEqual(plugin("outcome")(run)["outcome"],outcome)
    def test_stats_empty_and_outliers(self):
        self.assertIsNone(stats([])["mean"])
        self.assertEqual(stats([{"ms":x} for x in [1,1,1,1,100]])["outliers"],[{"ms":100}])

class GreedyTests(unittest.TestCase):
    def state(self):
        return dict(credits=700,incomePerSecond=8,generationPeriodTicks=40,builderMoveSpeed=.8,builderWarmupTicks=30,
                    economy=[dict(buildCost=700,buildWorkTicks=1000,incomePerSecond=8,upgradeCost=1400,upgradeWorkTicks=1666.667),
                             dict(incomePerSecond=12,upgradeCost=4000,upgradeWorkTicks=3333.334),dict(incomePerSecond=20)])
    def test_nearest_node_and_stable_id(self):
        r=[dict(resourceId="R2",occupied=False,pathDistance=100),dict(resourceId="R1",occupied=False,pathDistance=100)]
        first=choose(self.state(),dict(phase="idle"),r)
        self.assertEqual(first,choose(self.state(),dict(phase="idle"),list(reversed(r))))
        self.assertEqual(first["id"],"R1")
    def test_funds_wait_uses_real_generation_period(self):
        state=self.state();state["credits"]=0
        r=[dict(resourceId="R1",occupied=False,pathDistance=100)]
        c=choose(state,dict(phase="idle"),r)
        self.assertAlmostEqual(c["fundingSeconds"],700/12)
    def test_busy_builder_still_allows_independent_upgrades(self):
        s=self.state();s["credits"]=1400
        r=[dict(resourceId="R1",occupied=True,buildingId=1001,level=1,busy=False)]
        self.assertEqual(choose(s,dict(phase="constructing"),r)["kind"],"upgrade")
    def test_next_observation_tracks_remaining_work(self):
        s=self.state();b=dict(phase="constructing",resourceId="R1")
        def wait(progress):
            return next_event(s,b,[dict(resourceId="R1",occupied=True,buildProgress=progress)],None)
        self.assertAlmostEqual(wait(.2)-wait(.8),10)

if __name__=="__main__":
    unittest.main()
