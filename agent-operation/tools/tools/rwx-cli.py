#!/usr/bin/env python3
"""Portable loopback client for RWX's argv/framed CLI protocol (Python 3.9+)."""
import argparse
import base64
import os
from pathlib import Path
import shlex
import socket
import sys

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PORT_FILE = ROOT / "agent-operation" / "tmp" / "port"


def request(port, arguments, timeout=60):
    with socket.create_connection(("127.0.0.1", port), timeout=min(timeout, 3)) as connection:
        connection.settimeout(timeout)
        encoded = ",".join(base64.b64encode(arg.encode("utf-8")).decode("ascii") for arg in arguments[1:])
        connection.sendall((arguments[0] + "\t" + encoded + "\n").encode("utf-8"))
        connection.shutdown(socket.SHUT_WR)
        chunks = []
        while True:
            chunk = connection.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
        return b"".join(chunks).decode("utf-8").rstrip("\r\n")


def alive(port):
    try:
        return 0 < port < 65536 and request(port, ["rwx-ping"], 1) == "pong"
    except (OSError, ValueError, UnicodeError):
        return False


def port_from_file(path):
    try:
        port = int(Path(path).read_text(encoding="ascii").strip())
        return port if alive(port) else None
    except (OSError, ValueError):
        return None


def discover(explicit_port=None, port_file=None):
    if explicit_port is not None:
        if alive(explicit_port):
            return explicit_port
        raise ValueError("Specified CLI port is not a live RWX endpoint")
    configured = port_file or os.environ.get("RWX_CLI_PORT_FILE")
    primary = Path(configured) if configured else DEFAULT_PORT_FILE
    port = port_from_file(primary)
    if port:
        return port
    if configured:
        raise ValueError(f"Missing/stale instance port file: {primary}")
    instances = sorted(primary.with_name(primary.name + ".instances").glob("*.port"))
    candidates = {p for path in instances if (p := port_from_file(path))}
    if len(candidates) == 1:
        return candidates.pop()
    if len(candidates) > 1:
        raise ValueError("Multiple RWX instances: select --port-file or --port explicitly")
    raise ValueError(f"No live RWX CLI; start with --rwx-cli or --headless (stale file ignored: {primary})")


def framed(port, lines, timeout):
    """One persistent TCP connection; no shared request/response mailbox."""
    with socket.create_connection(("127.0.0.1", port), timeout=3) as connection:
        connection.settimeout(timeout)
        stream = connection.makefile("rwb", buffering=0)

        def reply():
            while True:
                byte = stream.read(1)
                if not byte:
                    raise EOFError("CLI connection closed")
                if byte == b"\x1e":
                    break
            data = bytearray()
            while True:
                byte = stream.read(1)
                if not byte:
                    raise EOFError("CLI reply was truncated")
                if byte == b"\x1e":
                    return data.decode("utf-8")
                data.extend(byte)

        stream.write(b"!!framed\n")
        if reply() != "ready":
            raise ValueError("Unexpected CLI handshake")
        failed = False
        for line in lines:
            args = shlex.split(line)
            if not args or args[0].startswith("#"):
                continue
            encoded = ",".join(base64.b64encode(a.encode()).decode() for a in args[1:])
            stream.write((args[0] + "\t" + encoded + "\n").encode())
            output = reply()
            print(output, flush=True)
            failed |= output.startswith("error:")
        return 4 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int)
    parser.add_argument("--port-file")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--line")
    parser.add_argument("--batch-file")
    parser.add_argument("--interactive", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    opts = parser.parse_args()
    try:
        port = discover(opts.port, opts.port_file)
        if opts.list:
            print(f"CLI port: {port} (loopback)")
            return 0
        if opts.batch_file:
            return framed(port, Path(opts.batch_file).read_text(encoding="utf-8-sig").splitlines(), opts.timeout)
        if opts.interactive:
            def lines():
                while True:
                    try:
                        line = input("rwx> ")
                    except EOFError:
                        return
                    if line in ("exit", "quit", ":q"):
                        return
                    yield line
            return framed(port, lines(), opts.timeout)
        arguments = shlex.split(opts.line) if opts.line else opts.command
        if not arguments:
            parser.error("supply a command, --batch-file, --line or --interactive")
        output = request(port, arguments, opts.timeout)
        print(output)
        return 4 if output.startswith("error:") else 0
    except (OSError, ValueError, EOFError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
