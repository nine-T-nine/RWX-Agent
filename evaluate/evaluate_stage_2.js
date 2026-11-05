#!/usr/bin/env node
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const readline=require('node:readline');
const {configure}=require('./events/config');
const {identity,stamp,directB,argsObject,CommandMatcher,EconomyRecorder}=require('./events/model');
const detectors=require('./events/detectors');const {stageDirectory}=require('./utils/paths');
const {EconomyTimeline}=require('./events/economy');

/** 同一个原因码在这个窗口内再次出现，算一次"没从回执里改过来"。 */
const REJECT_REPEAT_MS=30000;

async function *records(file) {
  const stream=fs.createReadStream(file,{encoding:'utf8'}),lines=readline.createInterface({input:stream,crlfDelay:Infinity});
  let line=0,lastSeq=-1,lastTime=-1;
  try {for await(const raw of lines){line++;if(!raw.trim())continue;
    let row;try{row=JSON.parse(raw.replace(/^\uFEFF/,''));}catch(e){throw Error(`${file}:${line}: ${e.message}`);}
    // Trimmed evidence: one "frames" row stands for a run of per-tick frame rows. The writer only
    // emits it while tick and seq advance by one and the clock advances by one constant step, so the
    // run is expanded lazily here; a row that cannot be rebuilt exactly is an error, never skipped.
    if(row.kind==='frames'){
      const count=row.toTick-row.tick+1;
      if(!Number.isInteger(row.timeMs)||!Number.isInteger(row.tick)||!Number.isInteger(row.seq)
          ||!Number.isInteger(row.toTimeMs)||!Number.isInteger(row.toTick)||!Number.isInteger(row.toSeq)
          ||!Number.isInteger(row.stepMs)||row.stepMs<=0||count<2
          ||row.toSeq-row.seq!==count-1||row.toTimeMs-row.timeMs!==(count-1)*row.stepMs)
        throw Error(`${file}:${line}: malformed frames row`);
      if(row.seq<=lastSeq||row.timeMs<lastTime)
        throw Error(`${file}:${line}: non-monotonic game clock or sequence`);
      lastSeq=row.toSeq;lastTime=row.toTimeMs;
      for(let offset=0;offset<count;offset++)yield {kind:'frame',timeMs:row.timeMs+offset*row.stepMs,tick:row.tick+offset,seq:row.seq+offset};
      continue;
    }
    if(!Number.isFinite(row.timeMs)||!Number.isInteger(row.seq)||row.seq<=lastSeq||row.timeMs<lastTime)
      throw Error(`${file}:${line}: non-monotonic game clock or sequence`);
    lastSeq=row.seq;lastTime=row.timeMs;yield row;
  }}finally{lines.close();stream.destroy();}
}
async function indexCommands(file) {
  const commands=new Map(),batches=new Map(),nativeBefore=new Map(),nativeAfter=new Map();let meta=null,ended=false,endReason=null;
  for await(const row of records(file)){
    if(row.kind==='meta'){if(meta)throw Error('Only one match is allowed per evidence file');meta=row;}
    if(row.kind==='end'){ended=row.reason!=='observation_error';endReason=row.reason??null;}
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
      const command=commands.get(row.commandId);if(command)command.applications.push({timeMs:row.timeMs,seq:row.seq,nativeId:row.nativeId,
        applied:row.results?.some(r=>r.applied===true)??null,results:row.results||[]});
      for(const result of row.results||[]){
        const key=`${row.commandId??row.nativeId}:${result.unitId}:${row.tick}`;
        const count=(result.queueAdded||[]).filter(q=>q.kind==='produce').reduce((n,q)=>n+q.count,0);
        batches.set(key,(batches.get(key)||0)+count);
      }
    }
  }
  if(!meta||meta.schema!==1)throw Error('Engine evidence schema 1 metadata is required; Replay command JSONL is not state evidence');
  return {commands,batches,meta,ended,endReason,nativeBefore,nativeAfter};
}
function legacyCommands(index,commandsFile,replayFile) {
  const {readHigh,readLow,matchAll}=require('./evaluate_stage_1');
  const high=readHigh(commandsFile),low=replayFile?readLow(replayFile,high.team):[];
  if(high.team!==index.meta.team)throw Error('CLI player and engine evidence team differ');
  const matches=replayFile?matchAll(high.high,low,{commands:high.commands}):[],byLine=new Map(matches.map(r=>[r.highLine,r]));
  const embedded=[...(index.commands?.values()||[])],used=new Set();
  const commands=high.commands.filter(h=>h.meta.match!=='none').map(h=>{
    const match=byLine.get(h.line),explicit=String(h.args.units??h.args.unit??'').split(',').filter(s=>s.trim()).map(Number).filter(Number.isInteger);
    const tokens=s=>String(s||'').replace(/['"]/g,'').replace(/\s+/g,' ').trim();
    const exact=embedded.find(c=>!used.has(c.id)&&c.timeMs===Math.round(h.time*1000)&&c.name===h.name&&tokens(c.raw)===tokens(h.command));
    if(exact){used.add(exact.id);
      // order-build names its object with --builder. Older telemetry did not record it, so the log
      // argument is kept on the exact match too instead of dropping the only builder evidence.
      const builder=Number(h.args.builder),builders=Number.isInteger(builder)?[...new Set([...(exact.builders||[]),builder])]:exact.builders||[];
      return {...exact,builders,raw:h.command,logLine:h.line,legacy:false};}
    const applications=[];
    if(['matched','partial'].includes(match?.status))for(const line of match.lowLines){
      const native=index.nativeBefore.get(line),after=index.nativeAfter.get(line),original=low.find(l=>l.line===line);
      if(!native||!after||!original||native.tick!==original.tick||native.team!==original.team
          ||JSON.stringify([...native.units].sort((a,b)=>a-b))!==JSON.stringify([...original.units].sort((a,b)=>a-b)))continue;
      applications.push({nativeId:native.nativeId,timeMs:after.timeMs,seq:after.seq,applied:after.results?.some(r=>r.applied===true)??null,
        results:after.results||[],replayLine:line});
    }
    // Only explicit high-level selections are attribution evidence here. Replay-derived
    // operationsByUnit belongs to the native execution matcher and must not broaden a high-level
    // order to every unit that happened to execute around the same time.
    const builder=Number(h.args.builder),units=[...new Set([...explicit,...(Number.isInteger(builder)?[builder]:[])])];
    return {id:`legacy-${h.line}`,seq:h.line,timeMs:Math.round(h.time*1000),timeUncertaintyMs:h.timeUncertainty*1000,
      source:'agent',name:h.name,raw:h.command,args:h.args,units,refs:[],applications,legacy:true,
      x:h.args.x==null?null:Number(h.args.x),y:h.args.y==null?null:Number(h.args.y),
      targetId:h.args.target==null?null:Number(h.args.target),accepted:null,matchStatus:match?.status??null};
  }).sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
  // The receipt log is the complete command source for the evaluated run.  Its first/last
  // timestamps describe the observed span only; absence of a row outside that span means that
  // no command was issued, not that command evidence is missing.
  index.meta.commandCoverage={start:0,end:Infinity,
    observedStart:commands[0]?.timeMs??null,observedEnd:commands.at(-1)?.timeMs??null,complete:true};
  index.meta.legacyCommands=path.resolve(commandsFile);
  index.meta.commandCoverage.end+=commands.at(-1)?.timeUncertaintyMs||0;
  return commands;
}
class Session {
  constructor(meta,commands,overrides={},emit=()=>{}) {
    this.config=configure({X:meta.X??160,...overrides,legacyDetectors:overrides.legacyDetectors??false});this.meta=meta;this.commands=commands;
    this.units=new Map();this.deaths=new Map();this.regionNeighbors=new Map();this.matcher=new CommandMatcher(commands,this.config);
    this.commandCoverage=meta.commandCoverage??(meta.source==='replay'?null:{start:meta.timeMs??0,end:Infinity});
    this.output=emit;this.counter=0;this.now=meta.timeMs??0;this.counts={};this.statuses={};this.strict={};
    this.builderIntervals=[];this.latencies={};
    // 被拒命令是"世界模型过期"最直接的证据，但它常常分散在整局里，凑不出 C 类的突发窗口，
    // 所以单独按"拒绝率"统计一份，见 rejectionSummary()。
    this.rejections={issued:0,rejected:0,byCode:{},items:[]};
    this.diagnostics={inputKinds:{},detectors:{},commands:{total:commands.length,direct:this.matcher.commands.length}};
    this.detectors=detectors.createDetectors(this);this.economy=new EconomyRecorder(this.config.T_ECONOMY_SAMPLE);
  }
  nextId(type){return `${type}-${String(++this.counter).padStart(6,'0')}`;}
  diagnose(type,reason){const counts=this.diagnostics.detectors[type]??={};counts[reason]=(counts[reason]||0)+1;}
  covers(start,end){return this.commandCoverage!=null&&start>=this.commandCoverage.start&&end<=this.commandCoverage.end;}
  emit(event){
    this.matcher.finalize(event,event.t_end);
    // A rejected request has no observation window, so there is no economy background to attach.
    if(this.economyTimeline&&event.type!=='COMMAND_FAILURE')event.economy=this.economyTimeline.compare(event.t_start,event.t_end);
    this.counts[event.type]=(this.counts[event.type]||0)+1;
    const key=event.type+':'+event.status;this.statuses[key]=(this.statuses[key]||0)+1;
    if(event.strict)this.strict[event.type]=(this.strict[event.type]||0)+1;
    if(event.reactionMs!=null)(this.latencies[event.type]??=[]).push(event.reactionMs);
    if(event.type==='BUILDER_GAP'&&event.strict)this.builderIntervals.push({start:event.t_start,end:event.t_end,unitId:event.unit.id});
    // 建造者闲置（D）也计入并发统计：两个以上建造者同时闲着是系统性停摆，不是单点失误。
    // DETECTED 与 UNCERTAIN 都参与评分，所以都计入；时长只是下界的区间跳过，否则会低估并发。
    if(event.type==='BUILDER_IDLE'&&(event.status==='DETECTED'||event.status==='UNCERTAIN')
        &&event.unitId!=null&&!event.durationIsLowerBound)
      this.builderIntervals.push({start:event.t_start,end:event.t_end,unitId:event.unitId});
    this.output(event);
  }
  consume(row,command=null) {
    this.now=row.timeMs;
    this.matcher.now=this.now;
    // Commands from the receipt log are injected before the first row of the same instant, so no
    // application at that instant can already be known when the command is consumed.
    this.matcher.nowSeq=row.seq??-Infinity;
    this.diagnostics.inputKinds[row.kind]=(this.diagnostics.inputKinds[row.kind]||0)+1;
    if(row.kind==='unit')this.units.set(identity(row.unit),row.unit);
    if(row.kind==='death'){this.units.set(identity(row.unit),{...row.unit,alive:false});this.deaths.set(identity(row.unit),{timeMs:row.timeMs,seq:row.seq});}
    if(row.kind==='region')this.regionNeighbors.set(row.anchorId,row.nearby||[]);
    if(row.kind==='economy')this.economy.sample(row);
    if(row.kind==='result')this.result(row);
    if(command?.legacy){
      command.controlled=command.units.map(id=>[...this.units.values()].find(u=>u.id===id)).filter(Boolean);
      command.refs=command.controlled.filter(u=>u.alive===false||u.side!=='self').map(u=>({id:u.id,life:u.life,role:'controlled',
        status:u.alive===false?'dead':'wrong_team',checkedMs:command.timeMs,excluded:u.excluded??false}));
      command.targetUnit=[...this.units.values()].find(u=>u.id===command.targetId);
      if(command.targetUnit?.alive===false)command.refs.push({id:command.targetId,life:command.targetUnit.life,role:'target',status:'dead',checkedMs:command.timeMs});
      for(const ref of command.refs){const death=this.deaths.get(`${ref.id}:${ref.life??0}`);
        if(death?.timeMs===command.timeMs){ref.atReceipt=false;ref.deathTimeMs=null;}}
    }
    else if(command)this.receiptRefs(command);
    for(const detector of this.detectors){
      if(row.kind==='unit')detector.unit?.(row);
      if(row.kind==='damage')detector.damage?.(row);
      if(row.kind==='death')detector.death?.(row);
      if(row.kind==='native')detector.native?.(row);
      if(row.kind==='lifecycle')detector.lifecycle?.(row);
      if(row.kind==='idle')detector.idle?.(row);
      if(row.kind==='eventstream')detector.eventstream?.(row);
      if(row.kind==='command'&&command)detector.command?.(command);
    }
    if(row.kind==='frame')for(const detector of this.detectors)detector.advance?.(row.timeMs);
  }
  /**
   * Receipt-time lifecycle references for objects the request names but the collector did not record.
   * Only an object already known dead or outside the team at this instant is added: an unknown id or a
   * live unit stays unproven, so nothing is fabricated from the request arguments alone.
   */
  receiptRefs(command) {
    const args=argsObject(command),ids=new Set([...(command.units||[]),...(command.builders||[])]);
    for(const value of [args.units,args.unit,args.builder])for(const token of String(value??'').split(',')){
      const id=Number(token.trim());if(Number.isInteger(id)&&token.trim())ids.add(id);
    }
    const refs=command.refs??(command.refs=[]);
    for(const id of ids){
      if(refs.some(ref=>ref.id===id&&ref.role==='controlled'))continue;
      const unit=[...this.units.values()].find(u=>u.id===id);
      if(!unit||(unit.alive!==false&&unit.side==='self'))continue;
      const death=this.deaths.get(`${id}:${unit.life??0}`);
      refs.push({id,life:unit.life??0,role:'controlled',status:unit.alive===false?'dead':'wrong_team',
        checkedMs:command.timeMs,synthesized:true,deathTimeMs:death?.timeMs??null,deathSeq:death?.seq??null});
    }
  }
  /** 一条命令的最终回执。只有"被拒"才计入拒绝率，成功回执只用来做分母。 */
  result(row) {
    this.rejections.issued++;
    if (row.accepted !== false) return;
    this.rejections.rejected++;
    const code = row.code ?? null;
    if (code) this.rejections.byCode[code] = (this.rejections.byCode[code] || 0) + 1;
    this.rejections.items.push({timeMs: row.timeMs, commandId: row.commandId ?? null, code});
  }
  /**
   * 拒绝率 + 重复拒绝。
   *
   * <p>第一版只有 C 类（10 秒内 ≥3 批、250ms 一批）才出事件，一局里散落的十几次被拒会全部漏掉——
   * 实测一局 113 次调用里有 12 次被拒，C 类只报 1 条。这里补两个不依赖突发性的量：
   * <b>拒绝率</b>（被拒 / 有回执的命令）和 <b>重复拒绝</b>（30 秒内再次出现同一个原因码，
   * 说明它没从回执里改过来）。</p>
   */
  rejectionSummary() {
    const {issued, rejected, byCode, items} = this.rejections;
    const sorted = [...items].sort((a, b) => a.timeMs - b.timeMs);
    let repeated = 0;
    for (let i = 0; i < sorted.length; i++) {
      for (let j = i - 1; j >= 0; j--) {
        if (sorted[i].timeMs - sorted[j].timeMs > REJECT_REPEAT_MS) break;
        if (sorted[j].code === sorted[i].code) { repeated++; break; }
      }
    }
    return {issued, rejected, rate: issued ? Number((rejected / issued).toFixed(4)) : null, repeated, byCode};
  }
  finish(){for(const detector of this.detectors)detector.finish(this.now);}
  summary(complete,endReason=null) {
    const sweep=this.builderIntervals.flatMap(i=>[{time:i.start,delta:1},{time:i.end,delta:-1}]).sort((a,b)=>a.time-b.time||a.delta-b.delta);
    let active=0,last=0,overlap=0,max=0;
    for(const point of sweep){if(active>=2)overlap+=point.time-last;active+=point.delta;max=Math.max(max,active);last=point.time;}
    return {schema:1,team:this.meta.team,config:this.config,source:this.meta.source??'engine',complete,endReason,
      counts:this.counts,statuses:this.statuses,strict:this.strict,economySamples:this.economy.entries.length,
      economyComplete:this.economy.complete&&this.meta.timeMs===0,diagnostics:this.diagnostics,
      commandFailures:this.commandFailures??null,
      rejections:this.rejectionSummary(),
      builderOverlap:{milliseconds:overlap,maxConcurrent:max},
      responseLatencyMs:Object.fromEntries(Object.entries(this.latencies).map(([key,values])=>[key,{count:values.length,
        mean:values.reduce((a,b)=>a+b,0)/values.length,max:Math.max(...values)}])),
      limitations:['No final score or tactical-blame inference.',
        'Agent observation history is not recoverable from world visibility alone.',
        'Historical build placement is UNCERTAIN unless a matching placement proof was captured.',
        'COMMAND_FAILURE counts rejected requests; it is not a score and does not repeat STALE_COMMAND.',
        ...(this.meta.source==='replay'?['Replay does not contain CLI origin or rejected requests; supply original command evidence for attribution.']:[]),
        ...(!complete?['Evidence has no clean end marker; active events are truncated.']:[]),
        ...(endReason==='truncated_after_interference'?['Evidence stops at the recorded interference boundary; later game time has no evidence.']:[]),
        // A host abort (`rwx-game-abort`) stops the match without a result: the evidence is complete
        // up to that instant, but nothing after it exists, so late-game behaviour is not observable.
        ...(typeof endReason==='string'&&endReason.startsWith('host_abort')
          ?['The host aborted the match without a result; game time after the abort has no evidence.']:[])]};
  }
}
function eventText(e) {
  // The reply itself stays in events.jsonl; the timeline only needs identity and classification.
  if(e.type==='COMMAND_FAILURE')return `[${stamp(e.t_start)}] COMMAND_FAILURE | ${e.kind}/${e.category} | ${e.commandId} ${e.name}`
    +(e.staleObjects?.length?` | #${e.staleObjects.join(',#')}`:'')+(e.coveredByStaleCommand?' | covered_by_stale_command':'');
  if(e.type==='BUILDER_IDLE'||e.type==='FACTORY_IDLE')
    return `[${stamp(e.t_start)}-${stamp(e.t_end)}] ${e.type} | ${e.status} | ${e.tier}`
      +(e.unit?.type?` | ${e.unit.type}#${e.unitId}`:'')+` | idle=${(e.durationMs/1000).toFixed(3)}s`
      +` close=${e.closeReason}`+(e.referenceAction?` ref=${e.referenceAction.type}:${e.referenceAction.cost}`:'')
      +(e.agentState?` agent=${e.agentState}`:'')+(e.reason?` | ${e.reason}`:'');
  if(e.type==='GRINDER')
    return `[${stamp(e.t_start)}-${stamp(e.t_end)}] GRINDER | ${e.status} | ${e.operation}`
      +` | @(${e.location.x.toFixed(0)},${e.location.y.toFixed(0)}) delay=${(e.delayMs/1000).toFixed(1)}s`
      +` | lost ${e.consequence.type}#${e.consequence.unitId}`
      +(e.awareness?.aware?` | informed_by=${e.awareness.by}`:' | not_observed')
      +(e.agentState?` | agent=${e.agentState}`:'')+(e.reason?` | ${e.reason}`:'');
  if(e.type==='STALE_BURST')
    return `[${stamp(e.t_start)}-${stamp(e.t_end)}] STALE_BURST | ${e.status} | attempts=${e.attempts}`
      +` rejected=${e.count} | ${Object.entries(e.codes||{}).map(([code,n])=>code+'x'+n).join(',')}`
      +(e.reason?` | ${e.reason}`:'');
  const at=e.location?` | @(${e.location.x.toFixed(0)},${e.location.y.toFixed(0)};r=${e.location.r.toFixed(0)})`:'';
  return `[${stamp(e.t_start)}-${stamp(e.t_end)}] ${e.type}${at} | ${e.status}`
    +(e.kind?` | ${e.kind}`:'')
    +(e.unit?` | ${e.unit.type}#${e.unit.id}`:'')+(e.unitId!=null?` | #${e.unitId} count=${e.count??0}`:'')
    +(e.gapMs!=null?` | idle=${(e.gapMs/1000).toFixed(3)}s B=${e.B_count}`:'')
    +(e.reactionMs!=null?` | first=${(e.reactionMs/1000).toFixed(3)}s`:'')
    +(e.agentState?` | agent=${e.agentState}`:'')
    +(e.flags?.length?` | ${e.flags.join(',')}`:'')+(e.reason?` | ${e.reason}`:'');
}
async function evaluateEvents(file,{out,config={},commands:commandsFile,economy:economyFile,replay:replayFile}={}) {
  if(!commandsFile||!economyFile)throw Error('--commands commands.txt and --economy economy.log are required');
  if(config.T_ECONOMY_SAMPLE!=null&&config.T_ECONOMY_SAMPLE!==1000)throw Error('economy.log requires T_ECONOMY_SAMPLE=1000');
  for(const input of [file,commandsFile,economyFile,...(replayFile?[replayFile]:[])])if(!fs.statSync(input).isFile())throw Error('Input is not a file: '+input);
  const timeline=new EconomyTimeline(economyFile),index=await indexCommands(file);out??=stageDirectory(commandsFile,2);
  if(config.X!=null&&config.X!==index.meta.X)throw Error('X differs from captured spatial evidence; recapture with this configuration');
  if(config.T_REGION_HOLD!=null&&index.meta.regionHoldMs!=null&&config.T_REGION_HOLD!==index.meta.regionHoldMs)
    throw Error('T_REGION_HOLD differs from captured region retention; recapture with this configuration');
  const legacy=legacyCommands(index,commandsFile,replayFile);
  if(path.resolve(out)===path.dirname(path.resolve(file)))throw Error('Use a separate report directory to preserve evidence');
  const names=['events.jsonl','events.txt','summary.json','economy.log'];
  const inputs=[file,commandsFile,economyFile,...(replayFile?[replayFile]:[])].map(p=>path.resolve(p));
  if(names.some(n=>inputs.includes(path.resolve(out,n))))throw Error('Report output would overwrite an input file');
  const tempRoot=path.resolve(__dirname,'../agent-tmp');fs.mkdirSync(tempRoot,{recursive:true});
  const staging=fs.mkdtempSync(path.join(tempRoot,'stage2-report-'));
  const json=fs.openSync(path.join(staging,'events.jsonl'),'w'),txt=fs.openSync(path.join(staging,'events.txt'),'w');let closed=false;
  const session=new Session(index.meta,legacy,config,event=>{
    fs.writeSync(json,JSON.stringify(event)+'\n');fs.writeSync(txt,eventText(event)+'\n');
  });
  session.economyTimeline=timeline;
  session.diagnostics.commands.synthesized=legacy.filter(c=>c.legacy).length;
  session.diagnostics.commands.withExecution=legacy.filter(c=>(c.applications||[]).length>0).length;
  try{
    let nextLegacy=0;
    for await(const row of records(file)){
      if(row.kind==='economy'){
        const sample=timeline.at(row.sampleMs);
        if(!sample||sample.sampleMs!==row.sampleMs||Math.abs(sample.funds-row.funds)>1e-6||Math.abs(sample.income-row.income)>1e-6)
          throw Error('economy.log does not match telemetry at '+row.sampleMs+'ms');
      }
      while(nextLegacy<legacy.length&&(legacy[nextLegacy].timeMs<row.timeMs||legacy[nextLegacy].timeMs===row.timeMs&&(legacy[nextLegacy].legacy||legacy[nextLegacy].seq<=row.seq))){
        const command=legacy[nextLegacy++];session.consume({kind:'command',timeMs:command.timeMs},command);
      }
      if(row.kind==='native'&&row.phase==='after')for(const r of row.results||[])
        r.batchCount=index.batches.get(`${row.commandId??row.nativeId}:${r.unitId}:${row.tick}`);
      // Commands are consumed once from the required receipt log; embedded records only enrich evidence.
      if(row.kind==='command'){session.diagnostics.inputKinds.embedded_command=(session.diagnostics.inputKinds.embedded_command||0)+1;continue;}
      session.consume(row,null);
    }
    if(timeline.samples.length!==session.economy.entries.length||index.meta.timeMs!==0)throw Error('economy.log requires matching complete per-second telemetry starting at game time zero');
    session.finish();
    const summary=session.summary(index.ended,index.endReason);
    summary.inputs={telemetry:path.resolve(file),commands:path.resolve(commandsFile),economy:path.resolve(economyFile),replay:replayFile?path.resolve(replayFile):null};
    fs.writeFileSync(path.join(staging,'summary.json'),JSON.stringify(summary,null,2)+'\n');
    fs.writeFileSync(path.join(staging,'economy.log'),timeline.text());
    fs.closeSync(json);fs.closeSync(txt);closed=true;
    fs.mkdirSync(out,{recursive:true});for(const name of names)fs.copyFileSync(path.join(staging,name),path.join(out,name));
    return {out,summary};
  }finally{if(!closed){fs.closeSync(json);fs.closeSync(txt);}
    if(!path.resolve(staging).startsWith(tempRoot+path.sep))throw Error('Unexpected report temporary directory');
    fs.rmSync(staging,{recursive:true,force:true});}
}
if(require.main===module){
  const args=process.argv.slice(2),file=args.shift();
  if(!file){console.error('Usage: node evaluate/evaluate_stage_2.js <telemetry.jsonl> --commands commands.txt --economy economy.log [--replay replay.jsonl] [--out directory] [--config config.json]');process.exitCode=1;}
  else{const options={};while(args.length){const flag=args.shift(),value=args.shift();if(flag==='--out')options.out=value;
    else if(flag==='--config')options.config=JSON.parse(fs.readFileSync(value,'utf8'));else if(['--commands','--replay','--economy'].includes(flag))options[flag.slice(2)]=value;else throw Error(`Unknown option ${flag}`);}
    evaluateEvents(file,options).then(r=>console.log(JSON.stringify(r.summary,null,2))).catch(e=>{console.error(e.message);process.exitCode=1;});}
}
module.exports={Session,records,indexCommands,legacyCommands,evaluateEvents,eventText};
