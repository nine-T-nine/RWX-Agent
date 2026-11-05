#!/usr/bin/env node
'use strict';
/**
 * Render the per-record Stage-3 deduction detail for a set of `<match>/stage_3` reports.
 *
 * The aggregate report (`stage3_baseline.js`) answers "what did each match score"; this one answers
 * "which records produced that deduction". For every family it lists each closed buffer with its
 * loss/warning/value/time terms (`L`, `W`, `w`, `Tf`), the additive severity `s`, and its share of
 * the family deduction. Shares are proportional attribution of the saturated family value, not
 * independently addable points: `扣分 = P_max·(1−e^(−S/S0))` is non-linear.
 */
const fs = require('node:fs');
const path = require('node:path');

const VERSION = 'stage3-detail-v1';
const ROOT = path.resolve(__dirname, '../..');

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const readEvents = file => fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')
  .split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));

function num(value, digits = 3) {
  const number = Number(value);
  return Number.isFinite(number) ? String(Number(number.toFixed(digits))) : '—';
}
const cell = value => String(value ?? '').replace(/\|/g, '\\|');

function inspect(dir) {
  const stage3 = path.join(dir, 'stage_3');
  const calibrated = path.join(stage3, 'score.calibrated.json');
  const provisional = path.join(stage3, 'score.json');
  const file = fs.existsSync(calibrated) ? calibrated : provisional;
  if (!fs.existsSync(file)) return null;
  const score = readJson(file);
  const events = new Map(readEvents(path.join(stage3, 'events.jsonl')).map(event => [event.id, event]));
  const summary = readJson(path.join(stage3, 'summary.json'));
  const playbackFile = path.join(dir, 'resim', 'playback.json');
  const playback = fs.existsSync(playbackFile) ? readJson(playbackFile) : null;
  const reasons = [];
  if (summary.complete !== true) reasons.push('evidence_incomplete');
  if (playback && Number(playback.checksumWarnings ?? 0) > 0) reasons.push(`replay_desync:${playback.checksumWarnings}`);
  return {match: path.basename(dir), dir, file, calibrated: file === calibrated, score, events, summary,
    playback, reasons, included: reasons.length === 0};
}

function share(family, item) {
  const key = family.family === 'STAGE3_GRINDER' ? 'points' : 's';
  const total = (family.items || []).reduce((sum, entry) => sum + (Number(entry[key]) || 0), 0);
  return total > 0 ? Number(family.deduction) * (Number(item[key]) || 0) / total : 0;
}

function eventTime(events, id) {
  const event = events.get(id);
  return event && Number.isFinite(Number(event.t_start)) ? Number(event.t_start) / 1000 : null;
}

function excludedLine(family) {
  if (!family.excluded || !family.excluded.length) return '';
  const counts = {};
  for (const item of family.excluded) counts[item.reason || 'UNKNOWN'] = (counts[item.reason || 'UNKNOWN'] || 0) + 1;
  const detail = family.excluded.slice(0, 40).map(item => `${item.id}${item.reason ? `(${item.reason})` : ''}`).join('、');
  const more = family.excluded.length > 40 ? ` 等 ${family.excluded.length} 条` : '';
  return `排除 ${family.excluded.length} 条：${Object.entries(counts).map(([reason, count]) => `${reason} ${count}`).join('，')}`
    + `（${detail}${more}）`;
}

function familyHeader(family, s0) {
  return `| 项 | 上限 | 计分记录 | Σs | 时长缩放 | S | S0 | 扣分 |\n|---|---|---|---|---|---|---|---|\n`
    + `| ${cell(family.family)} | ${num(family.cap, 0)} | ${family.items.length} | ${num(family.raw)} | `
    + `${num(family.durationScale)} | ${num(family.S)} | ${s0 == null ? '—' : num(s0)} | ${num(family.deduction)} |`;
}

