'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {loadConfig, current, currentTree, FILE} = require('../config');
const {scoreEvidence, rawFeatures, DEFAULTS: STAGE1} = require('../stage1-v2');
const stage3 = require('../score_stage_3');
const total = require('../score_match_v2');

const META = new Set(['description', 'version', 'status']);
function knobs(node, trail = []) {
  if (node == null || typeof node !== 'object' || Array.isArray(node)) return [];
  if (Object.prototype.hasOwnProperty.call(node, 'current')) return [[trail.join('.'), node]];
  return Object.entries(node).flatMap(([key, value]) =>
    META.has(key) ? [] : knobs(value, [...trail, key]));
}

test('config.json is the single parameter ledger and every knob is described', () => {
  const config = loadConfig();
  assert.equal(config.schema, 1);
  assert.equal(config.version, 'rwx-evaluation-config-v1');
  assert.ok(fs.statSync(FILE).isFile());
  const rows = ['total', 'stage1', 'stage2', 'stage3'].flatMap(section => knobs(config[section], [section]));
  assert.ok(rows.length >= 40, `expected the stage 1-3 knobs, got ${rows.length}`);
  for (const [dotted, node] of rows) {
    assert.ok(node.description, `${dotted}: missing description`);
    assert.ok('initial' in node, `${dotted}: missing initial value`);
    assert.ok('current' in node, `${dotted}: missing current value`);
  }
  const stage3Params = current('stage3.parameters');
  assert.equal(stage3Params.ambush.PMax, 50);
  assert.equal(stage3Params.grinder.Cap, 20);
  assert.equal(stage3Params.funds.releaseShare, 0.8);
  assert.deepEqual(Object.keys(currentTree({a: {description: 'x', current: 1}})), ['a']);
  // Module versions must come from the same file, otherwise the ledger and the code drift apart.
  assert.equal(total.VERSION, config.total.v2.version);
  assert.equal(stage3.VERSION, config.stage3.version);
  assert.deepEqual(Object.keys(STAGE1).sort(),
    Object.keys(current('stage1.v2.parameters')).sort(), 'stage1-v2 reads its own parameter block');
});

function commands(rows) {
  return rows.map(([type, time], line) => ({type, time, line: line + 1}));
}

test('stage1-v2 derives features, shrinks few samples and computes the three groups', () => {
  const cmds = commands([['B', 10], ['A', 20], ['B', 60], ['B', 62]]);
  const evidence = rawFeatures(cmds, [10, 40, 100], 600, STAGE1);
  assert.equal(evidence.values.f1, 10);
  assert.equal(evidence.values.f2, 40);
  assert.equal(evidence.values.f3, 10);
  assert.equal(evidence.values.f5, 538, 'the tail window counts as a silence sample');
  assert.equal(evidence.values.f6, 500);

  const score = scoreEvidence(cmds, [10, 40, 100], 600);
  const [lo3, hi3] = STAGE1.featureThresholdsSeconds.f3;
  const raw = (Math.log1p(10) - Math.log1p(lo3)) / (Math.log1p(hi3) - Math.log1p(lo3));
  assert.ok(Math.abs(score.features.f3.u - (raw + 4) / 5) < 1e-3, 'one sample shrinks towards the worst case');
  assert.equal(score.features.f2.u, 1, 'a single very late A block saturates f2');
  assert.equal(score.features.f3.lowConfidence, true);
  assert.equal(score.features.f4.diagnosticOnly, true);
  assert.ok(score.score > 0 && score.score < 100);
  assert.equal(score.eligible, true);
  assert.ok(Math.abs(score.deduction - (100 - score.score)) < 1e-9);
});

test('stage1-v2 bounds: no A block saturates cycle features, short matches are not scored, low activity is capped', () => {
  const noA = scoreEvidence(commands([['B', 10], ['B', 40]]), [10, 40], 600);
  assert.equal(noA.features.f2.u, 1);
  assert.equal(noA.features.f3.u, 1);
  assert.equal(scoreEvidence(commands([['B', 10]]), [10], 299).eligible, false);
  const idle = scoreEvidence(commands([['B', 10], ['B', 11]]), [10], 1200);
  assert.equal(idle.activity.applied, true);
  assert.ok(idle.score <= STAGE1.activityGateScoreCap);
});

