'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {Session,evaluateEvents}=require('../evaluate_stage_2');
const {EconomyTimeline}=require('../events/economy');
const unit=(id,extra={})=>({id,life:0,type:'tank',side:'self',alive:true,complete:true,idle:true,
  x:0,y:0,canAttack:true,building:false,mobile:true,task:null,queue:[],...extra});
function run(commands=[]){
  const events=[],s=new Session({schema:1,team:0,timeMs:0},commands,{},e=>events.push(e));let seq=0;
  return {s,events,row:(kind,timeMs,fields={})=>s.consume({kind,timeMs,seq:++seq,...fields},fields.command),
    finish:t=>{s.now=t;s.finish();return events;}};
}
function ambush(h){for(let t=0;t<=5000;t+=1000){h.row('damage',t,{attacker:unit(2,{side:'enemy',x:50}),victim:unit(1)});h.row('frame',t);}}
const command=(id,timeMs,extra={})=>({id,timeMs,seq:2,source:'agent',kind:'command',name:'rwx-game-order-attack',
  units:[1],targetId:2,operation:'attack',raw:'rwx-game-order-attack --units 1 --target 2',applications:[],...extra});
test('AMBUSH uses spatial relation rather than the telemetry kind field',()=>{
  const c=command('c',5200,{targetId:99}),h=run([c]);ambush(h);h.row('command',5200,{command:c});
  assert.equal(h.finish(6000)[0].t_first_B,null);
  const related=command('r',5200),x=run([related]);ambush(x);x.row('command',5200,{command:related});
  assert.equal(x.finish(6000)[0].t_first_B,5200);
});
test('future applications do not leak into active or already ended AMBUSH events',()=>{
  const c=command('c',5200,{applications:[{applied:true,timeMs:10000}]}),h=run([c]);ambush(h);
  h.row('command',5200,{command:c});
  assert.equal(h.s.detectors[0].regions[0].event.t_first_applied,null);
  h.row('frame',8001);const e=h.events[0];assert.equal(e.t_first_applied,null);
  assert.deepEqual(e.commands[0].applications,[]);
});
test('a real application inside the event is retained at settlement, but never before issuance',()=>{
  const c=command('c',5200,{applications:[{applied:true,timeMs:5100},{applied:true,timeMs:5300}]}),h=run([c]);ambush(h);
  h.row('command',5200,{command:c});assert.equal(h.s.detectors[0].regions[0].event.t_first_applied,null);
  assert.equal(h.finish(6000)[0].t_first_applied,5300);
});
test('an application later in the same tick cannot predate event closure',()=>{
  const c=command('same',5200,{applications:[{applied:true,timeMs:8001,seq:999}]}),h=run([c]);ambush(h);
  h.row('command',5200,{command:c});h.row('frame',8001);
  assert.equal(h.events[0].t_first_applied,null);
});
test('receipt-log commands without a sequence precede every row of the same instant',()=>{
  const same=command('log',5200,{applications:[{applied:true,timeMs:5200,seq:999}]}),h=run([same]);ambush(h);
  h.s.consume({kind:'command',timeMs:5200},same);
  assert.equal(h.s.detectors[0].regions[0].event.t_first_B,5200);
  assert.equal(h.s.detectors[0].regions[0].event.t_first_applied,null);
  assert.equal(h.finish(6000)[0].t_first_applied,5200);
});
test('merging UNKNOWN and DANGER FEED regions recalculates the merged state',()=>{
  const h=run();for(const [id,x]of [[1,0],[2,600],[3,300]]){
    const enemy=unit(90+id,{side:'enemy',x:x+10});h.row('death',id*1000,{unit:unit(id,{x}),killer:enemy,nearby:[enemy]});
  }
  const e=h.finish(4000).find(e=>e.type==='FEED');assert.equal(e.state,'REPEATED');assert.equal(e.extra.tank,1);
  assert.equal(e.losses.tank,3);assert.equal(e.t_confirm,3000);
  assert.equal(h.s.diagnostics.detectors.FEED.regions_merged,2);
});
function factory({proof=true,task=null,cancel=false}={}){
  const h=run(),f=unit(7,{factory:true,building:true,mobile:false});
  h.row('unit',0,{unit:{...f,idle:false,queue:[{kind:'produce',action:'tank',count:1}]}});
  if(proof)h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});
  if(cancel)h.row('native',1000,{phase:'after',operation:'action',source:'agent',results:[{unitId:7,applied:true,queueAdded:[]}]});
  h.row('unit',1000,{funds:1000,unit:{...f,task,actions:{tank:{kind:'produce',cost:100,available:true,affordable:true}}}});
  h.row('native',6000,{phase:'after',source:'agent',results:[{unitId:7,queueAdded:[{kind:'produce',action:'tank',count:1}]}]});
  return h;
}
test('stationary factories with active tasks are not idle',()=>assert.equal(factory({task:{type:'repair'}}).events.length,0));
test('queue disappearance without actual completion proof is never a strict gap',()=>{
  const e=factory({proof:false}).events[0];assert.equal(e.strict,false);assert.equal(e.reason,'actual_production_completion_unproven');
  assert.equal(factory().events[0].strict,true);
});
test('queue cancellation does not masquerade as production completion',()=>assert.equal(factory({proof:false,cancel:true}).events.length,0));
test('strict upper bound remains ten seconds; discarded long/unclosed gaps are diagnostic',()=>{
  const h=run(),f=unit(7,{factory:true,mobile:false});h.row('unit',0,{unit:{...f,queue:[{kind:'produce'}]}});
  h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});h.row('unit',1000,{unit:f,funds:1000});
  h.row('unit',11000,{unit:f,funds:1000});h.finish(12000);
  assert.equal(h.events.length,0);assert.equal(h.s.diagnostics.detectors.PRODUCTION_GAP.expired_at_upper_bound,1);
});
test('offline evaluation requires both external logs and compares income around events',async()=>{
  await assert.rejects(()=>evaluateEvents('missing'),/--commands.*--economy/);
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});const dir=fs.mkdtempSync(path.join(root,'stage2-fix-'));
  try{
    const economy=path.join(dir,'economy.log'),commands=path.join(dir,'commands-test.txt'),file=path.join(dir,'telemetry.jsonl');
    fs.writeFileSync(economy,'1000(+20),1100(+30)\n');
    const timeline=new EconomyTimeline(economy);assert.equal(timeline.compare(100,1500).incomeDelta,10);
    fs.writeFileSync(commands,'[00:00:000]<name=x;player=0;faction=A;role=player;match=1><rwx-game-state>\n');
    const rows=[{kind:'meta',schema:1,team:0,timeMs:0,seq:1},{kind:'economy',timeMs:0,seq:2,sampleMs:0,funds:1000,income:20},
      {kind:'economy',timeMs:1000,seq:3,sampleMs:1000,funds:1100,income:30},{kind:'end',timeMs:1500,seq:4}];
    fs.writeFileSync(file,rows.map(r=>JSON.stringify(r)).join('\n'));
    await assert.rejects(()=>evaluateEvents(file,{commands}),/--economy/);
    const result=await evaluateEvents(file,{commands,economy,out:path.join(dir,'result')});
    assert.equal(result.summary.economySamples,2);assert.equal(result.summary.diagnostics.inputKinds.economy,2);
    fs.writeFileSync(economy,'1000(+20),1100(+31)\n');
    await assert.rejects(()=>evaluateEvents(file,{commands,economy,out:path.join(dir,'bad')}),/does not match/);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
// B-81: builder #218 died at 443.904s and the Agent ordered it to build at 450.144s. The request was
// rejected, but the receipt log alone carries no object lifecycle, so the builder has to come from the
// `--builder` argument plus the telemetry death row.
const rejection=(id,ms,name,args,extra={})=>({id,ms,name,args,raw:[name,...args].join(' '),units:[],refs:[],...extra});
const REJECTED=[
  rejection('B-18',106144,'rwx-game-order-action',['--units','18','--action','builder','--count','1'],{units:[18],
    refs:[{id:18,life:0,role:'controlled',status:'valid',checkedMs:106144,atReceipt:true,eligibleKnown:true,excluded:false}],
    reply:"error: Unit 18 (landFactory) has no action 'builder'; available now: c_1,110,u_builder,u_scout,u_c_tank,u_hoverTank. List them with rwx-game-unit-actions --unit 18"}),
  rejection('B-57',322880,'rwx-game-order-attack-move',['--units','125,143,184','--x','1310','--y','2210'],{units:[125,143,184],
    refs:[{id:125,life:0,role:'controlled',status:'dead',checkedMs:322880,atReceipt:true,eligibleKnown:true,deathTimeMs:314240,deathSeq:22667},
      {id:143,life:0,role:'controlled',status:'dead',checkedMs:322880,atReceipt:true,eligibleKnown:true,deathTimeMs:317232,deathSeq:22939},
      {id:184,life:0,role:'controlled',status:'dead',checkedMs:322880,atReceipt:true,eligibleKnown:true,deathTimeMs:309424,deathSeq:22327}],
    reply:'error: No controllable unit remains; skipped dead/missing ids: 125,143,184'}),
  rejection('B-77',442272,'rwx-game-order-repair',['--units','218','--target','78'],{units:[218],targetId:78,
    refs:[{id:218,life:0,role:'controlled',status:'valid',checkedMs:442272,atReceipt:true,eligibleKnown:true,excluded:false},
      {id:78,life:0,role:'target',status:'dead',checkedMs:442272,atReceipt:true,eligibleKnown:true,deathTimeMs:441856,deathSeq:33369}],
    reply:'error: Target is not currently observable (dead or hidden): 78'}),
  rejection('B-78',442544,'rwx-game-unit-show',['--id','78'],{reply:'error: Unit is not currently observable: 78'}),
  // No builders/refs: the old collector recorded neither, which is the shape that lost #218.
  rejection('B-81',450144,'rwx-game-order-build',['--builder','218','--type','landFactory','--x','1130','--y','2250','--auto-place'],
    {reply:'error: No controllable unit remains; skipped dead/missing ids: 218'}),
  rejection('B-82',450320,'rwx-game-unit-list',['--owner','self','--building-acting','--full','--compact'],
    {reply:'error: Unknown option: --building-acting'}),
  rejection('B-96',543056,'rwx-game-keep-set',['--unit','1465','--action','u_c_laserTank','--count','2'],{units:[1465],
    refs:[{id:1465,life:0,role:'controlled',status:'valid',checkedMs:543056,atReceipt:true,eligibleKnown:true,excluded:false}],
    reply:"error: Unit 1465 (landFactory) has no action 'u_c_laserTank' right now; available now: c_1,110,u_builder,u_scout,u_c_tank,u_hoverTank"}),
  rejection('B-98',543504,'rwx-game-order-build',['--builder','1245','--type','extractorT1','--x','1110','--y','2190','--auto-place'],
    {reply:'error: Cannot place extractorT1 anywhere within 2 tiles of 1110,2190: cannot place here at 1110,2190: 位置被阻挡（地形、建筑或单位占位）；blockedBy: extractorT1 id=1530 team=1 at 1110,2190'}),
  rejection('B-104',590512,'rwx-game-keep-set',['--unit','1465','--action','u_c_laserTank','--count','3'],{units:[1465],
    refs:[{id:1465,life:0,role:'controlled',status:'valid',checkedMs:590512,atReceipt:true,eligibleKnown:true,excluded:false}],
    reply:"error: Unit 1465 (landFactory) has no action 'u_c_laserTank' right now（正在升级：Upgrade - T2 98%，升级完成后再建规则）; available now: c_1,110,u_builder,u_scout,u_c_tank,u_hoverTank"}),
];
const accepted=(id,ms,name,args,extra={})=>({id,ms,name,args,raw:[name,...args].join(' '),units:[],refs:[],accepted:true,reply:'accepted',...extra});
function rejectionMatch(dir,extras=[]){
  const all=[...REJECTED,...extras].sort((a,b)=>a.ms-b.ms);
  const clock=ms=>`[${String(Math.floor(ms/60000)).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}:${String(ms%1000).padStart(3,'0')}]`;
  const commands=path.join(dir,'commands-Ice-Island-test.txt'),economy=path.join(dir,'economy.log'),file=path.join(dir,'telemetry.jsonl');
  fs.writeFileSync(commands,all.map(r=>`${clock(r.ms)}<name=Player;player=0;faction=A(0);role=player;match=1><${r.raw}>`).join('\n')+'\n');
  const samples=[];for(let ms=0;ms<=590000;ms+=1000)samples.push(ms);
  const lines=[];for(let i=0;i<samples.length;i+=60)lines.push(samples.slice(i,i+60).map(()=>'1000(+60)').join(','));
  fs.writeFileSync(economy,lines.join('\n')+'\n');
  const rows=[{kind:'meta',timeMs:0,schema:1,team:0,X:160,startComplete:true}];
  for(const ms of samples)rows.push({kind:'economy',timeMs:ms,sampleMs:ms,funds:1000,income:60});
  rows.push({kind:'death',timeMs:443904,unit:{id:218,life:0,type:'builder',side:'self',alive:false,x:1000,y:2000,visible:true},killer:null,nearby:[]});
  for(const r of all){
    rows.push({kind:'command',timeMs:r.ms,id:r.id,source:'agent',name:r.name,raw:r.raw,args:r.args,units:r.units,refs:r.refs,
      ...(r.builders?{builders:r.builders}:{}),x:null,y:null,targetId:r.targetId??null});
    rows.push({kind:'result',timeMs:r.ms,commandId:r.id,accepted:r.accepted===true,reply:r.reply});
  }
  rows.push({kind:'end',timeMs:591000,reason:'match_result'});
  rows.sort((a,b)=>a.timeMs-b.timeMs);
  rows.forEach((row,index)=>{row.seq=index+1;row.tick=Math.floor(row.timeMs/16);});
  fs.writeFileSync(file,rows.map(r=>JSON.stringify(r)).join('\n')+'\n');
  return {file,commands,economy};
}
test('B-81 dead builder is captured through --builder and counted once with STALE_COMMAND',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'stage2-b81-'));
  try{
    const {file,commands,economy}=rejectionMatch(dir);
    const result=await evaluateEvents(file,{commands,economy,out:path.join(dir,'out')});
    const events=fs.readFileSync(path.join(result.out,'events.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
    const stale=events.find(e=>e.type==='STALE_COMMAND'&&e.unitId===218);
    assert.ok(stale,'B-81 must produce a STALE_COMMAND for #218');
    assert.equal(stale.strict,true);assert.deepEqual(stale.command_ids,['B-81']);
    assert.deepEqual(stale.roles,['controlled']);
    const failure=events.find(e=>e.type==='COMMAND_FAILURE'&&e.commandId==='B-81');
    assert.equal(failure.kind,'operation');assert.equal(failure.category,'STALE_OBJECT');
    assert.deepEqual(failure.staleObjects,[218]);assert.equal(failure.coveredByStaleCommand,true);
    assert.equal(failure.accepted,false);assert.equal(failure.args[0],'--builder');
    assert.match(failure.reply,/skipped dead\/missing ids: 218/);
    assert.equal(failure.economy,undefined);
    assert.equal(result.summary.counts.STALE_COMMAND,5);
    assert.equal(result.summary.diagnostics.commands.withExecution,0);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('rejected requests are classified, split by kind, and never double counted',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'stage2-failures-'));
  try{
    const {file,commands,economy}=rejectionMatch(dir);
    const result=await evaluateEvents(file,{commands,economy,out:path.join(dir,'out')});
    const summary=result.summary,stats=summary.commandFailures;
    assert.equal(stats.total,9);assert.equal(stats.operation,5);assert.equal(stats.configuration,2);assert.equal(stats.query,2);
    assert.deepEqual(stats.byCategory,{INVALID_ACTION:1,STALE_OBJECT:3,UNAVAILABLE_ACTION:2,PLACEMENT_BLOCKED:1});
    assert.equal(stats.coveredByStaleCommand,3);assert.equal(stats.additional,4);
    assert.equal(stats.coveredByStaleCommand+stats.additional,stats.operation+stats.configuration);
    assert.equal(summary.counts.COMMAND_FAILURE,7);
    // Strict view: only the uncorrected tool error survives; timing and the blocked placement are out.
    assert.equal(stats.actionable.count,1);
    assert.deepEqual(stats.actionable.remaining.map(r=>r.commandId),['B-18']);
    assert.deepEqual(stats.actionable.excluded,{staleCommand:3,duplicateSignature:0,recoveredRetry:0,
      timing:2,objectContinued:0,unprovenAvoidability:1});
    // Queries are diagnostics: no event, but the rejection and its reply are still recorded.
    assert.deepEqual(stats.queries.map(q=>q.commandId),['B-78','B-82']);
    assert.equal(stats.queries[1].category,'INVALID_ARGUMENT');
    const events=fs.readFileSync(path.join(result.out,'events.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
    assert.ok(!events.some(e=>e.type==='COMMAND_FAILURE'&&['B-78','B-82'].includes(e.commandId)));
    assert.deepEqual(events.filter(e=>e.type==='COMMAND_FAILURE').map(e=>e.commandId).sort(),
      ['B-104','B-18','B-57','B-77','B-81','B-96','B-98']);
    // accepted=true is not applied=true: nothing is derived from a successful receipt here.
    assert.ok(events.filter(e=>e.type==='COMMAND_FAILURE').every(e=>e.accepted===false));
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('clean, truncated and missing end markers keep their own completeness state',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'stage2-complete-'));
  try{
    const commands=path.join(dir,'commands-test.txt'),economy=path.join(dir,'economy.log'),file=path.join(dir,'telemetry.jsonl');
    fs.writeFileSync(commands,'[00:00:000]<name=x;player=0;faction=A;role=player;match=1><rwx-game-state>\n');
    fs.writeFileSync(economy,'1000(+20)\n');
    const base=[{kind:'meta',schema:1,team:0,timeMs:0,seq:1},{kind:'economy',timeMs:0,seq:2,sampleMs:0,funds:1000,income:20}];
    fs.writeFileSync(file,[...base,{kind:'end',timeMs:0,seq:3,reason:'match_result'}].map(r=>JSON.stringify(r)).join('\n'));
    const clean=await evaluateEvents(file,{commands,economy,out:path.join(dir,'clean')});
    assert.equal(clean.summary.complete,true);
    assert.ok(!clean.summary.limitations.some(l=>l.includes('clean end marker')));
    fs.writeFileSync(file,[...base,{kind:'end',timeMs:0,seq:3,reason:'truncated_after_interference'}].map(r=>JSON.stringify(r)).join('\n'));
    const truncated=await evaluateEvents(file,{commands,economy,out:path.join(dir,'truncated')});
    assert.equal(truncated.summary.complete,true);
    assert.ok(truncated.summary.limitations.some(l=>l.includes('interference boundary')));
    fs.writeFileSync(file,[...base,{kind:'end',timeMs:0,seq:3,reason:'observation_error'}].map(r=>JSON.stringify(r)).join('\n'));
    const broken=await evaluateEvents(file,{commands,economy,out:path.join(dir,'broken')});
    assert.equal(broken.summary.complete,false);
    assert.ok(broken.summary.limitations.some(l=>l.includes('no clean end marker')));
    fs.writeFileSync(file,base.map(r=>JSON.stringify(r)).join('\n'));
    const missing=await evaluateEvents(file,{commands,economy,out:path.join(dir,'missing')});
    assert.equal(missing.summary.complete,false);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});

test('repeats and transient retries are filtered out of the statistic only',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'stage2-filter-'));
  try{
    const buildArgs=['--builder','1245','--type','extractorT1','--x','700','--y','700','--auto-place'];
    const actionArgs=['--units','18','--action','u_builder','--count','1'];
    const {file,commands,economy}=rejectionMatch(dir,[
      rejection('B-200',200000,'rwx-game-keep-set',['--unit','1465','--action','u_c_tank','--count','1'],{units:[1465],reply:'error: keep rule rejected'}),
      rejection('B-201',200400,'rwx-game-keep-set',['--unit','1465','--action','u_c_tank','--count','1'],{units:[1465],reply:'error: keep rule rejected'}),
      rejection('B-202',210000,'rwx-game-order-build',buildArgs,{reply:'error: not enough funds'}),
      accepted('B-203',212000,'rwx-game-order-build',buildArgs),
      rejection('B-204',220000,'rwx-game-order-action',actionArgs,{units:[18],reply:'error: queue full'}),
      accepted('B-205',240000,'rwx-game-order-action',actionArgs,{units:[18]}),
    ]);
    const result=await evaluateEvents(file,{commands,economy,out:path.join(dir,'out')});
    const stats=result.summary.commandFailures;
    assert.equal(stats.total,13);assert.equal(stats.operation,7);assert.equal(stats.configuration,4);assert.equal(stats.query,2);
    assert.deepEqual(stats.filtered.excluded,{staleCommand:3,duplicateSignature:1,recoveredRetry:1});
    assert.equal(stats.filtered.count,6);
    assert.deepEqual(stats.filtered.remaining.map(r=>r.commandId).sort(),['B-104','B-18','B-200','B-204','B-96','B-98']);
    // Strict view: B-18 has no corrected follow-up here, everything else is timing or unproven.
    assert.equal(stats.actionable.count,1);
    assert.deepEqual(stats.actionable.remaining.map(r=>r.commandId),['B-18']);
    assert.deepEqual(stats.actionable.excluded,{staleCommand:3,duplicateSignature:1,recoveredRetry:1,
      timing:2,objectContinued:0,unprovenAvoidability:3});
    const events=fs.readFileSync(path.join(result.out,'events.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(events.filter(e=>e.type==='COMMAND_FAILURE').length,11);
    assert.equal(events.find(e=>e.commandId==='B-200').duplicateSignature,false);
    assert.equal(events.find(e=>e.commandId==='B-201').duplicateSignature,true);
    assert.equal(events.find(e=>e.commandId==='B-202').recoveredRetry,true);
    assert.equal(events.find(e=>e.commandId==='B-204').recoveredRetry,false);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('a corrected follow-up retires the tool error and leaves no actionable rejection',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'stage2-actionable-'));
  try{
    // B-18 used `--action builder`; three seconds later the same unit was ordered with `u_builder`.
    const {file,commands,economy}=rejectionMatch(dir,[
      accepted('B-19',109376,'rwx-game-order-action',['--units','18','--action','u_builder','--count','1'],{units:[18]}),
    ]);
    const result=await evaluateEvents(file,{commands,economy,out:path.join(dir,'out')});
    const stats=result.summary.commandFailures;
    assert.equal(stats.total,9);
    assert.equal(stats.actionable.count,0);
    assert.deepEqual(stats.actionable.excluded,{staleCommand:3,duplicateSignature:0,recoveredRetry:0,
      timing:2,objectContinued:1,unprovenAvoidability:1});
    const events=fs.readFileSync(path.join(result.out,'events.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(events.find(e=>e.commandId==='B-18').objectContinued,true);
    assert.equal(events.find(e=>e.commandId==='B-98').objectContinued,false);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
