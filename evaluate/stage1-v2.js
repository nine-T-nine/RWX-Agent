'use strict';

const {current, loadConfig} = require('./config');

const CONFIG = loadConfig();
const VERSION = CONFIG.stage1.v2.version;
const DEFAULTS = Object.freeze(current('stage1.v2.parameters', CONFIG));
const round = (value, digits = 3) => value == null ? null : Number(value.toFixed(digits));
const clip = (value, lo = 0, hi = 1) => Math.max(lo, Math.min(hi, value));
const median = values => quantile(values, .5);

function quantile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const at = (sorted.length - 1) * p, index = Math.floor(at);
  return sorted[index] + (sorted[Math.min(index + 1, sorted.length - 1)] - sorted[index]) * (at - index);
}

function weightedQuantile(values, p) {
  const rows = values.filter(value => Number.isFinite(value) && value >= 0).sort((a, b) => a - b);
  if (!rows.length) return null;
  const total = rows.reduce((sum, value) => sum + value, 0);
  if (total <= 0) return rows.at(-1);
  let cumulative = 0;
  for (const value of rows) {
    cumulative += value;
    if (cumulative >= total * p) return value;
  }
  return rows.at(-1);
}

function clusters(commands, maxGap) {
  const result = [];
  for (const command of commands.filter(row => row.type === 'B').sort((a, b) => a.time - b.time || a.line - b.line)) {
    const last = result.at(-1);
    if (!last || command.time - last.end > maxGap) result.push({start: command.time, end: command.time, count: 1});
    else { last.end = command.time; last.count++; }
  }
  return result;
}

function rawFeatures(commands, nativeTimes, durationSeconds, parameters = DEFAULTS) {
  const ordered = commands.filter(row => row.type === 'A' || row.type === 'B')
    .sort((a, b) => a.time - b.time || a.line - b.line);
  const bs = clusters(ordered, parameters.clusterGapSeconds);
  const as = ordered.filter(row => row.type === 'A');
  const f2 = [], f3 = [], blocks = [], f5 = [];
  for (let index = 0; index < bs.length - 1; index++) {
    const left = bs[index], right = bs[index + 1];
    f5.push(Math.max(0, right.start - left.end));
    const firstA = as.find(row => row.time > left.end && row.time < right.start);
    if (!firstA) continue;
    blocks.push(firstA.time);
    f3.push(Math.max(0, firstA.time - left.end));
    f2.push(Math.max(0, right.start - firstA.time));
  }
  if (bs.length) f5.push(Math.max(0, durationSeconds - bs.at(-1).end));
  const f4 = blocks.slice(1).map((time, index) => Math.max(0, time - blocks[index]));
  if (blocks.length) f4.push(Math.max(0, durationSeconds - blocks.at(-1)));
  const native = [...nativeTimes].filter(Number.isFinite).sort((a, b) => a - b);
  const f6 = native.slice(1).map((time, index) => Math.max(0, time - native[index]));
  if (native.length) f6.push(Math.max(0, durationSeconds - native.at(-1)));
  return {
    counts: {A: as.length, B: ordered.length - as.length, bClusters: bs.length, native: native.length},
    samples: {f2, f3, f4, f5, f6},
    values: {
      f1: bs[0]?.start ?? null,
      f2: median(f2), f3: median(f3),
      f4: weightedQuantile(f4, parameters.weightedQuantile),
      f5: weightedQuantile(f5, parameters.weightedQuantile),
      f6: weightedQuantile(f6, parameters.weightedQuantile),
    },
    clusters: bs,
  };
}

function normalized(value, [lo, hi]) {
  if (!Number.isFinite(value)) return 1;
  return clip((Math.log1p(value) - Math.log1p(lo)) / (Math.log1p(hi) - Math.log1p(lo)));
}

function curve(u, lambda) {
  if (Math.abs(lambda) < 1e-9) return u;
  return (Math.exp(lambda * u) - 1) / (Math.exp(lambda) - 1);
}

function scoreEvidence(commands, nativeTimes, durationSeconds, overrides = {}) {
  const p = {...DEFAULTS, ...overrides,
    groupWeights: {...DEFAULTS.groupWeights, ...(overrides.groupWeights || {})},
    featureThresholdsSeconds: {...DEFAULTS.featureThresholdsSeconds, ...(overrides.featureThresholdsSeconds || {})}};
  if (!(durationSeconds >= p.minimumDurationSeconds)) return {
    schema: 1, version: VERSION, eligible: false, score: null,
    ineligibleReason: `duration_below_${p.minimumDurationSeconds}s`, durationSeconds: round(durationSeconds), parameters: p,
  };
  const evidence = rawFeatures(commands, nativeTimes, durationSeconds, p);
  const features = {};
  for (const id of ['f1', 'f2', 'f3', 'f4', 'f5', 'f6']) {
    const value = evidence.values[id], samples = id === 'f1' ? 1 : (evidence.samples[id]?.length ?? 0);
    let u = normalized(value, p.featureThresholdsSeconds[id]);
    const noA = (id === 'f2' || id === 'f3') && evidence.counts.A === 0;
    if (noA) u = 1;
    else if (id !== 'f1' && samples < p.minimumSamples)
      u = (samples * u + (p.minimumSamples - samples)) / p.minimumSamples;
    features[id] = {value: round(value), samples, u: round(u), severity: round(curve(u, p.lambda)),
      lowConfidence: id !== 'f1' && samples < p.minimumSamples, diagnosticOnly: id === 'f4'};
  }
  const weights = p.groupWeights;
  const groups = {
    startup: {weight: weights.startup, featureIds: ['f1']},
    cycle: {weight: weights.cycle, featureIds: ['f2', 'f3']},
    silence: {weight: weights.silence, featureIds: ['f5', 'f6']},
  };
  let deduction = 0;
  for (const group of Object.values(groups)) {
    group.severity = group.featureIds.reduce((sum, id) => sum + features[id].severity, 0) / group.featureIds.length;
    group.deduction = group.weight * group.severity;
    deduction += group.deduction;
    group.severity = round(group.severity); group.deduction = round(group.deduction);
  }
  const nativePerMinute = evidence.counts.native * 60 / durationSeconds;
  const rawScore = Math.max(0, 100 - deduction);
  const activityGateApplied = nativePerMinute < p.minimumNativeCommandsPerMinute;
  const score = activityGateApplied ? Math.min(rawScore, p.activityGateScoreCap) : rawScore;
  return {
    schema: 1, version: VERSION, eligible: true, baseline: 100, durationSeconds: round(durationSeconds),
    parameters: p, score: round(score), deduction: round(100 - score), rawScore: round(rawScore),
    status: Object.values(features).some(row => row.lowConfidence) ? 'low-confidence' : 'provisional',
    provisional: true,
    provisionalReasons: ['feature thresholds are design priors', 'lambda and activity gate cap are not calibrated'],
    activity: {nativeCommandsPerMinute: round(nativePerMinute), threshold: p.minimumNativeCommandsPerMinute,
      scoreCap: p.activityGateScoreCap, applied: activityGateApplied},
    groups, features, evidence,
  };
}

module.exports = {VERSION, DEFAULTS, quantile, weightedQuantile, clusters, rawFeatures, normalized, curve, scoreEvidence};
