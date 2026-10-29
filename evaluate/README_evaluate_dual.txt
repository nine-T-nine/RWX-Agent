RWX command execution latency evaluator (Node.js, no npm packages)

Files: evaluate.js, evaluate_1.js and execution-mapping.js must reside in the same directory.

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
It extends report.txt and summary.json with adjacent native Replay command
intervals, adds an interactive interval line chart, then appends the existing
execution-latency chart and detailed report.html sections (five charts total).
Additional output files:
  native_command_intervals.csv,
  execution_report.txt, execution_summary.json, execution_matches.csv,
  execution_matches.jsonl, mapping_rules.json

All artifacts are under ./output/<commands-file-stem>_analysis/ (relative to
current working directory).

X = first matching native engine command in replay JSONL.
Y = last matching native engine command from the same high-level request.
New receipt logs use [MM:SS:mmm], preserving gameTimeMillis to milliseconds.
Both evaluators also read legacy [MM:SS] logs. Existing integer-second logs
cannot recover the original receipt milliseconds and are never rewritten.
Latency = X - receipt game time. New logs have delayMin=delayMax=latency;
for legacy logs latency is an upper bound, with delayMin=max(0,latency-1).
Execution span = Y - X.  A/B cycle & wait analytics remain unchanged.

Native command intervals use ALL valid rows of the controlled team in the
entire Replay, independently of high-to-low matching. Automation and UI
commands are included; other teams and noop rows are excluded. N rows yield
N-1 intervals. Each Replay row is one command regardless of unit count;
same-tick rows are retained as distinct commands, with a zero-second gap.
The chart X value is the PREVIOUS command's execution time (the left endpoint),
and Y is next.time_s - previous.time_s. Hover shows both commands/ticks/lines.
native_command_intervals.csv stores the complete sequence and outlier flags;
summary.json.nativeCommandIntervals stores raw/clean mean, median, P90/P95,
maximum, threshold, outlier count and zero-gap count. report.txt lists outliers.
Long-gap detection uses the existing max(IQR fence, scaled MAD fence,
median + 5 seconds) rule; the JavaScript API's minOutlierSeconds option can
change the 5-second floor. Fewer than four intervals are not marked outliers.

All 19 order commands are mapped, including stop, rally, cancel and stance.
Eight group/keep/pending/auto commands configure local or indirect behavior;
they are listed in the report and excluded from direct execution latency.
Default-only stance updates are also indirect. Numeric IDs, targets, exact
action IDs, queue flags and coordinates constrain matches. Building placement
uses the engine's 20-unit tile grid and shipped building aliases. Production
count is checked per factory; build --count N is one native row with a
build_count field. Push has one attackMove, or move + queued attackMove when
an explicit staging point is given. Dynamic selector coverage is unverified.
Immediate requests use a 5-second window, plus 1 second for old log precision.
Deferred requests use the engine's 600-second lifetime, plus old precision.
Native rows are not reused. Competing requests with insufficient evidence
are marked ambiguous without assigning a latency. Partial means incomplete
numeric ID/count coverage; it does not prove CLI failure. Missing native
evidence cannot reveal the rejection reason because receipt logs omit replies.

Regenerate Replay JSONL using the current parser before evaluating old exports:
  python evaluate/utils/replay.py "replays/match.replay"
New JSONL includes command_queued, waypoint_queued, build_count, rally,
target_point, stop_current_action, clear_existing_orders and is_high_priority.
Legacy queued is retained; it is not Command.isQueued. Affected commands in
old JSONL are marked unobservable, with instructions to regenerate.

Regression checks (from repository root):
  node --test evaluate/test-evaluate.js
  python -W error::ResourceWarning evaluate/utils/test_replay.py

The three supplied samples now share matching suffixes between commands and
replays: _gpt_未完, _claudevsVeryHard_1_一半, _claudevsVeryHard_2_投降.
Commands ...5613052610805639813..._1_一半 pairs with replay ...21.27.25..._1_一半;
commands ...9568037334084955616..._2_投降 pairs with replay ...23.28.41..._2_投降.

Native replay command execution is NOT the same as unit movement / production
completion; game time is NOT necessarily wall-clock time.
