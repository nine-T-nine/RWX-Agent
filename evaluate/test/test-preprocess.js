'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {planWindow, preprocess} = require('../preprocess');

const at = (minutes, seconds, millis) => Number(minutes) * 60000 + Number(seconds) * 1000 + Number(millis || 0);

test('the scoreable window stops at surrender, exit, match end or the log truncation, whichever is first', () => {
  const commands = {lastMs: 200000, surrenderMs: 150000, exitMs: 190000, stop: null};
  const end = {timeMs: 200000, reason: 'match_result'};
  assert.deepEqual([planWindow({commands, end}).ms, planWindow({commands, end}).kind], [150000, 'surrender']);
  // Losing our command center beats a later match end: the ally's remaining play is not ours.
  const lost = planWindow({commands: {lastMs: 190000, surrenderMs: null, exitMs: null, stop: null},
    end: {timeMs: 200000, reason: 'match_result'}, defeatMs: 130000});
  assert.deepEqual([lost.ms, lost.kind, lost.reason], [130000, 'defeat', 'team_eliminated']);
  // ...but it never extends past a surrender that came first.
  assert.equal(planWindow({commands, end, defeatMs: 180000}).kind, 'surrender');
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

function workspace({surrenderMs = 150000, endMs = 200000, reason = 'match_result', hpLoss = false,
  defeatMs = null} = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-preprocess-'));
  const match = path.join(root, 'match-a');
  const live = path.join(match, 'events-live', 'match-001-team0');
  fs.mkdirSync(live, {recursive: true});
  fs.mkdirSync(path.join(match, 'replay'), {recursive: true});
  const lines = [JSON.stringify({kind: 'meta', schema: 1, team: 0, X: 160, timeMs: 0})];
  if (defeatMs != null) {
    lines.push(JSON.stringify({kind: 'unit', timeMs: 0, tick: 0, seq: 2, unit: {id: 99,
      type: 'commandCenter', side: 'self', alive: true, building: true, complete: true}}));
    lines.push(JSON.stringify({kind: 'death', timeMs: defeatMs, tick: defeatMs / 16, seq: 900,
      unit: {id: 99, type: 'commandCenter', side: 'self', alive: false, building: true}}));
  }
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
  // `replay.py` writes the exported JSONL with a space after each colon; the fixture keeps that
  // shape so the replay trimmer is exercised against the real format.
  fs.writeFileSync(path.join(match, 'replay', 'r.jsonl'),
    [1000, 150000, 190000].map(ms => `{"tick": ${ms / 16}, "game_time_ms": ${ms}, "time_s": ${ms / 1000}}`)
      .join('\n') + '\n');
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
  // The parsed replay is trimmed too: the 190s row would be ally/AI play after our window.
  const replay = fs.readFileSync(path.join(match, 'prepared', 'replay.jsonl'), 'utf8').trim().split('\n');
  assert.deepEqual(replay.map(line => JSON.parse(line).game_time_ms), [1000, 150000]);
  // Evidence coverage is recorded, not guessed.
  assert.equal(result.evidence.idleRows, 50);
  assert.deepEqual(result.evidence.missing, ['hp_loss']);
});

test('losing our command center ends the window even when the match runs on for the ally', async () => {
  const {match} = workspace({surrenderMs: null, endMs: 200000, reason: 'match_result', defeatMs: 120000});
  const result = await preprocess(match);
  assert.deepEqual([result.ending.kind, result.ending.reason, result.ending.defeatMs],
    ['defeat', 'team_eliminated', 120000]);
  assert.equal(result.window.endMs, 120000);
  assert.equal(result.window.trimmedMs, 80000);
  const telemetry = fs.readFileSync(path.join(match, 'prepared', 'telemetry.jsonl'), 'utf8').trim().split('\n');
  assert.ok(telemetry.every(line => Number(JSON.parse(line).timeMs) <= 120000), 'our defeat caps every row');
  assert.equal(JSON.parse(telemetry.at(-1)).cutKind, 'defeat');
  const replay = fs.readFileSync(path.join(match, 'prepared', 'replay.jsonl'), 'utf8').trim().split('\n');
  assert.deepEqual(replay.map(line => JSON.parse(line).game_time_ms), [1000]);
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

test('a consolidated match finds its command log inside the match directory', async () => {
  const {match} = workspace();
  // `tools/match_materials.js` moves the log to `<match>/commands-<match>.txt`; preprocessing has to
  // keep working from there, and the prepared inputs keep the same names so stage reports land back
  // in the same match directory.
  const name = path.basename(match);
  fs.renameSync(path.join(path.dirname(match), `commands-${name}.txt`),
    path.join(match, `commands-${name}.txt`));
  const result = await preprocess(match);
  assert.equal(result.ending.kind, 'surrender');
  assert.equal(result.inputs.commands, path.join(match, `commands-${name}.txt`));
  assert.ok(result.kept.commands > 0);
  assert.ok(fs.existsSync(path.join(match, 'prepared', `commands-${name}.txt`)));
  // One material, one copy: the preprocess record only lives where scoring reads it.
  assert.ok(fs.existsSync(path.join(match, 'prepared', 'preprocess.json')));
  assert.equal(fs.existsSync(path.join(match, 'preprocess.json')), false);
});
