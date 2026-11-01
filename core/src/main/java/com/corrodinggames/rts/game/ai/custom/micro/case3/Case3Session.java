package com.corrodinggames.rts.game.ai.custom.micro.case3;

import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.*;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.app.MicroJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * Case-local authority, command handling and transition evidence for CASE3-Transport. Commands are
 * translated into real engine commands; nothing here teleports units or edits cargo memory.
 */
public final class Case3Session extends AbstractCaseSession {
    private final List<Map<String,Object>> requirements;
    private final Set<String> targetTypes=new LinkedHashSet<>();
    private final Map<Long,OrderableUnit> factories=new LinkedHashMap<>();
    private final Map<Long,OrderableUnit> transports=new LinkedHashMap<>();
    private final Map<Long,String> transportTypes=new LinkedHashMap<>();
    private final Map<String,Integer> produced=new LinkedHashMap<>();
    private final Map<String,Integer> delivered=new LinkedHashMap<>();
    private final Map<Long,Float> transportHp=new LinkedHashMap<>();
    private final Map<Long,Set<Long>> cargo=new LinkedHashMap<>();
    private final BufferedWriter productionLog,transportLog,goalLog;
    private int lastDelivered=-1;
    private long goalReachedGameMs=-1;
    private boolean goalReached;
    @SuppressWarnings("unchecked") public Case3Session(MicroRuntime run,Map<String,Object> meta)throws IOException {
        super(run,meta);
        requirements=(List<Map<String,Object>>)meta.get("requirements");
        for(Map<String,Object> need:requirements){String type=(String)need.get("type");targetTypes.add(type);produced.put(type,0);delivered.put(type,0);}
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("factories"))factories.put(((Number)row.get("id")).longValue(),run.units.get(((Number)row.get("id")).longValue()));
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("transports")){
            long id=((Number)row.get("id")).longValue();transports.put(id,run.units.get(id));transportTypes.put(id,(String)row.get("type"));
        }
        productionLog=Files.newBufferedWriter(run.writer.directory.resolve("engine/production.jsonl"));
        transportLog=Files.newBufferedWriter(run.writer.directory.resolve("engine/transport.jsonl"));
        goalLog=Files.newBufferedWriter(run.writer.directory.resolve("engine/goal.jsonl"));
    }
    protected BufferedWriter nativeLog(){return productionLog;}
    protected Map<String,Object> nativeRow(Instruction i,int index) {
        return map("kind","native","requestId",i.request,"nativeIndex",index,"actor",i.actor,"operation",i.kind,"unitType",i.unitType);
    }
    protected void closeLogs()throws IOException{productionLog.close();transportLog.close();goalLog.close();}
    public void requestFinished(MicroRuntime.Request q,boolean error,String status) {
        if(q!=null&&q.kind.equals("B")&&error)emit(productionLog,map("kind","request","requestId",q.seq,"event","rejected","reason",status));
    }
    private static OrderableUnit ownUnit(long id){
        for(BaseUnit unit:BaseUnit.bE)if(unit!=null&&unit.objectId==id&&unit instanceof OrderableUnit orderable)return orderable;
        return null;
    }
    private OrderableUnit transport(String token){
        long id;try{id=Long.parseLong(token);}catch(NumberFormatException e){throw new IllegalArgumentException("invalid_request: invalid_transport_id");}
        OrderableUnit unit=transports.get(id);
        if(unit==null)throw new IllegalArgumentException("invalid_request: unknown_transport");
        return unit;
    }
    private OrderableUnit factory(String token){
        long id;try{id=Long.parseLong(token);}catch(NumberFormatException e){throw new IllegalArgumentException("invalid_request: invalid_factory_id");}
        OrderableUnit unit=factories.get(id);
        if(unit==null)throw new IllegalArgumentException("invalid_request: unknown_factory");
        return unit;
    }
    private static int slots(BaseUnit unit){return unit.getTransportSlotsNeeded();}
    private static int used(OrderableUnit transport){
        int n=0;for(Object item:transport.getTransportedUnitList())n+=slots((BaseUnit)item);return n;
    }
    private AbstractUnitAction productionAction(OrderableUnit factory,UnitType type){
        for(Object item:factory.getAvailableActions())if(item instanceof QueueUnitAction action&&action.getUnitType()==type)return action;
        return null;
    }
    private final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameCommands delegate=
            new com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameCommands(new com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxEngineLocalGameService());
    public String execute(Command command,List<String> args)throws Exception {
        if(command==Command.MAP_SHOW||command==Command.UNIT_LIST||command==Command.UNIT_SHOW
                ||command==Command.MAP_REACH)
            return delegate.execute(command,args);
        if(command==Command.TRANSPORT_STATE||command==Command.TRANSPORT_GOAL||command==Command.TRANSPORT_PRODUCTION||command==Command.TRANSPORT_FLEET) {
            if(!args.isEmpty()&&!args.equals(List.of("--json")))throw new IllegalArgumentException("invalid_request: expected --json");
            Map<String,Object> row=switch(command){case TRANSPORT_STATE->state();case TRANSPORT_GOAL->goalState();
                case TRANSPORT_PRODUCTION->productionState();default->fleetState();};
            return args.isEmpty()?"ok: observation\n"+MicroJson.encode(row):MicroJson.encode(row);
        }
        if(command==Command.TRANSPORT_PRODUCE)return produce(args);
        if(command==Command.TRANSPORT_LOAD)return load(args);
        if(command==Command.TRANSPORT_MOVE)return move(args);
        if(command==Command.TRANSPORT_UNLOAD)return unload(args);
        throw new IllegalArgumentException("unknown command");
    }
    private String produce(List<String> args) {
        running();
        if(args.size()<4||!args.get(0).equals("--factory")||!args.get(2).equals("--unit"))throw new IllegalArgumentException("invalid_request: expected --factory <id> --unit <type> [--count N]");
        int count=1;
        if(args.size()>4){if(args.size()!=6||!args.get(4).equals("--count"))throw new IllegalArgumentException("invalid_request: expected --count N");count=Integer.parseInt(args.get(5));}
        if(count<1)throw new IllegalArgumentException("invalid_request: invalid_count");
        OrderableUnit factory=factory(args.get(1));
        UnitType type=UnitTypeEnum.getUnitTypeByName(args.get(3));
        if(type==null||!targetTypes.contains(type.v()))return reject("produce",List.of(factory.objectId),args.get(3),"non_target_production","type_not_in_goal");
        if(!MicroRuntime.alive(factory))throw new IllegalArgumentException("invalid_request: factory_destroyed");
        int required=0;for(Map<String,Object> need:requirements)if(need.get("type").equals(type.v()))required=((Number)need.get("required")).intValue();
        int gap=required-produced.getOrDefault(type.v(),0);
        if(count>gap)throw new IllegalArgumentException("invalid_request: exceeds_goal_gap");
        AbstractUnitAction action=productionAction(factory,type);
        if(action==null)throw new IllegalArgumentException("invalid_request: factory_cannot_produce_type");
        if(!action.canAfford(factory,false))throw new IllegalArgumentException("invalid_request: insufficient_credits");
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.actor=factory.objectId;i.units=List.of(factory.objectId);i.kind="produce";i.unitType=type.v();i.unit=factory;
        for(int n=0;n<count;n++){
            var nativeCommand=engine.commandController.newCommandForTeam(factory.team);
            nativeCommand.addUnitToCommand(factory);nativeCommand.setActionId(action.getActionId());
            permitted.put(nativeCommand,i);
        }
        emit(productionLog,map("kind","order","requestId",i.request,"event","accepted","factoryId",factory.objectId,"unitType",type.v(),"count",count,"actionId",action.getActionIdString()));
        return "ok: submitted requestId="+i.request;
    }
    private String load(List<String> args) {
        running();
        if(args.size()!=4||!args.get(0).equals("--transport")||!args.get(2).equals("--units"))throw new IllegalArgumentException("invalid_request: expected --transport <id> --units <ids>");
        OrderableUnit transport=transport(args.get(1));
        if(!MicroRuntime.alive(transport))throw new IllegalArgumentException("invalid_request: transport_destroyed");
        List<OrderableUnit> units=new ArrayList<>();
        int extra=0;
        for(String token:args.get(3).split(",")) {
            long id;try{id=Long.parseLong(token);}catch(NumberFormatException e){throw new IllegalArgumentException("invalid_request: invalid_unit_id");}
            OrderableUnit unit=ownUnit(id);
            if(unit==null||unit.team==null||unit.team.teamId!=Case3Transport.SELF_TEAM)
                return reject("load",List.of(transport.objectId,id),id,"foreign_actor","unit_not_in_case");
            if(!targetTypes.contains(unit.r().v()))return reject("load",List.of(id),id,"non_target_unit","not_a_target_unit");
            if(unit.transportContainer!=null)throw new IllegalArgumentException("invalid_request: unit_already_loaded");
            if(!MicroRuntime.alive(unit))throw new IllegalArgumentException("invalid_request: unit_not_ready");
            units.add(unit);extra+=slots(unit);
        }
        int capacity=((Number)meta.get("transportCapacity")).intValue();
        if(used(transport)+extra>capacity)throw new IllegalArgumentException("invalid_request: insufficient_capacity");
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.actor=transport.objectId;i.target=transport.objectId;i.units=units.stream().map(u->u.objectId).toList();i.kind="load";i.unit=transport;
        var nativeCommand=engine.commandController.newCommandForTeam(transport.team);
        for(OrderableUnit unit:units)nativeCommand.addUnitToCommand(unit);
        nativeCommand.setLoadIntoTarget(transport);
        permitted.put(nativeCommand,i);
        emit(transportLog,map("kind","load","requestId",i.request,"event","issued","transportId",transport.objectId,"unitIds",units.stream().map(u->u.objectId).toList(),"usedSlots",used(transport)+extra,"capacity",capacity));
        return "ok: submitted requestId="+i.request;
    }
    private String move(List<String> args) {
        running();
        if(args.size()!=6||!args.get(0).equals("--transport")||!args.get(2).equals("--x")||!args.get(4).equals("--y"))throw new IllegalArgumentException("invalid_request: expected --transport <id> --x X --y Y");
        OrderableUnit transport=transport(args.get(1));
        if(!MicroRuntime.alive(transport))throw new IllegalArgumentException("invalid_request: transport_destroyed");
        float x=Float.parseFloat(args.get(3)),y=Float.parseFloat(args.get(5));
        if(x<0||y<0||x>Case3Map.SIZE*Case3Map.TILE||y>Case3Map.SIZE*Case3Map.TILE)throw new IllegalArgumentException("invalid_request: invalid_destination");
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.actor=transport.objectId;i.units=List.of(transport.objectId);i.kind="move";i.unit=transport;
        var nativeCommand=engine.commandController.newCommandForTeam(transport.team);
        nativeCommand.addUnitToCommand(transport);nativeCommand.setMoveTarget(x,y);
        permitted.put(nativeCommand,i);
        emit(transportLog,map("kind","move","requestId",i.request,"event","issued","transportId",transport.objectId,"x",x,"y",y));
        return "ok: submitted requestId="+i.request;
    }
    private String unload(List<String> args) {
        running();
        if(args.size()!=2||!args.get(0).equals("--transport"))throw new IllegalArgumentException("invalid_request: expected --transport <id>");
        OrderableUnit transport=transport(args.get(1));
        if(!MicroRuntime.alive(transport))throw new IllegalArgumentException("invalid_request: transport_destroyed");
        ActionId unload=transport.getUnloadActionId();
        if(unload==null||AbstractUnitAction.isActionIdSpecified(unload)==false)throw new IllegalArgumentException("invalid_request: transport_cannot_unload");
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.actor=transport.objectId;i.units=List.of(transport.objectId);i.kind="unload";i.unit=transport;
        var nativeCommand=engine.commandController.newCommandForTeam(transport.team);
        nativeCommand.addUnitToCommand(transport);nativeCommand.setActionId(unload);
        permitted.put(nativeCommand,i);
        emit(transportLog,map("kind","unload","requestId",i.request,"event","issued","transportId",transport.objectId,"actionId",unload.getId()));
        return "ok: submitted requestId="+i.request;
    }
    /** CASE3 never clears existing orders: every native command is a pure move/load/unload/produce. */
    protected boolean matches(com.corrodinggames.rts.gameFramework.Command command,Instruction i) {
        if(command.clearExistingOrders)return false;
        if(i.kind.equals("produce"))return command.unitCommand==null&&command.actionId!=null;
        if(i.kind.equals("load"))return command.unitCommand!=null&&command.unitCommand.getCommandType()==UnitCommandType.loadInto
                &&command.unitCommand.getTargetUnit()!=null&&command.unitCommand.getTargetUnit().objectId==i.target;
        if(i.kind.equals("move"))return command.unitCommand!=null&&command.unitCommand.getCommandType()==UnitCommandType.move;
        if(i.kind.equals("unload"))return command.unitCommand==null&&command.actionId!=null;
        return false;
    }
    public void applied(com.corrodinggames.rts.gameFramework.Command command) {
        Instruction i=permitted.get(command);if(i==null)return;i.applied=true;
        if(i.kind.equals("produce"))emit(productionLog,map("kind","order","requestId",i.request,"event","started","factoryId",i.actor,"unitType",i.unitType));
    }
    public void afterTick() {
        if(!run.phase.equals("running"))return;
        // Goal and cargo transitions are read from stable engine state, not by scanning the world.
        Map<String,Integer> counts=Case3Transport.delivered(engine,meta);
        int total=0;for(Map<String,Object> need:requirements)total+=counts.getOrDefault((String)need.get("type"),0);
        if(total!=lastDelivered){
            for(Map<String,Object> need:requirements){String type=(String)need.get("type");int now=counts.getOrDefault(type,0);
                if(now!=delivered.getOrDefault(type,0)){emit(goalLog,map("kind","delivered","unitType",type,"count",now,"required",need.get("required")));delivered.put(type,now);}}
            lastDelivered=total;
        }
        int aliveTransports=0;
        // Transport and production evidence is written before the goal check, so the unload that
        // completes the goal is still recorded instead of being cut off by the end of the run.
        for(var entry:transports.entrySet()){
            OrderableUnit unit=entry.getValue();if(unit==null||!MicroRuntime.alive(unit))continue;
            aliveTransports++;
            float hp=unit.currentHealth;Float last=transportHp.put(entry.getKey(),hp);
            if(last!=null&&last!=hp)emit(transportLog,map("kind","damage","transportId",entry.getKey(),"hp",hp,"maxHp",unit.maxHealth,"delta",hp-last));
            Set<Long> now=new LinkedHashSet<>();for(Object item:unit.getTransportedUnitList())now.add(((BaseUnit)item).objectId);
            Set<Long> before=cargo.getOrDefault(entry.getKey(),Set.of());
            if(!now.equals(before)){emit(transportLog,map("kind","cargo","transportId",entry.getKey(),"cargo",new ArrayList<>(now),"delta",now.size()-before.size()));cargo.put(entry.getKey(),now);}
        }
        for(String type:targetTypes){int own=0;for(BaseUnit unit:BaseUnit.bE)if(MicroRuntime.alive(unit)&&unit.team!=null&&unit.team.teamId==Case3Transport.SELF_TEAM&&unit.r()!=null&&type.equals(unit.r().v()))own++;
            if(own>produced.getOrDefault(type,0)){emit(productionLog,map("kind","order","event","completed","unitType",type,"count",own));produced.put(type,own);}}
        if(!goalReached&&Case3Transport.goalReached(meta,counts)){goalReached=true;goalReachedGameMs=engine.gameTimeMillis;
            emit(goalLog,map("kind","reached","gameMs",engine.gameTimeMillis,"tick",engine.currentTick,"counts",counts));run.end("success","target-delivered");return;}
        if(aliveTransports==0){run.end("defeat","transports-lost");return;}
    }
    public Map<String,Object> state() {
        Map<String,Object> s=stamp();
        int alive=0;for(OrderableUnit unit:transports.values())if(unit!=null&&MicroRuntime.alive(unit))alive++;
        s.putAll(map("status","ok","phase",run.phase,"task",run.config.task,"credits",engine.playerTeam==null?0:engine.playerTeam.credits,
                "incomePerSecond",engine.playerTeam==null?0:engine.playerTeam.b(com.corrodinggames.rts.game.units.custom.condition.resources.Resource.D),
                "remainingGameMs",Math.max(0,run.config.gameLimitSeconds*1000-engine.gameTimeMillis),
                "remainingWallMs",run.remainingWall()*1000,"transportAliveCount",alive,"transportCount",transports.size(),
                "targetReached",goalReached,"outcome",run.outcome));
        return s;
    }
    public Map<String,Object> goalState() {
        Map<String,Integer> counts=Case3Transport.delivered(engine,meta);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> need:requirements){String type=(String)need.get("type");int required=((Number)need.get("required")).intValue();
            int done=counts.getOrDefault(type,0);rows.add(map("type",type,"required",required,"delivered",done,"remaining",Math.max(0,required-done)));}
        Map<String,Object> s=stamp();s.putAll(map("status","ok","targetIsland",map("area",meta.get("targetIslandArea")),"requirements",rows,
                "deliveredCounts",counts,"targetReached",Case3Transport.goalReached(meta,counts)));
        return s;
    }
    public Map<String,Object> productionState() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var entry:factories.entrySet()){
            OrderableUnit unit=entry.getValue();
            int queueSize=0;
            if(unit!=null&&unit instanceof FactoryQueueInterface factory){
                var items=factory.dx();
                queueSize=items==null?0:items.size();
            }
            rows.add(map("factoryId",entry.getKey(),"factoryType",unit==null?null:unit.r().v(),"alive",unit!=null&&MicroRuntime.alive(unit),
                    "busy",unit!=null&&unit instanceof FactoryQueueInterface factory&&!factory.dy(),"queueSize",queueSize));
        }
        Map<String,Object> s=stamp();s.putAll(map("status","ok","factories",rows,"producedCounts",produced,
                "allowedUnitTypes",new ArrayList<>(targetTypes)));return s;
    }
    public Map<String,Object> fleetState() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var entry:transports.entrySet()){
            OrderableUnit unit=entry.getValue();int capacity=((Number)meta.get("transportCapacity")).intValue();
            List<Long> cargo=new ArrayList<>();if(unit!=null)for(Object item:unit.getTransportedUnitList())cargo.add(((BaseUnit)item).objectId);
            int occupied=unit==null?0:used(unit);
            rows.add(map("id",entry.getKey(),"type",transportTypes.get(entry.getKey()),"x",unit==null?null:unit.posX,"y",unit==null?null:unit.posY,
                    "hp",unit==null?null:unit.currentHealth,"maxHp",unit==null?null:unit.maxHealth,"alive",unit!=null&&MicroRuntime.alive(unit),
                    "capacity",capacity,"usedSlots",occupied,"freeSlots",Math.max(0,capacity-occupied),"cargo",cargo,
                    "order",unit==null||unit.getCurrentWaypoint()==null?"idle":unit.getCurrentWaypoint().getCommandType().name()));
        }
        Map<String,Object> s=stamp();s.putAll(map("status","ok","transports",rows));return s;
    }
    public Map<String,Object> result() {
        Map<String,Object> s=state();
        Map<String,Integer> counts=Case3Transport.delivered(engine,meta);
        s.putAll(map("deliveredCounts",counts,"producedCounts",produced,"goalReached",goalReached,
                "goalReachedGameMs",goalReachedGameMs<0?null:goalReachedGameMs,"goalReachedWallMs",goalReached?run.wall():null));
        return s;
    }
    public void finish(String outcome) { }
}
