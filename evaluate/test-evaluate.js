'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {RULES,INDIRECT_RULES,matchAll,buildMatches,clock}=require('./execution-mapping.js');
const {readHigh,readLow}=require('./evaluate.js');
const {readLog,evaluate_1}=require('./evaluate_1.js');
const repo=path.resolve(__dirname,'..');
const temp=fs.mkdtempSync(path.join(repo,'agent-tmp/evaluate-test-'));
test.after(()=>fs.rmSync(temp,{recursive:true,force:true}));
let serial=0;
function high(name,args={},time=10,line=1){
  return {name:'rwx-game-order-'+name,args,team:1,time,line,command:'rwx-game-order-'+name};
}
function low(overrides={}){
  const id=serial++;
  return {id,line:id+1,tick:650,time_s:10.4,team:1,units:[7],unit_types:[null],type:null,action:null,
    target_unit:-1,target_point:null,rally:null,attack_mode:null,build:null,build_count:null,
    command_queued:false,waypoint_queued:false,stop_current_action:false,clear_existing_orders:false,
    noop:false,...overrides};
}
const result=(h,rows)=>matchAll([h],rows)[0];

test('all 19 registered orders and 8 indirect B commands have audited rules',()=>{
  const java=fs.readFileSync(path.join(repo,'core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliCommands.java'),'utf8');
  const names=[...java.matchAll(/"(rwx-game-order-[\w-]+)"/g)].map(m=>m[1]);
  assert.deepEqual(Object.keys(RULES).sort(),names.sort());
  assert.equal(Object.keys(INDIRECT_RULES).length,8);
});

