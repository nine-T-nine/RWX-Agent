#!/usr/bin/env node
'use strict';
/**
 * 把「每一局、每一个扣分项扣在哪」的证据写进 `web/resource/results.json`。
 *
 *   node evaluate/tools/results_details.js            # 写入
 *   node evaluate/tools/results_details.js --check    # 只比对，不写（有漂移时退出码 1）
 *
 * 页面（`web/results.js`）只读 results.json，不读 evaluate/output。评分产物里原本只有
 * 分值，这个脚本把 stage_1/score.json 的 `evidence`、stage_2/score.json 的逐事件明细
 * 压成一句中文说明，补进：
 *
 *   metrics.stage1Items[i].detail        第一阶段 12 项的「本局证据」
 *   breakdown.stage2.families[j].detail  第二阶段 6 类的「本局证据」
 *   breakdown.stage2.families[j].events  该类事件条数（阶段二表格的事件列）
 *
 * 分值以 score.json 为准；脚本会核对 results.json 里的分数有没有过期，对不上就报错不写。
 */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const RESULTS = path.join(ROOT, 'web/resource/results.json');
const OUTPUT = path.join(ROOT, 'evaluate/output');

/** results.json 的 `id` → 对局目录。新对局加进账本时在这里补一行，再跑本脚本。 */
const DIRS = {
  tool1: 'Ice-Island-(2p)-20261103--00-28__dsh4.1_tool1_vsHard_小坦克',
  tool2: 'Ice-Island-(2p)-20261103--07-22__dsh4.1_tool1_vsHard',
  tool3: 'Ice-Island-(2p)-20261103--09-29_dsh4.1_tool3_vsHard_重',
  'native-2358': 'Ice-Island-(2p)-20261102--23-58_dsh4.1highvsHard_坚守',
  'firebridge-opencode': 'Fire-Bridge-(2p)-20261104--00-12_deepseek4.1flash_vsHard_opencodev2',
  'dsh-push': 'Ice-Island-(2p)-20261104--11-01_dsh4.1flash_push_vsHard_tool_push_message',
  'team8p-pi-mail': '火山-(8p)-20261104--20-53_pi+deepseekv4.1flashx4vsVeryHard',
  'team8p-pi-nomail': '火山-(8p)-20261104--21-15_Pi+deeepseekv4.1flashx4vsVeryHard_nomail',
  'team8p-dsh-nomail': 'Volcano-(8p)-20261104--22-28_dsh+deepseekv4.1flashx4vsVeryHard_nomail',
};

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const num = (value, digits = 1) => (typeof value === 'number' && Number.isFinite(value)
  ? value.toFixed(digits) : '—');
const pct = (value, digits = 1) => num((value ?? 0) * 100, digits);
const clock = (ms) => {
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
};

// --------------------------------------------------------------- 第一阶段

/** 每个 item id 一句「本局证据」。evidence 的结构见 evaluate_stage_1.js 的 metrics 输出。 */
const STAGE1_DETAIL = {
  'A1-first-order': (e) => `首条行动指令 ${num(e.seconds, 2)}s`,
  'A1-first-mining': (e) => `首个采矿建筑 ${num(e.seconds, 2)}s（20s 内不扣）`,
  'A1-first-builder': (e) => `首个建造者出厂 ${num(e.seconds, 2)}s（30s 内不扣）`,
  'A2-decision-cycle': (e) => `${e.count} 个 A→B 周期，去离群均值 ${num(e.cleanMean, 2)}s（中位 ${num(e.median, 2)}s / p90 ${num(e.p90, 2)}s）`,
  'A2-wait-after-action': (e) => `${e.count} 次 B→A 等待，去离群均值 ${num(e.cleanMean, 2)}s（中位 ${num(e.median, 2)}s / p90 ${num(e.p90, 2)}s）`,
  'A2-wait-outliers': (e) => `${e.outliers}/${e.count} 次等待离群（${pct(e.outlierShare)}%，阈值 ${num(e.threshold, 2)}s）`,
  'A2-decision-outliers': (e) => `${e.outliers}/${e.count} 个决策周期离群（${pct(e.outlierShare)}%，阈值 ${num(e.threshold, 2)}s）`,
  'A4-native-cadence': (e) => {
    const quiet = e.count - e.counts.reduce((a, b) => a + b, 0);
    return `${e.count} 段相邻底层间隔：3–5s ${e.counts[0]}、5–12s ${e.counts[1]}、12–25s ${e.counts[2]}、>25s ${e.counts[3]}（≤3s 的 ${quiet} 段不扣分）`;
  },
  'A5-AA': (e) => `A→A ${e.count} 次，离群 ${e.outliers} 次（${pct(e.outlierShare)}%）`,
  'A5-BB': (e) => `B→B ${e.count} 次，离群 ${e.outliers} 次（${pct(e.outlierShare)}%）`,
  'A4-production-rate': (e) => `${num(e.perMinute, 2)} 条底层生产指令 / 分钟（8 条/分以上不扣）`,
  'A5-query-action-ratio': (e) => `查询 ${e.A} 条 / 行动 ${e.B} 条（比值 ${num(e.ratio, 2)}）`,
};
const STAGE1_FALLBACK = (e) => {
  const pairs = Object.entries(e ?? {}).filter(([, v]) => typeof v === 'number' || typeof v === 'string');
  return pairs.length ? pairs.map(([k, v]) => `${k}=${num(v, 2)}`).join('，') : '无额外证据';
};

