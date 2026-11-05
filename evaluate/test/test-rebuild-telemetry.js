'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { rebuild } = require('../tools/rebuild_telemetry.js');

function workspace() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-rebuild-'));
  const metadata = '<name=Player;player=4;faction=A(0);role=player;match=1>';
  fs.writeFileSync(path.join(root, 'commands.txt'), [
    '[--:--:---]<name=Player;player=4;faction=A(0);role=player;match=none><rwx-ping\\t>',
    `[00:00:500]${metadata}<rwx-game-order-move --units 10 --x 100 --y 100>`,
    `[00:01:000]${metadata}<rwx-game-order-move --units 11 --x 100 --y 100>`,
    `[00:01:200]${metadata}<rwx-game-unit-list --owner self>`,
    ''
  ].join('\n'), 'utf8');
  fs.writeFileSync(path.join(root, 'replay.jsonl'), JSON.stringify({tick: 31, time_s: 0.5, team: 4,
    type: 'move', units: [10], unit_types: ['c_tank'], x: 100, y: 100, noop: false}) + '\n', 'utf8');
  const rows = [
    {kind: 'meta', schema: 1, team: 4, X: 160, timeMs: 0, seq: 1},
    {kind: 'economy', timeMs: 0, sampleMs: 0, seq: 2, funds: 4000, income: 36},
    {kind: 'unit', timeMs: 0, tick: 0, seq: 3, unit: {id: 10, side: 'self', alive: true, x: 90, y: 100}},
    {kind: 'unit', timeMs: 0, tick: 0, seq: 4, unit: {id: 11, side: 'self', alive: true, x: 90, y: 100}},
    {kind: 'native', timeMs: 500, tick: 31, seq: 5, team: 4, phase: 'after', replayOrdinal: 1,
      units: [10], operation: 'move', source: 'replay_unknown', results: []},
    {kind: 'death', timeMs: 800, tick: 50, seq: 6, unit: {id: 11, side: 'self', alive: false, x: 100, y: 100}},
    {kind: 'frames', timeMs: 816, tick: 51, seq: 7, toTimeMs: 832, toTick: 52, toSeq: 8, stepMs: 16},
    {kind: 'end', timeMs: 2000, tick: 125, seq: 9, reason: 'replay_end'}
  ];
  fs.writeFileSync(path.join(root, 'telemetry.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n', 'utf8');
  return root;
}

test('the receipt log supplies command/result rows and native attribution', () => {
  const root = workspace();
  const out = path.join(root, 'merged.jsonl');
  const result = rebuild({telemetry: path.join(root, 'telemetry.jsonl'), commands: path.join(root, 'commands.txt'),
    replay: path.join(root, 'replay.jsonl'), out});
  const rows = fs.readFileSync(out, 'utf8').trim().split(/\r?\n/).map(line => JSON.parse(line));
  assert.equal(rows[0].kind, 'meta');
  assert.equal(rows.at(-1).kind, 'end');
  for (let i = 1; i < rows.length; i++) assert.ok(rows[i].timeMs >= rows[i - 1].timeMs, 'rows must be ordered by the game clock');
  assert.deepEqual(rows.map(row => row.seq), rows.map((_, i) => i + 1), 'seq must be a dense increasing counter');

  const commands = rows.filter(row => row.kind === 'command');
  const results = rows.filter(row => row.kind === 'result');
  assert.equal(commands.length, 3, 'the lobby command is not part of the match');
  assert.equal(results.length, 3);

  assert.deepEqual([commands[0].id, commands[0].units, commands[0].refs[0].status], ['B-1', [10], 'valid']);
  assert.deepEqual([results[0].accepted, results[0].code], [true, 'ACCEPTED']);
  // Unit 11 died at 0.8s, before the 1.0s request; the engine would have skipped it.
  assert.deepEqual([commands[1].refs[0].status, commands[1].refs[0].deathTimeMs], ['dead', 800]);
  assert.deepEqual([results[1].accepted, results[1].code], [false, 'STALE_OBJECT']);
  assert.deepEqual([results[2].accepted, results[2].code], [true, 'ACCEPTED'], 'queries have no replay command but are not rejections');

  const native = rows.filter(row => row.kind === 'native');
  assert.deepEqual([native.length, native[0].source, native[0].commandId], [1, 'agent', 'B-1']);

  const frames = rows.filter(row => row.kind === 'frame');
  assert.deepEqual(frames.map(row => row.timeMs), [816, 832], 'the trimmed run is expanded for insertion');
  assert.ok(rows[0].rebuilt && rows[0].rebuilt.tool === 'evaluate/tools/rebuild_telemetry.js');
  fs.rmSync(root, {recursive: true, force: true});
});

test('a request with no replay trace becomes an inferred rejection instead of a silent acceptance', () => {
  const root = workspace();
  // Replace the dead unit with a live one so the unmatched request has no provable stale reference.
  const live = fs.readFileSync(path.join(root, 'telemetry.jsonl'), 'utf8').split('\n');
  const rows = live.filter(Boolean).map(line => JSON.parse(line)).map(row => row.kind === 'death' ? null : row).filter(Boolean);
  fs.writeFileSync(path.join(root, 'telemetry.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n', 'utf8');
  const out = path.join(root, 'merged.jsonl');
  rebuild({telemetry: path.join(root, 'telemetry.jsonl'), commands: path.join(root, 'commands.txt'),
    replay: path.join(root, 'replay.jsonl'), out});
  const results = fs.readFileSync(out, 'utf8').trim().split(/\r?\n/).map(line => JSON.parse(line)).filter(row => row.kind === 'result');
  assert.deepEqual([results[1].accepted, results[1].code, results[1].inferred], [false, 'OTHER', true]);
  fs.rmSync(root, {recursive: true, force: true});
});

test('receipt lines newer than the replay end are dropped instead of breaking the game clock', () => {
  const root = workspace();
  const metadata = '<name=Player;player=4;faction=A(0);role=player;match=1>';
  fs.appendFileSync(path.join(root, 'commands.txt'),
    `[00:05:000]${metadata}<rwx-game-unit-list --owner self>\n`, 'utf8');
  const out = path.join(root, 'merged.jsonl');
  const result = rebuild({telemetry: path.join(root, 'telemetry.jsonl'), commands: path.join(root, 'commands.txt'),
    replay: path.join(root, 'replay.jsonl'), out});
  const rows = fs.readFileSync(out, 'utf8').trim().split(/\r?\n/).map(line => JSON.parse(line));
  assert.equal(rows.at(-1).kind, 'end');
  for (let i = 1; i < rows.length; i++) assert.ok(rows[i].timeMs >= rows[i - 1].timeMs, 'the 5s request must not follow the 2s end row');
  assert.ok(!rows.some(row => row.kind === 'command' && row.timeMs === 5000), 'post-end requests are not part of the match window');
  assert.equal(rows[0].rebuilt.droppedAfterEnd, 1);
  assert.equal(result.commands, 4, 'the dropped request is still counted in the receipt log');
  fs.rmSync(root, {recursive: true, force: true});
});
