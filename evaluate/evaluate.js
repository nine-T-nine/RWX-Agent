#!/usr/bin/env node
/**
 * RWX 双源实时性分析：端口命令 (A/B) + 游戏 Replay JSONL (B 的底层执行)。
 * node evaluate.js <commands.txt> <replay.jsonl>
 * 与 evaluate_1.js 放在同一目录；无第三方依赖。
 *
 * 原 evaluate_1.js 全部报告/图表原样生成；此脚本向 report.html 追加双源分析，
 * 并写入 execution_*.{txt,json,csv,jsonl} 与 mapping_rules.json。
 */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { evaluate_1 } = require('./evaluate_1.js');

// 简化版映射表：此处只保留能从 Replay JSONL 可靠找到执行痕迹的指令。
const RULES = Object.freeze({
  'rwx-game-order-move':        {low:'move',        match:'units + x/y'},
  'rwx-game-order-attack-move': {low:'attackMove',  match:'units + x/y'},
  'rwx-game-order-attack':      {low:'attack',      match:'units + target_unit'},
  'rwx-game-order-patrol':      {low:'patrol',      match:'units + x/y'},
  'rwx-game-order-guard':       {low:'guard',       match:'units + target_unit'},
  'rwx-game-order-guard-at':    {low:'guardAt',     match:'units + x/y'},
  'rwx-game-order-follow':      {low:'follow',      match:'units + target_unit'},
  'rwx-game-order-load-into':   {low:'loadInto',    match:'units + target_unit'},
  'rwx-game-order-load-up':     {low:'loadUp',      match:'units + target_unit'},
  'rwx-game-order-repair':      {low:'repair',      match:'units + target_unit'},
  'rwx-game-order-assist':      {low:'repair',      match:'units + target_unit'},
  'rwx-game-order-reclaim':     {low:'reclaim',     match:'units + target_unit'},
  'rwx-game-order-build':       {low:'build',       match:'builder + build + x/y'},
  'rwx-game-order-action':      {low:'action',      match:'units + action + count'},
  'rwx-game-order-push':        {low:'move+attackMove',match:'rally x/y + attack x/y'},
});
// 设置/本地管理指令，不属于“下达后引擎执行 B 命令”评测。
const SETTINGS = new Set(`rwx-game-auto-set rwx-game-auto-clear rwx-game-auto-reset rwx-game-keep-set rwx-game-keep-clear rwx-game-pending-clear rwx-game-group-save rwx-game-group-delete rwx-game-order-rally rwx-game-order-attack-mode rwx-game-order-cancel`.split(' '));
const LINE = /^\[(\d+):([0-5]\d)\]<([^<>]*)><([^<>]*)>\s*$/;
const stamp = s => `${String(Math.floor(s/60)).padStart(2,'0')}:${String(Math.floor(s%60)).padStart(2,'0')}.${Math.round((s%1)*1000).toString().padStart(3,'0')}`;
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
    const meta=Object.fromEntries(m[3].split(';').map(x=>x.split('=')));
    const command=m[4].replace(/\\t/g,' ').trim(),name=command.split(/\s+/)[0]?.toLowerCase();
    if(!name?.startsWith('rwx-')||!['player','faction','match','role','name'].every(k=>k in meta))throw Error(`端口文件第 ${i+1} 行格式错误`);
    commands.push({line:i+1,time:Number(m[1])*60+Number(m[2]),team:Number(meta.player),meta,name,command,args:argsOf(command)});
  }
  const inGame=commands.filter(x=>x.meta.match!=='none');
  const teams=new Set(inGame.map(x=>x.team));
  if(teams.size!==1||[...teams].some(x=>!Number.isInteger(x)))throw Error('端口文件在同一局内出现多个玩家 team，拒绝合并分析');
  const high=inGame.filter(x=>x.name.startsWith('rwx-game-order-')&&!SETTINGS.has(x.name));
  for(const h of high)if(!(h.name in RULES))throw Error(`未配置映射规则：端口第 ${h.line} 行 ${h.name}`);
  return {commands,high,team:[...teams][0],stopped,excluded:inGame.filter(x=>SETTINGS.has(x.name))};
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
const norm = s => String(s??'').toLowerCase().replace(/^custom:/,'').replace(/[-_\s]/g,'');
function buildMatches(high,low){
  if(norm(high)==='turret'&&norm(low)==='cturrett1')return true; // CLI 别名
  return norm(high)===norm(low);
}
function idsOf(value){
  if(typeof value!=='string'||!/^\d+(,\d+)*$/.test(value))return null;
  return value.split(',').map(Number);
}
function xyOf(a,x='x',y='y'){
  return a[x]!=null&&a[y]!=null&&Number.isFinite(+a[x])&&Number.isFinite(+a[y])?[+a[x],+a[y]]:null;
}
function near(actual,coord,tol=2){return coord&&Number.isFinite(actual.x)&&Number.isFinite(actual.y)&&Math.hypot(actual.x-coord[0],actual.y-coord[1])<=tol;}
function signature(h){
  const a=h.args;return [h.name,a.units||'',a.builder||'',a.type||'',a.action||'',a.target||'',a.x||'',a.y||''].join('|');
}
function matchLow(h,l){
  const a=h.args,rule=RULES[h.name];
  const ids=idsOf(a.units)||(a.builder&&/^\d+$/.test(a.builder)?[+a.builder]:null);
  // 多单位原生命令有时只保留实际接受命令的存活单位，允许是非空子集。
  if(ids){if(!l.units.length||l.units.some(id=>!ids.includes(id)))return false;}
  else if(typeof a.units!=='string'||!(/^(all-army|all-units|group:|area:|rect:)/.test(a.units)))return false;
  if(h.name==='rwx-game-order-action')return l.action!=null&&String(l.action).toLowerCase()===String(a.action??'').toLowerCase();
  if(h.name==='rwx-game-order-build'){
    const tol=a['auto-place']?20*(Number(a.radius||2)+1):2;
    return l.type==='build'&&buildMatches(a.type,l.build)&&near(l,xyOf(a),tol);
  }
  if(h.name==='rwx-game-order-push'){
    return (l.type==='move'&&near(l,xyOf(a,'rally-x','rally-y')||xyOf(a),2))||
           (l.type==='attackMove'&&near(l,xyOf(a),2));
  }
  if(l.type!==rule.low)return false;
  if(['attack','guard','follow','loadInto','loadUp','repair','reclaim'].includes(l.type))return Number(a.target)===l.target_unit;
  return near(l,xyOf(a),2);
}
/**
 * 每条高层命令最多关联一组底层命令，底层记录不重复分配。
 * 按 team(读取阶段已筛选)、动作、单位、目标、坐标和时间顺序匹配。
 * 一条原生指令可能已经包含全部单位；若只包含部分单位则如实标记 partial。
 */
