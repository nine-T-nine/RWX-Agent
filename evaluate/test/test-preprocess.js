'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {planWindow, preprocess} = require('../preprocess');

const at = (minutes, seconds, millis) => Number(minutes) * 60000 + Number(seconds) * 1000 + Number(millis || 0);

test('the scoreable window stops at surrender, exit, match end or the log truncation, whichever is first', () => {
  const commands = {lastMs: 200000, surrenderMs: 150000, exitMs: 190000, stop: null};
  const end = {timeMs: 200000, reason: 'match_result'};
  assert.deepEqual([planWindow({commands, end}).ms, planWindow({commands, end}).kind], [150000, 'surrender']);
  // An exit without surrender ends the Agent's own play just the same.
  const leaving = {...commands, surrenderMs: null};
  assert.equal(planWindow({commands: leaving, end}).kind, 'agent_exit');
  // A command log cut short by the interference marker wins over a later telemetry end.
  const truncated = {lastMs: 120000, surrenderMs: null, exitMs: null,
    stop: {line: 9, content: '----以下是意外或者人为干涉情况----'}};
  const cut = planWindow({commands: truncated, end});
  assert.deepEqual([cut.ms, cut.kind], [120000, 'truncated']);
  // A dead process never scores the shutdown tail: cut at its last command instead.
  const aborted = planWindow({commands: {lastMs: 150000, surrenderMs: null, exitMs: null, stop: null},
    end: {timeMs: 170000, reason: 'process_exit'}});
  assert.deepEqual([aborted.ms, aborted.kind], [150000, 'aborted']);
  // No telemetry end at all still resolves to something usable.
  const orphan = planWindow({commands: {lastMs: 90000, surrenderMs: null, exitMs: null, stop: null},
    end: null, lastFrameMs: 95000});
  assert.deepEqual([orphan.ms, orphan.kind], [90000, 'aborted']);
});

function workspace({surrenderMs = 150000, endMs = 200000, reason = 'match_result', hpLoss = false} = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-preprocess-'));
  const match = path.join(root, 'match-a');
  const live = path.join(match, 'events-live', 'match-001-team0');
  fs.mkdirSync(live, {recursive: true});
  fs.mkdirSync(path.join(match, 'replay'), {recursive: true});
  const lines = [JSON.stringify({kind: 'meta', schema: 1, team: 0, X: 160, timeMs: 0})];
  const economy = [];
  for (let second = 0; second <= endMs / 1000; second++) {
    const timeMs = second * 1000;
    economy.push(`${4000 + second}(${36})`);
    lines.push(JSON.stringify({kind: 'economy', timeMs, sampleMs: timeMs, funds: 4000 + second, income: 36}));
    lines.push(JSON.stringify({kind: 'frame', timeMs, tick: second * 62, seq: second * 3}));
    if (second >= 10 && second < 60) lines.push(JSON.stringify({kind: 'idle', timeMs, unitId: 7, life: 0,
      type: 'landFactory', roles: ['factory'], fromMs: 10000, toMs: 60000, durationMs: 50000,
      closeReason: 'PRODUCE', closeQueue: []}));
    if (second === 30) lines.push(JSON.stringify({kind: 'damage', timeMs, tick: 1860, seq: 999,
      hpLoss: hpLoss ? 50 : null, victim: {id: 7, life: 0, side: 'self', maxHp: 1000}, attacker: {id: 9, life: 0, side: 'enemy'}}));
    if (second === 40) lines.push(JSON.stringify({kind: 'native', phase: 'before', timeMs, seq: 1200,
      source: 'agent', units: [7], operation: 'produce', commandId: 'B-1'}));
  }
  lines.push(JSON.stringify({kind: 'end', timeMs: endMs, tick: endMs / 16, seq: endMs, reason}));
  fs.writeFileSync(path.join(live, 'telemetry.jsonl'), lines.join('\n') + '\n');
  const chunks = [];
  for (let i = 0; i < economy.length; i += 60) chunks.push(economy.slice(i, i + 60).join(','));
  fs.writeFileSync(path.join(live, 'economy.log'), chunks.join('\n') + '\n');
  const commands = ['[--:--:---]<name=Player;player=0;faction=A(0);role=player;match=none><rwx-ping\\t>'];
  for (const second of [1, 20, 40]) commands.push(`[0${Math.floor(second / 60)}:${String(second % 60).padStart(2, '0')}]`
    + '<name=Player;player=0;faction=A(0);role=player;match=1><rwx-game-state --summary>');
  if (surrenderMs != null) commands.push(`[0${Math.floor(surrenderMs / 60000)}:${String((surrenderMs % 60000) / 1000).padStart(2, '0')}:000]`
    + '<name=Player;player=0;faction=A(0);role=player;match=1><rwx-game-surrender\\t>');
  fs.writeFileSync(path.join(root, 'commands-match-a.txt'), commands.join('\n') + '\n');
  fs.writeFileSync(path.join(match, 'replay', 'r.jsonl'),
    [{game_time_ms: 1000}, {game_time_ms: 150000}, {game_time_ms: 190000}].map(r => JSON.stringify(r)).join('\n') + '\n');
  return {root, match};
}

test('preprocessing cuts a surrendered match and the trimmed inputs still line up', async () => {
  const {match} = workspace();
  const result = await preprocess(match);
  assert.equal(result.ending.kind, 'surrender');
  assert.equal(result.window.endMs, 150000);
  assert.equal(result.scoreEligible, true);
  assert.equal(result.kept.economy, 151);
  // The synthetic end row records why the window closed.
  const prepared = fs.readFileSync(path.join(match, 'prepared', 'telemetry.jsonl'), 'utf8').trim().split('\n');
  const end = JSON.parse(prepared.at(-1));
  assert.equal(end.kind, 'end');
  assert.equal(end.reason, 'preprocess_cut');
  assert.equal(end.cutKind, 'surrender');
  assert.ok(prepared.every(line => {
    const row = JSON.parse(line);
    return row.kind === 'end' || Number(row.timeMs) <= 150000;
  }), 'no row after the cut may survive');
  // The surrender command itself is still in the window; later polling is not.
  const commands = fs.readFileSync(path.join(match, 'prepared', 'commands-match-a.txt'), 'utf8');
  assert.match(commands, /rwx-game-surrender/);
  // Evidence coverage is recorded, not guessed.
  assert.equal(result.evidence.idleRows, 50);
  assert.deepEqual(result.evidence.missing, ['hp_loss']);
});

test('a finished match keeps its own end row and only drops trailing polling', async () => {
  const {match} = workspace({surrenderMs: null, endMs: 200000, reason: 'match_result', hpLoss: true});
  const result = await preprocess(match);
  assert.equal(result.ending.kind, 'normal');
  assert.equal(result.window.endMs, 200000);
  assert.equal(result.window.trimmedMs, 0);
  assert.deepEqual(result.evidence.missing, []);
  const prepared = fs.readFileSync(path.join(match, 'prepared', 'telemetry.jsonl'), 'utf8').trim().split('\n');
  assert.equal(JSON.parse(prepared.at(-1)).cutKind, 'normal');
});

test('a match that dies without an end row is marked aborted and keeps its last command', async () => {
  const {match} = workspace({surrenderMs: null, endMs: 200000, reason: 'process_exit'});
  const result = await preprocess(match);
  assert.equal(result.ending.kind, 'aborted');
  assert.ok(result.window.endMs <= 200000);
  assert.match(result.ending.reason, /process_exit/);
});
