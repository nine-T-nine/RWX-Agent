'use strict';
const {identity,contains,intersects,location,composition,directB,operation}=require('./model');

class Detector {
  constructor(session){this.s=session;this.c=session.config;}
  event(type,start,extra={}) {return {id:this.s.nextId(type),type,t_start:start,t_confirm:null,t_end:null,
    t_first_B:null,t_first_applied:null,commands:[],status:'DETECTED',observability:'unknown',...extra};}
  emit(event,end) {
    event.t_end=end;
    if(event.t_first_B!=null)event.reactionMs=event.t_first_B-event.t_start;
    this.s.emit(event);
  }
}
class AmbushDetector extends Detector {
  constructor(s){super(s);this.regions=[];this.lastDamage=new Map();}
  circles(region){return [...new Map([...region.pairs.values()].flatMap(p=>[p.attacker,p.victim]).map(u=>[identity(u),this.s.units.get(identity(u))||u])).values()];}
  damage(row) {
    const a=row.attacker,v=row.victim;
    if(!v)return;
    this.lastDamage.set(identity(v),row.timeMs);
    for(const r of this.regions)for(const [key,p]of r.pairs)if(identity(p.attacker)===identity(v))r.pairs.delete(key);
    if(!a||a.side!=='enemy'||v.side!=='self'||!v.idle||(v.building&&!v.canAttack)
        ||a.excluded||v.excluded||row.timeMs-(this.lastDamage.get(identity(a))??-Infinity)<this.c.T_INACTIVE)return;
    const key=identity(a)+'>'+identity(v);
    let region=this.regions.find(r=>r.pairs.has(key));
    if(!region){
      region={event:this.event('AMBUSH',row.timeMs),pairs:new Map(),history:new Map(),losses:new Map()};
      this.regions.push(region);
    }
    const old=region.pairs.get(key);
    region.pairs.set(key,{attacker:a,victim:v,start:old&&old.start!=null&&row.timeMs-old.last<=this.c.T_INACTIVE?old.start:row.timeMs,last:row.timeMs,nonIdleSince:null});
    region.history.set(identity(a),a);region.history.set(identity(v),v);
    // Stable region identity survives a later split; it is never counted again as two events.
    let changed=true;
    while(changed){changed=false;for(const other of [...this.regions]){
      if(other===region||!intersects(this.circles(region),this.circles(other),this.c.X))continue;
      const keep=other.event.t_start<region.event.t_start?other:region,drop=keep===region?other:region;
      for(const[k,p]of drop.pairs)keep.pairs.set(k,p);
      for(const[k,u]of drop.history)keep.history.set(k,u);
      for(const[k,u]of drop.losses)keep.losses.set(k,u);
      keep.event.t_start=Math.min(keep.event.t_start,drop.event.t_start);
      keep.event.t_confirm=keep.event.t_confirm==null?drop.event.t_confirm:drop.event.t_confirm==null?keep.event.t_confirm:Math.min(keep.event.t_confirm,drop.event.t_confirm);
      for(const command of drop.event.commands)this.s.matcher.response(keep.event,command,command.kind);
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
      this.s.matcher.response(r.event,command,kind);
    }
  }
  death(row){for(const region of this.regions)if(region.history.has(identity(row.unit)))region.losses.set(identity(row.unit),row.unit);}
  close(r,now,truncated=false) {
    this.regions.splice(this.regions.indexOf(r),1);
    if(r.event.t_confirm==null)return;
    const us=[...r.history.values()].filter(u=>u.side==='self'),enemies=[...r.history.values()].filter(u=>u.side==='enemy');
    Object.assign(r.event,{location:location(this.circles(r).length?this.circles(r):[...r.history.values()]),
      self:composition(us),enemy:composition(enemies),units:[...r.history.keys()],losses:composition([...r.losses.values()]),
      status:r.event.t_first_B==null?(this.s.covers(r.event.t_start,now)?'UNANSWERED':'UNCERTAIN'):'DETECTED',truncated,
      observability:enemies.some(u=>u.visible)?'visible_threat':'damage_notice_only'});
    this.emit(r.event,now);
  }
  finish(now){for(const r of [...this.regions])this.close(r,now,true);}
}

class BuilderGapDetector extends Detector {
  constructor(s){super(s);this.states=new Map();}
  unit(row) {
    const u=row.unit;if(!u.builder||u.side!=='self'||u.excluded)return;
    const key=identity(u),old=this.states.get(key),task=u.task;
    let gap=old?.gap;
    if(!u.alive){this.states.delete(key);return;}
    if(old?.unit.task&&['build','repair'].includes(old.unit.task.type)&&!task&&u.idle)
      gap={start:row.timeMs,previous:old.unit.task,snapshot:u,funds:row.funds};
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
          feasible,reason:!coverage?'CLI_command_coverage_unavailable':reason,status:!coverage||feasible==null?'UNCERTAIN':!feasible?'EXPLAINED':commands.length?'EXPLAINED':'UNANSWERED',
          flags:commands.length?['BUILDER_INACTIVE']:coverage?['NO_B_GAP']:[],strict:coverage&&feasible===true&&!commands.length});
        this.emit(event,row.timeMs);
      }
      gap=null;
    }else if(gap&&(!u.idle||row.timeMs-gap.start>=this.c.T_GAP_MAX))gap=null;
    this.states.set(key,{unit:u,gap});
  }
  finish(){}
}

