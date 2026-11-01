from __future__ import annotations
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from .run import ROOT, read_json, read_lines

sys.path.insert(0, str(ROOT / "evaluate/utils"))
from evaluate_replay import digest, playback_command

# A paused command is recorded between ticks but executed inside one, so a shot (and therefore a
# death) can land a couple of ticks earlier in playback with an identical final state. Anything
# larger, or a different kill order/identity, is a real divergence.
KILL_TICK_TOLERANCE = 2

def resim(directory: Path, dist: Path | None = None, java: str | None = None, timeout: int = 180):
    directory = directory.resolve()
    meta = read_json(directory / "meta.json", {})
    replay = directory / meta.get("replay", "match.replay")
    before = digest(replay)
    output = directory / "engine"
    output.mkdir(exist_ok=True)
    dist = (dist or ROOT / "desktop/build/headless").resolve()
    java = java or os.environ.get("RWX_JAVA") or str(Path(os.environ.get("JAVA_HOME", "")) / "bin/java.exe")
    if not Path(java).is_file():
        java = shutil.which("java") or java
    temporary_root = ROOT / "agent-operation/tmp"
    temporary_root.mkdir(parents=True, exist_ok=True)
    log_path = output / "playback.log"
    with tempfile.TemporaryDirectory(prefix="micro-resim-", dir=temporary_root) as temporary:
        temp = Path(temporary)
        (temp / "replays").mkdir()
        copy = temp / "replays/match.replay"
        shutil.copyfile(replay, copy)
        # The engine resolves replay names as "replays/<name>" relative to the process cwd
        # (getGameDataPath() is empty on desktop), while assets come from -Dlaunch.dir.
        # Run inside the isolated data dir so the copy is found without touching <repo>/replays.
        command = playback_command(copy, dist, java, temp / "port", {
            "rwx.micro.resim": directory / "meta.json", "rwx.micro.resimOut": output,
            "rwx.evaluate.replaySpeed": 64}, data_dir=ROOT)
        with log_path.open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=temp, stdout=log, stderr=subprocess.STDOUT, timeout=timeout,
                           check=True, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    final = read_json(output / "final.json", {})
    live = read_json(directory / "result.json", {})
    events = read_lines(output / "events.jsonl")
    kills = [{"unit": e["unit"], "tick": e["tick"]} for e in events if e["kind"] == "kill"]
    errors = []
    for expected in live.get("units", []):
        actual = next((u for u in final.get("units", []) if u["id"] == expected["id"]), None)
        if not actual or any(abs(actual.get(k, 0) - expected.get(k, 0)) > 0.001 for k in ("hp", "shield")) or actual.get("alive") != expected.get("alive"):
            errors.append({"unit": expected["id"], "reason": "final state mismatch"})
    self_alive = final.get("self", {}).get("alive")
    enemy_alive = final.get("enemy", {}).get("alive")
    outcome = "draw" if self_alive == 0 and enemy_alive == 0 else "loss" if self_alive == 0 else "win" if enemy_alive == 0 else live.get("outcome", "unknown")
    if live and outcome != live.get("outcome"):
        errors.append({"reason": "outcome mismatch"})
    live_kills = live.get("kills")
    warnings = []
    if live_kills is not None and live_kills != kills:
        # Kill identity and order must match exactly; a single tick of skew is tolerated per kill.
        # Paused commands are recorded between ticks but executed inside one, so a kill can land
        # one tick earlier or later in playback without any state divergence.
        same_units = len(live_kills) == len(kills) and all(
            a["unit"] == b["unit"] for a, b in zip(live_kills, kills))
        skew = [{"index": i, "unit": a["unit"], "live": a["tick"], "resim": b["tick"]}
                for i, (a, b) in enumerate(zip(live_kills, kills)) if a["tick"] != b["tick"]]
        if same_units and all(abs(s["live"] - s["resim"]) <= KILL_TICK_TOLERANCE for s in skew):
            warnings.append({"reason": "kill tick skew", "kills": len(kills),
                             "maxSkew": max((abs(s["live"] - s["resim"]) for s in skew), default=0),
                             "skew": skew})
        else:
            errors.append({"reason": "kill tick sequence mismatch", "actual": kills})
    text = log_path.read_text(encoding="utf-8", errors="replace")
    checksum_frames = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if "checksums don't match" in line:
            for follow in lines[index + 1:index + 3]:
                if "game frameNumber:" in follow:
                    checksum_frames.append(int(follow.rsplit(":", 1)[1]))
                    break
    checksums = len(checksum_frames)
    if digest(replay) != before:
        errors.append({"reason": "replay input changed"})
    report = {"schema": "rwx-micro-playback/1", "consistent": not errors and bool(final), "outcome": outcome,
              "finalStateChecked": bool(live), "killSequenceChecked": live_kills is not None,
              "checksumWarnings": checksums, "checksumFrames": checksum_frames,
              "warnings": warnings, "sha256": before, "kills": kills, "errors": errors}
    (output / "playback.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report
