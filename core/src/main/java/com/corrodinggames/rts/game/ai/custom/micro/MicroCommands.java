package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.*;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.GameEngine;
import io.github.rwx.app.MicroJson;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** Micro formatting and alias translation; native validation/execution remains in the existing CLI. */
public final class MicroCommands {
    private final MicroRuntime run;
    private final RwxLocalGameCommands delegate=new RwxLocalGameCommands(new RwxEngineLocalGameService());
    private final Map<Long,String> baseline=new LinkedHashMap<>();
    MicroCommands(MicroRuntime run){this.run=run;}
    public String execute(Command command,List<String> args)throws Exception {
        if(command==Command.MICRO_STATE){options(args,Set.of(),Set.of("--json"));return state(args.contains("--json"));}
        if(command==Command.MICRO_UNITS)return units(args);
        if(command==Command.MICRO_UNIT){options(args,Set.of("--id"),Set.of("--json"));long id=resolve(required(args,"--id"));delegate.microUnit(id);return unitOutput(List.of(row(id)),args.contains("--json"));}
        if(command==Command.MICRO_BEGIN){options(args,Set.of(),Set.of());return run.advance(true,true);}
        if(command==Command.MICRO_NEXT){options(args,Set.of(),Set.of("--quiet"));return run.advance(false,args.contains("--quiet"));}
        if(command==Command.MICRO_BATCH)return batch(args);
        MicroCommandProfile.Entry entry=MicroCommandProfile.entry(command);
        if(entry==null)throw new IllegalArgumentException("unknown command");
        if(entry.kind().equals("A")) {
            List<String> translated=translate(args,false);
            if(command==Command.MICRO_MAP)options(args,Set.of("--x","--y","--half-width","--half-height"),Set.of("--json"));
            else options(args,Set.of("--unit","--x","--y"),Set.of("--json"));
            boolean json=translated.remove("--json");
            // CASE1 always renders the land-unit overlay: the terrain alone never says where the
            // tanks are, and the duel is decided by positions.
            if(command==Command.MICRO_MAP&&translated.stream().noneMatch(option->option.startsWith("--unit-")))
                translated.add("--unit-land");
            String text=delegate.execute(entry.delegate(),translated);
            String legend=command==Command.MICRO_MAP?"\ncliff (^/-7): OVER_CLIFF can cross cliff tiles; LAND cannot. Explicit blocked tiles remain impassable.":"";
            return json?MicroJson.encode(map("status","ok","t",seconds(),"text",text+legend)):"ok: observation t="+MicroRuntime.time()+"\n"+text+legend;
        }
        if(!entry.kind().equals("B"))throw new IllegalArgumentException("unknown command");
        if(run.phase.equals("ended"))throw new IllegalStateException("match has ended: "+run.outcome);
        if(run.phase.equals("briefing")&&run.config.track.equals("realtime")&&!run.config.briefingOrders)throw new IllegalStateException("orders are denied during briefing");
        Set<String> values=new HashSet<>(Set.of("--units","--hp"));
        if(command==Command.MICRO_MOVE||command==Command.MICRO_ATTACK_MOVE){values.add("--x");values.add("--y");}
        if(command==Command.MICRO_ATTACK)values.add("--target");if(command==Command.MICRO_MODE)values.add("--mode");
        options(args,values,Set.of("--queue"));
        List<String> translated=translate(args,true);
        if(command==Command.MICRO_MODE)translated.remove("--queue");
        String result=delegate.execute(entry.delegate(),translated);
        if(result.startsWith("error:"))throw new IllegalArgumentException(result.substring(6).trim());
        MicroRuntime.Request q=MicroRuntime.request();
        return (q!=null&&!q.skippedDead.isEmpty()?"partial: skipped dead="+String.join(",",q.skippedDead):"ok: submitted")+" t="+MicroRuntime.time();
    }
    private String batch(List<String> args)throws Exception {
        if(args.size()!=1)throw new IllegalArgumentException("batch requires one quoted semicolon-separated argument");
        String[] parts=args.get(0).split(";",-1);if(parts.length>128)throw new IllegalArgumentException("batch is limited to 128 commands");
        List<List<String>> parsed=new ArrayList<>();
        for(int i=0;i<parts.length;i++) {
            List<String> tokens=RwxCommandLine.split(parts[i]);if(tokens.isEmpty())throw new IllegalArgumentException("empty batch child");
            Command command=RwxCliCommands.find("rwx-micro-1-"+tokens.get(0));
            boolean next=command==Command.MICRO_NEXT;
            if(command==null||command==Command.MICRO_BATCH||(!next&&!MicroCommandProfile.kind(command).equals("B")))throw new IllegalArgumentException("batch accepts action commands and a final next");
            if(next&&(i!=parts.length-1||!run.config.track.equals("pause")))throw new IllegalArgumentException("next must be last and is only available in pause track");
            parsed.add(tokens);
        }
        MicroRuntime.Request parent=MicroRuntime.request();boolean partial=false;StringBuilder text=new StringBuilder();
        for(List<String> tokens:parsed) {
            String name="rwx-micro-1-"+tokens.get(0);MicroRuntime.Request child=run.receive(name+" "+String.join(" ",tokens.subList(1,tokens.size())),parent==null?0:parent.seq);
            String reply;
            try{reply=execute(RwxCliCommands.find(name),tokens.subList(1,tokens.size()));}
            catch(Exception e){reply=MicroRuntime.error(e.getMessage());}
            run.finishRequest(child,reply);
            if(reply.startsWith("error:")||reply.startsWith("partial:"))partial=true;
            text.append('\n').append(name).append(' ').append(reply);
        }
        return (partial?"partial: batch":"ok: batch")+" t="+MicroRuntime.time()+text;
    }
    private long resolve(String token) {
        Long id=run.ids.get(token.toUpperCase(Locale.ROOT));
        if(id!=null)return id;
        try{return Long.parseLong(token);}catch(NumberFormatException e){throw new IllegalArgumentException("unknown unit alias: "+token);}
    }
    private List<String> translate(List<String> args,boolean action) {
        List<String> output=new ArrayList<>(args);
        for(String key:List.of("--id","--unit","--target")){int i=output.indexOf(key);if(i>=0)output.set(i+1,Long.toString(resolve(output.get(i+1))));}
        int i=output.indexOf("--units");
        if(action&&i<0)throw new IllegalArgumentException("--units is required (use all for all surviving friendly units)");
        if(i>=0) {
            String selector=output.get(i+1);List<Long> selected=new ArrayList<>();
            if(selector.equals("all"))for(var e:run.units.entrySet()) {if(!action||e.getValue().team.teamId==run.selfTeam)if(MicroRuntime.alive(e.getValue()))selected.add(e.getKey());}
            else for(String token:selector.split(",",-1))selected.add(resolve(token));
            List<Long> live=new ArrayList<>();MicroRuntime.Request q=MicroRuntime.request();
            Float hp=null;String h=value(args,"--hp");if(h!=null){try{hp=Float.parseFloat(h);}catch(NumberFormatException e){throw new IllegalArgumentException("--hp must be a percentage");}if(!Float.isFinite(hp)||hp<1||hp>100)throw new IllegalArgumentException("--hp must be from 1 to 100");}
            for(long id:new LinkedHashSet<>(selected)) {
                OrderableUnit unit=run.units.get(id);
                if(unit!=null&&!MicroRuntime.alive(unit)){if(q!=null&&action)q.skippedDead.add(run.aliases.get(id)+":"+id);continue;}
                if(action&&unit!=null&&hp!=null&&unit.currentHealth*100/unit.maxHealth>=hp)continue;
                live.add(id);if(action&&q!=null)q.units.add(run.aliases.getOrDefault(id,"?")+":"+id);
            }
            if(live.isEmpty())throw new IllegalArgumentException("no surviving units match the selection");
            output.set(i+1,String.join(",",live.stream().map(String::valueOf).toList()));
        }
        return output;
    }
    public String units(List<String> args) {
        options(args,Set.of("--owner","--units"),Set.of("--compact","--delta","--json"));
        List<String> translated=translate(args,false);if(value(args,"--owner")==null){translated.add("--owner");translated.add("any");}
        RwxLocalGameTypes.UnitPage page=delegate.microUnits(translated);List<Map<String,Object>> rows=new ArrayList<>();
        for(var snapshot:page.units)if(run.units.containsKey(snapshot.id)) {
            Map<String,Object> row=row(snapshot.id);String signature=MicroJson.encode(row);
            if(!args.contains("--delta")||!signature.equals(baseline.get(snapshot.id)))rows.add(row);
            if(args.contains("--delta"))baseline.put(snapshot.id,signature);
        }
        return unitOutput(rows,args.contains("--json"));
    }
    private Map<String,Object> row(long id) {
        OrderableUnit unit=run.units.get(id);if(unit==null)throw new IllegalArgumentException("unit does not belong to this case");
        Map<String,Object> row=MicroSnapshots.unit(unit,run.aliases,true);List<String> hit=new ArrayList<>(),threatened=new ArrayList<>();double nearest=Double.POSITIVE_INFINITY;
        for(OrderableUnit enemy:run.units.values())if(enemy.team!=unit.team&&MicroRuntime.alive(enemy)) {
            double d=Math.hypot(unit.posX-enemy.posX,unit.posY-enemy.posY);nearest=Math.min(nearest,d);
            if(d<=unit.m())hit.add(run.aliases.get(enemy.objectId));if(d<=enemy.m())threatened.add(run.aliases.get(enemy.objectId));
        }
        row.put("nearestEnemyDistance",Double.isFinite(nearest)?nearest:null);row.put("canHit",hit);row.put("threatenedBy",threatened);return row;
    }
    @SuppressWarnings("unchecked") private String unitOutput(List<Map<String,Object>> rows,boolean json) {
        if(json)return MicroJson.encode(map("status","ok","t",seconds(),"phase",run.phase,"units",rows));
        StringBuilder text=new StringBuilder("ok: units t=").append(MicroRuntime.time()).append(" count=").append(rows.size());
        for(Map<String,Object> r:rows) {
            List<Map<String,Object>> weapons=(List<Map<String,Object>>)r.get("weapons");double cooldown=0,warmup=0;
            for(var w:weapons){cooldown=Math.max(cooldown,((Number)w.get("cooldownFrames")).doubleValue()/60);warmup=Math.max(warmup,((Number)w.get("warmupFrames")).doubleValue()/60);}
            text.append('\n').append(r.get("alias")).append(" id=").append(r.get("id")).append(" type=").append(r.get("type"))
                    .append(" x=").append(fmt(r.get("x"))).append(" y=").append(fmt(r.get("y")))
                    .append(" hp=").append(fmt(r.get("hp"))).append('/').append(fmt(r.get("maxHp")))
                    .append(" shield=").append(fmt(r.get("shield"))).append('/').append(fmt(r.get("maxShield")))
                    .append(" order=").append(r.get("order")).append(r.get("orderTarget")==null?"":"@"+alias(r.get("orderTarget")))
                    .append(" target=").append(alias(r.get("target"))).append(" cd=").append(fmt(cooldown)).append(" warm=").append(fmt(warmup))
                    .append(" nearest=").append(fmt(r.get("nearestEnemyDistance"))).append(" hit=").append(csv(r.get("canHit")))
                    .append(" threatened=").append(csv(r.get("threatenedBy"))).append(" pending=").append(MicroJson.encode(r.get("pending")));
        }
        return text.toString();
    }
    private String state(boolean json) {
        GameEngine e=GameEngine.getInstance();Map<String,Object> state=map("status","ok","t",seconds(),"tick",e.currentTick,"phase",run.phase,
                "track",run.config.track,"turn",run.turn,"turnTicks",run.config.turnTicks,"turnSeconds",run.config.turnTicks/60.0,
                "remainingWallSeconds",run.remainingWall(),"outcome",run.outcome,"endReason",run.endReason);
        var totals=run.finalState();state.put("self",totals.get("self"));state.put("enemy",totals.get("enemy"));
        if(json)return MicroJson.encode(state);
        return "ok: state t="+MicroRuntime.time()+" tick="+e.currentTick+" phase="+run.phase+" track="+run.config.track+" turn="+run.turn
                +" turnTicks="+run.config.turnTicks+" remainingWall="+fmt(run.remainingWall())+" outcome="+run.outcome+" reason="+(run.endReason.isEmpty()?"-":run.endReason)
                +"\nself="+MicroJson.encode(state.get("self"))+" enemy="+MicroJson.encode(state.get("enemy"));
    }
    private String alias(Object id){return id==null?"-":run.aliases.getOrDefault(((Number)id).longValue(),String.valueOf(id));}
    private static String csv(Object v){String s=String.join(",",(List<String>)v);return s.isEmpty()?"-":s;}
    private static String fmt(Object value){return value==null?"-":String.format(Locale.ROOT,"%.3f",((Number)value).doubleValue());}
    private static double seconds(){return GameEngine.getInstance().gameTimeMillis/1000.0;}
    private static String value(List<String> args,String key){int i=args.indexOf(key);return i<0?null:args.get(i+1);}
    private static String required(List<String> args,String key){String value=value(args,key);if(value==null)throw new IllegalArgumentException(key+" is required");return value;}
    private static void options(List<String> args,Set<String> values,Set<String> flags) {
        Set<String> seen=new HashSet<>();
        for(int i=0;i<args.size();i++){String key=args.get(i);if(!seen.add(key))throw new IllegalArgumentException("duplicate option: "+key);
            if(values.contains(key)){if(++i>=args.size())throw new IllegalArgumentException(key+" requires a value");}
            else if(!flags.contains(key))throw new IllegalArgumentException("unknown option: "+key);}
    }
}