class ProductionGapDetector extends Detector {
  constructor(s){super(s);this.states=new Map();}
  idle(u){return u.alive&&u.complete!==false&&!u.queue?.length&&(!u.mobile||u.idle);}
  unit(row) {
    const u=row.unit;if(!u.factory||u.side!=='self'||u.excluded)return;
    const key=identity(u),old=this.states.get(key)||{unit:null,produced:false,gap:null};
    if(!u.alive){this.states.delete(key);return;}
    const upgrade=u.queue?.some(q=>q.kind!=='produce');
    if(upgrade){old.produced=false;old.gap=null;}
    if(u.queue?.some(q=>q.kind==='produce'))old.produced=true;
    if(this.idle(u)&&old.unit&&!this.idle(old.unit)&&old.produced)
      old.gap={start:row.timeMs,snapshot:u,funds:row.funds};
    if(!this.idle(u)||old.gap&&row.timeMs-old.gap.start>=this.c.T_GAP_MAX)old.gap=null;
    old.unit=u;this.states.set(key,old);
  }
  native(row) {
    if(row.phase!=='after')return;
    for(const result of row.results||[]){
      const old=this.states.get(`${result.unitId}:${result.life??0}`);if(!old?.gap)continue;
      const gap=old.gap;old.gap=null;
      if(!result.queueAdded?.length)continue;
      if(result.queueAdded.some(a=>a.kind!=='produce'))continue;
      const duration=row.timeMs-gap.start;
      if(duration<=this.c.T_GAP_MIN||duration>=this.c.T_GAP_MAX)continue;
      const actionId=result.queueAdded[0].action,action=gap.snapshot.actions?.[actionId];
      const count=result.batchCount??result.queueAdded.reduce((n,q)=>n+q.count,0);
      const commands=this.s.matcher.between(gap.start,row.timeMs);
      let feasible=action?!!(action.kind==='produce'&&action.available&&action.affordable
        &&gap.funds>=action.cost*count&&(gap.snapshot.capRemaining??Infinity)>=count):null;
      const coverage=this.s.covers(gap.start,row.timeMs);
      const linked=this.s.matcher.commands.find(c=>c.id===row.commandId||(c.applications||[]).some(a=>a.nativeId===row.nativeId));
      const proactive=row.source==='agent'||linked!=null;
      const sharedFunds=(linked?.units.length??row.results.length)>1;
      if(sharedFunds)feasible=null;
      const arranged=linked&&linked.timeMs<gap.start;
      const uncertainOrigin=!proactive&&!['keep','pending','engine'].includes(row.source);
      const event=this.event('PRODUCTION_GAP',gap.start,{t_confirm:row.timeMs,gapMs:duration,unit:old.unit,
        next:result.queueAdded,batchCount:count,funds:gap.funds,cost:action? action.cost*count:null,
        B_count:commands.length,B_ids:commands.map(c=>c.id),feasible,
        t_first_B:linked?.timeMs??null,t_first_applied:row.timeMs,
        reason:!coverage?'CLI_command_coverage_unavailable':uncertainOrigin?'production_order_origin_unavailable':!proactive||arranged?'automation_or_prearranged_order':sharedFunds?'multi_factory_shared_funds_not_reconstructed':feasible==null?'historical_action_unavailable':!feasible?'production_not_feasible_at_start':commands.length?'global_B_during_gap':'verified',
        strict:coverage&&proactive&&!arranged&&feasible===true&&!commands.length,
        status:!coverage||uncertainOrigin||feasible==null?'UNCERTAIN':!proactive||arranged||!feasible||commands.length?'EXPLAINED':'UNANSWERED'});
      this.emit(event,row.timeMs);
    }
  }
  finish(){}
}

