#!/usr/bin/env node
/**
 * RWX 双源实时性分析：端口命令 (A/B) + 游戏 Replay JSONL (B 的底层执行)。
 * node evaluate_stage_1.js <commands.txt> <replay.jsonl>
 * 与 execution-mapping.js、utils/paths.js 放在同一目录；无第三方依赖。原 evaluate.js 与 evaluate_1.js 已合并进本文件。
 *
 * 保留原 evaluate_1.js 的 A/B 分析；补充 Replay 相邻底层间隔和高低层映射，
 * 并写入 native_command_intervals.csv、execution_* 与 mapping_rules.json。
 */
'use strict';
const {stageDirectory}=require('./utils/paths');
const {evaluateBase,readLog}=(()=>{
const fs = require('node:fs');
const path = require('node:path');

const A = new Set(`rwx-game-state rwx-game-team-list rwx-game-unit-list rwx-game-unit-show rwx-game-unit-actions rwx-game-map-show rwx-game-map-path rwx-game-map-place rwx-game-threat-list rwx-game-group-list rwx-game-group-show rwx-game-keep-list rwx-game-pending-list rwx-game-auto-list rwx-game-auto-show rwx-game-nuke-status rwx-game-events rwx-query-unit rwx-query-building rwx-query-production`.split(' '));
const B = new Set(`rwx-game-group-save rwx-game-group-delete rwx-game-keep-set rwx-game-keep-clear rwx-game-pending-clear rwx-game-auto-set rwx-game-auto-clear rwx-game-auto-reset`.split(' '));
const C = new Set(`rwx-help rwx-ping rwx-skirmish rwx-battleroom-show rwx-battleroom-add-ai rwx-battleroom-show-ai rwx-battleroom-set-player rwx-battleroom-select-map rwx-battleroom-settings rwx-battleroom-chat rwx-battleroom-start rwx-battleroom-quit rwx-connect rwx-connection-status rwx-disconnect rwx-host rwx-server-list rwx-exit rwx-game-pause rwx-game-speed rwx-game-step rwx-game-restart rwx-game-surrender rwx-game-camera rwx-game-replay-start rwx-game-replay-stop rwx-game-replay-status rwx-game-observe-reset`.split(' '));
const TYPES = ['A-A', 'A-B', 'B-A', 'B-B'];
const COLORS = {'A-A':'#437bb6','A-B':'#e28b37','B-A':'#3c9973','B-B':'#996cbd'};
const LOG = /^\[(\d+):([0-5]\d)(?::(\d{3}))?\]<([^<>]*)><([^<>]*)>\s*$/;
const stamp = s => {
  const ms=Math.round(s*1000),base=`${String(Math.floor(ms/60000)).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}`;
  return ms%1000?`${base}:${String(ms%1000).padStart(3,'0')}`:base;
};
const delta = (end,start) => Math.round((end-start)*1000)/1000;
const fmt = x => x == null ? 'N/A' : x.toFixed(2);
const esc = x => String(x).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const average = xs => xs.length ? xs.reduce((a,b)=>a+b,0)/xs.length : null;
const quantile = (xs,p) => { if (!xs.length) return null; const v=[...xs].sort((a,b)=>a-b), pos=(v.length-1)*p, i=Math.floor(pos); return v[i]+(v[Math.min(i+1,v.length-1)]-v[i])*(pos-i); };
const kindOf = (token, overrides) => overrides[token] || (A.has(token) ? 'A' : (B.has(token)||token.startsWith('rwx-game-order-') ? 'B' : 'C'));

function readLog(file, overrides={}) {
  const commands=[], unknown={}, lines=fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/);
  let started=false, stop=null;
  for (let i=0;i<lines.length;i++) {
    const raw=lines[i].trim();
    if (i === lines.length-1 && !raw) break; // ignore final newline
    const match=raw.match(LOG);
    if (!started) { if (!match) continue; started=true; }
    if (!match) { stop={line:i+1,content:raw,reason:'invalid_format'}; break; }
    const [,minutes,seconds,milliseconds,meta,rawCommand]=match;
    const command=rawCommand.replace(/\\t/g,' ').trim(), token=command.split(/\s+/)[0]?.toLowerCase()||'';
    const fields=new Set(meta.split(';').map(s=>s.split('=')[0]));
    if (!['name','player','faction','role','match'].every(k=>fields.has(k)) || !token) {
      stop={line:i+1,content:raw,reason:'invalid_command_structure'}; break;
    }
    const type=kindOf(token,overrides);
    if (type==='C' && !C.has(token) && !token.startsWith('rwx-battleroom-') && !(token in overrides)) unknown[token]=(unknown[token]||0)+1;
    commands.push({line:i+1,time:Number(minutes)*60+Number(seconds)+Number(milliseconds||0)/1000,type,command,token,index:commands.length});
  }
  return {commands,stop,unknown};
}

function splitSegments(commands) {
  const segments=[], resets=[];
  for (const cmd of commands.filter(c=>c.type!=='C')) {
    if (!segments.length) segments.push([]);
    let segment=segments.at(-1), prev=segment.at(-1);
    if (prev && cmd.time<prev.time) { resets.push({line:cmd.line,from:stamp(prev.time),to:stamp(cmd.time)}); segments.push([]); segment=segments.at(-1); }
    segment.push(cmd);
  }
  return {segments,resets};
}

function analyze(segments) {
  const cycles=[],gaps=[],incomplete=[];
  segments.forEach((seq,si)=>{
    for (let i=1;i<seq.length;i++) {
      const l=seq[i-1],r=seq[i];
      gaps.push({segment:si+1,type:`${l.type}-${r.type}`,time:l.time,start:stamp(l.time),end:stamp(r.time),seconds:delta(r.time,l.time),firstLine:l.line,lastLine:r.line,firstCommand:l.command,lastCommand:r.command,filteredC:r.index-l.index-1});
    }
    const blocks=[];
    for (const cmd of seq) {
      if (blocks.at(-1)?.type!==cmd.type) blocks.push({type:cmd.type,items:[]});
      blocks.at(-1).items.push(cmd);
    }
    blocks.forEach((block,i)=>{
      if (i===0 && block.type==='B') incomplete.push({segment:si+1,type:'leading_B',start:stamp(block.items[0].time)});
      if (block.type!=='A') return;
      const first=block.items[0], next=blocks[i+1];
      if (!next) { incomplete.push({segment:si+1,type:'trailing_A',start:stamp(first.time)}); return; }
      const firstB=next.items[0],lastB=next.items.at(-1);
      cycles.push({segment:si+1,time:first.time,start:stamp(first.time),end:stamp(lastB.time),firstAction:stamp(firstB.time),seconds:delta(lastB.time,first.time),observeToAction:delta(firstB.time,first.time),A_count:block.items.length,B_count:next.items.length,firstLine:first.line,lastLine:lastB.line});
    });
  });
  return {cycles,gaps,incomplete};
}

function markOutliers(rows,key,minExcess=5) {
  const vals=rows.map(r=>r[key]);
  let limit=null;
  if (vals.length>=4) {
    const median=quantile(vals,.5),q1=quantile(vals,.25),q3=quantile(vals,.75);
    const mad=quantile(vals.map(x=>Math.abs(x-median)),.5);
    limit=Math.max(q3+1.5*(q3-q1),median+3*1.4826*mad,median+minExcess);
  }
  rows.forEach(r=>r.outlier=limit!==null && r[key]>limit);
  return limit;
}
function stats(rows,key) {
  const xs=rows.map(r=>r[key]),clean=rows.filter(r=>!r.outlier).map(r=>r[key]);
  return {n:xs.length,mean:average(xs),cleanMean:average(clean),median:quantile(xs,.5),p90:quantile(xs,.9),p95:quantile(xs,.95),max:xs.length?Math.max(...xs):null,outliers:xs.length-clean.length};
}
function csv(file,rows,keys) {
  const quote=v=>'"'+String(v??'').replace(/"/g,'""')+'"';
  fs.writeFileSync(file,'\uFEFF'+[keys.join(','),...rows.map(r=>keys.map(k=>quote(r[k])).join(','))].join('\n'),'utf8');
}

// SVG stays inline in HTML: mouseover reveals exact time, value, category and commands.
function plotLine(title, rows, mean=null, grouped=false) {
  const W=1100,H=365,L=65,R=25,T=42,D=53,pw=W-L-R,ph=H-T-D;
  const last=Math.max(1,...rows.map(r=>r.time)),highest=Math.max(1,...rows.map(r=>r.seconds),mean||0)*1.12;
  const X=t=>L+pw*t/last, Y=v=>T+ph*(1-v/highest);
  let svg=`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(title)}"><text x="${L}" y="25" font-size="19" font-weight="bold">${esc(title)}</text>`;
  for(let i=0;i<=5;i++){let v=highest*i/5,y=Y(v);svg+=`<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="#dfe5ed"/><text x="${L-8}" y="${y+4}" text-anchor="end" font-size="12">${v.toFixed(1)}</text>`;}
  for(let i=0;i<=6;i++){const t=last*i/6;svg+=`<text x="${X(t)}" y="${H-25}" text-anchor="middle" font-size="12">${stamp(Math.round(t))}</text>`;}
  if(rows.length){svg+=`<polyline fill="none" stroke="#98a3b2" stroke-width="1.5" points="${rows.map(r=>`${X(r.time)},${Y(r.seconds)}`).join(' ')}"/>`;}
  if(mean!==null){svg+=`<line x1="${L}" x2="${W-R}" y1="${Y(mean)}" y2="${Y(mean)}" stroke="#16866c" stroke-width="2" stroke-dasharray="6 5"/><text x="${W-R-5}" y="${Y(mean)-7}" text-anchor="end" fill="#16866c" font-size="12">去异常均值 ${fmt(mean)} 秒</text>`;}
  for(const r of rows){
    const color=r.outlier?'#d93042':(grouped?COLORS[r.type]:'#3278b8');
    const tip=grouped?`${r.type} | ${r.start} → ${r.end} | ${r.seconds} 秒${r.outlier?' | 离群值':''}\n${r.firstCommand}\n${r.lastCommand}`:`A→B | ${r.start} → ${r.end} | ${r.seconds} 秒${r.outlier?' | 离群值':''}\nA: ${r.A_count} 条，B: ${r.B_count} 条；观察到首次行动 ${r.observeToAction} 秒`;
    svg+=`<circle class="data-point" cx="${X(r.time)}" cy="${Y(r.seconds)}" r="${r.outlier?6:4}" fill="${color}" data-tip="${esc(tip)}"><title>${esc(tip)}</title></circle>`;
  }
  if(grouped)TYPES.forEach((type,i)=>{svg+=`<circle cx="${L+i*116}" cy="${H-7}" r="5" fill="${COLORS[type]}"/><text x="${L+11+i*116}" y="${H-3}" font-size="12">${type}</text>`;});
  return svg+'</svg>';
}
// Two alternating timelines: A→B decision work and B→A time before the next observation.
function plotCycleAndWait(cycles, waits, cycleMean, waitMean) {
  const W=1100,H=405,L=65,R=25,T=48,D=73,pw=W-L-R,ph=H-T-D;
  const all=[...cycles,...waits];
  const maxTime=Math.max(1,...all.map(r=>r.time));
  const maxValue=Math.max(1,...all.map(r=>r.seconds),cycleMean||0,waitMean||0)*1.12;
  const X=t=>L+pw*t/maxTime, Y=v=>T+ph*(1-v/maxValue);
  let svg=`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="A→B 决策周期与 B→A 等待间隔"><text x="${L}" y="25" font-size="19" font-weight="bold">A→B 决策周期 / B→A 等待间隔</text>`;
  for(let i=0;i<=5;i++){const v=maxValue*i/5,y=Y(v);svg+=`<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="#dfe5ed"/><text x="${L-8}" y="${y+4}" text-anchor="end" font-size="12">${v.toFixed(1)}</text>`;}
  for(let i=0;i<=6;i++){const t=maxTime*i/6;svg+=`<text x="${X(t)}" y="${H-40}" text-anchor="middle" font-size="12">${stamp(Math.round(t))}</text>`;}
  const series=[{rows:cycles,name:'A→B 决策',color:'#3278b8',mean:cycleMean},{rows:waits,name:'B→A 等待',color:'#e28b37',mean:waitMean}];
  for(const item of series){
    if(item.rows.length)svg+=`<polyline fill="none" stroke="${item.color}" stroke-width="2" points="${item.rows.map(r=>`${X(r.time)},${Y(r.seconds)}`).join(' ')}"/>`;
    if(item.mean!==null){svg+=`<line x1="${L}" x2="${W-R}" y1="${Y(item.mean)}" y2="${Y(item.mean)}" stroke="${item.color}" stroke-width="1.5" stroke-dasharray="6 5" opacity=".7"/>`;}
    for(const r of item.rows){
      const tip=item.name==='A→B 决策'
        ?`A→B 决策 | ${r.start} → ${r.end} | ${r.seconds} 秒${r.outlier?' | 离群值':''}\nA: ${r.A_count} 条，B: ${r.B_count} 条；观察到首次行动 ${r.observeToAction} 秒`
        :`B→A 等待 | ${r.start} → ${r.end} | ${r.seconds} 秒${r.outlier?' | 离群值':''}\n上一条：${r.firstCommand}\n下一条：${r.lastCommand}`;
      svg+=`<circle class="data-point" cx="${X(r.time)}" cy="${Y(r.seconds)}" r="${r.outlier?6:4.5}" fill="${r.outlier?'#d93042':item.color}" stroke="white" stroke-width=".8" data-tip="${esc(tip)}"><title>${esc(tip)}</title></circle>`;
    }
  }
  series.forEach((item,i)=>{const x=L+i*285;svg+=`<line x1="${x}" x2="${x+25}" y1="${H-14}" y2="${H-14}" stroke="${item.color}" stroke-width="3"/><text x="${x+32}" y="${H-10}" font-size="12">${item.name}（去异常均值 ${fmt(item.mean)} 秒）</text>`;});
  return svg+'</svg>';
}
// 10 belongs to >=10, so the preceding interval is (8,10).
const BUCKETS=[
  {label:'0',match:x=>x===0},
  {label:'(0,1]',match:x=>x>0&&x<=1},
  {label:'(1,2]',match:x=>x>1&&x<=2},
  {label:'(2,4]',match:x=>x>2&&x<=4},
  {label:'(4,6]',match:x=>x>4&&x<=6},
  {label:'(6,8]',match:x=>x>6&&x<=8},
  {label:'(8,10)',match:x=>x>8&&x<10},
  {label:'≥10',match:x=>x>=10},
];
function plotHistogram(gaps){
  const W=1100,H=340,L=60,R=25,T=45,D=62,pw=W-L-R,ph=H-T-D;
  const bins=BUCKETS.map(b=>({label:b.label,count:gaps.filter(r=>b.match(r.seconds)).length}));
  const max=Math.max(1,...bins.map(x=>x.count)),slot=pw/bins.length;
  let svg=`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="所有指令间隔频率分布"><text x="${L}" y="25" font-size="19" font-weight="bold">总体指令间隔频率分布（含离群值）</text>`;
  for(let i=0;i<=4;i++){const y=T+ph*(1-i/4);svg+=`<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="#dfe5ed"/><text x="${L-8}" y="${y+4}" font-size="12" text-anchor="end">${Math.round(max*i/4)}</text>`;}
  bins.forEach((b,i)=>{const x=L+i*slot+slot*.13,w=slot*.74,h=ph*b.count/max,y=T+ph-h,tip=`${b.label} 秒：${b.count} 条，占 ${(100*b.count/gaps.length).toFixed(1)}%（包含离群值）`;
    svg+=`<rect class="data-point" x="${x}" y="${y}" width="${w}" height="${Math.max(h,0.2)}" fill="#417fbb" data-tip="${esc(tip)}"><title>${esc(tip)}</title></rect><text x="${x+w/2}" y="${H-29}" font-size="13" text-anchor="middle">${esc(b.label)}</text>`;
  });
  return svg+'</svg>';
}
function renderReport(source,stop,unknown,resets,counts,delay,reaction,cycles,gaps,incomplete,cycleLimit,limits,cycleStats,gapStats,waits,waitStats) {
  const lines=['RWX 命令实时性评估','='.repeat(42),`文件：${path.basename(source)}`,`扫描终点：${stop?`第 ${stop.line} 行（格式不符，已截断）`:'文件结束'}`,`命令：A=${counts.A||0}，B=${counts.B||0}，C=${counts.C||0}（分析时排除）`,`开局延迟：${delay} 秒；开局反应时间：${fmt(reaction)} 秒`,'','【A→B 决策周期】',`完整周期：${cycleStats.n}；未配对段：${incomplete.length}`,`平均：${fmt(cycleStats.mean)} 秒；去异常均值：${fmt(cycleStats.cleanMean)} 秒`,`中位数：${fmt(cycleStats.median)} 秒；P90：${fmt(cycleStats.p90)} 秒；P95：${fmt(cycleStats.p95)} 秒`,`异常阈值：${fmt(cycleLimit)} 秒；异常数：${cycleStats.outliers}`];
  for(const x of cycles.filter(x=>x.outlier))lines.push(`  [异常] ${x.start} → ${x.end}，${x.seconds} 秒（行 ${x.firstLine}–${x.lastLine}）`);
  lines.push('','【B→A 周期间等待】',`等待段：${waitStats.n}；平均：${fmt(waitStats.mean)} 秒；去异常均值：${fmt(waitStats.cleanMean)} 秒`,`中位数：${fmt(waitStats.median)} 秒；P90：${fmt(waitStats.p90)} 秒；P95：${fmt(waitStats.p95)} 秒`,`异常阈值：${fmt(limits['B-A'])} 秒；异常数：${waitStats.outliers}`);
  for(const x of waits.filter(x=>x.outlier))lines.push(`  [等待异常] ${x.start} → ${x.end}，${x.seconds} 秒（行 ${x.firstLine}–${x.lastLine}）`);
  lines.push('','【相邻命令间隔】');
  for(const type of TYPES){const st=gapStats[type];lines.push(`${type}：n=${st.n}，均值=${fmt(st.mean)}s，P95=${fmt(st.p95)}s，阈值=${fmt(limits[type])}s，异常=${st.outliers}`);for(const x of gaps.filter(x=>x.type===type&&x.outlier))lines.push(`  [异常] ${x.start} → ${x.end}，${x.seconds} 秒（行 ${x.firstLine}–${x.lastLine}）`,`      ${x.firstCommand}`,`      ${x.lastCommand}`);}
  if(stop)lines.push('',`截断行原文：${stop.content}`);
  if(Object.keys(unknown).length)lines.push(`未知命令（按 C 排除）：${JSON.stringify(unknown)}`);
  if(resets.length)lines.push(`检测到时间倒退 ${resets.length} 次，已分段避免负间隔。`);
  lines.push('','注：日志时间戳不一定是墙钟时间；统计离群值不能直接判定模型卡顿。');
  return lines.join('\n')+'\n';
}

async function evaluateBase(fileName, {outputRoot=path.join(__dirname,'output'),minOutlierSeconds=5,overridesPath=null}={}) {
  if(!fs.existsSync(fileName))throw new Error(`找不到日志：${fileName}`);
  if(minOutlierSeconds<0)throw new Error('minOutlierSeconds 不能为负数');
  const overrides=overridesPath?JSON.parse(fs.readFileSync(overridesPath,'utf8')):{};
  if(Object.values(overrides).some(x=>!['A','B','C'].includes(x)))throw new Error('overrides 必须是命令到 A/B/C 的映射');
  const {commands,stop,unknown}=readLog(fileName,Object.fromEntries(Object.entries(overrides).map(([k,v])=>[k.toLowerCase(),v])));
  const ab=commands.filter(c=>c.type!=='C'),duration=commands.length?commands.at(-1).time:0;
  const counts={A:commands.filter(c=>c.type==='A').length,B:commands.filter(c=>c.type==='B').length,C:commands.filter(c=>c.type==='C').length};
  // Reject before producing any output. Exactly 5:00 / exactly 50 commands are insufficient.
  if(duration<=300 || ab.length<=50){const reasons=[];if(duration<=300)reasons.push(`对局时长 ${stamp(duration)}，必须大于 05:00`);if(ab.length<=50)reasons.push(`A+B 指令数 ${ab.length}，必须大于 50`);console.warn(`拒绝评估：${reasons.join('；')}`);return null;}
  const {segments,resets}=splitSegments(commands),{cycles,gaps,incomplete}=analyze(segments);
  const cycleLimit=markOutliers(cycles,'seconds',minOutlierSeconds),limits={};
  for(const type of TYPES)limits[type]=markOutliers(gaps.filter(x=>x.type===type),'seconds',minOutlierSeconds);
  const cycleStats=stats(cycles,'seconds'),gapStats=Object.fromEntries(TYPES.map(k=>[k,stats(gaps.filter(x=>x.type===k),'seconds')]));
  const waits=gaps.filter(x=>x.type==='B-A'),waitStats=gapStats['B-A'];
  const firstA=ab.findIndex(c=>c.type==='A'),nextB=ab.slice(firstA+1).find(c=>c.type==='B');
  const reaction=firstA<0||!nextB?null:delta(nextB.time,ab[firstA].time);
  const out=stageDirectory(fileName,1,outputRoot);fs.mkdirSync(out,{recursive:true});
  const report=renderReport(fileName,stop,unknown,resets,counts,ab[0].time,reaction,cycles,gaps,incomplete,cycleLimit,limits,cycleStats,gapStats,waits,waitStats);
  const summary={file:path.resolve(fileName),durationSeconds:duration,scanStoppedAt:stop,commandCounts:counts,unknownCommands:unknown,timeResets:resets,startupDelaySeconds:ab[0].time,startupReactionSeconds:reaction,cycleDefinition:'首条 A → 后续相邻 B 段最后一条 B',cycleOutlierLimit:cycleLimit,cycleStats,waitDefinition:'末条 B → 下一条 A（同一个有效时间序列中）',waitOutlierLimit:limits['B-A'],waitStats,incompleteBlocks:incomplete,gapOutlierLimits:limits,gapStats,minOutlierSeconds};
  fs.writeFileSync(path.join(out,'report.txt'),report,'utf8');fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2),'utf8');
  csv(path.join(out,'classified_commands.csv'),commands.map(c=>({line:c.line,timestamp:stamp(c.time),seconds:c.time,class:c.type,command:c.command})),['line','timestamp','seconds','class','command']);
  csv(path.join(out,'decision_cycles.csv'),cycles,['segment','start','end','firstAction','seconds','observeToAction','A_count','B_count','firstLine','lastLine','outlier']);
  csv(path.join(out,'waiting_intervals.csv'),waits,['segment','start','end','seconds','firstLine','lastLine','firstCommand','lastCommand','outlier']);
  csv(path.join(out,'command_intervals.csv'),gaps,['segment','type','start','end','seconds','firstLine','lastLine','firstCommand','lastCommand','filteredC','outlier']);
  const cycleChart=plotCycleAndWait(cycles,waits,cycleStats.cleanMean,waitStats.cleanMean);
  const gapChart=plotLine('四类指令间隔',gaps,null,true);
  const overallHistogram=plotHistogram(gaps);
  const cards=[['Duration',stamp(duration)],['A / B commands',`${counts.A} / ${counts.B}`],['Completed cycles',String(cycleStats.n)],['Clean mean cycle',`${fmt(cycleStats.cleanMean)} s`],['Clean mean wait',`${fmt(waitStats.cleanMean)} s`]];
  const html=`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RWX 实时性评估 — ${esc(path.basename(fileName))}</title><style>
body{margin:0;background:#f4f6fa;color:#172438;font:15px/1.65 system-ui,-apple-system,'Segoe UI',sans-serif}main{max-width:1200px;margin:auto;padding:30px 20px 70px}h1{margin-bottom:3px}header p{color:#66758a;margin-top:0}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:13px;margin:26px 0}.card,section{background:#fff;border:1px solid #e1e7ef;border-radius:12px;padding:18px;box-shadow:0 3px 12px #182c4806}.card small{color:#63738a;display:block}.card strong{display:block;font-size:27px;margin-top:5px}section{margin:20px 0}section h2{font-size:20px;margin:0 0 14px}pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.7 ui-monospace,Consolas,monospace;color:#25364b}.chart{display:block;width:100%;height:auto;overflow:visible}.chart text{font-family:system-ui,sans-serif;fill:#42546a}.data-point{cursor:crosshair}.data-point:hover{stroke:#172438;stroke-width:3;filter:brightness(1.1)}.tooltip{position:fixed;z-index:10;pointer-events:none;white-space:pre-wrap;max-width:min(460px,85vw);background:#142337;color:white;padding:10px 12px;border-radius:8px;font:12px/1.5 ui-monospace,Consolas,monospace;box-shadow:0 8px 32px #0003;display:none}.caption{color:#64748b;font-size:13px}
</style></head><body><main><header><h1>RWX 实时性评估</h1><p>${esc(path.basename(fileName))} · 离线交互 HTML · 悬浮图表查看明细</p></header><div class="cards">${cards.map(([k,v])=>`<div class="card"><small>${esc(k)}</small><strong>${esc(v)}</strong></div>`).join('')}</div><section><h2>文字分析报告</h2><pre>${esc(report)}</pre></section><section><h2>决策周期折线图</h2><p class="caption">蓝线为首条 A 到末条 B 的决策周期；橙线为末条 B 到下一条 A 的等待间隔。横坐标均为区间起点；分别计算去异常均值，红色点表示离群值。</p>${cycleChart}</section><section><h2>四类指令间隔折线图</h2>${gapChart}</section><section><h2>总体间隔频率分布（含离群值）</h2>${overallHistogram}</section><p class="caption">时间戳来自日志；离群值不等于模型故障。所有图表直接嵌入，无需额外库或联网。</p></main><div id="tooltip" class="tooltip"></div><script>
const tip=document.querySelector('#tooltip');document.querySelectorAll('[data-tip]').forEach(p=>{p.addEventListener('pointerenter',()=>{tip.textContent=p.dataset.tip;tip.style.display='block'});p.addEventListener('pointermove',e=>{tip.style.left=Math.min(e.clientX+15,innerWidth-tip.offsetWidth-10)+'px';tip.style.top=Math.min(e.clientY+15,innerHeight-tip.offsetHeight-10)+'px'});p.addEventListener('pointerleave',()=>tip.style.display='none')});
</script></body></html>`;
  fs.writeFileSync(path.join(out,'report.html'),html,'utf8');
  console.log(report);console.log(`报告：${path.join(out,'report.html')}`);
  return out;
}


