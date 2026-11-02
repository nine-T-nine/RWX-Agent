#!/usr/bin/env node
'use strict';
/**
 * 批量重跑 `evaluate/output/` 下所有已存在的完整对局 Stage1 报告，并打印一行摘要。
 *
 * 用法：
 *   node evaluate/tools/stage1_audit.js                 # 就地重生成
 *   node evaluate/tools/stage1_audit.js --out <目录>    # 写到别处，不动现有报告
 *   node evaluate/tools/stage1_audit.js --resolve <目录># 输入缺失时额外搜索该目录（可重复）
 *
 * 为什么要它：`evaluate_stage_1.js` 一次只处理一局，而口径或评分规则改动后要确认"现有报告复算出来的
 * 数字有没有变"。这个脚本按 `execution_summary.json` 遍历，缺输入时按文件名在 output / 指定目录里找，
 * 单局失败不中断其余样本。
 *
 * 注意：`evaluate()` 会**重写**目标目录下的 report.html 与各产物；不带 `--out` 就是覆盖现有报告。
 *
 * 例外：早期扁平布局（`evaluate/output/commands-…_analysis/`，没有 `stage_1/`）的样本，产物目录由
 * commands 文件名推出，会落到一个**新的** `<对局名>/stage_1/`，不会覆盖原目录。这类样本需要人工比对后
 * 再决定是否替换旧产物。
 */
const fs = require('node:fs');
const path = require('node:path');
const {evaluate} = require('../evaluate_stage_1.js');

const ROOT = path.resolve(__dirname, '..', '..');
const OUTPUT = path.join(ROOT, 'evaluate', 'output');

function walk(dir, name, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    // `_old` 是"材料不全/旧版本"的归档约定：它们不参与复算，也不该被刷新。
    if (entry.name.endsWith('_old')) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file, name, out);
    else if (entry.name === name) out.push(file);
  }
  return out;
}

function parseArgs(argv) {
  const args = {out: OUTPUT, roots: [OUTPUT]};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--out') args.out = path.resolve(argv[++i]);
    else if (argv[i] === '--resolve') args.roots.push(path.resolve(argv[++i]));
    else throw new Error(`未知参数：${argv[i]}`);
  }
  return args;
}

function resolver(roots) {
  return file => {
    if (fs.existsSync(file)) return file;
    const base = path.basename(file);
    const found = roots.flatMap(root => walk(root, base));
    if (found.length !== 1) throw new Error(`${file}: 找到 ${found.length} 份，无法唯一确定输入`);
    return found[0];
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const resolve = resolver(args.roots);
  const summaries = walk(OUTPUT, 'execution_summary.json');
  let ok = 0;
  const failed = [];
  const skipped = [];
  for (const summaryFile of summaries) {
    const name = path.basename(path.dirname(summaryFile));
    const log = console.log;
    let file1, file2;
    try {
      const summary = JSON.parse(fs.readFileSync(summaryFile, 'utf8'));
      file1 = resolve(summary.file1);
      file2 = resolve(summary.file2);
    } catch (error) {
      skipped.push({sample: name, reason: error.message});
      console.error(`SKIP ${name}: ${error.message}`);
      continue;
    }
    try {
      console.log = () => {};
      const result = await evaluate(file1, file2, {outputRoot: args.out});
      console.log = log;
      const m = result.metrics;
      console.log(JSON.stringify({
        sample: name, duration: m.durationSeconds, A: m.counts.A, B: m.counts.B,
        score: result.score.score, status: result.score.systemHealth.scoreStatus,
        first: m.opening, cycle: m.cadence.decisionCycles.stats.cleanMean, wait: m.cadence.waitAfterAction.stats.cleanMean,
        gapShare: m.cadence.orderGaps.over30ShareOfMatch, tail: m.cadence.orderGaps.tailSeconds,
        noTrace: m.mapping.rawNoTrace, adjusted: m.mapping.stormAdjustedNoTrace,
      }));
      ok++;
    } catch (error) {
      console.log = log;
      failed.push({sample: name, error: error.message});
      console.error(`FAIL ${name}: ${error.message}`);
    }
  }
  console.log(`\n重生成 ${ok} 份，跳过 ${skipped.length} 份，失败 ${failed.length} 份`);
  if (skipped.length) console.log('跳过的样本缺原始输入；用 --resolve <目录> 指定解析后的 JSONL 所在目录。');
  if (failed.length) process.exitCode = 1;
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
