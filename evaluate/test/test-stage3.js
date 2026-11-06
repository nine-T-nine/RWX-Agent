'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {compile}=require('../evaluate_stage_3');
const {scoreEvents,loss,DEFAULTS}=require('../score_stage_3');

test('stage3 compiler applies collection gates and preserves the grinder funnel',()=>{
  const rows=[
    {kind:'meta',schema:1,team:0,source:'replay',timeMs:0},
    {kind:'stage3_meta',schema:1,timeMs:0,X:160},
    {kind:'stage3_ambush',phase:'hit',eventId:'A-1',unitId:1,type:'tank',fromMs:1000,
      DHp:20,DShield:0,N:1,maxHp:100,maxShield:0,cost:350,timeMs:1000},
    {kind:'stage3_ambush',phase:'close',eventId:'A-1',unitId:1,type:'tank',fromMs:1000,toMs:4000,
      durationMs:3000,DHp:20,DShield:0,N:1,maxHp:100,maxShield:0,cost:350,complete:true,timeMs:4000},
    {kind:'stage3_barrage',phase:'hit',eventId:'H-1',unitId:2,type:'turret',fromMs:2000,
      DHp:40,DShield:0,N:2,maxHp:500,maxShield:0,cost:1500,timeMs:3000},
    {kind:'stage3_barrage',phase:'close',eventId:'H-1',unitId:2,type:'turret',fromMs:2000,toMs:9000,
      DHp:40,DShield:0,N:2,maxHp:500,maxShield:0,cost:1500,complete:true,timeMs:9000},
    {kind:'stage3_builder_death',unitId:3,type:'builder',deathMs:8000,weight:1,damageKilled:true,
      transported:false,zoneId:null,ageMs:null,scoreable:false,seq:8,timeMs:8000},
    {kind:'stage3_zone',action:'create',zoneId:1,birthMs:5000,timeMs:5000},
    {kind:'end',reason:'replay_end',timeMs:10000},
  ];
  const result=compile(rows);
  assert.equal(result.summary.dropped.ambushShort,1);
  assert.deepEqual(result.events.map(e=>e.type),['STAGE3_BARRAGE','STAGE3_GRINDER']);
  assert.equal(result.events[0].N,2);
  assert.equal(result.events[1].reason,'OUTSIDE_ACTIVE_ZONE');
  assert.equal(result.summary.zones.created,1);
});

test('ambush severity uses damage, warning frequency, duration and value terms',()=>{
  const event={D_hp:50,D_sh:0,N:8,T_ms:30000,HP_max:100,Shield_max:0,cost:1000,died:false};
  const item=loss(event,DEFAULTS.ambush,DEFAULTS.alpha,DEFAULTS.beta,true);
  assert.equal(item.L,.45);
  assert.equal(item.W,1);
  assert.equal(item.w,1);
  assert.equal(item.Tf,1.5);
  assert.ok(Math.abs(item.s-.675)<1e-12);
});

test('stage3 scoring saturates two loss families and anchor-merges grinder deaths',()=>{
  const events=[
    {id:'A',type:'STAGE3_AMBUSH',scoreable:true,D_hp:50,D_sh:0,N:8,T_ms:30000,
      HP_max:100,Shield_max:0,cost:1000,died:false,unit:{id:1,type:'tank'}},
    {id:'H',type:'STAGE3_BARRAGE',scoreable:true,D_hp:500,D_sh:0,N:4,T_ms:0,
      HP_max:500,Shield_max:0,cost:1000,destroyed:true,unit:{id:2,type:'turret'}},
    {id:'G1',type:'STAGE3_GRINDER',scoreable:true,t_start:40000,zoneId:7,zoneBirthMs:0,weight:1},
    {id:'G2',type:'STAGE3_GRINDER',scoreable:true,t_start:45000,zoneId:7,zoneBirthMs:0,weight:10},
    {id:'G3',type:'STAGE3_GRINDER',scoreable:true,t_start:45001,zoneId:7,zoneBirthMs:0,weight:1},
  ];
  const score=scoreEvents(events,{durationMs:1200000});
  assert.equal(score.breakdown[0].S,.675);
  assert.equal(score.breakdown[1].S,1);
  assert.equal(score.breakdown[2].items.length,2); // 45.001s is outside the first 5s anchor window.
  assert.equal(score.breakdown[2].items[0].weight,11);
  assert.equal(score.breakdown[2].items[0].points,DEFAULTS.grinder.SingleMax);
  assert.equal(score.breakdown[2].items[1].points,DEFAULTS.grinder.SingleMax*.1);
  assert.equal(score.breakdown[2].cap,DEFAULTS.grinder.Cap);
  assert.equal(score.calibration.provisional,true);
  assert.ok(score.total>=0&&score.total<=100);
});

test('duration normalization is clipped for short and long matches',()=>{
  const base={id:'A',type:'STAGE3_AMBUSH',scoreable:true,D_hp:100,D_sh:0,N:8,T_ms:30000,
    HP_max:100,Shield_max:0,cost:1000,died:true,unit:{id:1,type:'tank'}};
  assert.equal(scoreEvents([base],{durationMs:1000}).breakdown[0].durationScale,.5);
  assert.equal(scoreEvents([base],{durationMs:9999999}).breakdown[0].durationScale,2);
});
