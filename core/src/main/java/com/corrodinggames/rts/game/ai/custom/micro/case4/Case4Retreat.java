package com.corrodinggames.rts.game.ai.custom.micro.case4;

import com.corrodinggames.rts.game.GameTeam;
import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.micro.MicroCase;
import com.corrodinggames.rts.game.ai.custom.micro.MicroCommandProfile;
import com.corrodinggames.rts.game.ai.custom.micro.MicroConfig;
import com.corrodinggames.rts.game.ai.custom.micro.MicroRuntime;
import com.corrodinggames.rts.game.ai.custom.micro.MicroSession;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.GameObject;
import java.nio.file.Path;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * CASE4-Retreat: shielded hover tanks and repair bays in the bottom-right corner must clear every
 * enemy turret of the top-left region. Turret types, counts and exact coordinates are evaluator
 * private: the agent only learns the generic objective.
 */
public final class Case4Retreat implements MicroCase {
    public static final int SELF_TEAM=0,ENEMY_TEAM=1;
    /** Draft rings, in tiles, measured from the top-left map corner. */
    static final int RING_A_MIN=20,RING_A_MAX=30,RING_B_MIN=30,RING_B_MAX=50;
    static final double SPACING_A=10,SPACING_B=3;
    static final String TANK="experimentalHoverTank",BAY="repairbay";
    /** Ring A holds the long-range guns, ring B the short-range ones (see README). */
    static final Set<String> RING_A_TYPES=Set.of("c_turret_t3_gun","c_turret_t2_artillery","c_turret_t1_artillery");
    static final Map<String,Double> RANGES=Map.of("c_turret_t1_lightning",210.0,"c_turret_t1_artillery",350.0,
            "c_turret_t2_artillery",460.0,"c_turret_t2_gun",185.0,"c_turret_t3_gun",320.0,"c_turret_t2_lightning",250.0);

    public record Turret(String type,int perLevel){ public int ring(){return RING_A_TYPES.contains(type)?1:2;} }
    public record Spec(int tanks,int bays,List<Turret> turrets,int fogMode,int gameLimitSeconds){ }
    public record Task(int type,int level){ }

    public static Task task(String name){
        if(name==null||!name.matches("4_[1-3]_[1-3]"))throw new IllegalArgumentException("task must be 4_<type 1-3>_<level 1-3>");
        return new Task(name.charAt(2)-'0',name.charAt(4)-'0');
    }
    public static Spec spec(Task t){
        return switch(t.type()){
            // fogMode 0 = no fog, 2 = the stock persistent fog (fogEnabled + periodic maintenance).
            case 1 -> new Spec(2,2,List.of(new Turret("c_turret_t2_gun",5),new Turret("c_turret_t1_lightning",3)),0,900);
            case 2 -> new Spec(2,4,List.of(new Turret("c_turret_t2_gun",3),new Turret("c_turret_t1_artillery",4),
                    new Turret("c_turret_t2_lightning",2)),2,1200);
            case 3 -> new Spec(3,4,List.of(new Turret("c_turret_t3_gun",1),new Turret("c_turret_t2_artillery",1),
                    new Turret("c_turret_t2_gun",5)),2,1500);
            default -> throw new IllegalArgumentException("task type must be 1..3");
        };
    }
    /** Evaluator-only turret inventory: type -> count for this task. */
    public static Map<String,Integer> turretCounts(Task t){
        Map<String,Integer> out=new LinkedHashMap<>();
        for(Turret turret:spec(t).turrets())out.put(turret.type(),turret.perLevel()*t.level());
        return out;
    }

    @Override public String id(){return "CASE4-Retreat";}
    /** CASE4's command surface: its own commands, read-only observations and the stock order commands. */
    private static final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command[] COMMANDS={
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.RETREAT_STATE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.RETREAT_BEGIN,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.RETREAT_HELP,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MAP_SHOW,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MAP_REACH,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.UNIT_LIST,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.UNIT_SHOW,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.THREATS,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.ORDER_MOVE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.ORDER_ATTACK_MOVE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.ORDER_ATTACK,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.ORDER_STOP};
    @Override public MicroCommandProfile profile(MicroConfig c){return new MicroCommandProfile(c.track,COMMANDS);}
    @Override public MicroCase.TaskInfo taskInfo(String task){Spec spec=spec(task(task));
        return new MicroCase.TaskInfo(0,0,spec.gameLimitSeconds(),spec.gameLimitSeconds()+120);}
    @Override public String helpCommand(){return com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.RETREAT_HELP.commandName();}
    @Override public boolean reportsScenarioFailure(){return true;}
    @Override public MicroSession session(MicroRuntime run,Map<String,Object> meta)throws Exception{return new Case4Session(run,meta);}

    @Override public Path prepareMap(MicroConfig c,Path dir)throws Exception{
        Path file=dir.resolve("map.tmx");
        Case4Map.write(file);
        return file;
    }

