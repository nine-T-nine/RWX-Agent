#!/usr/bin/env python3
"""Subscribe to the local RWX event stream and append its protocol records to JSONL."""

import argparse
import json
import socket
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PORT = ROOT / 'tmp' / 'events-port'
DEFAULT_OUTPUT = ROOT / 'tmp' / 'game-events.jsonl'
PROTOCOL = 'rwx-events-v1'
MAX_LINE = 1024 * 1024


def last_cursor(path):
    """Find the last durable sequence from this log, including stream restarts."""
    session, seq = None, 0
    if not path.exists():
        return session, seq
    with path.open('r', encoding='utf-8') as source:
        for line in source:
            try:
                record = json.loads(line)
            except (ValueError, UnicodeError):
                continue
            if (isinstance(record, dict) and record.get('protocol') == PROTOCOL
                    and isinstance(record.get('session'), str)
                    and isinstance(record.get('seq'), int) and record['seq'] >= 0):
                if record['session'] != session:
                    session, seq = record['session'], 0
                seq = max(seq, record['seq'])
    return session, seq


def append(output, record):
    output.write(json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n')
    output.flush()


def diagnostic(output, kind, message):
    append(output, {'protocol': PROTOCOL, 'kind': kind, 'message': message})
    print(message, file=sys.stderr, flush=True)


def receive(sock, output, session, seq, include_info=False, allies=False):
    request = {'protocol': PROTOCOL, 'kind': 'subscribe', 'after': seq,
               'session': session, 'info': include_info, 'allies': allies}
    sock.sendall((json.dumps(request, separators=(',', ':')) + '\n').encode('utf-8'))
    with sock.makefile('rb') as source:
        while True:
            try:
                line = source.readline(MAX_LINE + 1)
            except OSError:
                return session, seq
            if not line:
                return session, seq
            if len(line) > MAX_LINE or not line.endswith(b'\n'):
                diagnostic(output, 'parse_error', 'Incomplete or oversized event message; reconnecting')
                return session, seq
            try:
                message = json.loads(line.decode('utf-8'))
                if not isinstance(message, dict) or message.get('protocol') != PROTOCOL:
                    raise ValueError('unexpected protocol or non-object message')
            except (ValueError, UnicodeError) as error:
                diagnostic(output, 'parse_error', 'Invalid event message: ' + str(error))
                continue
            kind = message.get('kind')
            current = message.get('session')
            if kind == 'hello':
                if not isinstance(current, str):
                    diagnostic(output, 'parse_error', 'Hello message missing session')
                    return session, seq
                if session is not None and current != session:
                    diagnostic(output, 'gap', 'Event server restarted; prior session cannot be resumed')
                    seq = 0
                session = current
                append(output, message)
                continue
            if kind in ('event', 'update', 'remove', 'cursor', 'overflow', 'reset') and 'seq' in message:
                number = message.get('seq')
                if not isinstance(number, int) or number <= 0 or current != session:
                    diagnostic(output, 'parse_error', 'Invalid event sequence or session')
                    continue
                if number <= seq:
                    continue
                if seq and number > seq + 1:
                    diagnostic(output, 'gap', 'Missing event sequence %d..%d' % (seq + 1, number - 1))
                append(output, message)
                seq = number
            elif kind in ('gap', 'overflow', 'error'):
                append(output, message)
                print('RWX event stream: ' + json.dumps(message, ensure_ascii=False), file=sys.stderr, flush=True)
            else:
                diagnostic(output, 'parse_error', 'Unknown event message kind: ' + str(kind))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, help='event port (overrides the discovery file)')
    parser.add_argument('--port-file', type=Path, default=DEFAULT_PORT)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--retry-seconds', type=float, default=1.0)
    parser.add_argument('--info', action='store_true', help='also subscribe to info events')
    parser.add_argument('--allies', action='store_true', help='use allied destroyed and losses events')
    args = parser.parse_args()
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    if args.retry_seconds <= 0:
        parser.error('--retry-seconds must be positive')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    session, seq = last_cursor(args.output)
    print('Appending RWX events to %s; Ctrl+C stops' % args.output, flush=True)
    try:
        with args.output.open('a', encoding='utf-8', newline='\n') as output:
            while True:
                try:
                    port = args.port if args.port is not None else int(args.port_file.read_text(encoding='ascii').strip())
                    if not 1 <= port <= 65535:
                        raise ValueError('port outside 1..65535')
                    with socket.create_connection(('127.0.0.1', port), timeout=5) as sock:
                        sock.settimeout(None)
                        print('Connected to 127.0.0.1:%d' % port, flush=True)
                        session, seq = receive(sock, output, session, seq, args.info, args.allies)
                    print('Disconnected; reconnecting', file=sys.stderr, flush=True)
                except (OSError, ValueError) as error:
                    print('Event connection unavailable: %s; retrying' % error, file=sys.stderr, flush=True)
                time.sleep(args.retry_seconds)
    except KeyboardInterrupt:
        print('\nStopped', flush=True)
    except OSError as error:
        print('Cannot write event log: %s' % error, file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