for(const [name,type] of [['move','move'],['attack-move','attackMove'],['patrol','patrol'],['guard-at','guardAt']]){
  test(name+' uses exact point, units, team and native queue flag',()=>{
    const h=high(name,{units:'7',x:'100',y:'120',queue:true});
    const rows=[low({type,x:100,y:120,team:0,command_queued:true}),low({type,x:100,y:120}),low({type,x:100,y:120,units:[8],command_queued:true}),low({type,x:100,y:120,command_queued:true})];
    const r=result(h,rows);
    assert.equal(r.status,'matched');assert.deepEqual(r.lowLines,[rows[3].line]);
  });
}
for(const [name,type] of [['attack','attack'],['guard','guard'],['follow','follow'],['repair','repair'],['assist','repair'],['reclaim','reclaim'],['load-into','loadInto'],['load-up','loadUp']]){
  test(name+' uses native unit target, not waypoint placeholder coordinates',()=>{
    const h=high(name,{units:'7',target:'42'}),rows=[low({type,target_unit:41}),low({type,target_unit:42,x:2,y:2})];
    assert.deepEqual(result(h,rows).lowLines,[rows[1].line]);
  });
}
test('stop, rally and stance are three different orderless native commands',()=>{
  const rows=[low({clear_existing_orders:true}),low({rally:[100,120]}),low({attack_mode:'holdFire'})];
  assert.deepEqual(result(high('stop',{units:'7'}),rows).lowLines,[rows[0].line]);
  assert.deepEqual(result(high('rally',{units:'7',x:'100',y:'120'}),rows).lowLines,[rows[1].line]);
  assert.deepEqual(result(high('attack-mode',{units:'7',mode:'holdFire'}),rows).lowLines,[rows[2].line]);
});
test('a build count is one native packet with build_count, and normal placement snaps to grid',()=>{
  const h=high('build',{builder:'7',type:'landFactory',x:'1800',y:'1700',count:'3'});
  const r=result(h,[low({type:'build',build:'landFactory',x:1790,y:1690,build_count:3})]);
  assert.equal(r.status,'matched');assert.equal(r.matchedCount,1);assert.equal(r.matchedOperations,3);
});
test('build aliases follow shipped overrides without collapsing arbitrary names',()=>{
  assert.equal(buildMatches('antiAirTurret','custom:c_antiAirTurret'),true);
  assert.equal(buildMatches('fabricator','custom:fabricatorT1'),true);
  assert.equal(buildMatches('turret','custom:c_turret_t1'),true);
  assert.equal(buildMatches('c_tank','tank'),false);
  assert.equal(buildMatches('fabricatorT1','custom:fabricatorT2'),false);
});
test('auto placement covers square corner rings and rejects out-of-radius/off-grid points',()=>{
  const h=high('build',{builder:'7',type:'turret',x:'1800',y:'1700','auto-place':true,radius:'5'});
  const rows=[low({type:'build',build:'custom:c_turret_t1',x:1910,y:1700,build_count:1}),low({type:'build',build:'custom:c_turret_t1',x:1920,y:1700,build_count:1}),low({type:'build',build:'custom:c_turret_t1',x:1900,y:1800,build_count:1})];
  assert.deepEqual(result(h,rows).lowLines,[rows[2].line]);
});
test('production count must be covered for each factory',()=>{
  const h=high('action',{units:'7,8',action:'u_tank',count:'2'});
  const r=result(h,[low({action:'u_tank'}),low({action:'u_tank'}),low({action:'u_tank'}),low({action:'u_tank',units:[8]})]);
  assert.equal(r.status,'partial');assert.deepEqual(r.operationsByUnit,{'7':2,'8':1});
  assert.equal(r.matchedCount,3);
});
test('production and queue cancellation are not interchangeable',()=>{
  const rows=[low({action:'u_tank',stop_current_action:true}),low({action:'u_tank'})];
  assert.deepEqual(result(high('action',{units:'7',action:'u_tank'}),rows).lowLines,[rows[1].line]);
  assert.deepEqual(result(high('cancel',{unit:'7',action:'u_tank'}),rows).lowLines,[rows[0].line]);
});
test('actions match point/unit targets and exact case-sensitive ActionId',()=>{
  const rows=[low({action:'Launch',target_point:[100,120]}),low({action:'launch',target_point:[101,120]}),low({action:'launch',target_point:[100,120]}),low({action:'launch',target_unit:42})];
  assert.deepEqual(result(high('action',{units:'7',action:'launch',x:'100',y:'120'}),rows).lowLines,[rows[2].line]);
  assert.deepEqual(result(high('action',{units:'7',action:'launch',target:'42'}),rows).lowLines,[rows[3].line]);
  assert.equal(result(high('action',{units:'7',action:'launch'}),rows).status,'unmatched');
});
test('push without rally creates only one unqueued attackMove',()=>{
  const r=result(high('push',{units:'7',x:'100',y:'120'}),[low({type:'attackMove',x:100,y:120})]);
  assert.equal(r.status,'matched');assert.equal(r.expectedCount,1);
});
test('push with rally requires move and queued attackMove on the same selection',()=>{
  const h=high('push',{units:'7,8',x:'100',y:'120','rally-x':'50','rally-y':'60'});
  const rows=[low({type:'move',x:50,y:60,units:[7,8]}),low({type:'attackMove',x:100,y:120,units:[7],command_queued:true}),low({type:'attackMove',x:100,y:120,units:[7,8],command_queued:true})];
  assert.deepEqual(result(h,rows).lowLines,[rows[0].line,rows[2].line]);
});
test('push default and group selectors are supported, but coverage remains unverified',()=>{
  for(const args of [{},{group:'main'}]){
    const r=result(high('push',{...args,x:'100',y:'120'}),[low({type:'attackMove',x:100,y:120})]);
    assert.equal(r.status,'matched');assert.equal(r.coverageVerifiable,false);
  }
});
test('numeric exclusions do not turn deliberately excluded units into partial execution',()=>{
  const r=result(high('move',{units:'7,8',exclude:'8',x:'100',y:'120'}),[low({type:'move',x:100,y:120})]);
  assert.equal(r.status,'matched');assert.equal(r.requestedUnits,1);
});
test('dead/missing explicit IDs remain partial with named missing units',()=>{
  const r=result(high('move',{units:'7,8',x:'100',y:'120'}),[low({type:'move',x:100,y:120})]);
  assert.equal(r.status,'partial');assert.deepEqual(r.missingUnits,[8]);
});
test('same-second duplicate requests do not create an empty first matching window or reuse rows',()=>{
  const hs=[high('action',{units:'7',action:'u_tank'},10,1),high('action',{units:'7',action:'u_tank'},10,2)];
  const rows=matchAll(hs,[low({action:'u_tank'}),low({action:'u_tank'})]);
  assert.deepEqual(rows.map(r=>r.status),['matched','matched']);
  assert.equal(new Set(rows.flatMap(r=>r.lowLines)).size,2);
});
test('identical deferred retries sharing too few packets remain ambiguous',()=>{
  const args={units:'7',action:'u_tank',count:'3','after-upgrade':true};
  const hs=[high('action',args,10,1),high('action',args,13,2)];
  const rows=matchAll(hs,[low({action:'u_tank',time_s:50}),low({action:'u_tank',time_s:50}),low({action:'u_tank',time_s:50})]);
  assert.deepEqual(rows.map(r=>r.status),['ambiguous','ambiguous']);
  assert.equal(rows.flatMap(r=>r.lowLines).length,0);
  assert.ok(rows.every(r=>r.delay===null&&r.candidateLowLines.length===3));
});
test('deferred lifetime and a known pending-clear boundary prevent borrowing later execution',()=>{
  const h=high('build',{builder:'7',type:'extractorT1',x:'110',y:'130','allow-unaffordable':true});
  const l=low({type:'build',build:'custom:extractorT1',x:110,y:130,build_count:1,time_s:70});
  assert.equal(result(h,[l]).status,'matched');
  assert.equal(result(h,[{...l,time_s:611}]).status,'unmatched');
  assert.equal(matchAll([h],[l],{commands:[h,{name:'rwx-game-pending-clear',args:{},line:2,time:60}]})[0].status,'unmatched');
});
test('old JSONL reports missing fields instead of silently losing rally/stop/cancel',()=>{
  for(const [name,args] of [['rally',{units:'7',x:'100',y:'120'}],['stop',{units:'7'}],['cancel',{unit:'7'}],['action',{units:'7',action:'u_tank'}]]){
    assert.equal(result(high(name,args),[{id:0,line:1,team:1,time_s:10.4,type:null,units:[7],action:'u_tank'}]).status,'unobservable');
  }
});
test('truncated receipt seconds yield a delay interval and clocks carry rounded milliseconds',()=>{
  const r=result(high('move',{units:'7',x:'100',y:'120'}),[low({type:'move',x:100,y:120})]);
  assert.equal(r.delayMin,0);assert.ok(Math.abs(r.delayMax-.4)<1e-8);
  assert.equal(clock(59.9999),'01:00.000');
});
test('input separates default stance from direct orders and rejects mixed matches',()=>{
  const file=path.join(temp,'port.txt');
  const line=(match,cmd)=>`[00:10]<name=Player;player=1;faction=B(1);role=player;match=${match}><${cmd}>`;
  fs.writeFileSync(file,[line(1,'rwx-game-order-attack-mode --mode aggressive --apply-to-new'),line(1,'rwx-game-order-attack-mode --units 7 --mode holdFire'),line(1,'rwx-game-group-save --name main --units 7')].join('\n'));
  const port=readHigh(file);assert.equal(port.high.length,1);assert.equal(port.excluded.length,2);
  fs.writeFileSync(file,[line(1,'rwx-game-state'),line(2,'rwx-game-state')].join('\n'));
  assert.throws(()=>readHigh(file),/多局/);
});
test('both evaluators read mixed legacy and millisecond clocks without losing receipt precision',()=>{
  const file=path.join(temp,'millisecond-port.txt');
  const line=(time,cmd)=>`[${time}]<name=Player;player=1;faction=B(1);role=player;match=1><${cmd}>`;
  fs.writeFileSync(file,[
    '[--:--:---]<name=unknown;player=none;faction=none;role=unassigned;match=none><rwx-ping>',
    line('00:04','rwx-game-state'),
    line('00:04:123','rwx-game-order-move --units 7 --x 100 --y 120'),
    line('00:05:001','rwx-game-state'),
  ].join('\n'));
  const port=readHigh(file),legacy=readLog(file);
  assert.deepEqual(port.commands.map(r=>r.time),[4,4.123,5.001]);
  assert.deepEqual(legacy.commands.map(r=>r.time),[4,4.123,5.001]);
  assert.equal(port.commands[0].timeUncertainty,1);assert.equal(port.high[0].timeUncertainty,0);
  const r=result(port.high[0],[low({type:'move',x:100,y:120,time_s:4.128})]);
  assert.equal(r.status,'matched');assert.ok(Math.abs(r.delay-.005)<1e-8);
  assert.equal(r.delayMin,r.delayMax);assert.equal(r.timePrecision,'milliseconds');
});
test('millisecond source rejects native execution before receipt within the same second',()=>{
  const h={...high('move',{units:'7',x:'100',y:'120'},10.5),timeUncertainty:0,timePrecision:'milliseconds'};
  assert.equal(result(h,[low({type:'move',x:100,y:120,time_s:10.4})]).status,'unmatched');
});
test('base A/B report keeps millisecond decision intervals',async()=>{
  const file=path.join(temp,'millisecond-cycles.txt');
  const lines=[];
  for(let i=0;i<60;i++){
    const ms=i%2?'432':'123',seconds=i*6;
    lines.push(`[${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}:${ms}]<name=Player;player=1;faction=B(1);role=player;match=1><${i%2?'rwx-game-order-stop --units 7':'rwx-game-state'}>`);
  }
  fs.writeFileSync(file,lines.join('\n'));
  const output=await evaluate_1(file,{outputRoot:temp});
  const summary=JSON.parse(fs.readFileSync(path.join(output,'summary.json'),'utf8'));
  assert.equal(summary.startupReactionSeconds,6.309);
  assert.equal(summary.cycleStats.n,30);
  const csv=fs.readFileSync(path.join(output,'decision_cycles.csv'),'utf8');
  assert.ok(csv.includes('00:00:123'));assert.ok(csv.includes('6.309'));
});
test('real GPT match preserves missing evidence and explicitly identifies deferred ambiguity',{skip:!fs.existsSync(path.join(__dirname,'output'))},()=>{
  const root=path.join(__dirname,'output');
  const name=fs.readdirSync(root).find(n=>n.startsWith('commands-')&&n.endsWith('_gpt_未完.txt')&&!n.includes('-stats'));
  const dir=fs.readdirSync(root).find(n=>n.startsWith('[local]')&&n.endsWith('_gpt_未完'));
  if(!name||!dir)return;
  const file=fs.readdirSync(path.join(root,dir)).find(n=>n.endsWith('.jsonl'));
  const port=readHigh(path.join(root,name)),lowRows=readLow(path.join(root,dir,file),port.team);
  if(!('rally' in lowRows[0]))return;
  const rows=matchAll(port.high,lowRows,{commands:port.commands});
  assert.equal(rows.length,124);
  assert.equal(rows.filter(r=>r.status==='matched').length,102);
  assert.equal(rows.filter(r=>r.status==='partial').length,5);
  assert.equal(rows.filter(r=>r.status==='unmatched').length,15);
  assert.deepEqual(rows.filter(r=>r.status==='ambiguous').map(r=>r.highLine),[169,170]);
  assert.deepEqual(rows.find(r=>r.highLine===169).candidateLowLines,[1261,1262,1263]);
  assert.equal(new Set(rows.flatMap(r=>r.lowLines)).size,rows.flatMap(r=>r.lowLines).length);
  assert.equal(rows.find(r=>r.highLine===51).status,'unmatched');
});
