#!/usr/bin/env node
'use strict';
/**
 * Summarise Stage 3 over a set of already-built `<match>/stage_3` reports and calibrate the two
 * saturation constants.
 *
 * `S0` is the only Stage-3 parameter that cannot come from game data: the design asks for the
 * median `S` of a baseline. This tool reads each match's compiled events (`events.jsonl`) and
 * summary, recomputes `S` with the default weights, takes the median over the replay-faithful
 * matches, and writes a calibrated variant next to the provisional report:
 *
 *   <match>/stage_3/score.calibrated.json   same shape as score.json, S0 = baseline median
 *   <match>/stage_3/score.calibrated.txt    human-readable summary
 *   <out>.json / <out>.md                   aggregate: S distribution, N diagnostics, per match
 *
 * The provisional `score.json` is left untouched: the archive corpus is a convenience sample of
 * different models and prompts, so its median is a working calibration, not a controlled baseline.
 */
const fs = require('node:fs');
const path = require('node:path');
const { DEFAULTS, scoreEvents, report } = require('../score_stage_3.js');

const VERSION = 'stage3-baseline-v1';
const ROOT = path.resolve(__dirname, '../..');
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const readEvents = file => fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')
  .split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
const round = (value, digits = 3) => Number(Number(value).toFixed(digits));

function median(values) {
  const numbers = values.filter(value => Number.isFinite(value)).sort((a, b) => a - b);
  if (!numbers.length) return null;
  const middle = numbers.length >> 1;
  return numbers.length % 2 ? numbers[middle] : (numbers[middle - 1] + numbers[middle]) / 2;
}

function histogram(values) {
  const counts = {};
  for (const value of values) counts[value] = (counts[value] || 0) + 1;
  return counts;
}

/** One match: identity, provenance, faithful-playback flag, the three families and their S. */
function inspect(dir) {
  const stage3 = path.join(dir, 'stage_3');
  const eventsFile = path.join(stage3, 'events.jsonl');
  const summaryFile = path.join(stage3, 'summary.json');
  if (!fs.existsSync(eventsFile) || !fs.existsSync(summaryFile)) {
    return {match: path.basename(dir), dir, status: 'missing', reason: 'no stage_3 report'};
  }
  const events = readEvents(eventsFile), summary = readJson(summaryFile);
  const playbackFile = path.join(dir, 'resim', 'playback.json');
  const playback = fs.existsSync(playbackFile) ? readJson(playbackFile) : null;
  // A live capture is faithful by definition; a resim has to prove it by matching every checksum.
  const checksumWarnings = playback ? Number(playback.checksumWarnings ?? 0) : null;
  const reasons = [];
  if (summary.complete !== true) reasons.push('evidence_incomplete');
  if (checksumWarnings !== null && checksumWarnings > 0) reasons.push(`replay_desync:${checksumWarnings}`);
  const provisional = scoreEvents(events, summary);
  const family = name => provisional.breakdown.find(item => item.family === name);
  const ambush = family('STAGE3_AMBUSH'), barrage = family('STAGE3_BARRAGE'), grinder = family('STAGE3_GRINDER');
  return {
    match: path.basename(dir), dir, status: reasons.length ? 'excluded' : 'included',
    reasons, checksumWarnings, playbackSource: playback ? playback.source : 'live',
    replay: playback ? playback.replay ?? null : null,
    replaySha256: playback ? playback.replaySha256 ?? null : null,
    resim: playback ? {engineCommit: playback.engineCommit ?? null, createdAt: playback.createdAt ?? null,
      seconds: playback.seconds ?? null} : null,
    team: summary.team, source: summary.source, endReason: summary.endReason,
    durationSeconds: provisional.durationSeconds,
    counts: summary.counts, scoreable: summary.scoreable, dropped: summary.dropped,
    grinderFunnel: summary.grinderFunnel,
    zones: {created: summary.zones.created, cleared: summary.zones.cleared,
      dropped: summary.zones.dropped, checks: summary.zones.checks,
      averageLifetimeMs: round(summary.zones.averageLifetimeMs, 1), jitter: summary.zones.jitter,
      createRatios: summary.zones.createRatios.map(zone => ({zoneId: zone.zoneId, E: zone.E, F: zone.F,
        ratio: round(zone.ratio, 4)}))},
    families: {
      ambush: {S: ambush.S, raw: ambush.raw, deduction: ambush.deduction, cap: ambush.cap,
        records: ambush.diagnostics.records, lethalShare: ambush.diagnostics.lethalShare,
        N: ambush.diagnostics.N, NHistogram: histogram(ambush.diagnostics.N), types: ambush.diagnostics.types},
      barrage: {S: barrage.S, raw: barrage.raw, deduction: barrage.deduction, cap: barrage.cap,
        records: barrage.diagnostics.records, lethalShare: barrage.diagnostics.lethalShare,
        N: barrage.diagnostics.N, NHistogram: histogram(barrage.diagnostics.N), types: barrage.diagnostics.types},
      grinder: {deduction: grinder.deduction, cap: grinder.cap, events: grinder.diagnostics.events,
        scoreableDeaths: grinder.diagnostics.scoreableDeaths, types: grinder.diagnostics.types}
    },
    provisional: {deduction: provisional.deduction, total: provisional.total},
  };
}

