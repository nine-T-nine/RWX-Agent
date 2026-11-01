package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** Same schema for live fallback and replay collection. Exact events; 250 game-ms snapshots. */
public final class MicroEvidence implements AutoCloseable {
    private final BufferedWriter snapshots,events;
    private final Map<Long,OrderableUnit> units;
    private final Map<Long,String> aliases;
    private final int selfTeam;
    private final Set<Long> killed=new HashSet<>();
    private int nextSample;
    public MicroEvidence(Path directory,Map<Long,OrderableUnit> units,Map<Long,String> aliases,int selfTeam)throws IOException {
        Files.createDirectories(directory);this.units=units;this.aliases=aliases;this.selfTeam=selfTeam;
        snapshots=Files.newBufferedWriter(directory.resolve("snapshots.jsonl"),StandardCharsets.UTF_8);
        events=Files.newBufferedWriter(directory.resolve("events.jsonl"),StandardCharsets.UTF_8);
        sample(true);
    }
    public void sample(boolean force) {
        GameEngine engine=GameEngine.getInstance();
        if(!force&&engine.gameTimeMillis<nextSample)return;
        List<Map<String,Object>> rows=new ArrayList<>();for(OrderableUnit unit:units.values())rows.add(MicroSnapshots.unit(unit,aliases,false));
        MicroRunWriter.line(snapshots,map("gameMs",engine.gameTimeMillis,"tick",engine.currentTick,"units",rows));
        nextSample=(engine.gameTimeMillis/250+1)*250;
    }
    public void damage(BaseUnit victim,BaseUnit attacker,float hpBefore,float shieldBefore) {
        if(!units.containsKey(victim.objectId))return;
        GameEngine engine=GameEngine.getInstance();
        List<Map<String,Object>> opponents=new ArrayList<>();
        if(attacker!=null)for(OrderableUnit u:units.values())if(u.team!=attacker.team&&MicroRuntime.alive(u))
            opponents.add(map("id",u.objectId,"x",u.posX,"y",u.posY,"range",u.m()));
        Map<String,Object> row=map("kind","damage","gameMs",engine.gameTimeMillis,"tick",engine.currentTick,
                "attacker",attacker==null?null:attacker.objectId,"victim",victim.objectId,
                "hpDamage",Math.max(0,hpBefore-Math.max(0,victim.currentHealth)),"shieldDamage",Math.max(0,shieldBefore-victim.shield),
                "hp",Math.max(0,victim.currentHealth),"shield",Math.max(0,victim.shield),"victimX",victim.posX,"victimY",victim.posY,
                "attackerX",attacker==null?null:attacker.posX,"attackerY",attacker==null?null:attacker.posY,"opponents",opponents);
        MicroRunWriter.line(events,row);
        if(victim.currentHealth<=0&&killed.add(victim.objectId))MicroRunWriter.line(events,map("kind","kill","gameMs",engine.gameTimeMillis,
                "tick",engine.currentTick,"unit",victim.objectId,"attacker",attacker==null?null:attacker.objectId));
    }
    public void order(Command command,List<Long> ids) {
        GameEngine engine=GameEngine.getInstance();
        for(long id:ids)if(units.containsKey(id)) {
            Map<String,Object> row=MicroSnapshots.command(command);
            row.putAll(map("kind","order","gameMs",engine.gameTimeMillis,"tick",engine.currentTick,"unit",id,
                    "source",command.team==null?"engine":command.team.teamId==selfTeam?"player":"ai"));
            MicroRunWriter.line(events,row);
        }
    }
    @Override public void close()throws IOException{sample(true);snapshots.close();events.close();}
}
