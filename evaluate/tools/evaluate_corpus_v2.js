#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {scanCommands, scanTelemetry, planWindow} = require('../preprocess');
const {main: scoreStage1} = require('../score_stage_1_v2');
const {evaluateStage3} = require('../evaluate_stage_3');
const {main: scoreStage3} = require('../score_stage_3');
const {main: scoreTotal} = require('../score_match_v2');
const {loadConfig, archiveLabelFor} = require('../config');

const OUTPUT = path.resolve(__dirname, '..', 'output');
const CONFIG = loadConfig();

function read(file) { return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); }
function first(dir, pattern) {
  if (!fs.existsSync(dir)) return null;
  return fs.readdirSync(dir, {withFileTypes: true}).filter(entry => entry.isFile() && pattern.test(entry.name))
    .map(entry => path.join(dir, entry.name)).sort()[0] || null;
}
function matchDirectories(root = OUTPUT) {
  const found = [];
  function walk(dir) {
    const entries = fs.readdirSync(dir, {withFileTypes: true});
    if (entries.some(entry => entry.isFile() && /^commands-.*\.txt$/i.test(entry.name)) ||
        fs.existsSync(path.join(dir, 'prepared', 'preprocess.json'))) { found.push(dir); return; }
    for (const entry of entries) if (entry.isDirectory() && !['events-live', 'replay', 'prepared', 'resim',
      'stage_1', 'stage_1_v2', 'stage_2', 'stage_3', 'micro'].includes(entry.name)) walk(path.join(dir, entry.name));
  }
  for (const entry of fs.readdirSync(root, {withFileTypes: true}))
    if (entry.isDirectory() && !['events-live', 'micro'].includes(entry.name)) walk(path.join(root, entry.name));
  return [...new Set(found)];
}
function playback(match) {
  const file = path.join(match, 'resim', 'playback.json');
  return fs.existsSync(file) ? read(file) : null;
}
function files(match) {
  const prepared = path.join(match, 'prepared');
  const pre = path.join(prepared, 'preprocess.json');
  const preparedTelemetry = path.join(prepared, 'telemetry.jsonl');
  const resimTelemetry = path.join(match, 'resim', 'telemetry.jsonl');
  const preparedStat = fs.existsSync(preparedTelemetry) ? null : false;
  return {pre: fs.existsSync(pre) ? pre : null,
    commands: first(prepared, /^commands-.*\.txt$/i) || first(match, /^commands-.*\.txt$/i),
    preparedTelemetry: fs.existsSync(preparedTelemetry) ? preparedTelemetry : null,
    resimTelemetry: fs.existsSync(resimTelemetry) ? resimTelemetry : null,
    economy: [path.join(prepared, 'economy.log'), path.join(match, 'resim', 'economy.log')].find(fs.existsSync) || null,
    preparedStat};
}
async function choose(match, material) {
  const live = material.preparedTelemetry ? await scanTelemetry(material.preparedTelemetry) : null;
  if (live?.stage3Meta) return {stage1Telemetry: material.preparedTelemetry,
    stage3Telemetry: material.preparedTelemetry, evidence: live, source: 'live'};
  const play = playback(match);
  if (!material.resimTelemetry || !play || play.checksumWarnings !== 0)
    throw Error(play?.checksumWarnings ? `replay_desync:${play.checksumWarnings}` : 'stage3_evidence_unavailable');
  return {stage1Telemetry: material.preparedTelemetry || material.resimTelemetry,
    stage3Telemetry: material.resimTelemetry,
    evidence: live, source: 'faithful-resim'};
}
function outcome(kind, meta) {
  if (kind === 'victory') return {outcome: 'win', label: '胜利'};
  if (['defeat', 'surrender'].includes(kind)) return {outcome: 'defeat', label: kind === 'surrender' ? '主动投降' : '战败'};
  if (meta?.result?.outcome) return meta.result;
  return {outcome: 'unknown', label: '结局未知'};
}

