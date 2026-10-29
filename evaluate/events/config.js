'use strict';

const DEFAULTS = Object.freeze({X:160, T_AMBUSH:5000, T_INACTIVE:3000,
  T_REACTION_TAIL:3000, T_GAP_MIN:3000, T_GAP_MAX:10000,
  T_REGION_HOLD:5000, T_FEED_MERGE:5000, T_ECONOMY_SAMPLE:1000});
function configure(overrides={}) {
  for (const key of Object.keys(overrides)) if (!(key in DEFAULTS)) throw Error(`Unknown event parameter: ${key}`);
  const result={...DEFAULTS,...overrides};
  for(const [key,value] of Object.entries(result)) if(!Number.isFinite(value)||value<=0) throw Error(`Invalid ${key}`);
  if(result.T_GAP_MIN>=result.T_GAP_MAX) throw Error('T_GAP_MIN must be less than T_GAP_MAX');
  return Object.freeze(result);
}
module.exports={DEFAULTS,configure};
