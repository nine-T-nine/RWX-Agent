"""Protocol regression tests: python -m unittest discover -s agent-operation/tools -p test_rwx_cli.py"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("rwx-cli.py")
spec = importlib.util.spec_from_file_location("rwx_cli", SCRIPT)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        framed = False
        for line in self.rfile:
            if line == b"!!framed\n":
                framed = True
                self.wfile.write(b"\x1eready\x1e")
                continue
            cmd, _, encoded = line.rstrip(b"\r\n").partition(b"\t")
            if cmd == b"drop":
                return
            if cmd == b"truncated":
                self.wfile.write(b"\x1epartial")
                return
            if cmd == b"stall":
                threading.Event().wait(0.3)
                return
            if cmd == b"rwx-ping":
                data = b"pong"
            elif cmd == b"echo":
                import base64
                data = base64.b64decode(encoded)
            elif cmd == b"multi":
                data = "第一行\nsecond".encode()
            elif cmd == b"empty":
                data = b""
            else:
                data = b"error: unknown"
            self.wfile.write(b"\x1e" + data + b"\x1e" if framed else data + b"\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = Server(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_cli(self, *args, input=None):
        return subprocess.run([sys.executable, str(SCRIPT), "--port", str(self.port), *args],
                              input=input, text=True, encoding="utf-8", capture_output=True, timeout=5)

    def test_single_line_batch_and_stdio(self):
        self.assertEqual(self.run_cli("rwx-ping").stdout.strip(), "pong")
        self.assertEqual(self.run_cli("--line", 'echo "中文 空格"').stdout.strip(), "中文 空格")
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "batch.txt"
            f.write_text('# skip\nrwx-ping\necho "中文 空格"\n', encoding="utf-8")
            r = self.run_cli("--batch-file", str(f))
            self.assertEqual((r.returncode, r.stdout.splitlines()), (0, ["pong", "中文 空格"]))
        r = self.run_cli("--stdio", input='rwx-ping\necho "中文 空格"\nmulti\nempty\nbad\necho "bad\nrwx-ping\n')
        replies = [json.loads(line) for line in r.stdout.splitlines()]
        self.assertEqual([x["reply"] for x in replies], ["pong", "中文 空格", "第一行\nsecond", "", "error: unknown", "error: No closing quotation", "pong"])
        self.assertEqual([x["error"] for x in replies], [False, False, False, False, True, True, False])
        self.assertEqual(r.returncode, 4)
        self.assertEqual(r.stderr.strip(), "ready")

    def test_disconnect_timeout_and_port_selection(self):
        for cmd in ("drop", "truncated", "stall"):
            r = self.run_cli("--timeout", "0.05", "--stdio", input=cmd + "\n")
            self.assertEqual(r.returncode, 2)
            self.assertEqual(r.stdout, "")
            self.assertIn("error:", r.stderr)
        with tempfile.TemporaryDirectory() as d, Server(("127.0.0.1", 0), Handler) as other:
            threading.Thread(target=other.serve_forever, daemon=True).start()
            root = Path(d) / "port"
            directory = Path(str(root) + ".instances")
            directory.mkdir()
            (directory / "a.port").write_text(str(self.port))
            (directory / "b.port").write_text(str(other.server_address[1]))
            with mock.patch.object(cli, "DEFAULT_PORT_FILE", root), mock.patch.dict(os.environ, {}, clear=True):
                with self.assertRaisesRegex(ValueError, "Multiple"):
                    cli.discover()
                self.assertEqual(cli.discover(port_file=directory / "a.port"), self.port)
                root.write_text(str(self.port))
                self.assertEqual(cli.discover(), self.port)
                with self.assertRaisesRegex(ValueError, "Missing/stale"):
                    cli.discover(port_file=root.parent / "missing")
            other.shutdown()

    def test_stdio_flushes_before_next_command_and_ignores_comments(self):
        p = subprocess.Popen([sys.executable, str(SCRIPT), "--port", str(self.port), "--stdio"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", bufsize=1)
        try:
            self.assertEqual(p.stderr.readline().strip(), "ready")
            p.stdin.write('# comment\n\nrwx-ping\n')
            p.stdin.flush()
            self.assertEqual(json.loads(p.stdout.readline())["reply"], "pong")
            p.stdin.write('multi\n')
            p.stdin.flush()
            self.assertEqual(json.loads(p.stdout.readline())["reply"], "第一行\nsecond")
            p.stdin.close()
            self.assertEqual(p.wait(timeout=3), 0)
        finally:
            if p.poll() is None:
                p.kill()
                p.wait()
            p.stdout.close()
            p.stderr.close()


if __name__ == "__main__":
    unittest.main()
