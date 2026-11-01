'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {scoreEvents, deduction, DEFAULTS, FAMILY} = require('../events/score');
const {economySeconds, economyCsv, timelineCsv, reportText} = require('../score_stage_2');

const event = (type, extra = {}) => ({id: `${type}-1`, type, t_start: 0, t_end: 0,
  status: 'DETECTED', ...extra});

test('event families map old and new names onto the same buckets', () => {
  assert.equal(FAMILY.AMBUSH, 'AMBUSH');
  assert.equal(FAMILY.FEED, 'GRINDER');
  assert.equal(FAMILY.GRINDER, 'GRINDER');
  assert.equal(FAMILY.STALE_COMMAND, 'STALE');
  assert.equal(FAMILY.STALE_BURST, 'STALE');
  assert.equal(FAMILY.BUILDER_GAP, 'BUILDER_IDLE');
  assert.equal(FAMILY.PRODUCTION_GAP, 'FACTORY_IDLE');
  assert.equal(FAMILY.COMMAND_FAILURE, 'STALE');
  assert.equal(FAMILY.UNKNOWN_TYPE, undefined);
});

test('only unanswered ambushes cost points, and a base attack costs more', () => {
  const config = {...DEFAULTS, weights: DEFAULTS.weights, caps: DEFAULTS.caps};
  assert.equal(deduction(event('AMBUSH', {status: 'UNANSWERED'}), config).points, 3);
  assert.equal(deduction(event('AMBUSH', {status: 'UNANSWERED', kind: 'BASE'}), config).points, 5);
  assert.equal(deduction(event('AMBUSH', {status: 'DETECTED'}), config).points, 0);
  assert.equal(deduction(event('AMBUSH', {status: 'UNCERTAIN'}), config).points, 0);
});

test('grinder costs only the net losses above the free allowance', () => {
  const config = {...DEFAULTS, weights: DEFAULTS.weights, caps: DEFAULTS.caps};
  const row = (self, enemy) => event('GRINDER', {lossesSelf: {tank: self}, lossesEnemy: {tank: enemy}});
  assert.equal(deduction(row(3, 0), config).points, 0);
  assert.equal(deduction(row(5, 4), config).points, 0);
  assert.equal(deduction(row(7, 2), config).points, 1);
  assert.equal(deduction(event('GRINDER', {status: 'EXPLAINED', lossesSelf: {tank: 9}}), config).points, 0);
  // The legacy union field is used when the split fields are absent.
  assert.equal(deduction(event('FEED', {losses: {tank: 6}}), config).points, 1.5);
});

test('a stale burst absorbs its first rejection and charges the rest', () => {
  const config = {...DEFAULTS, weights: DEFAULTS.weights, caps: DEFAULTS.caps};
  assert.equal(deduction(event('STALE_BURST', {count: 1}), config).points, 0);
  assert.equal(deduction(event('STALE_BURST', {count: 4}), config).points, 3.5);
  assert.equal(deduction(event('STALE_COMMAND', {count: 1}), config).points, 0);
  assert.equal(deduction(event('STALE_BURST', {count: 4, scoreable: false}), config).points, 0);
});

test('idle events charge the time above the free window and cap one episode', () => {
  const config = {...DEFAULTS, weights: DEFAULTS.weights, caps: DEFAULTS.caps};
  assert.equal(deduction(event('FACTORY_IDLE', {durationMs: 3000}), config).points, 0);
  assert.equal(deduction(event('FACTORY_IDLE', {durationMs: 63000}), config).points, 3);
  // 10 minutes is clamped to the per-episode cap of two minutes.
  assert.equal(deduction(event('FACTORY_IDLE', {durationMs: 600000}), config).points, 6);
  assert.equal(deduction(event('BUILDER_IDLE', {durationMs: 63000}), config).points, 3);
  assert.equal(deduction(event('BUILDER_IDLE', {durationMs: 63000, status: 'UNCERTAIN'}), config).points, 0);
});

test('category caps bound the total and the result stays additive', () => {
  const events = [];
  for (let index = 0; index < 12; index++) events.push(event('AMBUSH', {id: `A-${index}`, status: 'UNANSWERED'}));
  const score = scoreEvents(events);
  const ambush = score.breakdown.find(row => row.family === 'AMBUSH');
  assert.equal(ambush.raw, 36);
  assert.equal(ambush.capped, 20);
  assert.equal(score.deduction, 20);
  assert.equal(score.total, 80);
  assert.equal(score.breakdown.reduce((sum, row) => sum + row.capped, 0), score.deduction);
});

test('an event outside every family is reported as excluded, not silently dropped', () => {
  const score = scoreEvents([event('SOMETHING_NEW')]);
  assert.equal(score.excluded.SOMETHING_NEW, 1);
  assert.equal(score.deduction, 0);
  assert.equal(score.total, 100);
});

test('weights and caps can be overridden without touching the defaults', () => {
  const score = scoreEvents([event('AMBUSH', {status: 'UNANSWERED'})],
    {weights: {AMBUSH_UNANSWERED: 7}, caps: {AMBUSH: 5}});
  assert.equal(score.breakdown[0].raw, 7);
  assert.equal(score.breakdown[0].capped, 5);
  assert.equal(score.total, 95);
  assert.equal(DEFAULTS.weights.AMBUSH_UNANSWERED, 3);
});

test('economy seconds are rebuilt from the packed log and keep the measured delta', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-score-'));
  const file = path.join(dir, 'economy.log');
  fs.writeFileSync(file, '4000(+36),4054(+36),4108(+36)\n');
  const rows = economySeconds(file);
  assert.deepEqual(rows.map(row => row.second), [0, 1, 2]);
  assert.equal(rows[1].fundsDelta, 54);
  assert.equal(rows[0].fundsDelta, null);
  const csv = economyCsv(rows);
  assert.match(csv, /^second,funds,incomeField,fundsDelta\n0,4000,36,\n1,4054,36,54\n/);
  assert.equal(economySeconds(path.join(dir, 'missing.log')), null);
  fs.rmSync(dir, {recursive: true, force: true});
});

test('timeline sorts by start time and carries the deduction of each event', () => {
  const csv = timelineCsv([
    {id: 'B', type: 'STALE_BURST', t_start: 200, t_end: 200, status: 'DETECTED', count: 4},
    {id: 'A', type: 'AMBUSH', t_start: 100, t_end: 150, status: 'UNANSWERED'},
  ]);
  const lines = csv.trim().split('\n');
  assert.match(lines[0], /^t_start,t_end,family,type,status,scoreable,points,reason,detail$/);
  assert.match(lines[1], /^100,150,AMBUSH,AMBUSH,UNANSWERED,yes,3,unanswered_ambush/);
  assert.match(lines[2], /^200,200,STALE,STALE_BURST,DETECTED,yes,3\.5,rejected_command_burst/);
});

test('the text report names the score, the caps and the top deductions', () => {
  const score = scoreEvents([event('AMBUSH', {status: 'UNANSWERED'})]);
  const text = reportText(score, {complete: true, economyComplete: true, team: 0, source: 'live'});
  assert.match(text, /基准分 100/);
  assert.match(text, /总分 97/);
  assert.match(text, /unanswered_ambush=1/);
  assert.match(text, /F（资金空余）只出材料/);
});
