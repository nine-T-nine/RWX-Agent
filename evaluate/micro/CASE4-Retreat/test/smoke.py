"""CASE4-Retreat real-engine acceptance (build the headless archive first; Java + Python stdlib).

Scenarios: a deterministic baseline win with fog off, a shortened-budget timeout, a no-agent error,
a command-authority probe (violations rejected before execution) and layout reproducibility.
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
from evaluate import evaluate  # noqa: E402


class Client:
    def __init__(self, port_file):
        self.process = subprocess.Popen(
            [sys.executable, str(ROOT / "agent-operation/tools/rwx-cli.py"), "--port-file", str(port_file), "--stdio"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", cwd=ROOT)

    def call(self, command):
        self.process.stdin.write(command + "\n")
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


def probe(client):
    """Every call below must be rejected before execution or accepted as a legal case command."""
    state = client.call("rwx-micro-4-state --json")
    assert state["phase"] == "briefing", state
    assert "goal" in state and "turretsAlive" not in state, "state must not leak enemy composition"
    assert client.call("rwx-micro-4-begin").startswith("ok:")
    for command, marker in [
        ("rwx-micro-4-begin", "already_begun"),
        ("rwx-micro-2-state", "unknown command"),
        ("rwx-micro-3-state", "unknown command"),
        ("rwx-game-order-build --units 1000 --type turret --x 100 --y 100", "unknown command"),
        ("rwx-game-order-move --units 1008 --x 100 --y 100", "violation"),
        ("rwx-game-order-move --units 1000 --x 100 --y 100", "violation"),
        ("rwx-game-order-attack --units 9999 --target 1000", "unit_not_owned"),
        ("rwx-game-order-attack --units 1010 --target 424242", "target_not_visible"),
        ("rwx-game-order-stop --units 1010 --x 5 --y 5", "unexpected_option"),
        ("rwx-micro-4-state --bad", "expected --json"),
    ]:
        try:
            client.call(command)
        except RuntimeError as error:
            assert marker in str(error), f"{command} -> {error}"
        else:
            raise AssertionError(f"accepted a forbidden command: {command}")


def run_one(dist, tmp, java, name, task, seed, *, limit=None, agent=None, no_agent=False):
    scratch = tmp / name
    scratch.mkdir(parents=True, exist_ok=True)
    options = f"task={task},seed={seed},track=realtime,out={(CASE.parents[1] / 'output/micro/CASE4-Retreat').as_posix()}"
    if limit:
        options += f",gameLimitSeconds={limit}"
    options += ",startTimeoutSeconds=1" if no_agent else ",startTimeoutSeconds=90"
    command = [java, "-cp", str(dist / "lib/*"), "io.github.rwx.DesktopMain", "--headless", "--no-steam",
               "--evaluate", "CASE4-Retreat", "--case-args", options,
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
                if agent == "probe":
                    client = Client(scratch / "port")
                    probe(client)
                else:
                    subprocess.run([sys.executable, str(CASE / "baseline.py"), "--port-file", str(scratch / "port")],
                                   cwd=ROOT, timeout=2400, check=False)
            process.wait(timeout=2400)
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
    return (directory, json.loads((directory / "result.json").read_text(encoding="utf-8")),
            json.loads((directory / "meta.json").read_text(encoding="utf-8")), evaluate(directory))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--tmp", type=Path, default=ROOT / "agent-tmp/retreat-smoke")
    parser.add_argument("--java", default="java")
    parser.add_argument("--heavy", action="store_true", help="also run the fogged types")
    args = parser.parse_args()
    args.dist, args.tmp = args.dist.resolve(), args.tmp.resolve()

    directory, result, meta, metrics = run_one(args.dist, args.tmp, args.java, "win-1", "4_1_1", 1)
    assert result["outcome"] == "success", result
    assert metrics["metrics"]["turret_progress"]["value"]["totals"]["destroyed"] == meta["turretsByType"]["c_turret_t2_gun"] \
        + meta["turretsByType"]["c_turret_t1_lightning"], metrics["metrics"]["turret_progress"]
    assert metrics["metrics"]["violations"]["value"]["count"] == 0
    print(json.dumps({"name": "win-1", "run": str(directory), "outcome": result["outcome"],
                      "gameMs": result["gameMs"], "turrets": result["turretsTotal"]}), flush=True)

    _, timed_out, _, _ = run_one(args.dist, args.tmp, args.java, "timeout", "4_1_1", 2, limit=0.02)
    assert timed_out["outcome"] == "timeout", timed_out
    _, no_agent, _, _ = run_one(args.dist, args.tmp, args.java, "no-agent", "4_1_1", 3, no_agent=True)
    assert no_agent["outcome"] == "error", no_agent

    probe_run, probe_result, _, probe_metrics = run_one(args.dist, args.tmp, args.java, "probe", "4_1_1", 4,
                                                       limit=20, agent="probe")
    assert probe_result["outcome"] in ("timeout", "success", "defeat"), probe_result
    assert probe_metrics["metrics"]["violations"]["value"]["count"] >= 2, probe_metrics["metrics"]["violations"]
    print(json.dumps({"name": "probe", "run": str(probe_run), "violations":
                      probe_metrics["metrics"]["violations"]["value"]["byType"]}), flush=True)

    if args.heavy:
        _, fog_result, fog_meta, _ = run_one(args.dist, args.tmp, args.java, "fog-2", "4_2_1", 1)
        assert fog_meta["fogMode"] == 2, fog_meta["fogMode"]
        assert fog_result["outcome"] == "success", fog_result
        print(json.dumps({"name": "fog-2", "outcome": fog_result["outcome"], "gameMs": fog_result["gameMs"]}), flush=True)

    # Same task+seed must produce the same map and the same starting layout.
    _, _, repro_a, _ = run_one(args.dist, args.tmp, args.java, "repro-a", "4_2_2", 7, no_agent=True)
    _, _, repro_b, _ = run_one(args.dist, args.tmp, args.java, "repro-b", "4_2_2", 7, no_agent=True)
    for key in ("turrets", "repairBays", "tanks", "fogMode"):
        assert repro_a[key] == repro_b[key], f"layout mismatch: {key}"
    assert repro_a["map"]["sha256"] == repro_b["map"]["sha256"], "map hash mismatch"
    assert repro_a["fogMode"] == 2 and repro_a["turrets"] != run_one(
        args.dist, args.tmp, args.java, "repro-c", "4_2_2", 8, no_agent=True)[2]["turrets"]
    print("RETREAT_SMOKE_OK baselineWin=true timeoutError=true authorityRejected=true reproducibleLayout=true")

if __name__ == "__main__":
    main()

