'use strict';

const {signature}=require('./execution-mapping.js');
const {isAgent}=require('./low-attribution.js');
const {curve,piecewise}=require('./scoring-curves');

const VERSION='stage1-v2';
const SEVERITY_NAMES=['normal','mild','moderate','severe'];
const SEVERITY_WEIGHTS=[0,1,2,4];
const THRESHOLDS=Object.freeze({
  decisionCycleSeconds:[30,45,75],
  waitAfterActionSeconds:[10,20,40],
  nativeIntervalSeconds:[10,20,40],
  mappingDelaySeconds:[0.5,1,2],
  longOrderGapSeconds:30,
  mappingCleanMeanSeconds:0.5,
  mappingOutlierShare:0.05,
});

const average=values=>values.length?values.reduce((sum,value)=>sum+value,0)/values.length:null;
const quantile=(values,p)=>{
  if(!values.length)return null;
  const sorted=[...values].sort((a,b)=>a-b),position=(sorted.length-1)*p,index=Math.floor(position);
  return sorted[index]+(sorted[Math.min(index+1,sorted.length-1)]-sorted[index])*(position-index);
};
const round=(value,digits=4)=>value==null?null:Number(value.toFixed(digits));
const share=(count,total)=>total?count/total:null;
const isFlag=(args,name)=>args?.[name]===true;
const inGame=command=>command.meta?.match!=='none';

function robustStats(values,minExcess=0){
  const finite=values.filter(Number.isFinite),median=quantile(finite,.5),q1=quantile(finite,.25),q3=quantile(finite,.75);
  let threshold=null;
  if(finite.length>=4){
    const mad=quantile(finite.map(value=>Math.abs(value-median)),.5);
    threshold=Math.max(q3+1.5*(q3-q1),median+3*1.4826*mad,median+minExcess);
  }
  const clean=threshold==null?finite:finite.filter(value=>value<=threshold);
  return {count:finite.length,mean:round(average(finite)),cleanMean:round(average(clean)),median:round(median),
    p90:round(quantile(finite,.9)),p95:round(quantile(finite,.95)),max:finite.length?round(Math.max(...finite)):null,
    threshold:round(threshold),outliers:finite.length-clean.length,outlierShare:round(share(finite.length-clean.length,finite.length))};
}

function tierSummary(samples,thresholds,valueOf=value=>value){
  const counts=Object.fromEntries(SEVERITY_NAMES.map(name=>[name,0])),rows=[];
  for(const sample of samples){
    const value=valueOf(sample);
    if(!Number.isFinite(value))continue;
    let index=thresholds.findIndex(limit=>value<=limit);
    if(index<0)index=3;
    const severity=SEVERITY_NAMES[index],weight=SEVERITY_WEIGHTS[index];
    counts[severity]++;
    rows.push({value:round(value),severity,weight});
  }
  const total=rows.length,weightedCount=rows.reduce((sum,row)=>sum+row.weight,0);
  return {thresholds:[...thresholds],weights:Object.fromEntries(SEVERITY_NAMES.map((name,index)=>[name,SEVERITY_WEIGHTS[index]])),
    count:total,counts,shares:Object.fromEntries(SEVERITY_NAMES.map(name=>[name,round(share(counts[name],total))])),
    abnormalCount:total-counts.normal,abnormalShare:round(share(total-counts.normal,total)),weightedCount,
    weightedBurden:round(share(weightedCount,total)),stats:robustStats(rows.map(row=>row.value))};
}

function timingRows(classified){
  const ab=classified.filter(command=>command.type==='A'||command.type==='B'),gaps=[],cycles=[];
  for(let index=1;index<ab.length;index++){
    const first=ab[index-1],last=ab[index];
    gaps.push({type:`${first.type}-${last.type}`,seconds:round(last.time-first.time),firstLine:first.line,lastLine:last.line,
      firstTime:first.time,lastTime:last.time,firstCommand:first.command,lastCommand:last.command});
  }
  const blocks=[];
  for(const command of ab){
    if(blocks.at(-1)?.type!==command.type)blocks.push({type:command.type,items:[]});
    blocks.at(-1).items.push(command);
  }
  for(let index=0;index<blocks.length-1;index++){
    const block=blocks[index],next=blocks[index+1];
    if(block.type!=='A'||next.type!=='B')continue;
    cycles.push({seconds:round(next.items.at(-1).time-block.items[0].time),observeToAction:round(next.items[0].time-block.items[0].time),
      firstLine:block.items[0].line,lastLine:next.items.at(-1).line,A_count:block.items.length,B_count:next.items.length});
  }
  return {ab,gaps,cycles};
}

