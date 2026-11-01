"""Consolidated real-engine acceptance. Build the headless archive first; Java 25 and Python stdlib."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys
import time

CASE=Path(__file__).resolve().parents[1]
ROOT=CASE.parents[2]
sys.path.insert(0,str(CASE))
from greedy import Client, play
from evaluate import evaluate


def task_nodes(task):
    return int(task.split("_")[1])


def map_size(nodes):
    return math.ceil(math.sqrt(nodes*20*20/0.7 + math.pi*15*15))


def run_one(dist, temp, java, name, task, seed, *, limit=None, no_agent=False, probe=False):
    scratch=temp/name;scratch.mkdir(parents=True,exist_ok=True)
    output=ROOT/"evaluate/output/micro/CASE2-LTE"
    options=f"task={task},seed={seed},track=realtime,out={output}"
    if limit:
        options+=f",gameLimitSeconds={limit}"
    if no_agent:
        options+=",startTimeoutSeconds=1"
    cp=str(dist/"lib/*")
    if SOURCE_CLASSES:cp=str(SOURCE_CLASSES)+";"+cp
    main="io.github.rwx.DesktopMain"
    if probe:
        cp=str(temp/"classes")+";"+cp;main="NativeAuthorityProbe"
    command=[java,"-cp",cp,main,"--headless","--no-steam","--evaluate","CASE2-LTE","--case-args",options,
             "--data-dir="+str(scratch),"--cli-port-file="+str(scratch/"port")]
    with (scratch/"engine.log").open("w",encoding="utf-8") as log:
        process=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        client=None
        try:
            deadline=time.monotonic()+90
            while "RWX_MICRO_READY" not in (scratch/"engine.log").read_text(encoding="utf-8",errors="replace"):
                if process.poll() is not None or time.monotonic()>deadline:
                    raise RuntimeError((scratch/"engine.log").read_text(encoding="utf-8",errors="replace")[-5000:])
                time.sleep(.1)
            if probe:
                # The native probe asserts untouched state, so let it finish before the game starts.
                deadline=time.monotonic()+60
                while "RWX_LTE_AUTHORITY_VERIFIED" not in (scratch/"engine.log").read_text(encoding="utf-8",errors="replace"):
                    if process.poll() is not None or time.monotonic()>deadline:
                        raise RuntimeError((scratch/"engine.log").read_text(encoding="utf-8",errors="replace")[-5000:])
                    time.sleep(.1)
            if not no_agent:
                client=Client(scratch/"port")
                # Ordinary errors are distinct from behavioral violations.
                if probe:
                    client.call("rwx-micro-2-begin")
                    for cmd in ["rwx-game-order-move --units 1000 --x 5 --y 5","rwx-game-order-reclaim --units 1000 --target 1000",
                                "rwx-micro-2-build --type turret --resource R1", "rwx-micro-2-upgrade --building 9999"]:
                        try:client.call(cmd)
                        except RuntimeError as e:assert "violation" in str(e),str(e)
                        else:raise AssertionError("Forbidden CLI accepted")
                    try:client.call("rwx-micro-2-build --resource missing")
                    except RuntimeError as e:assert "unknown_resource" in str(e),str(e)
                    else:raise AssertionError("Unknown resource accepted")
                if limit:
                    client.call("rwx-micro-2-begin")
                else:
                    try:play(client)
                    except (EOFError,BrokenPipeError):pass
            process.wait(timeout=600)
        finally:
            if client:client.close()
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill();process.wait(timeout=5)
    # Each run's stdout is the authoritative directory (concurrent runs may share task+seed).
    line=next(s for s in (scratch/"engine.log").read_text(encoding="utf-8").splitlines() if s.startswith("RWX_MICRO_RUN "))
    directory=Path(line[len("RWX_MICRO_RUN "):])
    result=json.loads((directory/"result.json").read_text(encoding="utf-8"))
    metrics=evaluate(directory)
    expected="error" if no_agent else "timeout" if limit else "success"
    assert result["outcome"]==expected,result
    assert all(m["value"]["status"]=="OK" for m in metrics["metrics"].values()),metrics
    assert not metrics["diagnostics"],metrics["diagnostics"]
    meta=json.loads((directory/"meta.json").read_text(encoding="utf-8"))
    assert [r["type"] for r in meta["units"]]==["builder"]
    assert meta["resourceCount"]==task_nodes(meta["task"])
    assert meta["mapSize"]==map_size(meta["resourceCount"])
    if probe:
        assert metrics["metrics"]["violations"]["value"]["count"]>=20
        assert "RWX_LTE_AUTHORITY_VERIFIED" in (scratch/"engine.log").read_text(encoding="utf-8")
    if not no_agent and not limit:
        anchors=metrics["metrics"]["income_anchors"]["value"]["rows"]
        assert anchors[0]["incomePerSecond"]==0,anchors
        assert anchors[-1]["incomePerSecond"]>=result["targetIncomePerSecond"],anchors
        assert all(b["incomePerSecond"]>=a["incomePerSecond"] for a,b in zip(anchors,anchors[1:])),anchors
        instructions=metrics["metrics"]["instruction_lifecycle"]["value"]["rows"]
        completed=[i for i in instructions if i["result"]=="completed"]
        assert completed,instructions
        assert all(i["actualCost"] in (700,1400,4000) for i in completed),completed
        assert all(i["mapping"]["status"]=="matched" for i in completed),completed
        assert result["finalIncomePerSecond"]==result["targetIncomePerSecond"],result
    print(json.dumps({"name":name,"run":str(directory),"outcome":result["outcome"],"gameMs":result["gameMs"],"income":result["finalIncomePerSecond"]}),flush=True)
    return meta,metrics,directory


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dist",type=Path,required=True)
    p.add_argument("--tmp",type=Path,default=ROOT/"agent-tmp/lte-smoke")
    p.add_argument("--java",default="java")
    p.add_argument("--javac",default="javac")
    p.add_argument("--classes",type=Path,help="Optional freshly compiled development classes ahead of the distribution")
    args=p.parse_args();args.dist=args.dist.resolve();args.tmp=args.tmp.resolve()
    SOURCE_CLASSES=args.classes.resolve() if args.classes else None
    (args.tmp/"classes").mkdir(parents=True,exist_ok=True)
    subprocess.run([args.javac,"-cp",str(args.dist/"lib/*"),"-d",str(args.tmp/"classes"),str(CASE/"test/NativeAuthorityProbe.java")],check=True)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first=pool.submit(run_one,args.dist,args.tmp,args.java,"upgrade-a","2_4_2",17,probe=True)
        second=pool.submit(run_one,args.dist,args.tmp,args.java,"upgrade-b","2_4_2",17)
        a,b=first.result(),second.result()
    for key in ["resourceNodes","builder","initialCredits","resourceCount","targetIncomePerSecond","economy","allowedActionIds","forbiddenActionIds"]:
        assert a[0][key]==b[0][key],key
    assert a[0]["map"]["sha256"]==b[0]["map"]["sha256"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs=[pool.submit(run_one,args.dist,args.tmp,args.java,"timeout","2_4_1",18,limit=.032),
              pool.submit(run_one,args.dist,args.tmp,args.java,"no-agent","2_4_1",19,no_agent=True)]
        for job in jobs:job.result()
    print("LTE_SMOKE_OK reproducibleLayout=true nativeBypassRejected=true upgradePath=true successTimeoutError=true")
