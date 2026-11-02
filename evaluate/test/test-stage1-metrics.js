'use strict';

const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {tierSummary,buildMetrics,scoreMetrics}=require('../stage1-metrics.js');
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
  const metrics={opening:{firstOrderSeconds:0,firstMiningBuildSeconds:0,firstMilitaryProductionSeconds:0},
    cadence:{decisionCycles:tierSummary([1],[30,45,75]),waitAfterAction:tierSummary([1],[10,20,40]),
      orderGaps:{over30ShareOfMatch:0,over30Seconds:0,tailSeconds:0},AA:{n:1,outliers:0,outlierShare:0},BB:{n:1,outliers:0,outlierShare:0}},
    native:{intervals:tierSummary([1],[10,20,40])},mapping:{immediateDelay:{health:'abnormal'}}};
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
  assert.equal(summary.stage1Score.version,'stage1-v1');
  assert.equal(summary.stage1Metrics.counts.directOrders,30);
  const html=fs.readFileSync(path.join(result.out,'report.html'),'utf8');
  assert.match(html,/Stage1 v1 实时性指标与初评分/);
  assert.match(html,/审计结论\/限制/);
});
