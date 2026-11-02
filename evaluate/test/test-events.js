'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {Session,evaluateEvents,indexCommands,legacyCommands,records}=require('../evaluate_stage_2');
const {configure}=require('../events/config');
const {EconomyRecorder,stamp}=require('../events/model');
const unit=(id,extra={})=>({id,life:0,type:'tank',side:'self',x:0,y:0,alive:true,complete:true,
  visible:true,idle:true,canAttack:true,building:false,mobile:true,builder:false,factory:false,task:null,queue:[],...extra});
const b=(id,timeMs,extra={})=>({id,timeMs,seq:id,source:'agent',name:'rwx-game-order-move',operation:'move',
  units:[1],x:1000,y:1000,controlled:[unit(1)],refs:[],...extra});
function harness(commands=[],config={}) {
  const events=[],session=new Session({schema:1,team:0,timeMs:0,X:160},commands,{legacyDetectors:true,...config},e=>events.push(e));let seq=0;
  return {session,events,row:(kind,timeMs,fields={})=>session.consume({kind,timeMs,tick:timeMs,seq:++seq,...fields},kind==='command'?fields.command:null),
    end:time=>{session.now=time;session.finish();return events;}};
}
function attacks(h,start=0,until=6000,attacker=2,victim=1) {
  for(let time=start;time<=until;time+=1000){h.row('damage',time,{attacker:unit(attacker,{side:'enemy',x:100}),victim:unit(victim)});h.row('frame',time);}
}
test('configuration validates all centralized parameters and formats milliseconds',()=>{
  assert.equal(configure().X,160);assert.throws(()=>configure({X:0}));assert.throws(()=>configure({typo:1}));
  assert.throws(()=>configure({T_GAP_MIN:11000}));assert.equal(stamp(3661007),'01:01:01.007');
});
test('ambush confirms at five seconds and starts at first damage',()=>{
  const h=harness();attacks(h);const e=h.end(7000).find(e=>e.type==='AMBUSH');
  assert.equal(e.t_start,0);assert.equal(e.t_confirm,5000);assert.equal(e.status,'UNANSWERED');assert.equal(e.truncated,true);
});
test('short ambush and normal economic buildings do not become confirmed events',()=>{
  const h=harness();attacks(h,0,4000);h.end(4000);assert.equal(h.events.length,0);
  const x=harness();for(let t=0;t<=6000;t+=1000)x.row('damage',t,{attacker:unit(2,{side:'enemy'}),victim:unit(1,{building:true,canAttack:false})});
  assert.equal(x.end(6000).length,0);
});
test('counterfire removes the attacker without attributing an Agent response',()=>{
  const h=harness();attacks(h,0,3000);h.row('damage',3000,{attacker:unit(1),victim:unit(2,{side:'enemy'})});h.row('frame',3000);
  assert.equal(h.end(7000).length,0);
});
test('ambush relation times cannot be inherited by new attackers',()=>{
  const h=harness();attacks(h,0,2000);attacks(h,3000,6000,3,1);h.row('frame',6000);assert.equal(h.end(6000).length,0);
});
test('intersecting ambush circles merge transitively',()=>{
  const h=harness();
  for(let t=0;t<=6000;t+=1000)for(const[id,x]of [[1,0],[3,300],[5,600]]){
    h.row('damage',t,{attacker:unit(id+1,{side:'enemy',x:x+10}),victim:unit(id,{x})});
    h.row('frame',t);
  }
  assert.equal(h.end(7000).filter(e=>e.type==='AMBUSH').length,1);
  assert.equal(h.events[0].self.tank,3);
});
test('victim leaving idle expires only after three seconds',()=>{
  const h=harness();attacks(h,0,5000);h.row('unit',5000,{unit:unit(1,{idle:false,task:{type:'move'}})});
  h.row('frame',5000);h.row('frame',7999);assert.equal(h.events.length,0);h.row('frame',8000);
  assert.equal(h.events.length,1);
});
test('attacker inactivity expires after three seconds and deaths retain losses',()=>{
  const h=harness();attacks(h,0,5000);h.row('frame',8000);assert.equal(h.events.length,0);h.row('frame',8001);assert.equal(h.events.length,1);
  const x=harness();attacks(x,0,5000);x.row('death',5100,{unit:unit(1),killer:unit(2,{side:'enemy'}),nearby:[]});x.row('frame',5100);
  assert.equal(x.events.find(e=>e.type==='AMBUSH').losses.tank,1);
});
test('ambush keeps own losses and kills apart without changing the losses union',()=>{
  const h=harness();attacks(h,0,5000);
  h.row('death',5100,{unit:unit(1),killer:unit(2,{side:'enemy'}),nearby:[]});
  h.row('death',5200,{unit:unit(2,{side:'enemy'}),killer:unit(1),nearby:[]});
  h.row('frame',5200);
  const e=h.end(6000).find(e=>e.type==='AMBUSH');
  assert.deepEqual(e.losses,{tank:2});
  assert.deepEqual(e.lossesSelf,{tank:1});
  assert.deepEqual(e.lossesEnemy,{tank:1});
});
test('explicit group response retains issued and applied times plus three second tail',()=>{
  const commands=[b(1,5200,{operation:'attackMove',x:10,y:0,applications:[{applied:true,timeMs:5300}]}),b(2,8200),b(3,8201)];
  const h=harness(commands);attacks(h,0,5000);
  for(const command of commands)h.row('command',command.timeMs,{command});
  const e=h.end(9000).find(e=>e.type==='AMBUSH');assert.equal(e.t_first_B,5200);assert.equal(e.t_first_applied,5300);assert.equal(e.commands.length,2);
});
test('keep automatic operations and unrelated B commands are not ambush responses',()=>{
  const h=harness();attacks(h,0,5000);
  for(const command of[b(1,5200,{source:'keep'}),b(2,5300,{units:[9],controlled:[unit(9)]})])h.row('command',command.timeMs,{command});
  assert.equal(h.end(6000)[0].t_first_B,null);
});
function builderGap(duration,next={type:'repair',targetId:9},commands=[],start={}) {
  const h=harness(commands),base=unit(1,{type:'builder',builder:true,canAttack:false});
  h.row('unit',0,{unit:{...base,idle:false,task:{type:'build',buildType:'turret'}}});
  h.row('unit',1000,{funds:1000,unit:{...base,repairable:[9],...start}});
  h.row('unit',1000+duration,{unit:{...base,idle:false,task:next}});h.end(1000+duration);return h;
}
for(const [gap,expected]of [[2900,0],[3000,0],[5000,1],[9900,1],[10000,0]])test(`builder gap strict boundary ${gap}`,()=>assert.equal(builderGap(gap).events.length,expected));
test('builder gap uses task end rather than two task assignment times',()=>{
  const h=builderGap(5000);assert.equal(h.events[0].t_start,1000);assert.equal(h.events[0].strict,true);
});
test('builder B interval includes its left endpoint and excludes the next assignment',()=>{
  assert.equal(builderGap(5000,undefined,[b(1,1000)]).events[0].strict,false);
  assert.equal(builderGap(5000,undefined,[b(1,6000)]).events[0].strict,true);
});
test('gap B counts stay global while reporting whether the command named the object',()=>{
  const related=builderGap(5000,undefined,[b(1,2000,{units:[1],controlled:[unit(1)]})]).events[0];
  assert.equal(related.B_count,1);assert.equal(related.B_related,1);
  assert.equal(related.B_unknown,0);assert.equal(related.B_unrelated,0);
  const unrelated=builderGap(5000,undefined,[b(1,2000,{units:[9],controlled:[unit(9)]})]).events[0];
  assert.equal(unrelated.B_count,1);assert.equal(unrelated.B_related,0);assert.equal(unrelated.B_unrelated,1);
  assert.equal(unrelated.status,'EXPLAINED');
  const unknown=builderGap(5000,undefined,[b(1,2000,{units:[],controlled:[]})]).events[0];
  assert.equal(unknown.B_count,1);assert.equal(unknown.B_unknown,1);
  const production=productionGap(5000,[b(1,3000,{units:[9],controlled:[unit(9)]})]).events.find(e=>e.type==='PRODUCTION_GAP');
  assert.equal(production.B_count,1);assert.equal(production.B_unrelated,1);assert.equal(production.status,'EXPLAINED');
});
test('existing movement task is not builder idle',()=>{
  const h=harness(),base=unit(1,{builder:true});
  h.row('unit',0,{unit:{...base,task:{type:'build'}}});h.row('unit',1000,{unit:{...base,idle:false,task:{type:'move'}}});
  h.row('unit',6000,{unit:{...base,idle:false,task:{type:'repair',targetId:9}}});assert.equal(h.end(6000).length,0);
});
test('historical build affordability and unknown placement stay distinct',()=>{
  const task={type:'build',buildType:'turret',count:1};
  assert.equal(builderGap(5000,task,[],{actions:{a:{kind:'build',type:'turret',cost:350,available:true,affordable:true}}}).events[0].status,'UNCERTAIN');
  assert.equal(builderGap(5000,task,[],{actions:{a:{kind:'build',type:'turret',cost:350,available:false,affordable:false}}}).events[0].status,'EXPLAINED');
});
function productionGap(gap,commands=[],snapshot={},endpoint={}) {
  const h=harness(commands),base=unit(7,{type:'landFactory',factory:true,building:true,mobile:false});
  h.row('unit',0,{unit:{...base,idle:false,queue:[{action:'tank',kind:'produce',count:1}]}});
  h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});
  h.row('unit',1000,{funds:1000,unit:{...base,actions:{tank:{kind:'produce',type:'tank',cost:300,available:true,affordable:true}},capRemaining:50,...snapshot}});
  h.row('native',1000+gap,{phase:'after',source:'agent',results:[{unitId:7,life:0,queueAdded:[{action:'tank',kind:'produce',type:'tank',count:1}],...endpoint}]});
  h.end(1000+gap);return h;
}
for(const [gap,expected]of [[2900,0],[3000,0],[5000,1],[9900,1],[10000,0]])test(`production gap strict boundary ${gap}`,()=>assert.equal(productionGap(gap).events.length,expected));
test('production B open interval excludes both endpoints',()=>{
  assert.equal(productionGap(5000,[b(1,1000),b(2,6000)]).events[0].strict,true);
  assert.equal(productionGap(5000,[b(1,3000)]).events[0].strict,false);
});
test('factory entire production batch must have been affordable with capacity at start',()=>{
  assert.equal(productionGap(5000,[],{}, {batchCount:4}).events[0].feasible,false);
  assert.equal(productionGap(5000,[],{capRemaining:0}).events[0].feasible,false);
  assert.equal(productionGap(5000,[],{actions:{tank:{kind:'produce',cost:300,available:false,affordable:true}}}).events[0].feasible,false);
});
test('upgrade endpoints cannot become production orders',()=>{
  assert.equal(productionGap(5000,[],{}, {queueAdded:[{action:'T2',kind:'upgrade_or_action',count:1}]}).events.length,0);
});
function productionGapAt(funds,batchCount,affordable=true) {
  const h=harness(),base=unit(7,{type:'landFactory',factory:true,building:true,mobile:false});
  h.row('unit',0,{funds,unit:{...base,idle:false,queue:[{action:'tank',kind:'produce',count:1}]}});
  h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});
  h.row('unit',1000,{funds,unit:{...base,actions:{tank:{kind:'produce',type:'tank',cost:300,available:true,affordable}},capRemaining:50}});
  h.row('native',6000,{phase:'after',source:'replay_unknown',results:[{unitId:7,life:0,batchCount,
    queueAdded:[{action:'tank',kind:'produce',type:'tank',count:batchCount}]}]});
  return h.end(6000).find(e=>e.type==='PRODUCTION_GAP');
}
test('unaffordable production at gap start explains idle time even when the order origin is unknown',()=>{
  const e=productionGapAt(200,1,false);
  assert.equal(e.cannotOrder,true);assert.equal(e.reason,'production_not_feasible_at_start');
  assert.equal(e.status,'EXPLAINED');assert.equal(e.strict,false);
});
test('affording one unit keeps an unattributable production gap uncertain',()=>{
  const e=productionGapAt(400,2);
  assert.equal(e.cannotOrder,false);assert.equal(e.feasible,false);
  assert.equal(e.status,'UNCERTAIN');assert.equal(e.reason,'production_order_origin_unavailable');
});
test('initial never-used factory and moving mobile factory are not production gaps',()=>{
  const h=harness();h.row('unit',0,{unit:unit(7,{factory:true})});
  h.row('native',5000,{phase:'after',results:[{unitId:7,queueAdded:[{action:'a',kind:'produce',count:1}]}]});assert.equal(h.end(5000).length,0);
  assert.equal(productionGap(5000,[],{mobile:true,idle:false,task:{type:'move'}}).events.length,0);
});
test('a shared factory budget that cannot be reconstructed is uncertain, not explained',()=>{
  const h=harness(),base=unit(7,{type:'landFactory',factory:true,building:true,mobile:false});
  h.row('unit',0,{unit:{...base,idle:false,queue:[{action:'tank',kind:'produce',count:1}]}});
  h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});
  h.row('unit',1000,{funds:1000,unit:{...base,actions:{tank:{kind:'produce',type:'tank',cost:300,available:true,affordable:true}},capRemaining:50}});
  h.row('native',6000,{phase:'after',source:'agent',results:[
    {unitId:7,life:0,queueAdded:[{action:'tank',kind:'produce',type:'tank',count:1}]},
    {unitId:9,life:0,queueAdded:[{action:'tank',kind:'produce',type:'tank',count:1}]}]});
  const e=h.end(6000).find(e=>e.type==='PRODUCTION_GAP');
  assert.equal(e.feasible,null);assert.equal(e.status,'UNCERTAIN');
  assert.equal(e.reason,'multi_factory_shared_funds_not_reconstructed');assert.equal(e.strict,false);
});
test('a missing historical action menu stays uncertain rather than explained',()=>{
  const h=harness(),base=unit(7,{type:'landFactory',factory:true,building:true,mobile:false});
  h.row('unit',0,{unit:{...base,idle:false,queue:[{action:'tank',kind:'produce',count:1}]}});
  h.row('lifecycle',1000,{phase:'produced',unitId:7,relatedId:8});
  h.row('unit',1000,{funds:1000,unit:{...base,capRemaining:50}});
  h.row('native',6000,{phase:'after',source:'agent',results:[{unitId:7,life:0,queueAdded:[{action:'tank',kind:'produce',count:1}]}]});
  const e=h.end(6000).find(e=>e.type==='PRODUCTION_GAP');
  assert.equal(e.feasible,null);assert.equal(e.cannotOrder,null);
  assert.equal(e.status,'UNCERTAIN');assert.equal(e.reason,'historical_action_unavailable');assert.equal(e.strict,false);
});
test('completion proof outranks the t0 feasibility verdict',()=>{
  const h=harness(),base=unit(7,{type:'landFactory',factory:true,building:true,mobile:false});
  h.row('unit',0,{funds:200,unit:{...base,idle:false,queue:[{action:'tank',kind:'produce',count:1}]}});
  h.row('unit',1000,{funds:200,unit:{...base,actions:{tank:{kind:'produce',type:'tank',cost:300,available:true,affordable:false}},capRemaining:50}});
  h.row('native',6000,{phase:'after',source:'agent',results:[{unitId:7,life:0,queueAdded:[{action:'tank',kind:'produce',type:'tank',count:1}]}]});
  const e=h.end(6000).find(e=>e.type==='PRODUCTION_GAP');
  assert.equal(e.cannotOrder,true);assert.equal(e.completionProven,false);
  assert.equal(e.status,'UNCERTAIN');assert.equal(e.reason,'actual_production_completion_unproven');
  assert.equal(e.strict,false);
  assert.equal(productionGapAt(200,1,false).status,'EXPLAINED');
});
test('a rejected direct B still counts as B activity inside a gap',()=>{
  const rejected=b(1,3000,{accepted:false,reply:'error: No controllable unit remains; skipped dead/missing ids: 1'});
  const e=productionGap(5000,[rejected]).events.find(e=>e.type==='PRODUCTION_GAP');
  assert.equal(e.B_count,1);assert.equal(e.strict,false);
  assert.equal(e.status,'EXPLAINED');assert.equal(e.reason,'global_B_during_gap');
});
function feedDeath(h,time,id,extra={}) {
  const enemy=unit(90,{side:'enemy',x:10});
  h.row('death',time,{unit:unit(id,extra),killer:enemy,nearby:[enemy]});
  h.row('region',time,{anchorId:id,nearby:[enemy]});h.row('frame',time);
}
test('feed UNKNOWN then DANGER then third-loss evidence, with death deduplication',()=>{
  const h=harness();feedDeath(h,0,1);feedDeath(h,1000,2);feedDeath(h,2000,2);assert.equal(h.events.length,0);
  feedDeath(h,3000,3);const e=h.end(4000).find(e=>e.type==='FEED');
  assert.equal(e.t_confirm,3000);assert.equal(e.t_first_repeat,3000);assert.equal(e.state,'REPEATED');
  assert.equal(e.extra.tank,1);assert.equal(e.losses.tank,3);
});
test('attackMove losses, living allies nearby, and missing attacker proof are excluded',()=>{
  const h=harness();feedDeath(h,0,1,{task:{type:'attackMove'}});
  h.row('death',1000,{unit:unit(2),killer:unit(90,{side:'enemy'}),nearby:[unit(90,{side:'enemy'}),unit(3,{side:'ally'})]});
  h.row('death',2000,{unit:unit(3),killer:null,nearby:[unit(90,{side:'enemy'})]});assert.equal(h.end(9000).length,0);
});
test('losses at 100s/108s/112s are two segments and neither confirms a repeat',()=>{
  const h=harness();feedDeath(h,100000,1);feedDeath(h,108000,2);feedDeath(h,112000,3);
  assert.equal(h.end(120000).filter(e=>e.type==='FEED').length,0);
  // [100s] and [108s,112s]: the 8s gap starts a new segment, so no segment reaches a third loss.
  assert.equal(h.session.diagnostics.detectors.FEED.segment_before_third_loss,2);
});
test('each loss segment confirms on its own third loss and settles its own window',()=>{
  const commands=[b(1,1500),b(2,9500)],h=harness(commands);
  feedDeath(h,0,1);feedDeath(h,1000,2);h.row('command',1500,{command:commands[0]});feedDeath(h,2000,3);
  feedDeath(h,9000,4);h.row('command',9500,{command:commands[1]});feedDeath(h,9600,5);feedDeath(h,10200,6);
  const rows=h.end(11000).filter(e=>e.type==='FEED');
  assert.equal(rows.length,2);assert.equal(rows[0].region_id,rows[1].region_id);
  assert.equal(rows[0].t_start,0);assert.equal(rows[0].t_confirm,2000);assert.equal(rows[0].t_first_B,1500);
  assert.equal(rows[1].t_start,9000);assert.equal(rows[1].t_confirm,10200);assert.equal(rows[1].t_first_B,9500);
  assert.equal(rows[1].reactionMs,500);
});
test('feed transitive merge and separated repeated-loss segments share a region id',()=>{
  const h=harness();feedDeath(h,0,1,{x:0});feedDeath(h,1000,2,{x:300});feedDeath(h,2000,3,{x:600});
  feedDeath(h,9000,4,{x:300});feedDeath(h,9500,5,{x:300});feedDeath(h,10000,6,{x:300});
  const rows=h.end(11000).filter(e=>e.type==='FEED');assert.equal(rows.length,2);assert.equal(rows[0].region_id,rows[1].region_id);
  assert.equal(rows[0].t_confirm,2000);assert.equal(rows[1].t_confirm,10000);
  assert.equal(rows[0].losses.tank,3);assert.equal(rows[1].losses.tank,3);
});
test('a retaliation issued before a later loss burst cannot answer that burst',()=>{
  const command=b(1,500),h=harness([command]);
  feedDeath(h,0,1,{x:0});feedDeath(h,1000,2,{x:300});h.row('command',500,{command});feedDeath(h,2000,3,{x:600});
  feedDeath(h,9000,4,{x:300});feedDeath(h,9500,5,{x:300});feedDeath(h,10000,6,{x:300});
  const rows=h.end(11000).filter(e=>e.type==='FEED');
  assert.equal(rows.length,2);
  assert.equal(rows[0].t_first_B,500);assert.equal(rows[0].reactionMs,500);
  assert.equal(rows[1].t_first_B,null);assert.equal(rows[1].reactionMs,undefined);
  assert.ok(rows.every(row=>row.t_confirm>=row.t_start&&(row.reactionMs??0)>=0));
});
test('feed region closes after its minimum hold when friends enter',()=>{
  const h=harness();feedDeath(h,0,1);feedDeath(h,1000,2);feedDeath(h,2000,3);
  for(const anchorId of[1,2,3])h.row('region',4000,{anchorId,nearby:[unit(5)]});h.row('frame',4999);assert.equal(h.events.length,0);
  h.row('frame',5000);assert.equal(h.events.length,1);
});
test('feed records plain move exposure separately from explicit assault',()=>{
  const commands=[b(1,1500,{x:0,y:0,units:[4]})],h=harness(commands);feedDeath(h,0,1);feedDeath(h,1000,2);
  h.row('command',1500,{command:commands[0]});feedDeath(h,2000,3);
  const e=h.end(3000).find(e=>e.type==='FEED');assert.deepEqual(e.flags,['NO_ASSAULT_RESPONSE','REPEATED_EXPOSURE']);
});
test('explicit attack or retreat explains repeated losses without scoring tactical intent',()=>{
  const command=b(1,1500,{operation:'attack',targetId:90}),h=harness([command]);
  feedDeath(h,0,1);feedDeath(h,1000,2);h.row('command',1500,{command});feedDeath(h,2000,3);
  assert.equal(h.end(4000).find(e=>e.type==='FEED').status,'EXPLAINED');
});
test('stale command dedups objects per command, aggregates repeats, and separates lifetimes',()=>{
  const ref={id:7,life:0,role:'controlled',status:'dead',checkedMs:1000};
  const commands=[b(1,1000,{refs:[ref,{...ref,role:'target'}]}),b(2,2000,{refs:[ref]}),b(3,3000,{refs:[{...ref,life:1}]})];
  const h=harness(commands);for(const command of commands)h.row('command',command.timeMs,{command});
  const events=h.end(4000).filter(e=>e.type==='STALE_COMMAND');assert.equal(events.length,2);assert.equal(events[0].count,2);assert.equal(events[1].count,1);
});
test('targets dying after receipt stay uncertain and ordinary failures are not stale',()=>{
  const commands=[b(1,1000,{refs:[{id:7,role:'target',status:'dead',checkedMs:2000,deathTimeMs:1500}]}),
    b(2,2000,{accepted:false,refs:[{id:8,status:'valid',role:'controlled'}]})];
  const h=harness(commands);for(const command of commands)h.row('command',command.timeMs,{command});
  const stale=h.end(3000).filter(e=>e.type==='STALE_COMMAND');
  assert.equal(stale.length,1);assert.equal(stale[0].strict,false);
});
test('same tick ordering distinguishes a later death from stale-at-receipt',()=>{
  const commands=[b(1,1000,{seq:1,refs:[{id:7,role:'target',status:'dead',checkedMs:1100,deathTimeMs:1000,deathSeq:2}]}),
    b(2,1000,{seq:3,refs:[{id:8,role:'target',status:'dead',checkedMs:1100,deathTimeMs:1000,deathSeq:2}]})];
  const h=harness(commands);for(const command of commands)h.row('command',command.timeMs,{command});
  h.end(2000);assert.equal(h.events.filter(e=>e.strict).length,1);assert.equal(h.events.filter(e=>e.status==='UNCERTAIN').length,1);
});
test('settings, keep automation, mods and biological objects never count as stale',()=>{
  const ref={id:7,status:'missing',role:'controlled',checkedMs:0};
  const commands=[b(1,0,{name:'rwx-game-keep-set',refs:[ref]}),b(2,0,{source:'keep',refs:[ref]}),b(3,0,{refs:[{...ref,excluded:true}]})];
  const h=harness(commands);for(const command of commands)h.row('command',0,{command});assert.equal(h.end(1).length,0);
});
for(const count of [0,10,60,61,120])test(`economy ${count} samples form complete minute lines without padding`,()=>{
  const recorder=new EconomyRecorder();for(let i=0;i<count;i++)recorder.sample({sampleMs:i*1000,funds:1000+i/2,income:i%2?60:-5});
  const lines=recorder.text().trim().split('\n').filter(Boolean);assert.equal(lines.length,Math.ceil(count/60));
  if(count>=60)assert.equal(lines[0].split(',').length,60);assert.equal(recorder.entries.length,count);
});
test('economy refuses duplicate or unordered samples and reports holes',()=>{
  const e=new EconomyRecorder();e.sample({sampleMs:0,funds:0,income:0});assert.throws(()=>e.sample({sampleMs:0,funds:0,income:0}));
  e.sample({sampleMs:2000,funds:1,income:1});assert.equal(e.complete,false);
});
test('streaming evaluator produces deterministic artifacts and rejects command-only Replay JSONL',async()=>{
  const tempRoot=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(tempRoot,{recursive:true});
  const dir=fs.mkdtempSync(path.join(tempRoot,'rwx-events-'));
  try{
    const file=path.join(dir,'telemetry.jsonl'),rows=[{kind:'meta',schema:1,timeMs:0,seq:1,team:0,X:160},
      {kind:'economy',timeMs:0,seq:2,sampleMs:0,funds:1000,income:60},{kind:'end',timeMs:1000,seq:3}];
    fs.writeFileSync(file,rows.map(r=>JSON.stringify(r)).join('\n')+'\n');
    const commands=path.join(dir,'commands-test.txt'),economy=path.join(dir,'economy.log');
    fs.writeFileSync(commands,'[00:00:000]<name=test;player=0;faction=A;match=1;role=player><rwx-game-state>\n');
    fs.writeFileSync(economy,'1000(+60)\n');
    const a=await evaluateEvents(file,{out:path.join(dir,'a'),commands,economy}),b=await evaluateEvents(file,{out:path.join(dir,'b'),commands,economy});
    assert.deepEqual(a.summary,b.summary);assert.equal(fs.readFileSync(path.join(a.out,'economy.log'),'utf8'),'1000(+60)\n');
    fs.writeFileSync(file,'{"time_s":0,"type":"move"}\n');await assert.rejects(()=>indexCommands(file));
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('temporary departure from idle resets continuous attack confirmation',()=>{
  const h=harness();attacks(h,0,2000);h.row('unit',2500,{unit:unit(1,{idle:false})});h.row('frame',2500);
  h.row('unit',3500,{unit:unit(1)});attacks(h,4000,7000);assert.equal(h.end(7000).filter(e=>e.type==='AMBUSH').length,0);
});
test('Replay without CLI coverage cannot assert global no-B or unanswered events',()=>{
  const events=[],session=new Session({schema:1,team:0,timeMs:0,source:'replay'},[],{legacyDetectors:true},e=>events.push(e));
  const factory=unit(7,{factory:true,mobile:false});
  session.consume({kind:'unit',timeMs:0,unit:{...factory,idle:false,queue:[{kind:'produce'}]}});
  session.consume({kind:'unit',timeMs:1000,funds:1000,unit:{...factory,actions:{tank:{kind:'produce',cost:100,available:true,affordable:true}}}});
  session.consume({kind:'native',timeMs:6000,phase:'after',source:'replay_unknown',results:[{unitId:7,queueAdded:[{action:'tank',kind:'produce',count:1}]}]});
  assert.equal(events[0].strict,false);assert.equal(events[0].status,'UNCERTAIN');
});
test('factory upgrade cancels an idle candidate and cannot restart it without actual production',()=>{
  const h=harness(),base=unit(7,{factory:true,mobile:false});
  h.row('unit',0,{unit:{...base,idle:false,queue:[{kind:'produce'}]}});h.row('unit',1000,{unit:base,funds:1000});
  h.row('unit',2000,{unit:{...base,idle:false,queue:[{kind:'upgrade_or_action'}]}});h.row('unit',3000,{unit:base,funds:1000});
  h.row('native',8000,{source:'agent',phase:'after',results:[{unitId:7,queueAdded:[{kind:'produce',action:'tank',count:1}]}]});
  assert.equal(h.end(8000).length,0);
});
test('queue still containing production never starts an idle interval',()=>{
  const h=harness(),busy=unit(7,{factory:true,queue:[{kind:'produce',action:'tank',count:1}]});
  h.row('unit',0,{unit:busy});h.row('unit',5000,{unit:busy});
  h.row('native',6000,{source:'agent',phase:'after',results:[{unitId:7,queueAdded:[{kind:'produce',action:'tank',count:1}]}]});
  assert.equal(h.end(8000).length,0);
});
test('late expansion in same game millisecond does not prove stale-at-receipt',()=>{
  const command=b(1,1000,{seq:10,refs:[{id:9,role:'target',status:'dead',atReceipt:false,checkedMs:1000,deathTimeMs:1000,deathSeq:11}]});
  const h=harness([command]);h.row('command',1000,{command});assert.equal(h.end(1001)[0].strict,false);
});
test('unknown removed entity type is uncertain rather than silently including excluded mods',()=>{
  const command=b(1,1000,{refs:[{id:9,role:'target',status:'missing',eligibleKnown:false,checkedMs:1000}]});
  const h=harness([command]);h.row('command',1000,{command});assert.equal(h.end(1001)[0].reason,'unknown_entity_lifecycle');
});
test('index retains receipt-valid references even if later intent sees them dead',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});const dir=fs.mkdtempSync(path.join(root,'event-index-'));
  try{
    const file=path.join(dir,'telemetry.jsonl'),rows=[{kind:'meta',schema:1,team:0,timeMs:0,seq:1},
      {kind:'command',id:'B1',timeMs:1000,seq:2,refs:[{id:8,role:'target',status:'valid',atReceipt:true}]},
      {kind:'intent',commandId:'B1',timeMs:1000,seq:3,refs:[{id:8,role:'target',status:'dead',atReceipt:false}]},
      {kind:'end',timeMs:1001,seq:4}];
    fs.writeFileSync(file,rows.map(r=>JSON.stringify(r)).join('\n'));const index=await indexCommands(file);
    assert.equal(index.commands.get('B1').refs[0].status,'valid');
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('legacy application linkage verifies Replay ordinal tick team and original selection',()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});const dir=fs.mkdtempSync(path.join(root,'event-legacy-'));
  try{
    const high=path.join(dir,'commands.txt'),low=path.join(dir,'replay.jsonl');
    fs.writeFileSync(high,'[00:00]<name=test;player=0;faction=A;match=1;role=player><rwx-game-order-move --units 1 --x 10 --y 20>\n');
    fs.writeFileSync(low,JSON.stringify({time_s:0.016,tick:1,team:0,units:[1],type:'move',x:10,y:20,command_queued:false})+'\n');
    const index={meta:{team:0},nativeBefore:new Map([[1,{nativeId:'N1',tick:1,team:0,units:[1]}]]),
      nativeAfter:new Map([[1,{timeMs:16,results:[{unitId:1,applied:true}]}]])};
    assert.equal(legacyCommands(index,high,low)[0].applications.length,1);
    index.nativeBefore.get(1).tick=2;assert.equal(legacyCommands(index,high,low)[0].applications.length,0);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});

test('trimmed frames rows expand to the same events as per-tick frames',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'event-frames-'));
  try{
    const commands=path.join(dir,'commands.txt'),economy=path.join(dir,'economy.log');
    fs.writeFileSync(commands,'[00:00:000]<name=test;player=0;faction=A;match=1;role=player><rwx-game-state>\n');
    fs.writeFileSync(economy,Array.from({length:7},()=>'1000(+60)').join(',')+'\n');
    const raw=[],trimmed=[];let seq=0,tick=0,tseq=0;
    raw.push({kind:'meta',schema:1,timeMs:0,seq:++seq,team:0,X:160});
    trimmed.push({kind:'meta',schema:1,timeMs:0,seq:++tseq,team:0,X:160});
    for(let second=0;second<=6;second++){
      raw.push({kind:'economy',timeMs:second*1000,seq:++seq,tick:tick++,sampleMs:second*1000,funds:1000,income:60});
      trimmed.push({...raw.at(-1),seq:++tseq});
      raw.push({kind:'damage',timeMs:second*1000,seq:++seq,tick:tick++,
        attacker:unit(2,{side:'enemy',x:100}),victim:unit(1)});
      trimmed.push({...raw.at(-1),seq:++tseq});
      const run=[];
      for(let frame=0;frame<10;frame++){
        run.push({kind:'frame',timeMs:second*1000+frame*100,tick:tick++,seq:++seq});
      }
      raw.push(...run);
      trimmed.push({kind:'frames',timeMs:run[0].timeMs,tick:run[0].tick,seq:++tseq,
        toTimeMs:run.at(-1).timeMs,toTick:run.at(-1).tick,toSeq:tseq+run.length-1,stepMs:100});
      tseq+=run.length-1;
    }
    raw.push({kind:'end',timeMs:6900,seq:++seq,tick:tick});trimmed.push({kind:'end',timeMs:6900,seq:++tseq});
    const rawFile=path.join(dir,'raw.jsonl'),trimmedFile=path.join(dir,'trimmed.jsonl');
    fs.writeFileSync(rawFile,raw.map(r=>JSON.stringify(r)).join('\n')+'\n');
    fs.writeFileSync(trimmedFile,trimmed.map(r=>JSON.stringify(r)).join('\n')+'\n');
    const a=await evaluateEvents(rawFile,{out:path.join(dir,'a'),commands,economy});
    const b=await evaluateEvents(trimmedFile,{out:path.join(dir,'b'),commands,economy});
    const strip=s=>{const copy=JSON.parse(JSON.stringify(s));delete copy.inputs;return copy;};
    assert.deepEqual(strip(b.summary),strip(a.summary));
    assert.ok(a.summary.counts.AMBUSH>0,'fixture must produce an ambush');
    fs.writeFileSync(trimmedFile,JSON.stringify({kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:999,toTick:9,toSeq:10,stepMs:16})+'\n');
    await assert.rejects(()=>evaluateEvents(trimmedFile,{out:path.join(dir,'c'),commands,economy}),/malformed frames row/);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('malformed frames rows are rejected instead of silently dropped',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'event-frames-bad-'));
  try{
    const file=path.join(dir,'telemetry.jsonl'),meta={kind:'meta',schema:1,team:0,timeMs:0,seq:1};
    const bad=[
      {kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:999,toTick:9,toSeq:10,stepMs:16},      // span disagrees with step
      {kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:48,toTick:3,toSeq:4,stepMs:0},         // zero step
      {kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:33.5,toTick:2,toSeq:3,stepMs:16.5},    // non-integer endpoints
      {kind:'frames',timeMs:32,tick:1,seq:2,toTimeMs:16,toTick:2,toSeq:3,stepMs:-16},       // clock runs backwards
      {kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:32,toTick:2,toSeq:4,stepMs:16},        // seq gap inside the run
    ];
    for(const row of bad){
      fs.writeFileSync(file,[meta,row].map(r=>JSON.stringify(r)).join('\n')+'\n');
      await assert.rejects(async()=>{for await(const ignored of records(file))void ignored;},/malformed frames row/);
    }
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('frames rows expand lazily so the rest of the file is not parsed up front',async()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'event-frames-lazy-'));
  try{
    const file=path.join(dir,'telemetry.jsonl');
    // A 1000-tick run followed by a line that cannot parse: an eager reader would throw before yielding.
    fs.writeFileSync(file,[JSON.stringify({kind:'meta',schema:1,team:0,timeMs:0,seq:1}),
      JSON.stringify({kind:'frames',timeMs:16,tick:1,seq:2,toTimeMs:16000,toTick:1000,toSeq:1001,stepMs:16}),
      '{"kind":'].join('\n')+'\n');
    const iterator=records(file);
    assert.equal((await iterator.next()).value.kind,'meta');
    assert.deepEqual((await iterator.next()).value,{kind:'frame',timeMs:16,tick:1,seq:2});
    let count=1,last={tick:1,timeMs:16};
    await assert.rejects(async()=>{
      for(;;){const {value}=await iterator.next();count++;last=value;}
    },/telemetry\.jsonl:\d+/);
    assert.equal(count,1000);assert.equal(last.tick,1000);assert.equal(last.timeMs,16000);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});

