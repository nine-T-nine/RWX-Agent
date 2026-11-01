"""CASE3-Transport real-engine acceptance (build the headless archive first; Java + Python stdlib).

Scenarios: a deterministic baseline win, a shortened-budget timeout, a no-agent error, a
command-authority probe (violations rejected before execution) and layout reproducibility.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

CASE = Path(__file__).resolve().parents[1]
ROOT = CASE.parents[2]
sys.path.insert(0, str(CASE))
sys.path.insert(0, str(CASE / "baseline"))
from common import Client, try_call  # noqa: E402
from evaluate import evaluate  # noqa: E402


def run_one(dist, tmp, java, name, task, seed, *, limit=None, agent=None, no_agent=False):
    scratch = tmp / name
    scratch.mkdir(parents=True, exist_ok=True)
    options = f"task={task},seed={seed},track=realtime,out={(CASE.parents[1] / 'output/micro/CASE3-Transport').as_posix()}"
    if limit:
        options += f",gameLimitSeconds={limit}"
    if no_agent:
        options += ",startTimeoutSeconds=1"
    command = [java, "-cp", str(dist / "lib/*"), "io.github.rwx.DesktopMain", "--headless", "--no-steam",
               "--evaluate", "CASE3-Transport", "--case-args", options,
               "--data-dir=" + str(scratch), "--cli-port-file=" + str(scratch / "port")]
    log = scratch / "engine.log"
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        client = None
        try:
            deadline = time.monotonic() + 120
            while "RWX_MICRO_READY" not in log.read_text(encoding="utf-8", errors="replace"):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(log.read_text(encoding="utf-8", errors="replace")[-4000:])
                time.sleep(.2)
            if not no_agent:
                client = Client(scratch / "port")
                if agent == "probe":
                    probe(client)
                else:
                    baseline = CASE / "baseline" / f"type{task[2]}.py"
                    try:
                        subprocess.run([sys.executable, str(baseline), "--port-file", str(scratch / "port")],
                                       cwd=ROOT, timeout=1800, check=False)
                    except subprocess.TimeoutExpired:
                        pass
            process.wait(timeout=1800)
        finally:
            if client:
                client.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
    text = log.read_text(encoding="utf-8", errors="replace")
    line = next((row for row in text.splitlines() if row.startswith("RWX_MICRO_RUN ")), None)
    if not line:
        raise RuntimeError("no run directory: " + text[-2000:])
    directory = Path(line[len("RWX_MICRO_RUN "):])
    return directory, json.loads((directory / "result.json").read_text(encoding="utf-8")), \
        json.loads((directory / "meta.json").read_text(encoding="utf-8")), evaluate(directory)


def probe(client):
    """Every call below must be rejected before execution or accepted as a legal case command."""
    state = client.call("rwx-micro-3-state --json")
    assert state["phase"] in ("running", "ended"), state
    for command, marker in [
        ("rwx-game-order-move --units 1007 --x 100 --y 100", "unknown command"),
        ("rwx-game-order-attack --units 1007 --target 1000", "unknown command"),
        ("rwx-micro-2-begin", "unknown command"),
        ("rwx-micro-3-produce --factory 1000 --unit dropship", "violation"),
        ("rwx-micro-3-load --transport 1007 --units 1000", "violation"),
        ("rwx-micro-3-unload --transport 1000", "unknown_transport"),
        ("rwx-micro-3-move --transport 1007 --x 999999 --y 0", "invalid_destination"),
        ("rwx-micro-3-state --bad", "expected --json"),
    ]:
        try:
            client.call(command)
        except RuntimeError as error:
            assert marker in str(error), f"{command} -> {error}"
        else:
            raise AssertionError(f"accepted a forbidden command: {command}")
    assert try_call(client, "rwx-micro-3-produce --factory 1000 --unit heavyTank") is not None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--tmp", type=Path, default=ROOT / "agent-tmp/transport-smoke")
    parser.add_argument("--java", default="java")
    parser.add_argument("--heavy", action="store_true", help="also run the experimental transport type")
    args = parser.parse_args()
    args.dist, args.tmp = args.dist.resolve(), args.tmp.resolve()

    directory, result, meta, metrics = run_one(args.dist, args.tmp, args.java, "win-1", "3_1_1", 1)
    assert result["outcome"] == "success", result
    assert metrics["metrics"]["delivery"]["value"]["totals"]["unitsUnloaded"] >= 8
    assert metrics["metrics"]["violations"]["value"]["count"] == 0
    print(json.dumps({"name": "win-1", "run": str(directory), "outcome": result["outcome"],
                      "gameMs": result["gameMs"], "delivered": result["deliveredCounts"]}), flush=True)

    directory, result, meta2, metrics = run_one(args.dist, args.tmp, args.java, "win-2", "3_2_1", 1)
    assert result["outcome"] == "success", result
    print(json.dumps({"name": "win-2", "run": str(directory), "outcome": result["outcome"],
                      "gameMs": result["gameMs"]}), flush=True)

    _, timed_out, _, _ = run_one(args.dist, args.tmp, args.java, "timeout", "3_1_1", 2, limit=0.02)
    assert timed_out["outcome"] == "timeout", timed_out
    _, no_agent, _, _ = run_one(args.dist, args.tmp, args.java, "no-agent", "3_1_1", 3, no_agent=True)
    assert no_agent["outcome"] == "error", no_agent

    probe_run, probe_result, _, probe_metrics = run_one(args.dist, args.tmp, args.java, "probe", "3_1_1", 4,
                                                       limit=25, agent="probe")
    violations = json.loads((probe_run / "violations.jsonl").read_text(encoding="utf-8").strip().splitlines()[0])
    assert violations["rejected"] is True and violations["violationType"], violations
    assert probe_metrics["metrics"]["violations"]["value"]["count"] >= 2, probe_metrics["metrics"]["violations"]
    print(json.dumps({"name": "probe", "run": str(probe_run), "outcome": probe_result["outcome"],
                      "violations": probe_metrics["metrics"]["violations"]["value"]["byType"]}), flush=True)

    if args.heavy:
        directory, result, _, _ = run_one(args.dist, args.tmp, args.java, "win-4", "3_4_1", 1)
        assert result["outcome"] == "success", result
        print(json.dumps({"name": "win-4", "run": str(directory), "outcome": result["outcome"]}), flush=True)

    # Same task+seed must produce the same map and the same starting layout.
    _, _, repro_a, _ = run_one(args.dist, args.tmp, args.java, "repro-a", "3_1_1", 7, no_agent=True)
    _, _, repro_b, _ = run_one(args.dist, args.tmp, args.java, "repro-b", "3_1_1", 7, no_agent=True)
    for key in ("targetIslandTiles", "baseIslandTiles", "targetIslandArea", "baseIslandArea", "units"):
        assert repro_a[key] == repro_b[key], f"layout mismatch: {key}"
    assert repro_a["map"]["sha256"] == repro_b["map"]["sha256"], "map hash mismatch"
    assert repro_a["targetIslandArea"] == 400 and repro_a["baseIslandArea"] == 625, repro_a["targetIslandArea"]
    print("TRANSPORT_SMOKE_OK baselineWin=true timeoutError=true authorityRejected=true reproducibleLayout=true")


if __name__ == "__main__":
    main()
