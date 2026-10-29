"""Replay a real engine session, then run the second-round event evaluator (no CLI polling)."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]

def digest(file: Path) -> str:
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--team", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--dist", type=Path, default=ROOT / "desktop/build/headless")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java")
    parser.add_argument("--speed", type=int, choices=[1, 2, 4, 8, 16, 32, 64], default=64)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--commands", type=Path)
    parser.add_argument("--jsonl", type=Path, help="Command JSONL from replay.py; required with --commands")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    replay = args.replay.resolve()
    if replay.parent != (ROOT / "replays").resolve():
        parser.error("Replay must be in this checkout's replays directory")
    if not replay.is_file() or not (args.dist / "lib").is_dir():
        parser.error("Replay or Headless distribution is missing; build :desktop:headlessArchive first")
    if bool(args.commands) != bool(args.jsonl):
        parser.error("--commands and --jsonl must be supplied together")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    before = digest(replay)
    config = json.loads(args.config.read_text(encoding="utf-8-sig")) if args.config else {}
    ports = ROOT / "agent-operation/tmp"
    ports.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="evaluate-replay-", dir=ports) as temp:
        temporary = Path(temp)
        command = [args.java, "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                   "-Xmx1536m", "-Dfile.encoding=UTF-8", f"-Dlaunch.dir={ROOT}",
                   f"-Drwx.evaluate.dir={out / 'engine'}", f"-Drwx.evaluate.team={args.team}",
                   f"-Drwx.evaluate.replaySpeed={args.speed}", "-Drwx.evaluate.exitOnReplayEnd=true",
                   f"-Drwx.cli.recordingDir={temporary / 'commands'}", "-cp", str(args.dist.resolve() / "lib/*"),
                   "io.github.rwx.DesktopMain", "--headless", "--no-steam",
                   f"--cli-port-file={temporary / 'port'}", f"--auto-replay={replay.name}"]
        if "X" in config:
            command.insert(1, f"-Drwx.evaluate.X={config['X']}")
        if "T_REGION_HOLD" in config:
            command.insert(1, f"-Drwx.evaluate.regionHoldMs={config['T_REGION_HOLD']}")
        with (out / "playback.log").open("w", encoding="utf-8") as log:
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
    command = [node, str(ROOT / "evaluate/events.js"), str(evidence), "--out", str(out / "analysis")]
    if args.commands:
        command += ["--commands", str(args.commands.resolve()), "--replay", str(args.jsonl.resolve())]
    if args.config:
        command += ["--config", str(args.config.resolve())]
    subprocess.run(command, cwd=ROOT, check=True)
    after = digest(replay)
    if before != after:
        raise RuntimeError("Replay input changed during evaluation")
    playback = (out / "playback.log").read_text(encoding="utf-8", errors="replace")
    verification = {"replay": replay.name, "sha256": after, "unchanged": True,
                    "checksumWarnings": playback.count("checksums don't match"),
                    "evidence": str(evidence.relative_to(out))}
    (out / "playback.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(verification, ensure_ascii=False))

if __name__ == "__main__":
    main()