function ambushTable(row, family) {
  const lines = ['| # | 时间 s | 单位 | N | E | H | L | W | w | Tf | s | 份额 | 结果 |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|'];
  family.items.forEach((item, index) => {
    const time = eventTime(row.events, item.id);
    lines.push(`| ${index + 1} | ${time == null ? '—' : num(time, 1)} | ${cell(`${item.unit?.type ?? '?'}#${item.unit?.id ?? '?'}`)} `
      + `| ${num(item.N, 0)} | ${num(item.E, 1)} | ${num(item.H, 1)} | ${num(item.L)} | ${num(item.W)} `
      + `| ${num(item.w)} | ${num(item.Tf)} | ${num(item.s)} | ${num(share(family, item))} | ${item.died ? '死亡' : '存活'} |`);
  });
  return lines.join('\n');
}

function barrageTable(row, family) {
  const lines = ['| # | 时间 s | 建筑 | N | E | H | L | W | w | s | 份额 | 结果 |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|'];
  family.items.forEach((item, index) => {
    const time = eventTime(row.events, item.id);
    lines.push(`| ${index + 1} | ${time == null ? '—' : num(time, 1)} | ${cell(`${item.unit?.type ?? '?'}#${item.unit?.id ?? '?'}`)} `
      + `| ${num(item.N, 0)} | ${num(item.E, 1)} | ${num(item.H, 1)} | ${num(item.L)} | ${num(item.W)} `
      + `| ${num(item.w)} | ${num(item.s)} | ${num(share(family, item))} | ${item.destroyed ? '摧毁' : '存活'} |`);
  });
  return lines.join('\n');
}

function grinderTable(family) {
  if (!family.items.length) return '（本局没有计分的建造者死亡事件）';
  const lines = ['| # | 区 | 首死亡 s | 死亡 | 权重和 | age s | B | L | 分 | 份额 | 事件 id |',
    '|---|---|---|---|---|---|---|---|---|---|---|'];
  family.items.forEach((item, index) => {
    lines.push(`| ${index + 1} | ${item.zoneId} | ${num(Number(item.t_first) / 1000, 1)} | ${item.deathIds.length} `
      + `| ${num(item.weight, 1)} | ${num(item.age, 1)} | ${num(item.B)} | ${num(item.L)} | ${num(item.points)} `
      + `| ${num(share(family, item))} | ${cell(item.deathIds.join('、'))} |`);
  });
  return lines.join('\n');
}

function renderMatch(row) {
  const score = row.score;
  const calibration = score.calibration || {};
  const baseline = calibration.baseline || {};
  const lines = [`## ${row.match}`, '',
    `- 总分 **${num(score.total)} / 100**，扣分 **${num(score.deduction)}**（${score.version}${row.calibrated ? '，已按 S0 标定' : '，S0 占位'}）`,
    `- 时长 ${num(score.durationSeconds, 1)} s，队伍 ${row.summary.team}，`
      + `证据 ${row.playback ? 'resim' : 'live'}${row.playback ? `（checksum 不一致 ${row.playback.checksumWarnings ?? 0}）` : ''}`,
    `- 状态 ${row.included ? '计入基线' : `排除：${row.reasons.join(', ')}`}` +
      (calibration.s0Source ? `；S0 来源 ${calibration.s0Source}（组 ${baseline.matches ?? '?'}）` : '')];
  for (const family of score.breakdown) {
    const s0 = family.family === 'STAGE3_AMBUSH' ? score.parameters?.ambush?.S0
      : family.family === 'STAGE3_BARRAGE' ? score.parameters?.barrage?.S0 : null;
    lines.push('', `### ${family.family}`, '', familyHeader(family, s0), '');
    if (family.family === 'STAGE3_AMBUSH') lines.push(ambushTable(row, family));
    else if (family.family === 'STAGE3_BARRAGE') lines.push(barrageTable(row, family));
    else lines.push(grinderTable(family));
    lines.push('');
    const excluded = excludedLine(family);
    if (excluded) lines.push(excluded);
  }
  return lines.join('\n') + '\n';
}

