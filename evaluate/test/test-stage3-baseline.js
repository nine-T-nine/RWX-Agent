'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {collect, calibrate, median, histogram, markdown, inspect} = require('../tools/stage3_baseline.js');
const {scoreEvents, DEFAULTS} = require('../score_stage_3.js');

function ambushEvent(id, extra = {}) {
  return {schema: 1, version: 'stage3-events-v1', id, type: 'STAGE3_AMBUSH', t_start: 1000, t_end: 1000,
    scoreable: true, status: 'scoreable', reason: null, unit: {id: 10, type: 'c_tank'},
    D_hp: 100, D_sh: 0, N: 1, T_ms: 0, HP_max: 100, Shield_max: 0, cost: 1000, died: true,
    destroyed: false, closeReason: 'ACTION', complete: true, ...extra};
}

function summary(durationMs, counts) {
  return {schema: 1, version: 'stage3-events-v1', team: 0, source: 'replay', complete: true,
    endReason: 'replay_end', durationMs, counts, scoreable: counts, dropped: {ambushShort: 0, barrageSingle: 0},
    grinderFunnel: {total: 0, damageKilled: 0, nonTransport: 0, activeZone: 0, pastGrace: 0},
    zones: {created: 0, cleared: 0, dropped: 0, checks: 0, lifetimesMs: [], averageLifetimeMs: 0,
      jitter: 0, jitterRate: 0, createRatios: []}};
}

/** One match workspace: the stage_3 pair plus the resim provenance the tool insists on. */
function matchWorkspace(root, name, events, durationMs, checksumWarnings = 0) {
  const dir = path.join(root, name);
  fs.mkdirSync(path.join(dir, 'stage_3'), {recursive: true});
  fs.mkdirSync(path.join(dir, 'resim'), {recursive: true});
  const counts = {STAGE3_AMBUSH: 0, STAGE3_BARRAGE: 0, STAGE3_GRINDER: 0};
  for (const event of events) counts[event.type]++;
  const data = summary(durationMs, counts);
  fs.writeFileSync(path.join(dir, 'stage_3', 'events.jsonl'),
    events.map(event => JSON.stringify(event)).join('\n') + '\n', 'utf8');
  fs.writeFileSync(path.join(dir, 'stage_3', 'summary.json'), JSON.stringify(data) + '\n', 'utf8');
  fs.writeFileSync(path.join(dir, 'resim', 'playback.json'), JSON.stringify({schema: 1, source: 'resim',
    checksumWarnings, team: 0}) + '\n', 'utf8');
  return dir;
}

test('median and histogram handle odd, even and empty samples', () => {
  assert.equal(median([3, 1, 2]), 2);
  assert.equal(median([4, 1, 3, 2]), 2.5);
  assert.equal(median([]), null);
  assert.deepEqual(histogram([2, 2, 5]), {'2': 2, '5': 1});
});

test('S0 comes from the median S of the faithful matches only', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-baseline-'));
  const counts = [1, 2, 4];
  counts.forEach((count, index) => {
    const events = Array.from({length: count}, (_, i) => ambushEvent(`A-${index}-${i}`));
    matchWorkspace(root, `match-${index}`, events, 1200000);
  });
  // A desynchronised replay keeps its report but must not move the median.
  matchWorkspace(root, 'match-desync', Array.from({length: 20}, (_, i) => ambushEvent(`D-${i}`)), 1200000, 475);

  const rows = collect({root});
  assert.equal(rows.length, 4);
  const excluded = rows.find(row => row.match === 'match-desync');
  assert.equal(excluded.status, 'excluded');
  assert.deepEqual(excluded.reasons, ['replay_desync:475']);

  const result = calibrate(rows);
  assert.equal(result.included.length, 3);
  const expected = result.included.map(row => row.families.ambush.S).sort((a, b) => a - b)[1];
  assert.equal(result.ambushS0, expected);
  assert.equal(result.barrageMedian, null, 'no barrage events means no barrage evidence');
  assert.equal(result.barrageS0, DEFAULTS.barrage.S0, 'an empty family keeps the placeholder instead of dividing by zero');

  // The calibrated total uses the median S0: the middle match sits at 1-e^-1 of the cap.
  const middle = result.scored.find(row => row.families.ambush.S === expected);
  const cap = DEFAULTS.ambush.PMax;
  assert.ok(Math.abs(middle.calibrated.ambush - cap * (1 - Math.exp(-1))) < 1e-2);
  assert.equal(middle.calibrated.barrage, 0);
  assert.equal(middle.score.calibration.s0Source, 'archive-median');
  assert.equal(middle.score.calibration.provisional, true);
  assert.equal(middle.score.calibration.baseline.barrageFallback, true);
  // The excluded match still gets numbers for review, but they are flagged and stay out of the median.
  const desync = result.scored.find(row => row.match === 'match-desync');
  assert.equal(desync.score.calibration.excluded, true);
  assert.ok(desync.calibrated.ambush > 0);
  fs.rmSync(root, {recursive: true, force: true});
});