function calibrate(rows, options = {}) {
  const included = rows.filter(row => row.status === 'included');
  const minimum = Number(options.minimum ?? 3);
  // `--s0` freezes an earlier calibration (e.g. score a new batch against the archive baseline);
  // without it the median of this very set is the baseline.
  const fixed = options.s0 || null;
  if (!fixed && included.length < minimum) {
    throw Error(`need at least ${minimum} faithful matches to calibrate S0, got ${included.length}`);
  }
  const medianOf = name => median(included.map(row => row.families[name].S));
  // A family with no events in the whole corpus has S = 0 and no usable scale: fall back to the
  // shipped placeholder instead of dividing by zero.
  const positive = value => (Number.isFinite(value) && value > 0 ? value : null);
  const ambushMedian = included.length ? positive(medianOf('ambush')) : null;
  const barrageMedian = included.length ? positive(medianOf('barrage')) : null;
  const ambushS0 = fixed ? Number(fixed.ambush?.S0 ?? fixed.ambushS0) : (ambushMedian ?? DEFAULTS.ambush.S0);
  const barrageS0 = fixed ? Number(fixed.barrage?.S0 ?? fixed.barrageS0) : (barrageMedian ?? DEFAULTS.barrage.S0);
  const weights = {ambush: {S0: ambushS0}, barrage: {S0: barrageS0}};
  const scored = rows.map(row => {
    if (!row.families) return row;
    const score = scoreEvents(readEvents(path.join(row.dir, 'stage_3', 'events.jsonl')),
      readJson(path.join(row.dir, 'stage_3', 'summary.json')), weights);
    score.calibration = {
      provisional: true,
      s0Source: fixed ? 'fixed-baseline' : 'archive-median',
      excluded: row.status !== 'included',
      baseline: {ambushS0: round(ambushS0), barrageS0: round(barrageS0),
        matches: fixed ? Number(fixed.ambush?.matches ?? fixed.matches ?? 0) : included.length,
        source: fixed ? (fixed.ambush?.source ?? fixed.source ?? 'fixed') : 'archive-corpus',
        ambushFallback: !fixed && ambushMedian === null, barrageFallback: !fixed && barrageMedian === null},
      reason: fixed
        ? `S0 固定为一组已有基线（${fixed.ambush?.source ?? fixed.source ?? 'fixed'}）；本批不重新标定`
        : 'S0 取自归档语料 S 中位数；受控基线（固定提示词/模型）建立前仍不得进入公榜'
    };
    return {...row, calibrated: {deduction: score.deduction, total: score.total,
      ambush: score.breakdown[0].deduction, barrage: score.breakdown[1].deduction,
      grinder: score.breakdown[2].deduction}, score};
  });
  return {included, ambushS0, barrageS0, ambushMedian, barrageMedian, weights, scored, fixed: Boolean(fixed)};
}