function orderGapMetrics(high,durationSeconds){
  const intervals=[];
  for(let index=1;index<high.length;index++){
    const first=high[index-1],last=high[index];
    intervals.push({seconds:round(last.time-first.time),firstLine:first.line,lastLine:last.line,firstTime:first.time,lastTime:last.time});
  }
  const long=intervals.filter(row=>row.seconds>THRESHOLDS.longOrderGapSeconds);
  const longSeconds=long.reduce((sum,row)=>sum+row.seconds,0),last=high.at(-1);
  return {definition:'adjacent direct order-* gaps; a gap over 30s contributes its full duration',count:intervals.length,
    over30Count:long.length,over30Seconds:round(longSeconds),over30ShareOfMatch:round(share(longSeconds,durationSeconds)),
    longestSeconds:intervals.length?Math.max(...intervals.map(row=>row.seconds)):null,
    tailSeconds:last?round(Math.max(0,durationSeconds-last.time)):null,intervals};
}

function isMiningBuild(command){
  if(command.name!=='rwx-game-order-build')return false;
  return /^(?:custom:)?extractor(?:t1)?$/i.test(String(command.args.type||''));
}
function isMilitaryProduction(command){
  if(!['rwx-game-order-action','rwx-game-keep-set'].includes(command.name))return false;
  const action=String(command.args.action||'');
  // Opening milestone follows the historical reports: workers and light scouting
  // production do not count as the first committed military-production order.
  return /^u_/i.test(action)&&!/^u_(?:builder|combatengineer|scout|lightgunship)$/i.test(action);
}
function isBuilderProduction(command){
  return ['rwx-game-order-action','rwx-game-keep-set'].includes(command.name)
    &&/^u_(?:builder|builderShip|combatEngineer|mechEngineer)$/i.test(String(command.args.action||''));
}
function scoredTiming(rows){
  const stats=robustStats(rows.map(row=>row.seconds),5);
  return {...stats,rows:rows.map(row=>({...row,outlier:stats.threshold!=null&&row.seconds>stats.threshold,
    excessSeconds:Math.max(0,row.seconds-(stats.cleanMean??0))}))};
}
function nativeCadence(values){
  const counts=[0,0,0,0];
  for(const x of values)if(x>3)counts[x<5?0:x<12?1:x<=25?2:3]++;
  const weights=[1,3,8,20],count=values.length;
  return {count,counts,weights,shares:counts.map(n=>count?n/count:0),
    coefficient:count?counts.reduce((sum,n,i)=>sum+n*weights[i],0)/(20*count):0};
}

function commandStorms(high,matches){
  const byLine=new Map(matches.map(row=>[row.highLine,row])),groups=new Map();
  for(const command of high){
    const key=signature(command);
    if(!groups.has(key))groups.set(key,[]);
    groups.get(key).push(command);
  }
  const storms=[];
  for(const commands of groups.values()){
    if(commands.length<5)continue;
    const intervals=commands.slice(1).map((command,index)=>command.time-commands[index].time);
    const rows=commands.map(command=>byLine.get(command.line)).filter(Boolean);
    const unmatched=rows.filter(row=>row.status==='unmatched').length;
    if(quantile(intervals,.5)>10||unmatched/commands.length<.8)continue;
    storms.push({command:commands[0].command,count:commands.length,unmatched,matched:rows.filter(row=>row.delay!=null).length,
      firstLine:commands[0].line,lastLine:commands.at(-1).line,firstTime:commands[0].time,lastTime:commands.at(-1).time,
      medianIntervalSeconds:round(quantile(intervals,.5))});
  }
  return storms;
}

