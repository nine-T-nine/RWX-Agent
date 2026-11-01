"""Run the public-state Greedy baseline on several CASE2-LTE tasks, then evaluate each run."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys
import time

CASE = Path(__file__).resolve().parents[1]
ROOT = CASE.parents[2]
sys.path.insert(0, str(CASE))
from greedy import Client, play
from evaluate import evaluate


def run_one(dist, temp, java, task, seed):
    scratch = temp / task
    scratch.mkdir(parents=True, exist_ok=True)
    output = ROOT / "evaluate/output/micro/CASE2-LTE"
    options = f"task={task},seed={seed},track=realtime,out={output}"
    command = [java, "-cp", str(dist / "lib/*"), "io.github.rwx.DesktopMain", "--headless", "--no-steam",
               "--evaluate", "CASE2-LTE", "--case-args", options,
               "--data-dir=" + str(scratch), "--cli-port-file=" + str(scratch / "port")]
    with (scratch / "engine.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        client = None
        try:
            deadline = time.monotonic() + 120
            while "RWX_MICRO_READY" not in (scratch / "engine.log").read_text(encoding="utf-8", errors="replace"):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError((scratch / "engine.log").read_text(encoding="utf-8", errors="replace")[-4000:])
                time.sleep(.1)
            client = Client(scratch / "port")
            try:
                play(client)
            except (EOFError, BrokenPipeError):
                pass
            process.wait(timeout=1500)
        finally:
            if client:
                client.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    line = next(s for s in (scratch / "engine.log").read_text(encoding="utf-8").splitlines() if s.startswith("RWX_MICRO_RUN "))
    directory = Path(line[len("RWX_MICRO_RUN "):])
    result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
    metrics = evaluate(directory)
    instructions = metrics["metrics"]["instruction_lifecycle"]["value"]["rows"]
    return {"task": task, "seed": seed, "run": str(directory), "outcome": result["outcome"],
            "target": result["targetIncomePerSecond"], "income": result["finalIncomePerSecond"],
            "gameMs": result["gameMs"], "targetReachedGameMs": result.get("targetReachedGameMs"),
            "targetReachedWallMs": result.get("targetReachedWallMs"),
            "completed": sum(1 for i in instructions if i["result"] == "completed"),
            "diagnostics": metrics["diagnostics"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--tmp", type=Path, default=ROOT / "agent-tmp/lte-configs")
    parser.add_argument("--java", default="java")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--parallel", type=int, default=2)
    parser.add_argument("tasks", nargs="+", help="e.g. 2_4_1 2_5_2 2_6_3 2_7_4 2_8_5")
    args = parser.parse_args()
    args.dist = args.dist.resolve()
    args.tmp = args.tmp.resolve()
    with ThreadPoolExecutor(max_workers=max(1, args.parallel)) as pool:
        rows = list(pool.map(lambda task: run_one(args.dist, args.tmp, args.java, task, args.seed), args.tasks))
    args.tmp.mkdir(parents=True, exist_ok=True)
    (args.tmp / "summary.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    for row in rows:
        print(json.dumps(row, ensure_ascii=False), flush=True)
