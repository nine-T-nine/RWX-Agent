'use strict';
const {identity,distance,contains,intersects,location,composition,directB,operation,argsObject,staleRefState,staleCommandRefs,commandTargets}=require('./model');
const {collectFailures}=require('./failures');
const {agentState,positionalQuery}=require('./commands');
const {endpointFeasibility}=require('./feasibility');

function hpEvidence(rows){
  const losses=new Map();let complete=true;
  for(const row of rows){
    const unit=row.victim;
    if(unit?.side!=='self')continue;
    if(!Number.isFinite(row.hpLoss)||row.hpLoss<0||!Number.isFinite(unit.maxHp)||unit.maxHp<=0){complete=false;continue;}
    const key=identity(unit),loss=losses.get(key)||{id:unit.id,life:unit.life??0,type:unit.type,building:unit.building===true,maxHp:unit.maxHp,hpLoss:0};
    loss.hpLoss+=row.hpLoss;losses.set(key,loss);
  }
  return {hpLosses:[...losses.values()],impactComplete:complete&&rows.length>0};
}
function effectiveResponse(session,event,end){
  const times=(event.scoreResponses||event.commands||[]).flatMap(entry=>{
    const command=session.commands.find(c=>c.id===entry.id)||entry;
    return (command.applications||[]).filter(a=>a.applied===true&&a.timeMs>=Math.max(event.t_start,entry.timeMs)
      &&(a.timeMs<end||a.timeMs===end&&(end<session.now||a.seq!=null&&a.seq<=session.matcher.nowSeq))).map(a=>a.timeMs);
  });
  return times.length?Math.min(...times):null;
}

/**
 * Diagnostic split of the global B set for one object. The verdict still uses B_count (any active
 * direct B explains the gap); these counts only show whether that B actually named the object.
 */
function bBreakdown(commands,unitId) {
  const counts={related:0,unknown:0,unrelated:0};
  for(const command of commands)counts[commandTargets(command,unitId)]++;
  return {B_related:counts.related,B_unknown:counts.unknown,B_unrelated:counts.unrelated};
}

class Detector {
  constructor(session){this.s=session;this.c=session.config;this.type=this.constructor.name.replace('Detector','').replace(/([a-z])([A-Z])/g,'$1_$2').toUpperCase();}
  diagnose(reason){this.s.diagnose(this.type,reason);}
  event(type,start,extra={}) {return {id:this.s.nextId(type),type,t_start:start,t_confirm:null,t_end:null,
    t_first_B:null,t_first_applied:null,commands:[],status:'DETECTED',observability:'unknown',...extra};}
  emit(event,end) {
    this.diagnose('emitted_'+event.status);
    event.t_end=end;
    if(event.t_first_B!=null)event.reactionMs=event.t_first_B-event.t_start;
    this.s.emit(event);
  }
}
class AmbushDetector extends Detector {
  constructor(s){super(s);this.regions=[];this.lastDamage=new Map();}
  circles(region){return [...new Map([...region.pairs.values()].flatMap(p=>[p.attacker,p.victim]).map(u=>[identity(u),this.s.units.get(identity(u))||u])).values()];}
  damage(row) {
    this.diagnose('damage_input');
    const a=row.attacker,v=row.victim;
    if(!v)return;
    this.lastDamage.set(identity(v),row.timeMs);
    for(const r of this.regions)for(const [key,p]of r.pairs)if(identity(p.attacker)===identity(v))r.pairs.delete(key);
    const rejection=!a||a.side!=='enemy'||v.side!=='self'?'not_enemy_to_self':!v.idle?'victim_not_idle'
      :a.excluded||v.excluded?'excluded_unit':row.timeMs-(this.lastDamage.get(identity(a))??-Infinity)<this.c.T_INACTIVE?'counterfire':null;
    if(rejection){this.diagnose(rejection);return;}
    const key=identity(a)+'>'+identity(v);
    let region=this.regions.find(r=>r.pairs.has(key));
    if(!region){
      region={event:this.event('AMBUSH',row.timeMs),pairs:new Map(),history:new Map(),losses:new Map(),damage:new Map()};
      this.regions.push(region);
      this.diagnose('candidate_created');
    }
    const old=region.pairs.get(key);
    region.pairs.set(key,{attacker:a,victim:v,start:old&&old.start!=null&&row.timeMs-old.last<=this.c.T_INACTIVE?old.start:row.timeMs,last:row.timeMs,nonIdleSince:null});
    region.history.set(identity(a),a);region.history.set(identity(v),v);
    region.damage.set(row.seq,row);
    // Stable region identity survives a later split; it is never counted again as two events.
    let changed=true;
    while(changed){changed=false;for(const other of [...this.regions]){
      if(other===region||!intersects(this.circles(region),this.circles(other),this.c.X))continue;
      const keep=other.event.t_start<region.event.t_start?other:region,drop=keep===region?other:region;
      for(const[k,p]of drop.pairs)keep.pairs.set(k,p);
      for(const[k,u]of drop.history)keep.history.set(k,u);
      for(const[k,u]of drop.losses)keep.losses.set(k,u);
      for(const[k,d]of drop.damage)keep.damage.set(k,d);
      keep.event.t_start=Math.min(keep.event.t_start,drop.event.t_start);
      keep.event.t_confirm=keep.event.t_confirm==null?drop.event.t_confirm:drop.event.t_confirm==null?keep.event.t_confirm:Math.min(keep.event.t_confirm,drop.event.t_confirm);
      for(const command of drop.event.commands)this.s.matcher.response(keep.event,command,command.kind);
      keep.event.scoreResponses=[...(keep.event.scoreResponses||[]),...(drop.event.scoreResponses||[])];
      this.regions.splice(this.regions.indexOf(drop),1);region=keep;changed=true;break;
    }}
  }
  advance(now) {
    for(const region of [...this.regions]){
      for(const[key,p]of region.pairs){
        const a=this.s.units.get(identity(p.attacker))||p.attacker,v=this.s.units.get(identity(p.victim))||p.victim;
        if(v.alive===false)region.losses.set(identity(v),v);
        if(!v.idle){p.nonIdleSince??=now;p.start=null;}else p.nonIdleSince=null;
        if(a.alive===false||v.alive===false||now-p.last>this.c.T_INACTIVE
            ||(p.nonIdleSince!=null&&now-p.nonIdleSince>=this.c.T_INACTIVE))region.pairs.delete(key);
        else if(v.idle&&p.start!=null&&p.last-p.start>=this.c.T_AMBUSH)region.event.t_confirm??=p.start+this.c.T_AMBUSH;
      }
      if(!region.pairs.size)this.close(region,now);
    }
  }
  command(command) {
    for(const r of this.regions){
      const pairs=[...r.pairs.values()],kind=this.s.matcher.relation(command,this.circles(r),new Set(pairs.map(p=>p.victim.id)),new Set(pairs.map(p=>p.attacker.id)));
      if(kind)(r.event.scoreResponses??=[]).push({id:command.id,timeMs:command.timeMs,kind});
      this.s.matcher.response(r.event,command,kind);
    }
  }
  death(row){for(const region of this.regions)if(region.history.has(identity(row.unit)))region.losses.set(identity(row.unit),row.unit);}
  close(r,now,truncated=false) {
    this.regions.splice(this.regions.indexOf(r),1);
    if(r.event.t_confirm==null){this.diagnose('closed_before_confirmation');return;}
    const us=[...r.history.values()].filter(u=>u.side==='self'),enemies=[...r.history.values()].filter(u=>u.side==='enemy');
    // `losses` is the union of both sides; the two extra fields keep kills and own losses apart
    // without changing the shape existing readers use.
    const lost=[...r.losses.values()];
    // A sustained attack on unarmed structures is a home attack, not an ambush of a unit: it is
    // reported with the same evidence but a distinct kind so it can be weighted separately.
    const kind=us.length&&us.every(u=>u.building&&!u.canAttack)?'BASE':'UNITS';
    const agent=agentState(this.s.commands,r.event.t_start,now);
    Object.assign(r.event,{kind,agentState:agent.state,agentStateEvidence:agent.evidence,
      ...hpEvidence([...r.damage.values()]),effectiveResponseMs:effectiveResponse(this.s,r.event,now),
      location:location(this.circles(r).length?this.circles(r):[...r.history.values()]),
      self:composition(us),enemy:composition(enemies),units:[...r.history.keys()],losses:composition(lost),
      lossesSelf:composition(lost.filter(u=>u.side==='self')),lossesEnemy:composition(lost.filter(u=>u.side==='enemy')),
      status:r.event.t_first_B==null?(this.s.covers(r.event.t_start,now)?'UNANSWERED':'UNCERTAIN'):'DETECTED',truncated,
      observability:enemies.some(u=>u.visible)?'visible_threat':'damage_notice_only'});
    r.event.geometry={radius:this.c.X,circles:(this.circles(r).length?this.circles(r):[...r.history.values()]).map(u=>({x:u.x,y:u.y,r:this.c.X})),displayRadius:'mean_member_distance'};
    this.emit(r.event,now);
  }
  finish(now){this.s.diagnose(this.type,'unclosed_candidates_'+this.regions.length);for(const r of [...this.regions])this.close(r,now,true);}
}

