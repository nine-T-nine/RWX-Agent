#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const {readLog} = require('./evaluate_stage_1');
const {scoreEvidence} = require('./stage1-v2');

async function telemetryEvidence(file, cutMs = null) {
  const nativePreferred = [], nativeFallback = [];
  let endMs = 0, team = null;
  const input = readline.createInterface({input: fs.createReadStream(file, 'utf8'), crlfDelay: Infinity});
  for await (const line of input) {
    if (!line.startsWith('{')) continue;
    let row;
    try { row = JSON.parse(line); } catch { continue; }
    if (row.kind === 'meta' && Number.isInteger(row.team)) team = row.team;
    if (row.kind === 'end' && Number.isFinite(row.timeMs)) endMs = Math.max(endMs, row.timeMs);
    if (row.kind !== 'native' || row.phase !== 'after' || !Number.isFinite(row.timeMs)) continue;
    if (Number.isFinite(cutMs) && row.timeMs > cutMs) continue;
    if (team != null && Number.isInteger(row.team) && row.team !== team) continue;
    if (row.source !== 'engine') nativeFallback.push(row.timeMs / 1000);
    if (['agent', 'pending', 'unknown'].includes(row.source)) nativePreferred.push(row.timeMs / 1000);
  }
  if (Number.isFinite(cutMs)) endMs = cutMs;
  return {durationSeconds: endMs / 1000, nativeTimes: nativePreferred.length ? nativePreferred : nativeFallback,
    nativeScope: nativePreferred.length ? 'agent/pending/unknown' : 'non-engine-fallback', team};
}

function report(score) {
  const lines = [`第一阶段 v2（${score.version}）`, '='.repeat(52)];
  if (!score.eligible) lines.push(`不评分：${score.ineligibleReason}`);
  else {
    lines.push(`总分 ${score.score} / 100（原始 ${score.rawScore}）`);
    for (const [name, group] of Object.entries(score.groups))
      lines.push(`${name.padEnd(9)} 扣 ${String(group.deduction).padStart(7)} / ${group.weight}`);
    if (score.activity.applied) lines.push(`活跃度闸门：${score.activity.nativeCommandsPerMinute}/min，得分封顶 ${score.activity.scoreCap}`);
    lines.push('状态：provisional（阈值、lambda 与活跃度闸门仍待 >15min 参考池标定）');
  }
  return lines.join('\n') + '\n';
}

async function main(argv) {
  const args = argv.slice(2), target = args.shift();
  if (!target) throw Error('Usage: node evaluate/score_stage_1_v2.js <match dir> [--commands file] [--telemetry file] [--out dir]');
  const dir = path.resolve(target), options = {};
  while (args.length) { const flag = args.shift();
    if (flag === '--commands') options.commands = path.resolve(args.shift());
    else if (flag === '--telemetry') options.telemetry = path.resolve(args.shift());
    else if (flag === '--out') options.out = path.resolve(args.shift());
    else if (flag === '--cut-ms') options.cutMs = Number(args.shift());
    else throw Error(`Unknown option ${flag}`);
  }
  const prepared = path.join(dir, 'prepared'), sourceDir = fs.existsSync(prepared) ? prepared : dir;
  const commands = options.commands || fs.readdirSync(sourceDir)
    .filter(name => /^commands-.*\.txt$/i.test(name)).map(name => path.join(sourceDir, name))[0];
  const telemetry = options.telemetry || [path.join(prepared, 'telemetry.jsonl'), path.join(dir, 'resim', 'telemetry.jsonl')]
    .find(file => fs.existsSync(file));
  if (!commands || !telemetry) throw Error('Stage1-v2 requires a command log and telemetry');
  const evidence = await telemetryEvidence(telemetry, options.cutMs);
  const score = scoreEvidence(readLog(commands).commands, evidence.nativeTimes, evidence.durationSeconds);
  score.source = {commands, telemetry, nativeScope: evidence.nativeScope, team: evidence.team};
  const out = options.out || path.join(dir, 'stage_1_v2');
  fs.mkdirSync(out, {recursive: true});
  fs.writeFileSync(path.join(out, 'score.json'), JSON.stringify(score, null, 2) + '\n');
  fs.writeFileSync(path.join(out, 'score.txt'), report(score));
  console.log(report(score));
  return {out, score};
}

if (require.main === module) main(process.argv).catch(error => { console.error(error.message); process.exitCode = 1; });
module.exports = {telemetryEvidence, report, main};
