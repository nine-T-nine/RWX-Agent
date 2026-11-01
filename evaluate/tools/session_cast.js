#!/usr/bin/env node
'use strict';
/**
 * 把一个 Pi 会话归档进 `evaluate/session-cast/`，并生成一份基本分析。
 *
 * 用法：
 *   node evaluate/tools/session_cast.js <session.jsonl> [--name 对局名] [--match 对局目录名] [--out 目录]
 *
 * 产出（默认 `evaluate/session-cast/<对局名>/`，整目录被 .gitignore 忽略）：
 *   session.jsonl  会话原文副本
 *   analysis.md    工具调用 / 被拒命令 / 思考量 / 单轮延迟 / 关键片段
 *
 * 会话文件是模型自己的推理记录，属于本地资料：不随仓库分发，只做个人复盘。
 */
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');

const ROOT = path.resolve(__dirname, '..', '..');
const DEFAULT_OUT = path.join(ROOT, 'evaluate', 'session-cast');

function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map(part => (typeof part === 'string' ? part : part.text || '')).join('\n');
}

const clean = value => String(value).replace(/\u0000/g, '');

/** WSL 在 NAT 模式下对 localhost 代理的告警：每条工具返回都会带一行，统计它是否还在。 */
const WSL_NOISE = /检测到 localhost 代理配置|localhost proxy configuration/i;

async function readSession(file) {
  const lines = readline.createInterface({input: fs.createReadStream(file, {encoding: 'utf8'}), crlfDelay: Infinity});
  const calls = new Map();
  const results = new Map();
  const thinking = [];
  const order = [];
  let model = null;
  let startedAt = null, lastAt = null;
  const turnGaps = [];

  for await (const line of lines) {
    if (!line.trim()) continue;
    const row = JSON.parse(line);
    if (row.type === 'session') { startedAt = Date.parse(row.timestamp); continue; }
    if (row.type === 'model_change') { model = row.modelId; continue; }
    if (row.type !== 'message') continue;
    const msg = row.message;
    const ts = row.timestamp ? Date.parse(row.timestamp) : null;
    if (msg.role === 'assistant' && ts) {
      if (lastAt) turnGaps.push(ts - lastAt);
      lastAt = ts;
    }
    if (msg.role === 'toolResult') {
      results.set(msg.toolCallId, {text: clean(textOf(msg.content)), toolName: msg.toolName || ''});
      continue;
    }
    if (!Array.isArray(msg.content)) continue;
    for (const block of msg.content) {
      if (!block || typeof block !== 'object') continue;
      if (block.type === 'thinking') thinking.push(clean(block.thinking || block.text || ''));
      else if (block.type === 'toolCall') {
        const args = block.arguments || {};
        calls.set(block.id, {name: block.name, args, ts});
        order.push({id: block.id, name: block.name, args, ts});
      }
    }
  }
  return {model, startedAt, turnGaps, thinking, calls, results, order};
}

// 只认真正的 CLI 子命令：`rwx-cli-tools` 是技能目录名、`rwx-alerts` 是回执尾部的告警标记，
// 它们不是被下发的命令，混进统计会让命令表失真。
const NOT_A_COMMAND = new Set(['rwx-cli', 'rwx2', 'rwx-cli-tools', 'rwx-alerts']);
const cliNames = raw => [...raw.matchAll(/\brwx-[a-z0-9]+(?:-[a-z0-9]+)*\b/g)]
  .map(m => m[0]).filter(n => !NOT_A_COMMAND.has(n));

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * p))];
}

function analyse(session) {
  const perCommand = new Map();
  const rejections = [];
  let wslNoisy = 0, outputChars = 0;

  for (const call of session.order) {
    const raw = clean(String(call.args.command || call.args.cmd || call.args.script || ''));
    const result = session.results.get(call.id);
    const text = result ? result.text : '';
    outputChars += text.length;
    if (WSL_NOISE.test(text)) wslNoisy++;
    const names = cliNames(raw);
    const rejected = /"error"\s*:\s*true/.test(text);
    for (const name of (names.length ? names : [call.name])) {
      const row = perCommand.get(name) || {count: 0, rejected: 0};
      row.count++;
      if (rejected) row.rejected++;
      perCommand.set(name, row);
    }
    if (rejected) {
      const replies = [...text.matchAll(/"error"\s*:\s*true[^}]*"reply"\s*:\s*"((?:[^"\\]|\\.)*)"/g)]
        .map(m => m[1].replace(/\\n/g, ' ').replace(/\\"/g, '"').trim());
      rejections.push({command: raw.replace(/\s+/g, ' ').trim(), replies});
    }
  }
  return {perCommand, rejections, wslNoisy, outputChars};
}

