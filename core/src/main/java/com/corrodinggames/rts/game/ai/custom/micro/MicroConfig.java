package com.corrodinggames.rts.game.ai.custom.micro;

import io.github.rwx.app.MicroJson;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool;
import java.nio.file.Path;
import java.util.*;

/** Validated launch inputs. JSON is overlaid by explicit command-line case arguments. */
public final class MicroConfig {
    private static final Set<String> KEYS=Set.of("CASE","task","seed","track","mode","briefingOrders",
            "briefingSeconds","turnSeconds","turnTicks","wallLimitSeconds","gameLimitSeconds",
            "startTimeoutSeconds","enemyDelaySeconds","liveSnapshots","out");
    public final String caseId,task,track,mode;
    public final long seed;
    public final int mapType,level,turnTicks;
    public final double briefingSeconds,wallLimitSeconds,gameLimitSeconds,startTimeoutSeconds,enemyDelaySeconds;
    public final boolean briefingOrders,liveSnapshots;
    public final Path outputRoot;
    public final Map<String,String> values;

    public static MicroConfig parse(String caseId,String arguments,String file) {
        Map<String,String> input=new LinkedHashMap<>();
        if(file!=null) MicroJson.read(Path.of(file)).forEach((k,v)->{
            if(v instanceof Map || v instanceof List || v==null) throw new IllegalArgumentException("Invalid config value: "+k);
            input.put(k,String.valueOf(v));
        });
        if(caseId!=null) input.put("CASE",caseId);
        if(arguments!=null&&!arguments.isBlank()) {
            Set<String> seen=new HashSet<>();
            for(String pair:arguments.split(",",-1)) {
                String[] parts=pair.split("=",2);
                if(parts.length!=2||parts[0].isBlank()||parts[1].isBlank()) throw new IllegalArgumentException("Expected case argument key=value");
                String key=parts[0].trim();
                if(!seen.add(key)) throw new IllegalArgumentException("Duplicate case argument: "+key);
                input.put(key,parts[1].trim());
            }
        }
        return new MicroConfig(input);
    }

    public MicroConfig(Map<String,String> input) {
        for(String key:input.keySet()) if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown micro option: "+key);
        values=Map.copyOf(input);
        caseId=required(input,"CASE");
        // The registry validates the case id; the case itself validates the task grammar and owns
        // its budget, so this class carries no per-case knowledge.
        MicroCase scenario=MicroCaseRegistry.find(caseId);
        task=required(input,"task");
        MicroCase.TaskInfo info=scenario.taskInfo(task);
        mapType=info.mapType(); level=info.level();
        // A run without an explicit seed is reproducible only through the seed printed into meta.
        seed=input.containsKey("seed")?parseSeed(input.get("seed")):new Random().nextLong()&Long.MAX_VALUE;
        mode=choice(input,"mode","agent","agent","human");
        track=mode.equals("human")?"realtime":choice(input,"track",null,"realtime","pause");
        if(scenario.requiresAgentRealtime()&&(!mode.equals("agent")||!track.equals("realtime")||input.containsKey("turnTicks")||input.containsKey("turnSeconds")||input.containsKey("enemyDelaySeconds")))
            throw new IllegalArgumentException(caseId+" requires mode=agent,track=realtime; no turns or enemy options");
        briefingOrders=choice(input,"briefingOrders","allow","allow","deny").equals("allow");
        briefingSeconds=positive(input,"briefingSeconds",180);
        wallLimitSeconds=positive(input,"wallLimitSeconds",info.wallSeconds());
        gameLimitSeconds=positive(input,"gameLimitSeconds",info.gameSeconds());
        startTimeoutSeconds=positive(input,"startTimeoutSeconds",600);
        enemyDelaySeconds=positive(input,"enemyDelaySeconds",0,true);
        liveSnapshots=choice(input,"liveSnapshots","false","true","false").equals("true");
        if(input.containsKey("turnTicks")&&input.containsKey("turnSeconds")) throw new IllegalArgumentException("Specify turnTicks or turnSeconds, not both");
        // Default round length is one game second: 60 simulation ticks of 16.67 game-ms.
        double ticks=input.containsKey("turnSeconds")?Math.round(positive(input,"turnSeconds",0)*60):positive(input,"turnTicks",60);
        if(ticks<1||ticks>36000||ticks!=Math.rint(ticks)) throw new IllegalArgumentException("turnTicks must be an integer in 1..36000");
        turnTicks=(int)ticks;
        outputRoot=input.containsKey("out")?Path.of(input.get("out")).toAbsolutePath():RwxCliTool.repoRoot().resolve("evaluate/output/micro/"+caseId);
    }
    private static long parseSeed(String value) {
        if(value==null||value.isBlank()) throw new IllegalArgumentException("seed must be an integer");
        try {return Long.parseLong(value.trim());}
        catch(NumberFormatException e){throw new IllegalArgumentException("seed must be an integer");}
    }
    private static String required(Map<String,String> input,String key) {
        String value=input.get(key);
        if(value==null||value.isBlank()) throw new IllegalArgumentException("Missing micro option: "+key);
        return value;
    }
    private static String choice(Map<String,String> input,String key,String fallback,String... options) {
        String value=input.getOrDefault(key,fallback);
        if(value==null||!Arrays.asList(options).contains(value)) throw new IllegalArgumentException(key+" must be "+String.join("|",options));
        return value;
    }
    private static double positive(Map<String,String> input,String key,double fallback) {return positive(input,key,fallback,false);}
    private static double positive(Map<String,String> input,String key,double fallback,boolean zero) {
        double value;
        try{value=Double.parseDouble(input.getOrDefault(key,String.valueOf(fallback)));}
        catch(NumberFormatException e){throw new IllegalArgumentException(key+" must be a number");}
        if(!Double.isFinite(value)||(zero?value<0:value<=0)) throw new IllegalArgumentException(key+" must be "+(zero?"nonnegative":"positive"));
        return value;
    }
}
