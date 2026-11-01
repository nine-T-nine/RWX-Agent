'use strict';
/**
 * Preliminary round-2 score.
 *
 * The evaluator is evidence first: every point of deduction names the event that caused it, and
 * each category is capped so one failure mode cannot dominate the total. This is intentionally not
 * a "final" score — the weights are a starting point, and `excluded` records everything that was
 * NOT scored so a reader can see how much evidence the formula ignores.
 *
 * Event families (round 2):
 *   A 偷袭        AMBUSH (kind UNITS | BASE)
 *   B 绞肉机      GRINDER
 *   C 抖动过期    STALE_BURST (a burst of rejected commands)
 *   D 空闲建造者  BUILDER_IDLE
 *   E 空闲工厂    FACTORY_IDLE
 *   F 资金空余    material only (economy_seconds.csv), never scored here
 *
 * The older event names (FEED, STALE_COMMAND, PRODUCTION_GAP, BUILDER_GAP) are mapped onto the same
 * families so this scorer also runs on reports produced before the round-2 rewrite.
 */

const DEFAULTS = Object.freeze({
  baseline: 100,
  /** Short idling is normal; only the part above this is penalised. */
  freeIdleMs: 3000,
  /** One episode cannot cost more than this many seconds, however long it lasted. */
  maxIdleMsPerEpisode: 120000,
  /** A grinder needs more than this many net losses before it costs anything. */
  grinderFreeLosses: 3,
  /** The first rejections in a burst are noise (the same turn retried itself). */
  staleFreePerBurst: 1,
  /** A "positional" query is one whose reply carries enemy positions; these do not count. */
  weights: Object.freeze({
    AMBUSH_UNANSWERED: 3,
    AMBUSH_BASE_EXTRA: 2,
    GRINDER_PER_NET_LOSS: 0.5,
    GRINDER_UNOBSERVED: 3,
    STALE_BURST: 2,
    STALE_PER_ITEM: 0.5,
    BUILDER_IDLE_PER_MINUTE: 3,
    FACTORY_IDLE_PER_MINUTE: 3,
  }),
  caps: Object.freeze({AMBUSH: 20, GRINDER: 25, STALE: 20, BUILDER_IDLE: 15, FACTORY_IDLE: 20}),
});

const FAMILY = Object.freeze({
  AMBUSH: 'AMBUSH',
  FEED: 'GRINDER', GRINDER: 'GRINDER',
  STALE_COMMAND: 'STALE', STALE_BURST: 'STALE', COMMAND_FAILURE: 'STALE',
  BUILDER_GAP: 'BUILDER_IDLE', BUILDER_IDLE: 'BUILDER_IDLE',
  PRODUCTION_GAP: 'FACTORY_IDLE', FACTORY_IDLE: 'FACTORY_IDLE',
});

const sumCounts = counts => Object.values(counts || {}).reduce((a, b) => a + (Number(b) || 0), 0);
const ms = value => (Number.isFinite(value) ? value : 0);

/** Only a confirmed, agent-attributable event may cost points. */
function scoreable(event) {
  if (event.scoreable === false) return false;
  if (event.status === 'EXPLAINED' || event.status === 'UNCERTAIN') return false;
  return true;
}

/**
 * Why one event did or did not cost anything. `points` is always non-negative; `reason` is the
 * machine-readable justification, so `score.json` can be audited without re-reading the event.
 */
