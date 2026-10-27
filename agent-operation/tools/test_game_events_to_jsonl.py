"""Tests for the event JSONL transport, using only the Python standard library.

The receiver applies a second filter on receipt: only `kind == "update"` reaches the
file, while transport diagnostics go to stderr.
"""

import contextlib
import importlib.util
import io
import json
import socket
import tempfile
import threading
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name('rwx-game-events-to-jsonl.py')
spec = importlib.util.spec_from_file_location('rwx_events_receiver', MODULE)
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


def message(kind, **fields):
    row = {'protocol': receiver.PROTOCOL, 'kind': kind, 'session': 's'}
    row.update(fields)
    return row


def payload(*rows):
    return b''.join((json.dumps(row) + '\n').encode() for row in rows)


class ReceiverTests(unittest.TestCase):
    def exchange(self, data, session=None, seq=0):
        """Return (cursor, stored records, stderr lines, subscribe request)."""
        client, server = socket.socketpair()
        request = []

        def send():
            with server:
                request.append(server.recv(4096))
                server.sendall(data)

        thread = threading.Thread(target=send)
        thread.start()
        output = io.StringIO()
        errors = io.StringIO()
        with client, contextlib.redirect_stderr(errors):
            cursor = receiver.receive(client, output, session, seq)
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive())
        return (cursor, [json.loads(line) for line in output.getvalue().splitlines()],
                errors.getvalue().splitlines(), json.loads(request[0]))

    def test_only_updates_reach_the_file_and_the_cursor_follows_every_sequence(self):
        self.assertEqual(frozenset({'update'}), receiver.KEPT_KINDS)
        data = payload(
            message('hello'),
            message('event', seq=5, event={'type': 'losses'}),   # already consumed
            message('update', seq=6, eventId='a', revision=2),
            message('cursor', seq=7),                            # server-side placeholder
            message('remove', seq=8, eventId='b', reason='expired'),
            message('update', seq=9, eventId='a', revision=3),
        )
        cursor, records, errors, request = self.exchange(data, 's', 5)
        self.assertEqual(cursor, ('s', 9))
        self.assertEqual(request['after'], 5)
        self.assertEqual([row['kind'] for row in records], ['update', 'update'])
        self.assertEqual([row['seq'] for row in records], [6, 9])
        self.assertEqual([row['revision'] for row in records], [2, 3])
        self.assertEqual([], errors)

    def test_transport_diagnostics_go_to_stderr_only(self):
        data = payload(
            message('hello'),
            message('update', seq=1, eventId='a', revision=1),
            message('gap'),                                      # no sequence: control only
            message('overflow', seq=2),
            message('reset', seq=3),
            message('update', seq=4, eventId='a', revision=2),
        )
        cursor, records, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 4))
        self.assertEqual([row['seq'] for row in records], [1, 4])
        self.assertEqual(3, len(errors))
        self.assertTrue(errors[0].startswith('gap: '))
        self.assertTrue(errors[1].startswith('dropped: '), errors[1])
        self.assertIn('overflow', errors[1])
        self.assertIn('reset', errors[2])

    def test_missing_sequence_is_reported_without_being_stored(self):
        data = payload(
            message('hello'),
            message('update', seq=10, eventId='a', revision=1),
            message('update', seq=13, eventId='a', revision=2),
        )
        cursor, records, errors, _ = self.exchange(data, 's', 9)
        self.assertEqual(cursor, ('s', 13))
        self.assertEqual([row['seq'] for row in records], [10, 13])
        self.assertEqual(['gap: Missing event sequence 11..12'], errors)

    def test_invalid_and_partial_messages_are_never_events(self):
        data = b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\nnot-json\n{"kind":"event"'
        cursor, records, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([], records)
        self.assertEqual(['parse_error', 'parse_error'], [line.split(':')[0] for line in errors])

    def test_unknown_kind_and_foreign_protocol_never_advance_the_cursor(self):
        data = (b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\n'
                b'{"protocol":"rwx-events-v1","kind":"bogus","session":"s","seq":4}\n'
                b'{"protocol":"other","kind":"event","session":"s","seq":5}\n')
        cursor, records, errors, _ = self.exchange(data)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([], records)
        self.assertEqual(['parse_error', 'parse_error'], [line.split(':')[0] for line in errors])

    def test_log_keeps_only_the_newest_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            log = receiver.JsonlLog(path, 3)
            try:
                for number in range(1, 6):
                    receiver.append(log, message('update', seq=number))
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
                    source.write(json.dumps(message('update', seq=number)) + '\n')
            receiver.JsonlLog(path).close()
            rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            self.assertEqual(500, len(rows))
            self.assertEqual([101, 600], [rows[0]['seq'], rows[-1]['seq']])

    def test_cursor_recovers_from_append_only_log(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            with path.open('a', encoding='utf-8') as output:
                receiver.append(output, message('update', seq=8))
                receiver.append(output, message('update', seq=9))
            self.assertEqual(receiver.last_cursor(path), ('s', 9))
            with path.open('a', encoding='utf-8') as output:
                receiver.append(output, message('update', seq=1, session='next'))
            self.assertEqual(receiver.last_cursor(path), ('next', 1))

    def test_paths_do_not_depend_on_working_directory(self):
        self.assertEqual(receiver.DEFAULT_PORT, MODULE.resolve().parent.parent / 'tmp' / 'events-port')
        self.assertEqual(receiver.DEFAULT_OUTPUT, MODULE.resolve().parent.parent / 'tmp' / 'game-events.jsonl')


if __name__ == '__main__':
    unittest.main()
