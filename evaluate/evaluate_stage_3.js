#!/usr/bin/env node
'use strict';
/** Build the auditable Stage-3 event set from engine telemetry. */
const fs=require('node:fs');
const path=require('node:path');
const {records}=require('./evaluate_stage_2');

const VERSION='stage3-events-v1';

function closedEvent(row,type,scoreable=true,reason=null){
  return {schema:1,version:VERSION,id:row.eventId,type,t_start:row.fromMs,t_end:row.toMs,
    scoreable,status:scoreable?'scoreable':'excluded',reason,
    unit:{id:row.unitId,type:row.type},D_hp:Number(row.DHp||0),D_sh:Number(row.DShield||0),
    N:Number(row.N||0),T_ms:Math.max(0,Number(row.durationMs??((row.toMs??0)-(row.fromMs??0)))),
    HP_max:Number(row.maxHp||0),Shield_max:Number(row.maxShield||0),cost:Number(row.cost||0),
    died:Boolean(row.died),destroyed:Boolean(row.destroyed),closeReason:row.closeReason??null,
    complete:row.complete!==false};
}

function compile(rows){
  let meta=null,stage3Meta=null,end=null,lastMs=0;
  const ambush=new Map(),barrage=new Map(),builder=[],zones=[];
  for(const row of rows){
    lastMs=Math.max(lastMs,Number(row.timeMs||0));
    if(row.kind==='meta')meta=row;
    else if(row.kind==='stage3_meta')stage3Meta=row;
    else if(row.kind==='end')end=row;
    else if(row.kind==='stage3_zone')zones.push(row);
    else if(row.kind==='stage3_builder_death')builder.push({schema:1,version:VERSION,
      id:`G-${row.unitId}-${row.seq}`,type:'STAGE3_GRINDER',t_start:row.deathMs,t_end:row.deathMs,
      scoreable:row.scoreable===true,status:row.scoreable===true?'scoreable':'excluded',
      reason:row.damageKilled!==true?'NOT_DAMAGE_KILL':row.transported===true?'TRANSPORT_DEATH':
        row.zoneId==null?'OUTSIDE_ACTIVE_ZONE':!(row.ageMs>0)?'WITHIN_GRACE':null,
      unit:{id:row.unitId,type:row.type},weight:Number(row.weight||0),zoneId:row.zoneId??null,
      zoneBirthMs:row.zoneBirthMs??null,ageMs:row.ageMs??null,x:row.x,y:row.y});
    else if(row.kind==='stage3_ambush'){
      if(row.phase==='hit')ambush.set(row.eventId,{...(ambush.get(row.eventId)||{}),...row});
      else if(row.phase==='close')ambush.set(row.eventId,{...(ambush.get(row.eventId)||{}),...row,closed:true});
    }else if(row.kind==='stage3_barrage'){
      if(row.phase==='hit')barrage.set(row.eventId,{...(barrage.get(row.eventId)||{}),...row});
      else if(row.phase==='close')barrage.set(row.eventId,{...(barrage.get(row.eventId)||{}),...row,closed:true});
    }
  }
  if(!meta||meta.schema!==1)throw Error('Engine evidence schema 1 metadata is required');
  if(!stage3Meta||stage3Meta.schema!==1)throw Error('Stage-3 evidence is missing; replay with a current engine build');
  const events=[],dropped={ambushShort:0,barrageSingle:0};
  for(const row of ambush.values()){
    if(!row.closed){row.toMs=end?.timeMs??lastMs;row.durationMs=row.toMs-row.fromMs;row.closeReason='PREPROCESS_CUT';row.complete=false;}
    if(!(row.durationMs>3000)){dropped.ambushShort++;continue;}
    events.push(closedEvent(row,'STAGE3_AMBUSH',row.complete!==false,row.complete===false?'INCOMPLETE_INTERVAL':null));
  }
  for(const row of barrage.values()){
    if(!row.closed){row.toMs=end?.timeMs??lastMs;row.closeReason='PREPROCESS_CUT';row.complete=false;}
    if(!(row.N>=2||row.destroyed===true)){dropped.barrageSingle++;continue;}
    events.push(closedEvent(row,'STAGE3_BARRAGE',row.complete!==false,row.complete===false?'INCOMPLETE_INTERVAL':null));
  }
  events.push(...builder);
  events.sort((a,b)=>a.t_start-b.t_start||a.id.localeCompare(b.id));
  const creates=zones.filter(z=>z.action==='create'),clears=new Map(zones.filter(z=>z.action==='clear').map(z=>[z.zoneId,z]));
  const lifetimes=creates.map(z=>(clears.get(z.zoneId)?.clearMs??(end?.timeMs??lastMs))-z.birthMs);
  let jitter=0;
  for(const created of creates)for(const prior of creates){const cleared=clears.get(prior.zoneId);
    if(cleared&&cleared.clearMs<=created.birthMs&&created.birthMs-cleared.clearMs<=10000
        &&Math.hypot(created.cx-prior.cx,created.cy-prior.cy)<=Number(stage3Meta.X||0)){jitter++;break;}}
  const builderRows=events.filter(e=>e.type==='STAGE3_GRINDER');
  const summary={schema:1,version:VERSION,team:meta.team,source:meta.source,complete:Boolean(end),
    endReason:end?.reason??null,durationMs:Number(end?.timeMs??lastMs),meta:stage3Meta,
    counts:Object.fromEntries(['STAGE3_AMBUSH','STAGE3_BARRAGE','STAGE3_GRINDER'].map(type=>
      [type,events.filter(e=>e.type===type).length])),
    scoreable:Object.fromEntries(['STAGE3_AMBUSH','STAGE3_BARRAGE','STAGE3_GRINDER'].map(type=>
      [type,events.filter(e=>e.type===type&&e.scoreable).length])),dropped,
    grinderFunnel:{total:builderRows.length,
      damageKilled:builderRows.filter(e=>e.reason!=='NOT_DAMAGE_KILL').length,
      nonTransport:builderRows.filter(e=>!['NOT_DAMAGE_KILL','TRANSPORT_DEATH'].includes(e.reason)).length,
      activeZone:builderRows.filter(e=>!['NOT_DAMAGE_KILL','TRANSPORT_DEATH','OUTSIDE_ACTIVE_ZONE'].includes(e.reason)).length,
      pastGrace:builderRows.filter(e=>e.scoreable).length},
    zones:{created:creates.length,cleared:clears.size,dropped:zones.filter(z=>z.action==='drop').length,
      checks:zones.filter(z=>z.action==='check').length,lifetimesMs:lifetimes,
      averageLifetimeMs:lifetimes.length?lifetimes.reduce((a,b)=>a+b,0)/lifetimes.length:0,
      jitter,jitterRate:creates.length?jitter/creates.length:0,
      createRatios:creates.map(z=>({zoneId:z.zoneId,E:z.E,F:z.F,ratio:z.ratio}))}};
  return {events,zones,summary};
}

