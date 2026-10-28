'use strict';

// Audited against RwxLocalGameCommands, RwxEngineLocalGameService and Command.
// Replay records engine command submission, not movement/production completion.
const RULES = Object.freeze({
  'rwx-game-order-move': {low:'move', match:'units + x/y + command_queued'},
  'rwx-game-order-attack-move': {low:'attackMove', match:'units + x/y + command_queued'},
  'rwx-game-order-attack': {low:'attack', match:'units + target_unit + command_queued'},
  'rwx-game-order-stop': {low:'clear_existing_orders', match:'units + clear_existing_orders (type=null)'},
  'rwx-game-order-patrol': {low:'patrol', match:'units + x/y + command_queued'},
  'rwx-game-order-guard': {low:'guard', match:'units + target_unit + command_queued'},
  'rwx-game-order-guard-at': {low:'guardAt', match:'units + x/y + command_queued'},
  'rwx-game-order-follow': {low:'follow', match:'units + target_unit + command_queued'},
  'rwx-game-order-load-into': {low:'loadInto', match:'units + target_unit + command_queued'},
  'rwx-game-order-load-up': {low:'loadUp', match:'units + target_unit + command_queued'},
  'rwx-game-order-repair': {low:'repair', match:'units + target_unit + command_queued'},
  'rwx-game-order-assist': {low:'repair', match:'units + target_unit (unfinished building) + command_queued'},
  'rwx-game-order-reclaim': {low:'reclaim', match:'units + target_unit + command_queued'},
  'rwx-game-order-build': {low:'build', match:'builder + canonical build type + snapped/auto-place grid + build_count'},
  'rwx-game-order-action': {low:'action', match:'units + exact action + target_unit/target_point + per-unit count; stop_current_action=false'},
  'rwx-game-order-cancel': {low:'action(cancel)', match:'unit + optional action + stop_current_action=true + count/all'},
  'rwx-game-order-rally': {low:'rally', match:'units + rally (type=null)'},
  'rwx-game-order-attack-mode': {low:'attack_mode', match:'units + attack_mode; default-only changes are indirect'},
  'rwx-game-order-push': {low:'[move+]attackMove', match:'optional rally move (not queued), then attackMove (queued only with rally); same selection'},
});

