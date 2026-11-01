#!/usr/bin/env node
'use strict';
/**
 * Round-2 post-processing: turn one `stage_2/` directory into a scored, auditable summary.
 *
 * Usage:
 *   node evaluate/score_stage_2.js "evaluate/output/<对局名>/stage_2"
 *   node evaluate/score_stage_2.js "<dir>" --weights my-weights.json --out "<dir>"
 *
 * Reads  : events.jsonl, summary.json, economy.log (all optional except events.jsonl)
 * Writes : score.json      machine-readable score with a per-event deduction list
 *          score.txt       the same, as a table for humans
 *          timeline.csv    every event sorted by start time, with its family and deduction
 *          economy_seconds.csv  F material: one row per game second (funds + income field + delta)
 *
 * The score is preliminary and additive; see `events/score.js` for the model. F (资金空余) is
 * material only and never contributes points.
 */
const fs = require('node:fs');
const path = require('node:path');
const {scoreEvents, DEFAULTS, FAMILY} = require('./events/score');

function readEvents(file) {
  if (!fs.existsSync(file)) throw Error('events.jsonl not found: ' + file);
  return fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/)
    .filter(line => line.trim()).map((line, index) => {
      try { return JSON.parse(line); } catch (error) { throw Error(`${file}:${index + 1}: ${error.message}`); }
    });
}

function readJson(file) {
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')) : null;
}

/** F material. `income` in economy.log is credits per resourceIncomeRatePeriod (40 ticks), not per
 * second; `fundsDelta` is the measured change per game second and is the honest lower bound of the
 * real income on a second that also spent credits. */
function economySeconds(economyFile) {
  if (!fs.existsSync(economyFile)) return null;
  const rows = [];
  for (const line of fs.readFileSync(economyFile, 'utf8').replace(/^\uFEFF/, '').trim().split(/\r?\n/)) {
    for (const value of line.split(',')) {
      const match = value.match(/^(-?\d+(?:\.\d+)?)\(([+-]?\d+(?:\.\d+)?)\)$/);
      if (!match) continue;
      const funds = Number(match[1]), incomeField = Number(match[2]);
      const previous = rows.at(-1);
      rows.push({second: rows.length, funds, incomeField,
        fundsDelta: previous ? Number((funds - previous.funds).toFixed(3)) : null});
    }
  }
  return rows;
}

function economyCsv(rows) {
  const lines = ['second,funds,incomeField,fundsDelta'];
  for (const row of rows) {
    lines.push([row.second, row.funds, row.incomeField, row.fundsDelta ?? ''].join(','));
  }
  return lines.join('\n') + '\n';
}

function timelineCsv(events) {
  const scored = scoreEvents(events);
  const points = new Map();
  for (const family of scored.breakdown) for (const item of family.items) points.set(item.id, item);
  const lines = ['t_start,t_end,family,type,status,scoreable,points,reason,detail'];
  const quote = value => `"${String(value ?? '').replace(/"/g, '""')}"`;
  for (const event of [...events].sort((a, b) => a.t_start - b.t_start || a.id.localeCompare(b.id))) {
    const item = points.get(event.id);
    const detail = [event.unit && `${event.unit.type}#${event.unit.id}`,
      event.unitId != null && `#${event.unitId}`,
      event.location && `@(${event.location.x.toFixed(0)},${event.location.y.toFixed(0)})`,
      event.durationMs != null || event.gapMs != null
        ? `${((event.durationMs ?? event.gapMs) / 1000).toFixed(1)}s` : null,
      event.reason, event.agentState].filter(Boolean).join(' ');
    lines.push([event.t_start, event.t_end, FAMILY[event.type] || 'OTHER', event.type,
      event.status, event.scoreable === false ? 'no' : 'yes',
      item ? item.points : 0, item ? item.reason : '', quote(detail)].join(','));
  }
  return lines.join('\n') + '\n';
}

function reportText(score, summary) {
  const out = [];
  out.push('第二轮初步评分');
  out.push('='.repeat(60));
  out.push(`基准分 ${score.baseline}   扣分 ${score.deduction}   总分 ${score.total}`);
  if (summary) {
    out.push(`证据: complete=${summary.complete} economyComplete=${summary.economyComplete} `
      + `team=${summary.team} source=${summary.source}`);
  }
  out.push('');
  out.push('分类            原始    封顶    上限   扣分来源');
  for (const family of score.breakdown) {
    out.push(`${family.family.padEnd(14)} ${String(family.raw).padStart(6)} `
      + `${String(family.capped).padStart(6)} ${String(family.cap).padStart(6)}   `
      + Object.entries(family.counts).map(([k, v]) => `${k}=${v}`).join(' '));
  }
  if (Object.keys(score.excluded).length) {
    out.push('');
    out.push('未计分事件: ' + Object.entries(score.excluded).map(([k, v]) => `${k}=${v}`).join(' '));
  }
  const items = score.breakdown.flatMap(f => f.items).sort((a, b) => b.points - a.points).slice(0, 20);
  if (items.length) {
    out.push('');
    out.push('扣分明细（前 20 条）');
    for (const item of items) {
      out.push(`  ${item.id} ${item.type} t=${(item.t_start / 1000).toFixed(1)}s `
        + `-${item.points} ${item.reason}`);
    }
  }
  out.push('');
  out.push('说明: 分数是初步的加法模型，每个扣分点都能追溯到一条事件；每类有上限，避免单一失败');
  out.push('模式主导总分。F（资金空余）只出材料，不参与计分。权重见 events/score.js。');
  return out.join('\n') + '\n';
}

function main(argv) {
  const args = argv.slice(2);
  const target = args.shift();
  if (!target) {
    console.error('Usage: node evaluate/score_stage_2.js <stage_2 dir> [--weights file] [--out dir]');
    process.exitCode = 1;
    return;
  }
  const options = {};
  while (args.length) {
    const flag = args.shift(), value = args.shift();
    if (flag === '--weights') options.weights = readJson(value) || {};
    else if (flag === '--out') options.out = value;
    else throw Error(`Unknown option ${flag}`);
  }
  const dir = path.resolve(target);
  const outDir = path.resolve(options.out || dir);
  const events = readEvents(path.join(dir, 'events.jsonl'));
  const summary = readJson(path.join(dir, 'summary.json'));
  const score = scoreEvents(events, options.weights || {});
  score.source = {events: path.join(dir, 'events.jsonl'), summary: path.join(dir, 'summary.json')};
  fs.mkdirSync(outDir, {recursive: true});
  fs.writeFileSync(path.join(outDir, 'score.json'), JSON.stringify(score, null, 2) + '\n');
  fs.writeFileSync(path.join(outDir, 'score.txt'), reportText(score, summary));
  fs.writeFileSync(path.join(outDir, 'timeline.csv'), timelineCsv(events));
  const economy = economySeconds(path.join(dir, 'economy.log'));
  if (economy) fs.writeFileSync(path.join(outDir, 'economy_seconds.csv'), economyCsv(economy));
  console.log(reportText(score, summary));
  return {outDir, score};
}

if (require.main === module) {
  try { main(process.argv); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {main, readEvents, readJson, economySeconds, economyCsv, timelineCsv, reportText,
  DEFAULTS};
