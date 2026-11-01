package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.*;
import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.*;
import io.github.rwx.app.MicroJson;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** One isolated local case per process. Hooks are no-ops for normal games. */
public final class MicroRuntime {
    private static volatile MicroRuntime current;
    private static volatile boolean playbackFinished;
    private static MicroEvidence playbackEvidence;
    private static Map<String,Object> playbackMeta;
    private static final Map<Long,OrderableUnit> playbackUnits=new LinkedHashMap<>();
    private static int playbackLastMs;
    private static final ThreadLocal<Request> REQUEST=new ThreadLocal<>();
    /** Seconds a windowed run keeps the stock end screen up before the process exits. */
    private static final int SECONDS_BEFORE_EXIT=10;
    /** android.view.KeyEvent.KEYCODE_Q. */
    private static final int KEYCODE_Q=45;
    public final MicroConfig config;
    public final MicroCase scenario;
    public final MicroCommandProfile profile;
    public final MicroRunWriter writer;
    public final Map<Long,OrderableUnit> units=new LinkedHashMap<>();
    public final Map<Long,String> aliases=new LinkedHashMap<>();
    public final Map<String,Long> ids=new LinkedHashMap<>();
    public long origin=System.nanoTime();
    public MicroSession session;
    public volatile String phase="briefing",outcome="active",endReason="";
    public volatile int turn;
    public volatile long firstCommand=-1,readyWall=-1,endedWall=-1;
    public volatile boolean ready;
    public int selfTeam=0,enemyTeam=1;
    private final Path mapFile;
    private final AtomicLong sequence=new AtomicLong();
    private Map<String,Object> meta;
    private MicroCommands commands;
    private MicroEvidence evidence;
    private Path recording;
    private final List<Map<String,Object>> kills=new ArrayList<>();
    private final Set<Long> killed=new HashSet<>();

