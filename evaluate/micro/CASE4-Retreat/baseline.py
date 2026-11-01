"""CASE4-Retreat public-state-only baseline for every type and level.

Deterministic policy (only public observations: `rwx-micro-4-state`, `rwx-game-unit-list`,
`rwx-game-threat-list`; never meta/evidence/replays):

1. begin the match, then keep one shared decision cycle every 2 s;
2. every tank whose HP is below 40 % of its maximum retreats to the nearest repair bay and stays
   there until HP is back to 85 %; the unit snapshot exposes HP only, so the shield is never read;
3. tanks that are fit attack the nearest *visible* enemy with a direct attack order, so the whole
   group focuses one target instead of spreading damage;
4. a tank that is already inside repair range and still damaged is never ordered out;
5. with nothing visible the tanks sweep a fixed quarter-disc grid over the public objective area,
   because fogged turrets can only be found by walking into sight of them;
6. the loop ends when `state` reports the goal as reached or the case ends.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
POLL_SECONDS = 2.0
RETREAT_HP_RATIO = 0.40
REENGAGE_HP_RATIO = 0.85
TANK_TYPE = "experimentalHoverTank"
BAY_TYPE = "repairbay"
# The objective is public ("the top-left region"). With nothing visible the tanks sweep a grid over
# the quarter disc the objective covers, so fogged guns are actually discovered: five radii crossed
# with four angles, in world units from the map corner.
SWEEP = [(radius * 20.0 * math.cos(angle), radius * 20.0 * math.sin(angle))
         for radius in (50, 42, 34, 26, 18, 10) for angle in (0.1, 0.3, 0.5, 0.7, 0.9, 1.1, 1.3, 1.5)]


class Client:
    def __init__(self, port_file):
        self.process = subprocess.Popen(
            [sys.executable, str(ROOT / "agent-operation/tools/rwx-cli.py"), "--port-file", str(port_file), "--stdio"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", cwd=ROOT)

    def call(self, command):
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise EOFError("Case process ended; use result.json for outcome")
        reply = json.loads(line)
        if reply["error"]:
            raise RuntimeError(reply["reply"])
        data = reply["reply"]
        return json.loads(data) if data.startswith("{") else data

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=5)
        self.process.stdout.close()


def try_call(client, command):
    try:
        return client.call(command)
    except RuntimeError as error:
        print(json.dumps({"skipped": str(error), "command": command}), flush=True)
        return None


def own_units(client):
    return client.call("rwx-game-unit-list --owner self --full --json")["units"]


def visible_enemies(client):
    return client.call("rwx-game-unit-list --owner enemy --full --json")["units"]


def play(client):
    state = client.call("rwx-micro-4-state --json")
    if state.get("phase") == "briefing":
        print(json.dumps({"begin": client.call("rwx-micro-4-begin")}), flush=True)
    sweep = {}  # tank id -> how many sweep legs it has already been sent on
    while True:
        state = client.call("rwx-micro-4-state --json")
        if state.get("phase") == "ended" or (state.get("goal") or {}).get("reached"):
            return
        units = own_units(client)
        tanks = [unit for unit in units if unit.get("type") == TANK_TYPE]
        bays = [unit for unit in units if unit.get("type") == BAY_TYPE]
        if not tanks or not bays:
            time.sleep(POLL_SECONDS)
            continue
        enemies = visible_enemies(client)
        for tank in sorted(tanks, key=lambda unit: unit["id"]):
            hp_ratio = tank["hp"] / tank["maxHp"] if tank.get("maxHp") else 1.0
            bay = min(bays, key=lambda item: math.dist((tank["x"], tank["y"]), (item["x"], item["y"])))
            distance = math.dist((tank["x"], tank["y"]), (bay["x"], bay["y"]))
            in_range = distance <= 220.0
            has_order = tank.get("currentOrder") is not None
            if hp_ratio < RETREAT_HP_RATIO and not in_range:
                if has_order and tank.get("waypointX") is not None and \
                        math.dist((tank["waypointX"], tank["waypointY"]), (bay["x"], bay["y"])) < 60.0:
                    continue  # already heading for that bay
                print(json.dumps({"retreat": tank["id"], "bay": bay["id"], "hp": tank["hp"]}), flush=True)
                try_call(client, f'rwx-game-order-move --units {tank["id"]} --x {bay["x"]:.0f} --y {bay["y"]:.0f}')
                continue
            if in_range and hp_ratio < REENGAGE_HP_RATIO:
                continue  # stay parked and let the bay work
            if not enemies:
                # Nothing in sight: keep sweeping the objective area so the tanks reveal something.
                if tank.get("waypointX") is not None:
                    continue  # already moving
                point = SWEEP[sweep.get(tank["id"], 0) % len(SWEEP)]
                sweep[tank["id"]] = sweep.get(tank["id"], 0) + 1
                print(json.dumps({"advance": tank["id"], "to": point}), flush=True)
                try_call(client, f'rwx-game-order-attack-move --units {tank["id"]} '
                                 f'--x {point[0]:.0f} --y {point[1]:.0f}')
                continue
            target = min(enemies, key=lambda unit: (math.dist((tank["x"], tank["y"]), (unit["x"], unit["y"])), unit["id"]))
            if tank.get("targetId") == target["id"]:
                continue  # already shooting that turret
            print(json.dumps({"attack": tank["id"], "target": target["id"]}), flush=True)
            try_call(client, f'rwx-game-order-attack --units {tank["id"]} --target {target["id"]}')
        time.sleep(POLL_SECONDS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port-file", type=Path, required=True)
    args = parser.parse_args()
    client = Client(args.port_file)
    try:
        play(client)
    except (EOFError, BrokenPipeError, ConnectionError):
        print("Case connection closed; evaluate the run for authoritative outcome.", flush=True)
    finally:
        client.close()


if __name__ == "__main__":
    main()