test('stage3 funds v2 reads hoarding intervals and honours releaseShare from the ledger', () => {
  // theta = K * income = 30. Funds climb to 1000, drain to 50 and recover to 1100: the drain is one
  // counted spend, and whether it ends the interval depends only on releaseShare.
  const sample = (funds, second) => ({second, funds, incomePerSecond: 1});
  const decline = [900, 800, 700, 600, 500, 400, 300, 200, 100, 50];
  const samples = [
    ...Array.from({length: 31}, () => 1000), ...decline, ...Array.from({length: 30}, () => 1100),
  ].map(sample);
  const summary = {durationMs: samples.length * 1000, complete: true};
  const params = stage3.DEFAULTS.funds;
  const base = stage3.scoreEvents([], summary, {}, samples);
  const share = base.financialEvents[0].spends[0].share;
  assert.ok(share > 0.8 && share < 1, `the big drain releases ${share} of the peak`);
  const closed = stage3.scoreEvents([], summary,
    {funds: {...params, releaseShare: share - 0.01}}, samples);
  const kept = stage3.scoreEvents([], summary,
    {funds: {...params, releaseShare: share + 0.01}}, samples);
  const fundsOf = score => score.breakdown.find(row => row.family === 'STAGE3_FUNDS');
  assert.equal(fundsOf(closed).items.length, 2,
    'a release above releaseShare ends the interval and a new one starts');
  assert.equal(fundsOf(kept).items.length, 1,
    'a release below releaseShare keeps the same interval alive');
  const funds = fundsOf(kept);
  assert.equal(funds.cap, params.Cap);
  assert.ok(funds.items[0].durationSeconds > params.d0, 'the interval outlives the grace window');
  assert.ok(funds.items[0].severity > 0 && funds.items[0].severity <= 1);
  assert.ok(Math.abs(funds.items[0].points - params.eventMax * funds.items[0].severity) < 0.01);
});

test('stage3 flags missing economy evidence instead of scoring zero', () => {
  const score = stage3.scoreEvents([], {durationMs: 600000, complete: true});
  const funds = score.breakdown.find(row => row.family === 'STAGE3_FUNDS');
  assert.equal(funds.deduction, 0);
  assert.equal(score.evidenceComplete, false);
  assert.deepEqual(funds.excluded, [{id: null, reason: 'ECONOMY_EVIDENCE_MISSING'}]);
});

test('total-v2 combines stage3 and stage1-v2 with k from config and rejects stale reports', () => {
  assert.equal(total.combined(50, 80, 0.6), 68);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-v2-total-'));
  const dir = path.join(root, 'match');
  fs.mkdirSync(path.join(dir, 'stage_1_v2'), {recursive: true});
  fs.mkdirSync(path.join(dir, 'stage_3'), {recursive: true});
  fs.writeFileSync(path.join(dir, 'stage_1_v2', 'score.json'), JSON.stringify({version: 'stage1-score-v2',
    eligible: true, score: 50, provisionalReasons: ['uncalibrated thresholds']}));
  fs.writeFileSync(path.join(dir, 'stage_3', 'score.json'), JSON.stringify({version: 'stage3-score-v2',
    evidenceComplete: true, total: 80, calibration: {reasons: ['S0 provisional']}}));
  const score = total.main(['node', 'score_match_v2.js', dir]);
  assert.equal(score.total, 68);
  assert.equal(score.components.stage3.weight, 0.6);
  assert.deepEqual(score.provisionalReasons, ['uncalibrated thresholds', 'S0 provisional']);
  assert.ok(fs.existsSync(path.join(dir, 'score_v2.json')));
  fs.writeFileSync(path.join(dir, 'stage_3', 'score.json'), JSON.stringify({version: 'stage3-score-v1',
    evidenceComplete: true, total: 80}));
  assert.throws(() => total.main(['node', 'score_match_v2.js', dir]), /version/);
  fs.rmSync(root, {recursive: true, force: true});
});