    public static final class Request {
        public long seq,recv,gameMs,tick,parent;
        public String raw,name,phase,kind;
        public int turn;
        public boolean violation;
        public final List<String> units=new ArrayList<>(),skippedDead=new ArrayList<>();
    }
    private MicroRuntime(MicroConfig config)throws Exception {
        this.config=config;scenario=MicroCaseRegistry.find(config.caseId);profile=scenario.profile(config);
        String name=config.caseId+"_"+config.task+"_"+(config.mode.equals("human")?"human":config.track)+"_s"+config.seed+"_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // Concurrent runs can pick the same second, so claim the directory atomically instead of
        // testing existence first: the loser retries with the next suffix.
        Files.createDirectories(config.outputRoot);
        Path dir=config.outputRoot.resolve(name);int suffix=2;
        while(true) {
            try{Files.createDirectory(dir);break;}
            catch(FileAlreadyExistsException e){dir=config.outputRoot.resolve(name+"-"+suffix++);}
        }
        writer=new MicroRunWriter(dir);mapFile=scenario.prepareMap(config,dir).toAbsolutePath();
        System.setProperty("rwx.micro.enemyDelaySeconds",String.valueOf(config.enemyDelaySeconds));
        System.out.println("RWX_MICRO_RUN "+dir);
    }
    public static synchronized void configure(String caseId,String arguments,String file) {
        if(current!=null)throw new IllegalStateException("Only one micro attempt is allowed per process");
        try{current=new MicroRuntime(MicroConfig.parse(caseId,arguments,file));}
        catch(Exception e){throw new IllegalArgumentException("Could not initialize micro case: "+e.getMessage(),e);}
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"RWX-micro-clock");t.setDaemon(true);return t;});
        timer.scheduleWithFixedDelay(()->{
            MicroRuntime run=current;if(run==null||!run.ready)return;
            try{RwxEngineLocalGameService.onGameThread(()->{run.checkLimits();return null;});}
            catch(RuntimeException e){System.err.println("Micro clock: "+e.getMessage());}
        },50,50,TimeUnit.MILLISECONDS);
    }
    public static MicroRuntime current(){return current;}
    /** True while a micro case owns the match; the mission engine must not end it on its own. */
    public static boolean controllingMatch(){return current!=null&&current.ready;}
    /**
     * Ends the run after an engine failure. Returns true when the case took over the exit: the
     * caller must then leave the process to the {@link #exitAfter} thread, because an inline
     * System.exit on the game thread deadlocks against the headless shutdown hook.
     */
    public static boolean failed(Throwable failure) {
        MicroRuntime run=current;
        if(run!=null&&run.session!=null&&!run.phase.equals("ended")) {
            run.lifecycle("engine-error",failure.toString());run.end("error","engine-exception");
            return true;
        }
        return false;
    }
    public static String mapPath(){return current==null?null:current.mapFile.toString();}
    public static boolean isolated(){return current!=null&&current.config.mode.equals("agent");}
    public static boolean allowed(Command command) {
        if(current==null)return command==null||command.domain()!=RwxCliCommands.Domain.MICRO;
        return !isolated()||current.profile.contains(command);
    }
    public static String error(String reason){return "error: "+reason+" t="+time();}
    public static String time(){GameEngine e=GameEngine.getInstance();return String.format(Locale.ROOT,"%.3f",e==null?0:e.gameTimeMillis/1000.0);}
    public static void mapLoaded(GameEngine engine) {
        MicroRuntime run=current;if(run==null||run.ready)return;
        if(!Path.of(engine.currentMapPath).toAbsolutePath().equals(run.mapFile))return;
        try {
            run.meta=map("schema","rwx-micro-evidence/1","case",run.config.caseId,"task",run.config.task,"seed",run.config.seed,
                    "mapType",run.config.mapType,"level",run.config.level,"track",run.config.track,"mode",run.config.mode,
                    "agent",run.config.mode.equals("human")?"human":"agent",
                    "map",map("file",run.mapFile.toString(),"sha256",hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(run.mapFile))),
                            "width",engine.tileMap.tileCountX,"height",engine.tileMap.tileCountY,"tileSize",20),
                    "limits",map("wallSeconds",run.config.wallLimitSeconds,"gameSeconds",run.config.gameLimitSeconds,"briefingSeconds",run.config.briefingSeconds,
                            "startTimeoutSeconds",run.config.startTimeoutSeconds,"enemyDelaySeconds",run.config.enemyDelaySeconds),
                    "turnTicks",run.config.turnTicks,"turnSeconds",run.config.turnTicks/60.0,
                    // Engine clock: GameLogic adds 16.666666 game-ms per tick but stores the result in
                    // an int, so at 1x every tick adds exactly 16 game-ms. 60 ticks therefore report
                    // t=0.960, while 60 ticks is still the nominal one-second turn.
                    "tickDurationMs",16,"simulationFrameMs",1000.0/60,
                    "gameMsPerTick",16,"nominalTickMs",1000.0/60,
                    "engineVersion",engine.getVersionString(),"commit",commit(),"replay","match.replay",
                    "enemyPolicy","attackMove+radialRangeRetreat/v1","liveSnapshots",run.config.liveSnapshots,
                    // Calibration knobs are part of the run's identity: two results are only
                    // comparable when the clock and the delivery channel are the same. A diagnostic
                    // clock is explicitly not leaderboard eligible, so slow runs can never be
                    // averaged together with realtime ones.
                    "speedProfile",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().id(),
                    "diagnostic",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().diagnostic(),
                    "leaderboardEligible",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.active().leaderboardEligible(),
                    "alertDelivery",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDelivery.levelByDefault().id,
                    // The tier decides which of the above apply; two results are only comparable
                    // within the same tier, so the whole profile travels with the run.
                    "tier",com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.active().describe());
            run.meta.putAll(run.scenario.layout(run.config,engine));
            run.selfTeam=run.scenario.controlledTeam();
            run.enemyTeam=teams(run.meta).stream().filter(t->t!=run.selfTeam).findFirst().orElse(run.scenario.fallbackEnemyTeam());
            run.meta.put("controlledTeam",run.selfTeam);
            run.scenario.setup(run.meta,engine);
            run.bind(run.meta,run.units,run.aliases);
            run.aliases.forEach((id,alias)->run.ids.put(alias,id));
            // Every case answers through the same interface; the runtime never names a case here.
            run.commands=new MicroCommands(run);
            run.session=run.scenario.session(run,run.meta);
            if(run.session!=null)run.meta.put("wallClockOrigin","runtime-initialization; System.nanoTime");
            engine.gameSpeed=0;engine.isPaused=false;
            // A case without begin/pause/step starts at 1x as soon as the scenario is laid out.
            if(run.scenario.startsRunning()) {run.phase="running";engine.gameSpeed=1f;}
            String filename=run.writer.directory.getFileName()+".replay";
            engine.replayEngine.d(filename);
            if(!engine.replayEngine.isRecording())throw new IllegalStateException("Automatic replay recording failed");
            run.recording=engine.replayEngine.a(filename,true).toPath();
            run.writer.json("meta.json",run.meta);
            // Latency evidence belongs with the run it describes; the property is only a default so
            // an explicit -Drwx.trace.dir still wins.
            if(System.getProperty(com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDeliveryTrace.DIRECTORY_PROPERTY)==null)
                System.setProperty(com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDeliveryTrace.DIRECTORY_PROPERTY,
                        run.writer.directory.toString());
            if(run.config.liveSnapshots)run.evidence=new MicroEvidence(run.writer.directory.resolve("engine"),run.units,run.aliases,run.selfTeam);
            run.readyWall=run.wall();run.ready=true;
            if(run.config.track.equals("pause"))run.centerCamera(engine);
            System.out.println("RWX_MICRO_READY case="+run.config.caseId+" phase=briefing tick=0");
        } catch(Exception e){
            if(run.scenario.reportsScenarioFailure()) {
                if(run.meta!=null)run.writer.json("meta.json",run.meta);
                run.writer.json("result.json",map("outcome","error","endReason","invalid-scenario","reason",e.toString(),"tick",engine.currentTick,"gameMs",engine.gameTimeMillis));
                System.err.println("RWX_MICRO_RESULT outcome=error reason=invalid-scenario");
                exitAfter(1,0);
            }
            throw new IllegalStateException("Micro layout failed",e);
        }
    }
    /** Pause track only: each turn starts with the camera on our own units. */
    private void centerCamera(GameEngine engine) {
        double x=0,y=0;int count=0;
        for(OrderableUnit unit:units.values())if(unit.team!=null&&unit.team.teamId==selfTeam) {x+=unit.posX;y+=unit.posY;count++;}
        if(count==0)return;
        engine.centerViewpoint((float)(x/count),(float)(y/count));
        engine.clampCameraPosition();
    }
    @SuppressWarnings("unchecked") private void bind(Map<String,Object> metadata,Map<Long,OrderableUnit> units,Map<Long,String> aliases) {
        bindUnits(metadata,units,aliases);
    }
    @SuppressWarnings("unchecked") private static void bindUnits(Map<String,Object> metadata,Map<Long,OrderableUnit> units,Map<Long,String> aliases) {
        for(Map<String,Object> row:(List<Map<String,Object>>)metadata.get("units")) {
            long id=((Number)row.get("id")).longValue();BaseUnit u=GameObject.a(id,true);
            if(!(u instanceof OrderableUnit unit)||!unit.r().v().equals(row.get("type")))throw new IllegalStateException("Replay initial unit does not match meta");
            units.put(id,unit);aliases.put(id,(String)row.get("alias"));
        }
    }
    public long wall(){return (System.nanoTime()-origin)/1_000_000;}
    /** CASE1 keeps its command handling in {@link MicroCommands}; its session delegates to it. */
    public MicroCommands commands(){return commands;}
    /** Live snapshot sampler, present only when the run enabled {@code liveSnapshots}. */
    public MicroEvidence evidence(){return evidence;}
    public double remainingWall(){return config.mode.equals("human")?Double.POSITIVE_INFINITY:Math.max(0,config.wallLimitSeconds-(firstCommand<0?0:wall()-firstCommand)/1000.0);}
    public static Request received(String raw){return current==null?null:current.receive(raw,0);}
    public Request receive(String raw,long parent) {
        Request q=new Request();q.seq=sequence.incrementAndGet();q.raw=raw;q.recv=wall();q.parent=parent;
        GameEngine e=GameEngine.getInstance();q.gameMs=e==null?0:e.gameTimeMillis;q.tick=e==null?0:e.currentTick;q.phase=phase;q.turn=turn;
        q.name=raw.trim().split("[\\s\\t]",2)[0].toLowerCase(Locale.ROOT);q.kind=MicroCommandProfile.kind(RwxCliCommands.find(q.name));
        synchronized(this){if(firstCommand<0&&parent==0){firstCommand=q.recv;lifecycle("first-command",q.name);}}
        REQUEST.set(q);return q;
    }
    public static void replied(Request q,String reply) {
        if(q==null||current==null)return;
        current.finishRequest(q,reply);REQUEST.remove();
    }
    public void finishRequest(Request q,String reply) {
        boolean error=reply.startsWith("error:");
        String status=error?(reply.contains("unknown command")||reply.contains("not available")?"rejected":"error"):reply.startsWith("partial:")?"partial":"submitted";
        if(session!=null&&q.violation)status="rejected";
        if(session!=null&&error&&!q.violation)status="invalid_request";
        writer.gateway(map("kind","request","seq",q.seq,"requestId",q.seq,"wallRecvMs",q.recv,"wallReplyMs",wall(),"gameMs",q.gameMs,"tick",q.tick,
                "phase",q.phase,"turn",q.turn,"class",q.kind,"name",q.name,"raw",q.raw,"status",status,"error",error?reply:null,
                "units",q.units,"skippedDead",q.skippedDead,"parent",q.parent==0?null:q.parent));
        if(session!=null)session.requestFinished(q,error,status);
    }
    public static Request request(){return REQUEST.get();}
    public static String execute(Command command,List<String> arguments) {
        MicroRuntime run=current;if(run==null)return error("unknown command");
        try {
            // Help is a document lookup, not game state: it must answer while the case is still loading.
            if(command==Command.MICRO_HELP||command==Command.LTE_HELP||command==Command.TRANSPORT_HELP
                    ||command==Command.RETREAT_HELP)return run.help(arguments);
            if(command==Command.PING)return "ok: pong t="+time();
            if(!run.ready)return error("case is loading; retry shortly");
            Request request=REQUEST.get();
            // Full-game observations/orders carry their own onGameThread hop inside the service;
            // wrapping them here would re-enter the same lock and time out.
            if(command.domain()!=com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Domain.MICRO&&run.session!=null)
                return run.session.execute(command,arguments);
            return RwxEngineLocalGameService.onGameThread(()->{
                REQUEST.set(request);
                try{return run.session.execute(command,arguments);}finally{REQUEST.remove();}
            });
        }catch(Exception e){return error(e.getMessage()==null?"command failed":e.getMessage());}
    }
    private String help(List<String> arguments)throws Exception {
        // Bundled help lives next to the case docs; older cases name it command.md, newer ones commands.md.
        java.io.InputStream found=null;
        for(String name:new String[]{"commands.md","command.md"}) {
            found=MicroRuntime.class.getResourceAsStream("/micro/"+config.caseId+"/"+name);
            if(found!=null)break;
        }
        if(found==null)throw new IllegalStateException("Case help is not bundled");
        try(java.io.InputStream input=found) {
            String document=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            return "ok: help t="+time()+"\n"+RwxCliTool.helpDocument(document,arguments,scenario.helpCommand());
        }
    }
    /**
     * Runs one case command on the game thread, keeping the request association.
     *
     * <p>A case that reuses a full-game command name ({@code rwx-game-order-*}) handles it itself
     * instead of going through the service, so it does not inherit the service's hand-off. Creating
     * an engine {@code Command} and reading unit lists from a CLI thread races the simulation:
     * the pending-command list can be mutated while the game thread iterates it
     * ({@code ConcurrentModificationException} inside {@code executeLocalCommands}).</p>
     */
    public static String gameThread(java.util.concurrent.Callable<String> work)throws Exception {
        Request request=REQUEST.get();
        return RwxEngineLocalGameService.onGameThread(()->{
            if(request!=null)REQUEST.set(request);
            try{return work.call();}finally{REQUEST.remove();}
        });
    }
    public void lifecycle(String event,String reason) {
        GameEngine e=GameEngine.getInstance();
        writer.gateway(map("kind","lifecycle","event",event,"wallMs",wall(),"gameMs",e==null?0:e.gameTimeMillis,
                "tick",e==null?0:e.currentTick,"turn",turn,"reason",reason));
    }
    public String advance(boolean begin,boolean quiet) throws Exception {
        if(phase.equals("ended"))throw new IllegalStateException("match has ended: "+outcome);
        if(begin&&!phase.equals("briefing"))throw new IllegalArgumentException("already begun");
        if(!begin&&phase.equals("briefing"))throw new IllegalArgumentException("use begin first");
        if(!begin&&!config.track.equals("pause"))throw new IllegalArgumentException("not available in realtime track");
        GameEngine e=GameEngine.getInstance();phase="running";
        if(begin)lifecycle("begin","command");
        if(config.track.equals("pause")) {
            turn++;e.gameSpeed=0;
            // Pause track only: re-center between turns so a watching window follows the action.
            centerCamera(e);
            int stop=e.currentTick+config.turnTicks;
            while(e.currentTick<stop&&!phase.equals("ended")) {
                ((com.corrodinggames.rts.game.GameLogic)e).update(1f);
            }
            lifecycle("turn",phase.equals("ended")?"ended":"boundary");
        } else e.gameSpeed=1;
        return "ok: "+(begin?"begun":"turn completed")+" t="+time()+" tick="+e.currentTick+" turn="+turn
                +(quiet?"":"\n"+commands.units(List.of("--compact")));
    }
    private void checkLimits() {
        if(phase.equals("ended"))return;
        if(!config.mode.equals("human")) {
            if(firstCommand<0&&wall()-readyWall>=config.startTimeoutSeconds*1000){end("error","no-agent");return;}
            if(firstCommand>=0&&remainingWall()<=0){end("timeout","wall-limit");return;}
            if(session==null&&firstCommand>=0&&phase.equals("briefing")&&config.track.equals("realtime")&&wall()-firstCommand>=config.briefingSeconds*1000) {
                phase="running";GameEngine.getInstance().gameSpeed=1;lifecycle("begin","briefing-timeout");
            }
        }
        if(GameEngine.getInstance().gameTimeMillis>=config.gameLimitSeconds*1000)end("timeout","game-limit");
    }
    public static boolean allowTick() {
        if(playbackFinished)return false;
        MicroRuntime r=current;if(r==null)return true;
        if(r.ready&&r.config.mode.equals("human")&&r.phase.equals("briefing")&&GameEngine.getInstance().gameSpeed>0){r.phase="running";r.lifecycle("begin","human-resume");}
        return r.ready&&r.phase.equals("running");
    }
    /**
     * Human-mode helper for a watching window: Q toggles pause. The stock pause key lives in
     * GameUI.updateInput, which only runs inside gameLoop — and a paused frame never drives
     * gameLoop, so the stock key can start a pause but never end one. This runs from the render
     * loop instead, so it works in both directions.
     *
     * @return true when a micro human run consumed the key
     */
    public static boolean togglePause() {
        MicroRuntime r=current;
        if(r==null||!r.ready||!r.config.mode.equals("human")||r.phase.equals("ended"))return false;
        GameEngine engine=GameEngine.getInstance();
        boolean paused=engine.gameSpeed==0f;
        engine.gameSpeed=paused?1f:0f;
        System.out.println("RWX_MICRO_PAUSE paused="+(!paused)+" phase="+r.phase+" tick="+engine.currentTick);
        System.out.flush();
        return true;
    }
    /** Q key (android KeyEvent.KEYCODE_Q) on the CLI/render side: toggle pause in human mode. */
    public static boolean togglePauseForKey(int androidKeyCode) {
        return androidKeyCode==KEYCODE_Q&&togglePause();
    }
    public static void afterTick() {
        if(playbackEvidence!=null&&!playbackFinished){playbackEvidence.sample(false);playbackLastMs=GameEngine.getInstance().gameTimeMillis;}
        MicroRuntime r=current;if(r==null||!r.ready||!r.phase.equals("running"))return;
        r.session.afterTick();r.checkLimits();
    }
    public static boolean alive(BaseUnit u){return !u.isDead&&!u.isDestroyed&&u.currentHealth>0;}
    private static int countTeam(int team){int n=0;for(BaseUnit u:BaseUnit.bE)if(u.team!=null&&u.team.teamId==team&&alive(u))n++;return n;}
    /**
     * Micro cases intentionally have no builders, so the engine's "tech defeat" latch would
     * otherwise refuse every order after the first defeat check. Elimination decides the match.
     */
    public static void clearTechDefeat(int team){PlayerTeam t=PlayerTeam.k(team);
        if(t!=null&&!t.isTeamWipedOut&&countTeam(team)>0)t.isTeamDefeatedTech=false;}
    public Map<String,Object> finalState() {return finalState(units,aliases);}
    private static Map<String,Object> finalState(Map<Long,OrderableUnit> units,Map<Long,String> aliases) {
        List<Map<String,Object>> rows=new ArrayList<>();for(OrderableUnit u:units.values())rows.add(MicroSnapshots.unit(u,aliases,false));
        Map<String,Object> result=map("units",rows);
        int ownTeam=selfTeam(units),enemyTeam=ownTeam==0?1:0;
        for(OrderableUnit u:units.values())if(u.team!=null&&u.team.teamId!=ownTeam){enemyTeam=u.team.teamId;break;}
        result.put("self",teamTotals(units,ownTeam));result.put("enemy",teamTotals(units,enemyTeam));
        return result;
    }
    private static Map<String,Object> teamTotals(Map<Long,OrderableUnit> units,int team) {
        int count=0;double hp=0,shield=0;
        for(OrderableUnit u:units.values())if(u.team!=null&&u.team.teamId==team&&alive(u)){count++;hp+=u.currentHealth;shield+=u.shield;}
        return map("alive",count,"hp",hp,"shield",shield);
    }
    private static int selfTeam(Map<Long,OrderableUnit> units) {
        for(OrderableUnit u:units.values())if(u.team!=null)return u.team.teamId;
        return 0;
    }
    private static List<Integer> teams(Map<String,Object> meta) {
        List<Integer> result=new ArrayList<>();
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("units")){
            int team=((Number)row.get("team")).intValue();if(!result.contains(team))result.add(team);
        }
        return result;
    }
    /**
     * Exits from another thread after an optional countdown. The game thread holds the GameLogic
     * monitor while a case ends, and the headless shutdown hook disconnects networking, which waits
     * for that monitor: an inline System.exit there deadlocks the JVM.
     */
    private static void exitAfter(int status,int seconds) {
        Thread exit=new Thread(()->{
            for(int left=seconds;left>0;left--) {
                System.out.println("RWX_MICRO_EXIT_IN "+left);System.out.flush();
                try{Thread.sleep(1000);}catch(InterruptedException ignored){return;}
            }
            System.out.println("RWX_MICRO_EXIT");System.out.flush();
            System.exit(status);
        },"micro-exit");
        exit.setDaemon(true);exit.start();
    }
    public void end(String outcome,String reason) {
        if(phase.equals("ended"))return;
        this.outcome=outcome;endReason=reason;phase="ended";endedWall=wall();
        GameEngine engine=GameEngine.getInstance();
        boolean gui=GameEngine.guiPresent;
        // A window keeps running at 1x so the stock end screen can animate: the paused-frame path
        // draws the world only, without HUD. Headless has nothing to show and stops immediately.
        engine.gameSpeed=gui?1f:0f;
        lifecycle("end",reason);
        try {
            if(session!=null)session.finish(outcome);
            engine.replayEngine.e();
            if(recording!=null)Files.copy(recording,writer.directory.resolve("match.replay"));
            if(evidence!=null){evidence.close();evidence=null;}
            Map<String,Object> result=finalState();
            if(session!=null)result.putAll(session.result());
            result.putAll(map("schema","rwx-micro-evidence/1","agent",config.mode,"outcome",outcome,"endReason",reason,
                    "gameMs",engine.gameTimeMillis,"tick",engine.currentTick,"wallMs",firstCommand<0?0:wall()-firstCommand,"turns",turn,"kills",kills));
            writer.json("result.json",result);
            if(session!=null)session.close();
            System.out.println("RWX_MICRO_ENDED outcome="+outcome+" reason="+reason);
            reportResult(gui);
        }catch(Exception e){throw new IllegalStateException("Could not finalize micro run",e);}
    }
    /** GUI: stock victory/defeat screen. Both paths then wait before exiting. */
    private void reportResult(boolean gui) {
        Map<String,Object> self=teamTotals(units,selfTeam), enemy=teamTotals(units,enemyTeam);
        System.out.println("RWX_MICRO_RESULT outcome="+outcome+" reason="+endReason
                +" self="+self.get("alive")+"hp="+self.get("hp")+" shield="+self.get("shield")
                +" enemy="+enemy.get("alive")+"hp="+enemy.get("hp")
                +" tick="+GameEngine.getInstance().currentTick+" turns="+turn);
        System.out.flush();
        if(!gui) {
            exitAfter(0,SECONDS_BEFORE_EXIT);
            return;
        }
        try {
            GameEngine engine=GameEngine.getInstance();
            if(engine.gameUI!=null) {
                if(outcome.equals("win"))engine.gameUI.startGameEndSequence();
                else engine.gameUI.endGameSequence();
            }
        } catch(RuntimeException e) {System.err.println("RWX_MICRO_END_SCREEN failed: "+e);}
        exitAfter(0,SECONDS_BEFORE_EXIT);
    }
    public static void damage(BaseUnit victim,BaseUnit attacker,float hp,float shield) {
        if(current!=null&&current.ready&&victim.currentHealth<=0&&current.units.containsKey(victim.objectId)&&current.killed.add(victim.objectId))
            current.kills.add(map("unit",victim.objectId,"tick",GameEngine.getInstance().currentTick));
        if(current!=null&&current.session!=null)current.session.damage(victim,attacker,hp,shield);
        if(current!=null&&current.evidence!=null)current.evidence.damage(victim,attacker,hp,shield);
        if(playbackEvidence!=null&&!playbackFinished)playbackEvidence.damage(victim,attacker,hp,shield);
    }
    public static void commandApplied(com.corrodinggames.rts.gameFramework.Command command,List<Long> ids) {
        if(current!=null&&current.session!=null)current.session.applied(command);
        if(current!=null&&current.evidence!=null)current.evidence.order(command,ids);
        if(playbackEvidence!=null&&!playbackFinished)playbackEvidence.order(command,ids);
    }
    public static boolean collecting(){return current!=null&&(current.evidence!=null||current.session!=null)||playbackEvidence!=null&&!playbackFinished;}
    public static boolean allowNative(com.corrodinggames.rts.gameFramework.Command command) {
        return current==null||current.session==null||current.session.allow(command);
    }
    public static void nativeRecorded(com.corrodinggames.rts.gameFramework.Command command) {
        if(current!=null&&current.session!=null)current.session.recorded(command);
    }
    public static void constructionCreated(BaseUnit building,OrderableUnit builder,double paid) {
        if(current!=null&&current.session!=null)current.session.created(building,builder,paid);
    }
    public static void constructionWaiting(OrderableUnit builder) {
        if(current!=null&&current.session!=null)current.session.waiting(builder);
    }
    public static void replayLoaded() {
        String path=System.getProperty("rwx.micro.resim");if(path==null)return;
        try {
            playbackMeta=new LinkedHashMap<>(MicroJson.read(Path.of(path)));Map<Long,String> aliases=new LinkedHashMap<>();
            bindUnits(playbackMeta,playbackUnits,aliases);
            int selfTeam=((Number)playbackMeta.getOrDefault("controlledTeam",0)).intValue();
            playbackEvidence=new MicroEvidence(Path.of(System.getProperty("rwx.micro.resimOut")),playbackUnits,aliases,selfTeam);
            playbackLastMs=GameEngine.getInstance().gameTimeMillis;
            GameEngine.getInstance().gameSpeed=Float.parseFloat(System.getProperty("rwx.evaluate.replaySpeed","64"));
        }catch(Exception e){throw new IllegalStateException("Could not attach micro replay evidence",e);}
    }
    public static boolean playbackFinished(){return playbackFinished;}
    public static void replayEnded() {
        if(playbackEvidence==null)return;
        try {
            GameEngine engine=GameEngine.getInstance();engine.gameTimeMillis=playbackLastMs;playbackFinished=true;
            playbackEvidence.close();Map<Long,String> aliases=new LinkedHashMap<>();
            for(var row:(List<Map<String,Object>>)playbackMeta.get("units"))aliases.put(((Number)row.get("id")).longValue(),(String)row.get("alias"));
            Map<String,Object> state=finalState(playbackUnits,aliases);state.put("tick",engine.currentTick);state.put("gameMs",engine.gameTimeMillis);
            Path out=Path.of(System.getProperty("rwx.micro.resimOut"));Files.writeString(out.resolve("final.json"),MicroJson.encode(state));
            engine.gameSpeed=0;
            new Thread(()->System.exit(0),"micro-replay-exit").start();
        }catch(Exception e){throw new IllegalStateException("Could not finalize replay evidence",e);}
    }
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static String commit(){try{Path git=RwxCliTool.repoRoot().resolve(".git");String h=Files.readString(git.resolve("HEAD")).trim();return h.startsWith("ref: ")?Files.readString(git.resolve(h.substring(5))).trim():h;}catch(Exception e){return "unknown";}}
}
