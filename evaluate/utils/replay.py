"""RWX 回放（.replay）反解析工具。

把游戏内部录制格式 reverse 解析成"命令执行文本"，用于评测、复盘和 RL 数据抽取。

用法
====

命令行（三种模式，可叠加；多文件时配 --out 落盘）::

    # 1) 概要：地图、队伍、帧数、各队命令数、块统计、文件健康度
    python evaluate/utils/replay.py replays/xxx.replay --summary

    # 2) 命令文本：一行一条命令，可直接阅读/对比
    python evaluate/utils/replay.py replays/xxx.replay --text

    # 3) JSONL：一行一个 JSON 对象，便于喂给数据集/评测脚本
    python evaluate/utils/replay.py replays/xxx.replay --jsonl

    # 批量落盘（--out 时默认文件名 = 清洗后的回放名；--name 可覆盖）
    python evaluate/utils/replay.py replays/*.replay --summary --text --out evaluate/output

    # 给样例产物起可读别名（避免与 CommandRecorder 的 commands-*.txt 混淆）
    python evaluate/utils/replay.py replays/xxx.replay --text --jsonl \
        --out evaluate/output --name replay-xxx

    # 多文件概要汇总成 <out>/replays.index.txt
    python evaluate/utils/replay.py replays/*.replay --summary --out evaluate/output --index

    # 只关心某个队伍 / 某个时间窗，并限制行数
    python evaluate/utils/replay.py replays/xxx.replay --text --team 1 \
        --from-tick 600 --to-tick 3000 --limit 200

    # 文本里附带 [分:秒.十分位] 游戏时间（换算口径见表头 # clock / 概要"时间口径"）
    python evaluate/utils/replay.py replays/xxx.replay --text --clock

    # 在文本里夹带 es/cs 校验行（默认不显示）
    python evaluate/utils/replay.py replays/xxx.replay --text --checksums

    # 严格模式：任何结构异常直接抛异常（默认容错，截断/损坏文件也能出部分结果）
    python evaluate/utils/replay.py replays/xxx.replay --summary --strict

库调用::

    from replay import Replay

    replay = Replay.load("replays/xxx.replay")
    replay.header.version_string        # "1.15"
    replay.meta.map_path                # "maps/skirmish/[p2]Lake (2p).tmx"
    replay.meta.teams[1].name           # "Remote-B"
    for cmd in replay.commands:
        cmd.tick, cmd.team_id, cmd.unit_command.type
    print(replay.render_text())
    rows = replay.order_rows()          # 结构化命令（可直接 json.dumps）
    print(replay.summary())

    # 只想要块级访问（超大文件流式扫）
    for block_name, payload in Replay.iter_blocks("replays/xxx.replay"):
        ...

格式要点（详见同目录 ``replay.md``）
====================================

容器::

    UTF  "rustedWarfareReplay" | int versionCode | int protocol(96) | UTF version | bool demo
    之后是连续的块: UTF blockName | int payloadLen | payload[payloadLen]

块可能被 gzip 压缩（Java 侧建块时决定），这里按 ``1f 8b`` magic 自动识别。

``rc`` 块 = ``int tick`` + 内嵌块 ``c``（命令本体，见 ``_read_command``）；
``gamesave`` 块 = 一份完整初始存档（内层 gzip），可从中取出地图路径、队伍表、
初始单位 ``objectId -> 单位类型`` 表。

输出文件命名（``--out`` 时）::

    <stem>.txt           命令文本（--text）
    <stem>.jsonl         每行一个 JSON（--jsonl）
    <stem>.summary.txt   概要（--summary）
    replays.index.txt    多文件概要汇总（--summary + --index）

``<stem>`` 默认取回放文件名清洗后的结果，可用 ``--name`` 覆盖。

已知限制
========

* 命令里的单位是**运行期 objectId**，跨对局不复用；只有开局单位能从 ``gamesave``
  的初始对象表映射到类型，之后新造的单位只能显示 ``#123``。
* 回放不记录"建造产生的新 id"，无法反推每个新单位是什么。
* 截断或损坏的录制（仓库历史样本里出现过）只能解析到出错处，``partial`` 为真。
* 时间换算：``gameTimeMillis`` 每 tick 推进约 16 ms（本地样本实测 ``frames × 16 == time``
  精确成立，见 ``Replay.ms_per_tick``）。所以 tick × 16 ms 在"整局 1× 速度"时精确；一旦有
  改速度命令（系统命令或联机步进），每 tick 的毫秒数会变，工具按 ``resync``/结尾锚点分段线性
  估算，并在文本表头 ``# clock``、概要"时间口径"里标明是精确还是估算。
  真实墙钟时间无法还原：暂停、加速、掉帧都不进 tick。

相关工具
========

同目录 ``CommandRecorder.java`` 从本机 CLI Socket 在线记录**外部下发**的命令；
本工具离线解析 ``.replay``，能还原对局里执行过的**全部**命令（含 AI 与内置 UI 操作）。
"""

from __future__ import annotations

import argparse
import dataclasses
import gzip
import json
import os
import re
import struct
import sys
import zlib
from typing import Dict, Iterator, List, Optional, Sequence, Tuple

__all__ = [
    "Replay",
    "ReplayHeader",
    "ReplayMeta",
    "TeamInfo",
    "ReplayCommand",
    "UnitCommandData",
    "CommandTargetData",
    "ChatLine",
    "Checkpoint",
    "EndMeta",
    "ReplayError",
    "UnsupportedFormat",
    "TruncatedData",
    "UNIT_TYPE_NAMES",
    "UNIT_COMMAND_TYPES",
    "ATTACK_MODES",
    "SYSTEM_ACTIONS",
    "TICKS_PER_SECOND",
    "NOMINAL_MS_PER_TICK",
]

# ---------------------------------------------------------------------------
# 常量表（必须与引擎一致，顺序即序列化序号）
# ---------------------------------------------------------------------------

#: 标称模拟频率；回放只记 tick，毫秒时长会受游戏速度命令影响。
TICKS_PER_SECOND = 62.5
TICK_SECONDS = 1.0 / TICKS_PER_SECOND
#: 实测标称值：edGameLogic.update 里 gameTimeMillis 每 tick 推进约 16 ms。
NOMINAL_MS_PER_TICK = 16.0

#: core/src/main/java/com/corrodinggames/rts/game/units/UnitCommandType.java
UNIT_COMMAND_TYPES: Tuple[str, ...] = (
    "move", "attack", "build", "repair", "loadInto", "unloadAt", "reclaim",
    "attackMove", "loadUp", "patrol", "guard", "guardAt", "touchTarget",
    "follow", "triggerAction", "triggerActionWhenInRange", "setPassiveTarget",
)

#: core/src/main/java/com/corrodinggames/rts/game/units/AttackMode.java
ATTACK_MODES: Tuple[str, ...] = (
    "outOfRange", "onlyInRange", "returnFire", "holdFire", "guardArea",
    "aggressive", "mixed",
)

#: core/src/main/java/com/corrodinggames/rts/game/units/UnitTypeEnum.java 声明顺序
UNIT_TYPE_NAMES: Tuple[str, ...] = (
    "extractor", "landFactory", "airFactory", "seaFactory", "commandCenter", "turret",
    "antiAirTurret", "builder", "tank", "hoverTank", "artillery", "helicopter", "airShip",
    "gunShip", "missileShip", "gunBoat", "megaTank", "laserTank", "hovercraft", "ladybug",
    "battleShip", "tankDestroyer", "heavyTank", "heavyHoverTank", "laserDefence", "dropship",
    "tree", "repairbay", "NukeLaucher", "AntiNukeLaucher", "mammothTank", "experimentalTank",
    "experimentalLandFactory", "crystalResource", "wall_v", "fabricator", "attackSubmarine",
    "builderShip", "amphibiousJet", "supplyDepot", "experimentalHoverTank", "turret_artillery",
    "turret_flamethrower", "fogRevealer", "spreadingFire", "antiAirTurretT2", "turretT2",
    "turretT3", "damagingBorder", "zoneMarker", "editorOrBuilder", "dummyNonUnitWithTeam",
)