    @Override public Map<String,Object> layout(MicroConfig c,GameEngine e){
        Task task=task(c.task);Spec spec=spec(task);
        List<Map<String,Object>> units=new ArrayList<>();
        long id=1000;
        // Turrets: long range deepest, short range towards the approach, ring A then ring B.
        List<String> queue=new ArrayList<>();
        for(Turret turret:spec.turrets())
            for(int n=0;n<turret.perLevel()*task.level();n++)queue.add(turret.type());
        queue.sort(Comparator.comparingDouble((String type)->-RANGES.getOrDefault(type,0.0)).thenComparing(type->type));
        List<int[]> placed=new ArrayList<>();
        List<Map<String,Object>> turrets=new ArrayList<>();
        double achieved=Double.MAX_VALUE;
        // The seed only rotates the ring and shifts the base corner: same seed, same layout.
        int angleStart=(int)Math.floorMod(c.seed*7,91);
        // Turrets of one ring are spread evenly over the quarter arc instead of packing the first
        // free slot, otherwise every gun ends up in the same corner of the ring.
        Map<Integer,List<String>> byRing=new LinkedHashMap<>();
        for(String type:queue)byRing.computeIfAbsent(RING_A_TYPES.contains(type)?1:2,key->new ArrayList<>()).add(type);
        for(var ringEntry:byRing.entrySet()){
            int ring=ringEntry.getKey();List<String> types=ringEntry.getValue();
            int minRadius=ring==1?RING_A_MIN:RING_B_MIN,maxRadius=ring==1?RING_A_MAX:RING_B_MAX;
            double wanted=ring==1?SPACING_A:SPACING_B;
            for(int k=0;k<types.size();k++){
                String type=types.get(k);
                int baseAngle=Math.floorMod(angleStart+(int)Math.round(k*90.0/types.size()),91);
                int baseRadius=minRadius+(k%2)*(maxRadius-minRadius);
                int[] spot=null;
                for(double attempt=wanted;attempt>=0&&spot==null;attempt-=1.0){
                    for(int offset=0;offset<=45&&spot==null;offset+=3){
                        for(int sign:new int[]{1,-1}){
                            int angle=Math.floorMod(baseAngle+sign*offset,91);
                            double radians=Math.toRadians(angle);
                            for(int step=0;step<=maxRadius-minRadius&&spot==null;step++){
                                int radius=baseRadius+(step%2==0?step/2:-(step+1)/2);
                                if(radius<minRadius||radius>maxRadius)continue;
                                int tileX=(int)Math.round(radius*Math.cos(radians)),tileY=(int)Math.round(radius*Math.sin(radians));
                                if(tileX<3||tileY<3||tileX>Case4Map.SIZE-3||tileY>Case4Map.SIZE-3)continue;
                                boolean free=true;
                                for(int[] other:placed){
                                    // Same ring keeps its own spacing, any other turret only needs its footprint.
                                    double needed=Math.min(attempt,3.0);
                                    if(Math.hypot(other[0]-tileX,other[1]-tileY)<needed){free=false;break;}
                                }
                                if(free)spot=new int[]{tileX,tileY,radius,angle};
                            }
                        }
                    }
                }
                if(spot==null)throw new IllegalStateException("no free tile for turret "+type);
                for(int[] other:placed)achieved=Math.min(achieved,Math.hypot(other[0]-spot[0],other[1]-spot[1]));
                placed.add(new int[]{spot[0],spot[1]});
                Map<String,Object> unit=map("id",id,"alias","E"+(turrets.size()+1),"team",ENEMY_TEAM,"type",type,
                        "x",Case4Map.world(spot[0]),"y",Case4Map.world(spot[1]),"tileX",spot[0],"tileY",spot[1],
                        "ring",ring==1?"A":"B","radiusTiles",spot[2],"angleDegrees",spot[3],"attackRange",RANGES.get(type));
                turrets.add(unit);units.add(unit);id++;
            }
        }
        // Repair bays: an L in the bottom-right corner (corner + two perpendicular arms), then the
        // tanks beside the inner corner with a clear corridor towards the battlefield.
        int shift=(int)Math.floorMod(c.seed,5);
        int cornerX=Case4Map.SIZE-8-shift,cornerY=Case4Map.SIZE-8-shift;
        List<Map<String,Object>> bays=new ArrayList<>();
        for(int i=0;i<spec.bays();i++){
            int tileX,tileY;
            if(i==0){tileX=cornerX;tileY=cornerY;}
            else if(i%2==1){tileX=cornerX;tileY=cornerY-4*((i+1)/2);}
            else {tileX=cornerX-4*(i/2);tileY=cornerY;}
            Map<String,Object> unit=map("id",id,"alias","B"+(i+1),"team",SELF_TEAM,"type",BAY,
                    "x",Case4Map.world(tileX),"y",Case4Map.world(tileY),"tileX",tileX,"tileY",tileY);
            bays.add(unit);units.add(unit);id++;
        }
        List<Map<String,Object>> tanks=new ArrayList<>();
        for(int i=0;i<spec.tanks();i++){
            int tileX=cornerX-10,tileY=cornerY-2-4*i;
            Map<String,Object> unit=map("id",id,"alias","T"+(i+1),"team",SELF_TEAM,"type",TANK,
                    "x",Case4Map.world(tileX),"y",Case4Map.world(tileY),"tileX",tileX,"tileY",tileY);
            tanks.add(unit);units.add(unit);id++;
        }
        return map("task",c.task,"type",task.type(),"level",task.level(),"mapSize",Case4Map.SIZE,"tileSize",Case4Map.TILE,
                "rings",map("a",map("minTiles",RING_A_MIN,"maxTiles",RING_A_MAX,"spacingTiles",SPACING_A),
                        "b",map("minTiles",RING_B_MIN,"maxTiles",RING_B_MAX,"spacingTiles",SPACING_B),
                        "centre",map("tileX",0,"tileY",0,"unit","tile")),
                "achievedTurretSpacingTiles",achieved==Double.MAX_VALUE?null:Math.round(achieved*10)/10.0,
                "fogMode",spec.fogMode(),"turrets",turrets,"turretsByType",turretCounts(task),
                "repairBays",bays,"tanks",tanks,"units",units);
    }