// --- B 绞肉机：把刚死过人的地方当安全区 -------------------------------------
function grinderCase({order={},query=[],consequence={},stream=[]}={}) {
  const move=b(1,12_000,{name:'rwx-game-order-move',operation:'move',units:[5],x:1000,y:1000,
    controlled:[unit(5)],...order});
  const commands=[...query,move];
  const h=harness(commands);
  h.row('death',10_000,{unit:unit(1,{x:1000,y:1000}),killer:unit(2,{side:'enemy'}),nearby:[]});
  for(const row of stream)h.row('eventstream',row.timeMs,row);
  for(const command of commands)h.row('command',command.timeMs,{command});
  if(consequence!==false)
    h.row('damage',14_000,{attacker:unit(2,{side:'enemy'}),
      victim:unit(5,{x:1010,y:1000,taskType:'move',...consequence})});
  h.row('frame',14_000);
  return h.end(40_000);
}
test('a send into a place we just lost units is a danger-zone event',()=>{
  const events=grinderCase(),e=events.find(x=>x.type==='GRINDER');
  assert.ok(e,'expected a GRINDER event');
  assert.equal(e.status,'DETECTED');assert.equal(e.scoreable,true);
  assert.equal(e.t_start,10_000);assert.equal(e.delayMs,2_000);
  assert.equal(e.awareness.aware,false);assert.equal(e.operation,'move');
  assert.equal(e.consequence.unitId,5);assert.equal(e.agentState,'BUSY_ELSEWHERE');
});
test('a report that covers the loss turns the danger-zone event into EXPLAINED',()=>{
  const query=[{id:9,timeMs:11_000,seq:9,source:'agent',name:'rwx-game-events',
    args:['--seconds','60'],refs:[],units:[]}];
  const e=grinderCase({query}).find(x=>x.type==='GRINDER');
  assert.equal(e.status,'EXPLAINED');assert.equal(e.scoreable,false);
  assert.equal(e.awareness.by,'events_window');
});
test('a query window that does not reach back to the loss does not count as informed',()=>{
  const query={id:9,timeMs:30_000,seq:9,source:'agent',name:'rwx-game-events',
    args:['--seconds','5'],refs:[],units:[]};
  const move=b(1,31_000,{name:'rwx-game-order-move',operation:'move',units:[5],x:1000,y:1000,
    controlled:[unit(5)]});
  const h=harness([query,move]);
  h.row('death',10_000,{unit:unit(1,{x:1000,y:1000}),killer:unit(2,{side:'enemy'}),nearby:[]});
  h.row('command',30_000,{command:query});
  h.row('command',31_000,{command:move});
  h.row('damage',32_000,{attacker:unit(2,{side:'enemy'}),
    victim:unit(5,{x:1010,y:1000,taskType:'move'})});
  h.row('frame',32_000);
  const e=h.end(60_000).find(x=>x.type==='GRINDER');
  assert.ok(e,'expected a GRINDER event');
  assert.equal(e.awareness.aware,false);assert.equal(e.scoreable,true);
});
test('a live event socket counts as informed',()=>{
  const stream=[{timeMs:10_500,connected:true,clients:1},{timeMs:13_000,connected:false,clients:0}];
  const e=grinderCase({stream}).find(x=>x.type==='GRINDER');
  assert.equal(e.status,'EXPLAINED');assert.equal(e.awareness.by,'event_stream');
});
test('without a consequence there is no danger-zone event',()=>{
  assert.equal(grinderCase({consequence:false}).filter(x=>x.type==='GRINDER').length,0);
});
test('an assault or a guard is not a send into believed-safe ground',()=>{
  assert.equal(grinderCase({order:{name:'rwx-game-order-attack-move',operation:'attackMove'}})
    .filter(x=>x.type==='GRINDER').length,0);
  assert.equal(grinderCase({consequence:{taskType:'attackMove'}})
    .filter(x=>x.type==='GRINDER').length,0);
  assert.equal(grinderCase({consequence:{taskType:'guardAt'}})
    .filter(x=>x.type==='GRINDER').length,0);
});
test('a builder sent to build where we just lost units is judged the same way',()=>{
  const build=b(1,12_000,{name:'rwx-game-order-build',operation:'build',units:[],builders:[5],
    x:1000,y:1000,controlled:[unit(5,{type:'builder',builder:true,canAttack:false})]});
  const h=harness([build]);
  h.row('death',10_000,{unit:unit(1,{x:1000,y:1000}),killer:unit(2,{side:'enemy'}),nearby:[]});
  h.row('command',12_000,{command:build});
  h.row('damage',15_000,{attacker:unit(2,{side:'enemy'}),
    victim:unit(5,{type:'builder',builder:true,canAttack:false,x:1000,y:1000,taskType:'build'})});
  h.row('frame',15_000);
  const e=h.end(40_000).find(x=>x.type==='GRINDER');
  assert.ok(e);assert.equal(e.operation,'build');assert.equal(e.consequence.unitId,5);
});
function arrivalCase({arriveAt,hitAt}) {
  const move=b(1,12_000,{name:'rwx-game-order-move',operation:'move',units:[5],x:1000,y:1000,
    controlled:[unit(5)]});
  const h=harness([move]);
  h.row('death',10_000,{unit:unit(1,{x:1000,y:1000}),killer:unit(2,{side:'enemy'}),nearby:[]});
  h.row('command',12_000,{command:move});
  if(arriveAt!=null)h.row('unit',arriveAt,{unit:unit(5,{x:1000,y:1000,taskType:'move'})});
  h.row('damage',hitAt,{attacker:unit(2,{side:'enemy'}),
    victim:unit(5,{x:1010,y:1000,taskType:'move'})});
  h.row('frame',hitAt);
  return h.end(200_000).find(x=>x.type==='GRINDER');
}
test('the consequence window starts when the unit reaches the place',()=>{
  const near=arrivalCase({arriveAt:20_000,hitAt:28_000});
  assert.ok(near);assert.equal(near.consequence.arrivalObserved,true);
  assert.equal(near.consequence.arrivalMs,20_000);
  // Twelve seconds after arrival is outside the ten second window.
  assert.equal(arrivalCase({arriveAt:20_000,hitAt:32_000}),undefined);
});
test('an unobserved march falls back to the hard cap from the order',()=>{
  const late=arrivalCase({arriveAt:null,hitAt:100_000});
  assert.ok(late,'a long march must still count inside the hard cap');
  assert.equal(late.consequence.arrivalObserved,false);
  assert.equal(arrivalCase({arriveAt:null,hitAt:200_000}),undefined);
});
function idleCase(coverageEnd,fromMs=5000,toMs=60000) {
  const events=[],session=new Session({schema:1,team:0,timeMs:0,X:160,
    commandCoverage:{start:0,end:coverageEnd}},[],
  {legacyDetectors:false},e=>events.push(e));
  session.consume({kind:'idle',timeMs:toMs,tick:toMs,seq:1,unitId:7,type:'landFactory',
    roles:['factory'],fromMs,toMs,durationMs:toMs-fromMs,closeReason:'EVIDENCE_END',
    startFunds:3000,endFunds:3000,startCap:999,lastProduct:null,
    actions:{b_c_tank:{kind:'produce',type:'c_tank',cost:350,available:true,affordable:true}}});
  session.now=toMs;session.finish();
  return events.find(e=>e.type==='FACTORY_IDLE');
}
test('a covered prefix retains diagnostics but cannot score work whose endpoint is outside coverage',()=>{
  // The Agent process exits before the engine does, so the command log stops at 20 s while the
  // factory stays idle to 60 s. Keep the covered duration as evidence, but it has no covered B work.
  const e=idleCase(20_000);
  assert.ok(e);assert.equal(e.status,'UNCERTAIN');assert.equal(e.t_end,20_000);
  assert.equal(e.durationMs,15_000);assert.equal(e.observedDurationMs,55_000);
  assert.equal(e.durationIsLowerBound,true);assert.equal(e.scoreable,false);
  // A log that ends before the idle even starts proves nothing.
  assert.equal(idleCase(1_000).status,'UNCERTAIN');
});
