#!/usr/bin/env python3
"""托管房间多轮联机验收。

前置条件：一个 Headless/CLI 房主进程和三个已启动的客户端进程（各带 CLI 端口文件）。
脚本会依次验证：

1. 大厅按加入顺序选举房主，非房主修改设置被拒绝，房主可改设置/加 AI；
2. 房主离开后仅在大厅移交，原玩家回来后不抢回权限；
3. 对局中加入自动观战；对局中房主离开不移交；最后一个在线玩家离开后返回大厅；
4. 连续多局：每局从大厅开局后 tick 必须推进（回归“第二局卡在 tick 0”）。

用法示例（Windows 客户端 + WSL 房主）：

    python agent-operation/tools/verify-managed-room.py --host-wsl Ubuntu-22.04 --rounds 2

只使用公开 CLI；失败时抛出 AssertionError 并打印最后一条输出。
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "agent-operation/tools/rwx-cli.py"


def load_cli():
    spec = importlib.util.spec_from_file_location("rwx_cli", CLI)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wsl_path(path: Path) -> str:
    text = str(path).replace("\\", "/")
    if len(text) > 1 and text[1] == ":":
        return "/mnt/" + text[0].lower() + text[2:]
    return text


class ManagedRoom:
    def __init__(self, args) -> None:
        self.args = args
        self.cli = load_cli()
        self.last = ""

    def call(self, who: str, *argv: str) -> str:
        if who == "host" and self.args.host_wsl:
            command = ["wsl", "-d", self.args.host_wsl, "--", "python3", wsl_path(CLI),
                       "--port-file", self.args.host_port_file, *argv]
            result = subprocess.run(command, capture_output=True, timeout=60)
            output = result.stdout.decode("utf-8", "replace").strip()
        else:
            path = self.args.host_port_file if who == "host" else self.args.client[who]
            port = self.cli.discover(port_file=Path(path))
            output = self.cli.request(port, list(argv))
        self.last = output
        return output

    def wait(self, who: str, values: list[str], timeout: float = 60) -> str:
        end = time.monotonic() + timeout
        output = ""
        while time.monotonic() < end:
            output = self.call(who, "rwx-connection-status")
            if all(value in output for value in values):
                return output
            time.sleep(0.5)
        raise AssertionError(f"{who}: timed out waiting for {values}\n{output}")

    def require(self, who: str, *argv: str) -> str:
        output = self.call(who, *argv)
        if output.startswith("error:"):
            raise AssertionError(f"{who} {' '.join(argv)} failed: {output}")
        return output

    # ---- 场景 ----

    def start_room(self) -> None:
        for name in self.args.client:
            end = time.monotonic() + 60
            while not self.cli.port_from_file(Path(self.args.client[name])):
                assert time.monotonic() < end, f"CLI startup timeout: {name}"
                time.sleep(0.25)
        end = time.monotonic() + 60
        while self.call("host", "rwx-ping") != "pong":
            assert time.monotonic() < end, "host CLI startup timeout"
            time.sleep(0.25)
        self.require("host", "rwx-host", "--port", "5123", "--map", self.args.map,
                     "--managed-room", "true")

    def lobby(self) -> None:
        self.require("a", "rwx-connect", self.args.address, "--name", "Agent-A")
        self.wait("a", ["state: joined", "roomAdmin: true"])
        self.require("b", "rwx-connect", self.args.address, "--name", "Agent-B")
        self.wait("b", ["state: joined", "roomAdmin: false"])
        rejected = self.call("b", "rwx-battleroom-settings", "--income", "9")
        assert rejected.startswith("error:"), f"non-manager changed settings: {rejected}"
        self.require("a", "rwx-battleroom-settings", "--income", "2", "--fog", "none",
                     "--aiDifficulty", "impossible")
        self.require("a", "rwx-battleroom-add-ai", "--difficulty", "impossible")
        time.sleep(1)
        settings = self.require("b", "rwx-battleroom-show", "--settings")
        assert "2.0" in settings, settings
        self.require("a", "rwx-disconnect")
        self.wait("b", ["state: joined", "roomAdmin: true"])
        self.require("a", "rwx-connect", "--reconnect", "true")
        self.wait("a", ["state: joined", "roomAdmin: false"])
        self.require("b", "rwx-battleroom-start")
        self.wait("a", ["state: in-game"])
        self.wait("b", ["state: in-game", "roomAdmin: true"])
        print("lobby: PASS", flush=True)

    def late_join(self) -> None:
        if "state: in-game" not in self.call("c", "rwx-connection-status"):
            self.require("c", "rwx-connect", self.args.address, "--name", "Agent-C")
        self.wait("c", ["state: in-game", "spectator: true"])
        self.require("b", "rwx-disconnect")
        time.sleep(0.5)
        self.wait("a", ["state: in-game", "roomAdmin: false"])
        self.require("a", "rwx-disconnect")
        self.wait("c", ["state: joined", "roomAdmin: true"])
        self.wait("host", ["started: false"])
        print("late join + return to lobby: PASS", flush=True)

    def round(self, index: int) -> None:
        # 出生点/阵营改动是异步回包，必须等房间快照更新后再开局，否则会以旁观身份开局。
        deadline = time.monotonic() + 25
        while True:
            room = self.require("c", "rwx-battleroom-show", "--player")
            agent_line = next((line for line in room.splitlines() if line.startswith("Agent-C")), "")
            assert agent_line, room
            spawn = re.search(r"出生点 (\d+)", agent_line)
            if "Spec" not in agent_line and "阵营 B" in agent_line:
                break
            if "Spec" in agent_line:
                occupied = any("出生点 1 " in line and not line.startswith("Agent-C")
                               for line in room.splitlines())
                self.call("c", "rwx-battleroom-set-player", "--player", "Agent-C",
                          "--move", "2" if occupied else "1")
            else:
                self.call("c", "rwx-battleroom-set-player", "--player", "Agent-C", "--switch", "B")
            assert time.monotonic() < deadline, f"player setup did not settle: {agent_line}"
            time.sleep(1)
        self.require("c", "rwx-battleroom-start")
        self.wait("c", ["state: in-game"])
        deadline = time.monotonic() + 30
        state = ""
        while time.monotonic() < deadline:
            state = self.require("c", "rwx-game-state", "--summary", "--json")
            tick = int(re.search(r'"tick":(\d+)', state).group(1))
            if tick > 60:
                # 托管策略要等开局后的准备期结束才检查“还有没有活人”，这里必须比它活得久，
                # 否则会把“4 秒后误判无人、直接回大厅”当成通过。
                time.sleep(8)
                status = self.call("c", "rwx-connection-status")
                assert "state: in-game" in status, f"round {index} returned early: {status}"
                print(f"round {index}: PASS (tick {tick})", flush=True)
                return
            time.sleep(1)
        raise AssertionError(f"round {index} never advanced: {state}")

    def end_round(self) -> None:
        self.require("c", "rwx-disconnect")
        self.wait("host", ["started: false"], timeout=90)
        self.require("c", "rwx-connect", "--reconnect", "true")
        self.wait("c", ["state: joined", "roomAdmin: true"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", default="192.168.101.63:5123")
    parser.add_argument("--map", default="King of the Middle (3p)")
    parser.add_argument("--host-port-file", default="agent-operation/tmp/verify-linux-host/port")
    parser.add_argument("--host-wsl", default="")
    parser.add_argument("--client", action="append", default=[], metavar="NAME=PORT_FILE")
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()
    if not args.client:
        args.client = [f"{name}=agent-operation/tmp/verify-{name}/port" for name in "abc"]
    args.client = dict(item.split("=", 1) for item in args.client)

    room = ManagedRoom(args)
    try:
        room.start_room()
        room.lobby()
        room.late_join()
        for index in range(1, args.rounds + 1):
            room.round(index)
            room.end_round()
    except AssertionError:
        print("FAILED:", room.last, file=sys.stderr)
        raise
    print("MANAGED_ROOM_PASS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