async function evaluateStage3(file,options={}){
  const all=[];for await(const row of records(file))all.push(row);
  const result=compile(all),input=path.resolve(file),parent=path.dirname(input);
  const out=path.resolve(options.out||(path.basename(parent)==='prepared'
    ?path.join(path.dirname(parent),'stage_3'):path.join(parent,'stage_3')));
  fs.mkdirSync(out,{recursive:true});
  fs.writeFileSync(path.join(out,'events.jsonl'),result.events.map(row=>JSON.stringify(row)).join('\n')+(result.events.length?'\n':''));
  fs.writeFileSync(path.join(out,'zones.jsonl'),result.zones.map(row=>JSON.stringify(row)).join('\n')+(result.zones.length?'\n':''));
  result.summary.inputs={telemetry:input};
  fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(result.summary,null,2)+'\n');
  const lines=result.events.map(e=>`${(e.t_start/1000).toFixed(3)} ${e.type} ${e.id} ${e.status}`);
  fs.writeFileSync(path.join(out,'events.txt'),lines.join('\n')+(lines.length?'\n':''));
  return {out,...result};
}

if(require.main===module){
  const args=process.argv.slice(2),file=args.shift(),options={};
  if(!file){console.error('Usage: node evaluate/evaluate_stage_3.js <telemetry.jsonl> [--out directory]');process.exitCode=1;}
  else{while(args.length){const flag=args.shift();if(flag==='--out')options.out=args.shift();else throw Error(`Unknown option ${flag}`);}
    evaluateStage3(file,options).then(r=>console.log(JSON.stringify(r.summary,null,2)))
      .catch(error=>{console.error(error.message);process.exitCode=1;});}
}
module.exports={VERSION,compile,evaluateStage3};
