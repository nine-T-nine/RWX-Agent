package com.corrodinggames.rts.game.ai.custom.micro.case3;

import com.corrodinggames.rts.game.GameTeam;
import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.micro.MicroCase;
import com.corrodinggames.rts.game.ai.custom.micro.MicroCommandProfile;
import com.corrodinggames.rts.game.ai.custom.micro.MicroConfig;
import com.corrodinggames.rts.game.ai.custom.micro.MicroRuntime;
import com.corrodinggames.rts.game.ai.custom.micro.MicroSession;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.custom.CustomUnit;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.GameObject;
import java.nio.file.Path;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * CASE3-Transport: build the requested army from pre-placed factories and ferry it with the given
 * transports from the bottom-right base island to the top-left target island. All ids, capacities
 * and production actions come from the loaded engine definitions.
 */
public final class Case3Transport implements MicroCase {
    public static final int SELF_TEAM=0,ENEMY_TEAM=1;
    /** One-time fog: start unexplored, reveal by vision, never regrow (engine fogMode 1). */
    public static final int FOG_MODE=1;
    /** AA guns only sit on the stretch of coast within this half angle of the bearing to the base. */
    static final double AA_ARC_HALF_DEGREES=80;
    /** Coastal band for AA guns, as BFS depth from the coastline in tiles. */
    static final int AA_COAST_MIN_DEPTH=3,AA_COAST_MAX_DEPTH=6;
    /** Preferred minimum centre distance between two AA guns, in tiles. */
    static final double AA_MIN_SPACING=10;
    /** Target composition entry: internal unit id and how many per level multiplier. */
    public record Need(String type,int perLevel){ }
    /** Everything a task fixes before the seed-dependent layout. */
    public record Spec(List<Need> needs,int transports,String transportType,int transportCapacity,int extractors,
                       List<String> factories,int credits,int gameLimitSeconds){ }
    public record Task(int type,int level){ }

