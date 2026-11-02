'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {attributeLow, findTelemetry, isAgent, keepRules} = require('../low-attribution.js');

const repo = path.resolve(__dirname, '../..');
const temp = fs.mkdtempSync(path.join(repo, 'agent-tmp/low-attribution-test-'));
test.after(() => fs.rmSync(temp, {recursive: true, force: true}));

function writeTelemetry(matchDir, rows) {
  const dir = path.join(matchDir, 'events-live', 'match-001-team0');
  fs.mkdirSync(dir, {recursive: true});
  fs.writeFileSync(path.join(dir, 'telemetry.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n');
}

const native = (timeMs, units, source, operation = 'move') =>
  ({kind: 'native', phase: 'before', team: 0, timeMs, units, source, operation});

test('native rows are joined to replay rows by unit set and one tick', async () => {
  const matchDir = path.join(temp, 'join');
  writeTelemetry(matchDir, [
    native(10016, [7], 'agent'),
    native(10032, [8], 'keep', 'action'),
    native(20016, [9], 'engine'),
  ]);
  const low = [
    {line: 1, time_s: 10.0, units: [7], type: 'move'},
    {line: 2, time_s: 10.016, units: [8], type: null, action: 'u_tank'},
    {line: 3, time_s: 20.0, units: [9], type: 'attackMove'},
    {line: 4, time_s: 30.0, units: [11], type: 'move'},
  ];
  const result = await attributeLow({low, team: 0, matchDir, commands: []});
  assert.equal(result.telemetry, true);
  assert.equal(result.sourceOf(low[0]), 'agent');
  assert.equal(result.sourceOf(low[1]), 'keep');
  assert.equal(result.sourceOf(low[2]), 'engine');
  assert.equal(result.sourceOf(low[3]), 'unknown', 'a row with no native submission stays unknown');
  assert.deepEqual(result.counts, {agent: 1, keep: 1, engine: 1, unknown: 1});
  assert.equal(isAgent('agent'), true);
  assert.equal(isAgent('pending'), true);
  assert.equal(isAgent('keep'), false);
});

test('a telemetry written during playback carries no submitter and falls back to inference', async () => {
  const matchDir = path.join(temp, 'playback');
  writeTelemetry(matchDir, [native(10016, [7], 'replay_unknown')]);
  const low = [{line: 1, time_s: 10.0, units: [7], type: null, action: 'u_tank'}];
  const commands = [{name: 'rwx-game-keep-set', time: 5, args: {unit: '7', action: 'u_tank'}}];
  const result = await attributeLow({low, team: 0, matchDir, commands});
  assert.equal(result.telemetry, false);
  assert.equal(result.sourceOf(low[0]), 'keep', 'keep-set is exact: one rule, one factory+action');
});

test('keep inference stops at keep-clear and ignores other actions', () => {
  const commands = [
    {name: 'rwx-game-keep-set', time: 10, args: {unit: '7', action: 'u_tank'}},
    {name: 'rwx-game-keep-clear', time: 100, args: {}},
  ];
  const rules = keepRules([], commands);
  assert.equal(rules.length, 1);
  assert.deepEqual(rules[0], {unit: 7, action: 'u_tank', from: 10, to: 100});
});

test('findTelemetry only looks inside the match directory', () => {
  const matchDir = path.join(temp, 'locate');
  writeTelemetry(matchDir, [native(1, [1], 'agent')]);
  assert.ok(findTelemetry(matchDir));
  assert.equal(findTelemetry(path.join(temp, 'missing')), null);
  assert.equal(findTelemetry(null), null);
});
