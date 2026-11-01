"""delivery_latency.py 的自测（不依赖 pytest）：

    python evaluate/test/test_delivery_latency.py
    python -m unittest discover -s evaluate/test -p "test_*.py"

只验证三段延迟的配对与统计口径：合成一份 delivery.jsonl，断言
detect→deliver 取最早贡献事件的产生时间、deliver→decide 按 session 配对、
decide→execute 默认排除阻塞命令，以及空输入不抛异常。
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, os.path.join(REPO_ROOT, "evaluate", "utils"))

import delivery_latency as dl  # noqa: E402


def row(**fields):
    return fields


class DeliveryLatencyTest(unittest.TestCase):
    def write(self, rows):
        directory = tempfile.mkdtemp(prefix="rwx-latency-")
        path = Path(directory) / "delivery.jsonl"
        path.write_text("\n".join(json.dumps(item) for item in rows) + "\n", encoding="utf-8")
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        return path

    def test_three_segments_are_paired_per_session(self):
        path = self.write([
            row(kind="env", wall=0, game=0, speed="realtime", delivery="l1", map="Lake"),
            # Produced at 1000, delivered at 1200 → detect→deliver 200 ms / 200 game-ms.
            row(kind="deliver", channel="auto", session="a", seq=1, type="losses",
                events=2, produced={"wall": 1000, "game": 1000}, delivered={"wall": 1200, "game": 1200}),
            # The next command of session a arrives 800 ms later → deliver→decide 800 ms.
            row(kind="command", session="a", command="rwx-game-state", channel="state", failed=False,
                received={"wall": 2000, "game": 1900}, applied={"wall": 2010, "game": 1904},
                appliedBound="reply"),
            # A command from another session must not consume session a's delivery.
            row(kind="command", session="b", command="rwx-ping", channel="none", failed=False,
                received={"wall": 1500, "game": 1400}, applied={"wall": 1501, "game": 1400}),
        ])
        report = dl.analyse(path, include_blocking=False)
        self.assertEqual("realtime", report["environment"]["speed"])
        self.assertEqual(1, report["counts"]["deliveries"])
        self.assertEqual(2, report["counts"]["commands"])
        self.assertEqual(200.0, report["detectToDeliverMs"]["p50"])
        self.assertEqual(200.0, report["detectToDeliverGameMs"]["p50"])
        self.assertEqual(800.0, report["deliverToDecideMs"]["p50"])
        # 10 ms from receipt to reply for the state read, 1 ms for the ping.
        self.assertEqual(10.0, report["decideToExecuteMs"]["max"])
        self.assertEqual(2, report["decideToExecuteMs"]["count"])

    def test_blocking_commands_are_excluded_by_default(self):
        path = self.write([
            row(kind="command", session="a", command="rwx-game-step", channel="none", failed=False,
                received={"wall": 1000, "game": 1000}, applied={"wall": 31_000, "game": 31_000}),
            row(kind="command", session="a", command="rwx-game-state", channel="state", failed=False,
                received={"wall": 40_000, "game": 40_000}, applied={"wall": 40_004, "game": 40_000}),
        ])
        excluded = dl.analyse(path, include_blocking=False)
        self.assertEqual(1, excluded["counts"]["excludedBlockingCommands"])
        self.assertEqual(4.0, excluded["decideToExecuteMs"]["p50"])
        included = dl.analyse(path, include_blocking=True)
        self.assertEqual(2, included["decideToExecuteMs"]["count"])
        self.assertEqual(30_000.0, included["decideToExecuteMs"]["max"])

    def test_empty_trace_reports_zero_samples_instead_of_failing(self):
        path = self.write([row(kind="env", wall=0, game=0, speed="slow:0.5", delivery="l0", map="")])
        report = dl.analyse(path, include_blocking=False)
        self.assertEqual("slow:0.5", report["environment"]["speed"])
        self.assertEqual("l0", report["environment"]["delivery"])
        self.assertEqual(0, report["detectToDeliverMs"]["count"])
        self.assertEqual(0, report["deliverToDecideMs"]["count"])
        self.assertIn("no samples", dl.render(report))

    def test_malformed_lines_are_rejected_loudly(self):
        directory = tempfile.mkdtemp(prefix="rwx-latency-")
        path = Path(directory) / "delivery.jsonl"
        path.write_text("{not json}\n", encoding="utf-8")
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        with self.assertRaises(SystemExit):
            list(dl.records(path))


if __name__ == "__main__":
    unittest.main()
