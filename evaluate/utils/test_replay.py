"""replay.py 的自测（不依赖 pytest）：

    python evaluate/utils/test_replay.py
    python -m unittest discover -s evaluate/utils -p "test_*.py"

包含两部分：

1. 合成回放往返测试——按引擎写侧布局造一个最小的 .replay，验证解析出的每个字段；
   顺便覆盖中文地图名/队名（Java modified UTF-8）、gzip 存档块、系统命令、聊天、截断容错。
2. 真实回放回归——解析仓库 ``replays/`` 下的样本，断言全部能出结果、命令字段精确消费。
"""

import gzip
import json
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import replay as rp  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPLAY_DIR = os.path.join(REPO_ROOT, "replays")


# ---------------------------------------------------------------------------
# 合成回放写侧（只实现本工具会读到的部分）
# ---------------------------------------------------------------------------


class _Writer:
    """对应 Java GameOutputStream：块 = UTF 名字 + int 长度 + 载荷。"""

    def __init__(self):
        self.buf = bytearray()

    # -- 基础类型 -----------------------------------------------------
    def byte(self, value):
        self.buf += struct.pack(">b", value)

    def boolean(self, value):
        self.buf += b"\x01" if value else b"\x00"

    def int(self, value):
        self.buf += struct.pack(">i", value)

    def long(self, value):
        self.buf += struct.pack(">q", value)

    def short(self, value):
        self.buf += struct.pack(">h", value)

    def float(self, value):
        self.buf += struct.pack(">f", value)

    def utf(self, text):
        raw = rp.encode_modified_utf8(text)
        self.buf += struct.pack(">H", len(raw)) + raw

    def nullable_utf(self, text):
        self.boolean(text is not None)
        if text is not None:
            self.utf(text)

    def nullable_int(self, value):
        self.boolean(value is not None)
        if value is not None:
            self.int(value)

    # -- 块 -----------------------------------------------------------
    def block(self, name, payload):
        self.utf(name)
        self.int(len(payload))
        self.buf += payload

    def bytes(self):
        return bytes(self.buf)


def _team_state(writer, team_id, name, credits=4000, color=0):
    """PlayerTeam.writeBasicTeamState（protocol 96 / streamVersion 999999 全字段）。"""
    writer.byte(team_id)
    writer.int(credits)
    writer.int(color)
    writer.nullable_utf(name)
    writer.boolean(False)          # isTeamObserver
    writer.int(1)                  # teamNetworkId
    writer.long(0)                 # teamLastPingTime
    writer.boolean(False)          # isTeamSpectator
    writer.int(0)                  # teamPingTime
    writer.int(team_id)            # teamSortIndex
    writer.byte(0)
    writer.boolean(True)           # isTeamConnectionActive
    writer.boolean(True)           # isTeamNetworkActive
    writer.boolean(False)          # isTeamVictory
    writer.boolean(False)          # teamSurrenderTriggered
    writer.int(0)                  # surrenderVoteTimeMillis
    writer.nullable_utf(None)      # teamAIHint
    writer.int(0)                  # hostTeamFlag
    writer.nullable_int(None)      # aiDifficultyOverride
    writer.nullable_int(None)      # startingUnitsOverride
    writer.nullable_int(None)      # aiLevelOverride
    writer.nullable_int(None)      # playerColorOverride
    writer.int(color)              # assignedTeamColorIndex


