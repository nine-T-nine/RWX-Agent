"""Audit + clean evaluate/output/micro run dirs and their replays/<run>.replay twins.

Why this exists
---------------
A micro run is written to `evaluate/output/micro/<CASE>/<case>_<task>_<track>_s<seed>_<stamp>/`.
Re-running the same configuration produces a sibling directory that differs only in the timestamp,
so a few days of iteration leaves dozens of near-identical runs per configuration -- 156 runs across
27 configurations at the time this was written. Root `replays/` accumulates a `<run>.replay` twin for
each of them and keeps them after the run directory is gone, so it grows even faster.

Retention policy
----------------
Group runs by (case, task, track, seed) -- one *configuration*. Keep exactly one run per group:
  1. prefer runs that have both result.json and eval/report.txt (finished and scored),
  2. else the newest run that at least has result.json,
  3. never keep a run without result.json -- those are aborted or crashed launches.
"Newest" alone is not enough: the newest run of a group is sometimes the one that crashed.

`replays/<run>.replay` follows its run: keep the replay iff the run is kept.
`replays/[local] *.replay` are real player games and are never touched, and so is every other
replay that is not a micro run name (full-match replays are named after `evaluate/output/<match>/`
and are owned by `tools/match_materials.js`).
`<CASE>/reports/` (generated HTML summaries) is not a run and is never touched.

Usage
-----
    python evaluate/tools/cleanup_micro_output.py            # dry run, prints the plan
    python evaluate/tools/cleanup_micro_output.py --apply    # actually delete
"""
import json
import re
import shutil
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MICRO = ROOT / "evaluate" / "output" / "micro"
REPLAYS = ROOT / "replays"
NON_RUN = {"reports"}
# Micro run name: `<case>_<task>_<track>_s<seed>_<stamp>[-n]`; the case starts with `CASE<n>-`.
MICRO_RUN = re.compile(r"^CASE\d+-[A-Za-z0-9]+_\d+_\d+_\d+_(?:realtime|pause|human)_s\d+_\d{8}-\d{6}(?:-\d+)?$")

apply = "--apply" in sys.argv


def run_key(case, run_dir):
    """(case, task, track, seed) from meta.json, falling back to the directory name."""
    meta = run_dir / "meta.json"
    if meta.is_file():
        try:
            data = json.loads(meta.read_text(encoding="utf-8"))
            return (case, str(data["task"]), str(data["track"]), str(data["seed"]))
        except Exception:
            pass
    parts = run_dir.name.split("_")
    if len(parts) >= 5:
        return (case, parts[1] + "_" + parts[2], parts[3], parts[4])
    return (case, run_dir.name, "?", "?")


if not MICRO.is_dir():
    raise SystemExit(f"no such directory: {MICRO}")

runs = []
for case_dir in sorted(p for p in MICRO.iterdir() if p.is_dir()):
    for run_dir in sorted(p for p in case_dir.iterdir() if p.is_dir()):
        if run_dir.name in NON_RUN:
            continue
        runs.append(
            {
                "case": case_dir.name,
                "dir": run_dir,
                "name": run_dir.name,
                "key": run_key(case_dir.name, run_dir),
                "result": (run_dir / "result.json").is_file(),
                "eval": (run_dir / "eval" / "report.txt").is_file(),
            }
        )

groups = defaultdict(list)
for r in runs:
    groups[r["key"]].append(r)

keep, drop = [], []
for key, members in sorted(groups.items()):
    finished = sorted((m for m in members if m["result"] and m["eval"]), key=lambda m: m["name"], reverse=True)
    partial = sorted((m for m in members if m["result"]), key=lambda m: m["name"], reverse=True)
    winner = (finished or partial or [None])[0]
    for m in members:
        (keep if winner and m["name"] == winner["name"] else drop).append(m)

keep_names = {r["name"] for r in keep}

# Only micro run replays are ours to delete. A full-match replay is named after its
# `evaluate/output/<match>/` directory, and `[local] *.replay` files are real player games: both are
# somebody else's material, so they are counted as foreign and left alone.
matched, orphan, foreign = [], [], []
for f in sorted(REPLAYS.glob("*.replay")):
    if not MICRO_RUN.match(f.stem):
        foreign.append(f)
    elif f.stem in keep_names:
        matched.append(f)
    else:
        orphan.append(f)

print(f"runs: total={len(runs)} keep={len(keep)} drop={len(drop)}")
print(f"  of dropped, aborted (no result.json): {sum(1 for r in drop if not r['result'])}")
print(f"replays: kept-run={len(matched)} micro-to-remove={len(orphan)} not-micro={len(foreign)}")
print()
print("KEEP (one per configuration):")
for r in sorted(keep, key=lambda r: (r["case"], r["name"])):
    print(f"  {r['case']:<16} {r['name']}  eval={r['eval']}")

if not apply:
    print("\n[dry run] re-run with --apply to delete.")
    sys.exit(0)

removed_runs = removed_replays = 0
for r in drop:
    shutil.rmtree(r["dir"])
    removed_runs += 1
for f in orphan:
    f.unlink()
    removed_replays += 1
print(f"\nDELETED {removed_runs} run dirs, {removed_replays} root replays.")
