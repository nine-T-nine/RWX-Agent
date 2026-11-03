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
    /** Position-only refresh interval; state transitions are always written immediately. */
    private static final long POSITION_SAMPLE_MS=1000;
    private final GameEngine engine;
    private final Object map;
    private final PlayerTeam team;
    private final BufferedWriter output, economy;
    private final Path telemetryPath;
    private final double radius;
    private final long regionHold;
    private final Map<Long, BaseUnit> monitored = new LinkedHashMap<>();
    /** Deadline (game ms) until which a unit keeps being sampled after it dealt or took damage. */
    private final Map<Long, Long> watched = new HashMap<>();
    /** Last written snapshot time per unit; caps position-only refreshes at one per second. */
    private final Map<Long, Long> positionSamples = new HashMap<>();
    /** Last written position per unit, so a stationary unit never writes a heartbeat row. */
    private final Map<Long, double[]> positions = new HashMap<>();
    private final Map<Long, String> signatures = new HashMap<>();
    private final Map<Long, Anchor> anchors = new LinkedHashMap<>();
    private final Map<Long, Long> deathTimes = new HashMap<>();
    /** Last unit type each factory produced, so an idle pair can name the action it last used. */
    private final Map<Long, String> lastProduct = new HashMap<>();
    private final Map<Long, Long> deathSequences = new HashMap<>();
    private final Set<Long> removed = new HashSet<>();
    private long sequence, lastTime, nextSample;
    private int economyColumn;
    private boolean closed;

    private record Origin(Request request, String source) { }
    public record Request(String id, long timeMs, long tick, String name, List<String> args,
                          double creditsBefore) { }
    public record Application(String id, Origin origin, List<Long> units,
                              Map<Long, Map<String,Object>> before) { }
    private static final class Anchor {
        final long id, created; final float x,y; long hostile;
        Anchor(BaseUnit u,long time){id=u.objectId;x=u.posX;y=u.posY;created=hostile=time;}
    }
    private EngineEvidence(GameEngine engine, PlayerTeam team, Path directory) throws IOException {
        this.engine=engine;this.map=engine.tileMap;this.team=team;
        Files.createDirectories(directory);
        // The counter alone ("match-001-team0") cannot say which match a directory belongs to: it is a
        // JVM-wide ordinal and separate launches share one parent directory. Stamp the wall clock into the
        // name and append a label, so a run is identifiable from a plain listing.
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.ROOT)
                .format(java.time.LocalDateTime.now());
        String runLabel=label(engine);
        String recorded=recordingName(engine);
        Path target;
        do {
            String name=String.format(Locale.ROOT,"match-%03d-%s-team%d",++matchNumber,stamp,team.teamId);
            target=directory.resolve(runLabel==null?name:name+"-"+runLabel);
        } while(Files.exists(target));
        Files.createDirectory(target);
        telemetryPath=target.resolve("telemetry.jsonl");
        output=Files.newBufferedWriter(telemetryPath,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        economy=Files.newBufferedWriter(target.resolve("economy.log"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        double[] ranges={range(UnitTypeEnum.tank),range(UnitTypeEnum.heavyTank),range(UnitTypeEnum.laserTank)};
        Arrays.sort(ranges);radius=Double.parseDouble(System.getProperty("rwx.evaluate.X",Double.toString(ranges[1])));
        regionHold=Long.getLong("rwx.evaluate.regionHoldMs",5000L);
        if(!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("rwx.evaluate.X must be positive");
        lastTime=engine.gameTimeMillis;nextSample=(lastTime+999)/1000*1000;
        write("meta",data("schema",1,"team",team.teamId,"X",radius,"source",engine.replayEngine.j()?"replay":"live",
                "map",engine.getCurrentMapName(),"incomeField","PlayerTeam.getScaledIncomeRate",
                "incomeUnit","credits per resourceIncomeRatePeriod (40 ticks); economy rows also carry "
                        + "incomePerSecond, the same rate converted with the observed clock step",
                "startComplete",lastTime==0,"regionHoldMs",regionHold,
                // The directory name is only as good as the clock it was stamped with, so keep the exact
                // start instant and the requested label in the row: a directory can then be matched to it
                // even after being copied, renamed or merged into another tree.
                "startedAt",java.time.OffsetDateTime.now().toString(),"label",runLabel,
                // The replay is what the operator recognises a match by, and it is also what
                // `tools/match_materials.js` renames the directory after; keep both names in the row.
                "replay",recorded,"gameVersion",engine.getVersionString(),
                // Without these the report cannot tell a slow-clock diagnostic run from a scored
                // realtime one, or a poll-only agent from one that was told about events it never
                // asked for. `leaderboardEligible` is the gate: only realtime 1x runs may be ranked.
                "speedProfile",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().id(),
                "diagnostic",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().diagnostic(),
                "leaderboardEligible",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().leaderboardEligible(),
                "alertDelivery",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDelivery.levelByDefault().id,
                // The tier is the comparison bucket: a run is only comparable with runs of the
                // same tier, so every knob it controls is recorded next to it.
                "tier",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.active().level,
                "tierAlertLevel",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.active().alertLevel.id,
                "tierSuggestions",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.active().suggestions,
                "tierAgentMaySwitchAlerts",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.active().agentMaySwitchAlerts));
        if(engine.replayEngine.j() && System.getProperty("rwx.evaluate.replaySpeed")!=null)
            engine.gameSpeed=Float.parseFloat(System.getProperty("rwx.evaluate.replaySpeed"));
        try {
            GameStatistics.f.addObserver(this);
            // The getter drains transactional operations and increments modCount even when empty.
            // Builder snapshots call it again for repair targets, so iterate a detached snapshot.
            for(Object unit:BaseUnit.getGlobalUnitList().toArray())register((BaseUnit)unit);
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
        try{s.flushIdles("EVIDENCE_END");s.write("end",data("reason",reason));s.output.flush();
            if(s.economyColumn>0)s.economy.newLine();s.economy.close();s.output.close();
            trimTelemetry(s.telemetryPath);}
        catch(IOException error){System.err.println("Evaluation close failed: "+error);}
        finally{s.closed=true;GameStatistics.f.removeObserver(s);current=null;ORIGINS.clear();REPLAY_ORDINALS.clear();}
    }
    /**
     * Ends the current match cleanly while the process keeps running.
     *
     * <p>{@link #close} alone would let the next tick reopen a fresh session on the same map, so the
     * match is pinned to this game time first; the pin is released only when the clock goes
     * backwards, which is what a restart does. Used by the host abort path.</p>
     */
    public static synchronized void finish(String reason){
        EngineEvidence s=current;
        if(s!=null){endedMap=s.map;endedTime=s.engine.gameTimeMillis;}
        close(reason);
    }
    /**
     * The player left the match without ending it: menu exit, disconnect, mod reload.
     *
     * <p>Nothing else closes the evidence on that path — the session stays open until the process
     * exits, so a later hard kill would drop the {@code end} row and the last buffered second. The
     * replay is already finalized by {@code GameLogic.resetGame}; this makes the evidence just as
     * safe, which is what "exit game" needs to be.</p>
     */
    public static synchronized void gameLeft(){
        EngineEvidence s=current;
        if(s==null)return;
        endedMap=s.map;endedTime=s.engine.gameTimeMillis;
        close("match_left");
    }
    /**
     * Collapses runs of consecutive per-tick frame rows into one {@code "kind":"frames"} row.
     *
     * <p>The game clock is an int advanced by {@code (int)(prev + f*16.666666f)} where
     * {@code f = deltaSpeed * speedMultiplier} ({@code GameLogic.update}). Because the base is an
     * int the fraction never accumulates, so a constant speed adds a constant integer step per
     * tick: 16 ms at 1x, 33 at 2x, 1066 at 64x. A run is therefore lossless exactly while tick and
     * seq advance by one and the clock advances by one constant step; any speed change ends the
     * run and the reader rebuilds every intermediate row from {@code timeMs}, {@code tick},
     * {@code seq} and {@code stepMs}.</p>
     *
     * <p>Disable with {@code -Drwx.evaluate.trim=false} when the raw per-tick rows are needed.</p>
     */
    static void trimTelemetry(Path file) {
        if(!Boolean.parseBoolean(System.getProperty("rwx.evaluate.trim","true")))return;
        Path temporary=file.resolveSibling(file.getFileName()+".trim");
        try{
            // Streamed: a long match can write a telemetry file far larger than the heap, and only the
            // current run of frame rows has to be held in memory.
            try(BufferedReader input=Files.newBufferedReader(file,StandardCharsets.UTF_8);
                BufferedWriter output=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){
                String line=input.readLine();
                while(line!=null){
                    long[] first=frameFields(line);
                    if(first==null){output.write(line);output.write('\n');line=input.readLine();continue;}
                    long[] last=first;long step=-1;int count=1;
                    String next=input.readLine();
                    while(next!=null){
                        long[] current=frameFields(next);
                        if(current==null||current[1]!=last[1]+1||current[2]!=last[2]+1)break;
                        long delta=current[0]-last[0];
                        if(step<0)step=delta;else if(delta!=step)break;
                        last=current;count++;next=input.readLine();
                    }
                    if(count<2){output.write(line);output.write('\n');}
                    else output.write("{\"kind\":\"frames\",\"timeMs\":"+first[0]+",\"tick\":"+first[1]+",\"seq\":"+first[2]
                            +",\"toTimeMs\":"+last[0]+",\"toTick\":"+last[1]+",\"toSeq\":"+last[2]+",\"stepMs\":"+step+"}\n");
                    line=next;
                }
            }
            Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException|RuntimeException error){
            try{Files.deleteIfExists(temporary);}catch(IOException ignored){}
            System.err.println("Evaluation trim failed: "+error);
        }
    }
    /** {@code {timeMs,tick,seq}} of a plain frame row, or null for any other line. */
    private static long[] frameFields(String line){
        if(!line.startsWith("{\"kind\":\"frame\""))return null;
        long time=field(line,"\"timeMs\":"),tick=field(line,"\"tick\":"),seq=field(line,"\"seq\":");
        return time<0||tick<0||seq<0?null:new long[]{time,tick,seq};
    }
    private static long field(String line,String key){
        int at=line.indexOf(key);if(at<0)return -1;
        int i=at+key.length();long value=0;boolean any=false;
        while(i<line.length()&&Character.isDigit(line.charAt(i))){value=value*10+(line.charAt(i++)-'0');any=true;}
        return any?value:-1;
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
        // A command centre keeps producing while it defends itself, so its combat state must not hide
        // an idle production line. Every other asset keeps the existing `idle` definition; `workIdle`
        // is the field the idle-pair evidence uses, so ambush still reads `idle`.
        boolean workIdle=idle;
        if(!workIdle&&"commandCenter".equals(type(unit))
                &&w==null&&queue.isEmpty()&&unit.buildProgress>=1&&unit.transportContainer==null)workIdle=true;
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
                "visible",unit.d(team),"canAttack",weapon,"idle",idle,"workIdle",workIdle,"building",unit.bI(),
                "mobile",!unit.bI(),"builder",builder(unit),"factory",factory,"task",task,"queue",queue,
                "capRemaining",Math.max(0,team.getUnitCap()-team.getNonBuildingUnitCountIncludingQueued()),
                "hp",unit.currentHealth,"maxHp",unit.maxHealth);
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
        Object x=state.get("x"),y=state.get("y");
        // Movement is not a state transition: compare without coordinates and refresh positions at most
        // once per second, otherwise every walking unit writes a full snapshot on every tick.
        state.put("x",0);state.put("y",0);
        String signature=json(state);
        state.put("x",x);state.put("y",y);
        long now=engine.gameTimeMillis;
        double px=((Number)x).doubleValue(),py=((Number)y).doubleValue();
        double[] last=positions.get(unit.objectId);
        boolean moved=last==null||Math.abs(last[0]-px)>1e-3||Math.abs(last[1]-py)>1e-3;
        Long sampled=positionSamples.get(unit.objectId);
        boolean due=sampled==null||now-sampled>=POSITION_SAMPLE_MS;
        if(!force&&signature.equals(signatures.get(unit.objectId))&&!(due&&moved)){
            if(idles.containsKey(unit.objectId))trackIdle(unit,snapshot(unit,true));
            return;
        }
        signatures.put(unit.objectId,signature);
        positionSamples.put(unit.objectId,now);
        positions.put(unit.objectId,new double[]{px,py});
        if(Boolean.TRUE.equals(state.get("workIdle"))&&(builder(unit)||Boolean.TRUE.equals(state.get("factory"))
                ||"commandCenter".equals(type(unit))))state=snapshot(unit,true);
        write("unit",data("unit",state,"funds",team.credits));
        trackIdle(unit,state);
    }
    /**
     * One open idle pair per asset. The collector owns the pair because only it can name the reason
     * the idle ended: a task type, a queued production item, an automatic engagement (no task and no
     * queue but not idle), death, or the end of the evidence. Rebuilding that from the change-only
     * unit stream is possible but ambiguous exactly where it matters.
     */
    private static final class Idle {
        final long unitId,startMs; final double funds,startX,startY; final int cap;
        final String type; final Object actions; final List<String> roles;
        double minFunds; int minCap; long lastTick=-1; boolean complete=true;
        final Map<String,Double> continuousActions=new LinkedHashMap<>();
        Set<Long> continuousRepairable;
        Idle(long unitId,long startMs,double funds,int cap,Object actions,double x,double y,
             String type,List<String> roles){
            this.unitId=unitId;this.startMs=startMs;this.funds=funds;this.cap=cap;this.actions=actions;
            this.startX=x;this.startY=y;this.type=type;this.roles=roles;
            this.minFunds=funds;this.minCap=cap;
        }
    }
    private final Map<Long,Idle> idles=new HashMap<>();
    /** Idle periods shorter than this are dropped at the source; the offline layer may want a floor too. */
    private static final long IDLE_MIN_MS=Long.getLong("rwx.evaluate.idleMinMs",3000L);
    private void trackIdle(BaseUnit unit,Map<String,Object> state) throws IOException{
        long id=unit.objectId;
        boolean alive=!unit.isDead&&!unit.isDestroyed;
        boolean now=alive&&Boolean.TRUE.equals(state.get("workIdle"));
        Idle open=idles.get(id);
        if(now){
            if(open==null){
                Object actions=state.get("actions");
                List<String> roles=rolesOf(actions);
                if(roles.isEmpty())return;
                open=new Idle(id,engine.gameTimeMillis,team.credits,
                        ((Number)state.getOrDefault("capRemaining",0)).intValue(),actions,
                        unit.posX,unit.posY,type(unit),roles);
                idles.put(id,open);
            }
            checkIdle(open,state);
            return;
        }
        if(open==null)return;
        idles.remove(id);
        writeIdle(open,alive?closeReason(state):"DEATH",state);
    }
    /** Intersect feasible actions on every simulation tick, including unchanged unit states. */
    private void checkIdle(Idle open,Map<String,Object> state){
        long tick=engine.currentTick;
        if(open.lastTick>=0&&tick>open.lastTick+1)open.complete=false;
        boolean first=open.lastTick<0;open.lastTick=tick;
        open.minFunds=Math.min(open.minFunds,team.credits);
        open.minCap=Math.min(open.minCap,((Number)state.getOrDefault("capRemaining",0)).intValue());
        Map<String,Double> feasible=new LinkedHashMap<>();
        if(state.get("actions") instanceof Map<?,?> actions)for(Map.Entry<?,?> entry:actions.entrySet()){
            if(entry.getValue() instanceof Map<?,?> action&&Boolean.TRUE.equals(action.get("available"))
                    &&Boolean.TRUE.equals(action.get("affordable"))&&action.get("cost") instanceof Number cost)
                feasible.put(String.valueOf(entry.getKey()),cost.doubleValue());
        }
        if(first)open.continuousActions.putAll(feasible);
        else open.continuousActions.entrySet().removeIf(entry->!feasible.containsKey(entry.getKey()));
        for(Map.Entry<String,Double> entry:open.continuousActions.entrySet())
            entry.setValue(Math.max(entry.getValue(),feasible.get(entry.getKey())));
        Set<Long> repairable=new HashSet<>();
        if(state.get("repairable") instanceof List<?> ids)for(Object value:ids)repairable.add(((Number)value).longValue());
        if(first)open.continuousRepairable=repairable;else open.continuousRepairable.retainAll(repairable);
    }
    /** Roles are read from the action menu, not from a name list, so hybrids (carrier, nautilus) are exact. */
    private static List<String> rolesOf(Object actions){
        List<String> roles=new ArrayList<>();
        if(actions instanceof Map<?,?> map)for(Object value:map.values()){
            if(!(value instanceof Map<?,?> row))continue;
            if("produce".equals(row.get("kind"))&&!roles.contains("factory"))roles.add("factory");
            if("build".equals(row.get("kind"))&&!roles.contains("builder"))roles.add("builder");
        }
        return roles;
    }
    private static String closeReason(Map<String,Object> state){
        if(state.get("task") instanceof Map<?,?> task&&task.get("type")!=null){
            return switch(String.valueOf(task.get("type"))){
                case "build"->"BUILD"; case "repair"->"REPAIR"; case "move"->"MOVE";
                case "attackMove"->"ATTACK_MOVE"; case "guardAt"->"GUARD_AT";
                case "patrol"->"PATROL"; case "attack"->"ATTACK";
                default->"TASK_"+String.valueOf(task.get("type")).toUpperCase(Locale.ROOT);
            };
        }
        if(state.get("queue") instanceof List<?> queue&&!queue.isEmpty())return "PRODUCE";
        return "AUTO_ENGAGE";
    }
    private void writeIdle(Idle open,String reason,Map<String,Object> state) throws IOException{
        long end=engine.gameTimeMillis,duration=end-open.startMs;
        if(duration<IDLE_MIN_MS)return;
        Object first=state.get("queue") instanceof List<?> queue&&!queue.isEmpty()?queue.get(0):null;
        write("idle",data("unitId",open.unitId,"life",0,"type",open.type,"roles",open.roles,
                "fromMs",open.startMs,"toMs",end,"durationMs",duration,"closeReason",reason,
                "closeAction",first instanceof Map<?,?> row?row.get("action"):null,
                "closeQueue",state.get("queue"),"closeTask",state.get("task"),
                "feasibility",data("complete",open.complete&&open.lastTick>=engine.currentTick-1,"minFunds",open.minFunds,"minCap",open.minCap,
                        "actions",open.continuousActions,"repairable",open.continuousRepairable,
                        "fromMs",open.startMs,"toMs",end),
                "startFunds",open.funds,"endFunds",team.credits,"startCap",open.cap,
                "startX",open.startX,"startY",open.startY,"actions",open.actions,
                "lastProduct",lastProduct.get(open.unitId)));
    }
    /** Flushes every still-open pair when the evidence ends; the end time is then a lower bound. */
    private void flushIdles(String reason) throws IOException{
        for(Iterator<Map.Entry<Long,Idle>> it=idles.entrySet().iterator();it.hasNext();){
            Map.Entry<Long,Idle> entry=it.next();it.remove();
            BaseUnit unit=monitored.get(entry.getKey());
            writeIdle(entry.getValue(),reason,unit==null?Map.of():snapshot(unit,false));
        }
    }
    private void frame() throws IOException {
        lastTime=engine.gameTimeMillis;
        for(Iterator<Map.Entry<Long,BaseUnit>> it=monitored.entrySet().iterator();it.hasNext();){
            Map.Entry<Long,BaseUnit> entry=it.next();BaseUnit u=entry.getValue();
            if(u.isDead||u.isDestroyed){unit(u,false);it.remove();watched.remove(entry.getKey());continue;}
            // Structures are always sampled: some producers (command centre) implement production without
            // FactoryQueueInterface, and their queue/idle transitions drive PRODUCTION_GAP.
            if(u.team==team&&(builder(u)||u.bI()||u instanceof FactoryQueueInterface)||watched.getOrDefault(u.objectId,0L)>=lastTime)unit(u,false);
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
            if(Math.hypot(u.posX-x,u.posY-y)<=radius)result.add(member(u));
        }
        result.sort(Comparator.comparingLong(u->((Number)u.get("id")).longValue()));return result;
    }
    /**
     * Neighbourhood membership is only consumed as identity/side/alive (who is inside the circle), so a full
     * unit snapshot here used to add ~2.4 KB per region row of task/queue/action fields nobody reads.
     */
    private Map<String,Object> member(BaseUnit unit){
        return data("id",unit.objectId,"side",side(unit),"alive",!unit.isDead&&!unit.isDestroyed);
    }
    private void sample() throws IOException {
        // First simulation state on/after each fixed second. Never duplicate a sample during pause.
        if(engine.gameTimeMillis-nextSample>=1000){
            write("sampling_gap",data("nextSampleMs",nextSample,"resumedSampleMs",(long)engine.gameTimeMillis/1000*1000));
            nextSample=(long)engine.gameTimeMillis/1000*1000;
        }
        while(nextSample<=engine.gameTimeMillis){
            double funds=team.credits;int income=team.getScaledIncomeRate();
            // `income` is per resourceIncomeRatePeriod ticks, not per second; the converted value is
            // written next to it so an offline income table never has to re-derive the conversion.
            write("economy",data("sampleMs",nextSample,"funds",funds,"income",income,
                    "incomePerSecond",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxIncomeRate
                            .perSecond(income,engine)));
            if(economyColumn>0)economy.write(',');
            economy.write(number(funds)+"("+(income>=0?"+":"")+income+")");
            if(++economyColumn==60){economy.newLine();economyColumn=0;}
            economy.flush();output.flush();nextSample+=1000;
        }
    }
    @Override public void a(BaseUnit attacker,BaseUnit victim,Object unused) { }
    /**
     * The subset of a unit snapshot an ambush judgement needs: identity, side, position, whether it
     * was idle (so it could not have been fighting back), whether it can shoot, and whether our team
     * could see it. Damage rows are the largest part of a busy telemetry file and the full snapshot
     * carried task, queue, action menu and capacity fields no damage rule reads.
     */
    private Map<String,Object> combatSnapshot(BaseUnit unit){
        Map<String,Object> full=snapshot(unit,false);
        Object task=full.get("task");
        return data("id",full.get("id"),"life",full.get("life"),"type",full.get("type"),
                "side",full.get("side"),"x",full.get("x"),"y",full.get("y"),"alive",full.get("alive"),
                "idle",full.get("idle"),"building",full.get("building"),
                "canAttack",full.get("canAttack"),"visible",full.get("visible"),
                "hp",unit.currentHealth,"maxHp",unit.maxHealth,
                // Only the task kind: the danger-zone judgement must know whether a unit was
                // assaulting or guarding when it was hit, not what its full waypoint was.
                "taskType",task instanceof Map<?,?> map?map.get("type"):null);
    }
    @Override public void damaged(BaseUnit victim,BaseUnit attacker){
        recordDamage(victim,attacker,null);
    }
    @Override public void damaged(BaseUnit victim,BaseUnit attacker,float healthBefore){
        recordDamage(victim,attacker,Math.max(0,Math.max(0,healthBefore)-Math.max(0,victim.currentHealth)));
    }
    private void recordDamage(BaseUnit victim,BaseUnit attacker,Float hpLoss){
        if(closed||!eligible(victim)||!eligible(attacker))return;
        try{
            // Keep sampling both sides briefly after a hit; the old Long.MAX_VALUE never expired and made
            // every unit that ever fought write a snapshot on every tick for the rest of the match.
            long until=engine.gameTimeMillis+POSITION_SAMPLE_MS;
            watched.put(victim.objectId,until);watched.put(attacker.objectId,until);
            // The damage row already embeds both snapshots, so no extra unit rows are forced here.
            write("damage",data("victim",combatSnapshot(victim),"attacker",combatSnapshot(attacker),"hpLoss",hpLoss));
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
        if("produced".equals(phase)&&related!=null&&eligible(related))lastProduct.put(unit.objectId,type(related));
        if(eligible(unit))try{write("lifecycle",data("phase",phase,"unitId",unit.objectId,"relatedId",related==null?null:related.objectId));}
        catch(IOException e){fail(e);}
    }
    public static Request beginRequest(String name,List<String> arguments){
        if(!enabled())return null;
        GameEngine e=GameEngine.getInstance();if(e==null)return null;
        synchronized(e.gameStateLock){
            EngineEvidence s=session();if(s==null)return null;
            Request request=new Request("B-"+(++requestNumber),e.gameTimeMillis,e.currentTick,name,List.copyOf(arguments),s.team.credits);
            REQUEST.set(request);
            try{
                Map<String,String> opts=options(arguments);
                List<Long> units=ids(opts.getOrDefault("--units",opts.get("--unit")));
                // order-build names its controlled object with --builder. Without it a request against a
                // dead builder carried no receipt-time lifecycle evidence, so STALE_COMMAND could not see it.
                List<Long> builders=ids(opts.get("--builder"));
                List<Long> controlled=new ArrayList<>(units);
                for(Long id:builders)if(!controlled.contains(id))controlled.add(id);
                Long target=parseId(opts.get("--target"));
                s.write("command",data("id",request.id,"source","agent","name",name,"raw",name+" "+String.join(" ",arguments),
                        "args",arguments,"units",units,"builders",builders,"refs",s.references(controlled,target,true),
                        "x",coordinate(opts.get("--x")),"y",coordinate(opts.get("--y")),"targetId",target));
            }catch(IOException|RuntimeException error){fail(error);}
            return request;
        }
    }
    public static void endRequest(String reply){ endRequest(reply,null); }
    /**
     * Records the reply plus the two facts an offline judge needs: a stable rejection {@code code}
     * (so a verdict never depends on the engine's prose) and the credit balance around the call (so
     * a per-second income table can separate income from spending).
     *
     * <p>The code is assigned here, at the transport boundary, because that is the only place that
     * still sees the exception type — {@link RwxLocalGameTypes.CreditShortageException} is a real
     * type, everything else is text. The raw {@code reply} is kept unchanged and stays authoritative.</p>
     */
    public static void endRequest(String reply,Throwable error){
        Request request=REQUEST.get();REQUEST.remove();if(request==null||current==null)return;
        String code=error==null?"ACCEPTED":failureCode(error);
        synchronized(current){try{
            current.write("result",data("commandId",request.id,"accepted",!reply.startsWith("error:"),
                    "code",code,"creditsBefore",request.creditsBefore,"creditsAfter",current.team.credits,
                    "reply",reply));}
        catch(IOException e){fail(e);}}
    }
    /** Ordered, case-insensitive classification of one rejection. First match wins. */
    static String failureCode(Throwable error){
        if(error==null)return "ACCEPTED";
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<4;depth++,cause=cause.getCause()){
            if(com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.isCreditShortage(cause))
                return "INSUFFICIENT_CREDITS";
            String text=cause.getMessage()==null?"":cause.getMessage().toLowerCase(Locale.ROOT);
            for(String[] rule:FAILURE_RULES)if(text.contains(rule[1]))return rule[0];
        }
        return "INTERNAL";
    }
    /** Most specific first: a dead object must not be reported as a bad argument. */
    private static final String[][] FAILURE_RULES={
            {"STALE_OBJECT","skipped dead/missing"},{"STALE_OBJECT","no controllable unit remains"},
            {"STALE_OBJECT","not currently observable"},{"STALE_OBJECT","dead or hidden"},
            // 这两条实测占了一局里七次"INTERNAL"中的四次：Agent 拿着已经消失的 id 在操作，
            // 正是"世界模型过期"最直接的证据，不能落进 INTERNAL 这个垃圾桶。
            {"STALE_OBJECT","unit not found"},{"STALE_OBJECT","no pending order"},
            {"NOT_CONTROLLABLE","is not controllable"},{"NOT_CONTROLLABLE","not a controllable"},
            {"NOT_CONTROLLABLE","is not an owned"},{"NOT_CONTROLLABLE","has no nuclear launch action"},
            {"PLACEMENT_BLOCKED","cannot place"},{"PLACEMENT_BLOCKED","placement blocked"},
            {"PLACEMENT_BLOCKED","blockedby:"},{"PLACEMENT_BLOCKED","位置被阻挡"},
            // "has no action 'x' right now" is a timing problem; a bare "has no action" is a typo.
            {"UNAVAILABLE_ACTION","right now"},{"UNAVAILABLE_ACTION","正在升级"},
            // 建造者没有该科技/该动作（钱不够会先被 isCreditShortage 拦下）。
            {"UNAVAILABLE_ACTION","cannot build "},
            // 目标存在，但引擎没有"对已完成工厂加速"这类指令：不是参数错，是动作本身不适用。
            {"INVALID_ACTION","is not an unfinished building"},
            {"INVALID_ACTION","has no action"},{"INVALID_ACTION","no such action"},
            {"INVALID_ACTION","unknown action"},{"INVALID_ACTION","未找到动作"},
            {"INVALID_ARGUMENT","unknown option"},{"INVALID_ARGUMENT","unrecognized option"},
            {"INVALID_ARGUMENT","missing required"},{"INVALID_ARGUMENT","invalid argument"},
            {"INVALID_ARGUMENT","invalid value"},{"INVALID_ARGUMENT","must be"},
            {"INVALID_ARGUMENT","is required"},
    };
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
            // Observe all open assets before shared credits/cap are changed by a native order.
            for(Idle open:s.idles.values()){
                BaseUnit asset=s.monitored.get(open.unitId);
                if(asset!=null)s.checkIdle(open,s.snapshot(asset,true));
            }
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
    /**
     * Records that an Agent opened or closed the realtime event socket.
     *
     * <p>Without this the offline judge cannot tell a polling Agent from a subscribed one, and would
     * read "it never queried after the loss" as "it was never told" even though the loss was pushed
     * to it. The row carries the live client count so a reconnect storm is visible too.</p>
     */
    public static void streamClient(boolean connected,int clients){
        EngineEvidence s=current;if(s==null)return;
        try{s.write("eventstream",data("connected",connected,"clients",clients));}
        catch(IOException|RuntimeException error){fail(error);}
    }
    /**
     * Directory label: `-Drwx.evaluate.label` when set, otherwise the replay file being written.
     *
     * <p>The replay is named when the map loads, i.e. before the first tick, so it is already
     * available here. Using it as the default means a plain listing of `evaluate/output/events-live`
     * says which match each directory belongs to, instead of only the JVM-wide ordinal. Without a
     * recording and without an explicit label there is nothing to derive, so the old numbered name
     * stays.</p>
     */
    private static String label(GameEngine engine){
        String cleaned=clean(System.getProperty("rwx.evaluate.label"));
        return cleaned!=null?cleaned:clean(recordingName(engine));
    }
    /** The replay file currently being written, as a bare name with the `[local]`/`[v1.15]` decoration stripped. */
    private static String recordingName(GameEngine engine){
        if(engine==null||engine.replayEngine==null)return null;
        String path=engine.replayEngine.getReplayFilePath();
        if(path==null||path.isBlank())return null;
        String name=path.replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);
        if(name.toLowerCase(Locale.ROOT).endsWith(".replay"))name=name.substring(0,name.length()-7);
        name=name.replaceFirst("(?i)^\\[(local|net|network)]\\s*","");
        name=name.replaceFirst("\\s*\\[[vV][^\\]]*]","");
        return name.isBlank()?null:name;
    }
    /** Path-safe token, or null when unset or blank after cleaning. */
    private static String clean(String raw){
        if(raw==null)return null;
        String cleaned=raw.replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("[-_]{2,}","-")
                .replaceAll("^[-_]+|[-_]+$","");
        // 64 个字符足够放下"地图 + 开局时刻 + 人工后缀"，再长就开始挤 Windows 路径预算。
        if(cleaned.length()>64)cleaned=cleaned.substring(0,64).replaceAll("[-_]+$","");
        return cleaned.isEmpty()?null:cleaned;
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