class BuilderGapDetector extends Detector {
  constructor(s){super(s);this.states=new Map();}
  unit(row) {
    const u=row.unit;if(!u.builder||u.side!=='self'||u.excluded)return;
    const key=identity(u),old=this.states.get(key),task=u.task;
    let gap=old?.gap;
    if(!u.alive){this.states.delete(key);return;}
    if(old?.unit.task&&['build','repair'].includes(old.unit.task.type)&&!task&&u.idle)
      {gap={start:row.timeMs,previous:old.unit.task,snapshot:u,funds:row.funds};this.diagnose('candidate_created');}
    if(gap&&task&&['build','repair'].includes(task.type)){
      const duration=row.timeMs-gap.start;
      if(duration>this.c.T_GAP_MIN&&duration<this.c.T_GAP_MAX){
        const commands=this.s.matcher.between(gap.start,row.timeMs,true);
        const action=Object.values(gap.snapshot.actions||{}).find(a=>a.kind==='build'&&a.type===task.buildType);
        let feasible=null,reason='historical_build_placement_unavailable';
        if(task.type==='repair'){
          feasible=(gap.snapshot.repairable||[]).includes(task.targetId);
          reason=feasible?'repair_target_valid_at_start':'repair_target_not_proven_valid_at_start';
        }else if(!action||!action.available||!action.affordable||gap.funds<action.cost*(task.count||1)){
          feasible=false;reason='build_not_available_at_start';
        }else if(gap.snapshot.placementProofs?.some(p=>p.type===task.buildType&&p.x===task.x&&p.y===task.y&&p.valid)){
          feasible=true;reason='historical_placement_verified';
        }
        const coverage=this.s.covers(gap.start,row.timeMs);
        const event=this.event('BUILDER_GAP',gap.start,{t_confirm:row.timeMs,unit:u,previous:gap.previous,next:task,
          gapMs:duration,funds:gap.funds,cost:action?.cost??0,B_count:commands.length,B_ids:commands.map(c=>c.id),
          ...bBreakdown(commands,u.id),
          feasible,reason:!coverage?'CLI_command_coverage_unavailable':reason,status:!coverage||feasible==null?'UNCERTAIN':!feasible?'EXPLAINED':commands.length?'EXPLAINED':'UNANSWERED',
          flags:commands.length?['BUILDER_INACTIVE']:coverage?['NO_B_GAP']:[],strict:coverage&&feasible===true&&!commands.length});
        this.emit(event,row.timeMs);
      }else this.diagnose('duration_outside_strict_window');
      gap=null;
    }else if(gap&&(!u.idle||row.timeMs-gap.start>=this.c.T_GAP_MAX)){this.diagnose(!u.idle?'intervening_task':'expired_at_upper_bound');gap=null;}
    this.states.set(key,{unit:u,gap});
  }
  finish(){for(const state of this.states.values())if(state.gap)this.diagnose('unclosed_candidate');}
}

/**
 * One place for the PRODUCTION_GAP verdict so `reason` and `status` can never disagree and a null
 * (not provable) value can never be read as false. Order matters:
 * coverage, provable t0 action, real completion, t0 cannot order, order origin, automation,
 * shared budget, historical feasibility, global B, then a strict unanswered gap.
 */