def _save_block_payload(map_path, team_name, shell_units):
    inner = _Writer()
    inner.block("customUnitsBlock", b"")
    setup = _Writer()
    setup.boolean(True)            # networkGameActive
    setup.boolean(False)           # singleplayerServer
    setup.boolean(True)            # 房间设置随包（内容本工具不解析）
    inner.block("gameSetup", setup.bytes())
    inner.utf(map_path)
    inner.boolean(False)           # 无内嵌地图
    inner.int(0)                   # gameTimeMillis
    inner.float(1114.5)
    inner.float(2010.0)
    inner.float(1.0)
    inner.int(1)                   # nextFormationId
    inner.int(0)                   # 保留字段
    inner.short(rp.MAGIC_SHORT)
    inner.boolean(False)           # 光标选择是否存在
    inner.boolean(False)           # isGameStarted
    inner.boolean(True)            # fogEnabled
    inner.boolean(True)
    inner.boolean(True)
    inner.boolean(False)           # 无 mission 状态
    inner.short(rp.MAGIC_SHORT)
    inner.int(0)                   # playerTeamId
    inner.int(10)                  # TEAM_NEUTRAL
    for slot in range(10):
        is_last = slot == 0
        inner.boolean(False)       # isAIController
        inner.boolean(False)       # isAIPlayer
        inner.boolean(is_last)     # 该槽位是否存在
        if is_last:
            _team_state(inner, 0, team_name, color=1)
    inner.int(len(shell_units))
    for unit_id, type_name in shell_units:
        inner.byte(1)              # kind=内置单位
        inner.int(rp.UNIT_TYPE_NAMES.index(type_name))
        inner.long(unit_id)

    payload = _Writer()
    payload.utf(rp.SAVE_MAGIC)
    payload.int(176)
    payload.int(rp.SUPPORTED_PROTOCOL)
    payload.boolean(False)
    payload.block("saveCompression", gzip.compress(inner.bytes(), mtime=0))
    return payload.bytes()


def _unit_command(writer, cmd_type, x, y, build_ordinal=-1, target_unit=-1,
                  is_queued=False, action=None, build_queue=1):
    writer.int(rp.UNIT_COMMAND_TYPES.index(cmd_type))
    writer.int(build_ordinal)      # -1 无 / -2 自定义 / 其它为枚举序号
    writer.float(x)
    writer.float(y)
    writer.long(target_unit)
    writer.byte(build_queue)
    writer.float(-1.0)             # attackMoveRange
    writer.float(-1.0)             # maxWayPointSurvivingTime
    writer.boolean(False)          # isRepeating
    writer.boolean(is_queued)
    writer.boolean(False)          # isForceMove
    writer.nullable_utf(action)


def _command_body(team_id, unit_command=None, units=(), attack_mode=-1,
                  action_id="-1", system=None, target_point=None,
                  source_team=None, clear_orders=False, high_priority=False):
    body = _Writer()
    body.byte(team_id)
    body.boolean(unit_command is not None)
    if unit_command is not None:
        unit_command(body)
    body.boolean(False)            # isQueued
    body.boolean(False)            # stopCurrentAction
    body.int(-1)                   # 遗留 actionId
    body.int(attack_mode)
    body.boolean(False)            # 无 rallyPoint
    body.boolean(clear_orders)
    body.int(len(units))
    for unit in units:
        body.long(unit)
    body.boolean(source_team is not None)
    if source_team is not None:
        body.byte(source_team)
    body.boolean(target_point is not None)
    if target_point is not None:
        body.float(target_point[0])
        body.float(target_point[1])
    body.long(-1)                  # targetUnit
    body.utf(action_id)
    body.boolean(False)            # isInstantCommand
    body.short(0)                  # allowedTeamMask
    body.boolean(system is not None)
    if system is not None:
        speed, system_float, system_type = system
        body.byte(0)
        body.float(speed)
        body.float(system_float)
        body.int(system_type)
    body.int(0)                    # 无 commandTargets
    body.boolean(high_priority)
    return body.bytes()


