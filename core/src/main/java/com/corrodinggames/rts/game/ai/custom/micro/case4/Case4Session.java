package com.corrodinggames.rts.game.ai.custom.micro.case4;

import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.*;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.app.MicroJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * Case-local authority, order handling and battle evidence for CASE4-Retreat. The agent only ever
 * sees the generic objective: turret types, counts and coordinates stay in the private meta.
 */
public final class Case4Session extends AbstractCaseSession {
    /** A repair session shorter than this is not evidence (draft: strictly more than 5 game seconds). */
    private static final long MIN_REPAIR_SESSION_MS=5000;
    /** RepairBay.y() = KEYCODE_TV_DATA_SERVICE: the bay heals friendly units within 230 world units. */
    static final float REPAIR_RANGE=230f;
    private final Map<Long,OrderableUnit> tanks=new LinkedHashMap<>(),bays=new LinkedHashMap<>(),turrets=new LinkedHashMap<>();
    private final Map<Long,Float> turretHp=new LinkedHashMap<>(),tankHp=new LinkedHashMap<>(),tankShield=new LinkedHashMap<>();
    private final Map<Long,Long> sessionStart=new LinkedHashMap<>(),sessionLastHeal=new LinkedHashMap<>();
    private final Map<Long,Float> sessionStartHp=new LinkedHashMap<>();
    private final Map<Long,Set<Long>> sessionBays=new LinkedHashMap<>();
    private final BufferedWriter turretDamage,unitDamage,turretDestroyed,repairSessions;
    private long goalReachedGameMs=-1;
    private boolean goalReached;
    @SuppressWarnings("unchecked") public Case4Session(MicroRuntime run,Map<String,Object> meta)throws IOException {
        super(run,meta);
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("tanks"))
            tanks.put(((Number)row.get("id")).longValue(),run.units.get(((Number)row.get("id")).longValue()));
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("repairBays"))
            bays.put(((Number)row.get("id")).longValue(),run.units.get(((Number)row.get("id")).longValue()));
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("turrets"))
            turrets.put(((Number)row.get("id")).longValue(),run.units.get(((Number)row.get("id")).longValue()));
        turretDamage=Files.newBufferedWriter(run.writer.directory.resolve("engine/turret_damage.jsonl"));
        unitDamage=Files.newBufferedWriter(run.writer.directory.resolve("engine/unit_damage.jsonl"));
        turretDestroyed=Files.newBufferedWriter(run.writer.directory.resolve("engine/turret_destroyed.jsonl"));
        repairSessions=Files.newBufferedWriter(run.writer.directory.resolve("engine/repair_sessions.jsonl"));
    }
    protected BufferedWriter nativeLog(){return unitDamage;}
    protected void closeLogs()throws IOException{turretDamage.close();unitDamage.close();turretDestroyed.close();repairSessions.close();}
    public void requestFinished(MicroRuntime.Request q,boolean error,String status) {
        if(q!=null&&q.kind.equals("B")&&error)emit(unitDamage,map("kind","request","requestId",q.seq,"event","rejected","reason",status));
    }
    private final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameCommands delegate=
            new com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameCommands(
                    new com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxEngineLocalGameService());
    public String execute(Command command,List<String> args)throws Exception {
        if(command==Command.MAP_SHOW||command==Command.UNIT_LIST||command==Command.UNIT_SHOW
                ||command==Command.MAP_REACH||command==Command.THREATS)
            return delegate.execute(command,args);
        if(command==Command.RETREAT_STATE) {
            if(!args.isEmpty()&&!args.equals(List.of("--json")))throw new IllegalArgumentException("invalid_request: expected --json");
            Map<String,Object> row=state();
            return args.isEmpty()?"ok: observation\n"+MicroJson.encode(row):MicroJson.encode(row);
        }
        if(command==Command.RETREAT_BEGIN)return begin();
        // These reuse full-game command names, so they are handled here rather than by the
        // service — and they must therefore do the game-thread hand-off themselves.
        if(command==Command.ORDER_MOVE)return MicroRuntime.gameThread(()->order(args,"move"));
        if(command==Command.ORDER_ATTACK_MOVE)return MicroRuntime.gameThread(()->order(args,"attack-move"));
        if(command==Command.ORDER_ATTACK)return MicroRuntime.gameThread(()->order(args,"attack"));
        if(command==Command.ORDER_STOP)return MicroRuntime.gameThread(()->order(args,"stop"));
        throw new IllegalArgumentException("unknown command");
    }
    private String begin() {
        if(!run.phase.equals("briefing"))throw new IllegalArgumentException("invalid_request: already_begun");
        run.phase="running";engine.gameSpeed=1f;run.lifecycle("begin","command");
        return "ok: begun t="+MicroRuntime.time()+" tick="+engine.currentTick;
    }
    private OrderableUnit tank(String token) {
        long id;try{id=Long.parseLong(token);}catch(NumberFormatException e){throw new IllegalArgumentException("invalid_request: invalid_unit_id");}
        OrderableUnit unit=tanks.get(id);
        if(unit!=null)return unit;
        // A real but non-case actor (a repair bay, an enemy turret, anything else) is a violation;
        // an id that does not exist at all is only an ordinary argument error.
        for(BaseUnit other:BaseUnit.bE)if(other!=null&&other.objectId==id){
            reject("order",List.of(id),id,"non_case_actor","unit_not_in_case");
            throw new IllegalArgumentException("violation: non_case_actor");
        }
        throw new IllegalArgumentException("invalid_request: unit_not_owned");
    }
    private String order(List<String> args,String kind) {
        running();
        List<String> units=new ArrayList<>();
        Float x=null,y=null;Long target=null;
        for(int i=0;i<args.size();i+=2){
            String key=args.get(i);
            if(i+1>=args.size())throw new IllegalArgumentException("invalid_request: expected value after "+key);
            String value=args.get(i+1);
            switch(key){
                case "--units"->{for(String token:value.split(","))if(!token.isBlank())units.add(token);}
                case "--x"->x=Float.parseFloat(value);
                case "--y"->y=Float.parseFloat(value);
                case "--target"->target=Long.parseLong(value);
                default->throw new IllegalArgumentException("invalid_request: unexpected option "+key);
            }
        }
        if(units.isEmpty())throw new IllegalArgumentException("invalid_request: expected_units");
        List<OrderableUnit> actors=new ArrayList<>();
        for(String token:units){
            OrderableUnit unit=tank(token);
            if(!MicroRuntime.alive(unit))throw new IllegalArgumentException("invalid_request: unit_not_owned");
            actors.add(unit);
        }
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.actor=actors.get(0).objectId;i.kind=kind;i.unit=actors.get(0);
        i.units=actors.stream().map(unit->unit.objectId).toList();
        if(kind.equals("stop")){
            if(target!=null||x!=null||y!=null)throw new IllegalArgumentException("invalid_request: unexpected_option");
            var nativeCommand=engine.commandController.newCommandForTeam(actors.get(0).team);
            for(OrderableUnit unit:actors)nativeCommand.addUnitToCommand(unit);
            nativeCommand.setClearExistingOrders();
            permitted.put(nativeCommand,i);
            emit(unitDamage,map("kind","order","requestId",i.request,"event","issued","operation","stop","units",i.units));
            return "ok: submitted requestId="+i.request;
        }
        if(kind.equals("attack")){
            if(target==null)throw new IllegalArgumentException("invalid_request: expected --target");
            BaseUnit victim=null;
            for(BaseUnit unit:BaseUnit.bE)if(unit!=null&&unit.objectId==target)victim=unit;
            // Hidden and absent targets answer identically, so an error cannot probe enemy positions.
            if(victim==null||!MicroRuntime.alive(victim)||!victim.d(engine.playerTeam))
                throw new IllegalArgumentException("invalid_request: target_not_visible");
            i.target=victim.objectId;
            var nativeCommand=engine.commandController.newCommandForTeam(actors.get(0).team);
            for(OrderableUnit unit:actors)nativeCommand.addUnitToCommand(unit);
            nativeCommand.setAttackTarget(victim);
            permitted.put(nativeCommand,i);
            emit(unitDamage,map("kind","order","requestId",i.request,"event","issued","operation","attack","units",i.units,"target",i.target));
            return "ok: submitted requestId="+i.request;
        }
        if(x==null||y==null)throw new IllegalArgumentException("invalid_request: invalid_coordinate");
        if(x<0||y<0||x>Case4Map.SIZE*Case4Map.TILE||y>Case4Map.SIZE*Case4Map.TILE)
            throw new IllegalArgumentException("invalid_request: invalid_coordinate");
        var nativeCommand=engine.commandController.newCommandForTeam(actors.get(0).team);
        for(OrderableUnit unit:actors)nativeCommand.addUnitToCommand(unit);
        if(kind.equals("move"))nativeCommand.setMoveTarget(x,y); else nativeCommand.setAttackMoveTarget(x,y);
        permitted.put(nativeCommand,i);
        emit(unitDamage,map("kind","order","requestId",i.request,"event","issued","operation",kind,"units",i.units,"x",x,"y",y));
        return "ok: submitted requestId="+i.request;
    }
    /** Stop is the only order that may clear existing orders; the rest are plain waypoints. */
    protected boolean matches(com.corrodinggames.rts.gameFramework.Command command,Instruction i) {
        if(i.kind.equals("stop"))return command.clearExistingOrders&&command.unitCommand==null;
        if(command.clearExistingOrders)return false;
        if(i.kind.equals("move"))return command.unitCommand!=null&&command.unitCommand.getCommandType()==UnitCommandType.move;
        if(i.kind.equals("attack-move"))return command.unitCommand!=null&&command.unitCommand.getCommandType()==UnitCommandType.attackMove;
        if(i.kind.equals("attack"))return command.unitCommand!=null&&command.unitCommand.getCommandType()==UnitCommandType.attack
                &&command.unitCommand.getTargetUnit()!=null&&command.unitCommand.getTargetUnit().objectId==i.target;
        return false;
    }
    /** Real damage hook: the engine reports HP/shield before the hit together with the attacker. */
    public void damage(BaseUnit victim,BaseUnit attacker,float healthBefore,float shieldBefore) {
        if(!run.phase.equals("running"))return;
        Map<String,Object> row=map("victim",victim.objectId,"attacker",attacker==null?null:attacker.objectId,
                "attackerType",attacker==null||attacker.r()==null?null:attacker.r().v(),
                "hpBefore",healthBefore,"hpAfter",victim.currentHealth,"delta",victim.currentHealth-healthBefore,
                "shieldBefore",shieldBefore,"shieldAfter",victim.shield,
                "x",victim.posX,"y",victim.posY,"victimType",victim.r()==null?null:victim.r().v());
        if(turrets.containsKey(victim.objectId))emit(turretDamage,row);
        else if(tanks.containsKey(victim.objectId))emit(unitDamage,row);
    }
    public void afterTick() {
        if(!run.phase.equals("running"))return;
        for(var entry:turrets.entrySet()){
            OrderableUnit turret=entry.getValue();
            boolean alive=MicroRuntime.alive(turret);
            Float last=turretHp.put(entry.getKey(),alive?turret.currentHealth:0f);
            if(last!=null&&last>0&&!alive)
                emit(turretDestroyed,map("turretId",entry.getKey(),"turretType",turret.r()==null?null:turret.r().v(),
                        "x",turret.posX,"y",turret.posY,"tanks",snapshot()));
        }
        long now=engine.gameTimeMillis;
        for(var entry:tanks.entrySet()){
            OrderableUnit tank=entry.getValue();
            if(!MicroRuntime.alive(tank)){
                tankHp.remove(entry.getKey());closeSession(entry.getKey(),now,"dead");continue;
            }
            float hp=tank.currentHealth;
            Float last=tankHp.put(entry.getKey(),hp);
            float shield=tank.shield;Float lastShield=tankShield.put(entry.getKey(),shield);
            Set<Long> inRange=baysInRange(tank);
            boolean healed=last!=null&&(hp>last||(shield>lastShield+0.01f&&hp>=tank.maxHealth));
            if(healed&&!inRange.isEmpty()){
                if(!sessionStart.containsKey(entry.getKey())){
                    sessionStart.put(entry.getKey(),now);sessionStartHp.put(entry.getKey(),last==null?hp:last);
                    sessionBays.put(entry.getKey(),new LinkedHashSet<>());
                }
                sessionLastHeal.put(entry.getKey(),now);
                sessionBays.get(entry.getKey()).addAll(inRange);
            } else if(sessionStart.containsKey(entry.getKey())&&now-(sessionLastHeal.getOrDefault(entry.getKey(),now))>1000)
                closeSession(entry.getKey(),now,"idle");
        }
        if(!goalReached&&Case4Retreat.goalReached(engine,meta)){
            goalReached=true;goalReachedGameMs=engine.gameTimeMillis;
            emit(turretDestroyed,map("kind","reached","gameMs",engine.gameTimeMillis,"tick",engine.currentTick,"tanks",snapshot()));
            run.end("success","all-turrets-destroyed");return;
        }
        int aliveTanks=0;for(OrderableUnit tank:tanks.values())if(MicroRuntime.alive(tank))aliveTanks++;
        if(aliveTanks==0){run.end("defeat","tanks-lost");return;}
    }
    private Set<Long> baysInRange(OrderableUnit tank) {
        Set<Long> out=new LinkedHashSet<>();
        for(var entry:bays.entrySet()){
            OrderableUnit bay=entry.getValue();
            if(!MicroRuntime.alive(bay))continue;
            double distance=Math.hypot(bay.posX-tank.posX,bay.posY-tank.posY);
            if(distance<=REPAIR_RANGE)out.add(entry.getKey());
        }
        return out;
    }
    private void closeSession(long unit,long now,String reason) {
        Long start=sessionStart.remove(unit);if(start==null)return;
        Float startHp=sessionStartHp.remove(unit);
        OrderableUnit tank=tanks.get(unit);
        Set<Long> used=sessionBays.remove(unit);sessionLastHeal.remove(unit);
        long duration=now-start;
        if(duration<=MIN_REPAIR_SESSION_MS)return;
        emit(repairSessions,map("unitId",unit,"repairBayIds",used==null?List.of():new ArrayList<>(used),
                "startGameMs",start,"endGameMs",now,"durationMs",duration,"startHp",startHp,
                "endHp",tank==null?null:tank.currentHealth,"maxHp",tank==null?null:tank.maxHealth,
                "healedHp",tank==null?null:Math.max(0f,tank.currentHealth-(startHp==null?tank.currentHealth:startHp)),
                "reason",reason));
    }
    private List<Map<String,Object>> snapshot() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var entry:tanks.entrySet()){
            OrderableUnit tank=entry.getValue();
            rows.add(map("unitId",entry.getKey(),"alive",MicroRuntime.alive(tank),"hp",tank.currentHealth,
                    "maxHp",tank.maxHealth,"shield",tank.shield,"x",tank.posX,"y",tank.posY));
        }
        return rows;
    }
    public Map<String,Object> state() {
        Map<String,Object> s=stamp();
        int alive=0;for(OrderableUnit tank:tanks.values())if(MicroRuntime.alive(tank))alive++;
        s.putAll(map("status","ok","phase",run.phase,"task",run.config.task,
                "credits",engine.playerTeam==null?0:engine.playerTeam.credits,
                "remainingGameMs",Math.max(0,run.config.gameLimitSeconds*1000-engine.gameTimeMillis),
                "remainingWallMs",run.remainingWall()*1000,
                "goal",map("description","destroy every enemy turret in the top-left region and keep the tanks repaired",
                        "reached",goalReached),
                "tanksAlive",alive,"tanksTotal",tanks.size(),"outcome",run.outcome));
        return s;
    }
    public Map<String,Object> result() {
        Map<String,Object> s=state();
        s.putAll(map("goalReached",goalReached,"goalReachedGameMs",goalReachedGameMs<0?null:goalReachedGameMs,
                "goalReachedWallMs",goalReached?run.wall():null,"tanks",snapshot(),
                "turretsAlive",countAlive(turrets),"turretsTotal",turrets.size()));
        return s;
    }
    private static int countAlive(Map<Long,OrderableUnit> units) {
        int n=0;for(OrderableUnit unit:units.values())if(MicroRuntime.alive(unit))n++;return n;
    }
    public void finish(String outcome) {
        long now=engine.gameTimeMillis;
        for(Long unit:new ArrayList<>(sessionStart.keySet()))closeSession(unit,now,"finished");
    }
}