function productionGapVerdict({coverage,cannotOrder,completionProven,uncertainOrigin,proactive,arranged,sharedFunds,feasible,commands}) {
  if(!coverage)return {status:'UNCERTAIN',reason:'CLI_command_coverage_unavailable'};
  if(cannotOrder==null)return {status:'UNCERTAIN',reason:'historical_action_unavailable'};
  if(completionProven===false)return {status:'UNCERTAIN',reason:'actual_production_completion_unproven'};
  if(cannotOrder===true)return {status:'EXPLAINED',reason:'production_not_feasible_at_start'};
  if(uncertainOrigin)return {status:'UNCERTAIN',reason:'production_order_origin_unavailable'};
  if(!proactive||arranged)return {status:'EXPLAINED',reason:'automation_or_prearranged_order'};
  if(sharedFunds)return {status:'UNCERTAIN',reason:'multi_factory_shared_funds_not_reconstructed'};
  if(feasible==null)return {status:'UNCERTAIN',reason:'historical_action_unavailable'};
  if(feasible===false)return {status:'EXPLAINED',reason:'production_not_feasible_at_start'};
  if(commands)return {status:'EXPLAINED',reason:'global_B_during_gap'};
  return {status:'UNANSWERED',reason:'verified'};
}

class ProductionGapDetector extends Detector {
  constructor(s){super(s);this.states=new Map();}
  idle(u){return u.alive&&u.complete!==false&&!u.task&&!u.queue?.length&&(!u.mobile||u.idle);}
  lifecycle(row){
    const key=`${row.unitId}:${row.life??0}`,old=this.states.get(key)||{unit:null,produced:false,gap:null};
    const unit=this.s.units.get(key);if(!unit||unit.side!=='self'||!unit.factory)return;
    if(row.phase==='produced'){old.completion=row.timeMs;old.produced=true;this.diagnose('actual_production_completed');}
    else if(row.phase?.startsWith('upgrade')){old.produced=false;old.completion=null;old.gap=null;this.diagnose('upgrade_cancelled_candidate');}
    this.states.set(key,old);
  }
  unit(row) {
    const u=row.unit;if(!u.factory||u.side!=='self'||u.excluded)return;
    const key=identity(u),old=this.states.get(key)||{unit:null,produced:false,gap:null};
    if(!u.alive){this.states.delete(key);return;}
    const upgrade=u.queue?.some(q=>q.kind!=='produce');
    if(upgrade){old.produced=false;old.completion=null;old.gap=null;}
    if(u.queue?.some(q=>q.kind==='produce'))old.produced=true;
    if(this.idle(u)&&old.unit?.queue?.some(q=>q.kind==='produce')&&old.produced){
      old.gap={start:row.timeMs,snapshot:u,funds:row.funds,completionProven:old.completion===row.timeMs};
      this.diagnose(old.gap.completionProven?'candidate_after_completion':'candidate_completion_unproven');
    }
    if(!this.idle(u)||old.gap&&row.timeMs-old.gap.start>=this.c.T_GAP_MAX){
      if(old.gap)this.diagnose(!this.idle(u)?'activity_cancelled_candidate':'expired_at_upper_bound');old.gap=null;
    }
    old.unit=u;this.states.set(key,old);
  }
  native(row) {
    if(row.phase!=='after')return;
    for(const result of row.results||[]){
      const old=this.states.get(`${result.unitId}:${result.life??0}`);
      if(old&&row.operation==='action'&&result.applied===true&&!result.queueAdded?.length&&old.unit?.queue?.length){
        old.produced=false;old.completion=null;old.gap=null;this.diagnose('queue_changed_by_command');
      }
      if(!old?.gap)continue;
      const gap=old.gap;old.gap=null;
      if(!result.queueAdded?.length)continue;
      if(result.queueAdded.some(a=>a.kind!=='produce'))continue;
      const duration=row.timeMs-gap.start;
      if(duration<=this.c.T_GAP_MIN||duration>=this.c.T_GAP_MAX){this.diagnose('duration_outside_strict_window');continue;}
      const actionId=result.queueAdded[0].action,action=gap.snapshot.actions?.[actionId];
      const count=result.batchCount??result.queueAdded.reduce((n,q)=>n+q.count,0);
      const commands=this.s.matcher.between(gap.start,row.timeMs);
      let feasible=action?!!(action.kind==='produce'&&action.available&&action.affordable
        &&gap.funds>=action.cost*count&&(gap.snapshot.capRemaining??Infinity)>=count):null;
      // Being unable to queue even one unit is a fact about the gap start, not about whoever ordered later,
      // so it explains the idle time without knowing the order origin.
      const cannotOrder=action?!(
        action.kind==='produce'&&action.available&&gap.funds>=action.cost
        &&(gap.snapshot.capRemaining??Infinity)>=1):null;
      const coverage=this.s.covers(gap.start,row.timeMs);
      const linked=this.s.matcher.commands.find(c=>c.id===row.commandId||(c.applications||[]).some(a=>a.nativeId===row.nativeId));
      const proactive=row.source==='agent'||linked!=null;
      const sharedFunds=(linked?.units.length??row.results.length)>1;
      if(sharedFunds)feasible=null;
      const arranged=linked&&linked.timeMs<gap.start;
      const uncertainOrigin=!proactive&&!['keep','pending','engine'].includes(row.source);
      const verdict=productionGapVerdict({coverage,cannotOrder,completionProven:gap.completionProven,
        uncertainOrigin,proactive,arranged,sharedFunds,feasible,commands:commands.length});
      const event=this.event('PRODUCTION_GAP',gap.start,{t_confirm:row.timeMs,gapMs:duration,unit:old.unit,
        next:result.queueAdded,batchCount:count,funds:gap.funds,cost:action? action.cost*count:null,
        B_count:commands.length,B_ids:commands.map(c=>c.id),feasible,cannotOrder,
        ...bBreakdown(commands,old.unit.id),
        t_first_B:linked?.timeMs??null,t_first_applied:row.timeMs,reason:verdict.reason,
        strict:coverage&&proactive&&!arranged&&feasible===true&&!commands.length&&gap.completionProven!==false,
        status:verdict.status});
      event.completionProven=gap.completionProven;
      this.diagnose(event.reason);
      this.emit(event,row.timeMs);
    }
  }
  finish(){for(const state of this.states.values())if(state.gap)this.diagnose('unclosed_candidate');}
}