function malformedOrders(high){
  const rows=[];
  const empty=(args,key)=>args[key]===true||args[key]==='';
  for(const command of high){
    const {name,args}=command,missing=[];
    if(name==='rwx-game-order-build'){
      for(const key of ['builder','type','x','y'])if(args[key]==null||empty(args,key))missing.push(key);
    }else {
      if(!['rwx-game-order-push'].includes(name)&&(args.units==null||empty(args,'units'))&&!['rwx-game-order-cancel'].includes(name))missing.push('units');
      if(name==='rwx-game-order-cancel'&&(args.unit==null||empty(args,'unit')))missing.push('unit');
      if(['rwx-game-order-move','rwx-game-order-attack-move','rwx-game-order-patrol','rwx-game-order-guard-at','rwx-game-order-rally'].includes(name))for(const key of ['x','y'])if(args[key]==null||empty(args,key))missing.push(key);
      if(['rwx-game-order-attack','rwx-game-order-guard','rwx-game-order-follow','rwx-game-order-load-into','rwx-game-order-load-up','rwx-game-order-repair','rwx-game-order-assist','rwx-game-order-reclaim'].includes(name)&&(args.target==null||empty(args,'target')))missing.push('target');
      if(name==='rwx-game-order-action'&&(args.action==null||empty(args,'action')))missing.push('action');
    }
    if(missing.length)rows.push({line:command.line,time:command.time,command:command.command,missing});
  }
  return rows;
}

function batchSilences(classified){
  const ab=classified.filter(command=>command.type==='A'||command.type==='B'),blocks=[];
  for(const command of ab){
    if(blocks.at(-1)?.type!==command.type)blocks.push({type:command.type,items:[]});
    blocks.at(-1).items.push(command);
  }
  const rows=[];
  for(let index=0;index<blocks.length-1;index++){
    const block=blocks[index],next=blocks[index+1];
    if(block.type!=='B'||block.items.length<5)continue;
    const silence=next.items[0].time-block.items.at(-1).time;
    if(silence<=30)continue;
    rows.push({count:block.items.length,firstLine:block.items[0].line,lastLine:block.items.at(-1).line,
      firstTime:block.items[0].time,lastTime:block.items.at(-1).time,silenceSeconds:round(silence)});
  }
  return rows;
}

function anchorConcentration(commands){
  const spatial=commands.filter(command=>['rwx-game-threat-list','rwx-game-map-show'].includes(command.name)&&command.args.x!=null&&command.args.y!=null);
  const groups=new Map();
  for(const command of spatial){
    const key=[command.name,command.args.x,command.args.y,command.args.radius??command.args['half-width']??'',command.args['half-height']??''].join('|');
    groups.set(key,(groups.get(key)||0)+1);
  }
  const maximum=groups.size?Math.max(...groups.values()):0;
  return {spatialQueryCount:spatial.length,distinctAnchors:groups.size,largestAnchorCount:maximum,largestAnchorShare:round(share(maximum,spatial.length))};
}

