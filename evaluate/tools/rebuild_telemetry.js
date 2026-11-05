#!/usr/bin/env node
'use strict';
/**
 * Rebuild a live-like telemetry file from replay-reconstruction evidence.
 *
 * `evaluate/utils/evaluate_replay.py` plays the `.replay` with a headless build and gets the
 * engine-side half of the evidence (units, damage, deaths, idle pairs, native execution, economy,
 * regions, frames). The other half of a live capture comes from the CLI itself: one `command`
 * receipt per request, the `result` verdict, and the `commandId`/`source` on every `native` row.
 * That half cannot be recorded by the engine during playback, because the replay only contains
 * commands the engine already accepted.
 *
 * This tool joins the three inputs a replay-only match still has:
 *   - raw telemetry from the replay playback,
 *   - the original command receipt log (all requests, including rejected ones),
 *   - the parsed replay command JSONL.
 *
 * It emits a merged telemetry file that is ordered by the game clock and carries synthetic
 * `command`/`result` rows plus attributed `native` rows, so the normal stage-2 evaluator sees the
 * same shape it sees for a live match. Verdicts are written in two strengths: a request that names
 * only objects already gone at receipt is provably rejected (`STALE_OBJECT`, what the engine would
 * reply), while an order with no replay trace is recorded as an inferred rejection (`OTHER`,
 * `inferred:true`) because the engine did not apply it but the receipt log has no reply field.
 * Everything the evidence cannot decide stays `accepted:null` instead of being guessed.
 */
const fs = require('node:fs');
const path = require('node:path');
const { readHigh, readLow, matchAll } = require('../evaluate_stage_1.js');

const TOKEN = /(?:"[^"]*"|'[^']*'|\S+)/g;

