'use strict';
const {directB}=require('./model');

/**
 * Read-only requests. Kept here so one list decides what counts as an observation for every
 * consumer: the legacy COMMAND_FAILURE classifier, the `agentState` attribution and the positional
 * awareness test that the grinder judgement will need.
 */
const QUERY=new Set(['rwx-game-state','rwx-game-team-list','rwx-game-unit-list','rwx-game-unit-show',
  'rwx-game-unit-actions','rwx-game-map-show','rwx-game-map-path','rwx-game-map-place','rwx-game-events',
  'rwx-game-threat-list','rwx-game-replay-status','rwx-game-group-list','rwx-game-group-show',
  'rwx-game-pending-list','rwx-game-keep-list','rwx-game-auto-list','rwx-game-auto-show',
  'rwx-game-nuke-status','rwx-game-camera','rwx-game-connection-status']);

/** A = observation, B = active direct order, C = anything else (lobby, pause, camera, config). */
function commandKind(command){
  const name=String(command.name||'');
  if(QUERY.has(name)||name.startsWith('rwx-query-'))return 'A';
  if(directB(command))return 'B';
  return 'C';
}

/**
 * What the Agent was doing during `[start,end)`, from its own receipts only.
 *
 * SILENT means no command at all — the model or the link was stalled. OBSERVING means it was
 * looking but not ordering. BUSY_ELSEWHERE means it was ordering other things, so an idle asset is
 * an attention miss rather than a stall. This never changes a verdict; it explains one.
 */
function agentState(commands,start,end){
  let A=0,B=0,C=0;
  for(const command of commands||[]){
    if(command.timeMs<start||command.timeMs>=end)continue;
    const kind=commandKind(command);
    if(kind==='A')A++;else if(kind==='B')B++;else C++;
  }
  const state=B?'BUSY_ELSEWHERE':A||C?'OBSERVING':'SILENT';
  return {state,evidence:{A_count:A,B_count:B,C_count:C}};
}

/**
 * Whether a query could have told the Agent about enemies near a place: it must carry a position
 * or name enemy units. `state --summary` only gives global counts, so it does not count.
 */
function positionalQuery(command){
  const args=command.args||{};
  const name=String(command.name||'');
  if(name==='rwx-game-threat-list'||name==='rwx-game-map-place')return args.x!=null&&args.y!=null;
  if(name==='rwx-game-unit-show')return args.id!=null||args.unit!=null;
  if(name==='rwx-game-unit-list')return /enemy|all/i.test(String(args.owner||''))||args.area!=null||args.rect!=null;
  if(name==='rwx-game-events')return true;
  return false;
}

module.exports={QUERY,commandKind,agentState,positionalQuery};
