#!/usr/bin/env python3
"""Subscribe to the game's local event socket and keep its newest records as JSONL.

The socket already applies the subscription filter server-side, but a subscriber still
receives transport records it does not care about: `hello`, `cursor` placeholders for
messages outside the subscription, `event`, `remove` and the `gap`/`overflow` markers.
This receiver applies a second filter on receipt (only `kind == "update"` survives) and
splits the survivors by event type into two rolling logs, so that routine re-discovery
updates cannot push combat history out of the window:

* `game-events-observe.jsonl`: `new` (discoveries) and `info` (own production/upgrades).
* `game-events-combat.jsonl`: `damaged`, `destroyed` and `losses`.

The default subscription is `info=false, allies=false`: the `new`/`info` side carries both
our own and allied records (the scope flag never hides observations), while the combat side
stays on our own faction unless `--allies` asks for allied kills and losses.
"""

import argparse
import json
import os
import socket
import sys
import time
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PORT = ROOT / 'tmp' / 'events-port'
DEFAULT_OBSERVE = ROOT / 'tmp' / 'game-events-observe.jsonl'
DEFAULT_COMBAT = ROOT / 'tmp' / 'game-events-combat.jsonl'
PROTOCOL = 'rwx-events-v1'
MAX_LINE = 1024 * 1024
MAX_RECORDS = 500
# Second filter applied on receipt: only aggregate updates are written to the JSONL files.
KEPT_KINDS = frozenset({'update'})
# Event types that belong to the observation log rather than the combat log.
OBSERVE_TYPES = frozenset({'new', 'info'})
COMPACT_RETRY_SECONDS = 5.0


class JsonlLog:
    """Append-only JSONL file that keeps only the newest `limit` records."""

    def __init__(self, path, limit=MAX_RECORDS):
        self.path = path
        self.limit = limit
        self.records = count_records(path)
        self.retry_at = 0.0
        self.handle = path.open('a', encoding='utf-8', newline='\n')
        if self.records > limit:
            self.compact()

    def write(self, text):
        self.handle.write(text)
        self.records += text.count('\n')

    def flush(self):
        self.handle.flush()
        if self.records > self.limit and time.monotonic() >= self.retry_at:
            self.compact()

    def compact(self):
        """Rewrite the file with its newest records, and keep writing whatever happens.

        A rename over the log fails while another process holds it open (Windows share
        violation). The handle used to stay closed after that failure, so every later write
        raised into the reconnect loop and the log silently stopped growing while the process
        looked healthy. The handle is now reopened in every outcome and the next flush retries
        after a short backoff.
        """
        self.handle.flush()
        newest = deque(maxlen=self.limit)
        with self.path.open('r', encoding='utf-8') as source:
            for line in source:
                newest.append(line)
        temporary = self.path.with_name('.%s.%d.tmp' % (self.path.name, os.getpid()))
        with temporary.open('w', encoding='utf-8', newline='\n') as target:
            target.writelines(newest)
        self.handle.close()
        try:
            for attempt in range(3):
                try:
                    os.replace(temporary, self.path)
                    break
                except OSError:
                    if attempt == 2:
                        raise
                    time.sleep(0.1)
        except OSError as error:
            self.retry_at = time.monotonic() + COMPACT_RETRY_SECONDS
            print('Cannot compact %s: %s; still appending, retrying later'
                  % (self.path, error), file=sys.stderr, flush=True)
            try:
                os.unlink(temporary)
            except OSError:
                pass
        finally:
            self.handle = self.path.open('a', encoding='utf-8', newline='\n')
        self.records = count_records(self.path)

    def close(self):
        self.handle.close()


def count_records(path):
    if not path.exists():
        return 0
    with path.open('r', encoding='utf-8', errors='replace') as source:
        return sum(1 for _ in source)


def cursor_of(path):
    """Last durable session and sequence of one log file."""
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


def last_cursor(*paths):
    """Newest durable sequence across the split logs, including stream restarts.

    The logs hold updates only, so the resume point is the newest stored update; the
    sequences after it are re-requested and dropped again by the on-receipt filter.
    """
    session, seq = None, 0
    for path in paths:
        found_session, found_seq = cursor_of(path)
        if found_session is not None and (session is None or found_seq > seq):
            session, seq = found_session, found_seq
    return session, seq


