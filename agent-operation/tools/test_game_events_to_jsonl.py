"""Tests for the event JSONL transport, using only the Python standard library."""

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


class ReceiverTests(unittest.TestCase):
    def exchange(self, data, session=None, seq=0):
        client, server = socket.socketpair()
        request = []
        def send():
            with server:
                request.append(server.recv(4096))
                server.sendall(data)
        thread = threading.Thread(target=send)
        thread.start()
        output = io.StringIO()
        with client:
            cursor = receiver.receive(client, output, session, seq)
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive())
        return cursor, [json.loads(line) for line in output.getvalue().splitlines()], json.loads(request[0])

    def test_replay_deduplication_and_gap(self):
        messages = [
            {'protocol': receiver.PROTOCOL, 'kind': 'hello', 'session': 's'},
            {'protocol': receiver.PROTOCOL, 'kind': 'event', 'session': 's', 'seq': 5, 'event': {'type': 'losses'}},
            {'protocol': receiver.PROTOCOL, 'kind': 'update', 'session': 's', 'seq': 6, 'eventId': 'a', 'revision': 2},
            {'protocol': receiver.PROTOCOL, 'kind': 'gap', 'session': 's', 'from': 7, 'to': 8},
        ]
        payload = b''.join((json.dumps(m) + '\n').encode() for m in messages)
        cursor, records, request = self.exchange(payload, 's', 5)
        self.assertEqual(cursor, ('s', 6))
        self.assertEqual(request['after'], 5)
        self.assertEqual([row['kind'] for row in records], ['hello', 'update', 'gap'])
        self.assertEqual(records[1]['revision'], 2)

    def test_invalid_and_partial_messages_are_never_events(self):
        payload = b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\nnot-json\n{"kind":"event"'
        cursor, records, _ = self.exchange(payload)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([row['kind'] for row in records], ['hello', 'parse_error', 'parse_error'])

    def test_unknown_kind_and_foreign_protocol_never_advance_the_cursor(self):
        payload = (b'{"protocol":"rwx-events-v1","kind":"hello","session":"s"}\n'
                   b'{"protocol":"rwx-events-v1","kind":"bogus","session":"s","seq":4}\n'
                   b'{"protocol":"other","kind":"event","session":"s","seq":5}\n')
        cursor, records, _ = self.exchange(payload)
        self.assertEqual(cursor, ('s', 0))
        self.assertEqual([row['kind'] for row in records], ['hello', 'parse_error', 'parse_error'])

    def test_cursor_recovers_from_append_only_log(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.jsonl'
            with path.open('a', encoding='utf-8') as output:
                receiver.append(output, {'protocol': receiver.PROTOCOL, 'kind': 'event', 'session': 's', 'seq': 8})
                receiver.append(output, {'protocol': receiver.PROTOCOL, 'kind': 'update', 'session': 's', 'seq': 9})
            self.assertEqual(receiver.last_cursor(path), ('s', 9))
            with path.open('a', encoding='utf-8') as output:
                receiver.append(output, {'protocol': receiver.PROTOCOL, 'kind': 'event', 'session': 'next', 'seq': 1})
            self.assertEqual(receiver.last_cursor(path), ('next', 1))

    def test_paths_do_not_depend_on_working_directory(self):
        self.assertEqual(receiver.DEFAULT_PORT, MODULE.resolve().parent.parent / 'tmp' / 'events-port')
        self.assertEqual(receiver.DEFAULT_OUTPUT, MODULE.resolve().parent.parent / 'tmp' / 'game-events.jsonl')


if __name__ == '__main__':
    unittest.main()