    public static Task task(String name){
        if(name==null||!name.matches("3_[1-4]_[1-3]"))throw new IllegalArgumentException("task must be 3_<type 1-4>_<level 1-3>");
        return new Task(name.charAt(2)-'0',name.charAt(4)-'0');
    }
    public static Spec spec(Task t){
        return switch(t.type()){
            case 1 -> new Spec(List.of(new Need("heavyTank",3),new Need("plasmaTank",2),new Need("c_artillery",3)),
                    3,"dropship",4,5,List.of("landFactory","landFactory"),2000,900);
            case 2 -> new Spec(List.of(new Need("mechMinigun",1),new Need("mechArtillery",2)),
                    3,"dropship",4,5,List.of("mechFactoryT2","mechFactoryT2"),4000,1200);
            case 3 -> new Spec(List.of(new Need("mechFlame",1),new Need("mechLightning",1),new Need("c_mammothTank",2),new Need("heavyTank",3)),
                    3,"dropship",4,5,List.of("mechFactoryT2","mechFactoryT2","landFactory","landFactory"),4000,1500);
            case 4 -> new Spec(List.of(new Need("experimentalHoverTank",2),new Need("c_experimentalTank",1)),
                    2,"experimentalDropship",12,10,List.of("experimentalLandFactory","experimentalLandFactory"),20000,1800);
            default -> throw new IllegalArgumentException("task type must be 1..4");
        };
    }
    /** Required amount of each target unit for this task. */
    public static List<Map<String,Object>> requirements(Task t){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Need need:spec(t).needs())out.add(map("type",need.type(),"required",need.perLevel()*t.level()));
        return out;
    }

    private Set<Integer> target,base;
    @Override public String id(){return "CASE3-Transport";}
    /** CASE3's command surface: its own transport commands plus the read-only full-game observations. */
    private static final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command[] COMMANDS={
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_STATE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_GOAL,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_PRODUCTION,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_FLEET,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_PRODUCE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_LOAD,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_MOVE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_UNLOAD,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_HELP,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MAP_SHOW,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.MAP_REACH,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.UNIT_LIST,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.UNIT_SHOW};
    @Override public MicroCommandProfile profile(MicroConfig c){return new MicroCommandProfile(c.track,COMMANDS);}
    @Override public MicroCase.TaskInfo taskInfo(String task){Spec spec=spec(task(task));
        return new MicroCase.TaskInfo(0,0,spec.gameLimitSeconds(),spec.gameLimitSeconds()+120);}
    @Override public String helpCommand(){return com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.TRANSPORT_HELP.commandName();}
    /** The draft has no begin/pause/step/speed: the match runs at 1x immediately. */
    @Override public boolean startsRunning(){return true;}
    @Override public boolean reportsScenarioFailure(){return true;}
    @Override public MicroSession session(MicroRuntime run,Map<String,Object> meta)throws Exception{return new Case3Session(run,meta);}

    @Override public Path prepareMap(MicroConfig c,Path dir)throws Exception{
        Task t=task(c.task);
        target=Case3Map.targetIsland(c.seed);base=Case3Map.baseIsland(c.seed);
        Path file=dir.resolve("map.tmx");
        Case3Map.write(file,target,base);
        return file;
    }

    @Override public Map<String,Object> layout(MicroConfig c,GameEngine e){
        Task t=task(c.task);Spec spec=spec(t);
        Set<Integer> targetIsland=target!=null?target:Case3Map.targetIsland(c.seed);
        Set<Integer> baseIsland=base!=null?base:Case3Map.baseIsland(c.seed);
        List<Map<String,Object>> units=new ArrayList<>();
        long id=1000;
        // Base island: extractors first, then factories around the centre, then transports on the edge.
        List<Integer> islandTiles=new ArrayList<>(baseIsland);
        islandTiles.sort(Comparator.comparingInt(Case3Map::y).thenComparingInt(Case3Map::x));
        int minX=islandTiles.stream().mapToInt(Case3Map::x).min().orElseThrow();
        int maxX=islandTiles.stream().mapToInt(Case3Map::x).max().orElseThrow();
        int minY=islandTiles.stream().mapToInt(Case3Map::y).min().orElseThrow();
        int maxY=islandTiles.stream().mapToInt(Case3Map::y).max().orElseThrow();
        int cx=(minX+maxX)/2,cy=(minY+maxY)/2;
        // Factories: two per row, rows 12 tiles apart (>=10 required), 4-tile horizontal step.
        List<Map<String,Object>> factories=new ArrayList<>();
        for(int i=0;i<spec.factories().size();i++){
            int row=i/2,col=i%2;
            int fx=cx+(col==0?-4:4);
            int fy=cy+(row==0?-6:6);
            if(!baseIsland.contains(fy*Case3Map.SIZE+fx))throw new IllegalStateException("factory centre is not on the base island");
            Map<String,Object> unit=map("id",id,"alias","F"+(i+1),"team",SELF_TEAM,"type",spec.factories().get(i),
                    "x",Case3Map.worldX(fx),"y",Case3Map.worldY(fy),"tileX",fx,"tileY",fy);
            factories.add(unit);units.add(unit);id++;
        }
        // Extractors: grid over the island, skipping tiles covered by a factory footprint.
        Set<Integer> blocked=new HashSet<>();
        for(int i=0;i<spec.factories().size();i++){
            int row=i/2,col=i%2;
            int fx=cx+(col==0?-4:4),fy=cy+(row==0?-6:6);
            int half=spec.factories().get(i).equals("experimentalLandFactory")?2:1;
            for(int dy=-half-1;dy<=half+1;dy++)for(int dx=-half-1;dx<=half+1;dx++)blocked.add((fy+dy)*Case3Map.SIZE+(fx+dx));
        }
        List<Map<String,Object>> extractors=new ArrayList<>();
        int placed=0;
        for(int gy=minY+2;gy<=maxY-2&&placed<spec.extractors();gy+=3){
            for(int gx=minX+2;gx<=maxX-2&&placed<spec.extractors();gx+=3){
                int key=gy*Case3Map.SIZE+gx;
                if(!baseIsland.contains(key)||blocked.contains(key))continue;
                Map<String,Object> unit=map("id",id,"alias","E"+(placed+1),"team",SELF_TEAM,"type","extractorT3_overclocked",
                        "x",Case3Map.worldX(gx),"y",Case3Map.worldY(gy),"tileX",gx,"tileY",gy);
                extractors.add(unit);units.add(unit);id++;placed++;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)blocked.add((gy+dy)*Case3Map.SIZE+(gx+dx));
            }
        }
        if(placed<spec.extractors())throw new IllegalStateException("base island cannot hold "+spec.extractors()+" extractors");
        // Transports: island edge tiles facing away from the target island, deterministic shuffle.
        List<Integer> edge=new ArrayList<>();
        for(int key:islandTiles)if(Case3Map.neighbours(baseIsland,key)<4&&!blocked.contains(key))edge.add(key);
        edge.sort(Comparator.comparingInt(Case3Map::y).reversed().thenComparingInt(Case3Map::x).reversed());
        Collections.shuffle(edge,new Random(c.seed^0x6A09E667F3BCC909L));
        List<Map<String,Object>> transports=new ArrayList<>();
        List<Integer> used=new ArrayList<>();
        for(int key:edge){
            if(transports.size()>=spec.transports())break;
            int tx=Case3Map.x(key),ty=Case3Map.y(key);
            boolean far=used.stream().allMatch(other->Math.abs(Case3Map.x(other)-tx)+Math.abs(Case3Map.y(other)-ty)>=2);
            if(!far)continue;
            used.add(key);
            Map<String,Object> unit=map("id",id,"alias","T"+(transports.size()+1),"team",SELF_TEAM,"type",spec.transportType(),
                    "x",Case3Map.worldX(tx),"y",Case3Map.worldY(ty),"tileX",tx,"tileY",ty);
            transports.add(unit);units.add(unit);id++;
        }
        if(transports.size()<spec.transports())throw new IllegalStateException("base island has no free edge tiles for transports");
        // Target island: four T1 anti-air turrets on the stretch of coast that faces the base
        // island, spread as far apart as that coast allows. The transports arrive from that side,
        // so the guns cannot be dodged by flying around the far side, while the gaps between them
        // stay as wide as the coastline permits.
        List<Map<String,Object>> turrets=new ArrayList<>();
        if(t.type()<=3){
            double centreX=0,centreY=0;
            for(int key:targetIsland){centreX+=Case3Map.x(key);centreY+=Case3Map.y(key);}
            centreX/=targetIsland.size();centreY/=targetIsland.size();
            double baseX=0,baseY=0;
            for(int key:baseIsland){baseX+=Case3Map.x(key);baseY+=Case3Map.y(key);}
            baseX/=baseIsland.size();baseY/=baseIsland.size();
            Random random=new Random(c.seed^0xBB67AE8584CAA73BL);
            List<int[]> ring=coastRing(targetIsland,baseX,baseY,4);
            for(int i=0;i<ring.size();i++){
                int[] spot=ring.get(i);
                // Sub-tile jitter breaks the regular grid without leaving the tile, so the turret
                // still stands on land and the spacing check keeps holding.
                double jitterX=(random.nextDouble()-0.5)*0.8,jitterY=(random.nextDouble()-0.5)*0.8;
                float facing=(float)Math.toDegrees(Math.atan2(spot[1]-centreY,spot[0]-centreX));
                Map<String,Object> unit=map("id",id,"alias","A"+(i+1),"team",ENEMY_TEAM,"type","c_antiAirTurret",
                        "x",Case3Map.worldX(spot[0])+(float)jitterX*Case3Map.TILE,
                        "y",Case3Map.worldY(spot[1])+(float)jitterY*Case3Map.TILE,
                        "tileX",spot[0],"tileY",spot[1],"coastDepth",spot[2],"facing",facing);
                turrets.add(unit);units.add(unit);id++;
            }
        }
        return map("task",c.task,"type",t.type(),"level",t.level(),"mapSize",Case3Map.SIZE,"tileSize",Case3Map.TILE,
                "fogMode",FOG_MODE,
                "targetIslandTiles",new ArrayList<>(targetIsland),"baseIslandTiles",new ArrayList<>(baseIsland),
                "targetIslandArea",targetIsland.size(),"baseIslandArea",baseIsland.size(),
                "targetIslandBounds",map("minX",minX,"maxX",maxX,"minY",minY,"maxY",maxY),
                "requirements",requirements(t),"initialCredits",spec.credits(),"transportType",spec.transportType(),
                "transportCapacity",spec.transportCapacity(),"factories",factories,"extractors",extractors,
                "transports",transports,"antiAirTurrets",turrets,"units",units);
    }

    /**
     * AA slots on the coast that faces the base island: every island tile whose BFS distance to the
     * coast is {@link #AA_COAST_MIN_DEPTH}..{@link #AA_COAST_MAX_DEPTH} and whose bearing from the
     * island centre is within {@link #AA_ARC_HALF_DEGREES} of the bearing to the base centre.
     *
     * <p>Slots start at equal angles across that arc, then a local pass moves every gun to the
     * candidate with the largest distance to its neighbours, so the four guns end up as far apart as
     * the coastline allows instead of clustering where the noise happens to push the band inward.</p>
     */
    static List<int[]> coastRing(Set<Integer> island,double towardX,double towardY,int count){
        Map<Integer,Integer> depth=Case3Map.coastDepth(island);
        double centreX=0,centreY=0;
        for(int key:island){centreX+=Case3Map.x(key);centreY+=Case3Map.y(key);}
        centreX/=island.size();centreY/=island.size();
        double bearing=Math.toDegrees(Math.atan2(towardY-centreY,towardX-centreX));
        List<double[]> candidates=new ArrayList<>();
        for(int key:island){
            int d=depth.getOrDefault(key,0);
            if(d<AA_COAST_MIN_DEPTH||d>AA_COAST_MAX_DEPTH)continue;
            double x=Case3Map.x(key),y=Case3Map.y(key);
            double angle=Math.toDegrees(Math.atan2(y-centreY,x-centreX));
            if(Math.abs(angleGap(angle,bearing))>AA_ARC_HALF_DEGREES)continue;
            candidates.add(new double[]{x,y,angle,d});
        }
        if(candidates.size()<count)
            throw new IllegalStateException("target island has no coast facing the base island");
        List<int[]> picked=new ArrayList<>();
        double step=2*AA_ARC_HALF_DEGREES/count;
        for(int slot=0;slot<count;slot++){
            double want=bearing-AA_ARC_HALF_DEGREES+step*(slot+0.5);
            double[] best=null;
            double bestCost=Double.MAX_VALUE;
            for(double[] candidate:candidates){
                if(isPicked(picked,candidate))continue;
                // An off-centre slot is cheaper than two guns that overlap: the spacing term only
                // bites while the pair is closer than AA_MIN_SPACING.
                double cost=Math.abs(angleGap(candidate[2],want))
                        +Math.max(0,AA_MIN_SPACING-minGap(picked,candidate,-1))*4;
                if(cost<bestCost){bestCost=cost;best=candidate;}
            }
            if(best==null)throw new IllegalStateException("no room for an AA gun in slot "+slot);
            picked.add(toSpot(best));
        }
        spread(picked,candidates);
        return picked;
    }

    /** Local max-min pass: each gun moves to the candidate farthest from the other three. */
    private static void spread(List<int[]> picked,List<double[]> candidates){
        for(int pass=0;pass<3;pass++){
            boolean moved=false;
            for(int i=0;i<picked.size();i++){
                double bestGap=minGap(picked,picked.get(i),i);
                int[] best=picked.get(i);
                for(double[] candidate:candidates){
                    double gap=minGap(picked,candidate,i);
                    if(gap>bestGap+1e-9){bestGap=gap;best=toSpot(candidate);}
                }
                if(best!=picked.get(i)){picked.set(i,best);moved=true;}
            }
            if(!moved)break;
        }
    }

    private static double angleGap(double a,double b){
        double diff=(a-b)%360.0;
        if(diff>180)diff-=360;else if(diff<-180)diff+=360;
        return diff;
    }
    private static int[] toSpot(double[] candidate){return new int[]{(int)candidate[0],(int)candidate[1],(int)candidate[3]};}
    private static boolean isPicked(List<int[]> picked,double[] candidate){
        for(int[] spot:picked)if(spot[0]==(int)candidate[0]&&spot[1]==(int)candidate[1])return true;
        return false;
    }
    /** Smallest centre distance from a candidate to the picked guns, ignoring index {@code skip}. */
    private static double minGap(List<int[]> picked,double[] candidate,int skip){
        double best=Double.MAX_VALUE;
        for(int i=0;i<picked.size();i++){
            if(i==skip)continue;
            best=Math.min(best,Math.hypot(picked.get(i)[0]-candidate[0],picked.get(i)[1]-candidate[1]));
        }
        return best==Double.MAX_VALUE?Double.MAX_VALUE/4:best;
    }
    private static double minGap(List<int[]> picked,int[] candidate,int skip){
        return minGap(picked,new double[]{candidate[0],candidate[1],0,0},skip);
    }

    /** A building body is drawn upright only at rotationSpeed -90; mobile units face +x at 0. */
    static boolean isBuilding(BaseUnit unit){
        if(unit instanceof com.corrodinggames.rts.game.units.buildings.BaseBuilding)return true;
        return unit instanceof CustomUnit custom&&custom.unitConfig.isBuildingUnit();
    }

    public static int capacity(String transportType){
        UnitType type=UnitTypeEnum.getUnitTypeByName(transportType);
        if(type==null)throw new IllegalStateException("Transport type is not loaded: "+transportType);
        BaseUnit prototype=BaseUnit.getPrototypeForUnitType(type);
        if(prototype==null)return 0;
        if(prototype instanceof CustomUnit custom)return custom.unitConfig.maxTransportingUnits;
        // Built-in transports keep their capacity inside the unit class (see Dropship.d()).
        return switch(transportType){case "dropship","hovercraft"->4;case "nautilusSubmarine","nautilus"->3;default->0;};
    }

    @Override public void setup(Map<String,Object> meta,GameEngine e)throws Exception{
        for(BaseUnit unit:new ArrayList<>(BaseUnit.bE))unit.remove();
        GameObject.dL();
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team.teamId>=0)team.removeFromTeamRegistry();
        GameTeam self=new GameTeam(SELF_TEAM);self.teamName="Agent";self.teamColorId=0;
        self.credits=((Number)meta.get("initialCredits")).doubleValue();
        GameTeam enemy=new GameTeam(ENEMY_TEAM);enemy.teamName="Coast";enemy.teamColorId=1;enemy.credits=0;
        e.playerTeam=self;e.networkEngine.localPlayerTeam=self;e.networkEngine.roomSettings.incomeMultiplier=1;
        // One-time fog (fogMode 1): every tile starts unexplored (10) and only unit vision reveals
        // it. The periodic maintenance pass is off, so an explored tile never fades back and a
        // revealed map is the only thing that keeps the match fully visible (CASE1/CASE2 use that).
        e.tileMap.fogEnabled=true;e.tileMap.fogPeriodicMaintenanceEnabled=false;e.tileMap.fogRenderActive=false;
        // The engine only allocates the per-team fog arrays and its smoothing cache when the room
        // setting enables fog, so provide both before the first tick.
        e.tileMap.ensureFogCacheAllocated();
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team.teamId>=0){
            if(team.fogOfWarData==null)team.fogOfWarData=new byte[e.tileMap.tileCountX][e.tileMap.tileCountY];
            for(int x=0;x<e.tileMap.tileCountX;x++)for(int y=0;y<e.tileMap.tileCountY;y++)team.fogOfWarData[x][y]=10;
        }
        e.hasWonGame=false;e.hasLostGame=false;e.isStopped=false;e.isPaused=false;e.currentTick=0;e.gameTimeMillis=0;
        e.networkEngine.nextUnitId=1000;
        meta.put("transportCapacity",capacity((String)meta.get("transportType")));
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
            // Building bodies are drawn at rotationSpeed + 90, so the engine's -90 is what stands a
            // base texture upright; a mobile unit faces +x at 0.
            unit.rotationSpeed=isBuilding(unit)?-90f:0f;
            unit.buildProgress=1;unit.currentHealth=unit.maxHealth;
            unit.shield=unit.unitEnergyMax;unit.isActive=false;unit.n();PlayerTeam.c(unit);
            // The barrel follows movementLevels[0].targetX (0 = east by default): point it outward.
            if(unit.team==enemy&&spec.containsKey("facing")){
                float facing=((Number)spec.get("facing")).floatValue();
                unit.movementLevels[0].a(facing);
            }
            // The draft asks for T2 land factories; the upgrade level is the same flag the upgrade action sets.
            if("landFactory".equals(spec.get("type"))){unit.a(2);spec.put("upgradeLevel",2);}
        }
        GameObject.dL();e.unitSpatialIndex.a();e.pathfindingEngine.a(e.tileMap,false);
    }

    /** Target units alive, not carried and standing on the target island; used by the session. */
    @SuppressWarnings("unchecked")
    public static Map<String,Integer> delivered(GameEngine e,Map<String,Object> meta){
        Set<Integer> tiles=new HashSet<>((List<Integer>)meta.get("targetIslandTiles"));
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(Map<String,Object> need:(List<Map<String,Object>>)meta.get("requirements"))counts.put((String)need.get("type"),0);
        for(BaseUnit unit:BaseUnit.bE){
            if(unit==null||unit.isDead||unit.transportContainer!=null||unit.team==null||unit.team.teamId!=SELF_TEAM)continue;
            String type=unit.r()==null?null:unit.r().v();
            if(type==null||!counts.containsKey(type))continue;
            int tileX=(int)Math.floor(unit.posX/Case3Map.TILE),tileY=(int)Math.floor(unit.posY/Case3Map.TILE);
            if(tiles.contains(tileY*Case3Map.SIZE+tileX))counts.put(type,counts.get(type)+1);
        }
        return counts;
    }
    /** True when every requirement is met on the target island. */
    public static boolean goalReached(Map<String,Object> meta,Map<String,Integer> delivered){
        for(Map<String,Object> need:(List<Map<String,Object>>)meta.get("requirements")){
            int required=((Number)need.get("required")).intValue();
            if(delivered.getOrDefault((String)need.get("type"),0)<required)return false;
        }
        return true;
    }
}
