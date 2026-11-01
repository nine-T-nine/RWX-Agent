"""Evaluate one agent or replay-only micro run. Python 3.11+, no third-party dependencies."""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE.parent))
from common.run import Run, read_json
from common.resim import resim
from common.native_actions import match_gateway
from common.report import render

def load_reference(run, choice, index_path):
    if choice == "none":
        return None
    kind = "human"
    if choice == "auto":
        index = read_json(index_path, {})
        entry = index.get("runs", {}).get(f'{run.meta["task"]}:{run.meta["seed"]}')
        if not entry:
            return None
        if isinstance(entry, dict):
            kind = entry.get("kind", "human")
            entry = entry["path"]
        path = index_path.parent / entry
    else:
        path = Path(choice)
    if path.is_dir():
        path /= "eval/metrics.json"
    result = read_json(path)
    if not result:
        raise ValueError("human baseline metrics are missing")
    identity = result.get("run", {})
    if identity.get("task") != run.meta["task"] or identity.get("seed") != run.meta["seed"]:
        raise ValueError("baseline task/seed does not match this run")
    return {"kind": kind, "path": str(path.resolve()), "metrics": {k: v for k, v in result["metrics"].items() if v["tier"] == "T1"}}

def evaluate(directory, resim_mode="auto", baseline="auto", *, dist=None, java=None, settings=None, baseline_index=None):
    directory = Path(directory).resolve()
    diagnostics = []
    if resim_mode == "force" or resim_mode == "auto" and not (directory / "engine/snapshots.jsonl").is_file():
        try:
            verification = resim(directory, dist, java)
            if not verification["consistent"]:
                diagnostics.append({"replayConsistency": verification})
        except Exception as exc:
            diagnostics.append({"resimError": str(exc)})
    run = Run(directory, settings)
    if run.playback.get("checksumWarnings"):
        diagnostics.append({"playbackChecksumWarnings": run.playback["checksumWarnings"],
                            "frames": run.playback.get("checksumFrames", []),
                            "note": "transient frame checksum only; outcome/final state/kill ticks matched"})
    if run.playback.get("warnings"):
        diagnostics.append({"playbackWarnings": run.playback["warnings"]})
    result = {"schema": "rwx-micro-metrics/1", "run": {k: run.meta[k] for k in ("case", "task", "seed", "track", "mode")},
              "metrics": {}, "diagnostics": diagnostics + run.diagnostics, "reference": None,
              "evidence": {"snapshots": len(run.snapshots), "events": len(run.events), "nativePlayerCommands": len(run.actions),
                           "gateway": run.has_gateway, "playback": run.playback,
                           # Only a re-sim leaves playback.json. liveSnapshots=true (or --resim skip)
                           # has no independent replay gate, so reports must say so.
                           "source": "re-sim" if (directory / "engine/playback.json").is_file() else "live/unverified",
                           "verified": bool(run.playback.get("consistent"))}}
    for file in sorted((CASE / "metrics").glob("*.py")):
        key, tier = file.stem, "unknown"
        try:
            spec = importlib.util.spec_from_file_location("micro_metric_" + file.stem, file)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            key, tier = module.ID, module.TIER
            value = module.compute(run)
        except Exception as exc:
            value = {"status": "ERROR", "reason": str(exc)}
        result["metrics"][key] = {"tier": tier, "sources": ["replay", "engine"] if tier == "T1" else ["gateway", "meta"], "value": value}
    result["mapping"] = match_gateway(run.requests(), run.actions) if run.has_gateway else {"status": "N/A", "reason": "no agent gateway"}
    if run.unknown_actions:
        result["diagnostics"].append({"unknownNativeActions": len(run.unknown_actions), "ticks": [a["tick"] for a in run.unknown_actions]})
    try:
        result["reference"] = load_reference(run, baseline, Path(baseline_index) if baseline_index else CASE / "baselines/index.json")
    except Exception as exc:
        result["diagnostics"].append({"baselineError": str(exc)})
    output = directory / "eval"
    output.mkdir(exist_ok=True)
    text, document = render(run, result)
    (output / "metrics.json").write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    (output / "report.txt").write_text(text, encoding="utf-8")
    (output / "report.html").write_text(document, encoding="utf-8")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--resim", choices=("auto", "force", "skip"), default="auto")
    parser.add_argument("--baseline", default="auto")
    parser.add_argument("--baseline-index", type=Path)
    parser.add_argument("--dist", type=Path)
    parser.add_argument("--java")
    parser.add_argument("--metrics-config", type=Path)
    args = parser.parse_args()
    result = evaluate(args.run, args.resim, args.baseline, dist=args.dist, java=args.java,
                      settings=read_json(args.metrics_config, {}) if args.metrics_config else None, baseline_index=args.baseline_index)
    print(json.dumps({"output": str(args.run / "eval"), "outcome": result["metrics"].get("outcome", {}).get("value"),
                      "diagnostics": result["diagnostics"]}, ensure_ascii=True))

if __name__ == "__main__":
    main()