async function evaluateMatch(match) {
  const material = files(match);
  if (!material.commands || !material.economy) throw Error('commands_or_economy_missing');
  const selected = await choose(match, material);
  const commandStat = scanCommands(material.commands);
  const telemetryStat = await scanTelemetry(selected.stage1Telemetry);
  const window = planWindow({commands: commandStat, end: telemetryStat.end,
    lastFrameMs: telemetryStat.lastFrameMs, defeatMs: telemetryStat.defeatMs, victoryMs: telemetryStat.victoryMs});
  if (!Number.isFinite(window.ms)) throw Error('score_window_missing');
  await scoreStage1(['node', 'score_stage_1_v2.js', match, '--commands', material.commands,
    '--telemetry', selected.stage1Telemetry, '--cut-ms', String(window.ms)]);
  await evaluateStage3(selected.stage3Telemetry, {out: path.join(match, 'stage_3'), cutMs: window.ms});
  scoreStage3(['node', 'score_stage_3.js', path.join(match, 'stage_3'), '--economy', material.economy,
    '--cut-ms', String(window.ms)]);
  scoreTotal(['node', 'score_match_v2.js', match]);
  const metaFile = path.join(match, 'meta.json'), meta = fs.existsSync(metaFile) ? read(metaFile) : null;
  const relativePath = path.relative(OUTPUT, match).replace(/\\/g, '/');
  const result = {schema: 1, archive: archiveLabelFor(relativePath, CONFIG), match: path.basename(match),
    relativePath, evidenceSource: selected.source,
    scoreWindowSeconds: Number((window.ms / 1000).toFixed(3)), ending: window.kind,
    result: {...outcome(window.kind, meta), atSeconds: Number((window.ms / 1000).toFixed(3))},
    scores: {stage1v2: read(path.join(match, 'stage_1_v2', 'score.json')).score,
      stage3: read(path.join(match, 'stage_3', 'score.json')).total,
      totalV2: read(path.join(match, 'score_v2.json')).total},
    optionalLegacy: {
      stage1v1: fs.existsSync(path.join(match, 'stage_1', 'score.json')) ? read(path.join(match, 'stage_1', 'score.json')).score : null,
      stage2: fs.existsSync(path.join(match, 'stage_2', 'score.json')) ? read(path.join(match, 'stage_2', 'score.json')).total : null,
    }};
  fs.writeFileSync(path.join(match, 'evaluation_v2.json'), JSON.stringify(result, null, 2) + '\n');
  return result;
}

async function main(argv = process.argv) {
  const onlyIndex = argv.indexOf('--match'), only = onlyIndex >= 0 ? path.resolve(argv[onlyIndex + 1]) : null;
  const matches = only ? [only] : matchDirectories(), results = [], ignored = [];
  for (const match of matches) {
    try { const result = await evaluateMatch(match); results.push(result); console.log(`PASS ${result.match} ${result.scores.totalV2}`); }
    catch (error) { ignored.push({match: path.basename(match), relativePath: path.relative(OUTPUT, match).replace(/\\/g, '/'), reason: error.message});
      console.error(`IGNORE ${path.basename(match)}: ${error.message}`); }
  }
  // This round only tunes on the batch just retired (`old_v1.1`); the older `old_v1.0` material is
  // scored for the archive table but must not move any parameter.
  const tuningArchives = CONFIG.policy.tuningPool.archives || [];
  const tuningRows = results.filter(row =>
    row.scoreWindowSeconds > CONFIG.policy.tuningPool.minDurationExclusiveSeconds &&
    (!tuningArchives.length || tuningArchives.includes(row.archive)))
    .map(row => { const score = read(path.join(OUTPUT, row.relativePath, 'stage_3', 'score.json'));
      return {match: row.match, durationSeconds: row.scoreWindowSeconds, outcome: row.result.outcome,
        ambushS: score.breakdown.find(item => item.family === 'STAGE3_AMBUSH').S,
        barrageS: score.breakdown.find(item => item.family === 'STAGE3_BARRAGE').S}; });
  const median = (rows, key) => { const values = rows.map(row => row[key]).sort((a, b) => a - b), n = values.length;
    return n ? (n % 2 ? values[n >> 1] : (values[n / 2 - 1] + values[n / 2]) / 2) : null; };
  const summary = {schema: 1, version: 'corpus-v2', generatedAt: new Date().toISOString(),
    archives: [...new Set(results.map(row => row.archive))], evaluated: results, ignored,
    tuningPool: {policy: CONFIG.policy.tuningPool, matches: tuningRows,
      ambushS0Median: median(tuningRows, 'ambushS'), barrageS0Median: median(tuningRows, 'barrageS')}};
  fs.writeFileSync(path.join(OUTPUT, '_v2-summary.json'), JSON.stringify(summary, null, 2) + '\n');
  console.log(JSON.stringify({evaluated: results.length, ignored: ignored.length}, null, 2));
  return summary;
}

if (require.main === module) main().catch(error => { console.error(error.stack || error.message); process.exitCode = 1; });
module.exports = {matchDirectories, files, choose, evaluateMatch, main};
