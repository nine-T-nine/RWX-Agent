#!/usr/bin/env node
'use strict';
/** Score the three Stage-3 families. S0 values are explicit calibration placeholders. */
const fs=require('node:fs');
const path=require('node:path');

const VERSION='stage3-score-v1';
const DEFAULTS=Object.freeze({
  alpha:.4,beta:.9,
  ambush:{WMin:.1,NSat:8,gamma:.5,T0:3,TSpan:27,Cw:1000,wLo:.4,wHi:Math.sqrt(7),
    GRef:1200,rLo:.5,rHi:2,PMax:60,S0:1},
  barrage:{WMin:.1,NSat:4,Cw:1000,wLo:.5,wHi:Math.sqrt(8),GRef:1200,rLo:.5,rHi:2,PMax:60,S0:1},
  grinder:{delta0:5,TMerge:5,TStale:30,LMin:.4,WFull:10,SingleMax:8,Cap:40},
});
const round=(n,d=3)=>Number(n.toFixed(d));
const clip=(n,lo,hi)=>Math.max(lo,Math.min(hi,n));
const merge=(base,over={})=>Object.fromEntries(Object.keys(base).map(key=>[key,
  base[key]&&typeof base[key]==='object'&&!Array.isArray(base[key])?merge(base[key],over[key]||{}):over[key]??base[key]]));

function loss(event,p,alpha,beta,withTime){
  const E=event.D_hp+alpha*event.D_sh,H=event.HP_max+alpha*event.Shield_max;
  const L=event.died||event.destroyed?1:beta*Math.min(1,H>0?E/H:0);
  const W=p.WMin+(1-p.WMin)*Math.min(1,Math.log(Math.max(1,event.N))/Math.log(p.NSat));
  const value=clip(Math.sqrt(Math.max(0,event.cost)/p.Cw),p.wLo,p.wHi);
  const Tf=withTime?1+p.gamma*Math.min(1,Math.max(0,(event.T_ms/1000-p.T0)/p.TSpan)):1;
  return {E,H,L,W,w:value,Tf,s:value*L*W*Tf};
}
function saturated(S,p){return p.PMax*(1-Math.exp(-S/p.S0));}

function scoreEvents(events,summary,overrides={}){
  const p=merge(DEFAULTS,overrides),G=Math.max(0,Number(summary?.durationMs||0))/1000;
  const family=(type,key,withTime)=>{
    const parameter=p[key],items=[],excluded=[];
    for(const event of events.filter(e=>e.type===type)){
      if(event.scoreable===false){excluded.push({id:event.id,reason:event.reason});continue;}
      const parts=loss(event,parameter,p.alpha,p.beta,withTime);items.push({id:event.id,...parts,N:event.N,
        unit:event.unit,died:event.died,destroyed:event.destroyed});
    }
    const raw=items.reduce((n,item)=>n+item.s,0),scale=clip(G/parameter.GRef,parameter.rLo,parameter.rHi),S=raw/scale;
    const deduction=saturated(S,parameter);
    return {family:type,raw:round(raw),durationScale:round(scale),S:round(S),cap:parameter.PMax,
      deduction:round(deduction),items:items.map(item=>({...item,s:round(item.s),L:round(item.L),W:round(item.W),
        w:round(item.w),Tf:round(item.Tf),E:round(item.E),H:round(item.H)})),excluded,
      diagnostics:{records:items.length,lethalShare:items.length?round(items.filter(i=>i.died||i.destroyed).length/items.length):0,
        N:items.map(i=>i.N),types:Object.fromEntries([...new Set(items.map(i=>i.unit?.type??'unknown'))]
          .map(type=>[type,items.filter(i=>(i.unit?.type??'unknown')===type).length]))}};
  };
  const ambush=family('STAGE3_AMBUSH','ambush',true),barrage=family('STAGE3_BARRAGE','barrage',false);
  const eligible=events.filter(e=>e.type==='STAGE3_GRINDER'&&e.scoreable!==false)
    .sort((a,b)=>a.zoneId-b.zoneId||a.t_start-b.t_start||a.id.localeCompare(b.id));
  const groups=[];
  for(const death of eligible){let group=groups.at(-1);
    if(!group||group.zoneId!==death.zoneId||death.t_start-group.anchor>p.grinder.TMerge*1000){
      group={zoneId:death.zoneId,anchor:death.t_start,deaths:[]};groups.push(group);}
    group.deaths.push(death);
  }
  const grinderItems=groups.map((group,index)=>{const weight=group.deaths.reduce((n,e)=>n+e.weight,0);
    const age=Math.max(0,(group.anchor-Number(group.deaths[0].zoneBirthMs||0))/1000-p.grinder.delta0);
    const B=Math.min(1,weight/p.grinder.WFull),L=p.grinder.LMin+(1-p.grinder.LMin)*Math.min(1,age/p.grinder.TStale);
    return {id:`GE-${index+1}`,zoneId:group.zoneId,t_first:group.anchor,deathIds:group.deaths.map(e=>e.id),
      weight,age:round(age),B:round(B),L:round(L),points:round(p.grinder.SingleMax*L*B)};});
  const grinderRaw=grinderItems.reduce((n,item)=>n+item.points,0);
  const grinder={family:'STAGE3_GRINDER',raw:round(grinderRaw),cap:p.grinder.Cap,
    deduction:round(Math.min(p.grinder.Cap,grinderRaw)),items:grinderItems,
    excluded:events.filter(e=>e.type==='STAGE3_GRINDER'&&e.scoreable===false).map(e=>({id:e.id,reason:e.reason})),
    diagnostics:{builderDeaths:events.filter(e=>e.type==='STAGE3_GRINDER').length,scoreableDeaths:eligible.length,events:groups.length,
      types:Object.fromEntries([...new Set(eligible.map(e=>e.unit?.type??'unknown'))]
        .map(type=>[type,eligible.filter(e=>(e.unit?.type??'unknown')===type).length]))}};
  const breakdown=[ambush,barrage,grinder],deduction=breakdown.reduce((n,f)=>n+f.deduction,0);
  return {schema:1,version:VERSION,baseline:100,parameters:p,calibration:{provisional:true,
    reason:'ambush.S0 and barrage.S0 are placeholders until baseline medians are available'},
    durationSeconds:round(G),deduction:round(deduction),total:round(Math.max(0,100-deduction)),breakdown};
}

