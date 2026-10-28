#!/usr/bin/env node
/** RWX CLI replay timing analysis. Usage: node evaluate_1.js replay.txt
 * No external dependencies. Output: standalone interactive HTML + CSV/JSON/TXT
 */
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
    if (!['name','player','faction','role','match'].every(k=>fields.has(k)) || !token.startsWith('rwx-')) {
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

async function evaluate_1(fileName, {outputRoot='./output',minOutlierSeconds=5,overridesPath=null}={}) {
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
  const out=path.resolve(outputRoot,path.parse(fileName).name+'_analysis');fs.mkdirSync(out,{recursive:true});
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

if(require.main===module){const argv=process.argv.slice(2);const file=argv[0];if(!file){console.error('用法: node evaluate_1.js <replay.txt> [--output-root DIR] [--min-outlier-seconds N] [--overrides FILE.json]');process.exitCode=1;}else{const args={};for(let i=1;i<argv.length;i+=2){const key=argv[i],val=argv[i+1];if(key==='--output-root')args.outputRoot=val;else if(key==='--min-outlier-seconds')args.minOutlierSeconds=Number(val);else if(key==='--overrides')args.overridesPath=val;else{console.error(`未知参数: ${key}`);process.exitCode=1;break;}}if(!process.exitCode)evaluate_1(file,args).catch(e=>{console.error(e.message);process.exitCode=1;});}}
module.exports={evaluate_1,readLog};
