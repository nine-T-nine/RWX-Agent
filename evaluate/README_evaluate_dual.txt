RWX command execution latency evaluator (Node.js, no npm packages)

Files: evaluate.js and evaluate_1.js must reside in the same directory.

Usage:
  node evaluate.js "path/to/commands.txt" "path/to/replay.jsonl"

Both file paths are required; argument 1 is the original port command trace,
argument 2 is the complete replay JSONL of the SAME match.

The tool determines the controlled team from "player=N" in the port trace,
then filters native replay commands strictly to "team=N" (excluding noop).
No fallback to faction or another team.

It preserves all output from evaluate_1.js:
  report.txt, summary.json, classified_commands.csv, decision_cycles.csv,
  waiting_intervals.csv, command_intervals.csv, report.html (three charts).
It appends an interactive execution-latency chart and detailed report.html
sections, and adds:
  execution_report.txt, execution_summary.json, execution_matches.csv,
  execution_matches.jsonl, mapping_rules.json

All artifacts are under ./output/<commands-file-stem>_analysis/ (relative to
current working directory).

X = first matching native engine command in replay JSONL.
Y = last matching native engine command from the same high-level request.
Latency = X - port command time (port MM:SS treated as exact integer second).
Execution span = Y - X.  A/B cycle & wait analytics remain unchanged.

Matching: team, command mapping, numeric unit IDs (or selectors), target,
build type, coordinates, chronological order. Batch --count can match
multiple native rows; --auto-place allows placement shifts; deferred commands
(--allow-unaffordable / --after-upgrade) can produce longer latency.
Native rows are not reused. Missing/partial native execution is explicitly
reported and excluded from any fictitious latency values. Unsupported
high-level order types or incompatible input structures cause an error.

Native replay command execution is NOT the same as unit movement / production
completion; game time is NOT necessarily wall-clock time.
