#!/usr/bin/env node
'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {loadConfig, current} = require('../config');

const CONFIG = loadConfig();

const ROOT = path.resolve(__dirname, '..', '..');
const OUTPUT = path.join(ROOT, 'evaluate', 'output');
const WEB = path.join(ROOT, 'web', 'resource', 'results.json');
const summary = JSON.parse(fs.readFileSync(path.join(OUTPUT, '_v2-summary.json'), 'utf8'));
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const id = name => crypto.createHash('sha1').update(name).digest('hex').slice(0, 12);
const clock = seconds => `${Math.floor(seconds / 60)}:${String(Math.round(seconds) % 60).padStart(2, '0')}`;

const ARCHIVE = {
  'Ice-Island-(2p)-20261029--20-40_deepseek4.1vsHard_old': {
    date: '2026-10-29 20:40', agents: ['DeepSeek 4.1'],
    title: 'Ice Island (2p) 10-29 20:40 DeepSeek 4.1 vs Hard'},
  'Ice-Island-(2p)-20261030--01-39_dsh1017vsHard_old': {
    date: '2026-10-30 01:39', agents: ['DSH 1.0.17（底层模型未记录）'],
    title: 'Ice Island (2p) 10-30 01:39 DSH 1.0.17 vs Hard'},
  'Ice-Island-(2p)-20261101--18-51_Pi+claudeOpus5.5+dshvsHard_old': {
    date: '2026-11-01 18:51', agents: ['Pi', 'Claude Opus 5.5', 'DSH'],
    title: 'Ice Island (2p) 11-01 18:51 Pi + Claude Opus 5.5 + DSH vs Hard'},
  'Ice-Island-(2p)-20261101--19-16_Pi+deepseek4.1_old': {
    date: '2026-11-01 19:16', agents: ['Pi + DeepSeek 4.1'],
    title: 'Ice Island (2p) 11-01 19:16 Pi + DeepSeek 4.1'},
  'Ice-Island-(2p)-20261101--19-50_Pi=deepseek掺水_old': {
    date: '2026-11-01 19:50', agents: ['Pi + DeepSeek（掺水路由）'],
    title: 'Ice Island (2p) 11-01 19:50 Pi + DeepSeek（掺水路由）'},
  'Ice-Island-(2p)-20261101--21-31_Pi+Kimi3.1provsHard_延迟_old': {
    date: '2026-11-01 21:31', agents: ['Pi + Kimi K3.1 Pro'],
    title: 'Ice Island (2p) 11-01 21:31 Pi + Kimi K3.1 Pro（延迟）'},
  'Ice-Island-(2p)-20261101--21-51_Pi+Kimi3.1provsHard_延迟2_old': {
    date: '2026-11-01 21:51', agents: ['Pi + Kimi K3.1 Pro'],
    title: 'Ice Island (2p) 11-01 21:51 Pi + Kimi K3.1 Pro（延迟2）'},
};

function currentPairs(meta) {
  const tools = meta.tools || {};
  const count = Math.max(1, meta.sessions?.length || 1);
  return (meta.models?.length ? meta.models : [{provider: 'unknown', id: 'unknown', thinking: 'unknown'}]).map(model => ({
    count: meta.models?.length === 1 ? count : 1,
    agent: {provider: model.provider || 'unknown', model: model.id || 'unknown', reasoning: model.thinking || 'unknown'},
    tool: {name: tools.profile || tools.kind || 'unknown', version: tools.version || tools.mcpToolVersion || null,
      extra: tools.detail || null},
  }));
}

function archivePairs(name) {
  return ARCHIVE[name].agents.map(agent => ({count: 1,
    agent: {provider: 'unknown', model: agent, reasoning: 'unknown'},
    tool: {name: 'RWX CLI（历史原始工具）', version: null,
      extra: '历史材料未结构化记录精确工具版本、provider 与推理强度。'}}));
}

function opponent(meta) {
  if (!meta?.map?.teams) return 'AI - Hard';
  const teams = meta.map.teams.filter(row => !/=Player$/.test(row));
  return teams.length ? teams.join(' · ') : 'unknown';
}

