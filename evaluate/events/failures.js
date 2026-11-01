'use strict';

// Rejected CLI requests are facts recorded by the engine (`result.accepted=false`). They are counted
// and classified, never scored: a rejection can be the correct outcome of a probe or a retry.
const {directB,argsObject,staleCommandRefs,operation}=require('./model');
const {isIndirect,signature}=require('../execution-mapping');
const {QUERY}=require('./commands');

/**
 * The engine now records a stable `code` on every `result` row (assigned where the exception is
 * still typed). Prefer it; the prose patterns below are only the fallback for older telemetry and
 * must never be the reason a verdict changes when a code is present.
 */
const CODE_CATEGORY=Object.freeze({STALE_OBJECT:'STALE_OBJECT',NOT_CONTROLLABLE:'STALE_OBJECT',
  PLACEMENT_BLOCKED:'PLACEMENT_BLOCKED',UNAVAILABLE_ACTION:'UNAVAILABLE_ACTION',
  INVALID_ACTION:'INVALID_ACTION',INVALID_ARGUMENT:'INVALID_ARGUMENT',
  INSUFFICIENT_CREDITS:'INSUFFICIENT_CREDITS',INTERNAL:'OTHER'});

/**
 * Ordered reply patterns. The engine answers with prose, so the category is only as precise as the
 * message; anything unmatched stays OTHER instead of being guessed. `has no action … right now` is a
 * temporarily unavailable action, while a bare `has no action 'x'` means the action name is invalid.
 */
const RULES=Object.freeze([
  ['STALE_OBJECT',/skipped dead\/missing|no controllable unit remains|not currently observable|dead or hidden|unit not found|no pending order/i],
  ['PLACEMENT_BLOCKED',/cannot place|位置被阻挡|blockedby:|placement blocked/i],
  ['INVALID_ARGUMENT',/unknown option|unrecognized option|missing required|requires (an? )?--|invalid argument|invalid value|must be (an? )?(integer|number)/i],
  ['UNAVAILABLE_ACTION',/has no action .*right now|not available right now|正在升级|upgrade .*in progress|cannot build /i],
  ['INVALID_ACTION',/has no action|no such action|unknown action|未找到动作|is not an unfinished building/i],
]);
const CATEGORIES=Object.freeze(['INVALID_ARGUMENT','INVALID_ACTION','UNAVAILABLE_ACTION','STALE_OBJECT','PLACEMENT_BLOCKED','OTHER']);

function classify(reply) {
  const text=typeof reply==='string'?reply:'';
  for(const [category,pattern] of RULES)if(pattern.test(text))return category;
  return 'OTHER';
}
/** The engine's code wins; the prose classifier is the fallback for telemetry that predates it. */
function classifyCommand(command) {
  const code=command?.code;
  if(typeof code==='string'&&code&&code!=='ACCEPTED')return CODE_CATEGORY[code]||'OTHER';
  return classify(command?.reply);
}
/** operation = active direct B, configuration = indirect setup, query = read-only, other = not scored. */
function requestKind(command) {
  const name=String(command.name||'');
  if(QUERY.has(name)||name.startsWith('rwx-query-'))return 'query';
  if(isIndirect({name,args:argsObject(command)}))return 'configuration';
  if(directB(command))return 'operation';
  return 'other';
}
/** Objects a request names: controlled units/builders plus its target. */
function commandObjects(command) {
  const ids=new Set([...(command.units||[]),...(command.builders||[])]);
  if(command.targetId!=null)ids.add(Number(command.targetId));
  return [...ids].map(Number).filter(Number.isFinite);
}
function collectFailures(commands,{retryWindowMs=10000}={}) {
  const rows=[];
  for(const command of commands||[]){
    // Only an explicit rejection counts. `accepted=true` means the CLI took the request; it is not
    // proof that the engine applied it, so nothing here is derived from `accepted`.
    if(command.accepted!==false)continue;
    const reply=typeof command.reply==='string'?command.reply:'';
    rows.push({command,commandId:command.id,timeMs:command.timeMs,name:command.name,args:command.args,raw:command.raw,reply,
      code:typeof command.code==='string'?command.code:null,
      category:classifyCommand(command),kind:requestKind(command),staleObjects:staleCommandRefs(command).map(ref=>ref.id),
      objects:commandObjects(command),signature:signature({name:command.name,args:argsObject(command)})});
  }
  rows.sort((a,b)=>a.timeMs-b.timeMs||String(a.commandId).localeCompare(String(b.commandId)));
  // Only the first failed attempt of an identical request is the record; a repeat is noise.
  const seen=new Set();
  for(const row of rows){
    if(!['operation','configuration'].includes(row.kind))continue;
    if(seen.has(row.signature))row.duplicate=true;else seen.add(row.signature);
  }
  // An identical request accepted inside the window means the failure was transient, not unresolved.
  for(const row of rows){
    if(!['operation','configuration'].includes(row.kind)||row.duplicate||row.staleObjects.length)continue;
    const later=(commands||[]).filter(command=>command.accepted===true&&command.timeMs>=row.timeMs
      &&command.timeMs-row.timeMs<=retryWindowMs);
    row.recovered=later.some(command=>signature({name:command.name,args:argsObject(command)})===row.signature);
    // The same objects were used successfully by the same kind of request: the failure cost at most
    // the window, not the objects' productivity.
    row.objectContinued=later.some(command=>operation(command)===operation(row.command)
      &&commandObjects(command).some(id=>row.objects.includes(id)));
  }
  return rows;
}
module.exports={QUERY,RULES,CATEGORIES,CODE_CATEGORY,classify,classifyCommand,requestKind,collectFailures};