return {evaluateBase,readLog};
})();
const fs = require('node:fs');
const path = require('node:path');


const {RULES, INDIRECT_RULES, isIndirect, matchAll, clock} = require('./execution-mapping.js');
const LINE = /^\[(\d+):([0-5]\d)(?::(\d{3}))?\]<([^<>]*)><([^<>]*)>\s*$/;
const stamp = clock;
const fmt = n => n==null ? 'N/A' : n.toFixed(3);
const esc = s => String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const avg = xs => xs.length ? xs.reduce((a,b)=>a+b,0)/xs.length : null;
const percentile = (xs,p) => { if(!xs.length)return null;const v=[...xs].sort((a,b)=>a-b),i=(v.length-1)*p,j=Math.floor(i);return v[j]+(v[Math.min(j+1,v.length-1)]-v[j])*(i-j);};
function stats(rows,key,minExcess){
  const values=rows.map(r=>r[key]);let limit=null;
  if(values.length>=4){
    const median=percentile(values,.5),q1=percentile(values,.25),q3=percentile(values,.75);
    const mad=percentile(values.map(v=>Math.abs(v-median)),.5);
    limit=Math.max(q3+1.5*(q3-q1),median+3*1.4826*mad,median+minExcess);
  }
  for(const row of rows)row[key+'Outlier']=limit!=null&&row[key]>limit;
  const clean=rows.filter(r=>!r[key+'Outlier']).map(r=>r[key]);
  return {count:values.length,mean:avg(values),cleanMean:avg(clean),median:percentile(values,.5),p90:percentile(values,.9),p95:percentile(values,.95),max:values.length?Math.max(...values):null,threshold:limit,outliers:values.length-clean.length};
}
function argsOf(command){
  const tokens=command.match(/(?:"[^"]*"|'[^']*'|\S+)/g)||[],args={};
  for(let i=1;i<tokens.length;i++){
    if(!tokens[i].startsWith('--'))continue;
    const key=tokens[i].slice(2),next=tokens[i+1];
    if(next!=null&&!next.startsWith('--')){args[key]=next.replace(/^["']|["']$/g,'');i++;}
    else args[key]=true;
  }
  return args;
}
function readHigh(file){
  const lines=fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/),commands=[];
  let started=false,stopped=null;
  for(let i=0;i<lines.length;i++){
    const raw=lines[i].trim();
    if(!raw&&i===lines.length-1)break;
    const m=raw.match(LINE);
    if(!started){if(!m)continue;started=true;}
    if(!m){stopped={line:i+1,content:raw};break;}
    const meta=Object.fromEntries(m[4].split(';').map(x=>x.split('=')));
    const command=m[5].replace(/\\t/g,' ').trim(),name=command.split(/\s+/)[0]?.toLowerCase();
    if(!name||!['player','faction','match','role','name'].every(k=>k in meta))throw Error(`端口文件第 ${i+1} 行格式错误`);
    commands.push({line:i+1,time:Number(m[1])*60+Number(m[2])+Number(m[3]||0)/1000,
      timePrecision:m[3]==null?'seconds':'milliseconds',timeUncertainty:m[3]==null?1:0,
      team:Number(meta.player),meta,name,command,args:argsOf(command)});
  }
  const inGame=commands.filter(x=>x.meta.match!=='none');
  const teams=new Set(inGame.map(x=>x.team));
  if(teams.size!==1||[...teams].some(x=>!Number.isInteger(x)))throw Error('端口文件在同一局内出现多个玩家 team，拒绝合并分析');
  if(new Set(inGame.map(x=>x.meta.match)).size!==1||inGame.some((h,i)=>i&&h.time<inGame[i-1].time))throw Error('端口文件包含多局或游戏时钟回退，请先按对局拆分');
  const high=inGame.filter(x=>x.name.startsWith('rwx-game-order-')&&!isIndirect(x));
  for(const h of high)if(!(h.name in RULES))throw Error(`未配置映射规则：端口第 ${h.line} 行 ${h.name}`);
  return {commands,high,team:[...teams][0],stopped,excluded:inGame.filter(isIndirect)};
}
function readLow(file,team){
  const all=[];
  const lines=fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/);
  for(let i=0;i<lines.length;i++){
    if(!lines[i].trim())continue;
    let row;
    try{row=JSON.parse(lines[i]);}catch(e){throw Error(`Replay 第 ${i+1} 行 JSON 无法解析：${e.message}`);}
    if(!Number.isFinite(row.time_s)||!Number.isInteger(row.team)||!Array.isArray(row.units))throw Error(`Replay 第 ${i+1} 行缺少 time_s/team/units`);
    if(row.team===team&&!row.noop)all.push({...row,line:i+1,id:i});
  }
  all.sort((a,b)=>a.time_s-b.time_s||a.id-b.id);
  if(!all.length)throw Error(`Replay 没有 team=${team} 的有效命令；核对端口日志 player 字段`);
  return all;
}
function nativeDescription(row){
  const parts=[];
  if(row.type)parts.push(row.type);
  if(row.build)parts.push('build='+row.build+(row.build_count!=null?' ×'+row.build_count:''));
  if(row.action)parts.push((row.stop_current_action?'cancel=':'action=')+row.action);
  if(row.type&&row.build==null&&row.target_unit===-1&&Number.isFinite(row.x)&&Number.isFinite(row.y))parts.push(`@(${row.x},${row.y})`);
  if(row.type==='build')parts.push(`@(${row.x},${row.y})`);
  if(row.target_unit!=null&&row.target_unit!==-1)parts.push('target=#'+row.target_unit);
  if(row.target_point)parts.push('point='+JSON.stringify(row.target_point));
  if(row.rally)parts.push('rally='+JSON.stringify(row.rally));
  if(row.attack_mode)parts.push('attackMode='+row.attack_mode);
  if(row.clear_existing_orders)parts.push('clear-orders');
  if(row.system_action)parts.push('system='+row.system_action);
  if(row.command_queued)parts.push('[queue]');
  return `${parts.join(' ')||'command'} units=${JSON.stringify(row.units)}`;
}
function nativeIntervalsOf(low){
  // readLow already filters team/noop and orders equal-time rows by original JSONL order.
  // Each row is one command, regardless of its unit count; same-tick rows remain distinct.
  const intervals=[];
  for(let i=1;i<low.length;i++){
    const first=low[i-1],last=low[i];
    if(last.time_s<first.time_s)throw Error('底层间隔输入必须按执行时间排序');
    intervals.push({index:i,team:first.team,firstTime:first.time_s,lastTime:last.time_s,
      start:stamp(first.time_s),end:stamp(last.time_s),
      seconds:Math.round((last.time_s-first.time_s)*1000)/1000,
      firstTick:first.tick,lastTick:last.tick,firstLine:first.line,lastLine:last.line,
      firstCommand:nativeDescription(first),lastCommand:nativeDescription(last)});
  }
  return intervals;
}
function nativeIntervalReport(native){
  const {intervals,summary}=native,s=summary.stats;
  return [
    '', '【我方相邻底层命令执行间隔（Replay）】',
    `Agent team=${summary.team}；整份 Replay 有效底层命令 ${summary.commandCount} 条；相邻间隔 ${intervals.length} 个；零间隔 ${summary.zeroIntervals} 个。`,
    '口径：先过滤到当前玩家 team 并排除 noop；包含该 team 的手动、自动微操和 UI 命令，不依赖高低层匹配结果。',
    '每条底层记录算一条命令，多单位不展开；同 tick 的真实命令不去重，保留 0 秒间隔。',
    '间隔 = 后一条 time_s − 前一条 time_s；折线图横坐标 = 前一条命令执行时间（区间左端点）。',
    `样本 ${s.count}；原始均值 ${fmt(s.mean)}s；去离群均值 ${fmt(s.cleanMean)}s；P50 ${fmt(s.median)}s；P90 ${fmt(s.p90)}s；P95 ${fmt(s.p95)}s；最大 ${fmt(s.max)}s；离群 ${s.outliers} 个。`,
    `离群阈值 ${fmt(s.threshold)}s；算法：max(Q3+1.5×IQR, 中位数+3×1.4826×MAD, 中位数+${summary.minOutlierSeconds}s)；不足4个样本不判离群。`,
    ...intervals.filter(row=>row.secondsOutlier).flatMap(row=>[
      `  [底层间隔离群] ${row.start} → ${row.end}：${fmt(row.seconds)}s（Replay JSONL 行 ${row.firstLine}–${row.lastLine}；tick ${row.firstTick}–${row.lastTick}）`,
      '      前：'+row.firstCommand,'      后：'+row.lastCommand,
    ]),
    '完整序列：native_command_intervals.csv；统计摘要：summary.json 的 nativeCommandIntervals。',
    '以上为游戏时间中的命令记录间隔，不代表墙钟耗时，也不代表生产/移动完成时间。',
  ].join('\n')+'\n';
}
function plotNativeIntervals(native){
  const {intervals,summary}=native,s=summary.stats;
  const W=1120,H=390,L=76,R=28,T=45,B=90,w=W-L-R,h=H-T-B;
  const maxX=Math.max(1,...intervals.map(row=>row.firstTime)),maxY=Math.max(1,...intervals.map(row=>row.seconds))*1.12;
  const X=t=>L+w*t/maxX,Y=value=>T+h*(1-value/maxY);
  let svg=`<svg id="native-interval-chart" class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="我方相邻底层命令执行间隔；横坐标为前一条命令执行时间"><text x="${L}" y="25" font-size="19" font-weight="bold">我方相邻底层命令执行间隔</text>`;
  for(let i=0;i<=5;i++){
    const value=maxY*i/5,y=Y(value);
    svg+=`<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="#dfe5ed"/><text x="${L-9}" y="${y+4}" text-anchor="end" font-size="12">${value.toFixed(2)}s</text>`;
  }
  for(let i=0;i<=6;i++){
    const time=maxX*i/6;
    svg+=`<text x="${X(time)}" y="${H-66}" text-anchor="middle" font-size="12">${stamp(time).split('.')[0]}</text>`;
  }
  svg+=`<text x="${L+w/2}" y="${H-42}" text-anchor="middle" font-size="12">前一条底层命令执行时间（游戏时间，区间左端点）</text>`;
  if(intervals.length)svg+=`<polyline points="${intervals.map(row=>`${X(row.firstTime)},${Y(row.seconds)}`).join(' ')}" fill="none" stroke="#3278b8" stroke-width="1.6"/>`;
  for(const [label,value,color] of [['原始均值',s.mean,'#7b8796'],['去离群均值',s.cleanMean,'#16866c']]){
    if(value!=null)svg+=`<line x1="${L}" x2="${W-R}" y1="${Y(value)}" y2="${Y(value)}" stroke="${color}" stroke-dasharray="6 5"/>`;
    const legendX=L+(label==='原始均值'?0:350);
    svg+=`<line x1="${legendX}" x2="${legendX+24}" y1="${H-16}" y2="${H-16}" stroke="${color}" stroke-width="2" stroke-dasharray="6 5"/><text x="${legendX+32}" y="${H-12}" font-size="12">${label} ${fmt(value)}s</text>`;
  }
  for(const row of intervals){
    const tip=`底层间隔 #${row.index}：${fmt(row.seconds)}s${row.secondsOutlier?' [离群]':''}\n横坐标（左端点）：${row.start}\n前：${row.start} / tick ${row.firstTick} / Replay 行 ${row.firstLine}\n${row.firstCommand}\n后：${row.end} / tick ${row.lastTick} / Replay 行 ${row.lastLine}\n${row.lastCommand}`;
    svg+=`<circle class="data-point" cx="${X(row.firstTime)}" cy="${Y(row.seconds)}" r="${row.secondsOutlier?6:3.5}" fill="${row.secondsOutlier?'#d93042':'#3278b8'}" stroke="white" stroke-width=".5" data-tip="${esc(tip)}"><title>${esc(tip)}</title></circle>`;
  }
  if(!intervals.length)svg+=`<text x="${L+w/2}" y="${T+h/2}" text-anchor="middle">有效底层命令不足两条，没有相邻间隔</text>`;
  return svg+'</svg>';
}
function writeCSV(file,rows,fields){
  const q=v=>'"'+String(v==null?'':typeof v==='object'?JSON.stringify(v):v).replace(/"/g,'""')+'"';
  fs.writeFileSync(file,'\uFEFF'+[fields.join(','),...rows.map(row=>fields.map(k=>q(row[k])).join(','))].join('\n')+'\n','utf8');
}
function plot(rows,firstStats,lastStats){
  const W=1120,H=430,L=72,R=28,T=50,B=72,w=W-L-R,h=H-T-B;
  const all=rows.flatMap(r=>[{x:r.highTime,y:r.delay},{x:r.firstTime,y:r.span}]);
  const maxX=Math.max(1,...all.map(p=>p.x)),maxY=Math.max(1,...all.map(p=>p.y))*1.12;
  const X=x=>L+w*x/maxX,Y=y=>T+h*(1-y/maxY);
  let svg=`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="生效延迟与执行跨度"><text x="${L}" y="25" font-size="19" font-weight="bold">高层命令 → 底层执行：双折线</text>`;
  for(let i=0;i<=5;i++){const v=maxY*i/5,y=Y(v);svg+=`<line x1="${L}" x2="${W-R}" y1="${y}" y2="${y}" stroke="#dfe5ed"/><text x="${L-9}" y="${y+4}" text-anchor="end" font-size="12">${v.toFixed(2)}</text>`;}
  for(let i=0;i<=6;i++){const v=maxX*i/6;svg+=`<text x="${X(v)}" y="${H-42}" text-anchor="middle" font-size="12">${stamp(v).slice(0,5)}</text>`;}
  const series=[{name:'首次延迟（旧日志为上界）',color:'#3278b8',value:'delay',x:'highTime',stats:firstStats},
    {name:'底层执行跨度',color:'#df8c31',value:'span',x:'firstTime',stats:lastStats}];
  for(const s of series){
    const pts=[...rows].sort((a,b)=>a[s.x]-b[s.x]||a.highLine-b.highLine).map(r=>`${X(r[s.x])},${Y(r[s.value])}`).join(' ');
    svg+=`<polyline points="${pts}" fill="none" stroke="${s.color}" stroke-width="2"/>`;
    if(s.stats.cleanMean!=null)svg+=`<line x1="${L}" x2="${W-R}" y1="${Y(s.stats.cleanMean)}" y2="${Y(s.stats.cleanMean)}" stroke="${s.color}" stroke-dasharray="6 5" opacity=".65"/>`;
    for(const r of rows){
      const tip=`${s.name} ${fmt(r[s.value])}s${r[s.value+'Outlier']?' [离群]':''}\n高层 [${r.highClock}] 行 ${r.highLine}\nX: ${r.firstClock} / tick ${r.firstTick}\nY: ${r.lastClock} / tick ${r.lastTick}\n${r.status} | 底层 ${r.matchedCount} 条\n${r.command}`;
      svg+=`<circle class="data-point" cx="${X(r[s.x])}" cy="${Y(r[s.value])}" r="${r[s.value+'Outlier']?6:4.5}" fill="${r[s.value+'Outlier']?'#d93042':s.color}" stroke="white" stroke-width=".6" data-tip="${esc(tip)}"><title>${esc(tip)}</title></circle>`;
    }
  }
  series.forEach((s,i)=>{const x=L+i*375;svg+=`<line x1="${x}" x2="${x+24}" y1="${H-14}" y2="${H-14}" stroke="${s.color}" stroke-width="3"/><text x="${x+32}" y="${H-10}" font-size="12">${s.name}（去异常均值 ${fmt(s.stats.cleanMean)}s）</text>`;});
  return svg+'</svg>';
}
function reportText(port,low,rows,delay,span){
  const matched=rows.filter(x=>x.delay!=null),fail=rows.filter(x=>x.status==='unmatched'),partial=rows.filter(x=>x.status==='partial');
  const ambiguous=rows.filter(x=>x.status==='ambiguous'),unobservable=rows.filter(x=>x.status==='unobservable');
  return [
    'RWX 高层→底层执行延迟评测','='.repeat(42),
    `Agent team=${port.team}；Replay 仅使用该 team 的有效指令`,
    `端口总命令 ${port.commands.length}；直接 B 类 ${rows.length}；间接/本地设置类 ${port.excluded.length}`,
    `Replay 对应 team 的有效指令 ${low.length}；有唯一归属的执行痕迹 ${matched.length}；匹配 ${matched.length-partial.length}；部分 ${partial.length}；未匹配 ${fail.length}；归属歧义 ${ambiguous.length}；字段不足 ${unobservable.length}`,
    `时间口径：毫秒日志 ${port.commands.filter(h=>h.timePrecision==='milliseconds').length} 条；旧整秒日志 ${port.commands.filter(h=>h.timePrecision==='seconds').length} 条；均为游戏时间。`,
    '新 [MM:SS:mmm] 直接保留收到命令时的 gameTimeMillis，delayMin=delayMax=X−t。',
    '旧 [MM:SS] 真实收包时刻在 [t,t+1)；delay=X−t 为上界，delayMin=max(0,X−t−1)。旧日志无法补回毫秒。',
    '即时命令窗口 5 秒（旧日志再含 1 秒不确定度）；待发最长 600 秒，无 --id 的 pending-clear 截断后续候选。',
    'X=首条对应底层执行；Y=最后一条对应底层执行。',
    'partial 仅表示请求名单/数量覆盖不足，不等于 CLI 执行失败；选择器/动态过滤的覆盖率无法重建。',
    ...(port.stopped?[`输入在第 ${port.stopped.line} 行停止：${port.stopped.content}；之后的人工干预记录未参与分析。`]:[]),
    '', '【首次生效延迟 X−高层下发】',
    `样本 ${delay.count}；原始均值 ${fmt(delay.mean)}s；去离群均值 ${fmt(delay.cleanMean)}s；P50 ${fmt(delay.median)}s；P95 ${fmt(delay.p95)}s；阈值 ${fmt(delay.threshold)}s；离群 ${delay.outliers}`,
    ...matched.filter(x=>x.delayOutlier).map(x=>`  [离群] ${x.highClock} → ${x.firstClock}：${fmt(x.delay)}s（高层行 ${x.highLine}）`),
    '', '【底层执行跨度 Y−X】',
    `样本 ${span.count}；原始均值 ${fmt(span.mean)}s；去离群均值 ${fmt(span.cleanMean)}s；P50 ${fmt(span.median)}s；P95 ${fmt(span.p95)}s；阈值 ${fmt(span.threshold)}s；离群 ${span.outliers}`,
    ...matched.filter(x=>x.spanOutlier).map(x=>`  [离群] ${x.firstClock} → ${x.lastClock}：${fmt(x.span)}s（高层行 ${x.highLine}）`),
    '', '【只发生部分执行】',
    ...partial.map(x=>`  行 ${x.highLine} [${x.highClock}] ${x.command}；底层 ${x.lowLines.join(',')} 行，覆盖单位 ${x.coveredUnits??'-'}/${x.requestedUnits??'-'}；未覆盖 ID ${x.missingUnits.join(',')||'-'}`),
    '', '【归属歧义（不分配底层记录、不计入延迟）】',
    ...ambiguous.map(x=>`  行 ${x.highLine} [${x.highClock}] ${x.command}；与高层行 ${x.competingHighLines.join(',')} 竞争 Replay 行 ${x.candidateLowLines.join(',')}（${[...new Set(x.candidateTimes)].map(stamp).join(',')}）`),
    '', '【JSONL 字段不足】',
    ...unobservable.map(x=>`  行 ${x.highLine}：${x.note}`),
    '', '【未匹配（不能据此判断是否被引擎拒绝）】',
    ...fail.map(x=>`  行 ${x.highLine} [${x.highClock}] ${x.command}`),
    '', '【间接/本地 B 类（不计直接执行延迟）】',
    ...Object.entries(port.excluded.reduce((counts,h)=>(counts[h.name]=(counts[h.name]||0)+1,counts),{})).map(([name,count])=>`  ${name} ×${count}：${INDIRECT_RULES[name]||'仅改变默认姿态；未来新单位会另产生 attack_mode'}`),
    '', '说明：没有对应 Replay 命令时，不伪造延迟，也不把它计入平均值。',
    '仅衡量底层指令进入游戏记录的时间，不等于生产完成、移动到达等状态效果。',
  ].join('\n')+'\n';
}
function injectHtml(original,report,rows,delay,span,team,native){
  const ok=rows.filter(x=>x.delay!=null),unmatched=rows.filter(x=>x.status==='unmatched');
  const table=rows.map(r=>`<tr><td>${r.highLine}</td><td>${r.highClock}</td><td><code>${esc(r.command)}</code></td><td>${r.status}</td><td>${fmt(r.delayMin)}–${fmt(r.delayMax)}</td><td>${fmt(r.span)}</td><td>${esc(r.firstClock||'—')} → ${esc(r.lastClock||'—')}<br>Replay 行 ${esc(r.lowLines.join(',')||r.candidateLowLines.join(',')||'—')}<br>${esc(r.note)}</td></tr>`).join('');
  const rules=Object.entries({...RULES,...Object.fromEntries(Object.entries(INDIRECT_RULES).map(([k,v])=>[k,{low:'间接/本地',match:v}]))}).map(([k,v])=>`<tr><td><code>${esc(k)}</code></td><td>${esc(v.low)}</td><td>${esc(v.match)}</td></tr>`).join('');
  const nativeSection=`<section id="native-intervals"><h2>我方相邻底层命令执行间隔</h2><p class="caption">整份 Replay 的 team=${team} 有效命令，包含手动、自动微操和 UI，不依赖高低层映射。每个点为相邻两条记录的间隔；横坐标是前条执行时刻，纵坐标是间隔秒数。同 tick 的 0 秒间隔保留，红点表示长间隔离群值。悬浮可查看两条底层命令、tick 和 JSONL 行号。</p>
  <div class="cards"><div class="card"><small>底层命令 / 相邻间隔</small><strong>${native.summary.commandCount} / ${native.intervals.length}</strong></div><div class="card"><small>原始平均间隔</small><strong>${fmt(native.summary.stats.mean)} 秒</strong></div><div class="card"><small>去离群平均间隔</small><strong>${fmt(native.summary.stats.cleanMean)} 秒</strong></div><div class="card"><small>离群 / 零间隔</small><strong>${native.summary.stats.outliers} / ${native.summary.zeroIntervals}</strong></div></div>
  ${plotNativeIntervals(native)}</section>`;
  const section=nativeSection+`<section id="execution"><h2>高层 → 底层命令执行延迟</h2><p class="caption">Agent team=${team}。蓝线横坐标是端口下发时刻，橙线横坐标是对应底层首条执行时刻 X；红点代表各自的离群值。可鼠标悬浮检查映射详情。</p>
  <div class="cards"><div class="card"><small>直接 B 类</small><strong>${rows.length}</strong></div><div class="card"><small>已找到唯一归属执行</small><strong>${ok.length}</strong></div><div class="card"><small>未匹配 / 歧义 / 字段不足</small><strong>${unmatched.length} / ${rows.filter(r=>r.status==='ambiguous').length} / ${rows.filter(r=>r.status==='unobservable').length}</strong></div><div class="card"><small>延迟上界 / 执行跨度（去离群均值）</small><strong>${fmt(delay.cleanMean)} / ${fmt(span.cleanMean)} 秒</strong></div></div>
  ${plot(ok,delay,span)}</section>
  <section><h2>高层→底层执行报告</h2><pre>${esc(report)}</pre></section>
  <section><h2>逐条命令匹配明细</h2><div style="max-height:460px;overflow:auto"><table style="border-collapse:collapse;width:100%;font-size:12px"><thead><tr><th>行</th><th>时刻</th><th>高层指令</th><th>状态</th><th>延迟范围(s)</th><th>跨度(s)</th><th>X → Y / 证据 / 限制</th></tr></thead><tbody>${table}</tbody></table></div></section>
  <section><h2>经源码核对的 B 类映射规则</h2><table style="width:100%;font-size:12px"><thead><tr><th>高层指令</th><th>底层类型</th><th>匹配键（另含 team / 时间）</th></tr></thead><tbody>${rules}</tbody></table></section>`;
  const style='<style>#execution ~ section table td,#execution ~ section table th{border-bottom:1px solid #e2e8ef;padding:7px;text-align:left;vertical-align:top}#execution ~ section table code{overflow-wrap:anywhere}</style>';
  if(!original.includes('</main>'))throw Error('基础 HTML 不包含 </main>');
  return original.replace('</head>',style+'</head>').replace('</main>',section+'</main>');
}
async function evaluate(file1,file2,options={}){
  if(!fs.existsSync(file1)||!fs.existsSync(file2))throw Error('两个输入文件必须存在');
  const port=readHigh(file1),low=readLow(file2,port.team);
  const rows=matchAll(port.high,low,{commands:port.commands});
  if(!rows.some(r=>r.status!=='unmatched'))throw Error(`team=${port.team} 的高层与底层命令无法建立任何匹配，请检查两份文件的实际对局内容（不只看文件名）`);
  const matched=rows.filter(x=>x.delay!=null),delay=stats(matched,'delay',0.5),span=stats(matched,'span',0.05);
  // 基础 A/B 分析已内联在本文件的 evaluateBase 中，保持原 A/B、周期、B→A、四类间隔、直方图和准入条件不变。
  const out=await evaluateBase(file1,options);
  if(out==null)throw Error('第一轮的 5 分钟 / 50 条 A+B 准入检查未通过');
  const nativeIntervals=nativeIntervalsOf(low),minOutlierSeconds=options.minOutlierSeconds??5;
  const nativeIntervalStats=stats(nativeIntervals,'seconds',minOutlierSeconds);
  const native={intervals:nativeIntervals,summary:{sourceFile:path.resolve(file2),team:port.team,
    scope:'entire Replay; controlled team only; noop excluded; includes manual/automation/UI commands',
    definition:'next time_s minus previous time_s; X=previous time_s; one row per command; same-tick zero intervals retained',
    commandCount:low.length,firstTime:low[0]?.time_s??null,lastTime:low.at(-1)?.time_s??null,
    zeroIntervals:nativeIntervals.filter(row=>row.seconds===0).length,minOutlierSeconds,stats:nativeIntervalStats,
    sequenceFile:'native_command_intervals.csv'}};
  const baseReportFile=path.join(out,'report.txt'),baseReport=fs.readFileSync(baseReportFile,'utf8');
  const combinedReport=baseReport+nativeIntervalReport(native);
  fs.writeFileSync(baseReportFile,combinedReport,'utf8');
  const baseSummaryFile=path.join(out,'summary.json'),baseSummary=JSON.parse(fs.readFileSync(baseSummaryFile,'utf8'));
  baseSummary.nativeCommandIntervals=native.summary;
  fs.writeFileSync(baseSummaryFile,JSON.stringify(baseSummary,null,2),'utf8');
  writeCSV(path.join(out,'native_command_intervals.csv'),nativeIntervals,['index','team','firstTime','lastTime','start','end','seconds','firstTick','lastTick','firstLine','lastLine','firstCommand','lastCommand','secondsOutlier']);
  const report=reportText(port,low,rows,delay,span);
  fs.writeFileSync(path.join(out,'execution_report.txt'),report,'utf8');
  fs.writeFileSync(path.join(out,'execution_summary.json'),JSON.stringify({file1:path.resolve(file1),file2:path.resolve(file2),team:port.team,highCount:rows.length,lowCount:low.length,excludedCount:port.excluded.length,inputStopped:port.stopped,statusCounts:Object.fromEntries(['matched','partial','unmatched','ambiguous','unobservable'].map(s=>[s,rows.filter(r=>r.status===s).length])),delayBasis:'X minus receipt game time; millisecond logs exact, legacy integer seconds are upper bounds',delay,span},null,2),'utf8');
  fs.writeFileSync(path.join(out,'mapping_rules.json'),JSON.stringify({...RULES,...Object.fromEntries(Object.entries(INDIRECT_RULES).map(([k,v])=>[k,{low:null,match:v}]))},null,2),'utf8');
  fs.writeFileSync(path.join(out,'execution_matches.jsonl'),rows.map(r=>JSON.stringify(r)).join('\n')+'\n','utf8');
  writeCSV(path.join(out,'execution_matches.csv'),rows,['highLine','highClock','timePrecision','highTeam','command','rule','status','expectedCount','matchedCount','matchedOperations','operationsByUnit','requestedUnits','coveredUnits','missingUnits','coverageVerifiable','firstTick','lastTick','firstClock','lastClock','delay','delayMin','delayMax','span','delayOutlier','spanOutlier','lowLines','candidateLowLines','competingHighLines','note']);
  const htmlFile=path.join(out,'report.html');
  const originalHtml=fs.readFileSync(htmlFile,'utf8'),baseReportBlock=`<pre>${esc(baseReport)}</pre>`;
  if(!originalHtml.includes(baseReportBlock))throw Error('基础 HTML 缺少文字报告，无法追加底层间隔统计');
  const combinedHtml=originalHtml.replace(baseReportBlock,`<pre>${esc(combinedReport)}</pre>`);
  fs.writeFileSync(htmlFile,injectHtml(combinedHtml,report,rows,delay,span,port.team,native),'utf8');
  console.log(`\n双源评测完成：${htmlFile}`);
  console.log(`team=${port.team}：直接 B=${rows.length}，唯一归属执行=${matched.length}，未匹配=${rows.filter(r=>r.status==='unmatched').length}，歧义=${rows.filter(r=>r.status==='ambiguous').length}；首次延迟上界=${fmt(delay.cleanMean)}s，执行跨度=${fmt(span.cleanMean)}s`);
  console.log(`底层相邻间隔=${nativeIntervals.length}，原始均值=${fmt(nativeIntervalStats.mean)}s，去离群均值=${fmt(nativeIntervalStats.cleanMean)}s，离群=${nativeIntervalStats.outliers}，横坐标=前条底层执行时间`);
  return {out,rows,delay,span,nativeIntervals,nativeIntervalStats};
}
if(require.main===module){
  if(process.argv.length!==4){console.error('用法: node evaluate_stage_1.js <commands.txt> <replay.jsonl>');process.exitCode=1;}
  else evaluate(process.argv[2],process.argv[3]).catch(e=>{console.error('评测失败：'+e.message);process.exitCode=1;});
}
module.exports={evaluateBase,readLog,evaluate,RULES,readHigh,readLow,matchAll,stats,nativeIntervalsOf};