const matches = summary.evaluated.map(row => {
  const dir = path.join(OUTPUT, row.relativePath), metaFile = path.join(dir, 'meta.json');
  const meta = fs.existsSync(metaFile) ? read(metaFile) : null, old = ARCHIVE[row.match];
  if (!meta && !old) throw Error(`Missing web metadata for ${row.match}`);
  const stage3 = read(path.join(dir, 'stage_3', 'score.json'));
  const deductions = Object.fromEntries(stage3.breakdown.map(item => [item.family, {
    deduction: item.deduction, cap: item.cap, records: item.items.length,
  }]));
  const result = row.result;
  const legacy = row.optionalLegacy || {};
  const totalV1 = Number.isFinite(legacy.stage1v1) && Number.isFinite(legacy.stage2)
    ? Number((legacy.stage1v1 * .4 + legacy.stage2 * .6).toFixed(3)) : null;
  return {
    id: id(row.match), archive: row.archive, title: meta?.display_name || old?.title || row.match,
    date: meta?.timing?.startedAt || meta?.date || old.date,
    map: meta?.map?.name || 'Ice Island (2p)', opponent: meta ? opponent(meta) : 'AI - Hard · x2 收入',
    duration: {scoreSeconds: row.scoreWindowSeconds, gameSeconds: meta?.timing?.gameSeconds ?? null},
    result: {...result, detail: meta?.result?.detail || `${clock(result.atSeconds)} ${result.label}`},
    pairs: meta ? currentPairs(meta) : archivePairs(row.match),
    scores: {totalV2: row.scores.totalV2, stage1V2: row.scores.stage1v2, stage3: row.scores.stage3,
      stage1V1: legacy.stage1v1 ?? null, stage2: legacy.stage2 ?? null, totalV1},
    stage3: {version: stage3.version, deductions},
    evidenceSource: row.evidenceSource,
    notes: meta?.notes || ['历史材料已用忠实 resim 还原；未记录的模型/工具字段保持 unknown。'],
  };
}).sort((a, b) => b.scores.totalV2 - a.scores.totalV2);

const totals = matches.map(match => match.scores.totalV2);
const best = matches[0];
const IGNORED_REASON = {
  replay_desync: '重放与录制分叉（校验不一致条数见原因后缀），材料只能算出一部分，不猜分',
  commands_or_economy_missing: '缺少命令日志或经济日志，无法建立评分窗口',
  stage3_evidence_unavailable: '没有第三阶段证据，且无法用回放补齐',
};
const ignored = summary.ignored.map(row => {
  const key = Object.keys(IGNORED_REASON).find(name => row.reason.startsWith(name)) || null;
  return {title: row.match, relativePath: row.relativePath, reason: row.reason,
    note: key ? IGNORED_REASON[key] : '材料不满足评估门槛'};
});

// v1 is a frozen snapshot: the page still shows it, but nothing is recomputed or tuned from it any more.
const V1 = {
  version: 'total-v1',
  formula: 'stage1-v1 × 0.4 + stage2 × 0.6',
  weights: {stage1: .4, stage2: .6},
  frozen: true, frozenAt: '2026-11-06',
  note: 'v1 已冻结：2026-11-06 之后只维护 v2。这里只保留旧材料能复算出的分数，缺任一阶段就留空；'
    + '不计均分、不进排名，也不再作为调参依据。',
  counts: {scored: matches.filter(match => match.scores.totalV1 != null).length,
    missing: matches.filter(match => match.scores.totalV1 == null).length},
  stage1: {label: '第一阶段 v1', caps: [15, 5, 5, 8, 18, 10, 8, 15, 8, 8, 6, 10],
    names: ['首个行动指令', '首个采矿建造', '首个建造者生产', 'A→B 周期去离群均值',
      'B→A 等待去离群均值', 'B→A 等待离群值', 'A→B 决策离群值', '相邻底层命令间隔',
      'A→A 离群占比', 'B→B 离群占比', '底层生产指令 / 分钟', 'A/B 比值']},
  stage2: {label: '第二阶段 v2', caps: current('stage2.v2.parameters.caps', CONFIG)},
};
const results = {
  schema: 2, generatedAt: new Date().toISOString(), archives: summary.archives,
  standard: {version: 'total-v2', formula: 'stage3 × k + stage1-v2 × (1-k)', k: .6,
    stage1: 'stage1-score-v2', stage3: 'stage3-score-v2', provisional: true,
    tuningPool: summary.tuningPool},
  v1: V1,
  overview: {matches: matches.length, wins: matches.filter(match => match.result.outcome === 'win').length,
    defeats: matches.filter(match => match.result.outcome === 'defeat').length,
    ignored: ignored.length,
    archives: Object.fromEntries(summary.archives.map(label =>
      [label, matches.filter(match => match.archive === label).length])),
    average: Number((totals.reduce((a, b) => a + b, 0) / totals.length).toFixed(3)),
    best: {id: best.id, title: best.title, totalV2: best.scores.totalV2}},
  matches, ignored,
  method: {
    sources: ['stage_1_v2/score.json', 'stage_3/score.json（含资金 v2）', 'score_v2.json',
      'meta.json / evaluation_v2.json', '忠实 resim 的 playback.json 校验', 'evaluate/config.json 参数快照'],
    limits: ['Stage1-v2 的阈值、lambda 与活跃度闸门仍为 provisional。',
      'Stage3 S0 仅来自 5 局 >15min 忠实对局，且全部是战败；后续应补充胜利长局。',
      '资金 v2 的 lambda、动态 spend floor 与向先验收缩公式在设计中未定，当前使用 config.json 的显式先验。',
      '4P 归档局重放会分叉、1 局回放未完成，均只记录材料、不进入本表；胜负不进入公式。'],
  },
};
fs.writeFileSync(WEB, JSON.stringify(results, null, 2) + '\n');
console.log(`Wrote ${WEB}: ${matches.length} matches (${summary.archives.map(label =>
  `${label} ${results.overview.archives[label]}`).join(', ')}), ${results.overview.wins} win, ` +
  `${results.overview.defeats} defeats, ${ignored.length} ignored`);
