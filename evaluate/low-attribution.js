'use strict';

// Who submitted each native command?
//
// The Replay only says "a command was submitted at tick T for units U". It cannot tell an
// Agent order from a keep-set replenish or from the auto-set micro module. telemetry.jsonl
// can: every native command submission writes a `native` row whose `source` is `agent`
// (CLI order), `pending` (deferred order that finally landed), `keep` (production rule),
// `auto` (auto-set micro, tagged by AiAutomate) or `engine` (anything else that did not go
// through the CLI service: AI, UI, older builds without the auto tag).
//
// The two streams are the same sequence of submissions, one engine tick apart: the Replay
// timestamp is the tick the command is applied, the telemetry timestamp is the tick the
// submitter ran. Joining on (unit set, ±1 tick) resolves >99.9% of rows.

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');

/** Sources that came from the Agent itself; everything else is automation or the engine. */
const AGENT_SOURCES = new Set(['agent', 'pending']);
const TICK_SECONDS = 0.02;

function walk(dir, name, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file, name, out);
    else if (entry.name === name) out.push(file);
  }
  return out;
}

/**
 * Finds the telemetry of one match. `matchDir` is the match directory, never the output root:
 * a legacy Replay sits directly in `evaluate/output` next to every other match.
 */
function findTelemetry(matchDir) {
  if (!matchDir) return null;
  const found = walk(matchDir, 'telemetry.jsonl').filter(file => /[\\/](events-live|engine)[\\/]/.test(file));
  return found.length ? found.sort((a, b) => a.length - b.length)[0] : null;
}

async function readNativeRows(file, team) {
  const rows = [];
  const rl = readline.createInterface({input: fs.createReadStream(file, {encoding: 'utf8'}), crlfDelay: Infinity});
  for await (const line of rl) {
    if (!line.trim()) continue;
    let row;
    try { row = JSON.parse(line); } catch { continue; }
    if (row.kind !== 'native' || row.phase !== 'before') continue;
    if (team != null && row.team !== team) continue;
    rows.push({timeMs: row.timeMs, units: row.units || [], source: row.source || 'unknown', operation: row.operation});
  }
  return rows;
}

const keyOf = units => [...units].sort((a, b) => a - b).join(',');

/**
 * Attributes every low-level row to a submitter.
 *
 * @returns {{sources: Map<number,string>, sourceOf: (row:object)=>string, counts: object, telemetry: boolean}}
 */
async function attributeLow({low, team, matchDir, commands = []}) {
  const sources = new Map();
  const counts = {};
  const bump = source => { counts[source] = (counts[source] || 0) + 1; };
  const telemetryFile = findTelemetry(matchDir);
  const rows = telemetryFile ? await readNativeRows(telemetryFile, team) : [];

  if (rows.length) {
    const index = new Map();
    for (const row of rows) {
      const key = keyOf(row.units);
      if (!index.has(key)) index.set(key, []);
      index.get(key).push({...row, used: false});
    }
    for (const row of [...low].sort((a, b) => a.time_s - b.time_s || a.line - b.line)) {
      const candidates = (index.get(keyOf(row.units)) || [])
        .filter(n => !n.used && Math.abs(n.timeMs / 1000 - row.time_s) <= TICK_SECONDS);
      if (!candidates.length) { sources.set(row.line, 'unknown'); bump('unknown'); continue; }
      candidates.sort((a, b) => Math.abs(a.timeMs / 1000 - row.time_s) - Math.abs(b.timeMs / 1000 - row.time_s));
      candidates[0].used = true;
      // A telemetry written while reading a Replay has no live submitter; it is not evidence.
      const source = candidates[0].source === 'replay_unknown' ? 'unknown' : candidates[0].source;
      sources.set(row.line, source);
      bump(source);
    }
    // A telemetry written while *reading* a Replay has no live submitter: nearly every row says
    // replay_unknown. That is not evidence, so fall through to the inference path.
    const attributed = Object.entries(counts).filter(([source]) => source !== 'unknown')
      .reduce((sum, [, count]) => sum + count, 0);
    if (attributed > low.length / 2) {
      return {sources, sourceOf: row => sources.get(row.line) || 'unknown', counts, telemetry: true};
    }
    sources.clear();
    for (const key of Object.keys(counts)) delete counts[key];
  }

  // No usable telemetry (old Replay, or evidence written during playback): infer only what can be
  // proven from the command log. keep-set is exact — one rule is one (factory, action) pair — so
  // an `action` record on that factory with that action while the rule is active is automation.
  const rules = keepRules(low, commands);
  for (const row of low) {
    const inferred = rules.find(rule => row.type == null && row.action === rule.action
      && row.units.length === 1 && row.units[0] === rule.unit
      && row.time_s >= rule.from && (rule.to == null || row.time_s < rule.to));
    if (inferred) { sources.set(row.line, 'keep'); bump('keep'); }
    else { sources.set(row.line, 'unknown'); bump('unknown'); }
  }
  return {sources, sourceOf: row => sources.get(row.line) || 'unknown', counts, telemetry: false};
}

/** Active keep-set rules: `--unit <id> --action <id>` from the command to keep-clear/end. */
function keepRules(low, commands) {
  const rules = [];
  for (const command of commands) {
    if (command.name === 'rwx-game-keep-set' && command.args.unit && command.args.action) {
      rules.push({unit: Number(command.args.unit), action: command.args.action, from: command.time, to: null});
    } else if (command.name === 'rwx-game-keep-clear') {
      for (const rule of rules) if (rule.to == null) rule.to = command.time;
    }
  }
  return rules;
}

const isAgent = source => AGENT_SOURCES.has(source);

module.exports = {AGENT_SOURCES, attributeLow, findTelemetry, isAgent, keepRules};