function buildMetrics({classified,port,low,matches,durationSeconds,gapStats={},attribution=null}){
  const isAgentRow=attribution?.telemetry?row=>isAgent(attribution.sourceOf(row)):()=>true;
  const agentLow=low.filter(isAgentRow);
  const commands=port.commands.filter(inGame),counts={A:classified.filter(row=>row.type==='A').length,B:classified.filter(row=>row.type==='B').length};
  const {gaps,cycles}=timingRows(classified),waits=gaps.filter(row=>row.type==='B-A');
  const orders=port.high,storms=commandStorms(orders,matches),stormLines=new Set();
  for(const storm of storms)for(const command of orders)if(command.line>=storm.firstLine&&command.line<=storm.lastLine&&signature(command)===signature(orders.find(item=>item.line===storm.firstLine)))stormLines.add(command.line);
  const unmatched=matches.filter(row=>row.status==='unmatched'),stormUnmatched=unmatched.filter(row=>stormLines.has(row.highLine)).length;
  const immediate=matches.filter(row=>row.status==='matched'&&row.delayMin!=null&&!/\s--(?:allow-unaffordable|after-upgrade)(?:\s|$)/.test(row.command));
  const immediateStats=robustStats(immediate.map(row=>row.delayMin),.5);
  const mappingHealth=immediateStats.count===0?'unknown':immediateStats.cleanMean<THRESHOLDS.mappingCleanMeanSeconds&&
    (immediateStats.outlierShare??0)<THRESHOLDS.mappingOutlierShare?'normal':'abnormal';
  const orderGaps=orderGapMetrics(orders,durationSeconds);
  const productionNative=low.filter(row=>typeof row.action==='string'&&/^u_/i.test(row.action)&&!row.stop_current_action).length;
  const productionNativeAgent=agentLow.filter(row=>typeof row.action==='string'&&/^u_/i.test(row.action)&&!row.stop_current_action).length;
  const enemyLists=commands.filter(command=>command.name==='rwx-game-unit-list'&&String(command.args.owner||'').toLowerCase()==='enemy').length;
  const rulesQueries=commands.filter(command=>command.name.startsWith('rwx-query-'));
  const configCount=port.excluded.length,allowCount=orders.filter(command=>isFlag(command.args,'allow-unaffordable')).length;
  return {version:VERSION,durationSeconds,counts:{A:counts.A,B:counts.B,directOrders:orders.length,configCommands:configCount,nativeCommands:low.length},
    rates:{queriesPerMinute:round(durationSeconds?counts.A*60/durationSeconds:null),nativeProductionPerMinute:round(durationSeconds?productionNative*60/durationSeconds:null),
      agentProductionPerMinute:round(durationSeconds?productionNativeAgent*60/durationSeconds:null),
      configShareOfB:round(share(configCount,counts.B)),allowUnaffordableShareOfB:round(share(allowCount,counts.B))},
    opening:{firstOrderSeconds:orders[0]?.time??null,firstMiningBuildSeconds:orders.find(isMiningBuild)?.time??null,
      firstBuilderProductionSeconds:commands.find(isBuilderProduction)?.time??null,
      firstMilitaryProductionSeconds:commands.find(isMilitaryProduction)?.time??null},
    cadence:{decisionCycles:tierSummary(cycles,THRESHOLDS.decisionCycleSeconds,row=>row.seconds),
      decisionTiming:scoredTiming(cycles),waitTiming:scoredTiming(waits),
      waitAfterAction:tierSummary(waits,THRESHOLDS.waitAfterActionSeconds,row=>row.seconds),orderGaps,
      AA:{...gapStats['A-A'],outlierShare:round(share(gapStats['A-A']?.outliers,gapStats['A-A']?.n))},
      BB:{...gapStats['B-B'],outlierShare:round(share(gapStats['B-B']?.outliers,gapStats['B-B']?.n))},batchSilences:batchSilences(classified)},
    native:{intervals:tierSummary(low.slice(1).map((row,index)=>row.time_s-low[index].time_s),THRESHOLDS.nativeIntervalSeconds),
      scoringCadence:nativeCadence(low.slice(1).map((row,index)=>row.time_s-low[index].time_s)),
      agentIntervals:tierSummary(agentLow.slice(1).map((row,index)=>row.time_s-agentLow[index].time_s),THRESHOLDS.nativeIntervalSeconds),
      productionCommandCount:productionNative,agentProductionCommandCount:productionNativeAgent,
      agentCommandCount:agentLow.length,allCommandCount:low.length,
      automationCommandCount:low.length-agentLow.length},
    attribution:{telemetry:Boolean(attribution?.telemetry),agentSources:['agent','pending'],counts:attribution?.counts??{},
      note:'agent/pending 为 Agent，keep 为补产，auto 为微操，engine 为引擎/UI，unknown 无证据。评分节奏与生产率使用全部底层命令，提交者分档用于审计。'},
    mapping:{statusCounts:Object.fromEntries(['matched','partial','unmatched','ambiguous','unobservable'].map(status=>[status,matches.filter(row=>row.status===status).length])),
      immediateDelay:{definition:'unique matched direct orders excluding --allow-unaffordable/--after-upgrade; legacy seconds use delayMin',...immediateStats,
        severity:tierSummary(immediate.map(row=>row.delayMin),THRESHOLDS.mappingDelaySeconds),health:mappingHealth,
        normalLimits:{cleanMeanBelow:THRESHOLDS.mappingCleanMeanSeconds,outlierShareBelow:THRESHOLDS.mappingOutlierShare},
        excludedDeferred:matches.filter(row=>row.delayMin!=null&&/\s--(?:allow-unaffordable|after-upgrade)(?:\s|$)/.test(row.command)).length},
      rawNoTrace:{count:unmatched.length,total:orders.length,rate:round(share(unmatched.length,orders.length))},
      stormAdjustedNoTrace:{count:unmatched.length-stormUnmatched,total:orders.length-stormLines.size,rate:round(share(unmatched.length-stormUnmatched,orders.length-stormLines.size))},commandStorms:storms},
    diagnostics:{enemyUnitListCount:enemyLists,eventsQueryCount:commands.filter(command=>command.name==='rwx-game-events').length,
      threatListCount:commands.filter(command=>command.name==='rwx-game-threat-list').length,ruleQueryCount:rulesQueries.length,
      ruleQueriesFirst120Seconds:rulesQueries.filter(command=>command.time<=120).length,queryAnchors:anchorConcentration(commands),
      malformedOrders:malformedOrders(orders)},
  };
}