class FeedDetector extends Detector {
  constructor(s){super(s);this.regions=[];this.seen=new Set();}
  death(row) {
    this.diagnose('death_input');
    const u=row.unit,key=identity(u),killer=row.killer;
    if(this.seen.has(key)){this.diagnose('duplicate_death');return;}this.seen.add(key);
    if(u.side!=='self'||u.excluded||u.task?.type==='attackMove'||!killer||killer.side!=='enemy'||killer.excluded){this.diagnose('ineligible_loss_or_no_killer_proof');return;}
    const near=(row.nearby||[]).filter(v=>v.alive&&identity(v)!==key&&!v.excluded);
    if(!near.some(v=>v.side==='enemy')||near.some(v=>['self','ally'].includes(v.side))){this.diagnose('spatial_condition_not_met');return;}
    let r={id:this.s.nextId('REGION'),circles:[{x:u.x,y:u.y,anchorId:u.id}],losses:[{unit:u,timeMs:row.timeMs,seq:row.seq}],
      commands:[],lastHostile:row.timeMs,created:row.timeMs,segments:[]};
    this.regions.push(r);
    this.diagnose('candidate_region_created');
    let changed=true;
    while(changed){changed=false;for(const other of [...this.regions]){
      if(other===r||!intersects(r.circles,other.circles,this.c.X))continue;
      r.circles.push(...other.circles);r.losses.push(...other.losses);r.commands.push(...other.commands);
      r.segments.push(...other.segments);r.created=Math.min(r.created,other.created);
      r.lastHostile=Math.max(r.lastHostile,other.lastHostile);
      this.regions.splice(this.regions.indexOf(other),1);this.diagnose('regions_merged');changed=true;break;
    }}
    r.losses=[...new Map(r.losses.map(l=>[identity(l.unit),l])).values()].sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
  }
  command(command) {
    if(!directB(command))return;
    for(const r of this.regions){
      const attackers=new Set(r.circles.flatMap(c=>(this.s.regionNeighbors.get(c.anchorId)||[]).filter(u=>u.side==='enemy').map(u=>u.id)));
      const controlledInside=command.controlled?.filter(u=>contains(r.circles,u,this.c.X))||[];
      let kind=this.s.matcher.relation(command,r.circles,new Set(controlledInside.map(u=>u.id)),attackers);
      const op=operation(command);
      if(op==='move'&&contains(r.circles,command,this.c.X))kind='move_in';
      if(op==='move'&&controlledInside.length&&!contains(r.circles,command,this.c.X))kind='retreat';
      if(op==='guard'&&contains(r.circles,command.targetUnit,this.c.X))kind='guard';
      if(kind&&!r.commands.some(c=>c.id===command.id))r.commands.push({...command,kind});
    }
  }
  advance(now) {
    for(const r of [...this.regions]){
      const neighbors=r.circles.flatMap(c=>this.s.regionNeighbors.get(c.anchorId)||[]);
      const enemy=neighbors.some(u=>u.alive&&u.side==='enemy'&&!u.excluded);
      const friends=neighbors.some(u=>u.alive&&['self','ally'].includes(u.side)&&!u.excluded);
      if(enemy)r.lastHostile=now;
      if(now-r.created>=this.c.T_REGION_HOLD&&(friends||!enemy&&now-r.lastHostile>=this.c.T_REGION_HOLD))this.close(r,now);
    }
  }
  close(r,now,truncated=false) {
    this.regions.splice(this.regions.indexOf(r),1);
    const losses=[...r.losses].sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
    if(losses.length<3){this.diagnose('closed_before_third_loss');return;}
    // Every loss takes part in the 5s chaining, not only the ones after the second: losses at
    // 100s/108s/112s are two segments, and neither of them is a confirmed repeat.
    const segments=[];
    for(const loss of losses){const last=segments.at(-1);
      if(last&&loss.timeMs-last.at(-1).timeMs<=this.c.T_FEED_MERGE)last.push(loss);else segments.push([loss]);}
    for(const segment of segments){
      // A segment is settled on its own: its third loss confirms it, and its window starts at its
      // first loss, so an earlier segment's commands can never answer a later one.
      if(segment.length<3){this.diagnose('segment_before_third_loss');continue;}
      const start=segment[0].timeMs,end=segment.at(-1).timeMs,repeated=segment.slice(2),confirm=repeated[0].timeMs;
      const commands=r.commands.filter(c=>c.timeMs>=start&&c.timeMs<=end);
      const assault=commands.some(c=>['attack','attackMove','guard'].includes(c.kind));
      const exposure=commands.some(c=>c.kind==='move_in'&&c.timeMs>=segment[1].timeMs&&segment.some(l=>l.timeMs>c.timeMs));
      const covered=this.s.covers(start,end);
      const event=this.event('FEED',start,{region_id:r.id,t_confirm:confirm,
        t_first_B:commands[0]?.timeMs??null,
        t_first_applied:commands.flatMap(c=>c.applications||[]).filter(a=>a.applied===true&&a.timeMs<=end).reduce((t,a)=>Math.min(t??Infinity,a.timeMs),null),
        t_first_repeat:confirm,region_end:now,location:location(r.circles),state:'REPEATED',
        losses:composition(segment.map(l=>l.unit)),extra:composition(repeated.map(l=>l.unit)),
        lossEvidence:segment,commands:commands.map(c=>({id:c.id,timeMs:c.timeMs,kind:c.kind,raw:c.raw})),
        flags:[...(!assault&&covered?['NO_ASSAULT_RESPONSE']:[]),...(exposure?['REPEATED_EXPOSURE']:[])],
        status:!covered?'UNCERTAIN':assault||commands.some(c=>c.kind==='retreat')?'EXPLAINED':'DETECTED',truncated,
        observability:segment.some(l=>l.unit.visible)?'own_loss_notice':'unknown',
        interpretation:'Repeated losses are evidence; tactical intent is not inferred.'});
      event.geometry={radius:this.c.X,circles:r.circles.map(c=>({x:c.x,y:c.y,r:this.c.X})),displayRadius:'mean_member_distance'};
      this.emit(event,end);
    }
  }
  finish(now){for(const r of [...this.regions])this.close(r,now,true);}
}