function render(rows, options = {}) {
  const included = rows.filter(row => row.included);
  const excluded = rows.filter(row => !row.included);
  const ordered = [
    ...included.sort((a, b) => Number(b.score.total) - Number(a.score.total) || a.match.localeCompare(b.match)),
    ...excluded.sort((a, b) => a.match.localeCompare(b.match)),
  ];
  const sample = ordered.find(row => row.score.calibration?.baseline)?.score.calibration.baseline || {};
  const lines = ['# 第三阶段扣分明细（归档局）', '',
    `生成于 ${new Date().toISOString()}；评分口径 ${ordered[0]?.score.version ?? 'stage3-score-v1'}`
      + (sample.ambushS0 != null ? `，S0 取自 ${sample.matches ?? included.length} 局中位数（偷袭 ${num(sample.ambushS0)} / 炮击 ${num(sample.barrageS0)}）` : ''),
    '',
    '每条记录先算 `s = w·L·W·Tf`（绞肉机为 `P_i = SingleMax·L·B`），族内 `S = Σs / clip(G/G_ref)`，',
    '`扣分 = P_max·(1−e^(−S/S0))`；**「份额」= 族扣分 × 该条 s/Σs**，只表示这条记录占了多少，',
    '不是可以单独相加的分数。', '',
    '| 对局 | 状态 | 时长 s | 偷袭 | 炮击 | 绞肉机 | 合计扣 | 总分 |',
    '|---|---|---|---|---|---|---|---|'];
  for (const row of ordered) {
    const byFamily = Object.fromEntries(row.score.breakdown.map(family => [family.family, family.deduction]));
    lines.push(`| ${row.match} | ${row.included ? '计入' : `排除:${row.reasons.join(',')}`} | ${num(row.score.durationSeconds, 1)} `
      + `| ${num(byFamily.STAGE3_AMBUSH)} | ${num(byFamily.STAGE3_BARRAGE)} | ${num(byFamily.STAGE3_GRINDER)} `
      + `| ${num(row.score.deduction)} | ${num(row.score.total)} |`);
  }
  if (options.notes?.length) {
    lines.push('', '## 说明', '');
    for (const note of options.notes) lines.push(`- ${note}`);
  }
  for (const row of ordered) lines.push('', renderMatch(row));
  return lines.join('\n');
}

function collect(options) {
  const root = path.resolve(options.root || path.join(ROOT, 'evaluate/output/_archive'));
  const dirs = (options.match || []).map(dir => path.resolve(dir));
  const matches = dirs.length ? dirs : fs.readdirSync(root, {withFileTypes: true})
    .filter(entry => entry.isDirectory() && !entry.name.startsWith('_'))
    .map(entry => path.join(root, entry.name));
  return matches.map(inspect).filter(Boolean);
}

function main() {
  const argv = process.argv.slice(2);
  const options = {match: [], notes: []};
  while (argv.length) {
    const flag = argv.shift();
    if (flag === '--root') options.root = argv.shift();
    else if (flag === '--match') options.match.push(argv.shift());
    else if (flag === '--out') options.out = argv.shift();
    else if (flag === '--note') options.notes.push(argv.shift());
    else throw Error('Usage: node evaluate/tools/stage3_details.js [--root <dir>] [--match <dir>]... [--out <md>] [--note <text>]');
  }
  const rows = collect(options);
  if (!rows.length) throw Error('no stage_3 reports found');
  const text = render(rows, options);
  const out = path.resolve(options.out || path.join(path.resolve(options.root || path.join(ROOT, 'evaluate/output/_archive')),
    '_stage3-detail.md'));
  fs.mkdirSync(path.dirname(out), {recursive: true});
  fs.writeFileSync(out, text, 'utf8');
  console.log(text.slice(0, text.indexOf('\n## ')));
  console.log(`written: ${out}`);
  return {out, rows, text};
}

if (require.main === module) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {VERSION, render, renderMatch, inspect, collect, share, num};
