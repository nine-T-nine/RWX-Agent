"""Public-state-only LTE greedy baseline. Sleeps to estimated events, never reads run evidence."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]


def choose(state, builder, resources):
    economy = state["economy"]
    income = state["incomePerSecond"] * 60 / state["generationPeriodTicks"]
    credits = state["credits"]
    # A build pays when its foundation appears. While the builder is still walking or warming up,
    # keep that money aside so an upgrade cannot starve the in-flight build.
    unpaid_build = builder["phase"] in {"accepted", "pathing", "moving", "arrived", "warming_up", "waiting_for_credits"}
    def funding(cost, reserved=0):
        available = credits - reserved
        return max(0, cost-available)/income if income else (0 if available >= cost else float("inf"))
    candidates = []
    empty = [r for r in resources if not r["occupied"] and r["pathDistance"] >= 0]
    if builder["phase"] == "idle" and empty:
        node = min(empty, key=lambda r: (r["pathDistance"], int(r["resourceId"][1:])))
        t = funding(economy[0]["buildCost"])+(node["pathDistance"]/state["builderMoveSpeed"]+state["builderWarmupTicks"]+economy[0]["buildWorkTicks"])/60
        candidates.append({"score": economy[0]["incomePerSecond"]/t, "seconds": t, "fundingSeconds": funding(economy[0]["buildCost"]),
                           "kind": "build", "id": node["resourceId"], "tie": (0, int(node["resourceId"][1:]))})
    for r in resources:
        if not r["occupied"] or r["busy"] or r["level"] >= 3:
            continue
        level = r["level"]
        wait = funding(economy[level-1]["upgradeCost"], economy[0]["buildCost"] if unpaid_build else 0)
        t = wait+economy[level-1]["upgradeWorkTicks"]/60
        candidates.append({"score": (economy[level]["incomePerSecond"]-economy[level-1]["incomePerSecond"])/t,
                           "seconds": t, "fundingSeconds": wait,
                           "kind": "upgrade", "id": r["buildingId"], "tie": (1, r["buildingId"])})
    return min(candidates, key=lambda c: (-c["score"], c["seconds"], c["tie"])) if candidates else None


def next_event(state, builder, resources, candidate):
    times = []
    if candidate and candidate["fundingSeconds"] > 0:
        times.append(candidate["fundingSeconds"])
    for r in resources:
        if r.get("occupied") and r["buildProgress"] < 1 and r["resourceId"] == builder.get("resourceId"):
            ticks = (1-r["buildProgress"])*state["economy"][0]["buildWorkTicks"]
            if builder["phase"] in {"warming_up", "arrived"}:
                ticks += state["builderWarmupTicks"]
            times.append(ticks/60)
        elif r.get("busy") and r.get("upgradeProgress") is not None:
            times.append((1-max(0,r["upgradeProgress"]))*state["economy"][r["level"]-1]["upgradeWorkTicks"]/60)
    if builder["phase"] in {"accepted", "pathing", "moving", "waiting_for_credits"}:
        r = next(r for r in resources if r["resourceId"] == builder["resourceId"])
        ticks = r["pathDistance"]/state["builderMoveSpeed"] + state["builderWarmupTicks"] + state["economy"][0]["buildWorkTicks"]
        income = state["incomePerSecond"]*60/state["generationPeriodTicks"]
        deficit = max(0,state["economy"][0]["buildCost"]-state["credits"])
        times.append(ticks/60 + (deficit/income if income else 0))
    finite = [t for t in times if t != float("inf")]
    if not finite:
        raise RuntimeError("No affordable action or observable completion event")
    return max(1/60, min(finite)) + 2/60


class Client:
    def __init__(self, port_file):
        self.process = subprocess.Popen([sys.executable, str(ROOT/"agent-operation/tools/rwx-cli.py"), "--port-file", str(port_file), "--stdio"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", cwd=ROOT)
    def call(self, command):
        self.process.stdin.write(command+"\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise EOFError("Case process ended; use result.json for outcome")
        reply = json.loads(line)
        if reply["error"]:
            raise RuntimeError(reply["reply"])
        data = reply["reply"]
        return json.loads(data) if data.startswith("{") else data
    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=5)
        self.process.stdout.close()


def play(client):
    state = client.call("rwx-micro-2-state --json")
    if state["phase"] == "briefing":
        client.call("rwx-micro-2-begin")
    while True:
        state = client.call("rwx-micro-2-state --json")
        if state["targetReached"] or state["phase"] == "ended":
            return
        builder = client.call("rwx-micro-2-builder --json")
        resources = client.call("rwx-micro-2-resources --json")["resources"]
        candidate = choose(state,builder,resources)
        if candidate and candidate["fundingSeconds"] == 0:
            kind = candidate["kind"]
            reply = client.call(f'rwx-micro-2-{kind} --{"resource" if kind == "build" else "building"} {candidate["id"]}')
            print(json.dumps({"choice": candidate, "reply": reply}), flush=True)
            # Acknowledgement is asynchronous; sleep until the earliest estimated active event.
            seconds = candidate["seconds"]
            others = [r for r in resources if r.get("busy")]
            if others:
                seconds = min(seconds,next_event(state,builder,resources,None))
            # Permit another independent affordable operation immediately using fresh public state.
            if any(r.get("occupied") and not r["busy"] and r["level"] < 3 and r.get("buildingId") != candidate["id"] for r in resources):
                continue
            if kind == "upgrade" and builder["phase"] == "idle" and any(not r["occupied"] for r in resources):
                continue
            time.sleep(seconds+2/60)
        else:
            time.sleep(next_event(state,builder,resources,candidate))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port-file", type=Path, required=True)
    args = parser.parse_args()
    client = Client(args.port_file)
    try:
        play(client)
    except (EOFError, BrokenPipeError, ConnectionError):
        print("Case connection closed; evaluate the run for authoritative outcome.", flush=True)
    finally:
        client.close()
