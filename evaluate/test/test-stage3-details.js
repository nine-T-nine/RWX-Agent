'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {collect, inspect, render, share} = require('../tools/stage3_details.js');

function workspace(checksumWarnings = 0) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-details-'));
  const dir = path.join(root, 'match-1');
  fs.mkdirSync(path.join(dir, 'stage_3'), {recursive: true});
  fs.mkdirSync(path.join(dir, 'resim'), {recursive: true});
  const events = [
    {id: 'A-1', type: 'STAGE3_AMBUSH', t_start: 120000, t_end: 140000, scoreable: true},
    {id: 'A-2', type: 'STAGE3_AMBUSH', t_start: 300000, t_end: 310000, scoreable: true},
    {id: 'H-1', type: 'STAGE3_BARRAGE', t_start: 60000, t_end: 70000, scoreable: true},
    {id: 'G-1', type: 'STAGE3_GRINDER', t_start: 500000, t_end: 500000, scoreable: false, reason: 'OUTSIDE_ACTIVE_ZONE'}
  ];
  fs.writeFileSync(path.join(dir, 'stage_3', 'events.jsonl'),
    events.map(event => JSON.stringify(event)).join('\n') + '\n', 'utf8');
  const score = {
    schema: 1, version: 'stage3-score-v1', baseline: 100,
    parameters: {alpha: 0.4, ambush: {S0: 9.642}, barrage: {S0: 12.155}, grinder: {}},
    calibration: {provisional: true, s0Source: 'archive-median',
      baseline: {ambushS0: 9.642, barrageS0: 12.155, matches: 7}},
    durationSeconds: 600, deduction: 22, total: 78,
    breakdown: [
      {family: 'STAGE3_AMBUSH', raw: 1.5, durationScale: 0.5, S: 3, cap: 60, deduction: 15,
        items: [
          {id: 'A-1', E: 100, H: 100, L: 1, W: 0.5, w: 1, Tf: 1, s: 1, N: 5, unit: {id: 7, type: 'c_tank'}, died: true, destroyed: false},
          {id: 'A-2', E: 50, H: 100, L: 0.45, W: 0.61, w: 0.8, Tf: 1.2, s: 0.5, N: 2, unit: {id: 9, type: 'builder'}, died: false, destroyed: false}
        ], excluded: []},
      {family: 'STAGE3_BARRAGE', raw: 0.5, durationScale: 0.5, S: 1, cap: 60, deduction: 7,
        items: [{id: 'H-1', E: 300, H: 800, L: 0.337, W: 0.61, w: 1, s: 0.5, N: 3,
          unit: {id: 32, type: 'extractorT1'}, died: false, destroyed: false}], excluded: []},
      {family: 'STAGE3_GRINDER', raw: 0, cap: 40, deduction: 0, items: [],
        excluded: [{id: 'G-2', reason: 'NOT_DAMAGE_KILL'}]}
    ]
  };
  fs.writeFileSync(path.join(dir, 'stage_3', 'score.calibrated.json'), JSON.stringify(score) + '\n', 'utf8');
  fs.writeFileSync(path.join(dir, 'stage_3', 'summary.json'), JSON.stringify({
    team: 0, source: 'replay', complete: true, endReason: 'replay_end', durationMs: 600000,
    counts: {STAGE3_AMBUSH: 2, STAGE3_BARRAGE: 1, STAGE3_GRINDER: 1}
  }) + '\n', 'utf8');
  fs.writeFileSync(path.join(dir, 'resim', 'playback.json'), JSON.stringify({schema: 1, source: 'resim',
    checksumWarnings, replay: 'x.replay'}) + '\n', 'utf8');
  return {root, dir};
}

test('per-record rows carry the formula terms and a proportional share of the family deduction', () => {
  const {root, dir} = workspace();
  const row = inspect(dir);
  assert.equal(row.calibrated, true);
  assert.equal(row.included, true);
  const family = row.score.breakdown[0];
  assert.equal(share(family, family.items[0]), 10);
  assert.equal(share(family, family.items[1]), 5);
  const text = render(collect({root}));
  assert.match(text, /\| 1 \| 120 \| c_tank#7 \| 5 \| 100 \| 100 \| 1 \| 0\.5 \| 1 \| 1 \| 1 \| 10 \| 死亡 \|/);
  assert.match(text, /\| 2 \| 300 \| builder#9 \|/);
  assert.match(text, /总分 \*\*78 \/ 100\*\*，扣分 \*\*22\*\*/);
  assert.match(text, /S0 取自 7 局中位数（偷袭 9\.642 \/ 炮击 12\.155）/);
  assert.match(text, /排除 1 条：NOT_DAMAGE_KILL 1（G-2\(NOT_DAMAGE_KILL\)）/);
  fs.rmSync(root, {recursive: true, force: true});
});

test('a diverged replay is marked as excluded and still lists its records', () => {
  const {root, dir} = workspace(475);
  const row = inspect(dir);
  assert.equal(row.included, false);
  assert.deepEqual(row.reasons, ['replay_desync:475']);
  const text = render(collect({root}));
  assert.match(text, /排除:replay_desync:475/);
  assert.match(text, /checksum 不一致 475/);
  assert.match(text, /c_tank#7/);
  fs.rmSync(root, {recursive: true, force: true});
});

test('a match without a stage_3 report is skipped instead of crashing the report', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-details-'));
  fs.mkdirSync(path.join(root, 'empty'), {recursive: true});
  assert.deepEqual(collect({root}), []);
  fs.rmSync(root, {recursive: true, force: true});
});