class StaleCommandDetector extends Detector {
  constructor(s){super(s);this.objects=new Map();this.delayed=[];}
  command(command) {
    if(!directB(command))return;
    this.diagnose('direct_command_input');
    const counted=new Set();
    if(['attackMode','rally'].includes(operation(command)))return;
    for(const ref of command.refs||[]){
      const state=staleRefState(command,ref);
      if(!state)continue;
      const key=`${ref.id}:${ref.life??0}`;if(counted.has(key))continue;counted.add(key);
      if(state==='uncertain'){
        this.delayed.push(this.event('STALE_COMMAND',command.timeMs,{unitId:ref.id,command_ids:[command.id],
          status:'UNCERTAIN',strict:false,reason:ref.eligibleKnown===false?'unknown_entity_lifecycle':'invalid_at_check_but_not_proven_stale_at_receipt',ref}));continue;
      }
      const entry=this.objects.get(key)||this.event('STALE_COMMAND',command.timeMs,{unitId:ref.id,life:ref.life??0,
        count:0,last_trigger_time:command.timeMs,roles:[],reasons:{},command_ids:[],triggers:[],strict:true});
      entry.count++;entry.last_trigger_time=command.timeMs;
      if(!entry.roles.includes(ref.role))entry.roles.push(ref.role);
      entry.reasons[ref.status]=(entry.reasons[ref.status]||0)+1;entry.command_ids.push(command.id);
      entry.triggers.push({commandId:command.id,timeMs:command.timeMs,ref});this.objects.set(key,entry);
    }
  }
  finish(now){for(const event of [...this.objects.values(),...this.delayed])this.emit(event,event.last_trigger_time??now);}
}
/**
 * Counts requests the engine explicitly rejected. This is evidence about the tool surface and about
 * how the Agent spends its turns, not a score: a rejection can be a deliberate probe. Query failures
 * stay in diagnostics, and a rejection whose object is already a STALE_COMMAND is marked as covered
 * so the same mistake is never counted twice.
 */
class CommandFailureDetector extends Detector {
  constructor(s){
    super(s);
    this.stats={total:0,operation:0,configuration:0,query:0,other:0,byCategory:{},coveredByStaleCommand:0,additional:0,queries:[]};
    s.commandFailures=this.stats;
  }
  finish(now) {
    const rows=collectFailures(this.s.commands,{retryWindowMs:this.c.T_RETRY_WINDOW});
    // The filtered view keeps the original counts untouched and only answers "how many rejections are
    // left once stale-covered, repeated and recovered attempts are removed".
    const filtered=this.stats.filtered={retryWindowMs:this.c.T_RETRY_WINDOW,count:0,
      excluded:{staleCommand:0,duplicateSignature:0,recoveredRetry:0},remaining:[]};
    // `actionable` is the strict subset: a rejected request only counts when nothing explains it and
    // the Agent could have known. Object staleness belongs to STALE_COMMAND, timing is not scored, and
    // a blocked placement is left for review because the blocker is only named in the reply text.
    const actionable=this.stats.actionable={retryWindowMs:this.c.T_RETRY_WINDOW,count:0,
      excluded:{staleCommand:0,duplicateSignature:0,recoveredRetry:0,timing:0,objectContinued:0,unprovenAvoidability:0},
      remaining:[]};
    const UNPROVEN=['PLACEMENT_BLOCKED','STALE_OBJECT','OTHER'];
    for(const row of rows){
      this.stats.total++;
      this.stats[row.kind]=(this.stats[row.kind]||0)+1;
      if(row.kind==='query'){
        this.stats.queries.push({commandId:row.commandId,timeMs:row.timeMs,name:row.name,category:row.category,reply:row.reply});
        this.diagnose('query_'+row.category.toLowerCase());continue;
      }
      if(row.kind==='other'){this.diagnose('unscored_'+row.category.toLowerCase());continue;}
      this.stats.byCategory[row.category]=(this.stats.byCategory[row.category]||0)+1;
      const covered=row.staleObjects.length>0;
      if(covered)this.stats.coveredByStaleCommand++;else this.stats.additional++;
      const basic=covered?'staleCommand':row.duplicate?'duplicateSignature':row.recovered?'recoveredRetry':null;
      if(basic)filtered.excluded[basic]++;else{filtered.count++;filtered.remaining.push({commandId:row.commandId,timeMs:row.timeMs,kind:row.kind,category:row.category,name:row.name});}
      // World-state rejections have no provable "could have known", so they go to review before the
      // object-continuation shortcut can retire them.
      const reason=basic??(row.category==='UNAVAILABLE_ACTION'?'timing'
        :UNPROVEN.includes(row.category)?'unprovenAvoidability':row.objectContinued?'objectContinued':null);
      if(reason)actionable.excluded[reason]++;
      else{actionable.count++;actionable.remaining.push({commandId:row.commandId,timeMs:row.timeMs,kind:row.kind,category:row.category,name:row.name});}
      this.emit(this.event('COMMAND_FAILURE',row.timeMs,{t_confirm:row.timeMs,commandId:row.commandId,name:row.name,
        kind:row.kind,category:row.category,args:row.args,raw:row.raw,reply:row.reply,accepted:false,
        staleObjects:row.staleObjects,coveredByStaleCommand:covered,
        duplicateSignature:row.duplicate===true,recoveredRetry:row.recovered===true,objectContinued:row.objectContinued===true,
        status:'DETECTED',observability:'command_reply',
        interpretation:'A rejected request is a fact; STALE_COMMAND already covers its stale objects, so the two are never counted twice.'}),row.timeMs);
    }
  }
}
/** Codes that mean the Agent's picture of the world was wrong, not that it typed something wrong. */
const STALE_INFO=new Set(['STALE_OBJECT','NOT_CONTROLLABLE','PLACEMENT_BLOCKED','UNAVAILABLE_ACTION']);

/**
 * D 空闲建造者 / E 空闲工厂.
 *
 * <p>One idle pair from the collector becomes one event. The collector already dropped periods
 * shorter than its floor and named the reason the idle ended, so this detector only judges
 * attribution: was there anything the asset could have done, was an automation rule responsible,
 * and was the Agent stalled or busy elsewhere?</p>
 *
 * <p>An automation rule (keep-set) is a full explanation: the engine refills the queue itself when
 * the action is available and affordable, so an idle factory with an active rule can only be
 * blocked by money or by the rule having been paused.</p>
 */
