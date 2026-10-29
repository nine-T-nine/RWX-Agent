package evaluate.utils;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.*;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.buildings.Projectile;
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig;
import com.corrodinggames.rts.gameFramework.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Optional, read-only simulation evidence. Enable with -Drwx.evaluate.dir=<directory>. */
public final class EngineEvidence extends UnitEventListener {
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();
    private static final Map<Command, Origin> ORIGINS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile EngineEvidence current;
    private static final Map<Command,Integer> REPLAY_ORDINALS = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean disabled;
    private static int matchNumber;
    private static long requestNumber, nativeNumber;
    private static Object endedMap;
    private static long endedTime=-1;
    private final GameEngine engine;
    private final Object map;
    private final PlayerTeam team;
    private final BufferedWriter output, economy;
    private final double radius;
    private final long regionHold;
    private final Map<Long, BaseUnit> monitored = new LinkedHashMap<>();
    private final Map<Long, Long> watched = new HashMap<>();
    private final Map<Long, String> signatures = new HashMap<>();
    private final Map<Long, Anchor> anchors = new LinkedHashMap<>();
    private final Map<Long, Long> deathTimes = new HashMap<>();
    private final Map<Long, Long> deathSequences = new HashMap<>();
    private final Set<Long> removed = new HashSet<>();
    private long sequence, lastTime, nextSample;
    private int economyColumn;
    private boolean closed;

