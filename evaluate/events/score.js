'use strict';
const {clamp,piecewise}=require('../scoring-curves');
const VERSION='stage2-v2';
const DEFAULTS=Object.freeze({baseline:100,
  weights:Object.freeze({AMBUSH_SINGLE_MAX:18,GRINDER_SINGLE_MAX:15,BUILDER_IDLE_SINGLE_MAX:8,FACTORY_IDLE_SINGLE_MAX:5,FUNDS_SINGLE_MAX:7}),
  caps:Object.freeze({AMBUSH:40,GRINDER:20,STALE:15,BUILDER_IDLE:25,FACTORY_IDLE:20,FUNDS:30}),
  ambush:Object.freeze({responseSeconds:30,deadZone:1,fullImpact:12,gamma:3}),
  grinder:Object.freeze({responseSeconds:45,deadZone:3,fullImpact:18,gamma:1.5}),
  hp:Object.freeze({unitLow:500,unitMedium:1500,buildingLow:2000}),
  funds:Object.freeze({K:30,incomeFieldPerSecond:1/0.64,spendFloor:800,spendShare:.25,rho1:.5,rho2:.25})});
const FAMILY=Object.freeze({AMBUSH:'AMBUSH',GRINDER:'GRINDER',FEED:'GRINDER',
  STALE_BURST:'STALE',STALE_COMMAND:'STALE',COMMAND_FAILURE:'STALE',
  BUILDER_IDLE:'BUILDER_IDLE',BUILDER_GAP:'BUILDER_IDLE',FACTORY_IDLE:'FACTORY_IDLE',PRODUCTION_GAP:'FACTORY_IDLE',FUNDS:'FUNDS'});
const round=x=>Number(x.toFixed(3));
function configuration(options={}){
  const config={baseline:options.baseline??DEFAULTS.baseline};
  for(const key of ['weights','caps','ambush','grinder','hp','funds'])config[key]={...DEFAULTS[key],...options[key]};
  for(const group of [config.weights,config.caps,config.hp,config.funds])
    for(const [key,value]of Object.entries(group))if(!Number.isFinite(value)||value<0)throw Error('Invalid scoring parameter: '+key);
  if(!Number.isFinite(config.baseline)||config.baseline<0)throw Error('Invalid baseline');
  for(const key of ['ambush','grinder']){
    const c=config[key];
    if(!Object.values(c).every(Number.isFinite)||c.responseSeconds<=0||c.deadZone<0||c.fullImpact<=c.deadZone||c.gamma<=0)throw Error('Invalid '+key+' parameters');
  }
  if(config.funds.K<=0||config.funds.incomeFieldPerSecond<=0||config.funds.rho1>1||config.funds.rho2>1)throw Error('Invalid funds parameters');
  return config;
}
/**
 * Families whose `UNCERTAIN` still scores. An idle pair is an engine-side fact; the only accepted
 * excuses are a proven inability (`EXPLAINED`) or the Agent having touched the asset (`EXCLUDED`).
 * The other families keep `UNCERTAIN` unscored: there it means the event itself is not confirmed.
 */