def build_sample_replay(path):
    """合成一个包含 2 条命令 + 聊天 + 校验的完整回放。"""
    out = _Writer()
    out.utf(rp.REPLAY_MAGIC)
    out.int(176)
    out.int(rp.SUPPORTED_PROTOCOL)
    out.utf("1.15")
    out.boolean(False)
    out.block(
        "gamesave",
        _save_block_payload("maps/skirmish/[p2]测试 地图 (2p).tmx", "测试玩家",
                            [(5, "builder"), (4, "commandCenter")]),
    )
    out.block("wait", _Writer().bytes() or struct.pack(">i", 0))

    checksum = _Writer()
    checksum.byte(0)
    checksum.int(3)
    for value in (11, 22, 33):
        checksum.long(value)
    es = _Writer()
    es.int(0)
    es.int(len(checksum.bytes()))
    es.buf += checksum.bytes()
    out.block("es", es.bytes())

    # 命令 1：t130 建造（自定义单位 extractorT1），带 attackMode 与目标点
    body1 = _Writer()
    body1.byte(0)
    body1.boolean(True)
    body1.int(rp.UNIT_COMMAND_TYPES.index("build"))
    body1.int(-2)
    body1.utf("extractorT1")
    body1.float(1130.0)
    body1.float(390.0)
    body1.long(-1)
    body1.byte(1)
    body1.float(-1.0)
    body1.float(-1.0)
    body1.boolean(False)
    body1.boolean(True)            # isQueued
    body1.boolean(False)
    body1.nullable_utf(None)
    body1.boolean(False)           # isQueued
    body1.boolean(False)           # stopCurrentAction
    body1.int(-1)
    body1.int(3)                   # attackMode=holdFire
    body1.boolean(False)           # rallyPoint
    body1.boolean(False)           # clearExistingOrders
    body1.int(1)
    body1.long(5)
    body1.boolean(False)           # sourceTeam
    body1.boolean(True)            # targetPoint
    body1.float(1130.0)
    body1.float(390.0)
    body1.long(-1)
    body1.utf("-1")
    body1.boolean(False)
    body1.short(0)
    body1.boolean(False)
    body1.int(0)
    body1.boolean(False)

    rc1 = _Writer()
    rc1.int(130)
    rc1.block("c", body1.bytes())
    out.block("rc", rc1.bytes())

    # 命令 2：t60 全体改速度（系统命令，TEAM_ALL）
    body2 = _command_body(-1, system=(2.0, 0.0, 0))
    rc2 = _Writer()
    rc2.int(60)
    rc2.block("c", body2)
    out.block("rc", rc2.bytes())

    chat = _Writer()
    chat.int(0)
    chat.int(-1)
    chat.nullable_utf(None)
    chat.nullable_utf("Still waiting on: Remote-B")
    out.block("chat", chat.bytes())

    cs = _Writer()
    cs.int(492)
    cs.long(25653805)
    out.block("cs", cs.bytes())

    out.block("wait", struct.pack(">i", 600))
    out.block("end", b"")

    meta = _Writer()
    meta.byte(0)
    meta.int(600)
    meta.int(9600)
    meta.int(2)
    meta.int(0)
    meta.utf("{frames:600,time:9600,commandCount:2,resyncCount:0}")
    out.block("endReplayMetaData", meta.bytes())

    with open(path, "wb") as handle:
        handle.write(out.bytes())
    return path


# ---------------------------------------------------------------------------
# 1. 合成回放往返
# ---------------------------------------------------------------------------


class SyntheticReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = build_sample_replay(os.path.join(cls.tmp.name, "sample.replay"))
        cls.replay = rp.Replay.load(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_header(self):
        header = self.replay.header
        self.assertEqual(header.version_code, 176)
        self.assertEqual(header.protocol, 96)
        self.assertEqual(header.version_string, "1.15")
        self.assertFalse(header.demo)

    def test_meta_map_and_team(self):
        self.assertEqual(self.replay.meta.map_path, "maps/skirmish/[p2]测试 地图 (2p).tmx")
        self.assertEqual(self.replay.meta.teams[0].name, "测试玩家")
        self.assertEqual(self.replay.meta.teams[0].credits, 4000)
        self.assertTrue(self.replay.meta.setup_networked)

    def test_meta_unit_shells(self):
        self.assertEqual(
            self.replay.meta.unit_shells, {5: "builder", 4: "commandCenter"}
        )
        self.assertEqual(self.replay.meta.unit_label(5), "#5(builder)")
        self.assertEqual(self.replay.meta.unit_label(99), "#99")
        self.assertEqual(self.replay.meta.unit_label(-1), "-")

    def test_build_command_fields(self):
        command = self.replay.commands[0]
        self.assertEqual(command.tick, 130)
        self.assertEqual(command.team_id, 0)
        self.assertEqual(command.unit_ids, [5])
        self.assertEqual(command.attack_mode, "holdFire")
        self.assertEqual(command.target_point, (1130.0, 390.0))
        unit_cmd = command.unit_command
        self.assertIsNotNone(unit_cmd)
        self.assertEqual(unit_cmd.type, "build")
        self.assertEqual(unit_cmd.build_type, "custom:extractorT1")
        self.assertAlmostEqual(unit_cmd.target_x, 1130.0)
        self.assertAlmostEqual(unit_cmd.target_y, 390.0)
        self.assertTrue(unit_cmd.is_queued)
        self.assertEqual(unit_cmd.build_queue_size, 1)

    def test_system_command_fields(self):
        command = self.replay.commands[1]
        self.assertEqual(command.tick, 60)
        self.assertEqual(command.team_id, -1)
        self.assertEqual(self.replay.meta.team_label(command.team_id), "TEAM_ALL")
        self.assertTrue(command.is_system_action)
        self.assertAlmostEqual(command.game_speed_change, 2.0)
        self.assertEqual(command.system_action_type, 0)

    def test_no_layout_drift_and_status(self):
        self.assertEqual(self.replay.errors, [])
        self.assertTrue(self.replay.ok)
        self.assertEqual(self.replay.status(), "完整")
        self.assertEqual(len(self.replay.commands), 2)

    def test_end_meta_and_checkpoints(self):
        self.assertEqual(self.replay.end_meta.frames, 600)
        self.assertEqual(self.replay.end_meta.command_count, 2)
        self.assertAlmostEqual(self.replay.duration_seconds, 9.6)
        kinds = [checkpoint.kind for checkpoint in self.replay.checkpoints]
        self.assertEqual(kinds, ["es", "cs"])
        self.assertEqual(self.replay.checkpoints[0].detail["fields"], 3)

    def test_chat(self):
        self.assertEqual(len(self.replay.chats), 1)
        self.assertEqual(self.replay.chats[0].sender_id, -1)
        self.assertEqual(self.replay.chats[0].message, "Still waiting on: Remote-B")

    def test_render_text(self):
        text = self.replay.render_text()
        self.assertIn("# map    : maps/skirmish/[p2]测试 地图 (2p).tmx", text)
        self.assertIn("测试玩家(t0)", text)
        self.assertIn("build custom:extractorT1 @(1130,390)", text)
        self.assertIn("units[1]=#5(builder)", text)
        self.assertIn("[system:0 speed=2]", text)
        self.assertIn("Still waiting on: Remote-B", text)

    def test_render_text_filters(self):
        team_one = self.replay.render_text(team=0)
        self.assertIn("t=130", team_one)
        self.assertNotIn("t=60", team_one)
        window = self.replay.render_text(to_tick=100)
        self.assertIn("t=60", window)
        self.assertNotIn("t=130", window)
        limited = self.replay.render_text(limit=1)
        self.assertIn("--limit 1", limited)

    def test_order_rows_json(self):
        rows = self.replay.order_rows()
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["type"], "build")
        self.assertEqual(rows[0]["build"], "custom:extractorT1")
        self.assertEqual(rows[0]["units"], [5])
        self.assertEqual(rows[0]["unit_types"], ["builder"])
        self.assertEqual(rows[0]["team_name"], "测试玩家(t0)")
        self.assertEqual(rows[1]["system_action"], "system:0")
        json.loads(json.dumps(rows, ensure_ascii=False))

    def test_summary(self):
        summary = self.replay.summary()
        self.assertIn("状态        : 完整", summary)
        self.assertIn("测试玩家", summary)
        self.assertIn("按指令", summary)

    def test_write_outputs(self):
        written = rp.write_outputs(
            self.replay, os.path.join(self.tmp.name, "out"), ["summary", "text", "jsonl"]
        )
        self.assertEqual(len(written), 3)
        for target in written:
            self.assertTrue(os.path.isfile(target))
            with open(target, encoding="utf-8") as handle:
                self.assertTrue(handle.read().strip())

    def test_safe_stem(self):
        self.assertEqual(rp.safe_stem("replays/a b/c:d.replay"), "c_d")
        self.assertEqual(rp.safe_stem("[local] Lake (2p) [v1.15].replay"),
                         "[local]_Lake_(2p)_[v1.15]")
        self.assertEqual(rp.safe_stem(".replay"), "replay")

    def test_iter_blocks(self):
        names = [name for name, _ in rp.Replay.iter_blocks(self.path)]
        self.assertEqual(
            names,
            ["gamesave", "wait", "es", "rc", "rc", "chat", "cs", "wait", "end",
             "endReplayMetaData"],
        )


class ToleranceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.full = build_sample_replay(os.path.join(self.tmp.name, "full.replay"))
        with open(self.full, "rb") as handle:
            self.data = handle.read()

    def tearDown(self):
        self.tmp.cleanup()

    def _write_cut(self, size):
        path = os.path.join(self.tmp.name, "cut.replay")
        with open(path, "wb") as handle:
            handle.write(self.data[:size])
        return path

    def test_truncated_tail_is_partial_not_crash(self):
        replay = rp.Replay.load(self._write_cut(len(self.data) - 40))
        self.assertTrue(replay.partial)
        self.assertTrue(replay.errors)
        self.assertEqual(replay.status().startswith("部分解析"), True)
        self.assertTrue(replay.commands)  # 截断前的命令仍然可用

    def test_truncated_mid_command_keeps_earlier_commands(self):
        cut = self.data.index(b"\x00\x04chat") + 6
        replay = rp.Replay.load(self._write_cut(cut))
        self.assertTrue(replay.partial)
        self.assertLessEqual(len(replay.commands), 2)

    def test_not_a_replay(self):
        path = os.path.join(self.tmp.name, "nope.replay")
        with open(path, "wb") as handle:
            handle.write(b"not a replay at all")
        replay = rp.Replay.load(path)
        self.assertIsNone(replay.header)
        self.assertIn("头部解析失败", replay.errors[0])
        with self.assertRaises(rp.ReplayError):
            rp.Replay.load(path, strict=True)

    def test_strict_mode_raises_on_truncation(self):
        with self.assertRaises(rp.ReplayError):
            rp.Replay.load(self._write_cut(len(self.data) - 40), strict=True)


# ---------------------------------------------------------------------------
# 2. 真实回放回归
# ---------------------------------------------------------------------------


@unittest.skipUnless(os.path.isdir(REPLAY_DIR), "仓库内没有 replays/ 样本")
class RealReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = [
            os.path.join(REPLAY_DIR, name)
            for name in sorted(os.listdir(REPLAY_DIR))
            if name.lower().endswith(".replay")
        ]
        cls.parsed = [(path, rp.Replay.load(path)) for path in cls.paths]

    def test_all_files_parse(self):
        self.assertGreaterEqual(len(self.parsed), 1)
        for path, replay in self.parsed:
            self.assertIsNotNone(replay.header, path)
            self.assertEqual(replay.header.protocol, 96, path)

    def test_commands_have_no_layout_drift(self):
        drift = []
        total = 0
        for path, replay in self.parsed:
            total += len(replay.commands)
            for error in replay.errors:
                if "多出" in error:
                    drift.append((path, error))
        self.assertGreater(total, 10_000, "样本命令总数异常")
        self.assertEqual(drift, [], f"字段布局漂移: {drift[:3]}")

    def test_meta_and_frames_consistency(self):
        for path, replay in self.parsed:
            if replay.end_meta is None:
                continue
            self.assertEqual(
                replay.end_meta.command_count,
                len(replay.commands),
                f"{path}: meta 命令数与实际解析数不一致",
            )
            self.assertGreaterEqual(replay.end_meta.frames, replay.last_tick, path)

    def test_at_least_one_has_map_and_teams(self):
        with_map = [replay for _, replay in self.parsed if replay.meta.map_path]
        with_teams = [replay for _, replay in self.parsed if replay.meta.teams]
        self.assertGreaterEqual(len(with_map), len(self.parsed) - 2)
        self.assertGreaterEqual(len(with_teams), len(self.parsed) - 2)

    def test_render_text_is_json_safe(self):
        # 样本目录可能正在录制（刚创建的回放还没有命令），只对"有命令"的文件断言正文
        with_commands = [replay for _, replay in self.parsed if replay.commands]
        self.assertTrue(with_commands, "样本里没有任何可解析命令")
        for replay in with_commands[:3]:
            text = replay.render_text(limit=20)
            json.dumps(replay.order_rows()[:5], ensure_ascii=False)
            self.assertIn("t=", text)
        for _, replay in self.parsed:
            self.assertIn("# status", replay.render_text(limit=1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
