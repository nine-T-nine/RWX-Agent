#!/usr/bin/env node
'use strict';
/** Score the four Stage-3 families from the central evaluation config. */
const fs = require('node:fs');
const path = require('node:path');
const {current, loadConfig} = require('./config');
const {fundsEvents} = require('./events/score');
const {economySeconds} = require('./score_stage_2');

const CONFIG = loadConfig();
const VERSION = CONFIG.stage3.version;
const configured = current('stage3.parameters', CONFIG);
const DEFAULTS = Object.freeze({
  alpha: configured.shared.alpha, beta: configured.shared.beta,
  ambush: configured.ambush, barrage: configured.barrage,
  grinder: configured.grinder, funds: configured.funds,
});
const round = (n, d = 3) => Number(n.toFixed(d));
const clip = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
const merge = (base, over = {}) => Object.fromEntries(Object.keys(base).map(key => [key,
  base[key] && typeof base[key] === 'object' && !Array.isArray(base[key])
    ? merge(base[key], over[key] || {}) : over[key] ?? base[key]]));

function loss(event, p, alpha, beta, withTime) {
  const E = event.D_hp + alpha * event.D_sh, H = event.HP_max + alpha * event.Shield_max;
  const L = event.died || event.destroyed ? 1 : beta * Math.min(1, H > 0 ? E / H : 0);
  const W = p.WMin + (1 - p.WMin) * Math.min(1, Math.log(Math.max(1, event.N)) / Math.log(p.NSat));
  const value = clip(Math.sqrt(Math.max(0, event.cost) / p.Cw), p.wLo, p.wHi);
  const Tf = withTime ? 1 + p.gamma * Math.min(1, Math.max(0, (event.T_ms / 1000 - p.T0) / p.TSpan)) : 1;
  return {E, H, L, W, w: value, Tf, s: value * L * W * Tf};
}
function saturated(S, p) { return p.PMax * (1 - Math.exp(-S / p.S0)); }
function expSeverity(duration, p) {
  if (!(duration > p.d0)) return 0;
  if (duration >= p.d1) return 1;
  const u = (duration - p.d0) / (p.d1 - p.d0);
  return Math.abs(p.lambda) < 1e-9 ? u : (Math.exp(p.lambda * u) - 1) / (Math.exp(p.lambda) - 1);
}

