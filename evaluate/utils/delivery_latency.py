#!/usr/bin/env python3
"""Delivery-latency report for RWX `delivery.jsonl` traces.

The trace is written by `RwxDeliveryTrace` and lives next to the run it describes:

    <micro run dir>/delivery.jsonl        (evaluation cases)
    <rwx.evaluate.dir>/delivery.jsonl     (--evaluate matches)

Each line is one record:

    {"kind":"env",     "wall":..., "game":..., "speed":"realtime", "delivery":"l1", "map":"..."}
    {"kind":"deliver", "channel":"auto", "session":"default", "seq":12, "type":"damaged",
                       "produced":{"wall":...,"game":...}, "delivered":{"wall":...,"game":...}}
    {"kind":"command", "channel":"state", "command":"rwx-game-state", "session":"default",
                       "received":{"wall":...,"game":...}, "applied":{"wall":...,"game":...}}

Three segments are reported, and they measure different owners:

    detect -> deliver   produced.wall    -> delivered.wall    (environment pipeline)
    deliver -> decide   delivered.wall   -> received.wall     (harness + model; APPROXIMATE)
    decide -> execute   received.wall    -> applied.wall      (transport + game-thread hand-off)

`deliver -> decide` is only an approximation: it pairs a delivery with the *next* command of the
same session, which may be about something else entirely, and it includes the model's thinking
time as well as any harness scheduling. Read the distribution, never a single sample.

`applied` is the moment the reply was ready, not the moment the effect was observed
(`appliedBound:"reply"` in the record). For every non-blocking command the two are the same within
a millisecond; `rwx-game-step` overstates it by its own duration,
so they are excluded from the `decide -> execute` distribution by default.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

# Commands whose reply time is dominated by their own intentional blocking.
BLOCKING_COMMANDS = {"rwx-game-step"}


def records(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f"{path}:{number}: not JSON ({error})") from error
            if isinstance(row, dict) and "kind" in row:
                yield row


def percentile(values: List[float], fraction: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = fraction * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    weight = position - low
    return ordered[low] * (1 - weight) + ordered[high] * weight


def summary(values: List[float]) -> Dict[str, Any]:
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "min": round(min(values), 1),
        "p50": round(percentile(values, 0.50) or 0.0, 1),
        "p90": round(percentile(values, 0.90) or 0.0, 1),
        "max": round(max(values), 1),
        "mean": round(sum(values) / len(values), 1),
    }


def analyse(path: Path, include_blocking: bool) -> Dict[str, Any]:
    rows = list(records(path))
    environment = next((row for row in rows if row.get("kind") == "env"), {})
    deliveries = [row for row in rows if row.get("kind") == "deliver"]
    commands = [row for row in rows if row.get("kind") == "command"]

    detect: List[float] = []
    detect_game: List[float] = []
    for row in deliveries:
        produced = row.get("produced") or {}
        delivered = row.get("delivered") or {}
        wall = delivered.get("wall", 0) - produced.get("wall", 0)
        if produced.get("wall") and wall >= 0:
            detect.append(float(wall))
        game = delivered.get("game", 0) - produced.get("game", 0)
        if game >= 0:
            detect_game.append(float(game))

    # Pair each delivery with the next command of the same session. A delivery that no command ever
    # follows is not a decision gap, it is the end of the run.
    ordered = sorted(
        (row for row in rows if row.get("kind") in ("deliver", "command")),
        key=lambda row: (row.get("delivered") or row.get("received") or {}).get("wall", 0),
    )
    pending: Dict[str, List[float]] = {}
    decide: List[float] = []
    for row in ordered:
        session = row.get("session") or "default"
        if row.get("kind") == "deliver":
            delivered = (row.get("delivered") or {}).get("wall", 0)
            if delivered:
                pending.setdefault(session, []).append(float(delivered))
        else:
            received = (row.get("received") or {}).get("wall", 0)
            stamps = pending.get(session) or []
            if received and stamps:
                # The first command after the batch of deliveries is the decision this batch caused.
                decide.append(max(0.0, received - min(stamps)))
                pending[session] = []

    execute: List[float] = []
    excluded = 0
    for row in commands:
        if not include_blocking and row.get("command") in BLOCKING_COMMANDS:
            excluded += 1
            continue
        received = (row.get("received") or {}).get("wall", 0)
        applied = (row.get("applied") or {}).get("wall", 0)
        if received and applied and applied >= received:
            execute.append(float(applied - received))

    by_channel: Dict[str, int] = {}
    for row in commands:
        channel = row.get("channel") or "none"
        by_channel[channel] = by_channel.get(channel, 0) + 1
    by_type: Dict[str, int] = {}
    for row in deliveries:
        key = row.get("type") or "unknown"
        by_type[key] = by_type.get(key, 0) + 1

    return {
        "trace": str(path),
        "environment": {
            "speed": environment.get("speed"),
            "delivery": environment.get("delivery"),
            "map": environment.get("map"),
        },
        "counts": {
            "deliveries": len(deliveries),
            "commands": len(commands),
            "failedCommands": sum(1 for row in commands if row.get("failed")),
            "excludedBlockingCommands": excluded,
        },
        "deliveredByType": dict(sorted(by_type.items())),
        "commandsByChannel": dict(sorted(by_channel.items())),
        "detectToDeliverMs": summary(detect),
        "detectToDeliverGameMs": summary(detect_game),
        "deliverToDecideMs": summary(decide),
        "decideToExecuteMs": summary(execute),
        "note": "deliverToDecide pairs a delivery with the next command of the same session; "
                "it is an approximation of model latency, not a measurement of it.",
    }


def render(report: Dict[str, Any]) -> str:
    environment = report["environment"]
    counts = report["counts"]
    lines = [
        f"trace: {report['trace']}",
        f"environment: speed={environment.get('speed')} delivery={environment.get('delivery')}"
        f" map={environment.get('map')}",
        f"deliveries={counts['deliveries']} commands={counts['commands']}"
        f" failed={counts['failedCommands']} excludedBlocking={counts['excludedBlockingCommands']}",
        "",
    ]

    def block(title: str, key: str) -> None:
        stats = report[key]
        if not stats.get("count"):
            lines.append(f"{title}: (no samples)")
            return
        lines.append(
            f"{title}: n={stats['count']} min={stats['min']} p50={stats['p50']}"
            f" p90={stats['p90']} max={stats['max']} mean={stats['mean']} (ms)"
        )

    block("detect -> deliver (wall)", "detectToDeliverMs")
    block("detect -> deliver (game)", "detectToDeliverGameMs")
    block("deliver -> decide  (wall, approximate)", "deliverToDecideMs")
    block("decide -> execute  (wall)", "decideToExecuteMs")
    if report["deliveredByType"]:
        lines.append("")
        lines.append("delivered by type: " + ", ".join(
            f"{key}={value}" for key, value in report["deliveredByType"].items()))
    if report["commandsByChannel"]:
        lines.append("commands by channel: " + ", ".join(
            f"{key}={value}" for key, value in report["commandsByChannel"].items()))
    lines.append("")
    lines.append(report["note"])
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Summarize an RWX delivery.jsonl trace.")
    parser.add_argument("trace", nargs="?", help="path to delivery.jsonl")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    parser.add_argument("--include-blocking", action="store_true",
                        help="include step in decide->execute (overstates the segment)")
    arguments = parser.parse_args(argv)

    trace = Path(arguments.trace) if arguments.trace else None
    if trace is None:
        default = Path("evaluate/output/events-live/delivery.jsonl")
        if not default.exists():
            parser.error("no trace given and no default at " + str(default))
        trace = default
    if not trace.exists():
        parser.error("no such trace: " + str(trace))

    report = analyse(trace, arguments.include_blocking)
    print(json.dumps(report, indent=2, ensure_ascii=False) if arguments.json else render(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