function markdown(result, aggregate) {
  const label = (name, s0, medianValue) => (medianValue === null && !result.fixed
    ? `${round(s0)}（该族无记录，沿用占位值）` : `${round(s0)}`);
  const lines = ['# ' + (aggregate.title || '第三阶段归档基线'), '',
    `生成于 ${aggregate.generatedAt}，样本 ${aggregate.matches?.length ?? result.scored.length} 局（可复现重放${result.fixed
      ? `；其中 ${result.included.length} 局证据完整` : ''}）。`,
    '', `${result.fixed ? 'S0 固定' : 'S0 标定'}：STAGE3_AMBUSH = ${label('ambush', result.ambushS0, result.ambushMedian)}，`
      + `STAGE3_BARRAGE = ${label('barrage', result.barrageS0, result.barrageMedian)}`,
    result.fixed
      ? `（沿用 ${aggregate.baseline?.ambush?.source ?? aggregate.baseline?.source ?? '已有基线'} 的 S0，本批不重新标定；分数仍为 provisional。）`
      : `（${aggregate.baseline?.kind === 'archive-corpus' ? '归档语料' : '本批'} S 的中位数；受控基线建立前，标定后的分数仍为 provisional。）`, '',
    (result.fixed
      ? '扣分为按固定 S0 计算的值；`S` 与记录数用于回看分布。'
      : '扣分为标定后（S0 = 上面两个中位数）的值；`S` 与记录数用于回看分布。')
      + (result.fixed
        ? '标「分叉」的局重放与录制不一致，数字只说明这份材料能算出什么。'
        : '标「排除」的局不进中位数，数字只说明这份材料能算出什么，不代表真实战况。'), '',
    '| 局 | 状态 | 时长 s | 结局 | A 记录 | A S | A 扣 | B 记录 | B S | B 扣 | G 扣 | 合计扣 | 标定总分 |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|'];
  for (const row of result.scored) {
    const status = row.status === 'included' ? '计入'
      : `${result.fixed ? '分叉' : '排除'}:${(row.reasons || []).join(',') || 'missing'}`;
    if (!row.families) {
      lines.push(`| ${row.match} | ${status} | — | — | — | — | — | — | — | — | — | — | — |`);
      continue;
    }
    const c = row.calibrated || {};
    const a = row.families.ambush, b = row.families.barrage, g = row.families.grinder;
    lines.push(`| ${row.match} | ${status} | ${row.durationSeconds} | ${row.endReason ?? ''} | ${a.records} | ${a.S} | ${c.ambush ?? a.deduction} |`
      + ` ${b.records} | ${b.S} | ${c.barrage ?? b.deduction} | ${c.grinder ?? g.deduction} |`
      + ` ${c.deduction ?? row.provisional.deduction} | ${c.total ?? row.provisional.total} |`);
  }
  const excluded = result.scored.filter(row => row.status !== 'included');
  if (excluded.length) {
    lines.push('', result.fixed ? '## 未计入（重放分叉/证据不全）' : '## 未进入标定', '');
    for (const row of excluded) lines.push(`- ${row.match}: ${row.reasons?.join(', ') || 'missing stage_3'}`);
  }
  lines.push('', '## 诊断', '');
  for (const name of ['ambush', 'barrage']) {
    const values = result.included.map(row => row.families[name]);
    const allN = values.flatMap(family => family.N);
    const types = {};
    for (const family of values) for (const [type, count] of Object.entries(family.types || {})) types[type] = (types[type] || 0) + count;
    lines.push(`- ${name}: 记录 ${values.reduce((n, f) => n + f.records, 0)}，N 中位数 ${median(allN)}，`
      + `N 分布 ${JSON.stringify(histogram(allN))}，致死占比 ${round(values.reduce((n, f) => n + f.lethalShare * f.records, 0) / Math.max(1, values.reduce((n, f) => n + f.records, 0)))}，`
      + `类型 ${JSON.stringify(types)}`);
  }
  const zones = result.included.map(row => row.zones);
  const funnel = result.included.map(row => row.grinderFunnel || {});
  lines.push(`- grinder: 区 ${zones.reduce((n, z) => n + z.created, 0)} 建 / ${zones.reduce((n, z) => n + z.dropped, 0)} 弃，`
    + `建造者死亡 ${funnel.reduce((n, f) => n + (f.total || 0), 0)}，计分 ${funnel.reduce((n, f) => n + (f.pastGrace || 0), 0)}`);
  return lines.join('\n') + '\n';
}

