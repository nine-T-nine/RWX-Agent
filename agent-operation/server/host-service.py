#!/usr/bin/env python3
"""systemd post-start: wait for the local CLI, then host using the standard game protocol."""
import argparse
import importlib.util
import os
from pathlib import Path
import time

path = Path(__file__).resolve().parents[1] / "tools" / "rwx-cli.py"
spec = importlib.util.spec_from_file_location("rwx_cli", path)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--game-port", type=int, default=5123)
    parser.add_argument("--map", default="Lake (2p)")
    opts = parser.parse_args()
    until = time.monotonic() + 150
    port = None
    while time.monotonic() < until:
        port = cli.port_from_file(opts.port_file)
        if port:
            break
        time.sleep(0.25)
    if not port:
        raise SystemExit("RWX CLI did not become ready within 150 seconds")
    args = ["rwx-host", "--port", str(opts.game_port), "--name", "RWX-Server",
            "--map", opts.map, "--managed-room", "true"]
    if os.environ.get("RWX_ROOM_PASSWORD"):
        args += ["--password-env", "RWX_ROOM_PASSWORD"]
    reply = cli.request(port, args)
    print(reply, flush=True)
    if reply.startswith("error:"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
