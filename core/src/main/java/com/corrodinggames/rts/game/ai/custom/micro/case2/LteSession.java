package com.corrodinggames.rts.game.ai.custom.micro.case2;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.*;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.custom.condition.resources.Resource;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.app.MicroJson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** Case-local command authority and transition evidence. No scores or automatic orders. */
public final class LteSession implements MicroSession {
    private final MicroRuntime run;
    private final GameEngine engine;
    private final OrderableUnit builder;
    private final List<Map<String,Object>> nodes,economy;
    private final int target,tier,nodeCount;
    private final String composition;
    private final Map<String,OrderableUnit> buildings=new LinkedHashMap<>();
    private final Map<com.corrodinggames.rts.gameFramework.Command,Instruction> permitted=new IdentityHashMap<>();
    private final Set<Instruction> active=new LinkedHashSet<>();
    private final BufferedWriter events,violations;
    private long violationSeq,nativeIndex;
    public long beginWall=-1;
    private double lastIncome,initialIncome;
    private int generationPeriodTicks;
    private static final class Instruction {
        long request,actor;String kind,resource,action,phase="accepted";OrderableUnit building;
        int targetLevel;boolean terminal,recorded,applied;double cost,actualCost,creditsBefore;
    }
    @SuppressWarnings("unchecked") public LteSession(MicroRuntime run,Map<String,Object> meta)throws IOException {
        this.run=run;engine=GameEngine.getInstance();builder=run.units.get(1000L);
        nodes=(List<Map<String,Object>>)meta.get("resourceNodes");economy=(List<Map<String,Object>>)meta.get("economy");
        target=((Number)meta.get("targetIncomePerSecond")).intValue();tier=((Number)meta.get("tier")).intValue();
        nodeCount=((Number)meta.get("resourceCount")).intValue();composition=(String)meta.get("targetComposition");
        generationPeriodTicks=((Number)((Map<String,Object>)meta.get("incomeClock")).get("generationPeriodTicks")).intValue();
        Files.createDirectories(run.writer.directory.resolve("engine"));
        events=Files.newBufferedWriter(run.writer.directory.resolve("engine/lifecycle.jsonl"));
        violations=Files.newBufferedWriter(run.writer.directory.resolve("violations.jsonl"));
        lastIncome=initialIncome=income();incomeAnchor(0,List.of("initial"),List.of());
    }
    public Map<String,Object> stamp(){return map("tick",engine.currentTick,"gameMs",engine.gameTimeMillis,"wallMs",run.wall());}
    public double income(){PlayerTeam t=builder.team;return t.b(Resource.D)-t.getResourceDrainRate(Resource.D);}
    public void requestFinished(MicroRuntime.Request q,boolean error,String status) {
        if(q.kind.equals("B")&&error)emit(map("kind","instruction","requestId",q.seq,"event","rejected","reason",status));
    }
    private void emit(Map<String,Object> row){row.putAll(stamp());MicroRunWriter.line(events,row);}
    private double credits(){return builder.team.credits;}
    private void event(Instruction i,String name) {
        // `credits` is the team balance at the moment this row is written; the scorer uses it as an
        // exact anchor when it reconstructs how much earlier an instruction could have been issued.
        emit(map("kind","instruction","requestId",i.request,"event",name,"operation",i.kind,"actor",i.actor,
                "resourceId",i.resource,"buildingId",i.building==null?null:i.building.objectId,"targetLevel",i.targetLevel,
                "actionId",i.action,"cost",i.cost,"actualCost",i.actualCost,"credits",credits()));
    }
    private void phase(Instruction i,String next) {
        if(i.phase.equals(next))return;
        event(i,i.phase+"_end");i.phase=next;event(i,next);
    }
    private void terminal(Instruction i,String next){phase(i,next);i.terminal=true;active.remove(i);}
    public String reject(String raw,List<Long> actors,Object target,String type,String reason) {
        MicroRuntime.Request q=MicroRuntime.request();if(q!=null)q.violation=true;
        Map<String,Object> row=map("violationId",++violationSeq,"requestId",q==null?null:q.seq,"command",raw,"actor",actors,
                "target",target,"violationType",type,"reason",reason,"rejected",true);
        row.putAll(stamp());MicroRunWriter.line(violations,row);return MicroRuntime.error("violation: "+reason);
    }
    @Override public String rejectCommand(String name,List<String> args) {
        List<Long> actors=new ArrayList<>();Object target=null;
        for(int n=0;n+1<args.size();n++) {
            if(Set.of("--units","--unit","--building").contains(args.get(n)))for(String s:args.get(n+1).split(","))try{actors.add(Long.parseLong(s));}catch(NumberFormatException ignored){ }
            if(Set.of("--target","--resource","--action","--type").contains(args.get(n)))target=args.get(n+1);
        }
        return reject(name+" "+String.join(" ",args),actors,target,"forbidden_command","command_not_allowed");
    }
    public String execute(Command command,List<String> args) {
        if(command==Command.LTE_STATE||command==Command.LTE_BUILDER||command==Command.LTE_RESOURCES) {
            if(!args.isEmpty()&&!args.equals(List.of("--json")))throw new IllegalArgumentException("invalid_request: expected --json");
            Map<String,Object> row=switch(command){case LTE_STATE->state();case LTE_BUILDER->builderState();default->resources();};
            return args.isEmpty()?"ok: observation\n"+MicroJson.encode(row):MicroJson.encode(row);
        }
        if(command==Command.LTE_BEGIN) {
            if(!args.isEmpty())throw new IllegalArgumentException("invalid_request: no arguments");
            if(!run.phase.equals("briefing"))throw new IllegalArgumentException("invalid_request: already_begun");
            beginWall=run.wall();run.phase="running";engine.gameSpeed=1;run.lifecycle("begin","command");return "ok: begun";
        }
        String key=command==Command.LTE_BUILD?"--resource":"--building";
        if(args.size()!=2||!args.get(0).equals(key)) {
            if(args.stream().anyMatch(s->Set.of("--action","--type","--x","--y","--units","--level").contains(s)))
                return rejectCommand(command.commandName(),args);
            throw new IllegalArgumentException("invalid_request: expected "+key);
        }
        if(!run.phase.equals("running"))throw new IllegalArgumentException("invalid_request: not_running");
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();i.request=q.seq;
        com.corrodinggames.rts.gameFramework.Command nativeCommand;
        if(command==Command.LTE_BUILD) {
            Map<String,Object> node=nodes.stream().filter(p->p.get("resourceId").equals(args.get(1))).findFirst().orElseThrow(()->new IllegalArgumentException("invalid_request: unknown_resource"));
            i.resource=args.get(1);
            if(buildings.containsKey(i.resource))throw new IllegalArgumentException("invalid_request: resource_occupied");
            i.kind="build";i.actor=builder.objectId;i.targetLevel=1;i.cost=((Number)economy.get(0).get("buildCost")).doubleValue();
            for(Instruction old:new ArrayList<>(active))if(old.kind.equals("build"))terminal(old,"superseded");
            nativeCommand=engine.commandController.newCommandForTeam(builder.team);nativeCommand.addUnitToCommand(builder);
            nativeCommand.setBuildTarget(((Number)node.get("x")).floatValue(),((Number)node.get("y")).floatValue(),Case2Lte.type(1),1);
            i.action=builder.findActionForUnitTypeWithQueueSize(Case2Lte.type(1),1,false).getActionIdString();
        } else {
            long id;
            try{id=Long.parseLong(args.get(1));}catch(NumberFormatException e){throw new IllegalArgumentException("invalid_request: invalid_building_id");}
            var entry=buildings.entrySet().stream().filter(p->p.getValue().objectId==id).findFirst().orElse(null);
            if(entry==null)return reject(q.raw,List.of(id),id,"foreign_actor","building_not_in_case");
            i.building=entry.getValue();i.resource=entry.getKey();i.actor=id;i.kind="upgrade";
            int level=Case2Lte.level(i.building);i.targetLevel=level+1;
            if(level==3)return reject(q.raw,List.of(id),id,"forbidden_upgrade","no_standard_upgrade");
            if(i.building.buildProgress<1||!((FactoryQueueInterface)i.building).dy()||active.stream().anyMatch(old->old.actor==id))
                throw new IllegalArgumentException("invalid_request: building_busy");
            AbstractUnitAction action=Case2Lte.upgrade(i.building);i.action=action.getActionIdString();i.cost=action.getCostAmount();
            if(!action.canAfford(i.building,false))throw new IllegalArgumentException("invalid_request: insufficient_credits");
            nativeCommand=engine.commandController.newCommandForTeam(builder.team);nativeCommand.addUnitToCommand(i.building);nativeCommand.setActionId(action.getActionId());
        }
        active.add(i);permitted.put(nativeCommand,i);event(i,"accepted");
        return "ok: submitted requestId="+i.request;
    }
    /** Called both before replay recording and at direct execution; identity prevents all alternate entry points. */
    public boolean allow(com.corrodinggames.rts.gameFramework.Command c) {
        Instruction i=permitted.get(c);
        if(i!=null&&i.terminal&&!i.applied)return false;
        boolean valid=i!=null&&!i.terminal&&run.phase.equals("running")&&c.getUnitIds().equals(List.of(i.actor))
                &&!c.isSystemAction&&!c.stopCurrentAction&&!c.clearExistingOrders&&!c.isQueued&&!c.isInstantCommand&&!c.isHighPriority
                &&c.attackMode==null&&c.targetPoint==null&&c.targetUnit==null&&!c.hasRallyPoint();
        if(valid&&i.kind.equals("build")) {
            var n=nodes.stream().filter(p->p.get("resourceId").equals(i.resource)).findFirst().orElseThrow();
            valid=c.unitCommand!=null&&c.unitCommand.getCommandType()==UnitCommandType.build&&c.unitCommand.getBuildUnitType()==Case2Lte.type(1)
                    &&c.unitCommand.getBuildQueueSize()==1&&c.unitCommand.getTargetX()==((Number)n.get("x")).floatValue()
                    &&c.unitCommand.getTargetY()==((Number)n.get("y")).floatValue()&&!c.unitCommand.isForceMove
                    &&!c.unitCommand.isQueued&&!c.unitCommand.isRepeating&&!AbstractUnitAction.isActionIdSpecified(c.actionId);
        } else if(valid) valid=c.unitCommand==null&&c.actionId.getId().equals(i.action)&&Case2Lte.level(i.building)==i.targetLevel-1;
        if(!valid)reject("native",c.getUnitIds(),c.actionId.getId(),"native_bypass","unauthorized_native_command");
        return valid;
    }
    public void recorded(com.corrodinggames.rts.gameFramework.Command c) {
        Instruction i=permitted.get(c);if(i==null||i.recorded)return;i.recorded=true;
        i.creditsBefore=builder.team.credits;
        emit(map("kind","native","requestId",i.request,"nativeIndex",nativeIndex++,"actor",i.actor,"operation",i.kind,
                "actionId",i.kind.equals("upgrade")?i.action:null,"buildType",i.kind.equals("build")?Case2Lte.type(1).v():null,
                "resourceId",i.resource,"credits",i.creditsBefore));
    }
    public void applied(com.corrodinggames.rts.gameFramework.Command c) {
        Instruction i=permitted.get(c);if(i==null)return;i.applied=true;event(i,"order_started");
        if(i.kind.equals("upgrade")) {
            if(((FactoryQueueInterface)i.building).dy())terminal(i,"failed");
            else {i.actualCost=i.creditsBefore-builder.team.credits;phase(i,"queued");}
        } else phase(i,"pathing");
    }
    public void created(BaseUnit raw,OrderableUnit actor,double paid) {
        if(actor!=builder||!(raw instanceof OrderableUnit unit))return;
        Instruction i=active.stream().filter(x->x.kind.equals("build")&&x.applied).findFirst().orElse(null);
        if(i==null)return;i.building=unit;i.actualCost=paid;buildings.put(i.resource,unit);run.units.put(unit.objectId,unit);
        phase(i,"arrived");phase(i,"warming_up");
    }
    public void waiting(OrderableUnit actor) {
        for(Instruction i:active)if(i.kind.equals("build")&&i.applied&&actor==builder)phase(i,"waiting_for_credits");
    }
    public void afterTick() {
        List<String> causes=new ArrayList<>();List<Long> changed=new ArrayList<>();
        // Only outstanding instructions and their stable references are inspected; no world scan or tick log.
        for(Instruction i:new ArrayList<>(active))if(i.applied) {
            if(!MicroRuntime.alive(builder)||i.building!=null&&!MicroRuntime.alive(i.building)){terminal(i,"failed");continue;}
            if(i.kind.equals("build")) {
                if(i.building==null) {if(builder.isMoving)phase(i,"moving");else if(builder.getCurrentWaypoint()==null)terminal(i,"failed");}
                else if(i.building.buildProgress>=1) {terminal(i,"completed");causes.add("construction_complete");changed.add(i.building.objectId);}
                else if(builder.aO)phase(i,"waiting_for_credits");
                else if(i.building.buildProgress>0)phase(i,"constructing");
            } else {
                if(Case2Lte.level(i.building)==i.targetLevel){terminal(i,"completed");causes.add("upgrade_complete");changed.add(i.building.objectId);}
                else {var item=((FactoryQueueInterface)i.building).dw();if(item!=null&&item.m>=0)phase(i,"upgrading");
                    else if(((FactoryQueueInterface)i.building).dy())terminal(i,"cancelled");}
            }
        }
        double current=income();
        if(current!=lastIncome){incomeAnchor(current-lastIncome,causes.isEmpty()?List.of("unknown"):causes,changed);lastIncome=current;}
        if(current>=target)run.end("success","target-income");
        else if(!MicroRuntime.alive(builder))run.end("error","builder-lost");
    }
    private void incomeAnchor(double delta,List<String> cause,List<Long> ids){emit(map("kind","income","incomePerSecond",income(),"deltaIncome",delta,"cause",cause,"buildingIds",ids));}
    public Map<String,Object> state() {
        Map<String,Object> s=stamp();s.putAll(map("status","ok","phase",run.phase,"credits",builder.team.credits,"incomePerSecond",income(),
                "task",run.config.task,"tier",tier,"resourceCount",nodeCount,"targetComposition",composition,
                "targetIncomePerSecond",target,"targetReached",income()>=target,
                "remainingGameMs",Math.max(0,run.config.gameLimitSeconds*1000-engine.gameTimeMillis),"remainingWallMs",run.remainingWall()*1000,
                "economy",economy,"builderMoveSpeed",builder.getMoveSpeed(),"builderWarmupTicks",builder.e(0),"generationPeriodTicks",generationPeriodTicks));return s;
    }
    public Map<String,Object> builderState() {
        UnitCommand order=builder.getCurrentWaypoint();
        Instruction active=this.active.stream().filter(i->i.kind.equals("build")).findFirst().orElse(null);
        Map<String,Object> s=stamp();s.putAll(map("status","ok","id",builder.objectId,"type",builder.r().v(),"x",builder.posX,"y",builder.posY,
                "order",order==null?"idle":order.getCommandType().name(),"phase",active==null?"idle":active.phase,
                "resourceId",active==null?null:active.resource,"requestId",active==null?null:active.request,
                "buildProgress",active==null||active.building==null?null:active.building.buildProgress,"moving",builder.isMoving));return s;
    }
    public Map<String,Object> resources() {
        List<Map<String,Object>> rows=new ArrayList<>();
        var distances=AiMapGraph.of(UnitMovementType.LAND).distanceFieldFrom(builder.posX,builder.posY);
        for(var node:nodes) {
            Map<String,Object> row=new LinkedHashMap<>(node);OrderableUnit b=buildings.get(node.get("resourceId"));
            row.put("occupied",b!=null);row.put("pathDistance",Case2Lte.approachDistance(distances,
                    ((Number)node.get("tileX")).intValue(),((Number)node.get("tileY")).intValue()));
            if(b!=null) {
                int level=Case2Lte.level(b);boolean busy=b.buildProgress<1||!((FactoryQueueInterface)b).dy()||active.stream().anyMatch(i->i.actor==b.objectId);
                row.putAll(map("buildingId",b.objectId,"type",b.r().v(),"level",level,"buildProgress",b.buildProgress,"busy",busy,
                        "upgradeProgress",((FactoryQueueInterface)b).dw()==null?null:((FactoryQueueInterface)b).dw().m,
                        "availableActions",!busy&&level<3?List.of(Case2Lte.upgrade(b).getActionIdString()):List.of()));
            }
            rows.add(row);
        }
        Map<String,Object> s=stamp();s.putAll(map("status","ok","resources",rows,"economy",economy));return s;
    }
    public Map<String,Object> result() {
        Map<String,Object> s=state();s.putAll(map("initialIncomePerSecond",initialIncome,"finalIncomePerSecond",income(),"unusedCredits",builder.team.credits,
                "remainingResources",nodes.size()-buildings.size(),"beginWallMs",beginWall<0?null:beginWall,
                "gameElapsedMs",engine.gameTimeMillis,"wallElapsedMs",beginWall<0?null:run.wall()-beginWall,
                "targetReachedGameMs",run.outcome.equals("success")?engine.gameTimeMillis:null,
                "targetReachedWallMs",run.outcome.equals("success")?run.wall():null));return s;
    }
    public void finish(String outcome) {
        for(Instruction i:new ArrayList<>(active))terminal(i,outcome.equals("timeout")?"timeout":"cancelled");
    }
    @Override public void close()throws IOException{events.close();violations.close();}
}
