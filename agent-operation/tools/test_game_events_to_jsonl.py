"""Tests for the event JSONL transport, using only the Python standard library.

The receiver applies a second filter on receipt: only `kind == "update"` reaches a file,
routed by event type into an observation log (`new`/`info`) and a combat log, while
transport diagnostics go to stderr.
"""

import contextlib
import importlib.util
import io
import json
import os
import socket
import tempfile
import threading
import unittest
import unittest.mock
from pathlib import Path

MODULE = Path(__file__).with_name('rwx-game-events-to-jsonl.py')
spec = importlib.util.spec_from_file_location('rwx_events_receiver', MODULE)
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


def message(kind, **fields):
    row = {'protocol': receiver.PROTOCOL, 'kind': kind, 'session': 's'}
    row.update(fields)
    return row


def update(seq, event_type='new', **fields):
    row = message('update', seq=seq, eventId=1, revision=seq, tick=seq,
                  gameTimeMillis=seq, side='self', event={'type': event_type, 'description': 'x'})
    row.update(fields)
    return row


def payload(*rows):
    return b''.join((json.dumps(row) + '\n').encode() for row in rows)


class ReceiverTests(unittest.TestCase):
    def exchange(self, data, session=None, seq=0):
        """Return (cursor, observation records, combat records, stderr lines, request)."""
        client, server = socket.socketpair()
        request = []

        def send():
            with server:
                request.append(server.recv(4096))
                server.sendall(data)

        thread = threading.Thread(target=send)
        thread.start()
        observe, combat = io.StringIO(), io.StringIO()
        errors = io.StringIO()
        with client, contextlib.redirect_stderr(errors):
            cursor = receiver.receive(client, (observe, combat), session, seq)
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive())
        return (cursor,
                [json.loads(line) for line in observe.getvalue().splitlines()],
                [json.loads(line) for line in combat.getvalue().splitlines()],
                errors.getvalue().splitlines(), json.loads(request[0]))

    def test_updates_are_split_by_event_type_and_the_cursor_follows_every_sequence(self):
        self.assertEqual(frozenset({'update'}), receiver.KEPT_KINDS)
        data = payload(
            message('hello'),
            message('event', seq=5, event={'type': 'losses'}),          # already consumed
            update(6, 'new'),
            update(7, 'info'),
            update(8, 'damaged'),
            update(9, 'destroyed'),
            update(10, 'losses'),
            message('cursor', seq=11),                                  # server-side placeholder
            message('remove', seq=12, eventId=1, reason='expired'),
            update(13, 'new', revision=9),
        )
        cursor, observe, combat, errors, request = self.exchange(data, 's', 5)
        self.assertEqual(cursor, ('s', 13))
        self.assertEqual(request['after'], 5)
        self.assertEqual([6, 7, 13], [row['seq'] for row in observe])
        self.assertEqual(['new', 'info', 'new'], [row['event']['type'] for row in observe])
        self.assertEqual([8, 9, 10], [row['seq'] for row in combat])
        self.assertEqual([], errors)

    def test_subscription_defaults_are_own_faction_without_info(self):
        _, _, _, _, request = self.exchange(payload(update(1)))
        self.assertFalse(request['info'])
        self.assertFalse(request['allies'])

    def test_transport_diagnostics_go_to_stderr_only(self):
        data = payload(
            message('hello'),
            update(1),
            message('gap'),                                             # no sequence: control only
            message('overflow', seq=2),
            message('reset', seq=3),
            update(4),
        )
        cursor, observe, combat, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 4))
        self.assertEqual([1, 4], [row['seq'] for row in observe])
        self.assertEqual([], combat)
        self.assertEqual(3, len(errors))
        self.assertTrue(errors[0].startswith('gap: '))
        self.assertTrue(errors[1].startswith('dropped: '), errors[1])
        self.assertIn('overflow', errors[1])
        self.assertIn('reset', errors[2])

    def test_missing_sequence_is_reported_without_being_stored(self):
        data = payload(
            message('hello'),
            update(10),
            update(13),
        )
        cursor, observe, _, errors, _ = self.exchange(data, 's', 9)
        self.assertEqual(cursor, ('s', 13))
        self.assertEqual([10, 13], [row['seq'] for row in observe])
        self.assertEqual(['gap: Missing event sequence 11..12'], errors)

    def test_invalid_and_partial_messages_are_never_events(self):
        data = b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\nnot-json\n{"kind":"event"'
        cursor, observe, combat, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([], observe)
        self.assertEqual([], combat)
        self.assertEqual(['parse_error', 'parse_error'], [line.split(':')[0] for line in errors])

    def test_unknown_kind_and_foreign_protocol_never_advance_the_cursor(self):
        data = (b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\n'
                b'{"protocol":"rwx-events-v1","kind":"bogus","session":"s","seq":4}\n'
                b'{"protocol":"other","kind":"event","session":"s","seq":5}\n')
        cursor, observe, _, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([], observe)
        self.assertEqual(['parse_error', 'parse_error'], [line.split(':')[0] for line in errors])

    def test_log_keeps_only_the_newest_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            log = receiver.JsonlLog(path, 3)
            try:
                for number in range(1, 6):
                    receiver.append(log, update(number))
            finally:
                log.close()
            rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            self.assertEqual([3, 4, 5], [row['seq'] for row in rows])
            self.assertEqual(('s', 5), receiver.last_cursor(path))

    def test_default_limit_is_500_and_oversized_logs_are_trimmed_on_start(self):
        self.assertEqual(500, receiver.MAX_RECORDS)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            with path.open('w', encoding='utf-8', newline='\n') as source:
                for number in range(1, 601):
                    source.write(json.dumps(update(number)) + '\n')
            receiver.JsonlLog(path).close()
            rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            self.assertEqual(500, len(rows))
            self.assertEqual([101, 600], [rows[0]['seq'], rows[-1]['seq']])

    def test_compact_failure_keeps_writing_and_retries_later(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            log = receiver.JsonlLog(path, 2)
            try:
                receiver.append(log, update(1))
                receiver.append(log, update(2))

                def held_open(source, target):
                    raise PermissionError('another process holds the log open')

                errors = io.StringIO()
                with contextlib.redirect_stderr(errors):
                    with unittest.mock.patch.object(receiver.os, 'replace', side_effect=held_open):
                        receiver.append(log, update(3))     # compaction fails here
                        receiver.append(log, update(4))     # must still be writable
                rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
                self.assertEqual([1, 2, 3, 4], [row['seq'] for row in rows])
                self.assertIn('Cannot compact', errors.getvalue())
                temporary = path.with_name('.events.jsonl.%d.tmp' % os.getpid())
                self.assertFalse(temporary.exists(), 'failed compaction must not leave a temp file')

                log.retry_at = 0.0
                receiver.append(log, update(5))
                rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
                self.assertEqual([4, 5], [row['seq'] for row in rows])
                self.assertEqual(2, log.records)
            finally:
                log.close()

    def test_cursor_is_the_newest_sequence_across_both_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            observe = Path(directory) / 'observe.jsonl'
            combat = Path(directory) / 'combat.jsonl'
            with observe.open('a', encoding='utf-8') as output:
                receiver.append(output, update(9))
            with combat.open('a', encoding='utf-8') as output:
                receiver.append(output, update(4, 'losses'))
            self.assertEqual(receiver.last_cursor(observe, combat), ('s', 9))
            self.assertEqual(receiver.last_cursor(observe), ('s', 9))
            self.assertEqual(receiver.last_cursor(Path(directory) / 'missing'), (None, 0))

    def test_paths_do_not_depend_on_working_directory(self):
        base = MODULE.resolve().parent.parent / 'tmp'
        self.assertEqual(receiver.DEFAULT_PORT, base / 'events-port')
        self.assertEqual(receiver.DEFAULT_OBSERVE, base / 'game-events-observe.jsonl')
        self.assertEqual(receiver.DEFAULT_COMBAT, base / 'game-events-combat.jsonl')


if __name__ == '__main__':
    unittest.main()
