'use strict';
// Compares the income the CLI reports with the income the world actually delivered.
//
// The engine pays `getCreditIncomeRate()` per building in slices of
// `resourceIncomeUpdateInterval` (10) ticks, and the HUD/CLI rate is expressed per
// `resourceIncomeRatePeriod` (40) ticks. That only matches the credits-per-second the player
// actually receives if the two constants and the clock step are consistent, which is exactly what
// the Agent reasons about — so this script measures the delivered rate instead of trusting either.
const fs = require('node:fs');
const readline = require('node:readline');

async function scan(file, onRow) {
  const stream = fs.createReadStream(file, {encoding: 'utf8'});
  const lines = readline.createInterface({input: stream, crlfDelay: Infinity});
  for await (const raw of lines) {
    if (!raw.trim()) continue;
    const row = JSON.parse(raw);
    onRow(row);
  }
}

async function main(file) {
  const commands = [];
  const economy = [];
  const results = new Map();
  await scan(file, row => {
    if (row.kind === 'command') commands.push({timeMs: row.timeMs, name: row.name, id: row.id});
    else if (row.kind === 'economy') economy.push(row);
    else if (row.kind === 'result') results.set(row.commandId, row);
  });

  // A second is "quiet" when the Agent issued no order that could spend credits in it.
  const orderSeconds = new Set();
  for (const c of commands) if (/^rwx-game-order-/.test(c.name)) orderSeconds.add(Math.floor(c.timeMs / 1000));

  const pairs = [];
  for (let i = 1; i < economy.length; i++) {
    const prev = economy[i - 1], cur = economy[i];
    const second = Math.floor(cur.sampleMs / 1000);
    if (cur.sampleMs - prev.sampleMs !== 1000) continue;
    if (orderSeconds.has(second) || orderSeconds.has(second - 1)) continue;
    const delta = cur.funds - prev.funds;
    if (delta < 0) continue;
    pairs.push({second, income: cur.income, incomePerSecond: cur.incomePerSecond, delta});
  }
  const sum = key => pairs.reduce((a, p) => a + p[key], 0);
  const n = pairs.length;
  const meanDelta = n ? sum('delta') / n : 0;
  const meanField = n ? sum('income') / n : 0;
  const meanIps = n ? sum('incomePerSecond') / n : 0;
  const zero = pairs.filter(p => p.delta === 0).length;

  const reported = [];
  for (const c of commands) {
    if (c.name !== 'rwx-game-state') continue;
    const r = results.get(c.id);
    const m = /Credits:\s*([0-9.]+)\s*\(\+([0-9.]+)\/s\)/.exec(String(r && r.reply || ''));
    if (m) reported.push({timeMs: c.timeMs, credits: Number(m[1]), rate: Number(m[2])});
  }

  console.log(JSON.stringify({
    file: file.replace(/\\/g, '/').split('/').slice(-2)[0],
    quietSeconds: n,
    quietZeroDeltaSeconds: zero,
    meanFundsDelta: Number(meanDelta.toFixed(3)),
    meanIncomeField: Number(meanField.toFixed(3)),
    meanIncomePerSecond: Number(meanIps.toFixed(3)),
    fieldToDelta: meanField ? Number((meanDelta / meanField).toFixed(4)) : null,
    ipsToDelta: meanIps ? Number((meanDelta / meanIps).toFixed(4)) : null,
    reportedRates: reported.map(r => r.rate),
  }, null, 2));
}

main(process.argv[2]).catch(e => { console.error(e.message); process.exitCode = 1; });