function collect(options) {
  const root = path.resolve(options.root || path.join(ROOT, 'evaluate/output/_archive'));
  const dirs = (options.match || []).map(dir => path.resolve(dir));
  const matches = dirs.length ? dirs : fs.readdirSync(root, {withFileTypes: true})
    // Every match directory is reported: one without a stage_3 report explains itself instead of
    // disappearing from the baseline table.
    .filter(entry => entry.isDirectory() && !entry.name.startsWith('_'))
    .map(entry => path.join(root, entry.name));
  return matches.map(inspect);
}

function main() {
  const argv = process.argv.slice(2);
  const options = {match: []};
  while (argv.length) {
    const flag = argv.shift();
    if (flag === '--root') options.root = argv.shift();
    else if (flag === '--match') options.match.push(argv.shift());
    else if (flag === '--out') options.out = argv.shift();
    else if (flag === '--min') options.minimum = Number(argv.shift());
    else if (flag === '--s0') { const raw = readJson(argv.shift()); options.s0 = raw.baseline ?? raw; }
    else if (flag === '--title') options.title = argv.shift();
    else if (flag === '--kind') options.kind = argv.shift();
    else if (flag === '--write') options.write = true;
    else throw Error(`Usage: node evaluate/tools/stage3_baseline.js [--root <dir>] [--match <dir>]... [--out <json>] [--min 3] [--s0 <json>] [--title <text>] [--write]`);
  }
  const rows = collect(options);
  const result = calibrate(rows, options);
  const now = new Date().toISOString();
  const aggregate = {
    schema: 1, version: VERSION, generatedAt: now,
    title: options.title || '第三阶段归档基线',
    parameters: {alpha: DEFAULTS.alpha, beta: DEFAULTS.beta, grinder: DEFAULTS.grinder},
    baseline: {
      kind: options.kind || (result.fixed ? 'fixed' : 'archive-corpus'), matches: result.included.length,
      source: result.fixed ? (options.s0.ambush?.source ?? options.s0.source ?? 'fixed') : (options.kind || 'archive-corpus'),
      ambush: {S0: round(result.ambushS0), median: result.ambushMedian, matches: result.included.length,
        fallback: !result.fixed && result.ambushMedian === null,
        S: result.included.map(row => row.families.ambush.S)},
      barrage: {S0: round(result.barrageS0), median: result.barrageMedian, matches: result.included.length,
        fallback: !result.fixed && result.barrageMedian === null,
        S: result.included.map(row => row.families.barrage.S)}
    },
    matches: result.scored.map(row => ({match: row.match, status: row.status, reasons: row.reasons,
      checksumWarnings: row.checksumWarnings, replay: row.replay, replaySha256: row.replaySha256,
      resim: row.resim, team: row.team, source: row.source,
      endReason: row.endReason, durationSeconds: row.durationSeconds, counts: row.counts,
      scoreable: row.scoreable, dropped: row.dropped, grinderFunnel: row.grinderFunnel, zones: row.zones,
      families: row.families, provisional: row.provisional, calibrated: row.calibrated ?? null})),
  };
  const out = path.resolve(options.out || path.join(path.resolve(options.root || path.join(ROOT, 'evaluate/output/_archive')),
    '_stage3-baseline.json'));
  fs.mkdirSync(path.dirname(out), {recursive: true});
  fs.writeFileSync(out, JSON.stringify(aggregate, null, 2) + '\n', 'utf8');
  fs.writeFileSync(out.replace(/\.json$/, '.md'), markdown(result, aggregate), 'utf8');
  if (options.write) {
    for (const row of result.scored) {
      if (!row.calibrated) continue;
      const stage3 = path.join(row.dir, 'stage_3');
      const text = report(row.score) + (row.score.calibration.excluded
        ? '\n本局未进入 S0 基线（重放分叉或证据不全），分数只说明材料能算出什么。\n' : '');
      fs.writeFileSync(path.join(stage3, 'score.calibrated.json'), JSON.stringify(row.score, null, 2) + '\n', 'utf8');
      fs.writeFileSync(path.join(stage3, 'score.calibrated.txt'), text, 'utf8');
    }
  }
  console.log(markdown(result, aggregate));
  return {out, aggregate};
}

if (require.main === module) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {VERSION, collect, inspect, calibrate, median, histogram, markdown};