function scoreMetrics(metrics){
  const opening=metrics.opening,breakdown=[];
  const add=(id,label,points,evidence,cap)=>breakdown.push({id,label,points:round(points,1),cap,evidence});
  add('A1-first-order','首个行动指令',piecewise(opening.firstOrderSeconds,[[0,5,0,5],[5,8,5,8],[8,20,8,12],[20,60,12,15]],15),{seconds:opening.firstOrderSeconds},15);
  add('A1-first-mining','首个采矿建造',piecewise(opening.firstMiningBuildSeconds,[[0,20,0,5]],5),{seconds:opening.firstMiningBuildSeconds},5);
  add('A1-first-builder','首个建造者生产',piecewise(opening.firstBuilderProductionSeconds,[[0,30,0,5]],5),{seconds:opening.firstBuilderProductionSeconds},5);
  const decision=metrics.cadence.decisionTiming,wait=metrics.cadence.waitTiming;
  add('A2-decision-cycle','A→B 周期去离群均值',piecewise(decision.cleanMean,[[5,15,0,4],[15,30,4,8]]),decision,8);
  add('A2-wait-after-action','B→A 等待去离群均值',piecewise(wait.cleanMean,[[3,8,0,5],[8,25,5,18,'exp']]),wait,18);
  const outlierPenalty=(timing,bins,eligible=()=>true)=>timing.rows.filter(row=>row.outlier&&eligible(row))
    .reduce((sum,row)=>sum+(bins.find(([upper])=>row.excessSeconds<upper)||bins.at(-1))[1],0);
  add('A2-wait-outliers','B→A 等待离群值',Math.min(10,outlierPenalty(wait,[[20,.8],[40,1.2],[60,1.5],[Infinity,2]])),wait,10);
  add('A2-decision-outliers','A→B 决策离群值',Math.min(8,outlierPenalty(decision,[[30,.5],[60,.8],[Infinity,1.5]],row=>row.A_count/(row.A_count+row.B_count)>.5)),decision,8);
  add('A4-native-cadence','相邻底层命令间隔',15*metrics.native.scoringCadence.coefficient,metrics.native.scoringCadence,15);
  for(const [id,label,item] of [['A5-AA','A→A 离群占比',metrics.cadence.AA],['A5-BB','B→B 离群占比',metrics.cadence.BB]]){
    const rate=item.outlierShare??0,points=rate<.05?0:curve(rate,.05,.75,id==='A5-AA'?2:1,8,'exp');
    add(id,label,points,{outlierShare:rate,outliers:item.outliers,count:item.n},8);
  }
  const production=metrics.rates.nativeProductionPerMinute??0;
  add('A4-production-rate','底层生产指令 / 分钟',curve(production,0,8,6,0,'quadratic',-2),{perMinute:production},6);
  const {A,B}=metrics.counts,ratio=B?A/B:A?Infinity:0;
  add('A5-query-action-ratio','A / B',!A?10:ratio<.3?curve(ratio,0,.3,10,0,'quadratic',3)
    :ratio>3?curve(ratio,3,8,0,10,'quadratic',3):0,{A,B,ratio:Number.isFinite(ratio)?ratio:null},10);
  const deduction=round(breakdown.reduce((sum,row)=>sum+row.points,0),1);
  return {version:VERSION,score:round(Math.max(0,100-deduction),1),startingScore:100,deduction,breakdown,
    systemHealth:{mappingDelay:metrics.mapping.immediateDelay.health,scoreStatus:metrics.mapping.immediateDelay.health==='normal'?'valid':'provisional',
      note:'高层→底层即时延迟是环境健康检查；异常时总分标为 provisional，但不把系统延迟扣到 Agent。'},
    notScored:['B1/B2/B3/B5 情报与查询策略','C1 无落地痕迹','C2 命令风暴','C3 参数/格式错误','行动空窗、结尾静默、首个军事生产（仅保留材料）']};
}

module.exports={VERSION,THRESHOLDS,robustStats,tierSummary,timingRows,orderGapMetrics,buildMetrics,scoreMetrics,scoredTiming,nativeCadence};
