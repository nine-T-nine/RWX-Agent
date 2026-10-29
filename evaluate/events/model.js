'use strict';
const identity = unit => `${unit.id}:${unit.life??0}`;
const distance = (a,b) => Math.hypot(a.x-b.x,a.y-b.y);
const contains = (circles,p,radius) => p && Number.isFinite(p.x) && circles.some(c=>distance(c,p)<=radius);
const intersects = (left,right,radius) => left.some(a=>right.some(b=>distance(a,b)<=2*radius));
const stamp = value => {
  if(value==null)return 'unknown';
  const ms=Math.max(0,Math.round(value));
  return `${String(Math.floor(ms/3600000)).padStart(2,'0')}:${String(Math.floor(ms/60000)%60).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}.${String(ms%1000).padStart(3,'0')}`;
};
function location(points) {
  if(!points.length)return null;
  const center={x:points.reduce((s,p)=>s+p.x,0)/points.length,y:points.reduce((s,p)=>s+p.y,0)/points.length};
  return {...center,r:points.reduce((s,p)=>s+distance(center,p),0)/points.length};
}
function composition(units) {
  const counts={};
  for(const unit of new Map(units.map(u=>[identity(u),u])).values())counts[unit.type]=(counts[unit.type]||0)+1;
  return counts;
}
function directB(command) {
  if(command.source!=='agent')return false;
  if(command.name==='rwx-game-order-attack-mode' && /--default\s+true/.test(command.raw||'')) return false;
  return /^rwx-game-order-/.test(command.name)||['rwx-game-push','rwx-game-cancel'].includes(command.name);
}
function operation(command) {
  return (command.operation || command.name?.replace(/^rwx-game-order-/,'') || '').replace(/-([a-z])/g,(_,c)=>c.toUpperCase());
}
class CommandMatcher {
  constructor(commands,config) {
    this.commands=commands.filter(directB).sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
    this.uncertainty=Math.max(0,...this.commands.map(c=>c.timeUncertaintyMs||0));
    this.config=config;
  }
  between(start,end,includeStart=false) {
    // Binary search keeps final gap checks independent of the number of previous commands.
    let lo=0,hi=this.commands.length;
    while(lo<hi){const mid=(lo+hi)>>1;if(this.commands[mid].timeMs<start-this.uncertainty)lo=mid+1;else hi=mid;}
    const rows=[];
    for(let i=lo;i<this.commands.length&&this.commands[i].timeMs<end;i++){
      const c=this.commands[i],right=c.timeMs+(c.timeUncertaintyMs||0);
      if(includeStart?right>=start:right>start)rows.push(c);
    }
    return rows;
  }
  relation(command,circles,victims,attackers) {
    if(!directB(command))return null;
    const op=operation(command),ids=command.units||[],target=command.targetId;
    if(op==='attack'&&attackers.has(target))return 'attack';
    if(['repair','guard','assist'].includes(op)&&victims.has(target))return op;
    if(['move','attackMove'].includes(op)&&ids.some(id=>victims.has(id)))return op==='move'?'retreat':'attackMove';
    if(['move','attackMove','push'].includes(op)&&contains(circles,command,this.config.X)
        &&command.controlled?.some(u=>u.canAttack))return op==='move'?'move_in':'attackMove';
    return null;
  }
  response(event,command,kind) {
    if(command.timeMs+(command.timeUncertaintyMs||0)<event.t_start || !kind)return;
    if(event.t_first_B!=null && command.timeMs>event.t_first_B+this.config.T_REACTION_TAIL)return;
    if(event.commands.some(c=>c.id===command.id))return;
    event.t_first_B??=Math.max(command.timeMs,event.t_start);
    if(command.timeUncertaintyMs)event.t_first_B_isLowerBound=true;
    const applied=(command.applications||[]).filter(a=>a.applied===true&&a.timeMs>=event.t_start);
    if(applied.length)event.t_first_applied=Math.min(event.t_first_applied??Infinity,...applied.map(a=>a.timeMs));
    event.commands.push({id:command.id,timeMs:command.timeMs,kind,operation:operation(command),
      timeUncertaintyMs:command.timeUncertaintyMs||0,
      accepted:command.accepted??null,applied:applied.length>0,applications:command.applications||[],
      source:command.source,units:command.units||[],targetId:command.targetId??null,raw:command.raw});
  }
}
class EconomyRecorder {
  constructor(period=1000){this.period=period;this.next=0;this.entries=[];this.complete=true;}
  sample(row) {
    if(!Number.isInteger(row.sampleMs)||row.sampleMs%this.period||row.sampleMs<this.next)throw Error('Invalid economy sampling order');
    if(row.sampleMs!==this.next)this.complete=false;
    this.next=row.sampleMs+this.period;
    if(!Number.isFinite(row.funds)||!Number.isFinite(row.income))throw Error('Invalid economy value');
    this.entries.push(`${row.funds}(${row.income>=0?'+':''}${row.income})`);
  }
  text(){const lines=[];for(let i=0;i<this.entries.length;i+=60)lines.push(this.entries.slice(i,i+60).join(','));return lines.length?lines.join('\n')+'\n':'';}
}
module.exports={identity,distance,contains,intersects,stamp,location,composition,directB,operation,CommandMatcher,EconomyRecorder};
