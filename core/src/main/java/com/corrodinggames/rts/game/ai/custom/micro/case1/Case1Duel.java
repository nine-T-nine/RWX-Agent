package com.corrodinggames.rts.game.ai.custom.micro.case1;

import com.corrodinggames.rts.game.*;
import com.corrodinggames.rts.game.ai.AIController;
import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.*;
import com.corrodinggames.rts.gameFramework.GameObject;
import java.nio.file.*;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

public final class Case1Duel implements MicroCase {
    /** Lake map uses its own team slots: 0 is the agent, 1 is the opponent. */
    public static final int SELF_TEAM=0, ENEMY_TEAM=1;
    private Case1Map.Layout generated;
    @Override public String id(){return "CASE1-duel";}
    @Override public int controlledTeam(){return SELF_TEAM;}
    /** CASE1's command surface; the case keeps the turn track, so MICRO_NEXT is filtered by track. */
    public static final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command[] COMMANDS={
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_STATE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_UNITS,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_UNIT,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_MAP,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_PATH,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_MOVE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_ATTACK_MOVE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_ATTACK,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_STOP,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_MODE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_BATCH,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_BEGIN,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_NEXT,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_HELP};
    @Override public MicroCommandProfile profile(MicroConfig c){return new MicroCommandProfile(c.track,COMMANDS);}
    @Override public MicroCase.TaskInfo taskInfo(String task){
        if(!task.matches("1_[12]_[1234]"))throw new IllegalArgumentException("task must be 1_<map 1|2>_<level 1|2|3|4>");
        return new MicroCase.TaskInfo(task.charAt(2)-'0',task.charAt(4)-'0',600,1200);
    }
    @Override public String helpCommand(){return com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MICRO_HELP.commandName();}
    /** CASE1 keeps both tracks (realtime and turns). */
    @Override public boolean requiresAgentRealtime(){return false;}
    @Override public MicroSession session(MicroRuntime run,Map<String,Object> meta){return new Case1Session(run);}
    @Override public Path prepareMap(MicroConfig config,Path runDirectory)throws Exception {
        if(config.mapType==2)return RwxCliTool.repoRoot().resolve("evaluate/resource/maps/环湖_单挑.tmx");
        generated=Case1Map.generate(config.seed,config.task);
        Path path=runDirectory.resolve("map.tmx");Case1Map.write(generated,path);return path;
    }
    @Override public Map<String,Object> layout(MicroConfig config,GameEngine engine) {
        Random random=new Random(config.seed^((long)config.task.hashCode()<<32));
        // level1 a=b+1, level2 a=b, level3 b=a+1, level4 b=a+2; a,b stay within 1..5.
        int base=1+random.nextInt(config.level==4?3:4),a=base+(config.level==1?1:0),b=base+(config.level==3?1:0)+(config.level==4?2:0);
        List<Map<String,Object>> units=new ArrayList<>();
        List<Map<String,Object>> discarded=new ArrayList<>();
        long id=1000;
        for(int side=0;side<2;side++) {
            int count=side==0?a:b;List<float[]> positions=new ArrayList<>();
            if(config.mapType==2) {
                for(BaseUnit unit:BaseUnit.bE) {
                    String type=unit.r().v();
                    if(unit.team!=null&&unit.team.teamId==side&&type.equals("commandCenter"))positions.add(new float[]{unit.posX,unit.posY});
                    else if(unit.team==null||unit.team.teamId!=0&&unit.team.teamId!=1)
                        discarded.add(map("team",unit.team==null?null:unit.team.teamId,"type",type,"x",unit.posX,"y",unit.posY));
                }
                positions.sort(Comparator.<float[]>comparingDouble(p->p[1]).thenComparingDouble(p->p[0]));
                if(positions.size()!=5)throw new IllegalStateException("Lake map must contain five source centers on each side");
                Collections.shuffle(positions,random);positions=new ArrayList<>(positions.subList(0,count));
            } else {
                int x=side==0?generated.ax():generated.bx(),y=side==0?generated.ay():generated.by();
                for(int i=0;i<count;i++)positions.add(new float[]{x*20+10+(i%2)*50-25,y*20+10+(i/2)*50-50});
            }
            positions.sort(Comparator.<float[]>comparingDouble(p->p[1]).thenComparingDouble(p->p[0]));
            for(int i=0;i<count;i++) units.add(map("id",id++,"alias",(side==0?"M":"E")+(i+1),"team",side,
                    "sourceTeam",config.mapType==2?side:null,"type",side==0?"mechMinigun":"c_mammothTank",
                    "x",positions.get(i)[0],"y",positions.get(i)[1]));
        }
        Map<String,Object> result=map("a",a,"b",b,"units",units);
        if(config.mapType==2){result.put("discardedUnits",discarded);result.put("deletedUnusedCenters",10-a-b);}
        if(generated!=null){result.put("generationRetries",generated.retries());result.put("regions",generated.regions());}
        return result;
    }
    @Override @SuppressWarnings("unchecked") public void setup(Map<String,Object> meta,GameEngine engine)throws Exception {
        for(BaseUnit unit:new ArrayList<>(BaseUnit.bE))unit.remove();
        GameObject.dL();
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team instanceof AIController)((AIController)team).canBuild=true;
        GameTeam self=new GameTeam(SELF_TEAM);self.teamName="Agent";self.teamColorId=0;self.credits=0;
        AIController enemy=new AIController(ENEMY_TEAM);enemy.teamName="CASE1";enemy.teamColorId=1;enemy.credits=0;
        enemy.isTeamLocked=true;enemy.teamPingTime=Case1EnemyStrategy.CODE;enemy.canBuild=false;
        engine.playerTeam=self;engine.networkEngine.localPlayerTeam=self;
        engine.tileMap.fogEnabled=false;engine.tileMap.fogPeriodicMaintenanceEnabled=false;engine.tileMap.fogRenderActive=false;
        engine.hasWonGame=false;engine.hasLostGame=false;engine.gameSpeed=1;engine.isStopped=false;engine.isPaused=false;
        engine.currentTick=0;engine.gameTimeMillis=0;
        for(Map<String,Object> spec:(List<Map<String,Object>>)meta.get("units")) {
            long id=((Number)spec.get("id")).longValue();engine.networkEngine.nextUnitId=id;
            UnitType type=UnitTypeEnum.getUnitTypeByName((String)spec.get("type"));
            if(type==null)throw new IllegalStateException("Case unit type is not loaded: "+spec.get("type"));
            BaseUnit raw=type.a();if(!(raw instanceof OrderableUnit unit))throw new IllegalStateException("Case unit is not orderable");
            if(unit.objectId!=id)throw new IllegalStateException("Unstable initial unit id");
            unit.setUnitTeam(((Number)spec.get("team")).intValue()==SELF_TEAM?self:enemy);
            unit.posX=((Number)spec.get("x")).floatValue();unit.posY=((Number)spec.get("y")).floatValue();
            unit.rotationSpeed=unit.team==self?0:180;unit.buildProgress=1;unit.currentHealth=unit.maxHealth;unit.shield=unit.unitEnergyMax;
            unit.isActive=false;unit.n();PlayerTeam.c(unit);
            spec.put("maxHp",unit.maxHealth);spec.put("maxShield",unit.unitEnergyMax);
            List<Map<String,Object>> weapons=new ArrayList<>();
            for(int i=0;i<unit.getTechLevel();i++)if(unit.q(i)>0)weapons.add(map("index",i,"range",unit.m(),"damage",unit.q(i),
                    "reloadFrames",unit.b(i),"warmupFrames",unit.e(i),"warmupNoReset",unit.s(i),"reloadTransferFrames",-unit.t(i)));
            spec.put("weapons",weapons);spec.put("range",unit.m());
        }
        // The lake map must end with exactly the case units: no other teams, no spare centers.
        Set<Long> expected=new HashSet<>();
        for(Map<String,Object> spec:(List<Map<String,Object>>)meta.get("units"))expected.add(((Number)spec.get("id")).longValue());
        for(BaseUnit unit:new ArrayList<>(BaseUnit.bE))if(!expected.contains(unit.objectId))unit.remove();
        GameObject.dL();engine.unitSpatialIndex.a();engine.pathfindingEngine.a(engine.tileMap,false);
        if(generated!=null&&!AiMapGraph.sameRegion(generated.ax()*20+10,generated.ay()*20+10,generated.bx()*20+10,generated.by()*20+10,UnitMovementType.LAND))
            throw new IllegalStateException("Generated spawn regions are not connected in the engine LAND path map");
    }
}