class IdleAssetDetector extends Detector {
  constructor(s){super(s);this.keep=new Map();this.endpoints=new Map();}
  native(row){
    if(row.phase!=='after')return;
    for(const result of row.results||[])if(result.queueAdded?.length){
      const linked=this.s.commands.find(c=>c.id===row.commandId);
      this.endpoints.set(`${result.unitId}:${result.life??0}`,{timeMs:row.timeMs,source:row.source,
        commandId:row.commandId,commandTimeMs:linked?.timeMs??null,batchCount:result.batchCount,
        shared:(row.results?.length??0)>1||(linked?.units?.length??0)>1});
    }
  }
  command(command){
    const name=String(command.name||'');
    if(name==='rwx-game-keep-set'){
      const unit=Number(argsObject(command).unit);
      if(Number.isInteger(unit))this.keep.set(unit,{timeMs:command.timeMs,action:argsObject(command).action??null});
    }else if(name==='rwx-game-keep-clear'&&argsObject(command).id==null){
      // Clearing every rule releases every factory. A single-rule clear cannot be mapped back to a
      // unit without the keep-list reply, so that unit keeps its flag: over-explaining is safe.
      this.keep.clear();
    }
  }
  idle(row){
    const roles=Array.isArray(row.roles)?row.roles:[];
    const factory=roles.includes('factory'),builder=roles.includes('builder');
    if(!factory&&!builder){this.diagnose('no_role');return;}
    const type=factory?'FACTORY_IDLE':'BUILDER_IDLE';
    const endpoint=this.endpoints.get(`${row.unitId}:${row.life??0}`);
    const native=endpoint?.timeMs===row.toMs?endpoint:null;
    // Native packets for one requested batch can be split. The command index already knows its
    // complete count; do not judge only the first packet that happened to close the idle pair.
    if(factory&&native?.batchCount&&row.closeQueue?.length===1)
      row={...row,closeQueue:row.closeQueue.map(q=>({...q,count:native.batchCount}))};
    const feasibility=native?.shared?{throughout:null,reason:'shared_endpoint_budget_unavailable'}:endpointFeasibility(row,factory);
    const ref=factory&&row.closeAction?{action:row.closeAction,...row.actions?.[row.closeAction],source:'endpoint'}
      :row.closeTask?{...row.closeTask,source:'endpoint'}:null;
    const durationMs=Number(row.durationMs??(Number(row.toMs)-Number(row.fromMs)));
    // The command log can end before the telemetry does (the Agent process exited, quota ran out).
    // Retain covered duration for diagnostics. The score still requires B inside command coverage.
    const coverageEnd=this.s.commandCoverage?.end??-Infinity;
    const coveredTo=Math.min(Number(row.toMs),coverageEnd);
    const coverage=coveredTo>=Number(row.fromMs)&&this.s.covers(row.fromMs,coveredTo);
    const judgedEnd=coverage?coveredTo:Number(row.toMs);
    const judgedMs=coverage?coveredTo-Number(row.fromMs):durationMs;
    const keepActive=this.keep.has(Number(row.unitId));
    const agent=agentState(this.s.commands,row.fromMs,judgedEnd);
    const affordableStart=ref?Number(row.startFunds)>=ref.cost&&Number(row.startCap)>=1:null;
    const affordableEnd=ref?Number(row.endFunds)>=ref.cost&&Number(row.startCap)>=1:null;
    const tier=judgedMs>=this.c.T_IDLE_LONG?'STALL':judgedMs>=this.c.T_IDLE_SHORT?'LONG':'SHORT';
    let status,reason;
    if(!coverage){status='UNCERTAIN';reason='CLI_command_coverage_unavailable';}
    else if(judgedEnd<Number(row.toMs)){status='UNCERTAIN';reason='endpoint_outside_command_coverage';}
    else if(keepActive){status='EXPLAINED';reason='automation_configured';}
    else if(native&&(['keep','pending','auto','engine'].includes(native.source)||native.commandTimeMs<row.fromMs&&native.commandTimeMs!=null)){
      status='EXPLAINED';reason='automation_or_prearranged_order';}
    else if(feasibility.throughout==null){status='UNCERTAIN';reason=feasibility.reason;}
    else if(feasibility.throughout===false){status='EXPLAINED';reason=feasibility.reason;}
    else {status='DETECTED';reason=agent.state==='SILENT'?'agent_silent':'agent_busy_elsewhere';}
    const scoreable=status==='DETECTED'&&feasibility.throughout===true&&(factory||this.c.idleScoreBuilders);
    const event=this.event(type,row.fromMs,{t_confirm:judgedEnd,
      unitId:Number(row.unitId),life:Number(row.life??0),unit:{id:Number(row.unitId),type:row.type},
      roles,durationMs:judgedMs,observedDurationMs:durationMs,closeReason:row.closeReason??null,
      closeAction:row.closeAction??null,tier,
      startFunds:row.startFunds??null,endFunds:row.endFunds??null,startCap:row.startCap??null,
      referenceAction:ref,affordableStart,affordableEnd,feasibility,endpointMs:row.toMs,
      agentState:agent.state,agentStateEvidence:agent.evidence,
      keepRule:keepActive?this.keep.get(Number(row.unitId)):null,
      status,reason,strict:false,scoreable,visibility:'HIGH',
      durationIsLowerBound:row.closeReason==='EVIDENCE_END'||judgedMs<durationMs});
    this.diagnose(reason);
    this.emit(event,judgedEnd);
  }
  finish(){ }
}

/**
 * C 抖动过期指令.
 *
 * <p>A single rejected request is noise: the Agent may be probing, or a parallel batch may reject
 * itself. A *burst* is the signal — several attempts inside one window, where an attempt is a batch
 * of receipts within {@code T_STALE_BATCH} milliseconds, because commands sent together can
 * invalidate each other and should not each count.</p>
 *
 * <p>Two sources feed the same burst: the engine's explicit rejection ({@code accepted=false}) and
 * a receipt-time stale reference, which can be skipped without an error reply. Only bursts that
 * contain at least one "the world was not what I thought" code are scoreable; pure argument typos
 * stay as review evidence.</p>
 */
