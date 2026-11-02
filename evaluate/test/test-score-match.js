'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {main, combined, WEIGHTS} = require('../score_match');
const {VERSION: STAGE1} = require('../stage1-metrics');
const {VERSION: STAGE2} = require('../events/score');

function workspace({stage1 = 80, stage2 = 90, version1 = STAGE1, version2 = STAGE2, preprocess = null} = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-total-'));
  for (const [name, body] of [['stage_1', {version: version1, score: stage1, deduction: 100 - stage1,
    systemHealth: {scoreStatus: 'valid'}}], ['stage_2', {version: version2, total: stage2,
    deduction: 100 - stage2}]]) {
    fs.mkdirSync(path.join(dir, name), {recursive: true});
    fs.writeFileSync(path.join(dir, name, 'score.json'), JSON.stringify(body));
  }
  if (preprocess) fs.writeFileSync(path.join(dir, 'preprocess.json'), JSON.stringify(preprocess));
  return dir;
}

test('the combined score is 0.4 * stage1 + 0.6 * stage2', () => {
  assert.deepEqual(WEIGHTS, {stage1: 0.4, stage2: 0.6});
  assert.equal(combined({value: 80}, {value: 90}), 86);
  assert.equal(combined({value: 100}, {value: 100}), 100);
  assert.equal(combined({value: 0}, {value: 0}), 0);
  assert.equal(combined({value: 72.9}, {value: 77.806}), 75.844);
});

test('a match with current-version stages gets a combined score on disk', () => {
  const dir = workspace();
  const result = main(['node', 'score_match.js', dir]);
  assert.equal(result.total, 86);
  assert.equal(result.versionsCurrent, true);
  assert.equal(result.scoreEligible, true);
  const written = JSON.parse(fs.readFileSync(path.join(dir, 'score.json'), 'utf8'));
  assert.equal(written.total, 86);
  assert.equal(written.components.stage1.value, 80);
  assert.equal(written.components.stage2.weight, 0.6);
  assert.match(fs.readFileSync(path.join(dir, 'score.txt'), 'utf8'), /总分 86/);
});

test('a stale formula version is refused instead of being mixed into the leaderboard', () => {
  const dir = workspace({version1: 'stage1-v1'});
  assert.throws(() => main(['node', 'score_match.js', dir]), /旧版本不参与综合评分/);
  // Opt-in only: the caller must say so explicitly, and the result is flagged.
  const result = main(['node', 'score_match.js', dir, '--allow-stale']);
  assert.equal(result.versionsCurrent, false);
  assert.equal(result.components.stage1.stale, true);
  assert.equal(result.total, 86);
});

test('preprocessing can veto the combined score without deleting the components', () => {
  const dir = workspace({preprocess: {version: 'preprocess-v1', ending: {kind: 'aborted'},
    window: {startMs: 0, endMs: 40000, trimmedMs: 90000}, scoreEligible: false,
    ineligibleReason: 'window_too_short:40.0s'}});
  const result = main(['node', 'score_match.js', dir]);
  assert.equal(result.total, null);
  assert.equal(result.scoreEligible, false);
  assert.match(result.ineligibleReason, /window_too_short/);
  assert.equal(result.components.stage1.value, 80);
  const forced = main(['node', 'score_match.js', dir, '--allow-ineligible']);
  assert.equal(forced.total, 86);
});
