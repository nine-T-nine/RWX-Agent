"""CASE3-Transport public-state-only baseline shared by type1..type4.

Deterministic policy (only public observations; never meta/evidence/replays):

1. landing point: one public `rwx-game-map-show` window around the target island gives the land
   tiles, and `rwx-game-unit-list --owner enemy` gives the visible anti-air turrets.  The unload
   point is the interior land tile farthest from the nearest turret (ties: closest to the base),
   so the transports spend as little time inside anti-air range as possible.  One-time fog hides
   the island at the start, so while the window shows no land the fleet heads for the centre and
   the window is re-read every poll until a transport reveals the coast;
2. production: every idle factory is kept busy with the first requirement still short of its
   target, counting units that were ordered but have not appeared yet;
3. loading: the lowest-id transport with free slots takes the lowest-id idle target units that fit
   its real free slots.  Issued loads are remembered per transport until the units are really in
   the cargo hold, so a walking unit is never ordered twice and its transport never leaves early;
4. departure: a transport leaves when it is full, when its hold cannot take any more, or when
   nothing is left that could still arrive (every type is either delivered, alive or in the air).
   Empty transports fly home for a second and third trip, so a batch that does not fit one wave is
   still delivered instead of being stranded at the base;
5. shipping: fly to the landing point and unload only while actually above the island (a hold
   released over lava loses its cargo), then leave the coast again so the turrets stop firing; the
   delivered ground units then fight whatever is left on the island;
6. the loop polls public observations every 2 s.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[4]
BASE = (1930.0, 2050.0)
TARGET_CENTRE = (370.0, 370.0)
POLL_SECONDS = 2.0
NEAR_LANDING = 140.0
NEAR_BASE = 200.0
ON_ISLAND = 320.0
BOARD_TIMEOUT = 20.0
GRID_CHARS = set(".~?^*08 ")


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


def near(point, target, radius):
    return abs(point[0] - target[0]) <= radius and abs(point[1] - target[1]) <= radius


def try_call(client, command):
    """B commands race with the game (a unit may board while we look at it); skip and retry later."""
    ok, payload = attempt(client, command)
    if ok:
        return payload
    print(json.dumps({"skipped": payload, "command": command}), flush=True)
    return None


def attempt(client, command):
    """(True, reply) or (False, error text) without raising, so the caller can react to the reason."""
    try:
        return True, client.call(command)
    except RuntimeError as error:
        return False, str(error)


def load_into(client, transport, idle):
    """Load as many idle units as really fit; the engine rejects a hold whose slots are too small.

    Slot weights are per unit type and are not public, so the request size descends until the
    engine accepts it. Returns the units that were ordered aboard, or [] when nothing fits.
    """
    free = transport["freeSlots"]
    for count in range(min(free, len(idle)), 0, -1):
        take = idle[:count]
        ids = ",".join(str(unit["id"]) for unit in take)
        ok, payload = attempt(client, f'rwx-micro-3-load --transport {transport["id"]} --units {ids}')
        if ok:
            print(json.dumps({"load": {"transport": transport["id"], "units": ids}}), flush=True)
            return take
        if "insufficient_capacity" not in payload:
            print(json.dumps({"skipped": payload, "units": ids}), flush=True)
            return []
    return []

def land_tiles(client):
    """Public terrain window around the target island: {(tileX, tileY)} of land plus the tile size."""
    text = client.call(f"rwx-game-map-show --x {TARGET_CENTRE[0]:.0f} --y {TARGET_CENTRE[1]:.0f}"
                       " --half-width 20 --half-height 20")
    origin = re.search(r"origin=tile (\d+),(\d+)", text)
    tile = re.search(r"1 tile=(\d+)", text)
    if not origin or not tile:
        return set(), 20
    origin_x, origin_y = int(origin.group(1)), int(origin.group(2))
    land = set()
    row_index = -1
    for line in text.splitlines():
        if not line or set(line) - GRID_CHARS:
            continue
        row_index += 1  # header lines are not part of the grid, so count grid rows only
        for column, cell in enumerate(line.split(" ")):
            if cell == ".":
                land.add((origin_x + column, origin_y + row_index))
    return land, int(tile.group(1))


def choose_landing(client):
    """Unload on the land tile farthest from the visible turrets; ties prefer the base side."""
    land, size = land_tiles(client)
    if not land:
        print(json.dumps({"landing": "fallback", "reason": "no land in terrain window"}), flush=True)
        return TARGET_CENTRE, land, size
    enemies = [(u["x"], u["y"]) for u in client.call("rwx-game-unit-list --owner enemy --json")["units"]]
    interior = [t for t in land
                if all((t[0] + dx, t[1] + dy) in land for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    best = None
    for tile_x, tile_y in sorted(interior or land, key=lambda t: (t[1], t[0])):
        point = ((tile_x + 0.5) * size, (tile_y + 0.5) * size)
        safety = min((math.dist(point, enemy) for enemy in enemies), default=1e9)
        cost = (-round(safety, 1), math.dist(point, BASE))
        if best is None or cost < best[0]:
            best = (cost, point)
    print(json.dumps({"landing": best[1], "landTiles": len(land), "enemies": len(enemies)}), flush=True)
    return best[1], land, size


def over_land(position, land, size):
    """True when the transport is above an island tile, so an unload cannot drop units into lava."""
    return (int(position[0] // size), int(position[1] // size)) in land


def play(client):
    landing, land, tile_size = choose_landing(client)
    boarding = {}   # transport id -> {"units": [ids], "deadline": monotonic}
    unloading = {}  # transport id -> monotonic deadline
    sailing = set()  # transports already ordered to the landing point
    blocked = set()  # holds that are full even though freeSlots still reports a remainder
    inflight = {}   # unit type -> orders issued but not yet visible as a live unit
    seen = {}       # unit type -> live count observed last poll
    while True:
        state = client.call("rwx-micro-3-state --json")
        if state.get("phase") == "ended" or state.get("targetReached"):
            return
        goal = client.call("rwx-micro-3-goal --json")
        production = client.call("rwx-micro-3-production --json")
        fleet = client.call("rwx-micro-3-fleet --json")["transports"]
        # One-time fog hides the target island until a transport is close enough to see it, so the
        # first terrain window is empty. Re-read it every poll while it is empty and re-pick the
        # landing point as soon as real land appears; until then the fleet flies to the centre.
        if not land:
            land, tile_size = land_tiles(client)
            if land:
                landing, land, tile_size = choose_landing(client)
        units = client.call("rwx-game-unit-list --owner self --full --json")["units"]
        produced = production["producedCounts"]
        missing = {r["type"]: r["remaining"] for r in goal["requirements"]}
        # 0) Reconcile outstanding orders with units that actually appeared (a dead unit reopens the gap).
        for need in goal["requirements"]:
            kind = need["type"]
            grew = max(0, produced.get(kind, 0) - seen.get(kind, 0))
            inflight[kind] = max(0, inflight.get(kind, 0) - grew)
            seen[kind] = produced.get(kind, 0)
        # Cargo ids carry no type, so the hold is spread over the types that are still short, largest
        # gap first. Without this a transport in the air looks like three units that were never built
        # and the factories would keep churning out duplicates.
        alive_free = {}
        for unit in units:
            # Units already standing on the target island are counted by `delivered`, not here;
            # counting them twice would make the shortfall vanish and stop production early.
            if unit["type"] in missing and not near((unit["x"], unit["y"]), landing, ON_ISLAND):
                alive_free[unit["type"]] = alive_free.get(unit["type"], 0) + 1
        cargo_left = sum(len(transport["cargo"]) for transport in fleet)
        cargo_cover = {}
        for need in sorted(goal["requirements"], key=lambda r: -r["remaining"]):
            kind = need["type"]
            gap = max(0, need["remaining"] - alive_free.get(kind, 0) - inflight.get(kind, 0))
            take = min(cargo_left, gap)
            cargo_cover[kind] = take
            cargo_left -= take
        shortfall = {need["type"]: max(0, need["remaining"] - alive_free.get(need["type"], 0)
                                       - inflight.get(need["type"], 0) - cargo_cover[need["type"]])
                     for need in goal["requirements"]}
        # 1) Production: keep each idle factory working on the first type still short of its target.
        for factory in sorted(production["factories"], key=lambda f: f["factoryId"]):
            if not factory["alive"] or factory["busy"]:
                continue
            for need in goal["requirements"]:
                if shortfall[need["type"]] > 0 and need["type"] in production["allowedUnitTypes"]:
                    print(json.dumps({"produce": {"factory": factory["factoryId"], "unit": need["type"]}}), flush=True)
                    if try_call(client, f'rwx-micro-3-produce --factory {factory["factoryId"]} --unit {need["type"]}') is not None:
                        inflight[need["type"]] = inflight.get(need["type"], 0) + 1
                        shortfall[need["type"]] -= 1
                    break
        # 2) Cargo bookkeeping: a remembered load ends when the units really boarded or timed out.
        carried = {unit for transport in fleet for unit in transport["cargo"]}
        now = time.monotonic()
        for transport_id in list(boarding):
            row = boarding[transport_id]
            if now > row["deadline"] or all(unit in carried for unit in row["units"]):
                boarding.pop(transport_id, None)
        for transport_id in list(unloading):
            if now > unloading[transport_id]:
                unloading.pop(transport_id, None)
        pending = {unit for row in boarding.values() for unit in row["units"]}
        # Nothing more is coming for a type that is neither alive nor in the air, so a partial hold
        # may leave even though some type is still short (a second trip will pick the rest up).
        everything_built = all(shortfall[need["type"]] <= 0 for need in goal["requirements"])
        idle = sorted((u for u in units
                       if u["type"] in missing and u["id"] not in carried and u["id"] not in pending
                       and not near((u["x"], u["y"]), landing, ON_ISLAND)), key=lambda u: u["id"])
        for transport in sorted(fleet, key=lambda t: t["id"]):
            if not transport["alive"]:
                sailing.discard(transport["id"])
                blocked.discard(transport["id"])
                continue
            position = (transport["x"], transport["y"])
            order = transport["order"]
            if transport["id"] in boarding:
                continue  # never move a transport whose units are still walking over
            # Only drop while actually above the island: a hold released over lava loses its cargo.
            if transport["cargo"] and near(position, landing, NEAR_LANDING) and over_land(position, land, tile_size):
                sailing.discard(transport["id"])
                if transport["id"] not in unloading:
                    print(json.dumps({"unload": transport["id"]}), flush=True)
                    try_call(client, f'rwx-micro-3-unload --transport {transport["id"]}')
                    unloading[transport["id"]] = now + 8.0
                continue
            if transport["id"] in sailing:
                continue
            if transport["freeSlots"] > 0 and idle:
                take = load_into(client, transport, idle)
                if take:
                    idle = idle[len(take):]
                    boarding[transport["id"]] = {"units": [u["id"] for u in take], "deadline": now + BOARD_TIMEOUT}
                    continue
                # Nothing fits any more: the hold is full even though the raw slot count is not zero.
                blocked.add(transport["id"])
            if not transport["cargo"]:
                blocked.discard(transport["id"])
                # Empty: go home for the next batch (also leaves the anti-air range after a drop).
                if order == "idle" and not near(position, BASE, NEAR_BASE):
                    print(json.dumps({"move": {"transport": transport["id"], "to": BASE}}), flush=True)
                    try_call(client, f'rwx-micro-3-move --transport {transport["id"]} --x {BASE[0]} --y {BASE[1]}')
                continue
            if transport["freeSlots"] == 0 or transport["id"] in blocked or (everything_built and not idle):
                print(json.dumps({"move": {"transport": transport["id"], "to": landing}}), flush=True)
                try_call(client, f'rwx-micro-3-move --transport {transport["id"]} --x {landing[0]:.0f} --y {landing[1]:.0f}')
                sailing.add(transport["id"])
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