// --------------------------------------------------------------- 第二阶段

function stage2Detail(family, score) {
  const items = family.items ?? [];
  switch (family.family) {
    case 'BUILDER_IDLE':
    case 'FACTORY_IDLE': {
      const scored = items.filter((item) => item.reason === 'idle_with_endpoint_work_available');
      const unscored = family.unscored ?? { count: 0, durationMs: 0 };
      const longest = scored.reduce((best, item) => Math.max(best, item.durationMs ?? 0), 0);
      if (!scored.length) {
        return `${unscored.count || items.length} 段都缺「有活可干」的证据，按规则不扣` +
          (unscored.durationMs ? `（合计 ${clock(unscored.durationMs)}）` : '');
      }
      return `${scored.length} 段可计分（最长 ${clock(longest)}）` + (unscored.count
        ? `；另有 ${unscored.count} 段证据不足、不扣分（合计 ${clock(unscored.durationMs)}）`
        : '');
    }
    case 'AMBUSH':
    case 'GRINDER': {
      const impact = items.reduce((sum, item) => sum + (item.impact ?? 0), 0);
      const worst = items.reduce((best, item) => ((item.points ?? 0) > (best?.points ?? -1) ? item : best), null);
      return `${items.length} 次，累计加权冲击 ${num(impact, 1)}` +
        (worst && worst.points > 0
          ? `；最大一次扣 ${num(worst.points, 2)} 分（t=${clock(worst.t_start)}，响应系数 ${num(worst.response, 2)}）`
          : '；单次都没超过免扣区间');
    }
    case 'STALE': {
      const points = items.map((item) => item.points ?? 0);
      const worst = items[points.indexOf(Math.max(...points))];
      const ageMissing = items.filter((item) => item.reason === 'partial_stale_age_evidence').length;
      return `${items.length} 段过期指令，最大一段扣 ${num(Math.max(...points, 0), 2)} 分` +
        (worst ? `（t=${clock(worst.t_start)}）` : '') +
        (ageMissing ? `；${ageMissing} 段缺「指令过期时长」证据，只按部分档计` : '');
    }
    case 'FUNDS': {
      const events = score.financialEvents ?? [];
      const longest = events.reduce((best, item) => Math.max(best, item.durationSeconds ?? 0), 0);
      const peak = events.reduce((best, item) => Math.max(best, item.peakFunds ?? 0), 0);
      const hit = items.filter((item) => (item.points ?? 0) > 0).length;
      return `${items.length} 段资金闲置，其中 ${hit} 段实际扣分；最长 ${longest}s，峰值 ${peak} 资金`;
    }
    default:
      return `${items.length} 条事件`;
  }
}

// ------------------------------------------------------------------- 主流程

/** 第二阶段 6 类事件的展示顺序与上限；某一局没有该类事件时补 0，页面才能逐项对齐。 */
const FAMILIES = [
  ['AMBUSH', 35], ['GRINDER', 20], ['STALE', 15],
  ['BUILDER_IDLE', 20], ['FACTORY_IDLE', 15], ['FUNDS', 20],
];

