"""Replay a real engine session, then rebuild the Stage-2 and Stage-3 event reports."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import re
from replay import Replay, safe_stem, write_outputs

ROOT = Path(__file__).resolve().parents[2]

def digest(file: Path) -> str:
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def playback_command(replay: Path, dist: Path, java: str, port: Path,
                     properties: dict[str, object], data_dir: Path = ROOT) -> list[str]:
    """Shared engine launcher; callers own evidence schema, verification and temporary files."""
    return [java, "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
            "-Xmx1536m", "-Dfile.encoding=UTF-8", f"-Dlaunch.dir={data_dir}",
            *[f"-D{key}={value}" for key, value in properties.items()],
            "-cp", str(dist.resolve() / "lib/*"), "io.github.rwx.DesktopMain", "--headless", "--no-steam",
            f"--cli-port-file={port}", f"--auto-replay={replay.name}"]

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--team", type=int, required=True)
    parser.add_argument("--out", type=Path, help="对局输出根目录，默认evaluate/output/<commands去前缀文件名>")
    parser.add_argument("--dist", type=Path, default=ROOT / "desktop/build/headless")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java")
    parser.add_argument("--speed", type=int, choices=[1, 2, 4, 8, 16, 32, 64], default=64)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--commands", type=Path, required=True)
    parser.add_argument("--jsonl", type=Path, help="Replay命令JSONL，省略时自动解析到对局replay目录")
    parser.add_argument("--no-rebuild", action="store_true",
                        help="保留播放原始 telemetry；默认会用命令日志重建 command/result 行")
    parser.add_argument("--skip-stage2", action="store_true",
                        help="只重建证据并生成第三阶段事件，不跑第二阶段（补第三阶段材料时用）")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    replay = args.replay.resolve()
    if replay.parent != (ROOT / "replays").resolve():
        parser.error("Replay must be in this checkout's replays directory")
    if not replay.is_file() or not (args.dist / "lib").is_dir():
        parser.error("Replay or Headless distribution is missing; build :desktop:headlessArchive first")
    if not args.commands.is_file():
        parser.error("--commands must point to the original command log")
    match_name = re.sub(r"^commands?-?", "", args.commands.stem, flags=re.I)
    out = args.out.resolve() if args.out else ROOT / "evaluate/output" / match_name
    out.mkdir(parents=True, exist_ok=True)
    before = digest(replay)
    if args.jsonl is None:
        write_outputs(Replay.load(str(replay)), str(out / 'replay'), ('summary', 'jsonl', 'text'), clock=True)
        args.jsonl = out / 'replay' / (safe_stem(str(replay)) + '.jsonl')
    engine_dir = out / 'engine'
    engine_dir.mkdir(parents=True, exist_ok=True)
    config = json.loads(args.config.read_text(encoding="utf-8-sig")) if args.config else {}
    ports = ROOT / "agent-operation/tmp"
    ports.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="evaluate-replay-", dir=ports) as temp:
        temporary = Path(temp)
        command = playback_command(replay, args.dist, args.java, temporary / "port", {
            "rwx.evaluate.dir": out / "engine", "rwx.evaluate.team": args.team,
            "rwx.evaluate.replaySpeed": args.speed, "rwx.evaluate.exitOnReplayEnd": "true",
            "rwx.cli.recordingDir": temporary / "commands"})
        if "X" in config:
            command.insert(1, f"-Drwx.evaluate.X={config['X']}")
        if "T_REGION_HOLD" in config:
            command.insert(1, f"-Drwx.evaluate.regionHoldMs={config['T_REGION_HOLD']}")
        with (engine_dir / "playback.log").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout,
                           check=True, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    candidates = list((out / "engine").glob("match-*/telemetry.jsonl"))
    if not candidates:
        raise RuntimeError(f"Engine did not produce evidence; inspect {out / 'playback.log'}")
    evidence = max(candidates, key=lambda p: p.stat().st_mtime_ns)
    last = None
    with evidence.open(encoding="utf-8") as stream:
        for line in stream:
            if line.strip():
                last = json.loads(line)
    if not last or last.get("kind") != "end" or last.get("reason") != "replay_end":
        raise RuntimeError("Replay evidence did not close normally; inspect playback.log")
    node = shutil.which("node")
    if node is None:
        raise RuntimeError("Node.js is required")
    rebuilt = False
    if not args.no_rebuild:
        # The engine replay only records commands that were already accepted, so the receipt-shaped
        # half of a live capture (command/result rows, native attribution) has to be joined back from
        # the original command log. The raw playback file is kept next to the merged one.
        merged = evidence.parent / "telemetry.rebuilt.jsonl"
        build_command = [node, str(ROOT / "evaluate/tools/rebuild_telemetry.js"),
                         "--telemetry", str(evidence), "--commands", str(args.commands.resolve()),
                         "--replay", str(args.jsonl.resolve()), "--out", str(merged),
                         "--team", str(args.team)]
        subprocess.run(build_command, cwd=ROOT, check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        raw = evidence.parent / "telemetry.engine.jsonl"
        evidence.rename(raw)
        merged.rename(evidence)
        rebuilt = True
    if not args.skip_stage2:
        command = [node, str(ROOT / "evaluate/evaluate_stage_2.js"), str(evidence), "--out", str(out / "stage_2"),
                   "--economy", str(evidence.parent / "economy.log")]
        if args.commands:
            command += ["--commands", str(args.commands.resolve()), "--replay", str(args.jsonl.resolve())]
        if args.config:
            command += ["--config", str(args.config.resolve())]
        subprocess.run(command, cwd=ROOT, check=True)
    subprocess.run([node, str(ROOT / "evaluate/evaluate_stage_3.js"), str(evidence),
                    "--out", str(out / "stage_3")], cwd=ROOT, check=True)
    after = digest(replay)
    if before != after:
        raise RuntimeError("Replay input changed during evaluation")
    playback = (engine_dir / "playback.log").read_text(encoding="utf-8", errors="replace")
    verification = {"replay": replay.name, "sha256": after, "unchanged": True,
                    "checksumWarnings": playback.count("checksums don't match"),
                    "evidence": str(evidence.relative_to(out)), "rebuilt": rebuilt}
    (engine_dir / "playback.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False))

if __name__ == "__main__":
    main()