    private record Origin(Request request, String source) { }
    public record Request(String id, long timeMs, long tick, String name, List<String> args) { }
    public record Application(String id, Origin origin, List<Long> units,
                              Map<Long, Map<String,Object>> before) { }
    private static final class Anchor {
        final long id, created; final float x,y; long hostile;
        Anchor(BaseUnit u,long time){id=u.objectId;x=u.posX;y=u.posY;created=hostile=time;}
    }
    private EngineEvidence(GameEngine engine, PlayerTeam team, Path directory) throws IOException {
        this.engine=engine;this.map=engine.tileMap;this.team=team;
        Files.createDirectories(directory);
        Path target;
        do {target=directory.resolve(String.format(Locale.ROOT,"match-%03d-team%d",++matchNumber,team.teamId));}
        while(Files.exists(target));
        Files.createDirectory(target);
        output=Files.newBufferedWriter(target.resolve("telemetry.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        economy=Files.newBufferedWriter(target.resolve("economy.log"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        double[] ranges={range(UnitTypeEnum.tank),range(UnitTypeEnum.heavyTank),range(UnitTypeEnum.laserTank)};
        Arrays.sort(ranges);radius=Double.parseDouble(System.getProperty("rwx.evaluate.X",Double.toString(ranges[1])));
        regionHold=Long.getLong("rwx.evaluate.regionHoldMs",5000L);
        if(!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("rwx.evaluate.X must be positive");
        lastTime=engine.gameTimeMillis;nextSample=(lastTime+999)/1000*1000;
        write("meta",data("schema",1,"team",team.teamId,"X",radius,"source",engine.replayEngine.j()?"replay":"live",
                "map",engine.getCurrentMapName(),"incomeField","PlayerTeam.getScaledIncomeRate",
                "incomeUnit","credits per engine resourceIncomeRatePeriod (40 simulation units), scaled as HUD",
                "startComplete",lastTime==0,"regionHoldMs",regionHold));
        if(engine.replayEngine.j() && System.getProperty("rwx.evaluate.replaySpeed")!=null)
            engine.gameSpeed=Float.parseFloat(System.getProperty("rwx.evaluate.replaySpeed"));
        try {
            GameStatistics.f.addObserver(this);
            for(BaseUnit unit:BaseUnit.getGlobalUnitList())register(unit);
            sample();
            System.out.println("RWX_EVALUATION_READY "+target.toAbsolutePath());
        } catch(IOException|RuntimeException error) {
            GameStatistics.f.removeObserver(this);
            try{output.close();}catch(IOException ignored){}
            try{economy.close();}catch(IOException ignored){}
            throw error;
        }
    }
    private static double range(UnitType type){BaseUnit prototype=BaseUnit.findTurretPosition(type);return prototype instanceof OrderableUnit?((OrderableUnit)prototype).m():160;}
    private static boolean enabled(){return !disabled&&System.getProperty("rwx.evaluate.dir")!=null;}
    private static synchronized EngineEvidence session() {
        if(!enabled())return null;
        GameEngine e=GameEngine.getInstance();
        if(e==null||!e.hasLoadedLevel||e.isStopped||e.isMenuBackgroundMap)return null;
        int target=Integer.getInteger("rwx.evaluate.team",e.playerTeam==null?-1:e.playerTeam.teamId);
        PlayerTeam t=PlayerTeam.k(target);
        if(e.tileMap==endedMap&&e.gameTimeMillis<endedTime)endedMap=null;
        if(t==null||t.teamId<0||e.tileMap==endedMap)return null;
        if(current!=null&&(current.engine!=e||current.map!=e.tileMap||current.team!=t||e.gameTimeMillis<current.lastTime))close("new_match");
        if(current==null)try{current=new EngineEvidence(e,t,Path.of(System.getProperty("rwx.evaluate.dir")));}
        catch(IOException|RuntimeException error){disabled=true;System.err.println("Evaluation disabled: "+error);}
        return current;
    }
    public static void beforeTick(){session();}
    public static void observeObject(GameObject object){
        if(current!=null&&object instanceof BaseUnit)current.register((BaseUnit)object);
    }
    public static void afterTick(){
        EngineEvidence s=current;if(s==null)return;
        try{
            s.frame();
            if(!s.engine.replayEngine.j()&&s.resultEnded()){
                endedMap=s.map;endedTime=s.engine.gameTimeMillis;close("match_result");
            }
        }catch(IOException|RuntimeException error){fail(error);}
    }
    public static synchronized void close(String reason){
        EngineEvidence s=current;if(s==null)return;
        try{s.write("end",data("reason",reason));s.output.flush();
            if(s.economyColumn>0)s.economy.newLine();s.economy.close();s.output.close();}
        catch(IOException error){System.err.println("Evaluation close failed: "+error);}
        finally{s.closed=true;GameStatistics.f.removeObserver(s);current=null;ORIGINS.clear();REPLAY_ORDINALS.clear();}
    }
    public static void replayEnded(){
        if(current!=null){endedMap=current.map;endedTime=current.engine.gameTimeMillis;}
        close("replay_end");
        // Shutdown hooks also visit the simulation state. Exit after this tick releases its lock.
        if(Boolean.getBoolean("rwx.evaluate.exitOnReplayEnd"))
            java.util.concurrent.CompletableFuture.delayedExecutor(10,java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(()->System.exit(0));
    }
    public static void replayCommand(Command command,int ordinal){if(current!=null)REPLAY_ORDINALS.put(command,ordinal);}
    private static void fail(Exception error){System.err.println("Evaluation observation failed: "+error);close("observation_error");disabled=true;}
    public static void lifecycle(){
        if(current!=null&&(!current.engine.hasLoadedLevel||current.engine.isStopped||current.engine.isMenuBackgroundMap))close("match_end");
    }
    private static boolean defeated(PlayerTeam t){return t.isTeamVictory||t.isTeamDefeated||t.isTeamDefeatedTech||t.isTeamWipedOut;}
    private boolean resultEnded(){
        if(defeated(team))return true;
        boolean enemy=false;
        for(PlayerTeam other:PlayerTeam.getTeams())if(other.teamId>=0&&other.teamId<PlayerTeam.TEAM_NEUTRAL&&team.c(other)){
            enemy=true;if(!defeated(other))return false;
        }
        return enemy;
    }
    public static boolean eligible(BaseUnit unit){
        if(unit==null||unit.r()==null||unit.team==null||unit.team.teamId<0)return false;
        if(unit.getClass().getPackageName().contains(".bug"))return false;
        if(unit.r() instanceof CustomUnitConfig){CustomUnitConfig c=(CustomUnitConfig)unit.r();return c.modInfo==null&&!c.isBio;}
        return unit.r()!=UnitTypeEnum.tree&&unit.r()!=UnitTypeEnum.crystalResource;
    }
    private void register(BaseUnit unit){
        if(!eligible(unit)||unit.isDead||unit.isDestroyed)return;
        if(!monitored.containsKey(unit.objectId)){
            monitored.put(unit.objectId,unit);
            try{unit(unit,true);}catch(IOException|RuntimeException error){fail(error);}
        }
    }
    private String side(BaseUnit unit){return unit.team==team?"self":team.c(unit.team)?"enemy":"ally";}
    private static String type(BaseUnit unit){return unit.r().v();}
    private static boolean builder(BaseUnit unit){
        return Set.of("builder","builderShip","mechEngineer","combatEngineer","experimentalSpider",
                "experimentalGunship","experimentalGunshipLanded","modularSpider","modularSpider_nonEmpty").contains(type(unit));
    }
    private static boolean produces(AbstractUnitAction action){
        UnitType type=action.getUnitType();
        return action.getActionType()==ActionType.popupQueue&&type!=null&&!type.isBuildingUnit()
                &&action.getActionDisplayType()!=ActionDisplayType.upgrade;
    }
    private static List<Map<String,Object>> queue(BaseUnit unit){
        List<Map<String,Object>> rows=new ArrayList<>();
        if(unit instanceof FactoryQueueInterface){FactoryQueueInterface f=(FactoryQueueInterface)unit;
            if(f.dx()!=null)for(Object raw:f.dx()){
                Projectile q=(Projectile)raw;AbstractUnitAction action=q.j==null?null:((OrderableUnit)unit).validateActionId(q.j);
                rows.add(data("action",q.j==null?null:q.j.getId(),"kind",action!=null&&produces(action)?"produce":"upgrade_or_action",
                        "type",q.unitType==null?null:q.unitType.v(),"count",q.launchDelay));
            }
        }
        return rows;
    }
    private Map<String,Object> snapshot(BaseUnit unit,boolean options){
        OrderableUnit u=unit instanceof OrderableUnit?(OrderableUnit)unit:null;
        UnitCommand w=u==null?null:u.getCurrentWaypoint();
        List<Map<String,Object>> queue=queue(unit);
        boolean weapon=u!=null&&u.canAttack();
        boolean idle=w==null&&queue.isEmpty()&&(u==null||u.attackTarget==null)
                &&unit.attackTargetUnit==null&&unit.buildProgress>=1&&unit.transportContainer==null;
        if(u!=null&&engine.gameTimeMillis-u.lastWeaponFireMillis<=1000&&u.lastWeaponFireMillis>=0)idle=false;
        Map<String,Object> actions=new LinkedHashMap<>();boolean factory=false;
        if(u!=null)for(Object raw:u.getAvailableActions()){
            AbstractUnitAction action=(AbstractUnitAction)raw;
            boolean production=produces(action);factory|=production;
            if(options&&(production||builder(unit)&&action.getUnitType()!=null)){
                actions.put(action.getActionIdString(),data("kind",production?"produce":"build",
                        "type",action.getUnitType().v(),"cost",action.getPrice().a(),
                        "available",unit.buildProgress>=1&&!action.isNotAvailable(unit)&&action.b(unit),
                        "affordable",action.canAfford(unit,false)));
            }
        }
        Map<String,Object> task=w==null?null:data("type",w.getCommandType().name(),"targetId",w.getTargetUnit()==null?null:w.getTargetUnit().objectId,
                "x",w.getTargetX(),"y",w.getTargetY(),"buildType",w.getBuildUnitType()==null?null:w.getBuildUnitType().v(),"count",w.getBuildQueueSize());
        Map<String,Object> result=data("id",unit.objectId,"life",0,"type",type(unit),"side",side(unit),
                "x",unit.posX,"y",unit.posY,"alive",!unit.isDead&&!unit.isDestroyed,"complete",unit.buildProgress>=1,
                "visible",unit.d(team),"canAttack",weapon,"idle",idle,"building",unit.bI(),
                "mobile",!unit.bI(),"builder",builder(unit),"factory",factory,"task",task,"queue",queue,
                "capRemaining",Math.max(0,team.getUnitCap()-team.getNonBuildingUnitCountIncludingQueued()));
        result.put("waypointCount",u==null?0:u.getWaypointCount());
        if(options){
            result.put("actions",actions);
            List<Long> repairable=new ArrayList<>();
            if(builder(unit)&&u!=null)for(BaseUnit candidate:BaseUnit.getGlobalUnitList())
                if(eligible(candidate)&&!candidate.isDead&&!team.c(candidate.team)&&candidate.buildProgress>=1
                        &&candidate.currentHealth<candidate.maxHealth&&u.canRepairTarget(candidate))repairable.add(candidate.objectId);
            result.put("repairable",repairable);
        }
        return result;
    }
    private void unit(BaseUnit unit,boolean force) throws IOException {
        Map<String,Object> state=snapshot(unit,false);
        String signature=json(state);
        if(force||!signature.equals(signatures.get(unit.objectId))){
            signatures.put(unit.objectId,signature);
            if(Boolean.TRUE.equals(state.get("idle"))&&(builder(unit)||Boolean.TRUE.equals(state.get("factory"))))state=snapshot(unit,true);
            write("unit",data("unit",state,"funds",team.credits));
        }
    }
    private void frame() throws IOException {
        lastTime=engine.gameTimeMillis;
        for(Iterator<Map.Entry<Long,BaseUnit>> it=monitored.entrySet().iterator();it.hasNext();){
            Map.Entry<Long,BaseUnit> entry=it.next();BaseUnit u=entry.getValue();
            if(u.isDead||u.isDestroyed){unit(u,false);it.remove();watched.remove(entry.getKey());continue;}
            if(u.team==team&&(builder(u)||u instanceof FactoryQueueInterface)||watched.getOrDefault(u.objectId,0L)>=lastTime)unit(u,false);
        }
        watched.entrySet().removeIf(e->e.getValue()<lastTime);
        for(Iterator<Anchor> it=anchors.values().iterator();it.hasNext();){
            Anchor a=it.next();List<Map<String,Object>> near=nearby(a.x,a.y,-1);
            boolean enemy=near.stream().anyMatch(u->"enemy".equals(u.get("side")));
            boolean friend=near.stream().anyMatch(u->!"enemy".equals(u.get("side")));
            if(enemy)a.hostile=lastTime;
            String signature=json(near);long cacheKey=-a.id;
            if(!signature.equals(signatures.put(cacheKey,signature)))write("region",data("anchorId",a.id,"nearby",near));
            if(lastTime-a.created>=regionHold&&(friend||!enemy&&lastTime-a.hostile>=regionHold)){it.remove();signatures.remove(cacheKey);}
        }
        sample();write("frame",data());
    }
    private List<Map<String,Object>> nearby(float x,float y,long exclude){return nearby(x,y,exclude,false);}
    private List<Map<String,Object>> nearby(float x,float y,long exclude,boolean exact){
        List<Map<String,Object>> result=new ArrayList<>();
        if(!exact&&engine.unitSpatialIndex.e==null)return result;
        Iterable<BaseUnit> candidates=exact?BaseUnit.getGlobalUnitList():engine.unitSpatialIndex.a(x,y,(float)radius);
        for(BaseUnit u:candidates){
            if(u.objectId==exclude||!eligible(u)||u.isDead||u.isDestroyed)continue;
            if(Math.hypot(u.posX-x,u.posY-y)<=radius)result.add(snapshot(u,false));
        }
        result.sort(Comparator.comparingLong(u->((Number)u.get("id")).longValue()));return result;
    }
    private void sample() throws IOException {
        // First simulation state on/after each fixed second. Never duplicate a sample during pause.
        if(engine.gameTimeMillis-nextSample>=1000){
            write("sampling_gap",data("nextSampleMs",nextSample,"resumedSampleMs",(long)engine.gameTimeMillis/1000*1000));
            nextSample=(long)engine.gameTimeMillis/1000*1000;
        }
        while(nextSample<=engine.gameTimeMillis){
            double funds=team.credits;int income=team.getScaledIncomeRate();
            write("economy",data("sampleMs",nextSample,"funds",funds,"income",income));
            if(economyColumn>0)economy.write(',');
            economy.write(number(funds)+"("+(income>=0?"+":"")+income+")");
            if(++economyColumn==60){economy.newLine();economyColumn=0;}
            economy.flush();output.flush();nextSample+=1000;
        }
    }
    @Override public void a(BaseUnit attacker,BaseUnit victim,Object unused) { }
    @Override public void damaged(BaseUnit victim,BaseUnit attacker){
        if(closed||!eligible(victim)||!eligible(attacker))return;
        try{
            watched.put(victim.objectId,Long.MAX_VALUE);watched.put(attacker.objectId,Long.MAX_VALUE);
            unit(victim,true);unit(attacker,true);
            write("damage",data("victim",snapshot(victim,false),"attacker",snapshot(attacker,false)));
        }catch(IOException|RuntimeException e){fail(e);}
    }
    @Override public void removed(BaseUnit unit,BaseUnit killer){
        if(closed||!eligible(unit)||!removed.add(unit.objectId))return;
        try{
            Map<String,Object> dead=snapshot(unit,false);dead.put("alive",false);
            List<Map<String,Object>> near=unit.team==team?nearby(unit.posX,unit.posY,unit.objectId,true):List.of();
            deathTimes.put(unit.objectId,(long)engine.gameTimeMillis);deathSequences.put(unit.objectId,sequence+1);
            write("death",data("unit",dead,"killer",eligible(killer)?snapshot(killer,false):null,"nearby",near));
            if(unit.team==team){anchors.put(unit.objectId,new Anchor(unit,engine.gameTimeMillis));write("region",data("anchorId",unit.objectId,"nearby",near));}
        }catch(IOException|RuntimeException e){fail(e);}
    }
    @Override public void info(BaseUnit unit,BaseUnit related,String phase){
        if(closed)return;register(unit);if(related!=null)register(related);
        if(eligible(unit))try{write("lifecycle",data("phase",phase,"unitId",unit.objectId,"relatedId",related==null?null:related.objectId));}
        catch(IOException e){fail(e);}
    }
    public static Request beginRequest(String name,List<String> arguments){
        if(!enabled())return null;
        GameEngine e=GameEngine.getInstance();if(e==null)return null;
        synchronized(e.gameStateLock){
            EngineEvidence s=session();if(s==null)return null;
            Request request=new Request("B-"+(++requestNumber),e.gameTimeMillis,e.currentTick,name,List.copyOf(arguments));
            REQUEST.set(request);
            try{
                Map<String,String> opts=options(arguments);List<Long> ids=ids(opts.getOrDefault("--units",opts.get("--unit")));
                Long target=parseId(opts.get("--target"));
                s.write("command",data("id",request.id,"source","agent","name",name,"raw",name+" "+String.join(" ",arguments),
                        "args",arguments,"units",ids,"refs",s.references(ids,target,true),"x",coordinate(opts.get("--x")),"y",coordinate(opts.get("--y")),"targetId",target));
            }catch(IOException|RuntimeException error){fail(error);}
            return request;
        }
    }
    public static void endRequest(String reply){
        Request request=REQUEST.get();REQUEST.remove();if(request==null||current==null)return;
        synchronized(current){try{current.write("result",data("commandId",request.id,"accepted",!reply.startsWith("error:"),"reply",reply));}
        catch(IOException e){fail(e);}}
    }
    public static <T> Callable<T> propagate(Callable<T> work){
        Request request=REQUEST.get();if(request==null)return work;
        return ()->{Request previous=REQUEST.get();REQUEST.set(request);try{return work.call();}finally{if(previous==null)REQUEST.remove();else REQUEST.set(previous);}};
    }
    public static void intent(List<Long> ids,Long target,String operation,Float x,Float y){
        EngineEvidence s=current;Request request=REQUEST.get();if(s==null||request==null)return;
        try{
            List<Map<String,Object>> units=new ArrayList<>();
            for(Long id:ids){BaseUnit u=GameObject.a(id,true);if(eligible(u)&&!u.isDead)units.add(s.snapshot(u,false));}
            s.write("intent",data("commandId",request.id,"units",ids,"refs",s.references(ids,target,false),"operation",operation,
                    "controlled",units,"targetId",target,"x",x,"y",y,
                    "targetUnit",target==null||!eligible(GameObject.a(target,true))?null:s.snapshot(GameObject.a(target,true),false)));
        }catch(IOException|RuntimeException e){fail(e);}
    }
    private List<Map<String,Object>> references(List<Long> ids,Long target,boolean receipt){
        List<Map<String,Object>> refs=new ArrayList<>();
        for(Long id:ids)refs.add(reference(id,"controlled",receipt));if(target!=null)refs.add(reference(target,"target",receipt));return refs;
    }
    private Map<String,Object> reference(long id,String role,boolean receipt){
        BaseUnit u=GameObject.a(id,true);
        String status=u==null?"missing":u.isDead?"dead":u.isDestroyed?"removed":role.equals("controlled")&&u.team!=team?"wrong_team":"valid";
        return data("id",id,"life",0,"role",role,"status",status,"checkedMs",engine.gameTimeMillis,
                "atReceipt",receipt,"eligibleKnown",u!=null||deathTimes.containsKey(id),
                "excluded",u!=null&&!eligible(u),"deathTimeMs",deathTimes.get(id),"deathSeq",deathSequences.get(id));
    }
    public static void tag(Command command,String origin){
        if(current!=null)ORIGINS.put(command,new Origin(REQUEST.get(),"MANUAL".equals(origin)?"agent":origin.toLowerCase(Locale.ROOT)));
    }
    public static Application beforeCommand(Command command,List<Long> ids){
        EngineEvidence s=current;if(s==null)return null;
        if(s.engine.replayEngine.j()&&!command.isReplayCommand)return null;
        try{
            Map<Long,Map<String,Object>> before=new LinkedHashMap<>();
            for(Long id:ids){BaseUnit unit=GameObject.a(id,true);if(eligible(unit))before.put(id,s.snapshot(unit,false));}
            Application a=new Application("N-"+(++nativeNumber),ORIGINS.get(command),List.copyOf(ids),before);
            s.writeNative(command,a,"before",List.of());return a;
        }catch(IOException|RuntimeException e){fail(e);return null;}
    }
    public static void afterCommand(Command command,Application application){
        EngineEvidence s=current;if(s==null||application==null)return;
        try{
            List<Map<String,Object>> results=new ArrayList<>();
            for(Long id:application.units){BaseUnit unit=GameObject.a(id,true);if(!eligible(unit))continue;
                Map<String,Object> before=application.before.get(id),after=s.snapshot(unit,false);
                if(before==null)continue;
                List<Map<String,Object>> added=queueAdded(before,after);
                boolean changed=!Objects.equals(before.get("task"),after.get("task"))||!Objects.equals(before.get("queue"),after.get("queue"))
                        ||!Objects.equals(before.get("waypointCount"),after.get("waypointCount"));
                results.add(data("unitId",id,"life",0,"applied",changed?true:null,"queueAdded",added));
            }
            s.writeNative(command,application,"after",results);
            for(Long id:application.units){BaseUnit u=GameObject.a(id,true);if(eligible(u))s.unit(u,true);}
        }catch(IOException|RuntimeException e){fail(e);}
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> queueAdded(Map<String,Object> before,Map<String,Object> after){
        Map<Object,Integer> old=new HashMap<>();
        for(Map<String,Object> q:(List<Map<String,Object>>)before.get("queue"))old.merge(q.get("action"),((Number)q.get("count")).intValue(),Integer::sum);
        List<Map<String,Object>> added=new ArrayList<>();
        Map<Object,Integer> now=new LinkedHashMap<>();
        for(Map<String,Object> q:(List<Map<String,Object>>)after.get("queue"))now.merge(q.get("action"),((Number)q.get("count")).intValue(),Integer::sum);
        for(Map<String,Object> q:(List<Map<String,Object>>)after.get("queue")){
            int delta=now.getOrDefault(q.get("action"),0)-old.getOrDefault(q.get("action"),0);
            if(delta>0){Map<String,Object> item=new LinkedHashMap<>(q);item.put("count",delta);added.add(item);now.put(q.get("action"),0);}
        }
        return added;
    }
    private void writeNative(Command command,Application a,String phase,List<Map<String,Object>> results) throws IOException {
        UnitCommand w=command.unitCommand;
        write("native",data("nativeId",a.id,"phase",phase,"source",a.origin==null?command.isReplayCommand?"replay_unknown":"engine":a.origin.source,
                "team",command.team==null?-1:command.team.teamId,"replayOrdinal",REPLAY_ORDINALS.get(command),
                "commandId",a.origin==null||a.origin.request==null?null:a.origin.request.id,"units",a.units,
                "operation",w==null?command.clearExistingOrders?"stop":"action":w.getCommandType().name(),
                "action",command.actionId==null?null:command.actionId.getId(),"queued",command.isQueued,"results",results));
    }
    private synchronized void write(String kind,Map<String,Object> fields) throws IOException {
        Map<String,Object> row=data("kind",kind,"timeMs",engine.gameTimeMillis,"tick",engine.currentTick,"seq",++sequence);
        row.putAll(fields);output.write(json(row));output.newLine();
    }
    static Map<String,Object> data(Object...pairs){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put(pairs[i].toString(),pairs[i+1]);return map;}
    private static Map<String,String> options(List<String> args){Map<String,String> out=new HashMap<>();for(int i=0;i+1<args.size();i++)if(args.get(i).startsWith("--"))out.put(args.get(i),args.get(++i));return out;}
    private static Long parseId(String value){try{return value==null?null:Long.valueOf(value);}catch(NumberFormatException e){return null;}}
    private static List<Long> ids(String value){List<Long> out=new ArrayList<>();if(value!=null)for(String token:value.split(",")){Long id=parseId(token.trim());if(id!=null)out.add(id);}return out;}
    private static Double coordinate(String value){try{return value==null?null:Double.valueOf(value);}catch(NumberFormatException e){return null;}}
    static String number(Number n){double v=n.doubleValue();return Double.isFinite(v)?java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString():"null";}
    static String json(Object value){
        if(value==null)return "null";if(value instanceof Boolean)return value.toString();if(value instanceof Number)return number((Number)value);
        if(value instanceof Map<?,?>){StringJoiner j=new StringJoiner(",","{","}");((Map<?,?>)value).forEach((k,v)->j.add(json(k.toString())+":"+json(v)));return j.toString();}
        if(value instanceof Iterable<?>){StringJoiner j=new StringJoiner(",","[","]");for(Object item:(Iterable<?>)value)j.add(json(item));return j.toString();}
        String s=value.toString();StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}
        return out.append('"').toString();
    }
}
