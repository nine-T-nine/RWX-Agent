'use strict';

const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {tierSummary,buildMetrics,scoreMetrics,scoredTiming,nativeCadence}=require('../stage1-metrics.js');
const {evaluate}=require('../evaluate_stage_1.js');

const repo=path.resolve(__dirname,'../..');
fs.mkdirSync(path.join(repo,'agent-tmp'),{recursive:true});
const temp=fs.mkdtempSync(path.join(repo,'agent-tmp/stage1-metrics-test-'));
test.after(()=>fs.rmSync(temp,{recursive:true,force:true}));

test('fixed tiers keep boundary values in the lower severity band',()=>{
  const result=tierSummary([10,10.001,20,20.001,40,40.001],[10,20,40]);
  assert.deepEqual(result.counts,{normal:1,mild:2,moderate:2,severe:1});
  assert.equal(result.weightedCount,10);
});

test('command storms are reported separately and removed only from the adjusted no-trace view',()=>{
  const high=[];
  for(let index=0;index<6;index++)high.push({line:index+1,time:10+index*2,name:'rwx-game-order-action',
    command:'rwx-game-order-action --units 7 --action extractorT3_0',args:{units:'7',action:'extractorT3_0'},meta:{match:'1'}});
  high.push({line:7,time:30,name:'rwx-game-order-action',command:'rwx-game-order-action --units 8 --action u_tank',args:{units:'8',action:'u_tank'},meta:{match:'1'}});
  const matches=high.map((command,index)=>({highLine:command.line,status:index===0||index===6?'matched':'unmatched',delayMin:index===0||index===6?0:null,command:command.command}));
  const classified=high.map(command=>({line:command.line,time:command.time,type:'B',command:command.command,token:command.name}));
  const metrics=buildMetrics({classified,port:{commands:high,high,excluded:[]},low:[
    {time_s:10,action:'extractorT3_0',stop_current_action:false},{time_s:30,action:'u_tank',stop_current_action:false}],matches,durationSeconds:100,gapStats:{'A-A':{n:0,outliers:0},'B-B':{n:6,outliers:0}}});
  assert.equal(metrics.mapping.commandStorms.length,1);
  assert.deepEqual(metrics.mapping.rawNoTrace,{count:5,total:7,rate:0.7143});
  assert.deepEqual(metrics.mapping.stormAdjustedNoTrace,{count:0,total:1,rate:0});
  assert.equal(metrics.native.productionCommandCount,1);
});

test('mapping delay is a health gate and does not add an Agent deduction',()=>{
  const metrics={opening:{firstOrderSeconds:0,firstMiningBuildSeconds:0,firstBuilderProductionSeconds:0},
    rates:{nativeProductionPerMinute:8},counts:{A:10,B:10},
    cadence:{decisionCycles:tierSummary([1],[30,45,75]),waitAfterAction:tierSummary([1],[10,20,40]),
      decisionTiming:scoredTiming([{seconds:1}]),waitTiming:scoredTiming([{seconds:1}]),
      orderGaps:{over30ShareOfMatch:0,over30Seconds:0,tailSeconds:0},AA:{n:1,outliers:0,outlierShare:0},BB:{n:1,outliers:0,outlierShare:0}},
    native:{intervals:tierSummary([1],[10,20,40]),scoringCadence:nativeCadence([1])},mapping:{immediateDelay:{health:'abnormal'}}};
  const score=scoreMetrics(metrics);
  assert.equal(score.score,100);
  assert.equal(score.systemHealth.scoreStatus,'provisional');
  metrics.mapping.immediateDelay.health='unknown';
  assert.equal(scoreMetrics(metrics).systemHealth.scoreStatus,'provisional');
  metrics.mapping.immediateDelay.health='normal';
  assert.equal(scoreMetrics(metrics).systemHealth.scoreStatus,'valid');
});

test('mapping health uses only complete immediate matches',()=>{
  const high=[
    {line:1,time:10,name:'rwx-game-order-stop',command:'rwx-game-order-stop --units 7',args:{units:'7'},meta:{match:'1'}},
    {line:2,time:20,name:'rwx-game-order-stop',command:'rwx-game-order-stop --units 8',args:{units:'8'},meta:{match:'1'}},
  ];
  const metrics=buildMetrics({classified:high.map(command=>({line:command.line,time:command.time,type:'B',command:command.command,token:command.name})),
    port:{commands:high,high,excluded:[]},low:[],matches:[
      {highLine:1,status:'matched',delayMin:.1,command:high[0].command},
      {highLine:2,status:'partial',delayMin:8,command:high[1].command},
    ],durationSeconds:60,gapStats:{'A-A':{n:0,outliers:0},'B-B':{n:1,outliers:0}}});
  assert.equal(metrics.mapping.immediateDelay.count,1);
  assert.equal(metrics.mapping.immediateDelay.cleanMean,.1);
  assert.equal(metrics.mapping.immediateDelay.health,'normal');
});

