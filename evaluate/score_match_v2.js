#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {current, loadConfig, archiveLabelFor} = require('./config');

const CONFIG = loadConfig();
const VERSION = CONFIG.total.v2.version;
const K = current('total.v2.parameters.k', CONFIG);
const STAGE1_VERSION = CONFIG.stage1.v2.version;
const STAGE3_VERSION = CONFIG.stage3.version;
const OUTPUT = path.join(__dirname, 'output');
const round = (value, digits = 3) => Number(value.toFixed(digits));

function read(file) {
  if (!fs.existsSync(file)) throw Error(`Missing score input: ${file}`);
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}
function combined(stage1, stage3, k = K) { return round(stage3 * k + stage1 * (1 - k)); }
function report(score) {
  return [`RWX v2 总分（${score.version}）`, '='.repeat(48),
    `总分 ${score.total} = Stage3 ${score.components.stage3.value} × ${score.k}` +
      ` + Stage1-v2 ${score.components.stage1v2.value} × ${round(1 - score.k)}`,
    `归档 ${score.archive}；状态 ${score.status}`,
    score.provisional ? `provisional：${score.provisionalReasons.join('；')}` : '参数已冻结', ''].join('\n');
}

function main(argv) {
  const args = argv.slice(2), target = args.shift();
  if (!target) throw Error('Usage: node evaluate/score_match_v2.js <match dir> [--out file]');
  const dir = path.resolve(target); let out = path.join(dir, 'score_v2.json');
  while (args.length) { const flag = args.shift();
    if (flag === '--out') out = path.resolve(args.shift()); else throw Error(`Unknown option ${flag}`);
  }
  const stage1 = read(path.join(dir, 'stage_1_v2', 'score.json'));
  const stage3 = read(path.join(dir, 'stage_3', 'score.json'));
  if (stage1.version !== STAGE1_VERSION) throw Error(`Stage1-v2 version ${stage1.version} != ${STAGE1_VERSION}`);
  if (stage3.version !== STAGE3_VERSION) throw Error(`Stage3 version ${stage3.version} != ${STAGE3_VERSION}`);
  if (!stage1.eligible || !Number.isFinite(stage1.score)) throw Error('Stage1-v2 is not eligible');
  if (!stage3.evidenceComplete || !Number.isFinite(stage3.total)) throw Error('Stage3 evidence is incomplete');
  const score = {schema: 1, version: VERSION,
    archive: archiveLabelFor(path.relative(OUTPUT, dir).replace(/\\/g, '/'), CONFIG), k: K,
    components: {
      stage1v2: {version: stage1.version, value: stage1.score, weight: round(1 - K), source: 'stage_1_v2/score.json'},
      stage3: {version: stage3.version, value: stage3.total, weight: K, source: 'stage_3/score.json'},
    },
    total: combined(stage1.score, stage3.total), status: 'provisional', provisional: true,
    provisionalReasons: [...new Set([...(stage1.provisionalReasons || []), ...(stage3.calibration?.reasons || [])])],
  };
  fs.mkdirSync(path.dirname(out), {recursive: true});
  fs.writeFileSync(out, JSON.stringify(score, null, 2) + '\n');
  fs.writeFileSync(path.join(path.dirname(out), 'score_v2.txt'), report(score));
  console.log(report(score)); return score;
}

if (require.main === module) try { main(process.argv); } catch (error) { console.error(error.message); process.exitCode = 1; }
module.exports = {VERSION, K, combined, report, main};