function deduction(event, config) {
  const family = FAMILY[event.type];
  if (!family) return null;
  if (family === 'AMBUSH') {
    if (event.status !== 'UNANSWERED') return {points: 0, reason: 'responded_or_uncertain'};
    const base = event.kind === 'BASE' ? config.weights.AMBUSH_BASE_EXTRA : 0;
    return {points: config.weights.AMBUSH_UNANSWERED + base,
      reason: event.kind === 'BASE' ? 'unanswered_base_attack' : 'unanswered_ambush'};
  }
  if (family === 'GRINDER') {
    if (!scoreable(event)) return {points: 0, reason: 'not_scoreable'};
    // A danger-zone event has no loss counts: it is one send into a place the Agent had not
    // re-observed. Older FEED reports still use the net-loss form.
    if (!event.lossesSelf && !event.losses && event.consequence)
      return {points: config.weights.GRINDER_UNOBSERVED, reason: 'sent_into_unobserved_danger'};
    // Prefer the split fields; fall back to the legacy union so old reports still work.
    const self = event.lossesSelf ? sumCounts(event.lossesSelf) : sumCounts(event.losses);
    const enemy = event.lossesEnemy ? sumCounts(event.lossesEnemy) : 0;
    const net = self - enemy;
    if (net <= config.grinderFreeLosses) return {points: 0, reason: 'acceptable_exchange'};
    return {points: (net - config.grinderFreeLosses) * config.weights.GRINDER_PER_NET_LOSS,
      reason: 'net_losses_in_one_region'};
  }
  if (family === 'STALE') {
    if (!scoreable(event)) return {points: 0, reason: 'not_scoreable'};
    const count = Number.isFinite(event.count) ? event.count : 1;
    const penalised = Math.max(0, count - config.staleFreePerBurst);
    if (!penalised) return {points: 0, reason: 'within_burst_grace'};
    return {points: config.weights.STALE_BURST + penalised * config.weights.STALE_PER_ITEM,
      reason: 'rejected_command_burst'};
  }
  if (family === 'BUILDER_IDLE' || family === 'FACTORY_IDLE') {
    if (!scoreable(event)) return {points: 0, reason: 'not_scoreable'};
    const duration = ms(event.durationMs ?? event.gapMs ?? (event.t_end - event.t_start));
    const penalised = Math.min(config.maxIdleMsPerEpisode,
      Math.max(0, duration - config.freeIdleMs));
    if (!penalised) return {points: 0, reason: 'below_free_window'};
    const perMinute = family === 'BUILDER_IDLE'
      ? config.weights.BUILDER_IDLE_PER_MINUTE : config.weights.FACTORY_IDLE_PER_MINUTE;
    return {points: (penalised / 60000) * perMinute, reason: 'idle_with_work_available'};
  }
  return null;
}

/**
 * Scores one report. `events` is the parsed `events.jsonl`; `summary` is optional and only used to
 * carry diagnostics through. The result is additive and order-independent.
 */
function scoreEvents(events, {weights, caps, baseline, freeIdleMs, maxIdleMsPerEpisode,
  grinderFreeLosses, staleFreePerBurst} = {}) {
  const config = {
    baseline: baseline ?? DEFAULTS.baseline,
    freeIdleMs: freeIdleMs ?? DEFAULTS.freeIdleMs,
    maxIdleMsPerEpisode: maxIdleMsPerEpisode ?? DEFAULTS.maxIdleMsPerEpisode,
    grinderFreeLosses: grinderFreeLosses ?? DEFAULTS.grinderFreeLosses,
    staleFreePerBurst: staleFreePerBurst ?? DEFAULTS.staleFreePerBurst,
    weights: {...DEFAULTS.weights, ...(weights || {})},
    caps: {...DEFAULTS.caps, ...(caps || {})},
  };
  const families = {};
  const excluded = {};
  for (const event of events || []) {
    const family = FAMILY[event.type];
    if (!family) { excluded[event.type] = (excluded[event.type] || 0) + 1; continue; }
    const row = families[family] ||= {raw: 0, capped: 0, cap: config.caps[family] ?? Infinity,
      items: [], counts: {}, unscored: null};
    const result = deduction(event, config);
    if (!result) continue;
    row.counts[result.reason] = (row.counts[result.reason] || 0) + 1;
    if (!result.points) {
      // Unscored time has to stay visible: an idle episode that the evidence could not close
      // (EVIDENCE_END) costs nothing, and reporting only the total would read as if those minutes
      // never happened. `durationMs` is the detector's own length, not a re-derivation.
      if (result.reason === 'not_scoreable') {
        row.unscored ||= {count: 0, durationMs: 0};
        row.unscored.count += 1;
        row.unscored.durationMs += ms(event.durationMs ?? event.gapMs
          ?? (event.t_end - event.t_start));
      }
      continue;
    }
    row.raw += result.points;
    row.items.push({id: event.id, type: event.type, t_start: event.t_start, t_end: event.t_end,
      points: Number(result.points.toFixed(3)), reason: result.reason});
  }
  const breakdown = Object.entries(families).map(([family, row]) => {
    row.capped = Number(Math.min(row.cap, row.raw).toFixed(3));
    row.raw = Number(row.raw.toFixed(3));
    return {family, ...row};
  }).sort((a, b) => b.capped - a.capped);
  const deductionTotal = Number(breakdown.reduce((a, b) => a + b.capped, 0).toFixed(3));
  return {
    schema: 1,
    baseline: config.baseline,
    total: Number(Math.max(0, config.baseline - deductionTotal).toFixed(3)),
    deduction: deductionTotal,
    config,
    breakdown,
    excluded,
    interpretation: 'Preliminary and additive; each point names one event, and caps keep one '
      + 'failure mode from dominating. F (资金空余) is material only and is never scored here.',
  };
}

module.exports = {DEFAULTS, FAMILY, deduction, scoreEvents, scoreable};