def append(output, record):
    output.write(json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n')
    output.flush()


def diagnostic(kind, message):
    """Report a transport problem on stderr only; the files hold updates and nothing else."""
    print('%s: %s' % (kind, message), file=sys.stderr, flush=True)


def destination(logs, message):
    """Pick the log for one message: observations first, everything else is combat."""
    event = message.get('event')
    event_type = event.get('type') if isinstance(event, dict) else None
    return logs[0] if event_type in OBSERVE_TYPES else logs[1]


def catch_up(logs, message, session, seq):
    """Advance the resume cursor over one sequence-bearing message.

    Returns the new sequence, or `None` when the message must not advance it (an older
    duplicate, a stale session, or a malformed sequence). The cursor follows every
    sequence, kept or filtered, so a reconnect neither replays nor invents a gap.
    """
    number = message.get('seq')
    if not isinstance(number, int) or number <= 0 or message.get('session') != session:
        diagnostic('parse_error', 'Invalid event sequence or session')
        return None
    if number <= seq:
        return seq
    if seq and number > seq + 1:
        diagnostic('gap', 'Missing event sequence %d..%d' % (seq + 1, number - 1))
    kind = message.get('kind')
    if kind in KEPT_KINDS:
        append(destination(logs, message), message)
    elif kind in ('reset', 'overflow'):
        # Not persisted, but the reader must know the stream it is following broke.
        diagnostic('dropped', 'Stream %s marker not stored: %s'
                   % (kind, json.dumps(message, ensure_ascii=False)))
    return number


def receive(sock, logs, session, seq, include_info=False, allies=False):
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
                diagnostic('parse_error', 'Incomplete or oversized event message; reconnecting')
                return session, seq
            try:
                message = json.loads(line.decode('utf-8'))
                if not isinstance(message, dict) or message.get('protocol') != PROTOCOL:
                    raise ValueError('unexpected protocol or non-object message')
            except (ValueError, UnicodeError) as error:
                diagnostic('parse_error', 'Invalid event message: ' + str(error))
                continue
            kind = message.get('kind')
            current = message.get('session')
            if kind == 'hello':
                if not isinstance(current, str):
                    diagnostic('parse_error', 'Hello message missing session')
                    return session, seq
                if session is not None and current != session:
                    diagnostic('gap', 'Event server restarted; prior session cannot be resumed')
                    seq = 0
                session = current
                continue
            if kind in ('event', 'update', 'remove', 'cursor', 'overflow', 'reset') and 'seq' in message:
                advanced = catch_up(logs, message, session, seq)
                if advanced is not None:
                    seq = advanced
            elif kind in ('gap', 'overflow', 'error'):
                diagnostic(kind, json.dumps(message, ensure_ascii=False))
            else:
                diagnostic('parse_error', 'Unknown event message kind: ' + str(kind))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, help='event port (overrides the discovery file)')
    parser.add_argument('--port-file', type=Path, default=DEFAULT_PORT)
    parser.add_argument('--observe-output', type=Path, default=DEFAULT_OBSERVE,
                        help='new/info log (default: %(default)s)')
    parser.add_argument('--combat-output', type=Path, default=DEFAULT_COMBAT,
                        help='damaged/destroyed/losses log (default: %(default)s)')
    parser.add_argument('--retry-seconds', type=float, default=1.0)
    parser.add_argument('--info', action='store_true', help='also subscribe to info events')
    parser.add_argument('--allies', action='store_true', help='use allied destroyed and losses events')
    args = parser.parse_args()
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    if args.retry_seconds <= 0:
        parser.error('--retry-seconds must be positive')
    outputs = (args.observe_output, args.combat_output)
    if outputs[0].resolve() == outputs[1].resolve():
        parser.error('--observe-output and --combat-output must be different files')
    for path in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
    session, seq = last_cursor(*outputs)
    print('Appending RWX update events to %s and %s (newest %d records each); Ctrl+C stops'
          % (outputs[0], outputs[1], MAX_RECORDS), flush=True)
    try:
        logs = (JsonlLog(outputs[0]), JsonlLog(outputs[1]))
    except OSError as error:
        print('Cannot write event log: %s' % error, file=sys.stderr, flush=True)
        return 1
    try:
        while True:
            try:
                port = args.port if args.port is not None else int(args.port_file.read_text(encoding='ascii').strip())
                if not 1 <= port <= 65535:
                    raise ValueError('port outside 1..65535')
                with socket.create_connection(('127.0.0.1', port), timeout=5) as sock:
                    sock.settimeout(None)
                    print('Connected to 127.0.0.1:%d' % port, flush=True)
                    session, seq = receive(sock, logs, session, seq, args.info, args.allies)
                print('Disconnected; reconnecting', file=sys.stderr, flush=True)
            except (OSError, ValueError) as error:
                print('Event connection unavailable: %s; retrying' % error, file=sys.stderr, flush=True)
            time.sleep(args.retry_seconds)
    except KeyboardInterrupt:
        print('\nStopped', flush=True)
    except OSError as error:
        print('Cannot write event log: %s' % error, file=sys.stderr, flush=True)
        return 1
    finally:
        for log in logs:
            log.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
