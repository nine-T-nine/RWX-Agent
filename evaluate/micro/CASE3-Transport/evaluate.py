"""CASE3-Transport offline metrics runner and deduction score (Python 3.11+, stdlib only).

The engine only writes facts; every number below is derived here from one run directory
(meta/result/gateway/engine/violations). `score.py` turns the same evidence into the capped
deductions of `score_design.md` under the separate top-level `score` key.
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
from _shared import Case3Run, read_json  # noqa: E402
from score import compute as compute_score  # noqa: E402


def evaluate(directory):
    run = Case3Run(directory)
    result = {"schema": "rwx-micro-transport-metrics/1",
              "run": {key: run.meta.get(key) for key in ("case", "task", "seed", "track", "mode")},
              "metrics": {}, "diagnostics": [],
              "evidence": {"source": "gateway + engine evidence + replay",
                           "gatewayRequests": len(run.gateway), "productionEvents": len(run.production),
                           "transportEvents": len(run.transport), "goalEvents": len(run.goal),
                           "violations": len(run.violations)}}
    for file in sorted((CASE / "metrics").glob("*.py")):
        if file.stem.startswith("_"):
            continue
        spec = importlib.util.spec_from_file_location("transport_" + file.stem, file)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        try:
            value = module.compute(run)
        except Exception as exc:  # a broken plugin must not hide the rest of the evidence
            value = {"status": "ERROR", "reason": f"{type(exc).__name__}: {exc}"}
        result["metrics"][module.ID] = {"tier": module.TIER, "sources": module.SOURCES, "value": value}
    if run.result.get("outcome") == "error":
        result["diagnostics"].append({"severity": "severe", "reason": "engine_error"})
    if any(row.get("status") == "rejected" for row in run.gateway) and not run.violations:
        result["diagnostics"].append({"severity": "severe", "reason": "native_authority_mismatch"})
    try:
        result["score"] = compute_score(run)
    except Exception as exc:  # a scoring bug must not hide the raw metrics
        result["score"] = {"schema": "rwx-micro-score/1", "version": "case3-score-v1", "status": "ERROR",
                           "reason": f"{type(exc).__name__}: {exc}"}
        result["diagnostics"].append({"severity": "severe", "reason": "score_error", "detail": str(exc)})
    output = run.directory / "eval"
    output.mkdir(exist_ok=True)
    body = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    (output / "metrics.json").write_text(body, encoding="utf-8")
    (output / "report.txt").write_text(body, encoding="utf-8")
    (output / "report.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>CASE3-Transport</title>'
        '<style>body{font:14px system-ui;max-width:1100px;margin:30px auto}pre{white-space:pre-wrap}</style>'
        '<h1>CASE3-Transport</h1><pre>' + html.escape(body) + '</pre>', encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="run directory or a parent holding several runs")
    parser.add_argument("--json", action="store_true", help="print the full metrics document")
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
        delivery = metrics["delivery"]["value"]
        transport = metrics["transport_utilization"]["value"]
        damage = metrics["transport_damage"]["value"]
        score = document["score"]
        print(f'{document["run"]["task"]} s{document["run"]["seed"]}: {outcome.get("outcome")} '
              f'({outcome.get("endReason")}) game={outcome.get("gameMs")}ms '
              f'delivered={outcome.get("delivered")} rate={outcome.get("deliveryRate")} '
              f'hits={damage["totals"]["hits"]} hpLost={damage["totals"]["hpLost"]} '
              f'loads={transport["totals"]["loadCommands"]} emptyMoves={transport["totals"]["emptyMoves"]} '
              f'violations={metrics["violations"]["value"]["count"]} '
              f'score={score.get("total")} (deduction {score.get("deduction")}; '
              + ", ".join(f'{item["id"]}={item.get("points")}' for item in score.get("items", [])) + ")")
        for need in delivery["types"]:
            print(f'  {need["unitType"]}: {need["delivered"]}/{need["required"]} '
                  f'first={need["firstDeliveryGameMs"]} last={need["lastDeliveryGameMs"]}')


if __name__ == "__main__":
    main()