class StaleBurstDetector extends Detector {
  constructor(s){super(s);this.rows=[];}
  command(command){
    if(!directB(command))return;
    for(const ref of staleCommandRefs(command))
      this.rows.push({timeMs:command.timeMs,commandId:command.id,name:command.name,kind:'operation',
        code:'STALE_OBJECT',source:'receipt_ref',objectId:ref.id,
        staleAgeMs:Number.isFinite(ref.deathTimeMs)?Math.max(0,command.timeMs-ref.deathTimeMs):null});
  }
  finish(now){
    for(const row of collectFailures(this.s.commands,{retryWindowMs:this.c.T_RETRY_WINDOW}))
      this.rows.push({timeMs:row.timeMs,commandId:row.commandId,name:row.name,
        code:row.code||row.category,source:'rejected',category:row.category,kind:row.kind});
    const byCommand=new Map();
    for(const row of this.rows){const prior=byCommand.get(row.commandId);
      if(!prior||!Number.isFinite(prior.staleAgeMs)&&Number.isFinite(row.staleAgeMs))byCommand.set(row.commandId,row);}
    const unique=[...byCommand.values()]
      .sort((a,b)=>a.timeMs-b.timeMs||String(a.commandId).localeCompare(String(b.commandId)));
    const bursts=[];let current=null;
    for(const row of unique){
      if(current&&row.timeMs-current.end<=this.c.T_STALE_BURST_WINDOW){
        current.rows.push(row);current.end=row.timeMs;
      }else{current={rows:[row],start:row.timeMs,end:row.timeMs};bursts.push(current);}
    }
    for(const burst of bursts){
      const batches=[];
      for(const row of burst.rows){
        const last=batches.at(-1);
        if(!last||row.timeMs-last.at(-1).timeMs>this.c.T_STALE_BATCH)batches.push([row]);
        else last.push(row);
      }
      if(batches.length<this.c.T_STALE_BURST_MIN){this.diagnose('below_burst_threshold');continue;}
      const codes={};for(const row of burst.rows)codes[row.code]=(codes[row.code]||0)+1;
      const staleInfo=Object.keys(codes).some(code=>STALE_INFO.has(code));
      const coverage=this.s.covers(burst.start,burst.end);
      const event=this.event('STALE_BURST',burst.start,{t_confirm:burst.end,
        count:burst.rows.length,attempts:batches.length,windowMs:burst.end-burst.start,codes,
        commands:burst.rows.map(row=>({commandId:row.commandId,timeMs:row.timeMs,name:row.name,
          code:row.code,source:row.source,kind:row.kind,staleAgeMs:row.staleAgeMs??null,staleInfo:STALE_INFO.has(row.code)})),
        status:!coverage?'UNCERTAIN':'DETECTED',
        reason:!coverage?'CLI_command_coverage_unavailable'
          :staleInfo?'stale_or_conflicting_commands':'invalid_commands_only',
        strict:false,scoreable:coverage&&staleInfo,visibility:'MEDIUM'});
      this.diagnose(event.reason);
      this.emit(event,burst.end);
    }
  }
}

/** Task kinds that mean the unit was fighting, not "parked somewhere it believed safe". */
const FIGHTING=new Set(['attackMove','guard','guardAt','patrol','attack']);
/** Orders that move people or builders to a place; an assault is deliberately not one of them. */
const SEND_ORDERS=Object.freeze({'rwx-game-order-move':'move','rwx-game-order-build':'build',
  'rwx-game-order-repair':'repair','rwx-game-order-rally':'rally'});

/**
 * B 绞肉机 / 危险区误判.
 *
 * <p>The event is an information failure, not a tactical one: our units died at P, the Agent never
 * obtained a report that covered that loss, and it then sent units or a builder to P and they were
 * hit. A human sees exactly this in a replay — "it just died there, why is it walking back in".</p>
 *
 * <p>Three facts must all hold: a danger point from our own losses ({@code T_GRINDER_RECENT}), a
 * send order after that loss, and a consequence — one of the sent units damaged or killed at P
 * inside {@code T_GRINDER_AFTER}. The awareness test only reads the Agent's own receipts: an
 * {@code rwx-game-events}/{@code state} query whose window covers the loss, a positional query over
 * P, or a live event socket. An assault or a guard is excluded, because those are deliberate.</p>
 */