class FeedDetector extends Detector {
  constructor(s){super(s);this.regions=[];this.seen=new Set();}
  death(row) {
    const u=row.unit,key=identity(u),killer=row.killer;
    if(this.seen.has(key))return;this.seen.add(key);
    if(u.side!=='self'||u.excluded||u.task?.type==='attackMove'||!killer||killer.side!=='enemy'||killer.excluded)return;
    const near=(row.nearby||[]).filter(v=>v.alive&&identity(v)!==key&&!v.excluded);
    if(!near.some(v=>v.side==='enemy')||near.some(v=>['self','ally'].includes(v.side)))return;
    let r={id:this.s.nextId('REGION'),circles:[{x:u.x,y:u.y,anchorId:u.id}],losses:[{unit:u,timeMs:row.timeMs,seq:row.seq}],
      commands:[],lastHostile:row.timeMs,created:row.timeMs,segments:[]};
    this.regions.push(r);
    let changed=true;
    while(changed){changed=false;for(const other of [...this.regions]){
      if(other===r||!intersects(r.circles,other.circles,this.c.X))continue;
      r.circles.push(...other.circles);r.losses.push(...other.losses);r.commands.push(...other.commands);
      r.segments.push(...other.segments);r.created=Math.min(r.created,other.created);
      r.lastHostile=Math.max(r.lastHostile,other.lastHostile);
      this.regions.splice(this.regions.indexOf(other),1);changed=true;break;
    }}
    r.losses=[...new Map(r.losses.map(l=>[identity(l.unit),l])).values()].sort((a,b)=>a.timeMs-b.timeMs||a.seq-b.seq);
    r.state=r.losses.length>=2?'DANGER':'UNKNOWN';
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
    if(r.losses.length<3)return;
    const repeated=r.losses.slice(2),segments=[];
    for(const loss of repeated){const last=segments.at(-1);if(last&&loss.timeMs-last.at(-1).timeMs<=this.c.T_FEED_MERGE)last.push(loss);else segments.push([loss]);}
    for(const [index,losses]of segments.entries()){
      const start=index===0?r.losses[0].timeMs:losses[0].timeMs,end=losses.at(-1).timeMs;
      const commands=r.commands.filter(c=>c.timeMs>=r.created&&c.timeMs<=end);
      const assault=commands.some(c=>['attack','attackMove','guard'].includes(c.kind));
      const exposure=commands.some(c=>c.kind==='move_in'&&c.timeMs>=r.losses[1].timeMs&&losses.some(l=>l.timeMs>c.timeMs));
      const event=this.event('FEED',start,{region_id:r.id,t_confirm:r.losses[1].timeMs,
        t_first_B:commands[0]?.timeMs??null,
        t_first_applied:commands.flatMap(c=>c.applications||[]).filter(a=>a.applied===true&&a.timeMs<=end).reduce((t,a)=>Math.min(t??Infinity,a.timeMs),null),
        t_first_repeat:r.losses[2].timeMs,region_end:now,location:location(r.circles),state:r.state,
        losses:composition(r.losses.filter(l=>l.timeMs<=end).map(l=>l.unit)),extra:composition(losses.map(l=>l.unit)),
        lossEvidence:r.losses.filter(l=>l.timeMs<=end),commands:commands.map(c=>({id:c.id,timeMs:c.timeMs,kind:c.kind,raw:c.raw})),
        flags:[...(!assault&&this.s.covers(r.created,end)?['NO_ASSAULT_RESPONSE']:[]),...(exposure?['REPEATED_EXPOSURE']:[])],
        status:!this.s.covers(r.created,end)?'UNCERTAIN':assault||commands.some(c=>c.kind==='retreat')?'EXPLAINED':'DETECTED',truncated,
        observability:r.losses.some(l=>l.unit.visible)?'own_loss_notice':'unknown',
        interpretation:'Repeated losses are evidence; tactical intent is not inferred.'});
      this.emit(event,end);
    }
  }
  finish(now){for(const r of [...this.regions])this.close(r,now,true);}
}

class StaleCommandDetector extends Detector {
  constructor(s){super(s);this.objects=new Map();this.delayed=[];}
  command(command) {
    if(!directB(command))return;
    const counted=new Set();
    if(['attackMode','rally'].includes(operation(command)))return;
    for(const ref of command.refs||[]){
      if(ref.excluded||!['dead','missing','wrong_team','removed'].includes(ref.status))continue;
      const key=`${ref.id}:${ref.life??0}`;if(counted.has(key))continue;counted.add(key);
      if(ref.eligibleKnown===false || (ref.atReceipt===false||ref.checkedMs>command.timeMs) && (ref.deathTimeMs==null||ref.deathTimeMs>command.timeMs
          ||ref.deathTimeMs===command.timeMs&&ref.deathSeq>command.seq)){
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
module.exports={AmbushDetector,BuilderGapDetector,FeedDetector,StaleCommandDetector,ProductionGapDetector};
