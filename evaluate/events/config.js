'use strict';

const DEFAULTS = Object.freeze({X:160, T_AMBUSH:5000, T_INACTIVE:3000,
  T_REACTION_TAIL:3000, T_GAP_MIN:3000, T_GAP_MAX:10000,
  T_REGION_HOLD:5000, T_FEED_MERGE:5000, T_ECONOMY_SAMPLE:1000,
  // Only used by the COMMAND_FAILURE filter statistic (a retry accepted inside this window counts as
  // recovered). It never changes an event verdict.
  T_RETRY_WINDOW:10000,
  // C 抖动过期: rejections closer together than this belong to one burst; rejections inside one
  // batch are one attempt, because a parallel burst can reject itself.
  T_STALE_BURST_WINDOW:10000, T_STALE_BATCH:250, T_STALE_BURST_MIN:3,
  // D/E 空闲: the idle-pair evidence keeps everything above the collector floor; these are the
  // tier boundaries used only for reporting; the score applies independent duration curves.
  T_IDLE_SHORT:10000, T_IDLE_LONG:60000,
  // B 危险区误判: how long a loss keeps a place "recently dangerous"; how long after the ordered
  // unit first reaches the place a hit still counts; and the hard cap from the order, which bounds
  // the wait for units whose march we cannot observe.
  T_GRINDER_RECENT:40000, T_GRINDER_AFTER:10000, T_GRINDER_HARD_CAP:120000,
  // Detector set. The legacy 3-10s gap detectors are superseded by D/E; keep them only to reproduce
  // an old report. The CLI turns them off by default.
  legacyDetectors:true,
  // D/E both require proof that the exact endpoint work was feasible throughout the interval.
  idleScoreBuilders:true});

const BOOLEAN_KEYS = new Set(['legacyDetectors', 'idleScoreBuilders']);

function configure(overrides={}) {
  for (const key of Object.keys(overrides)) if (!(key in DEFAULTS)) throw Error(`Unknown event parameter: ${key}`);
  const result={...DEFAULTS,...overrides};
  for(const [key,value] of Object.entries(result)){
    if(BOOLEAN_KEYS.has(key)){ if(typeof value!=='boolean') throw Error(`Invalid ${key}`); continue; }
    if(!Number.isFinite(value)||value<=0) throw Error(`Invalid ${key}`);
  }
  if(result.T_GAP_MIN>=result.T_GAP_MAX) throw Error('T_GAP_MIN must be less than T_GAP_MAX');
  if(result.T_IDLE_SHORT>=result.T_IDLE_LONG) throw Error('T_IDLE_SHORT must be less than T_IDLE_LONG');
  if(result.T_STALE_BURST_MIN<2) throw Error('T_STALE_BURST_MIN must be at least 2');
  return Object.freeze(result);
}
module.exports={DEFAULTS,BOOLEAN_KEYS,configure};