class GrinderDetector extends Detector {
  constructor(s){super(s);this.deaths=[];this.pending=[];this.stream=[];this.positions=new Map();}
  eventstream(row){
    const last=this.stream.at(-1);
    if(row.connected){if(!last||last.to!=null)this.stream.push({from:row.timeMs,to:null});}
    else if(last&&last.to==null)last.to=row.timeMs;
  }
  /** Last observed position of our own units, used to tell "already there" from "still marching". */
  unit(row){
    const unit=row.unit;
    if(!unit||unit.side!=='self')return;
    this.positions.set(Number(unit.id),{x:unit.x,y:unit.y,timeMs:row.timeMs});
    for(const candidate of this.pending){
      if(candidate.consequence||candidate.arrivals.has(Number(unit.id)))continue;
      if(candidate.operation!=='rally'&&!candidate.units.has(Number(unit.id)))continue;
      if(distance(unit,candidate.point)<=this.c.X)
        candidate.arrivals.set(Number(unit.id),row.timeMs);
    }
  }
  /** Latest own loss within the radius and the recency window, or null. */
  dangerAt(x,y,now){
    let best=null;
    for(const death of this.deaths){
      if(now-death.timeMs>this.c.T_GRINDER_RECENT)continue;
      if(distance(death,{x,y})>this.c.X)continue;
      if(!best||death.timeMs>best.timeMs)best=death;
    }
    return best;
  }
  /** Did the Agent obtain anything that covered the loss, between the loss and the order? */
  awareness(tLoss,tOrder,point){
    for(const command of this.s.commands){
      if(command.timeMs<=tLoss||command.timeMs>tOrder)continue;
      const name=String(command.name||''),args=argsObject(command);
      if(name==='rwx-game-events'){
        if(args.since!=null)return {aware:true,by:'events_since',commandId:command.id};
        const seconds=Number(args.seconds??15);
        if(command.timeMs-seconds*1000<=tLoss)return {aware:true,by:'events_window',commandId:command.id};
      }else if(name==='rwx-game-state'&&args['no-events']!==true){
        const seconds=Number(args['event-seconds']??10);
        if(command.timeMs-seconds*1000<=tLoss)return {aware:true,by:'state_events',commandId:command.id};
      }else if(positionalQuery(command)){
        if(name==='rwx-game-threat-list'){
          const radius=Number(args.radius??0);
          if(Number.isFinite(radius)&&radius>0
              &&distance({x:Number(args.x),y:Number(args.y)},point)<=radius)
            return {aware:true,by:'threat_list',commandId:command.id};
        }else if(name==='rwx-game-unit-list'&&/enemy|all/i.test(String(args.owner||''))){
          return {aware:true,by:'enemy_list',commandId:command.id};
        }
      }
    }
    for(const interval of this.stream)
      if(interval.from<tOrder&&(interval.to==null||interval.to>tLoss))
        return {aware:true,by:'event_stream'};
    return {aware:false,by:null};
  }
  command(command){
    for(const candidate of this.pending){
      if(!candidate.consequence)continue;
      const kind=this.s.matcher.relation(command,[candidate.point],candidate.units,new Set());
      if(kind){(candidate.response.scoreResponses??=[]).push({id:command.id,timeMs:command.timeMs,kind});
        this.s.matcher.response(candidate.response,command,kind);}
    }
    if(command.source!=='agent')return;
    const operation=SEND_ORDERS[String(command.name||'')];
    if(!operation)return;
    const x=Number(command.x),y=Number(command.y);
    if(!Number.isFinite(x)||!Number.isFinite(y))return;
    const point=this.dangerAt(x,y,command.timeMs);
    if(!point){this.diagnose('no_recent_loss_at_target');return;}
    const units=[...(command.units||[]),...(command.builders||[])].map(Number).filter(Number.isFinite);
    // A unit already standing at the place has "arrived" at the order instant; anything else must
    // be seen marching in before the after-arrival window can start.
    const arrivals=new Map();
    for(const id of units){
      const position=this.positions.get(id);
      if(position&&command.timeMs-position.timeMs<=5000&&distance(position,point)<=this.c.X)
        arrivals.set(id,command.timeMs);
    }
    this.pending.push({tLoss:point.timeMs,point,orderMs:command.timeMs,commandId:command.id,operation,
      units:new Set(units),unitsResolved:units.length>0,arrivals,
      awareness:this.awareness(point.timeMs,command.timeMs,point),
      deadline:command.timeMs+this.c.T_GRINDER_HARD_CAP,consequence:null,damage:[],
      response:{t_start:command.timeMs,commands:[],t_first_B:null}});
    this.diagnose('candidate_send_into_danger');
  }
  /** A sent unit took damage or died at the danger point inside the follow-up window. */
  consequence(now,unit,x,y,row=null){
    if(unit?.side!=='self')return;
    const task=unit.taskType??unit.task?.type??null;
    for(const candidate of this.pending){
      if(now<candidate.orderMs||now>candidate.deadline)continue;
      if(distance({x,y},candidate.point)>this.c.X)continue;
      if(candidate.operation!=='rally'){
        if(!candidate.unitsResolved)continue;
        if(!candidate.units.has(Number(unit.id)))continue;
      }
      // An assault or a deliberate guard is not "it thought this was safe".
      if(unit.canAttack&&FIGHTING.has(task))continue;
      // The window starts when the ordered unit first reached the place: a long march must not eat
      // it, and a unit that parked there and was hit much later must not extend it. Units we never
      // saw marching fall back to the hard cap from the order.
      const arrival=candidate.arrivals.get(Number(unit.id));
      if(arrival!=null&&now-arrival>this.c.T_GRINDER_AFTER)continue;
      if(arrival==null&&now-candidate.orderMs>this.c.T_GRINDER_HARD_CAP)continue;
      candidate.consequence??={timeMs:now,unitId:unit.id,type:unit.type,alive:unit.alive!==false,
        taskType:task,arrivalMs:arrival??null,arrivalObserved:arrival!=null};
      candidate.lastHit=now;
      if(row)candidate.damage.push(row);
      // One HP loss belongs to one pending send, even after several orders name the same unit.
      break;
    }
  }
  damage(row){this.consequence(row.timeMs,row.victim,row.victim?.x,row.victim?.y,row);}
  death(row){
    if(row.unit?.side!=='self')return;
    this.deaths.push({timeMs:row.timeMs,x:row.unit.x,y:row.unit.y,unitId:row.unit.id,type:row.unit.type});
    if(this.deaths.length>1024)this.deaths.shift();
    this.consequence(row.timeMs,row.unit,row.unit.x,row.unit.y);
  }
  advance(now){this.close(now);}
  finish(now){this.close(now,true);for(const interval of this.stream)if(interval.to==null)interval.to=now;}
  close(now,force=false){
    for(const candidate of [...this.pending]){
      if(!force&&now<candidate.deadline&&(!candidate.consequence||now-candidate.lastHit<this.c.T_INACTIVE))continue;
      this.pending.splice(this.pending.indexOf(candidate),1);
      if(!candidate.consequence){this.diagnose('no_consequence');continue;}
      const end=Math.min(now,candidate.deadline),coverage=this.s.covers(candidate.tLoss,end);
      const agent=agentState(this.s.commands,candidate.tLoss,end);
      const event=this.event('GRINDER',candidate.tLoss,{t_confirm:candidate.consequence.timeMs,
        location:{x:candidate.point.x,y:candidate.point.y,r:this.c.X},
        commandId:candidate.commandId,operation:candidate.operation,
        delayMs:candidate.orderMs-candidate.tLoss,reactionMs:candidate.orderMs-candidate.tLoss,
        consequence:candidate.consequence,awareness:candidate.awareness,
        ...hpEvidence(candidate.damage),effectiveResponseMs:effectiveResponse(this.s,candidate.response,end),
        commands:candidate.response.commands,
        unitsResolved:candidate.unitsResolved,
        agentState:agent.state,agentStateEvidence:agent.evidence,
        status:!coverage?'UNCERTAIN':candidate.awareness.aware?'EXPLAINED':'DETECTED',
        reason:!coverage?'CLI_command_coverage_unavailable'
          :candidate.awareness.aware?'agent_was_informed':'danger_not_observed',
        strict:false,scoreable:coverage&&!candidate.awareness.aware,visibility:'HIGH'});
      this.diagnose(event.reason);
      this.emit(event,end);
    }
  }
}

/** Which detectors a session runs. The 3-10s gap family is superseded by D/E. */
const LEGACY=[BuilderGapDetector,FeedDetector,StaleCommandDetector,ProductionGapDetector,CommandFailureDetector];
function createDetectors(session){
  const types=[AmbushDetector,GrinderDetector,IdleAssetDetector,StaleBurstDetector];
  if(session.config.legacyDetectors)types.push(...LEGACY);
  return types.map(Type=>new Type(session));
}
module.exports={AmbushDetector,GrinderDetector,BuilderGapDetector,FeedDetector,StaleCommandDetector,
  ProductionGapDetector,CommandFailureDetector,IdleAssetDetector,StaleBurstDetector,LEGACY,STALE_INFO,
  FIGHTING,SEND_ORDERS,createDetectors};
