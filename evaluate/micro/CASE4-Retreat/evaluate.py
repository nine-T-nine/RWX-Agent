"""CASE4-Retreat offline metrics runner and deduction score (Python 3.11+, stdlib only).

The engine only writes facts; every number below is derived here from one run directory. Metrics
stay factual and `score.py` turns the same evidence into the capped deductions of
`score_design.md` under the separate top-level `score` key.
"""
from __future__ import annotations
import argparse
import html
import importlib.util
import json
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
sys.path.insert(0, str(CASE / "metrics"))
sys.path.insert(0, str(CASE))
from _shared import Case4Run  # noqa: E402
from score import compute as compute_score  # noqa: E402


def evaluate(directory):
    run = Case4Run(directory)
    result = {"schema": "rwx-micro-retreat-metrics/1",
              "run": {key: run.meta.get(key) for key in ("case", "task", "seed", "track", "mode")},
              "metrics": {}, "diagnostics": [],
              "evidence": {"source": "gateway + engine battle evidence + replay",
                           "gatewayRequests": len(run.gateway), "turretDamage": len(run.turret_damage),
                           "unitDamage": len(run.unit_damage), "destroyed": len(run.destroyed()),
                           "repairSessions": len(run.repairs), "tankState": len(run.tank_state),
                           "violations": len(run.violations)}}
    for file in sorted((CASE / "metrics").glob("*.py")):
        if file.stem.startswith("_"):
            continue
        spec = importlib.util.spec_from_file_location("retreat_" + file.stem, file)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        try:
            value = module.compute(run)
        except Exception as exc:
            value = {"status": "ERROR", "reason": f"{type(exc).__name__}: {exc}"}
        result["metrics"][module.ID] = {"tier": module.TIER, "sources": module.SOURCES, "value": value}
    if run.result.get("outcome") == "error":
        result["diagnostics"].append({"severity": "severe", "reason": "engine_error"})
    if any(row.get("status") == "rejected" for row in run.gateway) and not run.violations:
        result["diagnostics"].append({"severity": "severe", "reason": "native_authority_mismatch"})
    try:
        result["score"] = compute_score(run)
    except Exception as exc:  # a scoring bug must not hide the raw metrics
        result["score"] = {"schema": "rwx-micro-score/1", "version": "case4-score-v1", "status": "ERROR",
                           "reason": f"{type(exc).__name__}: {exc}"}
        result["diagnostics"].append({"severity": "severe", "reason": "score_error", "detail": str(exc)})
    output = run.directory / "eval"
    output.mkdir(exist_ok=True)
    body = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    (output / "metrics.json").write_text(body, encoding="utf-8")
    (output / "report.txt").write_text(body, encoding="utf-8")
    (output / "report.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>CASE4-Retreat</title>'
        '<style>body{font:14px system-ui;max-width:1100px;margin:30px auto}pre{white-space:pre-wrap}</style>'
        '<h1>CASE4-Retreat</h1><pre>' + html.escape(body) + '</pre>', encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="run directory or a parent holding several runs")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    runs = [args.run] if (args.run / "meta.json").exists() else sorted(
        path for path in args.run.iterdir() if (path / "meta.json").exists())
    documents = [evaluate(path) for path in runs]
    if args.json:
        print(json.dumps(documents, ensure_ascii=False, indent=2, allow_nan=False))
        return
    for document in documents:
        metrics = document["metrics"]
        outcome = metrics["outcome"]["value"]
        progress = metrics["turret_progress"]["value"]
        damage = metrics["battle_damage"]["value"]
        repair = metrics["repair_sessions"]["value"]
        score = document.get("score") or {}
        items = " ".join(f'{item["id"]}={item.get("points")}' for item in score.get("items", []))
        print(f'{document["run"]["task"]} s{document["run"]["seed"]}: {outcome.get("outcome")} '
              f'({outcome.get("endReason")}) game={outcome.get("gameMs")}ms '
              f'turrets={progress["totals"]["destroyed"]}/{progress["totals"]["turrets"]} '
              f'tankHits={damage["totals"]["hits"]} hpLost={damage["totals"]["hpLost"]} '
              f'tanksLost={damage["totals"]["tanksLost"]} repairs={repair["count"]} '
              f'healed={repair["totalHealed"]} violations={metrics["violations"]["value"]["count"]} '
              f'score={score.get("total")} (deduction {score.get("deduction")}; {items})')


if __name__ == "__main__":
    main()
