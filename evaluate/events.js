#!/usr/bin/env node
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const readline=require('node:readline');
const {configure}=require('./events/config');
const {identity,stamp,directB,CommandMatcher,EconomyRecorder}=require('./events/model');
const detectors=require('./events/detectors');

async function *records(file) {
  const stream=fs.createReadStream(file,{encoding:'utf8'}),lines=readline.createInterface({input:stream,crlfDelay:Infinity});
  let line=0,lastSeq=-1,lastTime=-1;
  try {for await(const raw of lines){line++;if(!raw.trim())continue;
    let row;try{row=JSON.parse(raw.replace(/^\uFEFF/,''));}catch(e){throw Error(`${file}:${line}: ${e.message}`);}
    if(!Number.isFinite(row.timeMs)||!Number.isInteger(row.seq)||row.seq<=lastSeq||row.timeMs<lastTime)
      throw Error(`${file}:${line}: non-monotonic game clock or sequence`);
    lastSeq=row.seq;lastTime=row.timeMs;yield row;
  }}finally{lines.close();stream.destroy();}
}
async function indexCommands(file) {
  const commands=new Map(),batches=new Map(),nativeBefore=new Map(),nativeAfter=new Map();let meta=null,ended=false;
  for await(const row of records(file)){
    if(row.kind==='meta'){if(meta)throw Error('Only one match is allowed per evidence file');meta=row;}
    if(row.kind==='end')ended=row.reason!=='observation_error';
    if(row.kind==='native'&&row.replayOrdinal!=null){
      (row.phase==='before'?nativeBefore:nativeAfter).set(row.replayOrdinal,row);
    }
    if(row.kind==='command')commands.set(row.id,{...row,units:row.units||[],refs:row.refs||[],applications:[]});
    if(row.kind==='intent'){
      const command=commands.get(row.commandId);if(command){
        const originalRefs=command.refs;
        Object.assign(command,row,{id:command.id,timeMs:command.timeMs,seq:command.seq,kind:'command'});
        command.refs=[...(row.refs||[])];
        for(const ref of originalRefs){const at=command.refs.findIndex(r=>r.id===ref.id&&r.role===ref.role);
          if(at<0)command.refs.push(ref);else command.refs[at]=ref;}
      }
    }
    if(row.kind==='result'){
      const command=commands.get(row.commandId);if(command){command.accepted=row.accepted;command.reply=row.reply;}
    }
    if(row.kind==='native'&&row.phase==='after'){
      const command=commands.get(row.commandId);if(command)command.applications.push({timeMs:row.timeMs,nativeId:row.nativeId,
        applied:row.results?.some(r=>r.applied===true)??null,results:row.results||[]});
      for(const result of row.results||[]){
        const key=`${row.commandId??row.nativeId}:${result.unitId}:${row.tick}`;
        const count=(result.queueAdded||[]).filter(q=>q.kind==='produce').reduce((n,q)=>n+q.count,0);
        batches.set(key,(batches.get(key)||0)+count);
      }
    }
  }
  if(!meta||meta.schema!==1)throw Error('Engine evidence schema 1 metadata is required; Replay command JSONL is not state evidence');
  return {commands,batches,meta,ended,nativeBefore,nativeAfter};
}
function legacyCommands(index,commandsFile,replayFile) {
  const {readHigh,readLow,matchAll}=require('./evaluate');
  const high=readHigh(commandsFile),low=readLow(replayFile,high.team);
  if(high.team!==index.meta.team)throw Error('CLI player and engine evidence team differ');
  const matches=matchAll(high.high,low),byLine=new Map(matches.map(r=>[r.highLine,r]));
  const commands=high.commands.filter(h=>h.meta.match!=='none').map(h=>{
    const match=byLine.get(h.line),explicit=String(h.args.units??h.args.unit??'').split(',').filter(s=>s.trim()).map(Number).filter(Number.isInteger);
    const applications=[];
    if(['matched','partial'].includes(match?.status))for(const line of match.lowLines){
      const native=index.nativeBefore.get(line),after=index.nativeAfter.get(line),original=low.find(l=>l.line===line);
      if(!native||!after||!original||native.tick!==original.tick||native.team!==original.team
          ||JSON.stringify([...native.units].sort((a,b)=>a-b))!==JSON.stringify([...original.units].sort((a,b)=>a-b)))continue;
      applications.push({nativeId:native.nativeId,timeMs:after.timeMs,applied:after.results?.some(r=>r.applied===true)??null,
        results:after.results||[],replayLine:line});
    }
    const units=[...new Set([...explicit,...Object.keys(match?.operationsByUnit||{}).map(Number)])];
    return {id:`legacy-${h.line}`,seq:h.line,timeMs:h.time*1000,timeUncertaintyMs:h.timeUncertainty*1000,
      source:'agent',name:h.name,raw:h.command,args:h.args,units,refs:[],applications,legacy:true,
      x:h.args.x==null?null:Number(h.args.x),y:h.args.y==null?null:Number(h.args.y),
      targetId:h.args.target==null?null:Number(h.args.target),accepted:null,matchStatus:match?.status??null};
  }).sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
  index.meta.commandCoverage={start:commands[0]?.timeMs??Infinity,end:commands.at(-1)?.timeMs??-Infinity};
  index.meta.legacyCommands=path.resolve(commandsFile);
  return commands;
}
class Session {
  constructor(meta,commands,overrides={},emit=()=>{}) {
    this.config=configure({X:meta.X??160,...overrides});this.meta=meta;
    this.units=new Map();this.regionNeighbors=new Map();this.matcher=new CommandMatcher(commands,this.config);
    this.commandCoverage=meta.commandCoverage??(meta.source==='replay'?null:{start:meta.timeMs??0,end:Infinity});
    this.output=emit;this.counter=0;this.now=meta.timeMs??0;this.counts={};this.statuses={};this.strict={};
    this.builderIntervals=[];this.latencies={};
    this.detectors=Object.values(detectors).map(Type=>new Type(this));this.economy=new EconomyRecorder(this.config.T_ECONOMY_SAMPLE);
  }
  nextId(type){return `${type}-${String(++this.counter).padStart(6,'0')}`;}
  covers(start,end){return this.commandCoverage!=null&&start>=this.commandCoverage.start&&end<=this.commandCoverage.end;}
  emit(event){
    this.counts[event.type]=(this.counts[event.type]||0)+1;
    const key=event.type+':'+event.status;this.statuses[key]=(this.statuses[key]||0)+1;
    if(event.strict)this.strict[event.type]=(this.strict[event.type]||0)+1;
    if(event.reactionMs!=null)(this.latencies[event.type]??=[]).push(event.reactionMs);
    if(event.type==='BUILDER_GAP'&&event.strict)this.builderIntervals.push({start:event.t_start,end:event.t_end,unitId:event.unit.id});
    this.output(event);
  }
  consume(row,command=null) {
    this.now=row.timeMs;
    if(row.kind==='unit')this.units.set(identity(row.unit),row.unit);
    if(row.kind==='death')this.units.set(identity(row.unit),{...row.unit,alive:false});
    if(row.kind==='region')this.regionNeighbors.set(row.anchorId,row.nearby||[]);
    if(row.kind==='economy')this.economy.sample(row);
    if(command?.legacy){
      command.controlled=command.units.map(id=>[...this.units.values()].find(u=>u.id===id)).filter(Boolean);
      command.refs=command.controlled.filter(u=>u.alive===false).map(u=>({id:u.id,life:u.life,role:'controlled',
        status:'dead',checkedMs:command.timeMs,excluded:u.excluded??false}));
      command.targetUnit=[...this.units.values()].find(u=>u.id===command.targetId);
    }
    for(const detector of this.detectors){
      if(row.kind==='unit')detector.unit?.(row);
      if(row.kind==='damage')detector.damage?.(row);
      if(row.kind==='death')detector.death?.(row);
      if(row.kind==='native')detector.native?.(row);
      if(row.kind==='command'&&command)detector.command?.(command);
    }
    if(row.kind==='frame')for(const detector of this.detectors)detector.advance?.(row.timeMs);
  }
  finish(){for(const detector of this.detectors)detector.finish(this.now);}
  summary(complete) {
    const sweep=this.builderIntervals.flatMap(i=>[{time:i.start,delta:1},{time:i.end,delta:-1}]).sort((a,b)=>a.time-b.time||a.delta-b.delta);
    let active=0,last=0,overlap=0,max=0;
    for(const point of sweep){if(active>=2)overlap+=point.time-last;active+=point.delta;max=Math.max(max,active);last=point.time;}
    return {schema:1,team:this.meta.team,config:this.config,source:this.meta.source??'engine',complete,
      counts:this.counts,statuses:this.statuses,strict:this.strict,economySamples:this.economy.entries.length,
      economyComplete:this.economy.complete&&this.meta.timeMs===0,
      builderOverlap:{milliseconds:overlap,maxConcurrent:max},
      responseLatencyMs:Object.fromEntries(Object.entries(this.latencies).map(([key,values])=>[key,{count:values.length,
        mean:values.reduce((a,b)=>a+b,0)/values.length,max:Math.max(...values)}])),
      limitations:['No final score or tactical-blame inference.',
        'Agent observation history is not recoverable from world visibility alone.',
        'Historical build placement is UNCERTAIN unless a matching placement proof was captured.',
        ...(this.meta.source==='replay'?['Replay does not contain CLI origin or rejected requests; supply original command evidence for attribution.']:[]),
        ...(!complete?['Evidence has no clean end marker; active events are truncated.']:[])]};
  }
}
function eventText(e) {
  const at=e.location?` | @(${e.location.x.toFixed(0)},${e.location.y.toFixed(0)};r=${e.location.r.toFixed(0)})`:'';
  return `[${stamp(e.t_start)}-${stamp(e.t_end)}] ${e.type}${at} | ${e.status}`
    +(e.unit?` | ${e.unit.type}#${e.unit.id}`:'')+(e.unitId!=null?` | #${e.unitId} count=${e.count??0}`:'')
    +(e.gapMs!=null?` | idle=${(e.gapMs/1000).toFixed(3)}s B=${e.B_count}`:'')
    +(e.reactionMs!=null?` | first=${(e.reactionMs/1000).toFixed(3)}s`:'')
    +(e.flags?.length?` | ${e.flags.join(',')}`:'')+(e.reason?` | ${e.reason}`:'');
}
async function evaluateEvents(file,{out,config={},commands:commandsFile,replay:replayFile}={}) {
  const index=await indexCommands(file);out??=path.join(path.dirname(file),'events_analysis');
  if(config.X!=null&&config.X!==index.meta.X)throw Error('X differs from captured spatial evidence; recapture with this configuration');
  if(config.T_REGION_HOLD!=null&&index.meta.regionHoldMs!=null&&config.T_REGION_HOLD!==index.meta.regionHoldMs)
    throw Error('T_REGION_HOLD differs from captured region retention; recapture with this configuration');
  if(!!commandsFile!==!!replayFile)throw Error('--commands and --replay must be supplied together');
  const legacy=commandsFile?legacyCommands(index,commandsFile,replayFile):[];
  if(path.resolve(out)===path.dirname(path.resolve(file)))throw Error('Use a separate report directory to preserve evidence');
  fs.mkdirSync(out,{recursive:true});
  const json=fs.openSync(path.join(out,'events.jsonl'),'w'),txt=fs.openSync(path.join(out,'events.txt'),'w');
  const session=new Session(index.meta,[...index.commands.values(),...legacy],config,event=>{
    fs.writeSync(json,JSON.stringify(event)+'\n');fs.writeSync(txt,eventText(event)+'\n');
  });
  try{
    let nextLegacy=0;
    for await(const row of records(file)){
      while(nextLegacy<legacy.length&&legacy[nextLegacy].timeMs<=row.timeMs){
        const command=legacy[nextLegacy++];session.consume({kind:'command',timeMs:command.timeMs},command);
      }
      if(row.kind==='native'&&row.phase==='after')for(const r of row.results||[])
        r.batchCount=index.batches.get(`${row.commandId??row.nativeId}:${r.unitId}:${row.tick}`);
      session.consume(row,index.commands.get(row.id));
    }
    session.finish();
    const summary=session.summary(index.ended);
    fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2)+'\n');
    fs.writeFileSync(path.join(out,'economy.log'),session.economy.text());
    return {out,summary};
  }finally{fs.closeSync(json);fs.closeSync(txt);}
}
if(require.main===module){
  const args=process.argv.slice(2),file=args.shift();
  if(!file){console.error('Usage: node evaluate/events.js <telemetry.jsonl> [--out directory] [--config config.json] [--commands commands.txt --replay replay.jsonl]');process.exitCode=1;}
  else{const options={};while(args.length){const flag=args.shift(),value=args.shift();if(flag==='--out')options.out=value;
    else if(flag==='--config')options.config=JSON.parse(fs.readFileSync(value,'utf8'));else if(flag==='--commands'||flag==='--replay')options[flag.slice(2)]=value;else throw Error(`Unknown option ${flag}`);}
    evaluateEvents(file,options).then(r=>console.log(JSON.stringify(r.summary,null,2))).catch(e=>{console.error(e.message);process.exitCode=1;});}
}
module.exports={Session,records,indexCommands,legacyCommands,evaluateEvents,eventText};
