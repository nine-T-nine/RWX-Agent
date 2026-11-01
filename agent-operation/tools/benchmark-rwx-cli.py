#!/usr/bin/env python3
"""Compare serial CLI round trips against one running RWX instance, without modifying game state."""
import argparse
import importlib.util
import json
import math
from pathlib import Path
import socket
import statistics
import subprocess
import sys
import time

SCRIPT = Path(__file__).with_name("rwx-cli.py")
spec = importlib.util.spec_from_file_location("rwx_cli", SCRIPT)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def stats(values):
    values = sorted(values)
    return {"p50_ms": round(statistics.median(values), 3),
            "p95_ms": round(values[math.ceil(len(values) * .95) - 1], 3),
            "max_ms": round(values[-1], 3)}


def ms(start):
    return (time.perf_counter() - start) * 1000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int)
    parser.add_argument("--port-file")
    parser.add_argument("--count", type=int, default=30)
    opts = parser.parse_args()
    if opts.count < 2:
        parser.error("--count must be at least 2")
    port = cli.discover(opts.port, opts.port_file)
    result = {"port": port, "count": opts.count, "units": "milliseconds",
              "notes": "serial samples; one-shot includes process startup, discovery/ping, connection and request; persistent per-call includes IPC or socket request and server processing; no independent breakdown of server execution"}
    interpreter = []
    for _ in range(opts.count):
        start = time.perf_counter()
        subprocess.run([sys.executable, "-c", "pass"], check=True, stdout=subprocess.DEVNULL)
        interpreter.append(ms(start))
    result["interpreter_start_and_exit"] = stats(interpreter)

    startups, connects = [], []
    for _ in range(opts.count):
        start = time.perf_counter()
        p = subprocess.Popen([sys.executable, str(SCRIPT), "--port", str(port), "--stdio"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", bufsize=1)
        if p.stderr.readline().strip() != "ready":
            raise RuntimeError("persistent client did not become ready")
        startups.append(ms(start))
        p.stdin.close()
        p.wait(timeout=5)
        start = time.perf_counter()
        with socket.create_connection(("127.0.0.1", port), timeout=3):
            connects.append(ms(start))
    result["python_persistent_start_discovery_connect_handshake"] = stats(startups)
    result["direct_connect"] = stats(connects)

    startup = time.perf_counter()
    proc = subprocess.Popen([sys.executable, str(SCRIPT), "--port", str(port), "--stdio"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", bufsize=1)
    if proc.stderr.readline().strip() != "ready":
        raise RuntimeError("persistent client did not become ready")
    sock = socket.create_connection(("127.0.0.1", port), timeout=3)
    sock.settimeout(60)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    stream = sock.makefile("rb")

    def read_frame():
        if stream.read(1) != b"\x1e":
            raise RuntimeError("invalid frame")
        data = bytearray()
        while True:
            b = stream.read(1)
            if not b:
                raise EOFError("truncated response")
            if b == b"\x1e":
                return data.decode("utf-8")
            data.extend(b)

    handshake = time.perf_counter()
    sock.sendall(b"!!framed\n")
    if read_frame() != "ready":
        raise RuntimeError("handshake failed")
    result["direct_handshake_ms"] = round(ms(handshake), 3)
    try:
        for name, args in (("ping", ["rwx-ping"]), ("game_state_summary", ["rwx-game-state", "--summary"])):
            one, persistent, direct = [], [], []
            for _ in range(opts.count):
                start = time.perf_counter()
                r = subprocess.run([sys.executable, str(SCRIPT), "--port", str(port), *args],
                                   capture_output=True, text=True, encoding="utf-8", timeout=60)
                one.append(ms(start))
                if r.returncode != 0:
                    raise RuntimeError(f"one-shot failed: {r.stdout} {r.stderr}")
                start = time.perf_counter()
                proc.stdin.write(" ".join(args) + "\n")
                proc.stdin.flush()
                reply = json.loads(proc.stdout.readline())
                persistent.append(ms(start))
                if reply["error"]:
                    raise RuntimeError(reply["reply"])
                import base64
                payload = ",".join(base64.b64encode(a.encode()).decode() for a in args[1:])
                start = time.perf_counter()
                sock.sendall((args[0] + "\t" + payload + "\n").encode())
                reply = read_frame()
                direct.append(ms(start))
                if reply.startswith("error:"):
                    raise RuntimeError(reply)
            result[name] = {"python_one_shot_end_to_end": stats(one),
                            "python_persistent_ipc_to_full_reply": stats(persistent),
                            "direct_request_to_full_reply": stats(direct)}
    finally:
        proc.stdin.close()
        proc.wait(timeout=5)
        stream.close()
        sock.close()
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
