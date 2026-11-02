'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {Session}=require('../evaluate_stage_2');
const {endpointFeasibility}=require('../events/feasibility');
const {deduction}=require('../events/score');
const factory=()=>({kind:'idle',timeMs:60000,seq:1,unitId:7,type:'landFactory',roles:['factory'],fromMs:0,toMs:60000,
  durationMs:60000,closeReason:'PRODUCE',closeAction:'heavy',closeQueue:[{kind:'produce',action:'heavy',count:2}],
  startFunds:10000,endFunds:10000,startCap:10,
  actions:{cheap:{kind:'produce',cost:100,available:true},heavy:{kind:'produce',cost:2000,available:true}},
  feasibility:{complete:true,fromMs:0,toMs:60000,minFunds:5000,minCap:10,actions:{cheap:100,heavy:2000}}});
function idle(row,meta={},config={}){
  const events=[],s=new Session({schema:1,team:0,timeMs:0,X:160,...meta},[],config,e=>events.push(e));
  s.consume(row);return events[0];
}
test('idle scoring uses the endpoint batch instead of an affordable cheap substitute',()=>{
  const row=factory();assert.equal(idle(row).scoreable,true);
  row.feasibility.minFunds=3500;
  const e=idle(row);assert.equal(e.scoreable,false);assert.equal(e.status,'EXPLAINED');
  assert.equal(e.reason,'endpoint_batch_not_affordable_throughout');
});
test('middle-of-interval cap, resource and availability failures reject the whole candidate',()=>{
  for(const change of [p=>p.minCap=1,p=>p.minFunds=10,p=>delete p.actions.heavy]){
    const row=factory();change(row.feasibility);assert.equal(idle(row).scoreable,false);
  }
  const row=factory();row.feasibility.complete=false;
  assert.equal(idle(row).status,'UNCERTAIN');
  delete row.feasibility;assert.equal(idle(row).status,'UNCERTAIN');
});
test('split native packets use the whole requested batch and shared budgets remain unproven',()=>{
  function run(shared){
    const events=[],s=new Session({schema:1,team:0,timeMs:0,X:160},[],{},e=>events.push(e));
    s.consume({kind:'native',phase:'after',timeMs:60000,seq:1,source:'agent',results:[
      {unitId:7,batchCount:3,queueAdded:[{kind:'produce',action:'heavy',count:1}]},
      ...(shared?[{unitId:8,batchCount:3,queueAdded:[{kind:'produce',action:'heavy',count:1}]}]:[])]});
    s.consume({...factory(),seq:2});return events[0];
  }
  assert.equal(run(false).reason,'endpoint_batch_not_affordable_throughout');
  assert.equal(run(true).reason,'shared_endpoint_budget_unavailable');
});
test('missing endpoint and uncovered endpoint cannot pass, even when both funds endpoints are rich',()=>{
  const row=factory();row.closeReason='EVIDENCE_END';row.closeQueue=[];
  assert.equal(idle(row).scoreable,false);
  assert.equal(idle(factory(),{commandCoverage:{start:0,end:30000}}).reason,'endpoint_outside_command_coverage');
});
test('builder placement must match the exact endpoint location throughout the same interval',()=>{
  const row={...factory(),roles:['builder'],closeReason:'BUILD',closeTask:{type:'build',buildType:'turret',x:10,y:20,count:1},
    actions:{buildTurret:{kind:'build',type:'turret',cost:1000}},
    feasibility:{complete:true,fromMs:0,toMs:60000,minFunds:5000,minCap:0,actions:{buildTurret:1000}}};
  assert.equal(idle(row).reason,'continuous_placement_unavailable');
  row.feasibility.placements=[{type:'turret',x:10,y:20,count:1,fromMs:0,toMs:60000,valid:true}];
  assert.equal(idle(row).scoreable,true);
  row.feasibility.placements[0].x=11;assert.equal(idle(row).scoreable,false);
  row.feasibility.placements[0].x=10;row.feasibility.placements[0].valid=false;
  assert.equal(idle(row).status,'EXPLAINED');
});
test('builder repairs require the same living damaged target for the entire interval',()=>{
  const row={...factory(),roles:['builder'],closeReason:'REPAIR',closeTask:{type:'repair',targetId:22}};
  row.feasibility.repairable=[22];assert.equal(endpointFeasibility(row,false).throughout,true);
  row.feasibility.repairable=[];assert.equal(idle(row).scoreable,false);
});
test('ambush accumulates actual HP loss and effective applications without same-tick future leakage',()=>{
  const unit=(id,side)=>({id,life:0,type:'tank',side,x:id*10,y:0,idle:true,alive:true,building:false,maxHp:1000});
  const c={id:'B',timeMs:5500,seq:7,source:'agent',name:'rwx-game-order-move',units:[1],x:500,y:500,
    applications:[{timeMs:6000,seq:999,applied:true}]};
  const events=[],s=new Session({schema:1,team:0,timeMs:0,X:160},[c],{},e=>events.push(e));
  let seq=0;
  for(let timeMs=0;timeMs<=6000;timeMs+=1000){
    if(timeMs===6000)s.consume({kind:'command',timeMs:5500,seq:++seq},c);
    s.consume({kind:'damage',timeMs,seq:++seq,hpLoss:100,victim:unit(1,'self'),attacker:unit(2,'enemy')});
    s.consume({kind:'frame',timeMs,seq:++seq});
  }
  s.finish();const [event]=events;
  assert.equal(event.hpLosses[0].hpLoss,700);assert.equal(event.impactComplete,true);
  assert.equal(event.effectiveResponseMs,null);
  assert.equal(deduction(event).impact,1.4);
});
test('stale burst preserves receipt-time expiry age when a rejection duplicates that command',()=>{
  const events=[],s=new Session({schema:1,team:0,timeMs:0,X:160},[],{},e=>events.push(e));
  for(let i=0;i<3;i++){
    const timeMs=20000+i*1000,c={id:'B'+i,timeMs,seq:i+1,source:'agent',name:'rwx-game-order-move',units:[1],
      refs:[{id:1,role:'controlled',status:'dead',atReceipt:true,deathTimeMs:1000}],
      accepted:false,reply:'STALE_OBJECT',code:'STALE_OBJECT'};
    s.commands.push(c);s.consume({kind:'command',timeMs,seq:i+1},c);
  }
  s.finish();const burst=events.find(e=>e.type==='STALE_BURST');
  assert.equal(burst.commands.length,3);assert.deepEqual(burst.commands.map(c=>c.staleAgeMs),[19000,20000,21000]);
  assert.equal(deduction(burst).points,8);
});
