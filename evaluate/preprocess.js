#!/usr/bin/env node
'use strict';
/**
 * 材料预处理桥段：把一局的原始材料裁剪成"有效对局窗口"，再交给两轮评分。
 *
 *   node evaluate/preprocess.js "evaluate/output/<对局名>"
 *   node evaluate/preprocess.js "<对局目录>" --out <目录> [--min-seconds 120] [--json]
 *
 * 为什么需要它：对局末尾经常不是"打完就退"——Agent 投降后队友 AI 继续打、赢了却不退出、
 * 掉线或额度用尽导致进程直接结束、旧日志在人为干涉处被截断。这些尾巴如果原样进评分，
 * 会被算成 D/E 空闲、结尾静默和多余的 A/B 轮询。预处理统一把它们裁到最后一个有效时点。
 *
 * 产出（默认 `<对局目录>/prepared/`）：
 *   telemetry.jsonl / economy.log / commands.txt / replay.jsonl   —— 裁剪后的输入
 *   preprocess.json                                              —— 结尾类型、窗口、证据覆盖
 *
 * 只做裁剪和标注，不改任何计分口径；两轮评分仍跑在裁剪后的材料上。
 */
const fs = require('node:fs');
const path = require('node:path');

const VERSION = 'preprocess-v1';
const LINE = /^\[(\d+):([0-5]\d)(?::(\d{3}))?\]<([^<>]*)><([^<>]*)>\s*$/;
const SURRENDER = /^rwx-game-surrender\b/i;
const EXIT = /^(rwx-exit|rwx-battleroom-quit|rwx-disconnect)\b/i;
/** End-row reasons that mean "the match itself is over", not "the capture died". */
const MATCH_END = new Set(['match_result', 'replay_end']);

/** Every timestamp in this file is milliseconds; the command log prints mm:ss[:mmm]. */
const clockMs = (minutes, seconds, millis) =>
  Number(minutes) * 60000 + Number(seconds) * 1000 + Number(millis || 0);

function scanCommands(file) {
  const out = {file, lines: 0, lastMs: null, surrenderMs: null, exitMs: null, stop: null,
    firstMs: null, commands: 0};
  if (!file || !fs.existsSync(file)) return out;
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/);
  for (let i = 0; i < text.length; i++) {
    const raw = text[i].trim();
    if (!raw) continue;
    const m = LINE.exec(raw);
    if (!m) {
      // A line that is not a command is the "human interference" marker: everything after it was
      // typed by hand and must never be scored.
      if (out.commands) { out.stop = {line: i + 1, content: raw.slice(0, 200)}; break; }
      continue;
    }
    const ms = clockMs(m[1], m[2], m[3]);
    const command = m[5].replace(/\\t/g, ' ').trim();
    out.lines++;
    out.commands++;
    out.firstMs ??= ms;
    out.lastMs = ms;
    if (SURRENDER.test(command)) out.surrenderMs ??= ms;
    if (EXIT.test(command)) out.exitMs = ms;
  }
  return out;
}

/** Decide where the scoreable window ends. Pure, so it can be unit-tested. */
function planWindow({commands, end, lastFrameMs}) {
  const candidates = [];
  if (commands.surrenderMs != null) candidates.push({ms: commands.surrenderMs, kind: 'surrender',
    reason: 'agent_surrendered'});
  if (commands.exitMs != null) candidates.push({ms: commands.exitMs, kind: 'agent_exit',
    reason: 'agent_left_the_match'});
  if (end) {
    const kind = end.reason === 'process_exit' ? 'aborted'
      : end.reason === 'truncated_after_interference' ? 'truncated'
        : MATCH_END.has(end.reason) ? 'normal' : 'unknown';
    candidates.push({ms: Number(end.timeMs), kind, reason: end.reason});
  }
  if (commands.stop) candidates.push({ms: commands.lastMs, kind: 'truncated',
    reason: 'command_log_truncated'});
  let chosen = null;
  for (const candidate of candidates) {
    if (!Number.isFinite(candidate.ms)) continue;
    if (!chosen || candidate.ms < chosen.ms) chosen = candidate;
  }
  if (!chosen) {
    const ms = Math.min(Number.isFinite(lastFrameMs) ? lastFrameMs : Infinity,
      Number.isFinite(commands.lastMs) ? commands.lastMs : Infinity);
    chosen = {ms: Number.isFinite(ms) ? ms : null, kind: 'aborted', reason: 'no_end_evidence'};
  }
  // A dead Agent cannot act after its last command, so an abort never scores the shutdown tail.
  if (chosen.kind === 'aborted' && Number.isFinite(commands.lastMs) && commands.lastMs < chosen.ms)
    chosen = {...chosen, ms: commands.lastMs, reason: chosen.reason + '_after_last_command'};
  return chosen;
}