const INDIRECT_RULES = Object.freeze({
  'rwx-game-group-save':'本地保存选择器/名单；不直接提交引擎命令',
  'rwx-game-group-delete':'本地删除组；不直接提交引擎命令',
  'rwx-game-keep-set':'建立补产规则；采样器以后提交 action，无唯一请求 ID',
  'rwx-game-keep-clear':'清除补产规则并撤销尚未执行的补产；无独立 Replay 命令',
  'rwx-game-pending-clear':'清除 CLI 待发队列；不等于 order-cancel，不提交退款 action',
  'rwx-game-auto-set':'配置微操；以后可产生 move/attackMove/attack/repair/action（上下潜）、clear-orders 或重发原 waypoint；idle-alert 不发指令，无唯一请求 ID',
  'rwx-game-auto-clear':'释放微操控制；可能 clear-orders 或恢复保存的任意 waypoint，不能按单一底层类型衡量',
  'rwx-game-auto-reset':'重置微操规则；可能 clear-orders 或恢复保存的任意 waypoint，不能按单一底层类型衡量',
});
const SETTINGS = new Set(Object.keys(INDIRECT_RULES));
const flag = (a,k) => a[k] === true;
const modeClears = a => /^(off|none|clear)$/i.test(a.mode);
const isIndirect = h => SETTINGS.has(h.name) || h.name==='rwx-game-order-attack-mode' && (!h.args.units || modeClears(h.args));
const norm = s => String(s??'').replace(/^custom:/i,'').toLowerCase();
// Shipped assets/units/* [core] overrideAndReplace. Do not equate arbitrary c_ or u_ names.
const BUILD_ALIASES = Object.freeze({
  extractor:'extractort1', fabricator:'fabricatort1', turret:'c_turret_t1',
  turrett2:'c_turret_t2_gun', turrett3:'c_turret_t3_gun',
  turret_artillery:'c_turret_t1_artillery', turret_flamethrower:'c_turret_t2_flame',
  antiairturret:'c_antiairturret', antiairturrett2:'c_antiairturrett2',
  nukelaucher:'nukelauncherc', antinukelaucher:'antinukelauncherc',
});
const canonicalBuild = s => BUILD_ALIASES[norm(s)] || norm(s);
const buildMatches = (a,b) => canonicalBuild(a) === canonicalBuild(b);
function idsOf(value){
  if(typeof value!=='string'||!/^\d+(,\d+)*$/.test(value))return null;
  const ids=[...new Set(value.split(',').map(Number))];
  return ids.every(Number.isSafeInteger)?ids:null;
}
function requestedIds(h){
  const a=h.args;
  const ids=idsOf(a.units)||idsOf(a.builder)||idsOf(a.unit);
  const excluded=String(a.exclude||'').split(',').filter(x=>/^\d+$/.test(x)).map(Number);
  return ids?.filter(id=>!excluded.includes(id))??null;
}
function xyOf(a,x='x',y='y'){
  return typeof a[x]==='string'&&typeof a[y]==='string'&&Number.isFinite(+a[x])&&Number.isFinite(+a[y])?[+a[x],+a[y]]:null;
}
function near(x,y,point,tolerance=0.1){
  return point&&Number.isFinite(x)&&Number.isFinite(y)&&Math.hypot(x-point[0],y-point[1])<=tolerance;
}
function snapPoint(type,point){
  if(!point)return null;
  const name=canonicalBuild(type);
  const offset=/^c_turret_|^c_antiairturret|^antiairturretflak$|^las(er)?defen[cs]e$|^bug_generator$/.test(name)?20:10;
  const offsets=name==='laboratory'?[10,0]:[offset,offset];
  // Engine map tiles are 20 world units. RwxEngineLocalGameService.snapBuildingPosition.
  return point.map((v,i)=>Math.trunc((v-offsets[i]+1)/20)*20+offsets[i]);
}
function buildPositionMatches(h,l){
  const snapped=snapPoint(h.args.type,xyOf(h.args));
  if(!snapped)return false;
  const dx=(l.x-snapped[0])/20,dy=(l.y-snapped[1])/20;
  if(!Number.isFinite(dx)||!Number.isFinite(dy)||Math.abs(dx-Math.round(dx))>0.005||Math.abs(dy-Math.round(dy))>0.005)return false;
  const radius=flag(h.args,'auto-place')?Math.max(1,Math.min(Number(h.args.radius??2),10)):0;
  // Search visits square rings, including their corners (not a Euclidean circle).
  return Math.max(Math.abs(dx),Math.abs(dy))<=radius;
}
function unitsMatch(h,l){
  if(!l.units.length)return false;
  const ids=requestedIds(h),a=h.args;
  if(ids){if(l.units.some(id=>!ids.includes(id)))return false;}
  else {
    const selector=a.units??(h.name==='rwx-game-order-push'?(a.group?'group:'+a.group:'all-army'):null);
    if(typeof selector!=='string'||!/^(all-army|all-units|group:.+|area:.+|rect:.+)$/.test(selector))return false;
  }
  const excludes=String(a.exclude||'').split(',');
  if(l.units.some(id=>excludes.includes(String(id))))return false;
  // Types of newly created units are not present in the initial save. Check known types only.
  const include=h.name==='rwx-game-order-build'?[]:String(a.type||'').split(',').filter(Boolean).map(norm);
  return !(l.unit_types||[]).some(t=>t && (include.length&&!include.includes(norm(t)) || excludes.some(e=>norm(e)===norm(t))));
}
function matchLow(h,l){
  const a=h.args,name=h.name;
  if(!unitsMatch(h,l)||l.system_action!=null)return false;
  if(name==='rwx-game-order-push'){
    const rally=xyOf(a,'rally-x','rally-y');
    return l.type==='move'&&rally&&near(l.x,l.y,rally)&&l.command_queued===false ||
      l.type==='attackMove'&&near(l.x,l.y,xyOf(a))&&l.command_queued===Boolean(rally);
  }
  if(name==='rwx-game-order-action'||name==='rwx-game-order-cancel'){
    const cancel=name==='rwx-game-order-cancel';
    if(l.type!=null||l.action==null||Boolean(l.stop_current_action)!==cancel)return false;
    if(a.action!=null && l.action!==a.action)return false; // ActionId is case-sensitive.
    if(cancel)return true;
    if(a.target!=null)return Number(a.target)===l.target_unit && l.target_point==null;
    const point=xyOf(a);
    return point?near(l.target_point?.[0],l.target_point?.[1],point)&&l.target_unit===-1:
      l.target_unit===-1&&l.target_point==null;
  }
  if(name==='rwx-game-order-rally')return l.type==null&&l.action==null&&near(l.rally?.[0],l.rally?.[1],xyOf(a));
  if(name==='rwx-game-order-attack-mode')return l.type==null&&l.action==null&&norm(l.attack_mode)===norm(a.mode);
  if(name==='rwx-game-order-stop')return l.type==null&&l.action==null&&l.clear_existing_orders===true;
  if('command_queued' in l && l.command_queued!==flag(a,'queue'))return false;
  if(name==='rwx-game-order-build')return l.type==='build'&&buildMatches(a.type,l.build)&&buildPositionMatches(h,l);
  if(l.type!==RULES[name].low)return false;
  if(['attack','guard','follow','loadInto','loadUp','repair','reclaim'].includes(l.type))return Number(a.target)===l.target_unit;
  return near(l.x,l.y,xyOf(a));
}
function signature(h){
  // Retry flags change the waiting policy, not command identity; count is deliberately included.
  const args={...h.args};
  delete args['allow-unaffordable'];delete args['after-upgrade'];
  return h.name+'|'+JSON.stringify(Object.keys(args).sort().map(k=>[k,args[k]]));
}
function missingFields(h,low){
  const fields=[];
  if(h.name==='rwx-game-order-stop')fields.push('clear_existing_orders');
  if(h.name==='rwx-game-order-rally')fields.push('rally');
  if(h.name==='rwx-game-order-cancel')fields.push('stop_current_action');
  if(h.name==='rwx-game-order-action')fields.push('target_point','stop_current_action');
  if(h.name==='rwx-game-order-build'&&Number(h.args.count)>1)fields.push('build_count');
  if(h.name==='rwx-game-order-push'||flag(h.args,'queue'))fields.push('command_queued');
  return fields.filter(f=>!low.some(l=>f in l));
}
function matchAll(high,low,{commands=high}={}){
  const used=new Set(),contexts=[];
  for(let i=0;i<high.length;i++){
    const h=high[i],a=h.args,ids=requestedIds(h),count=Number.isInteger(+a.count)&&+a.count>0?+a.count:1;
    const deferred=flag(a,'allow-unaffordable')||flag(a,'after-upgrade');
    let end=h.time+(deferred?600:5)+(h.timeUncertainty??1);
    // An immediate retry at the same second must not make the first interval empty.
    const next=high.slice(i+1).find(x=>x.time>h.time&&signature(x)===signature(h));
    if(!deferred&&next)end=Math.min(end,next.time);
    if(deferred){
      // An overlapping manual request is not proof of cancellation: it may itself be
      // rejected, and cancelling some units preserves the rest of a pending order.
      const clearing=commands.find(x=>x.line>h.line&&x.name==='rwx-game-pending-clear'&&x.args.id==null);
      if(clearing)end=Math.min(end,clearing.time);
    }
    const missing=missingFields(h,low);
    const possible=missing.length?[]:low.filter(l=>l.team===h.team&&!l.noop&&l.time_s>=h.time&&l.time_s<end&&matchLow(h,l));
    const action=h.name==='rwx-game-order-action',build=h.name==='rwx-game-order-build',push=h.name==='rwx-game-order-push',cancel=h.name==='rwx-game-order-cancel';
    const filtered=['type','hp','idle','exclude-builders'].some(k=>a[k]!=null)&&!build || String(a.exclude||'').split(',').some(x=>x&&!/^\d+$/.test(x));
    const coverageVerifiable=Boolean(ids&&!filtered);
    const expectedCount=push?(xyOf(a,'rally-x','rally-y')?2:1):cancel&&flag(a,'all')?null:
      (action?count*(ids?.length||1):build?Math.min(count,20):cancel?count:1);
    const selected=[],perUnit=new Map(),components=new Map();
    for(const l of possible){
      if(used.has(l.id))continue;
      if(selected.length&& !deferred && l.time_s-selected[0].time_s>2)break;
      if(push){
        if(components.has(l.type))continue;
        // Both native push commands are created with the same resolved selection.
        if(selected.length&&JSON.stringify([...l.units].sort((a,b)=>a-b))!==JSON.stringify([...selected[0].units].sort((a,b)=>a-b)))continue;
      }else if(action&&l.units.some(id=>(perUnit.get(id)||0)>=count))continue;
      selected.push(l);used.add(l.id);components.set(l.type,l);
      for(const id of l.units)perUnit.set(id,(perUnit.get(id)||0)+(build?l.build_count??1:1));
      if(push){if(selected.length>=expectedCount)break;}
      else if(build||!action&&!cancel)break;
      else if(cancel){if(expectedCount!=null&&selected.length>=expectedCount)break;}
      else if(ids?ids.every(id=>(perUnit.get(id)||0)>=count):selected.length>=count)break;
    }
    const enough=push?selected.length===expectedCount:build?(selected[0]?.build_count??1)>=Math.min(count,20):
      action?coverageVerifiable?ids.every(id=>(perUnit.get(id)||0)>=count):selected.length>=count:
      cancel?expectedCount==null||selected.length>=expectedCount:true;
    const enoughUnits=!coverageVerifiable||action||build||ids.every(id=>perUnit.has(id));
    const status=missing.length?'unobservable':!selected.length?'unmatched':enough&&enoughUnits?'matched':'partial';
    contexts.push({h,ids,count,possible,selected,perUnit,expectedCount,coverageVerifiable,status,missing,push,build,action});
  }
  // Connected candidate sets with insufficient evidence have no unique attribution. In
  // particular, an identical deferred request can coexist with a later retry in the CLI FIFO.
  const parents=contexts.map((_,i)=>i),owner=new Map();
  const find=i=>parents[i]===i?i:(parents[i]=find(parents[i]));
  for(let i=0;i<contexts.length;i++)for(const l of contexts[i].possible){
    if(owner.has(l.id))parents[find(i)]=find(owner.get(l.id));else owner.set(l.id,i);
  }
  const groups=new Map();
  contexts.forEach((c,i)=>{const k=find(i);if(!groups.has(k))groups.set(k,[]);groups.get(k).push(c);});
  for(const group of groups.values()){
    if(group.length<2||group.every(c=>c.status==='matched'))continue;
    if(!group.some(c=>c.selected.length))continue;
    for(const c of group){c.status='ambiguous';c.selected=[];c.perUnit.clear();c.competing=group.filter(x=>x!==c).map(x=>x.h.line);}
  }
  return contexts.map(c=>{
    const {h,ids,selected,perUnit,status}=c,first=selected[0],last=selected.at(-1);
    const elapsed=first?Math.round((first.time_s-h.time)*1000)/1000:null;
    const notes=[];
    if(status==='unmatched')notes.push('时间窗内没有相符 Replay 记录；收包日志没有响应，不能判定拒绝原因');
    if(status==='unobservable')notes.push('JSONL 缺少 '+c.missing.join('/')+'；重新运行 evaluate/utils/replay.py');
    if(status==='ambiguous')notes.push('多个请求竞争同一组底层记录，证据不足以唯一分配；不计入延迟统计');
    if(status==='partial')notes.push('底层记录只覆盖部分显式单位或数量；可能已跳过死/失踪单位');
    if(!c.coverageVerifiable)notes.push('选择器/动态过滤的完整名单无法从 Replay 重建，单位覆盖率未核验');
    if(h.name==='rwx-game-order-cancel'&&h.args.all)notes.push('--all 的原队列数量未记录，不能验证取消总量');
    return {highLine:h.line,highTime:h.time,highClock:clock(h.time),timePrecision:h.timePrecision??'seconds',command:h.command,rule:RULES[h.name].low,highTeam:h.team,
      requestedUnits:ids?.length??null,coveredUnits:ids?ids.filter(id=>perUnit.has(id)).length:null,
      missingUnits:ids?ids.filter(id=>!perUnit.has(id)):[],coverageVerifiable:c.coverageVerifiable,
      expectedCount:c.expectedCount,matchedCount:selected.length,matchedOperations:[...perUnit.values()].reduce((a,b)=>a+b,0),
      operationsByUnit:Object.fromEntries(perUnit),status,
      firstTime:first?.time_s??null,lastTime:last?.time_s??null,firstTick:first?.tick??null,lastTick:last?.tick??null,
      firstClock:first?clock(first.time_s):null,lastClock:last?clock(last.time_s):null,
      delay:elapsed,delayMin:first?Math.max(0,Math.round((elapsed-(h.timeUncertainty??1))*1000)/1000):null,
      delayMax:elapsed,span:first?Math.round((last.time_s-first.time_s)*1000)/1000:null,
      lowLines:selected.map(l=>l.line),lowUnits:selected.map(l=>l.units),
      candidateLowLines:status==='ambiguous'?c.possible.map(l=>l.line):[],
      candidateTimes:status==='ambiguous'?c.possible.map(l=>l.time_s):[],
      competingHighLines:c.competing||[],note:notes.join('；')};
  });
}
function clock(seconds){
  const ms=Math.round(seconds*1000);
  return `${String(Math.floor(ms/60000)).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}.${String(ms%1000).padStart(3,'0')}`;
}
module.exports={RULES,INDIRECT_RULES,SETTINGS,isIndirect,matchAll,matchLow,buildMatches,snapPoint,requestedIds,clock};
