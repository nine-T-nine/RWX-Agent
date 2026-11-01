"""Offline LTE evidence reader and metrics plugin runner (Python 3.11+, stdlib only)."""
from __future__ import annotations
import argparse
import html
import importlib.util
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE.parent))
sys.path.insert(0, str(CASE / "metrics"))
from common.run import read_json, read_lines, Replay, write_outputs


class LteRun:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.meta = read_json(self.directory / "meta.json", {})
        if self.meta.get("case") != "CASE2-LTE":
            raise ValueError("Expected CASE2-LTE meta.json")
        self.result = read_json(self.directory / "result.json", {})
        self.gateway = sorted((r for r in read_lines(self.directory / "gateway.jsonl") if r["kind"] == "request"),
                              key=lambda r: (r["wallRecvMs"], r["seq"]))
        self.events = read_lines(self.directory / "engine/lifecycle.jsonl")
        self.violations = read_lines(self.directory / "violations.jsonl")
        self.diagnostics = []
        replay_file = self.directory / self.meta.get("replay", "match.replay")
        self.has_replay = replay_file.is_file()
        self.actions = []
        if self.has_replay:
            replay = Replay.load(str(replay_file), strict=True)
            if not replay.ok:
                self.diagnostics.append({"severity": "severe", "reason": "incomplete_replay"})
            self.actions = replay.order_rows()
            write_outputs(replay, str(self.directory / "replay"), ("jsonl", "summary"), clock=True)
        else:
            self.diagnostics.append({"severity": "severe", "reason": "missing_replay"})


def evaluate(directory):
    run = LteRun(directory)
    result = {"schema": "rwx-micro-lte-metrics/2", "run": {k: run.meta.get(k) for k in ("case", "task", "seed", "track", "mode")},
              "metrics": {}, "diagnostics": run.diagnostics, "reference": None,
              "evidence": {"source": "live lifecycle + native replay", "independentResimulation": False,
                           "gatewayRequests": len(run.gateway), "events": len(run.events), "nativeCommands": len(run.actions)}}
    for file in sorted((CASE / "metrics").glob("*.py")):
        if file.stem.startswith("_"):
            continue
        spec = importlib.util.spec_from_file_location("lte_" + file.stem, file)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        try:
            value = module.compute(run)
        except Exception as exc:
            value = {"status": "ERROR", "reason": str(exc)}
        result["metrics"][module.ID] = {"tier": module.TIER, "sources": module.SOURCES, "value": value}
    output = run.directory / "eval"
    output.mkdir(exist_ok=True)
    body = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    (output / "metrics.json").write_text(body, encoding="utf-8")
    (output / "report.txt").write_text(body, encoding="utf-8")
    (output / "report.html").write_text('<!doctype html><meta charset="utf-8"><title>CASE2-LTE</title>'
                                       '<style>body{font:14px system-ui;max-width:1100px;margin:30px auto}pre{white-space:pre-wrap}</style>'
                                       '<h1>CASE2-LTE</h1><pre>' + html.escape(body) + '</pre>', encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    result = evaluate(args.run)
    print(json.dumps({"output": str(args.run / "eval"), "diagnostics": result["diagnostics"]}, ensure_ascii=True))
