#!/usr/bin/env node
'use strict';
/**
 * 综合得分：`stage1*0.4 + stage2*0.6`。
 *
 * 两轮本身仍是独立报告（实时性 / 事件质量），这里只做合成，不改任何一轮的口径。
 * 只接受当前公式版本的输入：旧版本（`stage1-v1`/`stage2-v2` 之前）默认拒绝，避免新旧混榜。
 *
 *   node evaluate/score_match.js "evaluate/output/<对局名>"
 *   node evaluate/score_match.js "<对局目录>" --out <目录> [--allow-stale] [--allow-ineligible]
 *
 * 读取：`stage_1/score.json`、`stage_2/score.json`，若存在 `preprocess.json` 则同时校验可评性。
 * 输出：`score.json` / `score.txt`（默认写回对局目录根）。
 */
const fs = require('node:fs');
const path = require('node:path');
const {VERSION: STAGE1_VERSION} = require('./stage1-metrics');
const {VERSION: STAGE2_VERSION} = require('./events/score');

const VERSION = 'total-v1';
const WEIGHTS = Object.freeze({stage1: 0.4, stage2: 0.6});
const round = (value, digits = 3) => Number(value.toFixed(digits));

function readJson(file) {
  if (!fs.existsSync(file)) return null;
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}

function loadStage(dir, name, expected, allowStale) {
  const file = path.join(dir, name, 'score.json');
  const score = readJson(file);
  if (!score) throw Error(`缺少 ${file}；先运行对应阶段的评分`);
  const version = score.version ?? null;
  const stale = version !== expected;
  if (stale && !allowStale) {
    throw Error(`${name}/score.json 版本为 ${version ?? '未知'}，当前版本为 ${expected}；` +
      '旧版本不参与综合评分（确需复算请加 --allow-stale）');
  }
  const value = name === 'stage_1' ? score.score : score.total;
  if (!Number.isFinite(value)) throw Error(`${file} 没有可用的总分量`);
  return {file, version, stale, value, deduction: score.deduction ?? null,
    status: score.systemHealth?.scoreStatus ?? score.status ?? null};
}

function combined(stage1, stage2) {
  return round(WEIGHTS.stage1 * stage1.value + WEIGHTS.stage2 * stage2.value, 3);
}

function text(result) {
  const lines = ['RWX 综合得分', '='.repeat(42)];
  lines.push(`总分 ${result.total ?? '—'} = 第一轮 ${result.components.stage1.value} × ${WEIGHTS.stage1}` +
    ` + 第二轮 ${result.components.stage2.value} × ${WEIGHTS.stage2}`);
  lines.push(`版本 ${result.components.stage1.version} / ${result.components.stage2.version}` +
    (result.versionsCurrent ? '（均为当前版本）' : '（含旧版本）'));
  if (result.preprocess) lines.push(`结尾 ${result.preprocess.ending}` +
    `，有效窗口 ${(result.preprocess.windowMs / 1000).toFixed(1)}s` +
    `，截断 ${(result.preprocess.trimmedMs / 1000).toFixed(1)}s`);
  if (!result.scoreEligible) lines.push(`不计分：${result.ineligibleReason}`);
  lines.push('两轮仍然分开呈现；综合分只用于横向比较，不与单个阶段的分数混榜。');
  return lines.join('\n') + '\n';
}

function main(argv) {
  const args = argv.slice(2);
  const target = args.shift();
  if (!target) throw Error('用法: node evaluate/score_match.js <对局目录> [--out <目录>] [--allow-stale] [--allow-ineligible]');
  const options = {allowStale: false, allowIneligible: false};
  let out = null;
  while (args.length) {
    const flag = args.shift();
    if (flag === '--out') out = path.resolve(args.shift());
    else if (flag === '--allow-stale') options.allowStale = true;
    else if (flag === '--allow-ineligible') options.allowIneligible = true;
    else throw Error(`未知参数：${flag}`);
  }
  const dir = path.resolve(target);
  const stage1 = loadStage(dir, 'stage_1', STAGE1_VERSION, options.allowStale);
  const stage2 = loadStage(dir, 'stage_2', STAGE2_VERSION, options.allowStale);
  const pre = readJson(path.join(dir, 'preprocess.json'));
  const versionsCurrent = !stage1.stale && !stage2.stale;
  const result = {
    schema: 1, version: VERSION, baseline: 100, weights: {...WEIGHTS},
    components: {
      stage1: {value: stage1.value, weight: WEIGHTS.stage1, version: stage1.version,
        deduction: stage1.deduction, status: stage1.status, stale: stage1.stale, source: stage1.file},
      stage2: {value: stage2.value, weight: WEIGHTS.stage2, version: stage2.version,
        deduction: stage2.deduction, status: stage2.status, stale: stage2.stale, source: stage2.file},
    },
    versionsCurrent,
    scoreEligible: true,
    ineligibleReason: null,
    preprocess: null,
    total: null,
  };
  if (pre) {
    const windowMs = Number(pre.window?.endMs ?? 0) - Number(pre.window?.startMs ?? 0);
    result.preprocess = {version: pre.version ?? null, ending: pre.ending?.kind ?? null,
      windowMs, trimmedMs: Number(pre.window?.trimmedMs ?? 0),
      evidence: pre.evidence ?? null, eligible: pre.scoreEligible !== false};
    if (pre.scoreEligible === false && !options.allowIneligible) {
      result.scoreEligible = false;
      result.ineligibleReason = pre.ineligibleReason ?? 'preprocess marked the match ineligible';
    }
  }
  result.total = result.scoreEligible ? combined(stage1, stage2) : null;
  const outDir = out || dir;
  fs.mkdirSync(outDir, {recursive: true});
  fs.writeFileSync(path.join(outDir, 'score.json'), JSON.stringify(result, null, 2) + '\n');
  fs.writeFileSync(path.join(outDir, 'score.txt'), text(result));
  console.log(text(result));
  return result;
}

if (require.main === module) {
  try { main(process.argv); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {main, combined, WEIGHTS, VERSION};