test('full stage1 evaluation writes metrics, score and updated HTML without replacing old artifacts',async()=>{
  const commands=path.join(temp,'commands-synthetic.txt'),replay=path.join(temp,'replay.jsonl'),lines=[],low=[];
  const meta='name=Agent;player=0;faction=A(0);role=player;match=1';
  for(let index=0;index<60;index++){
    const seconds=index*6,clock=`${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}:000`;
    if(index%2===0)lines.push(`[${clock}]<${meta}><rwx-game-state>`);
    else {
      lines.push(`[${clock}]<${meta}><rwx-game-order-stop --units 7>`);
      low.push({tick:seconds*60,time_s:seconds,team:0,units:[7],unit_types:['tank'],type:null,action:null,target_unit:-1,target_point:null,
        rally:null,attack_mode:null,build:null,build_count:null,command_queued:false,stop_current_action:false,clear_existing_orders:true,noop:false});
    }
  }
  fs.writeFileSync(commands,lines.join('\n'));fs.writeFileSync(replay,low.map(row=>JSON.stringify(row)).join('\n')+'\n');
  const result=await evaluate(commands,replay,{outputRoot:temp});
  for(const name of ['report.html','report.txt','summary.json','execution_summary.json','stage1_metrics.json','score.json','score.txt','mapping_rules.json'])assert.ok(fs.existsSync(path.join(result.out,name)),name);
  const summary=JSON.parse(fs.readFileSync(path.join(result.out,'summary.json'),'utf8'));
  assert.equal(summary.stage1Score.version,'stage1-v2');
  assert.equal(summary.stage1Metrics.counts.directOrders,30);
  const html=fs.readFileSync(path.join(result.out,'report.html'),'utf8');
  assert.match(html,/Stage1 v2 实时性指标与初评分/);
  assert.match(html,/审计结论\/限制/);
});

function metricFixture(){
  return {opening:{firstOrderSeconds:0,firstMiningBuildSeconds:0,firstBuilderProductionSeconds:0},
    counts:{A:10,B:10},rates:{nativeProductionPerMinute:8},
    cadence:{decisionTiming:scoredTiming([]),waitTiming:scoredTiming([]),AA:{n:0,outliers:0},BB:{n:0,outliers:0}},
    native:{scoringCadence:nativeCadence([])},mapping:{immediateDelay:{health:'normal'}}};
}
const points=(metrics,id)=>scoreMetrics(metrics).breakdown.find(row=>row.id===id).points;
test('v2 opening breakpoints, builder milestone and missing milestones',()=>{
  const m=metricFixture();
  for(const [seconds,expected]of [[0,0],[5,5],[8,8],[20,12],[60,15],[100,15],[null,15]]){
    m.opening.firstOrderSeconds=seconds;assert.equal(points(m,'A1-first-order'),expected);
  }
  m.opening.firstMiningBuildSeconds=20;assert.equal(points(m,'A1-first-mining'),5);
  m.opening.firstBuilderProductionSeconds=30;assert.equal(points(m,'A1-first-builder'),5);
});
test('clean means and distance-based outliers use strict A-majority eligibility',()=>{
  const m=metricFixture();
  m.cadence.decisionTiming=scoredTiming([...Array(10).fill({seconds:15,A_count:2,B_count:1}),{seconds:90,A_count:1,B_count:1}]);
  assert.equal(points(m,'A2-decision-cycle'),4);assert.equal(points(m,'A2-decision-outliers'),0);
  m.cadence.decisionTiming.rows.at(-1).A_count=2;assert.equal(points(m,'A2-decision-outliers'),1.5);
  m.cadence.waitTiming=scoredTiming([...Array(10).fill({seconds:8}),{seconds:28},{seconds:48},{seconds:68}]);
  assert.equal(points(m,'A2-wait-after-action'),5);assert.equal(points(m,'A2-wait-outliers'),4.7);
  m.cadence.waitTiming=scoredTiming([{seconds:25}]);assert.equal(points(m,'A2-wait-after-action'),18);
});
test('native cadence fills exact boundaries and weights shares over every adjacent interval',()=>{
  const m=metricFixture();m.native.scoringCadence=nativeCadence([0,3,4,5,12,25,26]);
  assert.deepEqual(m.native.scoringCadence.counts,[1,1,2,1]);
  assert.equal(points(m,'A4-native-cadence'),4.3);
  m.cadence.AA={outlierShare:.05};m.cadence.BB={outlierShare:.75};
  assert.equal(points(m,'A5-AA'),2);assert.equal(points(m,'A5-BB'),8);
  m.counts.A=0;assert.equal(points(m,'A5-query-action-ratio'),10);
  m.counts={A:800,B:100};assert.equal(points(m,'A5-query-action-ratio'),10);
});