test('a missing report is reported instead of being scored as zero', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-baseline-'));
  fs.mkdirSync(path.join(root, 'empty'), {recursive: true});
  const row = inspect(path.join(root, 'empty'));
  assert.equal(row.status, 'missing');
  assert.equal(row.reason, 'no stage_3 report');
  assert.throws(() => calibrate([row]), /need at least 3 faithful matches/);
  fs.rmSync(root, {recursive: true, force: true});
});

test('a frozen S0 scores a new batch without recomputing a median', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-baseline-'));
  // Two matches would be too few for a median, but a frozen baseline does not need one.
  matchWorkspace(root, 'match-a', [ambushEvent('A-1')], 1200000);
  matchWorkspace(root, 'match-b', [ambushEvent('B-1'), ambushEvent('B-2')], 1200000);
  const rows = collect({root});
  assert.throws(() => calibrate(rows), /need at least 3 faithful matches/);
  const result = calibrate(rows, {s0: {
    ambush: {S0: 2, matches: 7, source: 'archive-corpus'},
    barrage: {S0: 3, matches: 7, source: 'archive-corpus'}
  }});
  assert.equal(result.fixed, true);
  assert.equal(result.ambushS0, 2);
  assert.equal(result.barrageS0, 3);
  const scored = result.scored[0].score;
  assert.equal(scored.calibration.s0Source, 'fixed-baseline');
  assert.equal(scored.calibration.excluded, false);
  assert.deepEqual([scored.calibration.baseline.matches, scored.calibration.baseline.source],
    [7, 'archive-corpus']);
  // S = 0.1 at S0 = 2 is 60*(1-e^-0.05) ≈ 2.926, not the saturated 37.9 of the median rule.
  assert.ok(Math.abs(result.scored[0].calibrated.ambush - 60 * (1 - Math.exp(-0.05))) < 1e-2);
  fs.rmSync(root, {recursive: true, force: true});
});

test('the markdown table lists every match and the excluded reason', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-stage3-baseline-'));
  for (let index = 0; index < 3; index++) {
    matchWorkspace(root, `match-${index}`, [ambushEvent(`A-${index}`)], 600000);
  }
  matchWorkspace(root, 'match-desync', [ambushEvent('D-0')], 600000, 12);
  const rows = collect({root});
  const result = calibrate(rows);
  const text = markdown(result, {generatedAt: '2026-11-05T00:00:00.000Z'});
  assert.match(text, /S0 标定：STAGE3_AMBUSH = 0\.2，STAGE3_BARRAGE = 1（该族无记录，沿用占位值）/);
  assert.match(text, /match-0 \| 计入 \| 600 \|/);
  assert.match(text, /match-desync \| 排除:replay_desync:12 \|/);
  assert.match(text, /未进入标定[\s\S]*match-desync: replay_desync:12/);
  fs.rmSync(root, {recursive: true, force: true});
});