function detailsFor(match) {
  const base = path.join(OUTPUT, match);
  const stage1 = readJson(path.join(base, 'stage_1/score.json'));
  const stage2 = readJson(path.join(base, 'stage_2/score.json'));
  const byFamily = new Map(stage2.breakdown.map((family) => [family.family, family]));
  return {
    stage1: stage1.breakdown.map((item) => ({
      id: item.id,
      label: item.label,
      points: item.points,
      cap: item.cap,
      detail: (STAGE1_DETAIL[item.id] ?? STAGE1_FALLBACK)(item.evidence ?? {}),
    })),
    stage2: FAMILIES.map(([name, cap]) => {
      const family = byFamily.get(name);
      if (!family) {
        return {family: name, raw: 0, capped: 0, cap, events: 0, detail: '本局未出现该类事件'};
      }
      return {
        family: family.family,
        raw: family.raw,
        capped: family.capped,
        cap: family.cap,
        events: (family.items ?? []).length,
        detail: stage2Detail(family, stage2),
      };
    }),
    total: { stage1: stage1.score, stage2: stage2.total },
  };
}

function main(argv) {
  const check = argv.includes('--check');
  const ledger = readJson(RESULTS);
  const problems = [];
  let changed = 0;
  for (const entry of ledger.current) {
    const dir = entry.match ?? DIRS[entry.id];
    if (!dir) { problems.push(`${entry.id}: 没有对局目录映射`); continue; }
    const base = path.join(OUTPUT, dir);
    if (!fs.existsSync(path.join(base, 'stage_1/score.json'))) {
      problems.push(`${entry.id}: 缺 ${path.relative(ROOT, base)}/stage_1/score.json`);
      continue;
    }
    const details = detailsFor(dir);
    // 分数漂移说明 results.json 还停在旧的一次评分上，先重跑评分再来。
    for (const [stage, value] of [['stage1', entry.scores.stage1], ['stage2', entry.scores.stage2]]) {
      if (Math.abs(value - details.total[stage]) > 0.01) {
        problems.push(`${entry.id}: ${stage} 账本 ${value} ≠ 评分 ${details.total[stage]}`);
      }
    }
    const stage1Items = entry.metrics.stage1Items;
    for (const item of details.stage1) {
      const row = stage1Items.find((x) => x.id === item.id);
      if (!row) { problems.push(`${entry.id}: 账本缺第一阶段 ${item.id}`); continue; }
      if (Math.abs(row.points - item.points) > 0.001) problems.push(`${entry.id}: ${item.id} 分值不一致`);
    }
    // 以评分产物为准重建两个数组：显示名、上限、事件数与证据都跟着评分走，不手抄。
    const beforeStage1 = JSON.stringify(stage1Items);
    entry.metrics.stage1Items = details.stage1.map(({id, label, points, cap, detail}) => ({id, label, points, cap, detail}));
    const beforeStage2 = JSON.stringify(entry.breakdown.stage2.families);
    entry.breakdown.stage2.families = details.stage2;
    if (beforeStage1 !== JSON.stringify(entry.metrics.stage1Items)) changed++;
    if (beforeStage2 !== JSON.stringify(entry.breakdown.stage2.families)) changed++;
    entry.match = dir;
  }

  if (problems.length) {
    for (const problem of problems) console.error(`! ${problem}`);
    console.error(`\n${problems.length} 处对不上，未写入。先确认评分是否重跑过。`);
    return 1;
  }
  if (check) {
    console.log(changed ? `有 ${changed} 处与评分不一致` : '逐项扣分证据与评分一致');
    return changed ? 1 : 0;
  }
  if (!changed) { console.log('没有需要更新的扣分证据'); return 0; }
  fs.writeFileSync(RESULTS, `${JSON.stringify(ledger, null, 2)}\n`, 'utf8');
  console.log(`写入 ${ledger.current.length} 局的逐项扣分证据（${changed} 处更新）`);
  return 0;
}

if (require.main === module) process.exitCode = main(process.argv.slice(2));

module.exports = { STAGE1_DETAIL, detailsFor, stage2Detail };