#: Command.systemActionType 语义（见 Command.executeCommand）
SYSTEM_ACTIONS: Dict[int, str] = {
    1: "debug:desync-detector",
    2: "debug:desync-stress",
    5: "spawn-unit",
    100: "surrender",
    200: "quick-resync",
}

#: 回放里的对象表 kind（GameSaver 的 unit shells 段）
SHELL_KINDS = {1: "unit", 2: "object", 3: "custom-unit"}
OBJECT_SUBKINDS = {1: "scorch", 2: "projectile", 3: "effect"}

REPLAY_MAGIC = "rustedWarfareReplay"
SAVE_MAGIC = "rustedWarfareSave"
SUPPORTED_PROTOCOL = 96
MAGIC_SHORT = 12345
TEAM_NEUTRAL_DEFAULT = 10
GZIP_MAGIC = b"\x1f\x8b"


# ---------------------------------------------------------------------------
# 异常
# ---------------------------------------------------------------------------


class ReplayError(Exception):
    """回放结构错误（头部、块框架、命令载荷）。"""


class UnsupportedFormat(ReplayError):
    """结构合法但当前实现不支持（例如带区域控制/传送门快照的地图）。"""


class TruncatedData(ReplayError):
    """数据在中途截断（未完成录制或文件损坏）。"""


# ---------------------------------------------------------------------------
# Java DataOutputStream.writeUTF 兼容编解码（modified UTF-8）
# ---------------------------------------------------------------------------


def decode_modified_utf8(raw: bytes) -> str:
    """解码 Java ``writeUTF`` 的 modified UTF-8。

    与标准 UTF-8 的区别：NUL 写成 ``C0 80``，补充平面字符用 CESU-8（代理对）。
    """
    units: List[int] = []
    i = 0
    n = len(raw)
    while i < n:
        b = raw[i]
        if b < 0x80:
            units.append(b)
            i += 1
        elif (b & 0xE0) == 0xC0:
            if i + 1 >= n:
                raise TruncatedData("modified utf-8: 2 字节序列被截断")
            units.append(((b & 0x1F) << 6) | (raw[i + 1] & 0x3F))
            i += 2
        elif (b & 0xF0) == 0xE0:
            if i + 2 >= n:
                raise TruncatedData("modified utf-8: 3 字节序列被截断")
            units.append(
                ((b & 0x0F) << 12) | ((raw[i + 1] & 0x3F) << 6) | (raw[i + 2] & 0x3F)
            )
            i += 3
        else:
            raise ReplayError(f"modified utf-8: 非法首字节 0x{b:02x}（偏移 {i}）")
    # 以 UTF-16BE 码元还原，能自动合并代理对；非法码元用替代字符兜底，避免中文地图名解析中断
    packed = bytearray()
    for unit in units:
        packed += struct.pack(">H", unit & 0xFFFF)
    return packed.decode("utf-16-be", "replace")


def encode_modified_utf8(text: str) -> bytes:
    """写侧实现（测试与合成样本用），与 ``decode_modified_utf8`` 对称。"""
    out = bytearray()
    for char in text:
        code = ord(char)
        if code == 0:
            out += b"\xc0\x80"
        elif code < 0x80:
            out.append(code)
        elif code < 0x800:
            out.append(0xC0 | (code >> 6))
            out.append(0x80 | (code & 0x3F))
        elif code < 0x10000:
            out.append(0xE0 | (code >> 12))
            out.append(0x80 | ((code >> 6) & 0x3F))
            out.append(0x80 | (code & 0x3F))
        else:
            code -= 0x10000
            high = 0xD800 + (code >> 10)
            low = 0xDC00 + (code & 0x3FF)
            for unit in (high, low):
                out.append(0xE0 | (unit >> 12))
                out.append(0x80 | ((unit >> 6) & 0x3F))
                out.append(0x80 | (unit & 0x3F))
    return bytes(out)


# ---------------------------------------------------------------------------
# 字节读取器
# ---------------------------------------------------------------------------