function option(argv, name) {
  const index = argv.indexOf('--' + name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : null;
}
function fail(message) {
  console.error('rebuild_telemetry: ' + message);
  process.exitCode = 1;
}
function unquote(value) {
  return String(value ?? '').replace(/^["']|["']$/g, '');
}
function tokens(command) {
  return (String(command || '').match(TOKEN) || []).map(unquote);
}
function ids(value) {
  return String(value ?? '').split(',').map(x => x.trim()).filter(x => x !== '').map(Number).filter(Number.isInteger);
}
function coordinate(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

/** Per-object state at an arbitrary game time, built from the playback evidence. */
class UnitTimeline {
  constructor() {
    this.samples = new Map(); // id -> [{timeMs, side, alive}]
    this.deaths = new Map();  // id -> timeMs
  }
  observe(row) {
    const unit = row.kind === 'unit' ? row.unit : row.kind === 'death' ? row.unit : null;
    if (!unit || !Number.isInteger(unit.id)) return;
    if (!this.samples.has(unit.id)) this.samples.set(unit.id, []);
    this.samples.get(unit.id).push({ timeMs: row.timeMs, side: unit.side ?? null, alive: unit.alive !== false });
    if (row.kind === 'death') this.deaths.set(unit.id, row.timeMs);
  }
  /** {status, deathTimeMs} for one reference at `timeMs`; mirrors EngineEvidence.reference(). */
  reference(id, timeMs, controlled) {
    const death = this.deaths.get(id);
    const dead = death != null && death <= timeMs;
    const samples = this.samples.get(id) || [];
    let lo = 0, hi = samples.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (samples[mid].timeMs <= timeMs) lo = mid + 1; else hi = mid; }
    const latest = lo ? samples[lo - 1] : null;
    if (dead) return { status: 'dead', deathTimeMs: death };
    if (!latest) return { status: 'missing', deathTimeMs: death ?? null };
    if (controlled && latest.side === 'enemy') return { status: 'wrong_team', deathTimeMs: death ?? null };
    return { status: 'valid', deathTimeMs: death ?? null };
  }
}

function readTelemetry(file) {
  const rows = [];
  const timeline = new UnitTimeline();
  let meta = null;
  const lines = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index];
    if (!raw.trim()) continue;
    let row;
    try { row = JSON.parse(raw); } catch (error) { throw Error(`${file}:${index + 1}: ${error.message}`); }
    if (row.kind === 'meta') meta = row;
    if (row.kind === 'frames') {
      // A trimmed run is expanded before insertion: a new row in the middle would split the run.
      const count = row.toTick - row.tick + 1;
      for (let offset = 0; offset < count; offset++) {
        const frame = { kind: 'frame', timeMs: row.timeMs + offset * row.stepMs, tick: row.tick + offset, seq: null };
        rows.push(frame);
        timeline.observe(frame);
      }
      continue;
    }
    rows.push(row);
    timeline.observe(row);
  }
  if (!meta) throw Error('telemetry has no meta row');
  return { rows, meta, timeline };
}

function build(telemetry, log, matches, options) {
  const { rows, meta, timeline } = readTelemetry(telemetry);
  const byLine = new Map(matches.map(match => [match.highLine, match]));
  const ordinalToCommand = new Map();
  // Every low line claimed by a high request keeps that request's identity on the native row.
  for (const match of matches) {
    if (!['matched', 'partial', 'ambiguous'].includes(match.status)) continue;
    const high = log.commands.find(command => command.line === match.highLine);
    if (!high) continue;
    const id = 'B-' + (log.commands.indexOf(high) + 1);
    for (const line of match.lowLines) if (!ordinalToCommand.has(line)) ordinalToCommand.set(line, id);
  }

  const economyRows = rows.filter(row => row.kind === 'economy');
  const creditsAt = timeMs => {
    let lo = 0, hi = economyRows.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (economyRows[mid].timeMs <= timeMs) lo = mid + 1; else hi = mid; }
    return lo ? economyRows[lo - 1].funds ?? null : null;
  };

  const synthetic = [];
  log.commands.forEach((command, index) => {
    const id = 'B-' + (index + 1);
    const timeMs = Math.round(command.time * 1000);
    const args = tokens(command.command).slice(1);
    const values = {};
    for (let i = 0; i < args.length; i++) {
      if (!args[i].startsWith('--')) continue;
      const key = args[i].slice(2), next = args[i + 1];
      if (next != null && !next.startsWith('--')) { values[key] = next; i++; } else values[key] = true;
    }
    const controlled = [...new Set([...ids(values.units), ...ids(values.unit), ...ids(values.builder)])];
    const target = values.target == null ? null : Number(values.target);
    const refs = [];
    for (const unitId of controlled) {
      const ref = timeline.reference(unitId, timeMs, true);
      refs.push({ id: unitId, life: 0, role: 'controlled', status: ref.status, checkedMs: timeMs,
        atReceipt: true, eligibleKnown: ref.status !== 'missing', excluded: false,
        deathTimeMs: ref.deathTimeMs, deathSeq: null });
    }
    if (Number.isInteger(target)) {
      const ref = timeline.reference(target, timeMs, false);
      refs.push({ id: target, life: 0, role: 'target', status: ref.status, checkedMs: timeMs,
        atReceipt: true, eligibleKnown: ref.status !== 'missing', excluded: false,
        deathTimeMs: ref.deathTimeMs, deathSeq: null });
    }
    const allGone = refs.length > 0 && refs.every(ref => ['dead', 'missing', 'wrong_team'].includes(ref.status));
    const match = byLine.get(command.line);
    let accepted;
    if (allGone) accepted = false;
    else if (match) accepted = ['matched', 'partial', 'ambiguous'].includes(match.status) ? true : match.status === 'unmatched' ? false : null;
    else accepted = true; // queries and local settings never produce a replay command
    // A request with no replay trace did not reach the engine. The rejection reason is not in the
    // receipt log, so an inferred one is marked and classified as "OTHER" unless a stale reference
    // already explains it.
    const inferred = accepted === false && !allGone;
    const verdict = accepted === false
      ? (allGone ? { accepted: false, code: 'STALE_OBJECT', reply: 'error: skipped dead/missing unit' }
        : { accepted: false, code: 'OTHER', reply: 'error: command failed' })
      : accepted === true
        ? { accepted: true, code: 'ACCEPTED', reply: '' }
        : { accepted: null, code: null, reply: null };
    const credits = creditsAt(timeMs);
    synthetic.push({ kind: 'command', timeMs, tick: null, seq: null, id, source: 'agent',
      name: command.name, raw: command.command, args, units: ids(values.units).length ? ids(values.units) : ids(values.unit),
      builders: ids(values.builder), refs, x: coordinate(values.x), y: coordinate(values.y), targetId: Number.isInteger(target) ? target : null });
    synthetic.push({ kind: 'result', timeMs, tick: null, seq: null, commandId: id, accepted: verdict.accepted,
      code: verdict.code, inferred, creditsBefore: credits, creditsAfter: credits, reply: verdict.reply });
  });

  // Attribute the replay's native rows: our team's rows linked to a receipt name that request;
  // the AI team's rows are engine-originated. Engine-generated automation (keep/auto) has no
  // receipt and keeps the honest `replay_unknown` source.
  for (const row of rows) {
    if (row.kind !== 'native' || row.replayOrdinal == null) continue;
    if (row.team !== log.team) { if (row.source === 'replay_unknown') row.source = 'engine'; continue; }
    const id = ordinalToCommand.get(row.replayOrdinal);
    if (id) { row.source = 'agent'; row.commandId = id; }
  }

  const all = [...rows, ...synthetic];
  // Meta first, everything else by game clock; `end` stays last.
  const end = all.filter(row => row.kind === 'end');
  const endMs = end.length ? Math.max(...end.map(row => Number(row.timeMs) || 0)) : null;
  // Receipt lines can be newer than the replay's last frame: the agent keeps polling after the
  // match ended, or exits long after the defeat. Judging those requests needs engine state that no
  // longer exists, and preprocess trims the same tail away, so drop them instead of emitting a
  // non-monotonic clock that stage 2 rejects as corrupt.
  const late = endMs == null ? [] : all.filter(row => row.kind !== 'end' && row.kind !== 'meta'
    && Number(row.timeMs) > endMs);
  const dropped = new Set(late);
  const rest = all.filter(row => row.kind !== 'end' && row.kind !== 'meta' && !dropped.has(row))
    .sort((a, b) => a.timeMs - b.timeMs);
  const merged = [meta, ...rest, ...end];
  let seq = 0;
  for (const row of merged) row.seq = ++seq;
  if (options.startedAt) meta.startedAt = options.startedAt;
  meta.rebuilt = { tool: 'evaluate/tools/rebuild_telemetry.js', at: new Date().toISOString(),
    commands: synthetic.length / 2, source: 'replay+commands',
    droppedAfterEnd: late.filter(row => row.kind === 'command').length };
  return merged;
}