const json = line => JSON.parse(line);

async function scanTelemetry(file) {
  const readline = require('node:readline');
  const stat = {file, rows: 0, meta: null, end: null, lastFrameMs: null, lastMs: null, lastTick: null,
    lastSeq: null, idleRows: 0, damageRows: 0, hpLossRows: 0, hpFieldRows: 0, nativeSources: {}};
  if (!file || !fs.existsSync(file)) return stat;
  const stream = readline.createInterface({input: fs.createReadStream(file, 'utf8'), crlfDelay: Infinity});
  for await (const line of stream) {
    if (!line.startsWith('{"kind":"')) continue;
    const kind = /^\{"kind":"([a-z_]+)"/.exec(line)?.[1];
    if (!kind) continue;
    stat.rows++;
    stat.lastMs = Number(/"timeMs":(\d+)/.exec(line)?.[1] ?? stat.lastMs);
    stat.lastTick = Number(/"tick":(\d+)/.exec(line)?.[1] ?? stat.lastTick);
    stat.lastSeq = Number(/"seq":(\d+)/.exec(line)?.[1] ?? stat.lastSeq);
    if (kind === 'meta') { try { stat.meta = json(line); } catch { /* keep scanning */ } }
    else if (kind === 'end') { try { stat.end = json(line); } catch { /* keep scanning */ } }
    else if (kind === 'frame') stat.lastFrameMs = stat.lastMs;
    else if (kind === 'idle') stat.idleRows++;
    else if (kind === 'damage') {
      stat.damageRows++;
      if (/"hpLoss":\s*-?\d/.test(line)) stat.hpLossRows++;
      if (/"maxHp":\s*\d/.test(line)) stat.hpFieldRows++;
    } else if (kind === 'native') {
      const source = /"source":"([a-z_]+)"/.exec(line)?.[1] ?? 'none';
      stat.nativeSources[source] = (stat.nativeSources[source] || 0) + 1;
    }
  }
  return stat;
}

function trimTelemetry(input, output, cutMs, end) {
  const readline = require('node:readline');
  const fsync = fs.openSync(output, 'w');
  let lastTick = null, lastSeq = null, kept = 0;
  return new Promise((resolve, reject) => {
    const stream = readline.createInterface({input: fs.createReadStream(input, 'utf8'), crlfDelay: Infinity});
    stream.on('line', line => {
      if (!line.startsWith('{"kind":"')) return;
      const kind = /^\{"kind":"([a-z_]+)"/.exec(line)?.[1];
      if (kind === 'end') return;
      const timeMs = Number(/"timeMs":(\d+)/.exec(line)?.[1]);
      if (kind !== 'meta' && !(timeMs <= cutMs)) return;
      for (const key of ['seq', 'toSeq']) {
        const value = new RegExp(`"${key}":(\\d+)`).exec(line);
        if (value) lastSeq = Math.max(lastSeq ?? -1, Number(value[1]));
      }
      for (const key of ['tick', 'toTick']) {
        const value = new RegExp(`"${key}":(\\d+)`).exec(line);
        if (value) lastTick = Math.max(lastTick ?? -1, Number(value[1]));
      }
      fs.writeSync(fsync, line + '\n');
      kept++;
    });
    stream.on('error', reject);
    stream.on('close', () => {
      const row = {kind: 'end', timeMs: cutMs, tick: lastTick, seq: (lastSeq ?? 0) + 1,
        reason: 'preprocess_cut', cutKind: end.kind, sourceEndReason: end.reason ?? null};
      fs.writeSync(fsync, JSON.stringify(row) + '\n');
      fs.closeSync(fsync);
      resolve(kept);
    });
  });
}

function trimCommands(input, output, cutMs) {
  const text = fs.readFileSync(input, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/);
  const kept = [];
  let started = false;
  for (const raw of text) {
    const line = raw.trim();
    if (!line) continue;
    const m = LINE.exec(line);
    if (!m) { if (started) break; continue; } // pre-match lines are skipped; the marker ends the log
    started = true;
    const ms = clockMs(m[1], m[2], m[3]);
    if (ms > cutMs) continue;
    kept.push(line);
  }
  fs.writeFileSync(output, kept.join('\n') + (kept.length ? '\n' : ''), 'utf8');
  return kept.length;
}

function trimReplay(input, output, cutMs) {
  if (!input || !fs.existsSync(input)) return 0;
  const lines = fs.readFileSync(input, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/);
  const kept = [];
  for (const line of lines) {
    if (!line.trim()) continue;
    const timeMs = Number(/"game_time_ms":(\d+)/.exec(line)?.[1] ?? NaN);
    if (Number.isFinite(timeMs) && timeMs > cutMs) continue;
    kept.push(line);
  }
  fs.writeFileSync(output, kept.join('\n') + (kept.length ? '\n' : ''), 'utf8');
  return kept.length;
}

function trimEconomy(input, output, cutMs) {
  if (!input || !fs.existsSync(input)) return 0;
  const {EconomyTimeline} = require('./events/economy');
  const timeline = new EconomyTimeline(input);
  const count = Math.min(timeline.samples.length, Math.floor(cutMs / 1000) + 1);
  const samples = timeline.samples.slice(0, count);
  const lines = [];
  for (let i = 0; i < samples.length; i += 60)
    lines.push(samples.slice(i, i + 60).map(s => `${s.funds}(${s.income >= 0 ? '+' : ''}${s.income})`).join(','));
  fs.writeFileSync(output, lines.join('\n') + '\n', 'utf8');
  return count;
}

/** Find the four inputs of one match workspace without guessing names twice. */
function discover(dir) {
  const root = path.resolve(dir, '..');
  const name = path.basename(path.resolve(dir));
  const firstIn = (base, pattern) => {
    if (!fs.existsSync(base)) return null;
    for (const entry of fs.readdirSync(base, {withFileTypes: true})) {
      const file = path.join(base, entry.name);
      if (entry.isDirectory()) {
        const nested = firstIn(file, pattern);
        if (nested) return nested;
      } else if (pattern.test(entry.name)) return file;
    }
    return null;
  };
  const commands = fs.existsSync(path.join(root, `commands-${name}.txt`)) ? path.join(root, `commands-${name}.txt`)
    : firstIn(root, new RegExp(`^commands-${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}.*\\.txt$`));
  const replay = firstIn(path.join(dir, 'replay'), /\.jsonl$/);
  const telemetry = firstIn(path.join(dir, 'events-live'), /^telemetry\.jsonl$/)
    || firstIn(path.join(dir, 'engine'), /^telemetry\.jsonl$/);
  const economy = telemetry ? path.join(path.dirname(telemetry), 'economy.log') : null;
  return {commands, replay, telemetry, economy: economy && fs.existsSync(economy) ? economy : null};
}

async function preprocess(dir, options = {}) {
  const matchDir = path.resolve(dir);
  if (!fs.existsSync(matchDir)) throw Error(`对局目录不存在：${matchDir}`);
  const out = path.resolve(options.out || path.join(matchDir, 'prepared'));
  const inputs = discover(matchDir);
  if (!inputs.commands || !inputs.telemetry) throw Error('缺少 commands 日志或 telemetry，无法预处理');
  const commands = scanCommands(inputs.commands);
  const telemetry = await scanTelemetry(inputs.telemetry);
  const end = telemetry.end || (commands.stop ? {timeMs: commands.lastMs, reason: 'command_log_truncated'} : null);
  const window = planWindow({commands, end, lastFrameMs: telemetry.lastFrameMs});
  const cutMs = Math.round(window.ms ?? 0);
  // Everything the capture recorded after the last scoreable instant: shutdown tails, post-victory
  // polling, the teammate AI still fighting after a surrender.
  const observedEndMs = Math.max(Number(end?.timeMs ?? 0), Number(telemetry.lastFrameMs ?? 0),
    Number(commands.lastMs ?? 0));
  const trimmedMs = Math.max(0, Math.round(observedEndMs - cutMs));
  fs.mkdirSync(out, {recursive: true});
  // Keep the command-log file name: `utils/paths.js` derives the report directory from it, so
  // `prepared/commands-<match>.txt` lands in the same `<match>/stage_1` as the raw log would.
  const commandFile = path.join(out, `commands-${path.basename(matchDir)}.txt`);
  const replayFile = path.join(out, 'replay.jsonl');
  const telemetryFile = path.join(out, 'telemetry.jsonl');
  const economyFile = path.join(out, 'economy.log');
  const kept = {
    telemetry: await trimTelemetry(inputs.telemetry, telemetryFile, cutMs, window),
    economy: trimEconomy(inputs.economy, economyFile, cutMs),
    commands: trimCommands(inputs.commands, commandFile, cutMs),
    replay: trimReplay(inputs.replay, replayFile, cutMs),
  };
  const evidence = {
    idleRows: telemetry.idleRows,
    damageRows: telemetry.damageRows,
    hpLossRows: telemetry.hpLossRows,
    hpFieldRows: telemetry.hpFieldRows,
    nativeSources: telemetry.nativeSources,
    missing: [],
  };
  if (telemetry.hpLossRows === 0) evidence.missing.push('hp_loss');
  if (telemetry.idleRows === 0) evidence.missing.push('idle_pairs');
  if (!inputs.economy) evidence.missing.push('economy');
  evidence.complete = evidence.missing.length === 0;
  const minSeconds = Number(options.minSeconds ?? 120);
  const reasons = [];
  if (!(cutMs >= minSeconds * 1000)) reasons.push(`window_too_short:${(cutMs / 1000).toFixed(1)}s`);
  if (kept.commands === 0) reasons.push('no_commands_in_window');
  if (kept.telemetry === 0) reasons.push('no_telemetry_in_window');
  if (!inputs.economy) reasons.push('economy_missing');
  const result = {
    schema: 1, version: VERSION, match: path.basename(matchDir),
    inputs, prepared: out,
    ending: {kind: window.kind, reason: window.reason,
      endReason: telemetry.end?.reason ?? null, commandStop: commands.stop?.content ?? null,
      surrenderMs: commands.surrenderMs, exitMs: commands.exitMs, lastCommandMs: commands.lastMs},
    window: {startMs: 0, endMs: cutMs, trimmedMs,
      trailingAfterEndMs: Math.max(0, Math.round((commands.lastMs ?? 0) - (telemetry.end?.timeMs ?? 0)))},
    evidence,
    kept,
    files: {commands: commandFile, replay: replayFile, telemetry: telemetryFile, economy: economyFile,
      preprocess: path.join(out, 'preprocess.json')},
    scoreEligible: reasons.length === 0,
    ineligibleReason: reasons.length ? reasons.join('; ') : null,
  };
  fs.writeFileSync(path.join(out, 'preprocess.json'), JSON.stringify(result, null, 2) + '\n');
  fs.copyFileSync(path.join(out, 'preprocess.json'), path.join(matchDir, 'preprocess.json'));
  return result;
}

async function main(argv) {
  const args = argv.slice(2);
  const target = args.shift();
  if (!target) throw Error('用法: node evaluate/preprocess.js <对局目录> [--out <目录>] [--min-seconds N] [--json]');
  const options = {};
  let asJson = false;
  while (args.length) {
    const flag = args.shift();
    if (flag === '--out') options.out = args.shift();
    else if (flag === '--min-seconds') options.minSeconds = Number(args.shift());
    else if (flag === '--json') asJson = true;
    else throw Error(`未知参数：${flag}`);
  }
  const result = await preprocess(target, options);
  if (asJson) console.log(JSON.stringify(result, null, 2));
  else console.log(`${result.match}\n  结尾 ${result.ending.kind}（${result.ending.reason}）` +
    `  窗口 ${(result.window.endMs / 1000).toFixed(1)}s，截断 ${(result.window.trimmedMs / 1000).toFixed(1)}s` +
    `  证据缺失 ${result.evidence.missing.join(',') || '无'}  可评 ${result.scoreEligible}`);
  return result;
}

if (require.main === module) {
  main(process.argv).catch(error => { console.error(error.message); process.exitCode = 1; });
}
module.exports = {preprocess, planWindow, scanCommands, trimCommands, trimEconomy, VERSION};