class _Reader:
    """带边界检查的大端读取器（对应 Java DataInputStream）。"""

    __slots__ = ("buf", "pos")

    def __init__(self, buf: bytes):
        self.buf = buf
        self.pos = 0

    # -- 基础状态 -----------------------------------------------------
    def __len__(self) -> int:
        return len(self.buf)

    @property
    def remaining(self) -> int:
        return len(self.buf) - self.pos

    def eof(self) -> bool:
        return self.pos >= len(self.buf)

    # -- 原始读取 -----------------------------------------------------
    def take(self, n: int) -> bytes:
        if n < 0:
            raise ReplayError(f"读取长度为负数: {n}")
        end = self.pos + n
        if end > len(self.buf):
            raise TruncatedData(
                f"需要 {n} 字节，仅剩 {self.remaining} 字节（偏移 {self.pos}）"
            )
        chunk = self.buf[self.pos:end]
        self.pos = end
        return chunk

    def byte(self) -> int:
        return struct.unpack(">b", self.take(1))[0]

    def ubyte(self) -> int:
        return self.take(1)[0]

    def boolean(self) -> bool:
        return self.byte() != 0

    def int(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def uint(self) -> int:
        return struct.unpack(">I", self.take(4))[0]

    def short(self) -> int:
        return struct.unpack(">h", self.take(2))[0]

    def long(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def float(self) -> float:
        return struct.unpack(">f", self.take(4))[0]

    def utf(self) -> str:
        n = struct.unpack(">H", self.take(2))[0]
        return decode_modified_utf8(self.take(n))

    def nullable_utf(self) -> Optional[str]:
        return self.utf() if self.boolean() else None

    def nullable_int(self) -> Optional[int]:
        return self.int() if self.boolean() else None

    def bytes_with_length(self) -> bytes:
        return self.take(self.int())


def _maybe_gunzip(payload: bytes) -> Tuple[bytes, bool]:
    """块载荷可能是 gzip；按 magic 判断，失败时保留原样。"""
    if payload[:2] == GZIP_MAGIC:
        try:
            return gzip.decompress(payload), True
        except (OSError, EOFError, zlib.error) as exc:
            raise UnsupportedFormat(f"gzip 载荷不完整: {exc}") from exc
    return payload, False


def _read_block(reader: _Reader, what: str = "block") -> Tuple[str, bytes]:
    name = reader.utf()
    size = reader.int()
    if size < 0:
        raise ReplayError(f"{what}: 块 {name!r} 长度非法 {size}")
    return name, reader.take(size)


# ---------------------------------------------------------------------------
# 数据模型
# ---------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class ReplayHeader:
    version_code: int
    protocol: int
    version_string: str
    demo: bool


@dataclasses.dataclass(frozen=True)
class TeamInfo:
    team_id: int
    name: Optional[str]
    color_id: int
    credits: int
    is_ai: bool
    is_ai_player: bool
    is_observer: bool
    is_spectator: bool
    sort_index: int
    is_victory: bool
    ai_hint: Optional[str]
    network_id: int


@dataclasses.dataclass
class ReplayMeta:
    """内嵌 gamesave 里能稳定取出的元数据（解析失败时字段为默认值）。"""

    map_path: Optional[str] = None
    game_time_ms: int = 0
    view: Tuple[float, float, float] = (0.0, 0.0, 0.0)
    is_game_started: bool = False
    fog_flags: Tuple[bool, bool, bool] = (False, False, False)
    player_team_id: int = 0
    teams: Dict[int, TeamInfo] = dataclasses.field(default_factory=dict)
    unit_shells: Dict[int, str] = dataclasses.field(default_factory=dict)
    setup_networked: Optional[bool] = None
    custom_unit_bytes: int = 0
    embedded_map_bytes: int = 0
    note: Optional[str] = None

    def team_label(self, team_id: int) -> str:
        team = self.teams.get(team_id)
        if team is not None and team.name:
            return f"{team.name}(t{team_id})"
        return {0xFF: "TEAM_ALL", 0xFE: "TEAM_UNKNOWN"}.get(team_id & 0xFF, f"t{team_id}")

    def unit_label(self, unit_id: int) -> str:
        if unit_id == -1:
            return "-"
        kind = self.unit_shells.get(unit_id)
        return f"#{unit_id}({kind})" if kind else f"#{unit_id}"


@dataclasses.dataclass(frozen=True)
class UnitCommandData:
    type: Optional[str]
    build_type: Optional[str]
    target_x: float
    target_y: float
    target_unit_id: int
    build_queue_size: int
    attack_move_range: float
    max_waypoint_surviving_ms: float
    is_repeating: bool
    is_queued: bool
    is_force_move: bool
    action_id: Optional[str]


@dataclasses.dataclass(frozen=True)
class CommandTargetData:
    unit_id: int
    start: Tuple[float, float]
    target: Tuple[float, float]
    created_tick: int
    movement_type: Optional[int]
    path_points: int


@dataclasses.dataclass
class ReplayCommand:
    tick: int
    team_id: int
    unit_command: Optional[UnitCommandData]
    is_queued: bool
    stop_current_action: bool
    attack_mode: Optional[str]
    rally_point: Optional[Tuple[float, float]]
    clear_existing_orders: bool
    unit_ids: List[int]
    source_team_id: Optional[int]
    target_point: Optional[Tuple[float, float]]
    target_unit_id: int
    action_id: str
    is_instant: bool
    allowed_team_mask: int
    is_system_action: bool
    game_speed_change: float
    system_float: float
    system_action_type: int
    command_targets: List[CommandTargetData]
    is_high_priority: bool
    payload_size: int

    # -- 语义辅助 -----------------------------------------------------
    @property
    def system_action_name(self) -> Optional[str]:
        if not self.is_system_action:
            return None
        return SYSTEM_ACTIONS.get(self.system_action_type, f"system:{self.system_action_type}")

    @property
    def is_orderless(self) -> bool:
        """既没有单位指令也没有动作（纯 attackMode / 清空命令）。"""
        return self.unit_command is None and self.action_id in ("", "-1")


@dataclasses.dataclass(frozen=True)
class ChatLine:
    tick: int
    sender_id: int
    sender_name: Optional[str]
    message: Optional[str]


@dataclasses.dataclass(frozen=True)
class Checkpoint:
    tick: int
    kind: str  # "es" | "cs" | "resync"
    detail: object


@dataclasses.dataclass(frozen=True)
class EndMeta:
    frames: int
    game_time_ms: int
    command_count: int
    resync_count: int
    raw: str


# ---------------------------------------------------------------------------
# 命令载荷解析（对应 Command.deserializeCommand，protocol 96）
# ---------------------------------------------------------------------------


def _read_unit_type(reader: _Reader) -> Optional[str]:
    value = reader.int()
    if value == -1:
        return None
    if value == -2:
        return "custom:" + reader.utf()
    if 0 <= value < len(UNIT_TYPE_NAMES):
        return UNIT_TYPE_NAMES[value]
    return f"type#{value}"


def _read_unit_command(reader: _Reader) -> UnitCommandData:
    raw_type = reader.int()
    cmd_type = (
        UNIT_COMMAND_TYPES[raw_type]
        if 0 <= raw_type < len(UNIT_COMMAND_TYPES)
        else (None if raw_type == -1 else f"#{raw_type}")
    )
    build_type = _read_unit_type(reader)
    target_x = reader.float()
    target_y = reader.float()
    target_unit_id = reader.long()
    build_queue_size = reader.byte()
    attack_move_range = reader.float()
    max_waypoint_ms = reader.float()
    is_repeating = reader.boolean()
    is_queued = reader.boolean()
    is_force_move = reader.boolean()
    action_id = reader.nullable_utf()
    return UnitCommandData(
        type=cmd_type,
        build_type=build_type,
        target_x=target_x,
        target_y=target_y,
        target_unit_id=target_unit_id,
        build_queue_size=build_queue_size,
        attack_move_range=attack_move_range,
        max_waypoint_surviving_ms=max_waypoint_ms,
        is_repeating=is_repeating,
        is_queued=is_queued,
        is_force_move=is_force_move,
        action_id=action_id,
    )


def _read_path(reader: _Reader) -> int:
    """Path 序列化：bool(pathPoints!=null) + [块 "p"（gzip）]。返航点数。"""
    if not reader.boolean():
        return 0
    name, payload = _read_block(reader, "path")
    if name != "p":
        raise ReplayError(f"路径块名异常: 期望 'p'，实际 {name!r}")
    inner, _ = _maybe_gunzip(payload)
    points = _Reader(inner)
    count = points.int()
    if count < 0 or count > 160000:
        raise ReplayError(f"路径点数量异常: {count}")
    return count


def _read_command_target(reader: _Reader) -> CommandTargetData:
    unit_id = reader.long()
    start = (reader.float(), reader.float())
    target = (reader.float(), reader.float())
    created_tick = reader.int()
    movement_type = reader.int()
    path_points = 0
    if reader.boolean():  # hasPath
        path_points = _read_path(reader)
    return CommandTargetData(
        unit_id=unit_id,
        start=start,
        target=target,
        created_tick=created_tick,
        movement_type=None if movement_type == -1 else movement_type,
        path_points=path_points,
    )


def _read_attack_mode(reader: _Reader) -> Optional[str]:
    value = reader.int()
    if value == -1:
        return None
    if 0 <= value < len(ATTACK_MODES):
        return ATTACK_MODES[value]
    return f"#{value}"


def _read_command(reader: _Reader, tick: int, payload_size: int) -> ReplayCommand:
    """解析内嵌块 ``c`` 的载荷（调用方已完成 startBlockNamed("c")）。"""
    team_id = reader.byte()
    has_unit_command = reader.boolean()
    unit_command = _read_unit_command(reader) if has_unit_command else None
    is_queued = reader.boolean()
    stop_current_action = reader.boolean()
    reader.int()  # 遗留的 actionId 整型，恒 -1；protocol>=33 后由末尾 UTF 覆盖
    attack_mode = _read_attack_mode(reader)
    rally_point = (reader.float(), reader.float()) if reader.boolean() else None
    clear_existing_orders = reader.boolean()
    unit_count = reader.int()
    if unit_count < 0 or unit_count > 1_000_000:
        raise ReplayError(f"命令单位数量异常: {unit_count}")
    unit_ids = [reader.long() for _ in range(unit_count)]
    source_team_id = reader.byte() if reader.boolean() else None
    target_point = (reader.float(), reader.float()) if reader.boolean() else None
    target_unit_id = reader.long()
    action_id = reader.utf()
    is_instant = reader.boolean()
    allowed_team_mask = reader.short()
    is_system_action = reader.boolean()
    game_speed_change = 0.0
    system_float = 0.0
    system_action_type = 0
    if is_system_action:
        reader.byte()
        game_speed_change = reader.float()
        system_float = reader.float()
        system_action_type = reader.int()
    target_count = reader.int()
    if target_count < 0 or target_count > 1_000_000:
        raise ReplayError(f"命令目标数量异常: {target_count}")
    command_targets = [_read_command_target(reader) for _ in range(target_count)]
    is_high_priority = reader.boolean()
    return ReplayCommand(
        tick=tick,
        team_id=team_id,
        unit_command=unit_command,
        is_queued=is_queued,
        stop_current_action=stop_current_action,
        attack_mode=attack_mode,
        rally_point=rally_point,
        clear_existing_orders=clear_existing_orders,
        unit_ids=unit_ids,
        source_team_id=source_team_id,
        target_point=target_point,
        target_unit_id=target_unit_id,
        action_id=action_id,
        is_instant=is_instant,
        allowed_team_mask=allowed_team_mask,
        is_system_action=is_system_action,
        game_speed_change=game_speed_change,
        system_float=system_float,
        system_action_type=system_action_type,
        command_targets=command_targets,
        is_high_priority=is_high_priority,
        payload_size=payload_size,
    )


# ---------------------------------------------------------------------------
# 内嵌存档元数据
# ---------------------------------------------------------------------------

_UNSUPPORTED_MISSION_SNAPSHOT = "地图包含区域控制/传送门快照，元数据解析到此为止"


def _skip_mission(reader: _Reader) -> int:
    """跳过 MissionEngine 状态块。返回 missionStateVersion。"""
    reader.boolean()                       # showIntro
    for _ in range(5):                     # r,u,v,w,x
        reader.int()
    for _ in range(3):                     # z,A,B
        reader.float()
    reader.boolean()                       # isDefeatWave
    state_version = reader.int()
    for _ in range(reader.int()):          # mapTrigger 列表
        reader.utf()
        reader.boolean()
        reader.int()
        reader.int()
        reader.boolean()
        reader.int()
    reader.int()                           # y
    reader.boolean()                       # survivalWavesClassic
    if state_version >= 7 and reader.boolean():   # hasAreaControlMode
        raise UnsupportedFormat(_UNSUPPORTED_MISSION_SNAPSHOT)
    if state_version >= 8 and reader.boolean():   # hasMapPortalMode
        raise UnsupportedFormat(_UNSUPPORTED_MISSION_SNAPSHOT)
    return state_version


def _read_team(reader: _Reader) -> TeamInfo:
    team_id = reader.byte()
    credits = reader.int()
    color_id = reader.int()
    name = reader.nullable_utf()
    is_observer = reader.boolean()
    network_id = reader.int()
    reader.long()                            # teamLastPingTime
    is_spectator = reader.boolean()
    reader.int()                             # teamPingTime
    sort_index = reader.int()
    reader.byte()
    reader.boolean()                         # isTeamConnectionActive
    reader.boolean()                         # isTeamNetworkActive
    is_victory = reader.boolean()
    reader.boolean()                         # teamSurrenderTriggered
    reader.int()                             # surrenderVoteTimeMillis
    ai_hint = reader.nullable_utf()
    reader.int()                             # hostTeamFlag
    reader.nullable_int()                    # aiDifficultyOverride
    reader.nullable_int()                    # startingUnitsOverride
    reader.nullable_int()                    # aiLevelOverride
    reader.nullable_int()                    # playerColorOverride
    reader.int()                             # assignedTeamColorIndex
    return TeamInfo(
        team_id=team_id,
        name=name,
        color_id=color_id,
        credits=credits,
        is_ai=False,
        is_ai_player=False,
        is_observer=is_observer,
        is_spectator=is_spectator,
        sort_index=sort_index,
        is_victory=is_victory,
        ai_hint=ai_hint,
        network_id=network_id,
    )


def _parse_save_meta(payload: bytes, meta: ReplayMeta) -> ReplayMeta:
    """从 ``gamesave`` 块里取出地图、队伍、初始单位表。失败时尽量保留已有字段。"""
    reader = _Reader(payload)
    if reader.utf() != SAVE_MAGIC:
        raise ReplayError(f"内嵌存档头不是 {SAVE_MAGIC!r}")
    reader.int()                             # versionCode
    protocol = reader.int()
    if protocol != SUPPORTED_PROTOCOL:
        raise UnsupportedFormat(f"内嵌存档 protocol={protocol}，仅支持 {SUPPORTED_PROTOCOL}")
    reader.boolean()                         # demo

    name, comp = _read_block(reader, "save")
    if name != "saveCompression":
        raise ReplayError(f"存档块名异常: 期望 'saveCompression'，实际 {name!r}")
    inner, compressed = _maybe_gunzip(comp)
    if not compressed:
        raise UnsupportedFormat("saveCompression 未被压缩，格式与预期不符")

    body = _Reader(inner)
    for expected in ("customUnitsBlock", "gameSetup"):
        block_name, block_payload = _read_block(body, "save")
        if block_name != expected:
            raise ReplayError(f"存档块顺序异常: 期望 {expected!r}，实际 {block_name!r}")
        if expected == "customUnitsBlock":
            meta.custom_unit_bytes = len(block_payload)
        else:
            setup = _Reader(block_payload)
            networked = setup.boolean()
            setup.boolean()                  # singleplayerServer
            setup.boolean()                  # z（房间设置是否随包，本工具不需要内容）
            meta.setup_networked = networked

    meta.map_path = body.utf()
    if body.boolean():                       # 内嵌地图
        meta.embedded_map_bytes = len(body.take(body.int()))
    meta.game_time_ms = body.int()
    meta.view = (body.float(), body.float(), body.float())
    body.int()                               # nextFormationId
    body.int()                               # 保留字段
    magic = body.short()
    if magic != MAGIC_SHORT:
        raise ReplayError(f"存档段标记异常: {magic} != {MAGIC_SHORT}")
    body.boolean()                           # 光标选择是否存在（当前恒 false）
    meta.is_game_started = body.boolean()
    meta.fog_flags = (body.boolean(), body.boolean(), body.boolean())
    if body.boolean():                       # missionEngine != null
        _skip_mission(body)
    magic = body.short()
    if magic != MAGIC_SHORT:
        raise ReplayError(f"存档段标记异常: {magic} != {MAGIC_SHORT}")

    meta.player_team_id = body.int()
    team_count = body.int()
    if not 0 <= team_count <= 32:
        raise ReplayError(f"队伍槽位数异常: {team_count}")
    for _ in range(team_count):
        is_ai, is_ai_player, exists = body.boolean(), body.boolean(), body.boolean()
        if not exists:
            continue
        info = _read_team(body)
        meta.teams[info.team_id] = dataclasses.replace(
            info, is_ai=is_ai, is_ai_player=is_ai_player
        )

    shell_count = body.int()
    if not 0 <= shell_count <= 5_000_000:
        raise ReplayError(f"初始对象数量异常: {shell_count}")
    for _ in range(shell_count):
        kind = body.byte()
        if kind == 1:
            ordinal = body.int()
            label = (
                UNIT_TYPE_NAMES[ordinal]
                if 0 <= ordinal < len(UNIT_TYPE_NAMES)
                else f"type#{ordinal}"
            )
        elif kind == 3:
            label = "custom:" + body.utf()
        elif kind == 2:
            label = OBJECT_SUBKINDS.get(body.byte(), "object?")
        else:
            raise ReplayError(f"初始对象 kind 异常: {kind}")
        meta.unit_shells[body.long()] = label
    return meta


# ---------------------------------------------------------------------------
# 回放解析
# ---------------------------------------------------------------------------


class Replay:
    """一份解析后的回放。

    默认容错：截断/损坏的文件会解析到出错处，``errors`` 记录原因、``partial`` 为真。
    ``strict=True`` 时改为抛异常。
    """

    def __init__(self, path: str):
        self.path = path
        self.strict = False
        self.header: Optional[ReplayHeader] = None
        self.meta = ReplayMeta()
        self.commands: List[ReplayCommand] = []
        self.chats: List[ChatLine] = []
        self.checkpoints: List[Checkpoint] = []
        self.end_meta: Optional[EndMeta] = None
        self.block_counts: Dict[str, int] = {}
        self.unknown_blocks: List[str] = []
        self.errors: List[str] = []
        self.partial = False
        self.file_size = 0
        self.consumed_bytes = 0

    # -- 载入 ---------------------------------------------------------
    @classmethod
    def load(cls, path: str, strict: bool = False) -> "Replay":
        replay = cls(path)
        replay.strict = strict
        replay._parse()
        return replay

    @staticmethod
    def iter_blocks(path: str) -> Iterator[Tuple[str, bytes]]:
        """块级流式访问（只校验头部，不解析载荷）。"""
        with open(path, "rb") as handle:
            data = handle.read()
        reader = _Reader(data)
        magic = reader.utf()
        if magic != REPLAY_MAGIC:
            raise ReplayError(f"不是 RWX 回放文件（头部 {magic!r}）")
        reader.int()
        reader.int()
        reader.utf()
        reader.boolean()
        while not reader.eof():
            name, payload = _read_block(reader, "replay")
            yield name, payload

    def _fail(self, message: str) -> None:
        if self.strict:
            raise ReplayError(message)
        self.errors.append(message)
        self.partial = True

    def _parse(self) -> None:
        self.file_size = os.path.getsize(self.path)
        with open(self.path, "rb") as handle:
            reader = _Reader(handle.read())
        try:
            magic = reader.utf()
            if magic != REPLAY_MAGIC:
                raise ReplayError(f"不是 RWX 回放文件（头部 {magic!r}）")
            self.header = ReplayHeader(
                version_code=reader.int(),
                protocol=reader.int(),
                version_string=reader.utf(),
                demo=reader.boolean(),
            )
        except (ReplayError, TruncatedData) as exc:
            if self.strict:
                raise
            self.errors.append(f"头部解析失败: {exc}")
            self.partial = True
            return

        if self.header.protocol != SUPPORTED_PROTOCOL and self.strict:
            raise UnsupportedFormat(
                f"protocol={self.header.protocol}，本工具按 {SUPPORTED_PROTOCOL} 解析"
            )

        while not reader.eof():
            try:
                name, payload = _read_block(reader, "replay")
            except TruncatedData as exc:
                self._fail(f"块框架截断: {exc}")
                break
            except ReplayError as exc:
                self._fail(f"块框架损坏: {exc}")
                break
            self.block_counts[name] = self.block_counts.get(name, 0) + 1
            try:
                self._handle_block(name, payload)
            except TruncatedData as exc:
                self._fail(f"块 {name!r} 内容截断: {exc}")
                break
            except UnsupportedFormat as exc:
                # 元数据类问题不致命：继续解析命令流
                self.errors.append(f"块 {name!r} 部分解析: {exc}")
            except ReplayError as exc:
                self._fail(f"块 {name!r} 解析失败: {exc}")
                break
            # 注意：不能读到 "end" 就停——endReplayMetaData 排在 end 之后，
            # 只有把尾部读完才能拿到帧数/命令数。
        self.consumed_bytes = reader.pos

    def _handle_block(self, name: str, payload: bytes) -> None:
        if name == "rc":
            self._handle_command(payload)
        elif name == "chat":
            self._handle_chat(payload)
        elif name == "gamesave":
            self._handle_gamesave(payload)
        elif name in ("wait", "end"):
            return
        elif name == "cs":
            reader = _Reader(payload)
            self.checkpoints.append(
                Checkpoint(reader.int(), "cs", {"checksum": reader.long()})
            )
        elif name == "es":
            reader = _Reader(payload)
            tick = reader.int()
            body = _Reader(reader.take(reader.int()))
            flag = body.byte()
            fields = body.int()
            self.checkpoints.append(
                Checkpoint(tick, "es", {"trace": bool(flag), "fields": fields})
            )
        elif name == "resync":
            reader = _Reader(payload)
            tick = reader.int()
            resync_tick = reader.int()
            game_time_ms = reader.int()
            step_rate = reader.float()
            factor = reader.float()
            size = len(reader.take(reader.int()))
            self.checkpoints.append(
                Checkpoint(
                    tick,
                    "resync",
                    {
                        "resync_tick": resync_tick,
                        "game_time_ms": game_time_ms,
                        "step_rate": step_rate,
                        "factor": factor,
                        "save_bytes": size,
                    },
                )
            )
        elif name == "endReplayMetaData":
            reader = _Reader(payload)
            reader.byte()
            self.end_meta = EndMeta(
                frames=reader.int(),
                game_time_ms=reader.int(),
                command_count=reader.int(),
                resync_count=reader.int(),
                raw=reader.utf(),
            )
        else:
            self.unknown_blocks.append(name)

    def _handle_gamesave(self, payload: bytes) -> None:
        try:
            _parse_save_meta(payload, self.meta)
        except UnsupportedFormat as exc:
            self.meta.note = str(exc)
        except (ReplayError, TruncatedData) as exc:
            self.meta.note = f"存档元数据解析失败: {exc}"

    def _handle_command(self, payload: bytes) -> None:
        reader = _Reader(payload)
        tick = reader.int()
        name, body = _read_block(reader, "rc")
        if name != "c":
            raise ReplayError(f"命令块名异常: 期望 'c'，实际 {name!r}")
        inner = _Reader(body)
        command = _read_command(inner, tick, len(body))
        if inner.remaining:
            # 字段布局漂移：保留已解出的命令，但记一笔，避免静默错位
            note = f"tick {tick}: 命令载荷多出 {inner.remaining} 字节"
            if self.strict:
                raise ReplayError(note)
            self.errors.append(note)
        self.commands.append(command)
        if reader.remaining:
            note = f"tick {tick}: rc 块多出 {reader.remaining} 字节"
            if self.strict:
                raise ReplayError(note)
            self.errors.append(note)

    def _handle_chat(self, payload: bytes) -> None:
        reader = _Reader(payload)
        self.chats.append(
            ChatLine(
                tick=reader.int(),
                sender_id=reader.int(),
                sender_name=reader.nullable_utf(),
                message=reader.nullable_utf(),
            )
        )

    # -- 派生信息 -----------------------------------------------------
    @property
    def ok(self) -> bool:
        """结构完整：无异常、未截断、且读到了结尾元数据。"""
        return not self.errors and not self.partial and self.end_meta is not None

    @property
    def last_tick(self) -> int:
        if self.commands:
            return max(command.tick for command in self.commands)
        return 0

    @property
    def frames(self) -> Optional[int]:
        return self.end_meta.frames if self.end_meta else None

    @property
    def duration_seconds(self) -> float:
        if self.end_meta and self.end_meta.game_time_ms > 0:
            return self.end_meta.game_time_ms / 1000.0
        return self.last_tick / TICKS_PER_SECOND

    @property
    def resync_count(self) -> int:
        return sum(1 for item in self.checkpoints if item.kind == "resync")

    # -- tick 与游戏时间换算 ------------------------------------------
    @property
    def anchors(self) -> List[Tuple[int, int]]:
        """`(tick, gameTimeMillis)` 锚点：起点 + resync 快照 + 结尾元数据。"""
        anchors = [(0, 0)]
        for item in self.checkpoints:
            if item.kind == "resync":
                anchors.append(
                    (int(item.detail["resync_tick"]), int(item.detail["game_time_ms"]))
                )
        if self.end_meta is not None and self.end_meta.frames > 0:
            anchors.append((self.end_meta.frames, self.end_meta.game_time_ms))
        unique: Dict[int, int] = {}
        for tick, millis in sorted(anchors):
            unique[tick] = millis
        return sorted(unique.items())

    @property
    def speed_change_ticks(self) -> List[int]:
        return [command.tick for command in self.commands if command.game_speed_change]

    @property
    def ms_per_tick(self) -> Optional[float]:
        """整局平均"每 tick 推进多少游戏毫秒"；没有结尾元数据时返回 None。"""
        end = self.end_meta
        if end is None or end.frames <= 0:
            return None
        return end.game_time_ms / end.frames

    @property
    def time_basis(self) -> str:
        """说明 tick → 时间的可信度，供文本/概要展示。"""
        rate = self.ms_per_tick
        if rate is None:
            return f"无结尾元数据，按标称 {NOMINAL_MS_PER_TICK:g} ms/tick 估算"
        basis = (
            f"{rate:.3f} ms/tick（{self.end_meta.frames} tick = "
            f"{self.end_meta.game_time_ms} ms）"
        )
        if self.speed_change_ticks:
            return basis + f"，含 {len(self.speed_change_ticks)} 条改速度命令，按锚点分段估算"
        return basis + "，整局速率恒定，换算精确"

    def tick_to_ms(self, tick: int) -> int:
        """tick → 游戏时间（毫秒）。有锚点时线性插值，否则按标称值。"""
        anchors = self.anchors
        if not anchors:
            return int(round(tick * NOMINAL_MS_PER_TICK))
        if tick <= anchors[0][0]:
            return anchors[0][1]
        if tick >= anchors[-1][0]:
            # 未完成录制会超出最后一个锚点：按最后一段的速率外推
            prev_tick, prev_ms = anchors[-2] if len(anchors) > 1 else anchors[-1]
            last_tick, last_ms = anchors[-1]
            span = last_tick - prev_tick
            if span <= 0:
                return last_ms
            rate = (last_ms - prev_ms) / span
            return int(round(last_ms + (tick - last_tick) * rate))
        for index in range(1, len(anchors)):
            left_tick, left_ms = anchors[index - 1]
            right_tick, right_ms = anchors[index]
            if tick <= right_tick:
                span = right_tick - left_tick
                if span <= 0:
                    return right_ms
                return int(round(left_ms + (tick - left_tick) * (right_ms - left_ms) / span))
        return anchors[-1][1]

    def tick_to_seconds(self, tick: int) -> float:
        return self.tick_to_ms(tick) / 1000.0

    def clock(self, tick: int) -> str:
        """把 tick 渲染成 `MM:SS.s`（分钟不归零，和 CLI 记录器的口径一致）。"""
        tenths = int(round(self.tick_to_ms(tick) / 100.0))
        minutes, remainder = divmod(tenths, 600)
        seconds, tenth = divmod(remainder, 10)
        return f"{minutes}:{seconds:02d}.{tenth}"

    def status(self) -> str:
        if self.header is None:
            return "无法解析（头部损坏）"
        if self.partial or self.errors:
            return f"部分解析（{len(self.errors)} 处异常）"
        if self.end_meta is None:
            return "未完成录制（缺少 end/meta 块）"
        return "完整"

    # -- 渲染 ---------------------------------------------------------
    def describe_order(self, command: ReplayCommand) -> str:
        """把一条命令渲染成一段人类可读的动词短语。"""
        units = command.unit_ids
        unit_cmd = command.unit_command
        parts: List[str] = []
        if unit_cmd is not None:
            verb = unit_cmd.type or "none"
            if unit_cmd.build_type:
                verb += " " + unit_cmd.build_type
            if unit_cmd.target_unit_id != -1:
                verb += " -> " + self.meta.unit_label(unit_cmd.target_unit_id)
            elif unit_cmd.type in ("move", "attackMove", "patrol", "build", "guardAt", "unloadAt"):
                verb += f" @({unit_cmd.target_x:.0f},{unit_cmd.target_y:.0f})"
            elif unit_cmd.type == "attack" and unit_cmd.target_unit_id == -1:
                verb += " (no target)"
            if unit_cmd.is_queued:
                verb += " [queue]"
            if unit_cmd.is_repeating:
                verb += " [repeat]"
            if unit_cmd.is_force_move:
                verb += " [force]"
            if unit_cmd.build_queue_size not in (0, 1):
                verb += f" x{unit_cmd.build_queue_size}"
            if unit_cmd.action_id:
                verb += f" action={unit_cmd.action_id}"
            parts.append(verb)
        elif command.action_id not in ("", "-1"):
            parts.append(f"action {command.action_id}")
        if command.attack_mode:
            parts.append(f"attackMode={command.attack_mode}")
        if command.rally_point:
            parts.append(f"rally=({command.rally_point[0]:.0f},{command.rally_point[1]:.0f})")
        if command.target_point:
            parts.append(f"point=({command.target_point[0]:.0f},{command.target_point[1]:.0f})")
        if command.target_unit_id != -1:
            parts.append("target=" + self.meta.unit_label(command.target_unit_id))
        if command.is_system_action:
            label = command.system_action_name or "system"
            if command.game_speed_change:
                label += f" speed={command.game_speed_change:g}"
            if command.system_float:
                label += f" f={command.system_float:g}"
            parts.append(f"[{label}]")
        if command.clear_existing_orders:
            parts.append("[clear-orders]")
        if command.stop_current_action:
            parts.append("[stop-action]")
        if command.is_instant:
            parts.append("[instant]")
        if command.is_high_priority:
            parts.append("[prio]")
        if not parts:
            parts.append("noop")
        verb_text = " ".join(parts)
        unit_text = ",".join(self.meta.unit_label(unit) for unit in units)
        if len(units) > 6:
            unit_text = ",".join(self.meta.unit_label(unit) for unit in units[:6]) + f",+{len(units) - 6}"
        return f"{verb_text:<46} units[{len(units)}]={unit_text}"

    def render_text(
        self,
        team: Optional[int] = None,
        from_tick: Optional[int] = None,
        to_tick: Optional[int] = None,
        limit: Optional[int] = None,
        checksums: bool = False,
        header: bool = True,
        clock: bool = False,
    ) -> str:
        lines: List[str] = []
        if header:
            lines.append(self._text_header())

        # (tick, 类型序号, 行) 后统一按 tick 排序，保证命令/聊天/校验行时序正确
        entries: List[Tuple[int, int, str]] = []
        for command in self.commands:
            if not self._matches(command, team, from_tick, to_tick):
                continue
            clock_text = f"[{self.clock(command.tick)}] " if clock else ""
            entries.append(
                (
                    command.tick,
                    0,
                    f"t={command.tick:<6} {clock_text}{self._who(command.team_id):<20} "
                    f"{self.describe_order(command)}",
                )
            )
        for chat in self.chats:
            if from_tick is not None and chat.tick < from_tick:
                continue
            if to_tick is not None and chat.tick > to_tick:
                continue
            sender = chat.sender_name or ("system" if chat.sender_id == -1 else f"t{chat.sender_id}")
            clock_text = f"[{self.clock(chat.tick)}] " if clock else ""
            entries.append(
                (chat.tick, 1, f"t={chat.tick:<6} {clock_text}{'chat':<20} {sender}: {chat.message}")
            )
        if checksums:
            for checkpoint in self.checkpoints:
                if from_tick is not None and checkpoint.tick < from_tick:
                    continue
                if to_tick is not None and checkpoint.tick > to_tick:
                    continue
                entries.append(
                    (
                        checkpoint.tick,
                        2,
                        f"t={checkpoint.tick:<6} [{checkpoint.kind}] {self._checkpoint_text(checkpoint)}",
                    )
                )
        entries.sort(key=lambda entry: (entry[0], entry[1]))

        if limit is not None and len(entries) > limit:
            entries = entries[:limit] + [(entries[limit - 1][0], 3, f"... (达到 --limit {limit} 条，后续省略)")]
        lines.extend(entry[2] for entry in entries)
        if not entries:
            lines.append("(没有匹配的命令)")
        return "\n".join(lines)

    def _matches(
        self,
        command: ReplayCommand,
        team: Optional[int],
        from_tick: Optional[int],
        to_tick: Optional[int],
    ) -> bool:
        if team is not None and command.team_id != team and command.source_team_id != team:
            return False
        if from_tick is not None and command.tick < from_tick:
            return False
        if to_tick is not None and command.tick > to_tick:
            return False
        return True

    def _who(self, team_id: int) -> str:
        return self.meta.team_label(team_id)

    def _checkpoint_text(self, checkpoint: Checkpoint) -> str:
        detail = checkpoint.detail
        if checkpoint.kind == "cs":
            return f"checksum={detail['checksum']}"
        if checkpoint.kind == "es":
            return f"fields={detail['fields']} trace={detail['trace']}"
        return (
            f"resyncTick={detail['resync_tick']} time={detail['game_time_ms']}ms "
            f"stepRate={detail['step_rate']:g} save={detail['save_bytes']}B"
        )

    def _text_header(self) -> str:
        header = self.header
        lines = [
            f"# file   : {os.path.basename(self.path)}",
            f"# size   : {self.file_size} bytes",
            f"# build  : v{header.version_string if header else '?'} "
            f"(versionCode {header.version_code if header else '?'}, "
            f"protocol {header.protocol if header else '?'})",
        ]
        if self.meta.map_path:
            lines.append(f"# map    : {self.meta.map_path}")
        if self.meta.teams:
            teams = ", ".join(
                f"{tid}={info.name or '?'}" for tid, info in sorted(self.meta.teams.items())
            )
            lines.append(f"# teams  : {teams}")
        frames = self.frames if self.frames is not None else self.last_tick
        lines.append(
            f"# frames : {frames}  duration: {self.duration_seconds:.1f}s  "
            f"commands: {len(self.commands)}  chats: {len(self.chats)}  "
            f"resyncs: {self.resync_count}"
        )
        lines.append(f"# clock  : {self.time_basis}")
        lines.append(f"# status : {self.status()}")
        return "\n".join(lines)

    def order_rows(self) -> List[dict]:
        """结构化命令列表（JSON 友好）。"""
        rows: List[dict] = []
        for command in self.commands:
            unit_cmd = command.unit_command
            row = {
                "tick": command.tick,
                "game_time_ms": self.tick_to_ms(command.tick),
                "clock": self.clock(command.tick),
                "time_s": round(self.tick_to_seconds(command.tick), 3),
                "team": command.team_id,
                "team_name": self.meta.team_label(command.team_id),
                "source_team": command.source_team_id,
                "type": unit_cmd.type if unit_cmd else None,
                "build": unit_cmd.build_type if unit_cmd else None,
                "x": round(unit_cmd.target_x, 2) if unit_cmd else None,
                "y": round(unit_cmd.target_y, 2) if unit_cmd else None,
                "target_unit": unit_cmd.target_unit_id if unit_cmd else command.target_unit_id,
                "target_unit_type": self.meta.unit_shells.get(
                    unit_cmd.target_unit_id if unit_cmd else command.target_unit_id
                ),
                "action": (
                    unit_cmd.action_id
                    if unit_cmd and unit_cmd.action_id
                    else (command.action_id if command.action_id not in ("", "-1") else None)
                ),
                "attack_mode": command.attack_mode,
                "queued": unit_cmd.is_queued if unit_cmd else command.is_queued,
                "units": command.unit_ids,
                "unit_types": [self.meta.unit_shells.get(unit) for unit in command.unit_ids],
                "game_speed": command.game_speed_change or None,
                "system_action": command.system_action_name,
                # 空单位列表 + 非系统命令 = 引擎里什么都不执行（AI 群组指令常见）
                "noop": not command.unit_ids and not command.is_system_action,
            }
            rows.append(row)
        return rows

    def summary(self) -> str:
        header = self.header
        lines = [
            f"文件        : {self.path}",
            f"大小        : {self.file_size} bytes",
        ]
        if header is not None:
            lines.append(
                f"版本        : v{header.version_string} (versionCode {header.version_code}, "
                f"protocol {header.protocol}, demo={header.demo})"
            )
        lines.append(f"状态        : {self.status()}")
        if self.meta.map_path:
            lines.append(f"地图        : {self.meta.map_path}")
        if self.meta.setup_networked is not None:
            lines.append(f"联机对局    : {self.meta.setup_networked}")
        if self.meta.embedded_map_bytes:
            lines.append(f"内嵌地图    : {self.meta.embedded_map_bytes} bytes")
        if self.meta.teams:
            lines.append("队伍        :")
            for team_id, info in sorted(self.meta.teams.items()):
                flags = []
                if info.is_spectator:
                    flags.append("spectator")
                if info.is_observer:
                    flags.append("observer")
                if info.is_ai:
                    flags.append("ai")
                if info.is_victory:
                    flags.append("victory")
                if info.ai_hint:
                    flags.append(f"hint={info.ai_hint}")
                suffix = f" [{' '.join(flags)}]" if flags else ""
                lines.append(
                    f"  t{team_id:<2} {info.name or '?':<20} color={info.color_id:<3} "
                    f"credits={info.credits}{suffix}"
                )
        frames = self.frames if self.frames is not None else self.last_tick
        lines.append(
            f"帧/时长     : {frames} ticks / {self.duration_seconds:.1f}s "
            f"(标称 {TICKS_PER_SECOND:g} Hz)"
        )
        lines.append(f"时间口径    : {self.time_basis}")
        lines.append(
            f"命令/聊天   : {len(self.commands)} commands / {len(self.chats)} chat / "
            f"{self.resync_count} resync"
        )
        if self.meta.unit_shells:
            kinds: Dict[str, int] = {}
            for label in self.meta.unit_shells.values():
                kinds[label] = kinds.get(label, 0) + 1
            top = sorted(kinds.items(), key=lambda item: -item[1])[:6]
            lines.append(
                f"开局对象    : {len(self.meta.unit_shells)} 个（"
                + ", ".join(f"{name}×{count}" for name, count in top)
                + "）"
            )
        if self.commands:
            lines.append("块统计      : " + ", ".join(
                f"{name}={count}" for name, count in sorted(self.block_counts.items())
            ))
            by_team: Dict[str, int] = {}
            by_type: Dict[str, int] = {}
            empty_units = 0
            speed_changes = 0
            for command in self.commands:
                label = self.meta.team_label(command.team_id)
                by_team[label] = by_team.get(label, 0) + 1
                key = command.unit_command.type if command.unit_command else (
                    command.system_action_name or f"action:{command.action_id}"
                )
                by_type[key] = by_type.get(key, 0) + 1
                if not command.unit_ids:
                    empty_units += 1
                if command.game_speed_change:
                    speed_changes += 1
            lines.append("按队伍      : " + ", ".join(
                f"{name}={count}" for name, count in sorted(by_team.items(), key=lambda item: -item[1])
            ))
            lines.append("按指令      : " + ", ".join(
                f"{name}={count}" for name, count in sorted(by_type.items(), key=lambda item: -item[1])[:12]
            ))
            lines.append(
                f"空单位命令  : {empty_units} ({empty_units / len(self.commands):.1%})  "
                f"速度变更    : {speed_changes}"
            )
        if self.chats:
            preview = [chat for chat in self.chats[:3]]
            lines.append("聊天预览    : " + " | ".join(
                f"t={chat.tick} {chat.sender_name or 'system'}: {chat.message}" for chat in preview
            ))
        if self.end_meta is not None:
            lines.append(
                f"结尾元数据  : frames={self.end_meta.frames} "
                f"time={self.end_meta.game_time_ms}ms "
                f"commands={self.end_meta.command_count} "
                f"resyncs={self.end_meta.resync_count}"
            )
        if self.errors:
            lines.append("异常        :")
            for error in self.errors[:6]:
                lines.append(f"  - {error}")
            if len(self.errors) > 6:
                lines.append(f"  …其余 {len(self.errors) - 6} 条")
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# 文件名与落盘
# ---------------------------------------------------------------------------


_ILLEGAL_FILENAME = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def safe_stem(path: str) -> str:
    """把回放文件名变成安全的输出文件主名。"""
    stem = os.path.splitext(os.path.basename(path))[0]
    stem = _ILLEGAL_FILENAME.sub("_", stem)
    stem = re.sub(r"\s+", "_", stem).strip("._")
    return stem or "replay"


def write_outputs(
    replay: Replay,
    out_dir: str,
    modes: Sequence[str],
    limit: Optional[int] = None,
    stem: Optional[str] = None,
    clock: bool = False,
) -> List[str]:
    """按模式把结果写成文件，返回写出的路径列表。``stem`` 可覆盖输出主名。"""
    os.makedirs(out_dir, exist_ok=True)
    stem = stem or safe_stem(replay.path)
    written: List[str] = []
    for mode in modes:
        if mode == "summary":
            target = os.path.join(out_dir, f"{stem}.summary.txt")
            payload = replay.summary() + "\n"
        elif mode == "text":
            target = os.path.join(out_dir, f"{stem}.txt")
            payload = replay.render_text(limit=limit, clock=clock) + "\n"
        elif mode == "jsonl":
            target = os.path.join(out_dir, f"{stem}.jsonl")
            payload = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in replay.order_rows())
        else:
            raise ValueError(f"未知输出模式: {mode}")
        with open(target, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(payload)
        written.append(target)
    return written


# ---------------------------------------------------------------------------
# 命令行
# ---------------------------------------------------------------------------


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="replay.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="RWX .replay 反解析：输出概要与命令执行文本。",
        epilog=(
            "示例:\n"
            "  python evaluate/utils/replay.py replays/xxx.replay --summary\n"
            "  python evaluate/utils/replay.py replays/xxx.replay --text --limit 50\n"
            "  python evaluate/utils/replay.py replays/*.replay --summary --text --out evaluate/output\n"
        ),
    )
    parser.add_argument("paths", nargs="+", help="一个或多个 .replay 文件")
    parser.add_argument("--summary", action="store_true", help="输出概要")
    parser.add_argument("--text", action="store_true", help="输出命令文本")
    parser.add_argument("--jsonl", action="store_true", help="输出 JSONL（一行一条命令）")
    parser.add_argument("--out", metavar="DIR", help="写入目录；不给则打印到标准输出")
    parser.add_argument(
        "--name", metavar="STEM", help="配合 --out：覆盖输出文件主名（只允许单文件）"
    )
    parser.add_argument("--team", type=int, metavar="ID", help="只看该队伍（或下发给它的命令）")
    parser.add_argument("--from-tick", type=int, metavar="N", help="起始 tick（含）")
    parser.add_argument("--to-tick", type=int, metavar="N", help="结束 tick（含）")
    parser.add_argument("--limit", type=int, metavar="N", help="文本输出最多 N 行")
    parser.add_argument("--checksums", action="store_true", help="文本中夹带 es/cs/resync 行")
    parser.add_argument(
        "--clock", action="store_true", help="文本中额外显示 [分:秒.十分位] 游戏时间"
    )
    parser.add_argument("--strict", action="store_true", help="结构异常直接报错")
    parser.add_argument(
        "--index",
        action="store_true",
        help="配合 --out：把多个文件的概要汇总写成 <out>/replays.index.txt",
    )
    return parser


def _configure_stdout() -> None:
    """Windows 控制台默认 cp936，遇到某些字符会直接抛 UnicodeEncodeError。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass


def main(argv: Optional[Sequence[str]] = None) -> int:
    _configure_stdout()
    args = _build_parser().parse_args(argv)
    modes = [mode for mode, enabled in (
        ("summary", args.summary), ("text", args.text), ("jsonl", args.jsonl)
    ) if enabled]
    if not modes:
        modes = ["summary"]
    if args.out is None and len(args.paths) > 1 and len(modes) > 1:
        print("多个文件 + 多种输出请配合 --out 使用", file=sys.stderr)
        return 2

    exit_code = 0
    index_entries: List[str] = []
    for path in args.paths:
        if not os.path.isfile(path):
            print(f"跳过（不是文件）: {path}", file=sys.stderr)
            exit_code = 2
            continue
        try:
            replay = Replay.load(path, strict=args.strict)
        except (ReplayError, TruncatedData) as exc:
            print(f"解析失败 {path}: {exc}", file=sys.stderr)
            exit_code = 1
            continue

        if args.out is not None:
            stem = None
            if args.name:
                if len(args.paths) > 1:
                    print("--name 只能配合单个文件使用", file=sys.stderr)
                    return 2
                stem = safe_stem(args.name)
            written = write_outputs(
                replay, args.out, modes, limit=args.limit, stem=stem, clock=args.clock
            )
            print(f"{path}: {replay.status()}，写出 {len(written)} 个文件")
            for target in written:
                print(f"  -> {target}")
            if args.index and "summary" in modes:
                index_entries.append(replay.summary())
            continue

        for mode in modes:
            if mode == "summary":
                print(replay.summary())
            elif mode == "text":
                print(
                    replay.render_text(
                        team=args.team,
                        from_tick=args.from_tick,
                        to_tick=args.to_tick,
                        limit=args.limit,
                        checksums=args.checksums,
                        clock=args.clock,
                    )
                )
            else:
                rows = replay.order_rows()
                if args.team is not None:
                    rows = [
                        row for row in rows
                        if row["team"] == args.team or row["source_team"] == args.team
                    ]
                if args.from_tick is not None:
                    rows = [row for row in rows if row["tick"] >= args.from_tick]
                if args.to_tick is not None:
                    rows = [row for row in rows if row["tick"] <= args.to_tick]
                if args.limit is not None:
                    rows = rows[: args.limit]
                for row in rows:
                    print(json.dumps(row, ensure_ascii=False))
            if mode != modes[-1]:
                print()
    if index_entries and args.out is not None:
        target = os.path.join(args.out, "replays.index.txt")
        with open(target, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(f"{'=' * 78}\n{entry}" for entry in index_entries) + "\n")
        print(f"汇总索引: {target}（{len(index_entries)} 个文件）")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