    @Override public void setup(Map<String,Object> meta,GameEngine e)throws Exception{
        for(BaseUnit unit:new ArrayList<>(BaseUnit.bE))unit.remove();
        GameObject.dL();
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team.teamId>=0)team.removeFromTeamRegistry();
        GameTeam self=new GameTeam(SELF_TEAM);self.teamName="Agent";self.teamColorId=0;self.credits=0;
        GameTeam enemy=new GameTeam(ENEMY_TEAM);enemy.teamName="Turrets";enemy.teamColorId=1;enemy.credits=0;
        e.playerTeam=self;e.networkEngine.localPlayerTeam=self;e.networkEngine.roomSettings.incomeMultiplier=1;
        int fogMode=((Number)meta.get("fogMode")).intValue();
        // fogMode 0 = no fog; fogMode 2 = the stock persistent fog, unexplored at the start.
        e.tileMap.fogEnabled=fogMode!=0;
        e.tileMap.fogPeriodicMaintenanceEnabled=fogMode==2;
        e.tileMap.fogRenderActive=false;
        byte initial=(byte)(fogMode==0?0:10);
        // The engine only allocates the per-team fog arrays and its smoothing cache when the room
        // setting enables fog, so a case that turns fog on has to provide both before the first tick.
        if(fogMode!=0)e.tileMap.ensureFogCacheAllocated();
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team.teamId>=0){
            if(fogMode!=0&&team.fogOfWarData==null)team.fogOfWarData=new byte[e.tileMap.tileCountX][e.tileMap.tileCountY];
            if(team.fogOfWarData!=null)
                for(int x=0;x<e.tileMap.tileCountX;x++)for(int y=0;y<e.tileMap.tileCountY;y++)team.fogOfWarData[x][y]=initial;
        }
        e.hasWonGame=false;e.hasLostGame=false;e.isStopped=false;e.isPaused=false;e.currentTick=0;e.gameTimeMillis=0;
        e.networkEngine.nextUnitId=1000;
        List<Map<String,Object>> units=(List<Map<String,Object>>)meta.get("units");
        for(Map<String,Object> spec:units){
            long id=((Number)spec.get("id")).longValue();
            e.networkEngine.nextUnitId=id;
            UnitType type=UnitTypeEnum.getUnitTypeByName((String)spec.get("type"));
            if(type==null)throw new IllegalStateException("Case unit type is not loaded: "+spec.get("type"));
            BaseUnit raw=type.a();
            if(!(raw instanceof OrderableUnit unit))throw new IllegalStateException("Case unit is not orderable: "+spec.get("type"));
            if(unit.objectId!=id)throw new IllegalStateException("Unstable initial unit id");
            unit.setUnitTeam(((Number)spec.get("team")).intValue()==SELF_TEAM?self:enemy);
            unit.posX=((Number)spec.get("x")).floatValue();unit.posY=((Number)spec.get("y")).floatValue();
            unit.rotationSpeed=unit.team==self?0:180;unit.buildProgress=1;unit.currentHealth=unit.maxHealth;
            unit.shield=unit.unitEnergyMax;unit.isActive=false;unit.n();PlayerTeam.c(unit);
        }
        GameObject.dL();e.unitSpatialIndex.a();e.pathfindingEngine.a(e.tileMap,false);
    }

    /** True when every turret of this task is destroyed; used by the session. */
    public static boolean goalReached(GameEngine e,Map<String,Object> meta){
        for(Map<String,Object> row:(List<Map<String,Object>>)meta.get("turrets")){
            long id=((Number)row.get("id")).longValue();
            BaseUnit unit=GameObject.a(id,true);
            if(unit!=null&&!unit.isDead&&!unit.isDestroyed&&unit.currentHealth>0)return false;
        }
        return true;
    }
}