const SCORED_UNCERTAIN=new Set(['BUILDER_IDLE','FACTORY_IDLE']);
function scoreable(event){
  if(event.scoreable===false)return false;
  if(event.status==='EXPLAINED'||event.status==='EXCLUDED')return false;
  if(event.status==='UNCERTAIN')return SCORED_UNCERTAIN.has(FAMILY[event.type]);
  return true;
}
function impactWeight(unit,family,hp=DEFAULTS.hp){
  if(!Number.isFinite(unit.maxHp)||unit.maxHp<=0)return null;
  if(unit.building)return family==='AMBUSH'?(unit.maxHp<=hp.buildingLow?2:4):0;
  const tier=unit.maxHp<=hp.unitLow?0:unit.maxHp<=hp.unitMedium?1:2;
  return (family==='AMBUSH'?[1,2,3]:[3,2,1])[tier];
}
function weightedImpact(event,family,config){
  if(event.impactComplete!==true||!Array.isArray(event.hpLosses))return null;
  let impact=0;
  for(const loss of event.hpLosses){
    const weight=impactWeight(loss,family,config.hp);
    if(weight==null||!Number.isFinite(loss.hpLoss)||loss.hpLoss<0)return null;
    impact+=weight*loss.hpLoss/loss.maxHp;
  }
  return impact;
}
function idleSeverity(family,seconds){
  return family==='BUILDER_IDLE'?piecewise(seconds,[[0,8,0,.3,'exp'],[8,25,.3,.75],[25,60,.75,1,'log']])
    :piecewise(seconds,[[10,30,0,.3],[30,120,.3,1,'exp']]);
}
function deduction(event,config=DEFAULTS){
  const family=FAMILY[event.type];if(!family)return null;
  if(!scoreable(event))return {points:0,reason:'not_scoreable'};
  if(family==='AMBUSH'||family==='GRINDER'){
    const impact=weightedImpact(event,family,config);
    if(impact==null)return {points:0,reason:'missing_hp_loss_evidence'};
    const c=config[family==='AMBUSH'?'ambush':'grinder'];
    const severity=clamp((impact-c.deadZone)/(c.fullImpact-c.deadZone))**c.gamma;
    const responseMs=event.effectiveResponseMs;
    if(responseMs!=null&&(!Number.isFinite(responseMs)||!Number.isFinite(event.t_confirm)))return {points:0,reason:'missing_response_evidence'};
    const delaySeconds=responseMs==null?c.responseSeconds:Math.max(0,(responseMs-event.t_confirm)/1000);
    const response=clamp(delaySeconds/c.responseSeconds);
    return {points:config.weights[family+'_SINGLE_MAX']*severity*response,reason:'weighted_hp_loss_and_response',impact,severity,response,delaySeconds};
  }
  if(family==='STALE'){
    const rows=event.commands||[];let points=0,missing=0;const items=[];
    for(const row of rows){
      if(row.staleInfo!==true)continue;
      if(!Number.isFinite(row.staleAgeMs)||row.staleAgeMs<0){missing++;continue;}
      const seconds=row.staleAgeMs/1000,w=row.kind==='query'?0:row.kind==='configuration'?1:2;
      const factor=seconds<=5?0:seconds<10?.5:seconds<20?1:seconds<40?1.5:seconds<60?2:3;
      points+=w*factor;items.push({commandId:row.commandId,seconds,weight:w,factor});
    }
    return {points,reason:missing?'partial_stale_age_evidence':rows.length?'stale_command_severity':'missing_stale_age_evidence',items,missing};
  }
  if(family==='BUILDER_IDLE'||family==='FACTORY_IDLE'){
    // No feasibility gate here: an unproven pair still counts, and `scoreable` has already removed
    // the pairs that were disproved or excluded.
    const durationMs=event.durationMs??event.gapMs??event.t_end-event.t_start;
    if(!Number.isFinite(durationMs)||durationMs<0)return {points:0,reason:'missing_duration_evidence'};
    const severity=idleSeverity(family,durationMs/1000);
    return {points:config.weights[family+'_SINGLE_MAX']*severity,reason:'idle_with_endpoint_work_available',severity,durationMs};
  }
  if(family==='FUNDS'){
    const severity=piecewise(event.durationSeconds,[[15,120,0,1,'exp']]);
    return {points:config.weights.FUNDS_SINGLE_MAX*severity,reason:'funds_hoarding',severity,durationSeconds:event.durationSeconds};
  }
  return null;
}
/** Median is centred; endpoints use the available two samples. Durations use elapsed seconds. */
function fundsEvents(samples,config=DEFAULTS.funds){
  if(!samples?.length)return [];
  for(let i=0;i<samples.length;i++)if(samples[i].second!==i||!Number.isFinite(samples[i].funds)
    ||!Number.isFinite(samples[i].incomePerSecond??samples[i].incomeField))throw Error('Invalid per-second economy evidence');
  const median=values=>{values.sort((a,b)=>a-b);const n=values.length;return (values[(n-1)>>1]+values[n>>1])/2;};
  const smooth=samples.map((row,i)=>median(samples.slice(Math.max(0,i-1),i+2).map(r=>r.funds)));
  const result=[];let open=null,incomeSum=0;
  const close=second=>{if(open){result.push({id:'FUNDS-'+(result.length+1),type:'FUNDS',status:'DETECTED',scoreable:true,
    t_start:open.start*1000,t_end:second*1000,durationSeconds:open.maxD,peakFunds:open.maxFunds,spends:open.spends});open=null;}};
  const income=row=>row.incomePerSecond??row.incomeField*config.incomeFieldPerSecond;
  for(let i=0;i<samples.length;i++){
    incomeSum+=income(samples[i]);if(i>=30)incomeSum-=income(samples[i-30]);
    const theta=config.K*Math.max(0,incomeSum/Math.min(30,i+1)),funds=smooth[i];
    if(!open&&funds>=theta&&funds>0)open={start:i,D:0,maxD:0,maxFunds:funds,fallStart:null,spends:[]};
    if(!open)continue;
    if(i>open.start){open.D++;open.maxD=Math.max(open.maxD,open.D);}
    open.maxFunds=Math.max(open.maxFunds,funds);
    if(i>0&&funds<smooth[i-1]&&open.fallStart==null)open.fallStart=smooth[i-1];
    if(open.fallStart!=null&&(funds>smooth[i-1]||i===samples.length-1||funds<theta)){
      const trough=funds>smooth[i-1]?smooth[i-1]:funds,s=open.fallStart-trough,M=open.maxFunds;
      open.fallStart=null;
      if(s>Math.max(config.spendFloor,config.spendShare*M)){
        const share=s/M;open.spends.push({second:i,amount:s,share});
        if(share>(config.releaseShare??.8)){close(i);continue;}
        open.D*=share<=.5?config.rho1:config.rho2;
      }
    }
    if(funds<theta)close(i);
  }
  close(samples.length-1);return result;
}
function scoreEvents(events,options={}){
  const config=configuration(options),economy=options.economy;
  const financial=economy?fundsEvents(economy,config.funds):[];
  const families={},excluded={};
  for(const event of [...(events||[]),...financial]){
    const family=FAMILY[event.type];if(!family){excluded[event.type]=(excluded[event.type]||0)+1;continue;}
    const row=families[family]??={raw:0,capped:0,cap:config.caps[family],items:[],counts:{},unscored:null};
    const result=deduction(event,config);if(!result)continue;
    row.counts[result.reason]=(row.counts[result.reason]||0)+1;
    if(!result.points&&(/missing|not_scoreable/.test(result.reason))){
      row.unscored??={count:0,durationMs:0};row.unscored.count++;
      const duration=event.durationMs??event.gapMs??event.t_end-event.t_start;
      row.unscored.durationMs+=Number.isFinite(duration)?Math.max(0,duration):0;
    }
    row.raw+=result.points;
    row.items.push({id:event.id,type:event.type,t_start:event.t_start,t_end:event.t_end,...result,points:round(result.points)});
  }
  if(!economy)excluded.FUNDS_EVIDENCE_MISSING=1;
  const breakdown=Object.entries(families).map(([family,row])=>({family,...row,capped:round(Math.min(row.cap,row.raw)),raw:round(row.raw)})).sort((a,b)=>b.capped-a.capped);
  const deductionTotal=round(breakdown.reduce((sum,row)=>sum+row.capped,0));
  return {schema:2,version:VERSION,baseline:config.baseline,total:round(Math.max(0,config.baseline-deductionTotal)),deduction:deductionTotal,
    config,breakdown,excluded,financialEvents:financial,
    interpretation:'逐事件累计、分类封顶；缺少 HP、过期时间或连续可行性证据的事件不扣分。与 Stage1 分开呈现。'};
}
module.exports={VERSION,DEFAULTS,FAMILY,configuration,scoreable,impactWeight,weightedImpact,idleSeverity,deduction,fundsEvents,scoreEvents};