function matchAll(high,low){
  const used=new Set(),results=[];
  for(let i=0;i<high.length;i++){
    const h=high[i],a=h.args,ids=idsOf(a.units)||(a.builder&&/^\d+$/.test(a.builder)?[+a.builder]:null);
    const next=high.slice(i+1).find(x=>signature(x)===signature(h));
    const deferred=Boolean(a['allow-unaffordable']||a['after-upgrade']);
    // 即时指令只找同一操作窗口内的执行；待发指令则允许延迟到满足条件。
    // 同签名的下一次高层命令划分归属，防止同一条底层命令被重复认领。
    const end=Math.min(next?.time??Infinity,deferred?Infinity:h.time+5);
    const possible=low.filter(l=>!used.has(l.id)&&l.time_s>=h.time&&l.time_s<end&&matchLow(h,l));
    const selected=[],covered=new Set();
    const requestedCount=(Number.isInteger(+a.count)&&+a.count>0)?+a.count:1;
    // action --count N 是每个工厂重复 N 次；build --count N 也是多条建造指令。
    const expected=h.name==='rwx-game-order-action'?requestedCount*(ids?.length||1)
      :h.name==='rwx-game-order-build'?requestedCount
      :h.name==='rwx-game-order-push'?2:1;
    for(const l of possible){
      if(selected.length){
        const batch=(h.name==='rwx-game-order-action'||h.name==='rwx-game-order-build')&&expected>1;
        if(!batch&&l.time_s-selected[0].time_s>2)break;
      }
      selected.push(l);
      for(const id of l.units)covered.add(id);
      if(h.name==='rwx-game-order-push'){
        if(selected.some(x=>x.type==='move')&&selected.some(x=>x.type==='attackMove'))break;
      }else if((h.name==='rwx-game-order-action'||h.name==='rwx-game-order-build')&&selected.reduce((n,x)=>n+(ids?x.units.filter(id=>ids.includes(id)).length:Math.max(x.units.length,1)),0)>=expected)break;
      else if(h.name!=='rwx-game-order-action'&&h.name!=='rwx-game-order-build'&&(!ids||ids.every(id=>covered.has(id))))break;
    }
    for(const l of selected)used.add(l.id);
    const first=selected[0],last=selected.at(-1);
    const actualActions=selected.reduce((n,x)=>n+(ids?x.units.filter(id=>ids.includes(id)).length:Math.max(x.units.length,1)),0);
    const enoughCommands=h.name==='rwx-game-order-push'
      ?selected.some(x=>x.type==='move')&&selected.some(x=>x.type==='attackMove')
      :(h.name==='rwx-game-order-action'||h.name==='rwx-game-order-build')
        ?actualActions>=expected :selected.length>0;
    // 数字单位列表可以包含已经死亡的单位；底层只执行部分时记录 partial。
    const enoughUnits=!ids||h.name==='rwx-game-order-action'||h.name==='rwx-game-order-build'||ids.every(id=>covered.has(id));
    const status=!first?'unmatched':enoughCommands&&enoughUnits?'matched':'partial';
    results.push({highLine:h.line,highTime:h.time,highClock:stamp(h.time),command:h.command,
      rule:RULES[h.name].low,highTeam:h.team,requestedUnits:ids?.length??null,
      coveredUnits:ids?ids.filter(id=>covered.has(id)).length:null,
      expectedCount:expected,matchedCount:selected.length,status,
      firstTime:first?.time_s??null,lastTime:last?.time_s??null,
      firstTick:first?.tick??null,lastTick:last?.tick??null,
      firstClock:first?stamp(first.time_s):null,lastClock:last?stamp(last.time_s):null,
      delay:first?first.time_s-h.time:null,span:first?last.time_s-first.time_s:null,
      lowLines:selected.map(x=>x.line),lowUnits:selected.map(x=>x.units),
      note:!first?'没有相符底层记录（命令可能被拒绝、取消或没有成功执行）'
        :status==='partial'?'实际执行的单位或子命令少于高层请求':'',
    });
  }
  return results;
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
  const series=[{name:'首次生效延迟',color:'#3278b8',value:'delay',x:'highTime',stats:firstStats},
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
  const matched=rows.filter(x=>x.delay!=null),fail=rows.filter(x=>x.delay==null),partial=rows.filter(x=>x.status==='partial');
  return [
    'RWX 高层→底层执行延迟评测','='.repeat(42),
    `Agent team=${port.team}；Replay 仅使用该 team 的有效指令`,
    `端口总命令 ${port.commands.length}；可映射 B 类 ${rows.length}；已排除设置类 ${port.excluded.length}`,
    `Replay 对应 team 的有效指令 ${low.length}；有执行痕迹 ${matched.length}；完整 ${matched.length-partial.length}；部分 ${partial.length}；未匹配 ${fail.length}`,
    '时间口径：端口整秒当作准确下发时刻；Replay 使用精确 time_s（游戏时间）。',
    '非待发命令只关联 5 秒内的底层执行；--allow-unaffordable/--after-upgrade 可延迟执行。',
    'X=首条对应底层执行；Y=最后一条对应底层执行。',
    '', '【首次生效延迟 X−高层下发】',
    `样本 ${delay.count}；原始均值 ${fmt(delay.mean)}s；去离群均值 ${fmt(delay.cleanMean)}s；P50 ${fmt(delay.median)}s；P95 ${fmt(delay.p95)}s；阈值 ${fmt(delay.threshold)}s；离群 ${delay.outliers}`,
    ...matched.filter(x=>x.delayOutlier).map(x=>`  [离群] ${x.highClock} → ${x.firstClock}：${fmt(x.delay)}s（高层行 ${x.highLine}）`),
    '', '【底层执行跨度 Y−X】',
    `样本 ${span.count}；原始均值 ${fmt(span.mean)}s；去离群均值 ${fmt(span.cleanMean)}s；P50 ${fmt(span.median)}s；P95 ${fmt(span.p95)}s；阈值 ${fmt(span.threshold)}s；离群 ${span.outliers}`,
    ...matched.filter(x=>x.spanOutlier).map(x=>`  [离群] ${x.firstClock} → ${x.lastClock}：${fmt(x.span)}s（高层行 ${x.highLine}）`),
    '', '【只发生部分执行】',
    ...partial.map(x=>`  行 ${x.highLine} [${x.highClock}] ${x.command}；已执行 ${x.matchedCount} 条，覆盖单位 ${x.coveredUnits??'-'}/${x.requestedUnits??'-'}`),
    '', '【未匹配（不能据此判断是否被引擎拒绝）】',
    ...fail.map(x=>`  行 ${x.highLine} [${x.highClock}] ${x.command}`),
    '', '说明：没有对应 Replay 命令时，不伪造延迟，也不把它计入平均值。',
    '仅衡量底层指令进入游戏记录的时间，不等于生产完成、移动到达等状态效果。',
  ].join('\n')+'\n';
}
function injectHtml(original,report,rows,delay,span,team){
  const ok=rows.filter(x=>x.delay!=null),unmatched=rows.filter(x=>x.delay==null);
  const table=rows.map(r=>`<tr><td>${r.highLine}</td><td>${r.highClock}</td><td><code>${esc(r.command)}</code></td><td>${r.status}</td><td>${fmt(r.delay)}</td><td>${fmt(r.span)}</td><td>${esc(r.firstClock||'—')} → ${esc(r.lastClock||'—')}</td></tr>`).join('');
  const rules=Object.entries(RULES).map(([k,v])=>`<tr><td><code>${esc(k)}</code></td><td>${esc(v.low)}</td><td>${esc(v.match)}</td></tr>`).join('');
  const section=`<section id="execution"><h2>高层 → 底层命令执行延迟</h2><p class="caption">Agent team=${team}。蓝线横坐标是端口下发时刻，橙线横坐标是对应底层首条执行时刻 X；红点代表各自的离群值。可鼠标悬浮检查映射详情。</p>
  <div class="cards"><div class="card"><small>可映射动作</small><strong>${rows.length}</strong></div><div class="card"><small>已找到底层执行</small><strong>${ok.length}</strong></div><div class="card"><small>未匹配的命令</small><strong>${unmatched.length}</strong></div><div class="card"><small>生效延迟 / 执行跨度（去离群均值）</small><strong>${fmt(delay.cleanMean)} / ${fmt(span.cleanMean)} 秒</strong></div></div>
  ${plot(ok,delay,span)}</section>
  <section><h2>高层→底层执行报告</h2><pre>${esc(report)}</pre></section>
  <section><h2>逐条命令匹配明细</h2><div style="max-height:460px;overflow:auto"><table style="border-collapse:collapse;width:100%;font-size:12px"><thead><tr><th>行</th><th>时刻</th><th>高层指令</th><th>状态</th><th>延迟(s)</th><th>跨度(s)</th><th>X → Y</th></tr></thead><tbody>${table}</tbody></table></div></section>
  <section><h2>简化映射规则</h2><table style="width:100%;font-size:12px"><thead><tr><th>高层指令</th><th>底层类型</th><th>匹配键（另含 team / 时间）</th></tr></thead><tbody>${rules}</tbody></table></section>`;
  const style='<style>#execution ~ section table td,#execution ~ section table th{border-bottom:1px solid #e2e8ef;padding:7px;text-align:left;vertical-align:top}#execution ~ section table code{overflow-wrap:anywhere}</style>';
  if(!original.includes('</main>'))throw Error('evaluate_1.js 生成的 HTML 不包含 </main>');
  return original.replace('</head>',style+'</head>').replace('</main>',section+'</main>');
}
async function evaluate(file1,file2){
  if(!fs.existsSync(file1)||!fs.existsSync(file2))throw Error('两个输入文件必须存在');
  const port=readHigh(file1),low=readLow(file2,port.team);
  const rows=matchAll(port.high,low);
  if(!rows.some(r=>r.status==='matched'))throw Error(`team=${port.team} 的高层与底层命令无法建立任何完整匹配，请检查两份文件或映射规则`);
  const matched=rows.filter(x=>x.delay!=null),delay=stats(matched,'delay',0.5),span=stats(matched,'span',0.05);
  // 复用 evaluate_1.js 而不是复制其实现，保持原来 A/B、周期、B→A、四类间隔、直方图和准入条件不变。
  const out=await evaluate_1(file1);
  if(out==null)throw Error('evaluate_1.js 的 5 分钟 / 50 条 A+B 准入检查未通过');
  const report=reportText(port,low,rows,delay,span);
  fs.writeFileSync(path.join(out,'execution_report.txt'),report,'utf8');
  fs.writeFileSync(path.join(out,'execution_summary.json'),JSON.stringify({file1:path.resolve(file1),file2:path.resolve(file2),team:port.team,highCount:rows.length,lowCount:low.length,statusCounts:Object.fromEntries(['matched','partial','unmatched'].map(s=>[s,rows.filter(r=>r.status===s).length])),delay,span},null,2),'utf8');
  fs.writeFileSync(path.join(out,'mapping_rules.json'),JSON.stringify(RULES,null,2),'utf8');
  fs.writeFileSync(path.join(out,'execution_matches.jsonl'),rows.map(r=>JSON.stringify(r)).join('\n')+'\n','utf8');
  writeCSV(path.join(out,'execution_matches.csv'),rows,['highLine','highClock','highTeam','command','rule','status','expectedCount','matchedCount','requestedUnits','coveredUnits','firstTick','lastTick','firstClock','lastClock','delay','span','delayOutlier','spanOutlier','lowLines']);
  const htmlFile=path.join(out,'report.html');
  fs.writeFileSync(htmlFile,injectHtml(fs.readFileSync(htmlFile,'utf8'),report,rows,delay,span,port.team),'utf8');
  console.log(`\n双源评测完成：${htmlFile}`);
  console.log(`team=${port.team}：高层 B=${rows.length}，底层执行=${matched.length}，未匹配=${rows.length-matched.length}；首次执行延迟=${fmt(delay.cleanMean)}s，执行跨度=${fmt(span.cleanMean)}s`);
  return {out,rows,delay,span};
}
if(require.main===module){
  if(process.argv.length!==4){console.error('用法: node evaluate.js <commands.txt> <replay.jsonl>');process.exitCode=1;}
  else evaluate(process.argv[2],process.argv[3]).catch(e=>{console.error('评测失败：'+e.message);process.exitCode=1;});
}
module.exports={evaluate,RULES,readHigh,readLow,matchAll,stats};