function scoreEvents(events, summary, overrides = {}, economy = null) {
  const p = merge(DEFAULTS, overrides), G = Math.max(0, Number(summary?.durationMs || 0)) / 1000;
  const family = (type, key, withTime) => {
    const parameter = p[key], items = [], excluded = [];
    for (const event of events.filter(e => e.type === type)) {
      if (event.scoreable === false) { excluded.push({id: event.id, reason: event.reason}); continue; }
      const parts = loss(event, parameter, p.alpha, p.beta, withTime);
      items.push({id: event.id, ...parts, N: event.N, unit: event.unit,
        died: event.died, destroyed: event.destroyed});
    }
    const raw = items.reduce((n, item) => n + item.s, 0);
    const scale = clip(G / parameter.GRef, parameter.rLo, parameter.rHi), S = raw / scale;
    const deduction = saturated(S, parameter);
    return {family: type, raw: round(raw), durationScale: round(scale), S: round(S), cap: parameter.PMax,
      deduction: round(deduction), items: items.map(item => ({...item, s: round(item.s), L: round(item.L), W: round(item.W),
        w: round(item.w), Tf: round(item.Tf), E: round(item.E), H: round(item.H)})), excluded,
      diagnostics: {records: items.length,
        lethalShare: items.length ? round(items.filter(i => i.died || i.destroyed).length / items.length) : 0,
        N: items.map(i => i.N), types: Object.fromEntries([...new Set(items.map(i => i.unit?.type ?? 'unknown'))]
          .map(type => [type, items.filter(i => (i.unit?.type ?? 'unknown') === type).length]))}};
  };
  const ambush = family('STAGE3_AMBUSH', 'ambush', true);
  const barrage = family('STAGE3_BARRAGE', 'barrage', false);
  const eligible = events.filter(e => e.type === 'STAGE3_GRINDER' && e.scoreable !== false)
    .sort((a, b) => a.zoneId - b.zoneId || a.t_start - b.t_start || a.id.localeCompare(b.id));
  const groups = [];
  for (const death of eligible) {
    let group = groups.at(-1);
    if (!group || group.zoneId !== death.zoneId || death.t_start - group.anchor > p.grinder.TMerge * 1000) {
      group = {zoneId: death.zoneId, anchor: death.t_start, deaths: []}; groups.push(group);
    }
    group.deaths.push(death);
  }
  const grinderItems = groups.map((group, index) => {
    const weight = group.deaths.reduce((n, e) => n + e.weight, 0);
    const age = Math.max(0, (group.anchor - Number(group.deaths[0].zoneBirthMs || 0)) / 1000 - p.grinder.delta0);
    const B = Math.min(1, weight / p.grinder.WFull);
    const L = p.grinder.LMin + (1 - p.grinder.LMin) * Math.min(1, age / p.grinder.TStale);
    return {id: `GE-${index + 1}`, zoneId: group.zoneId, t_first: group.anchor,
      deathIds: group.deaths.map(e => e.id), weight, age: round(age), B: round(B), L: round(L),
      points: round(p.grinder.SingleMax * L * B)};
  });
  const grinderRaw = grinderItems.reduce((n, item) => n + item.points, 0);
  const grinder = {family: 'STAGE3_GRINDER', raw: round(grinderRaw), cap: p.grinder.Cap,
    deduction: round(Math.min(p.grinder.Cap, grinderRaw)), items: grinderItems,
    excluded: events.filter(e => e.type === 'STAGE3_GRINDER' && e.scoreable === false)
      .map(e => ({id: e.id, reason: e.reason})),
    diagnostics: {builderDeaths: events.filter(e => e.type === 'STAGE3_GRINDER').length,
      scoreableDeaths: eligible.length, events: groups.length,
      types: Object.fromEntries([...new Set(eligible.map(e => e.unit?.type ?? 'unknown'))]
        .map(type => [type, eligible.filter(e => (e.unit?.type ?? 'unknown') === type).length]))}};

  const financial = economy ? fundsEvents(economy, p.funds) : [];
  const fundsItems = financial.map(event => {
    const severity = expSeverity(event.durationSeconds, p.funds);
    return {id: event.id, t_start: event.t_start, t_end: event.t_end, durationSeconds: event.durationSeconds,
      peakFunds: event.peakFunds, spends: event.spends, severity: round(severity),
      points: round(p.funds.eventMax * severity)};
  });
  const fundsRaw = fundsItems.reduce((sum, item) => sum + item.points, 0);
  const funds = {family: 'STAGE3_FUNDS', raw: round(fundsRaw), cap: p.funds.Cap,
    deduction: round(Math.min(p.funds.Cap, fundsRaw)), items: fundsItems, excluded: [],
    evidenceComplete: Boolean(economy), diagnostics: {intervals: financial.length,
      maximumDurationSeconds: financial.length ? Math.max(...financial.map(row => row.durationSeconds)) : 0}};
  if (!economy) funds.excluded.push({id: null, reason: 'ECONOMY_EVIDENCE_MISSING'});

  const breakdown = [ambush, barrage, grinder, funds], deduction = breakdown.reduce((n, f) => n + f.deduction, 0);
  return {schema: 2, version: VERSION, baseline: 100, parameters: p,
    calibration: {provisional: true, s0Source: 'duration-filtered-median', baseline: {source: 'old_v1.1 >15min faithful corpus',
      matches: 5, minDurationExclusiveSeconds: 900, outcomes: {defeat: 5},
      ambushS0: p.ambush.S0, barrageS0: p.barrage.S0},
      reasons: ['S0 uses only five >15min matches and all are defeats',
        'funds lambda/spend floor/shrinkage are provisional']},
    evidenceComplete: Boolean(economy) && summary?.complete !== false,
    durationSeconds: round(G), deduction: round(deduction), total: round(Math.max(0, 100 - deduction)),
    breakdown, financialEvents: financial};
}

function readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); }
function readEvents(file) { return fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/)
  .filter(Boolean).map(line => JSON.parse(line)); }
function report(score) {
  const out = [`第三阶段评分（${score.version}）`, '='.repeat(52),
    `基准分 ${score.baseline}  扣分 ${score.deduction}  总分 ${score.total}`];
  const baseline = score.calibration?.baseline || {};
  out.push(`参数源：evaluate/config.json（偷袭 S0 ${baseline.ambushS0} / 炮击 S0 ${baseline.barrageS0}；provisional）`, '');
  for (const family of score.breakdown)
    out.push(`${family.family.padEnd(18)} 扣 ${String(family.deduction).padStart(7)} / ${family.cap}`);
  return out.join('\n') + '\n';
}
function timeline(events, score) {
  const financial = score.financialEvents || [];
  const points = new Map(score.breakdown.flatMap(f => f.items.map(i => [i.id, i.points ?? i.s])));
  return ['t_start,t_end,type,id,scoreable,value,reason', ...[...events, ...financial].map(e =>
    [e.t_start, e.t_end, e.type, e.id, e.scoreable ?? true, points.get(e.id) ?? '', e.reason ?? ''].join(','))]
    .join('\n') + '\n';
}

function defaultEconomy(dir) {
  const match = path.dirname(dir);
  return [path.join(match, 'prepared', 'economy.log'), path.join(match, 'resim', 'economy.log'),
    path.join(dir, 'economy.log')].find(file => fs.existsSync(file)) || null;
}
function main(argv) {
  const args = argv.slice(2), target = args.shift();
  if (!target) throw Error('Usage: node evaluate/score_stage_3.js <stage_3 dir> [--weights file] [--economy file] [--out dir]');
  let weights = {}, out = null, economyFile = null, cutMs = null;
  while (args.length) { const flag = args.shift();
    if (flag === '--weights') weights = readJson(args.shift());
    else if (flag === '--economy') economyFile = path.resolve(args.shift());
    else if (flag === '--cut-ms') cutMs = Number(args.shift());
    else if (flag === '--out') out = args.shift();
    else throw Error(`Unknown option ${flag}`);
  }
  const dir = path.resolve(target), outDir = path.resolve(out || dir);
  economyFile ||= defaultEconomy(dir);
  const events = readEvents(path.join(dir, 'events.jsonl')), summary = readJson(path.join(dir, 'summary.json'));
  let economy = economyFile ? economySeconds(economyFile) : null;
  if (economy && Number.isFinite(cutMs)) economy = economy.slice(0, Math.floor(cutMs / 1000) + 1);
  const score = scoreEvents(events, summary, weights, economy);
  score.source = {events: path.join(dir, 'events.jsonl'), summary: path.join(dir, 'summary.json'), economy: economyFile};
  fs.mkdirSync(outDir, {recursive: true});
  fs.writeFileSync(path.join(outDir, 'score.json'), JSON.stringify(score, null, 2) + '\n');
  fs.writeFileSync(path.join(outDir, 'score.txt'), report(score));
  fs.writeFileSync(path.join(outDir, 'timeline.csv'), timeline(events, score));
  console.log(report(score)); return {outDir, score};
}
if (require.main === module) try { main(process.argv); } catch (error) { console.error(error.message); process.exitCode = 1; }
module.exports = {VERSION, DEFAULTS, scoreEvents, loss, expSeverity, main, report, timeline, defaultEconomy};