function rebuild({ telemetry, commands, replay, out, startedAt }) {
  const log = readHigh(commands);
  const low = readLow(replay, log.team);
  const matches = matchAll(log.high, low, { commands: log.commands });
  const merged = build(telemetry, log, matches, { startedAt });
  fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
  fs.writeFileSync(out, merged.map(row => JSON.stringify(row)).join('\n') + '\n', 'utf8');
  return { out: path.resolve(out), team: log.team, merged, commands: log.commands.length };
}

function main() {
  const argv = process.argv.slice(2);
  const telemetry = option(argv, 'telemetry');
  const commands = option(argv, 'commands');
  const replay = option(argv, 'replay');
  const out = option(argv, 'out');
  if (!telemetry || !commands || !replay || !out) return fail('usage: rebuild_telemetry.js --telemetry <t.jsonl> --commands <commands.txt> --replay <replay.jsonl> --out <merged.jsonl> [--started-at <iso>]');
  let result;
  try { result = rebuild({ telemetry, commands, replay, out, startedAt: option(argv, 'started-at') }); }
  catch (error) { return fail(error.message); }
  const counts = result.merged.reduce((acc, row) => (acc[row.kind] = (acc[row.kind] || 0) + 1, acc), {});
  console.log(JSON.stringify({ out: result.out, team: result.team, rows: result.merged.length,
    commands: result.commands, nativeAttributed: result.merged.filter(row => row.kind === 'native' && row.source === 'agent').length,
    counts }, null, 1));
}

if (require.main === module) main();
module.exports = { build, rebuild, UnitTimeline };
