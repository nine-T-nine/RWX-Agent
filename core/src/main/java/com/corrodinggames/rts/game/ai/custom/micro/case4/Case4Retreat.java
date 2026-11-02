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
 * enemy turret of the upper-left region. Turret types, counts and exact coordinates are evaluator
 * private: the agent only learns the generic objective.
 */
public final class Case4Retreat implements MicroCase {
    public static final int SELF_TEAM=0,ENEMY_TEAM=1;
    /** Turrets only spawn inside the top-left 3/5 x 3/5 rectangle of the map. */
    static final double REGION_FRACTION=3.0/5.0;
    /** Tiles kept free between the field and the map border / the 3/5 rectangle edge. */
    static final int FIELD_MARGIN=3;
    /** Field radius around the seed centre: 8..18 tiles, sized from the room the map leaves. */
    static final int RADIUS_MIN=8,RADIUS_MAX=18;
    /** Long-range guns lean to the upper-left of the field, i.e. away from the player's corner. */
    static final double LONG_RANGE_BIAS_DEGREES=225,LONG_RANGE_BIAS_SPREAD=35;
    /** Share of long-range guns that is allowed to ignore the bias and land anywhere in the field. */
    static final double LONG_RANGE_RANDOM_SHARE=0.2;
    static final double SPACING_LONG=10,SPACING_SHORT=3,SPACING_CROSS=3;
    /** Worst-case spacing relaxation when the field is too tight: 10% per retry round. */
    public static final double SPACING_FLOOR=0.4;
    static final int RELAX_STEPS=(int)Math.round((1.0-SPACING_FLOOR)/0.1);
    static final double JITTER_TILES=0.35;
    static final double BASE_CLEARANCE=14;
    static final String TANK="experimentalHoverTank",BAY="repairbay";
    public static final Set<String> LONG_RANGE_TYPES=Set.of("c_turret_t3_gun","c_turret_t2_artillery","c_turret_t1_artillery");
    static final Map<String,Double> RANGES=Map.of("c_turret_t1_lightning",210.0,"c_turret_t1_artillery",350.0,
            "c_turret_t2_artillery",460.0,"c_turret_t2_gun",185.0,"c_turret_t3_gun",320.0,"c_turret_t2_lightning",250.0);

    public record Turret(String type,int perLevel){ public boolean isLongRange(){return LONG_RANGE_TYPES.contains(type);} }
    public record Spec(int tanks,int bays,List<Turret> turrets,int fogMode,int gameLimitSeconds){ }
    public record Task(int type,int level){ }
    /** One placed turret: tile plus a sub-tile offset, and the polar description used by the meta. */
    record Spot(String type,int tileX,int tileY,double offsetX,double offsetY,double cornerDistance,
                int cornerAngle,double centreDistance){
        boolean isLongRange(){return LONG_RANGE_TYPES.contains(type);}
    }