function render({session, stats, name, matchDir, sourceFile}) {
  const out = [];
  const gaps = session.turnGaps;
  const thinkingChars = session.thinking.reduce((a, t) => a + t.length, 0);
  out.push(`# 会话分析：${name}`, '');
  out.push(`- 来源：\`${sourceFile}\``);
  out.push(`- 模型：\`${session.model ?? 'unknown'}\``);
  out.push(`- 工具调用：**${session.order.length}** 次；其中返回被引擎拒绝：**${stats.rejections.length}** 次`);
  out.push(`- 工具输出：${(stats.outputChars / 1024).toFixed(0)} KB；含 WSL 代理告警的返回：${stats.wslNoisy} 条`);
  out.push(`- 思考：${session.thinking.length} 段 / ${(thinkingChars / 1024).toFixed(0)} KB`
    + `（平均 ${session.thinking.length ? Math.round(thinkingChars / session.thinking.length) : 0} 字符）`);
  out.push(`- 单轮间隔：p50 ${(percentile(gaps, 0.5) / 1000).toFixed(1)}s / `
    + `p90 ${(percentile(gaps, 0.9) / 1000).toFixed(1)}s / max ${(Math.max(0, ...gaps) / 1000).toFixed(1)}s`);
  if (matchDir) out.push(`- 对局评测：\`${path.relative(ROOT, matchDir).replace(/\\/g, '/')}\``);
  out.push('');

  out.push('## 命令使用', '', '| 命令 | 次数 | 被拒 |', '|---|---:|---:|');
  for (const [cmd, row] of [...stats.perCommand.entries()].sort((a, b) => b[1].count - a[1].count).slice(0, 20)) {
    out.push(`| \`${cmd}\` | ${row.count} | ${row.rejected} |`);
  }
  out.push('');

  out.push('## 被拒的命令', '');
  if (!stats.rejections.length) out.push('无。', '');
  for (const [i, rejection] of stats.rejections.entries()) {
    out.push(`### ${i + 1}`, '', '```', rejection.command.slice(0, 400), '```', '');
    for (const reply of rejection.replies.slice(0, 3)) out.push(`> ${reply.slice(0, 220)}`, '');
  }

  out.push('## 说明', '');
  out.push('- 本文件由 `evaluate/tools/session_cast.js` 生成，只做客观统计，不评价打法。');
  out.push('- `session.jsonl` 是模型自己的推理记录，本地留存，不随仓库分发。');
  return out.join('\n') + '\n';
}

async function main(argv) {
  const args = argv.slice(2);
  const file = args.shift();
  if (!file) {
    console.error('Usage: node evaluate/tools/session_cast.js <session.jsonl> [--name 名] [--match 目录名] [--out 目录]');
    process.exitCode = 1;
    return;
  }
  let name = path.parse(file).name.replace(/^\d{4}-\d{2}-\d{2}T[\d-]+Z_/, '');
  let matchDir = null, outRoot = DEFAULT_OUT;
  while (args.length) {
    const flag = args.shift(), value = args.shift();
    if (flag === '--name') name = value;
    else if (flag === '--out') outRoot = path.resolve(value);
    else if (flag === '--match') matchDir = path.join(ROOT, 'evaluate', 'output', value);
    else throw Error(`Unknown option ${flag}`);
  }
  const session = await readSession(file);
  const stats = analyse(session);
  const target = path.join(outRoot, name);
  fs.mkdirSync(target, {recursive: true});
  fs.copyFileSync(file, path.join(target, 'session.jsonl'));
  fs.writeFileSync(path.join(target, 'analysis.md'),
    render({session, stats, name, matchDir, sourceFile: file}));
  console.log(`archived ${name}: ${session.order.length} calls, ${stats.rejections.length} rejected -> ${target}`);
}

if (require.main === module) main(process.argv).catch(e => { console.error(e.message); process.exitCode = 1; });
module.exports = {readSession, analyse, cliNames};