function readJson(file){return JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));}
function readEvents(file){return fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));}
function report(score){const out=[`第三阶段评分（${score.version}）`,'='.repeat(52),
  `基准分 ${score.baseline}  扣分 ${score.deduction}  总分 ${score.total}`];
  const calibration=score.calibration||{},baseline=calibration.baseline||{};
  if(calibration.s0Source){
    const source=baseline.source;
    const label=calibration.s0Source==='archive-median'||source==='archive-corpus'?'归档语料中位数':(source||calibration.s0Source);
    out.push(`标定：S0 ${calibration.s0Source==='fixed-baseline'?'沿用':'取自'}${label}`
      +`（偷袭 ${baseline.ambushS0??'—'} / 炮击 ${baseline.barrageS0??'—'}，${baseline.matches??'?'} 局）`
      +(calibration.excluded?'；本局未计入基线':'')
      +(calibration.provisional?'；仍为 provisional':''));
  } else out.push('标定状态：S0 暂为占位值，基线中位数产生前不得与正式榜单混用。');
  out.push('');
  for(const family of score.breakdown)out.push(`${family.family.padEnd(18)} 扣 ${String(family.deduction).padStart(7)} / ${family.cap}`);
  return out.join('\n')+'\n';}
function timeline(events,score){const points=new Map(score.breakdown.flatMap(f=>f.items.map(i=>[i.id,i.points??i.s])));
  return ['t_start,t_end,type,id,scoreable,value,reason',...events.map(e=>[e.t_start,e.t_end,e.type,e.id,e.scoreable,
    points.get(e.id)??'',e.reason??''].join(','))].join('\n')+'\n';}

function main(argv){const args=argv.slice(2),target=args.shift();if(!target)throw Error('Usage: node evaluate/score_stage_3.js <stage_3 dir> [--weights file] [--out dir]');
  let weights={},out=null;while(args.length){const flag=args.shift();if(flag==='--weights')weights=readJson(args.shift());
    else if(flag==='--out')out=args.shift();else throw Error(`Unknown option ${flag}`);}
  const dir=path.resolve(target),outDir=path.resolve(out||dir),events=readEvents(path.join(dir,'events.jsonl')),
    summary=readJson(path.join(dir,'summary.json')),score=scoreEvents(events,summary,weights);
  score.source={events:path.join(dir,'events.jsonl'),summary:path.join(dir,'summary.json')};
  fs.mkdirSync(outDir,{recursive:true});fs.writeFileSync(path.join(outDir,'score.json'),JSON.stringify(score,null,2)+'\n');
  fs.writeFileSync(path.join(outDir,'score.txt'),report(score));fs.writeFileSync(path.join(outDir,'timeline.csv'),timeline(events,score));
  console.log(report(score));return {outDir,score};}
if(require.main===module)try{main(process.argv);}catch(error){console.error(error.message);process.exitCode=1;}
module.exports={VERSION,DEFAULTS,scoreEvents,loss,main,report,timeline};