    public static Task task(String name){
        if(name==null||!name.matches("4_[1-3]_[1-3]"))throw new IllegalArgumentException("task must be 4_<type 1-3>_<level 1-3>");
        return new Task(name.charAt(2)-'0',name.charAt(4)-'0');
    }
    /**
     * Turret counts stay where the geometry can hold them: the long-range types are the ones with the
     * 10-tile rule, so they stay at two per level while the short-range mass uses the 3-tile rule.
     */
    public static Spec spec(Task t){
        return switch(t.type()){
            // fogMode 0 = no fog, 2 = the stock persistent fog (fogEnabled + periodic maintenance).
            case 1 -> new Spec(2,2,List.of(new Turret("c_turret_t2_gun",3),new Turret("c_turret_t1_lightning",2)),0,900);
            case 2 -> new Spec(2,4,List.of(new Turret("c_turret_t2_gun",3),new Turret("c_turret_t1_artillery",2),
                    new Turret("c_turret_t2_lightning",2)),2,1200);
            case 3 -> new Spec(3,4,List.of(new Turret("c_turret_t3_gun",1),new Turret("c_turret_t2_artillery",1),
                    new Turret("c_turret_t2_gun",4)),2,1500);
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
        Case4Map.write(file,Case4Map.width(c.seed),Case4Map.height(c.seed));
        return file;
    }

    @Override public Map<String,Object> layout(MicroConfig c,GameEngine e){
        Task task=task(c.task);Spec spec=spec(task);
        int width=Case4Map.width(c.seed),height=Case4Map.height(c.seed);
        Random random=new Random(c.seed*0x9E3779B97F4A7C15L+task.type()*131L+task.level());
        int[] centre=pickCentre(width,height,minimumRadius(spec,task),random);
        int radius=regionRadius(centre,width,height);
        List<Spot> spots=placeTurrets(spec,task,centre,radius,width,height,random);
        int cornerX=width-8-(int)Math.floorMod(c.seed,5),cornerY=height-8-(int)Math.floorMod(c.seed,5);
        List<Map<String,Object>> units=new ArrayList<>();
        List<Map<String,Object>> turrets=new ArrayList<>();
        long id=1000;
        for(int i=0;i<spots.size();i++){
            Spot spot=spots.get(i);
            float x=Case4Map.world(spot.tileX())+(float)(spot.offsetX()*Case4Map.TILE);
            float y=Case4Map.world(spot.tileY())+(float)(spot.offsetY()*Case4Map.TILE);
            Map<String,Object> unit=map("id",id,"alias","E"+(i+1),"team",ENEMY_TEAM,"type",spot.type(),
                    "x",x,"y",y,"tileX",spot.tileX(),"tileY",spot.tileY(),
                    "jitterX",round(spot.offsetX()),"jitterY",round(spot.offsetY()),
                    "role",spot.isLongRange()?"long":"short",
                    "distanceFromCorner",round(spot.cornerDistance()),"angleDegrees",spot.cornerAngle(),
                    "distanceFromCentre",round(spot.centreDistance()),"attackRange",RANGES.get(spot.type()));
            turrets.add(unit);units.add(unit);id++;
        }
        // Repair bays: an L in the bottom-right corner (corner + two perpendicular arms), then the
        // tanks beside the inner corner with a clear corridor towards the battlefield.
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
        return map("task",c.task,"type",task.type(),"level",task.level(),
                "mapWidth",width,"mapHeight",height,"tileSize",Case4Map.TILE,
                "region",map("centreTileX",centre[0],"centreTileY",centre[1],
                        "centreDistanceFromCorner",round(Math.hypot(centre[0],centre[1])),
                        "radiusTiles",radius,"radiusMinTiles",RADIUS_MIN,"radiusMaxTiles",RADIUS_MAX,
                        "boxMinX",0,"boxMinY",0,"boxMaxX",regionMaxX(width),"boxMaxY",regionMaxY(height),
                        "boxWidthTiles",regionMaxX(width)+1,"boxHeightTiles",regionMaxY(height)+1,
                        "boxMapFraction",REGION_FRACTION,
                        "longRangeBiasDegrees",(int)LONG_RANGE_BIAS_DEGREES,
                        "longRangeRandomShare",LONG_RANGE_RANDOM_SHARE,
                        "spacingLong",SPACING_LONG,"spacingShort",SPACING_SHORT,"spacingCross",SPACING_CROSS),
                "baseCornerTile",map("x",cornerX,"y",cornerY),
                "achievedCentreDistanceTiles",map("min",round(centreDistance(spots,true)),"max",round(centreDistance(spots,false))),
                "achievedTurretSpacingTiles",map("long",round(minSpacing(spots,true)),"short",round(minSpacing(spots,false)),
                        "overall",round(minSpacing(spots,null))),
                "fogMode",spec.fogMode(),"turrets",turrets,"turretsByType",turretCounts(task),
                "repairBays",bays,"tanks",tanks,"units",units);
    }

    /** Last tile index inside the top-left 3/5 rectangle on each axis. */
    static int regionMaxX(int width){return (int)Math.floor(width*REGION_FRACTION)-1;}
    static int regionMaxY(int height){return (int)Math.floor(height*REGION_FRACTION)-1;}

    /** Room between the seed centre and the nearest edge of the allowed box, in tiles. */
    static int fieldRoom(int[] centre,int width,int height){
        return Math.min(Math.min(centre[0]-FIELD_MARGIN,regionMaxX(width)-centre[0]),
                Math.min(centre[1]-FIELD_MARGIN,regionMaxY(height)-centre[1]));
    }

    /**
     * Seed centre of the turret field: a random point of the top-left 3/5 rectangle that keeps at
     * least {@link #RADIUS_MIN} tiles to every edge of the box and stays clear of the player's
     * corner. Centres with room for the requested radius are preferred, so a heavy task does not
     * start from a centre that immediately squeezes its long-range guns together.
     */
    static int[] pickCentre(int width,int height,int minRadius,Random random){
        List<int[]> roomy=new ArrayList<>(),general=new ArrayList<>();
        for(int y=FIELD_MARGIN+RADIUS_MIN;y<=regionMaxY(height)-FIELD_MARGIN-RADIUS_MIN;y++){
            for(int x=FIELD_MARGIN+RADIUS_MIN;x<=regionMaxX(width)-FIELD_MARGIN-RADIUS_MIN;x++){
                if(Math.hypot(x-(width-8),y-(height-8))<BASE_CLEARANCE+8)continue;
                int[] tile={x,y};
                general.add(tile);
                if(fieldRoom(tile,width,height)>=minRadius)roomy.add(tile);
            }
        }
        List<int[]> candidates=roomy.isEmpty()?general:roomy;
        if(candidates.isEmpty())throw new IllegalStateException("no valid turret field centre on "+width+"x"+height);
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * Radius the field needs before the long-range spacing can even be drawn: n guns with a 10-tile
     * gap fit on a circle of radius 10/(2 sin(pi/n)); 35% slack leaves the upper-left bias room to
     * move. Clamped to 8..18 so it never contradicts the field bounds.
     */
    static int minimumRadius(Spec spec,Task task){
        int longs=0;
        for(Turret turret:spec.turrets())if(turret.isLongRange())longs+=turret.perLevel()*task.level();
        if(longs<=2)return RADIUS_MIN;
        double ring=SPACING_LONG/(2*Math.sin(Math.PI/longs))*1.35;
        return Math.min(RADIUS_MAX,Math.max(RADIUS_MIN,(int)Math.ceil(ring)));
    }

    /**
     * Field radius: the room the seed centre leaves inside the 3/5 rectangle and the map border,
     * clamped to 8..18 tiles. Every turret is drawn within this radius, so the whole field stays
     * inside the rectangle without being clipped.
     */
    static int regionRadius(int[] centre,int width,int height){
        return Math.max(RADIUS_MIN,Math.min(RADIUS_MAX,fieldRoom(centre,width,height)));
    }

    /**
     * Turret placement: every turret is drawn inside the seed centre's radius, so the whole field
     * stays inside the top-left 3/5 rectangle. Long-range guns are drawn with a strong upper-left
     * bias (a fifth of them may land anywhere), short-range guns fill the rest of the disc
     * uniformly, and every candidate is rejected while it breaks its own spacing rule, the cross
     * rule, the field radius or the map/base margins. A rejected draw is simply repeated.
     */
    static List<Spot> placeTurrets(Spec spec,Task task,int[] centre,int radius,
                                   int width,int height,Random random){
        List<String> longs=new ArrayList<>(),shorts=new ArrayList<>();
        for(Turret turret:spec.turrets()){
            List<String> target=turret.isLongRange()?longs:shorts;
            for(int n=0;n<turret.perLevel()*task.level();n++)target.add(turret.type());
        }
        Collections.shuffle(longs,random);
        Collections.shuffle(shorts,random);
        List<Spot> all=new ArrayList<>();
        for(String type:longs)all.add(placeOne(all,type,centre,radius,width,height,random,true));
        for(String type:shorts)all.add(placeOne(all,type,centre,radius,width,height,random,false));
        return all;
    }

    private static Spot placeOne(List<Spot> placed,String type,int[] centre,int radius,
                                 int width,int height,Random random,boolean longRange){
        double spacing=longRange?SPACING_LONG:SPACING_SHORT;
        for(int relax=0;relax<=RELAX_STEPS;relax++){
            double need=spacing*(1.0-0.1*relax);
            for(int attempt=0;attempt<900;attempt++){
                double angle;
                if(longRange&&random.nextDouble()>=LONG_RANGE_RANDOM_SHARE){
                    angle=Math.toRadians(LONG_RANGE_BIAS_DEGREES+random.nextGaussian()*LONG_RANGE_BIAS_SPREAD);
                }else{
                    angle=random.nextDouble()*Math.PI*2;
                }
                double distance=radius*Math.sqrt(random.nextDouble());
                int tileX=(int)Math.round(centre[0]+Math.cos(angle)*distance);
                int tileY=(int)Math.round(centre[1]+Math.sin(angle)*distance);
                if(!fits(placed,tileX,tileY,width,height,need,longRange,centre,radius))continue;
                return spot(type,tileX,tileY,centre,random);
            }
        }
        throw new IllegalStateException("no free tile for turret "+type);
    }

    private static Spot spot(String type,int tileX,int tileY,int[] centre,Random random){
        double offsetX=(random.nextDouble()-0.5)*2*JITTER_TILES;
        double offsetY=(random.nextDouble()-0.5)*2*JITTER_TILES;
        double cornerDistance=Math.hypot(tileX,tileY);
        int cornerAngle=(int)Math.round(Math.toDegrees(Math.atan2(tileY,tileX)));
        double centreDistance=Math.hypot(tileX-centre[0],tileY-centre[1]);
        return new Spot(type,tileX,tileY,offsetX,offsetY,cornerDistance,cornerAngle,centreDistance);
    }

    private static boolean fits(List<Spot> placed,int tileX,int tileY,int width,int height,double need,
                                boolean longRange,int[] centre,int radius){
        if(tileX<FIELD_MARGIN||tileY<FIELD_MARGIN||tileX>width-4||tileY>height-4)return false;
        if(tileX>regionMaxX(width)||tileY>regionMaxY(height))return false;
        if(Math.hypot(tileX-centre[0],tileY-centre[1])>radius)return false;
        int cornerX=width-8,cornerY=height-8;
        if(Math.hypot(tileX-cornerX,tileY-cornerY)<BASE_CLEARANCE)return false;
        for(Spot other:placed){
            double distance=Math.hypot(other.tileX()-tileX,other.tileY()-tileY);
            double required;
            if(other.isLongRange()&&longRange)required=need;
            else if(!other.isLongRange()&&!longRange)required=need;
            else required=SPACING_CROSS;
            if(distance<required)return false;
        }
        return true;
    }

    /** Minimum centre distance among one class of turret, or across all of them when {@code group} is null. */
    static double minSpacing(List<Spot> spots,Boolean longRange){
        double best=Double.MAX_VALUE;
        for(int i=0;i<spots.size();i++)for(int j=i+1;j<spots.size();j++){
            Spot a=spots.get(i),b=spots.get(j);
            if(longRange!=null&&(a.isLongRange()!=longRange||b.isLongRange()!=longRange))continue;
            best=Math.min(best,Math.hypot(a.tileX()-b.tileX(),a.tileY()-b.tileY()));
        }
        return best==Double.MAX_VALUE?0:best;
    }
    /** Smallest or largest turret distance from the seed centre, in tiles. */
    static double centreDistance(List<Spot> spots,boolean min){
        double best=min?Double.MAX_VALUE:0;
        for(Spot spot:spots)best=min?Math.min(best,spot.centreDistance()):Math.max(best,spot.centreDistance());
        return spots.isEmpty()?0:best;
    }
    private static double round(double value){return Math.round(value*10)/10.0;}

    /** A building body is drawn upright only at rotationSpeed -90; mobile units face +x at 0. */
    static boolean isBuilding(BaseUnit unit){
        if(unit instanceof com.corrodinggames.rts.game.units.buildings.BaseBuilding)return true;
        return unit instanceof com.corrodinggames.rts.game.units.custom.CustomUnit custom
                &&custom.unitConfig.isBuildingUnit();
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
        float corner=Case4Map.world(0);
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
            // A building body is drawn at rotationSpeed + 90 (BaseUnit.getRenderRotation - 90, then
            // the renderer's own +90), so the engine's -90 is exactly what stands a base texture
            // upright. Forcing 0/180 here is what made every turret and extractor look tilted.
            unit.rotationSpeed=isBuilding(unit)?-90f:0f;
            unit.buildProgress=1;unit.currentHealth=unit.maxHealth;
            unit.shield=unit.unitEnergyMax;unit.isActive=false;unit.n();PlayerTeam.c(unit);
            // The barrel is separate from the body: it follows movementLevels[0].targetX, which the
            // constructor leaves at 0 (pointing east) for every unit. Point each gun outward from the
            // defended corner instead, so the field reads as a defence.
            if(unit.team==enemy&&spec.containsKey("distanceFromCorner")){
                float facing=(float)Math.toDegrees(Math.atan2(unit.posY-corner,unit.posX-corner));
                unit.movementLevels[0].a(facing);
            }
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
