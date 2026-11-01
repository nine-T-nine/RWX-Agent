package com.corrodinggames.rts.game.ai.custom.micro.case2;

import com.corrodinggames.rts.game.*;
import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.micro.case1.Case1Map;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.*;
import com.corrodinggames.rts.game.units.custom.logic.CustomAction;
import com.corrodinggames.rts.gameFramework.*;
import com.corrodinggames.rts.gameFramework.GameObject;
import java.nio.file.*;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * Flat, single-builder economy task. The square map side comes from the classic Poisson-disk
 * density (0.7 nodes per spacing^2) over the area the builder can actually use, then Bridson
 * sampling places the nodes with a hard minimum spacing. All economy data comes from the loaded
 * engine definitions.
 */
public final class Case2Lte implements MicroCase {
    /** Minimum distance between two resource nodes, in tiles. */
    public static final double SPACING=20;
    /** The builder spawns at the centre and no node may be closer than this many tiles. */
    public static final double CENTER_CLEARANCE=15;
    /** Classic Poisson-disk density: about 0.7 points per spacing^2. */
    private static final double DENSITY=0.7;
    private static final int TILE=20;
    /** Fixed starting credits for every task. */
    public static final int CREDITS=2100;
    /**
     * Resource-pool tileset copied from the stock skirmish maps: the misc sheet's tile 4 carries
     * the {@code res_pool} property, so the pool renders with the stock sprite instead of terrain.
     */
    private static final int RESOURCE_TILESET_GID=100;
    private static final int RESOURCE_GID=RESOURCE_TILESET_GID+4;
    private static final String RESOURCE_TILESET="<tileset firstgid=\""+RESOURCE_TILESET_GID+"\" name=\"misc\" tilewidth=\"20\" tileheight=\"20\" tilecount=\"18\" columns=\"3\">"
            +"<image source=\"bitmaps/misc.png\" width=\"79\" height=\"120\"/>"
            +"<tile id=\"4\"><properties><property name=\"res_pool\"/></properties></tile></tileset>";
    private static final int MAX_SAMPLING_ATTEMPTS=256;

    public record Task(int nodes,int tier) { }
    public static Task task(String name) {
        if(name==null||!name.matches("2_[4-8]_[1-5]")) throw new IllegalArgumentException("task must be 2_<nodes 4-8>_<tier 1-5>");
        return new Task(name.charAt(2)-'0',name.charAt(4)-'0');
    }
    /**
     * {@code a = ceil(sqrt(Y * spacing^2 / 0.7 + pi * clearance^2))}: the classic density fills the
     * usable area, and the builder's clearance disk is added back so the nodes still fit.
     */
    public static int mapSize(int nodes) {
        return (int)Math.ceil(Math.sqrt(nodes*SPACING*SPACING/DENSITY+Math.PI*CENTER_CLEARANCE*CENTER_CLEARANCE));
    }
    public static String composition(Task t) {
        int half=t.nodes()/2,rest=t.nodes()-half;
        return switch(t.tier()) {
            case 1 -> t.nodes()+" x T1";
            case 2 -> half+" x T1 + "+rest+" x T2";
            case 3 -> t.nodes()+" x T2";
            case 4 -> half+" x T2 + "+rest+" x T3";
            default -> t.nodes()+" x T3";
        };
    }
    /** Target income for a tier: floor(Y/2) of the lower level plus the rest of the higher one. */
    public static int target(Task t,int t1,int t2,int t3) {
        int half=t.nodes()/2,rest=t.nodes()-half;
        return switch(t.tier()) {
            case 1 -> t.nodes()*t1;
            case 2 -> half*t1+rest*t2;
            case 3 -> t.nodes()*t2;
            case 4 -> half*t2+rest*t3;
            default -> t.nodes()*t3;
        };
    }
    private List<Map<String,Object>> nodes;
    public static UnitType type(int level) {
        UnitType t=UnitTypeEnum.getUnitTypeByName("extractorT"+level);
        if(t==null)throw new IllegalStateException("Required custom extractorT"+level+" is not loaded");return t;
    }
    public static int incomeOf(int level) {
        return (int)Math.round(((OrderableUnit)BaseUnit.getPrototypeForUnitType(type(level))).getCreditIncomeRate());
    }
    public static int level(BaseUnit u){for(int n=1;n<=3;n++)if(u.r()==type(n))return n;return 0;}
    public static AbstractUnitAction upgrade(OrderableUnit u) {
        int next=level(u)+1;
        for(Object item:u.getAvailableActions())if(item instanceof CustomAction action
                &&action.actionDef.convertTo!=null&&action.actionDef.convertTo.c()==type(next))return action;
        throw new IllegalStateException("Missing standard extractor upgrade");
    }
    /** PathCostMap blocks the res_pool tile for LAND, so the builder stands on a neighbour. */
    private static final int[][] APPROACH={{1,0},{-1,0},{0,1},{0,-1}};
    /** World walking distance to the closest standable tile next to a resource node; -1 when none. */
    public static int approachDistance(AiMapGraph.DistanceField field,int tileX,int tileY) {
        int best=-1;
        for(int[] d:APPROACH) {
            int value=field.distanceAt(tileX+d[0],tileY+d[1]);
            if(value>=0&&(best<0||value<best))best=value;
        }
        return best<0?-1:best*TILE;
    }
    @Override public String id(){return "CASE2-LTE";}
    /** CASE2's command surface: the LTE case commands only, no full-game observations. */
    private static final com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command[] COMMANDS={
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_STATE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_BUILDER,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_RESOURCES,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_BUILD,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_UPGRADE,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_BEGIN,
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_HELP};
    @Override public MicroCommandProfile profile(MicroConfig c){return new MicroCommandProfile(c.track,COMMANDS);}
    @Override public MicroCase.TaskInfo taskInfo(String task){task(task);return new MicroCase.TaskInfo(0,0,1200,1320);}
    @Override public String helpCommand(){return com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command.LTE_HELP.commandName();}
    @Override public boolean reportsScenarioFailure(){return true;}
    @Override public MicroSession session(MicroRuntime run,Map<String,Object> meta)throws Exception{return new LteSession(run,meta);}
    @Override public Path prepareMap(MicroConfig c,Path dir)throws Exception {
        Task t=task(c.task);
        int size=mapSize(t.nodes());
        nodes=nodes(c.seed,t.nodes(),size);
        int[] tiles=new int[size*size];Arrays.fill(tiles,1);
        Path file=dir.resolve("map.tmx");
        Case1Map.write(new Case1Map.Layout(size,size,tiles,size/2,size/2,size/2,size/2,0,List.of()),file);
        // Resource pools use the stock "misc" tileset (tile 4), exactly like assets/maps/skirmish.
        String xml=Files.readString(file);
        int start=xml.indexOf("<tileset firstgid=\""+RESOURCE_TILESET_GID+"\"");
        int end=xml.indexOf("</tileset>",start)+"</tileset>".length();
        xml=xml.substring(0,start)+RESOURCE_TILESET+xml.substring(end);
        // Extractor placement reads the Items layer, not Ground (TileMap.getPathingOverrideTileAt).
        java.nio.ByteBuffer items=java.nio.ByteBuffer.allocate(size*size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for(var p:nodes)items.putInt((((Number)p.get("tileY")).intValue()*size+((Number)p.get("tileX")).intValue())*4,RESOURCE_GID);
        var bytes=new java.io.ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(items.array());}
        int from=xml.indexOf('>',xml.indexOf("<data",xml.indexOf("<layer name=\"Items\"")))+1;
        xml=xml.substring(0,from)+Base64.getEncoder().encodeToString(bytes.toByteArray())+xml.substring(xml.indexOf("</data>",from));
        Files.writeString(file,xml);return file;
    }
    @Override public Map<String,Object> layout(MicroConfig c,GameEngine e) {
        Task t=task(c.task);
        int size=mapSize(t.nodes());
        float spawn=size*TILE/2f;
        int target=target(t,incomeOf(1),incomeOf(2),incomeOf(3));
        return map("builder",map("id",1000,"type","builder","spawn",map("x",spawn,"y",spawn)),
                "initialCredits",CREDITS,"resourceCount",t.nodes(),"resourceNodes",nodes,
                "tier",t.tier(),"targetIncomePerSecond",target,"targetComposition",composition(t),
                "mapSize",size,"poissonSpacingTiles",SPACING,"centerClearanceTiles",CENTER_CLEARANCE,
                "units",new ArrayList<>(List.of(map("id",1000L,"alias","B1","team",0,"type","builder","x",spawn,"y",spawn))),
                "enemyPolicy",null,"incomeClock",map("generationPeriodTicks",40,"nominalTicksPerSecond",60,"gameMsPerTick",16));
    }
    @Override public void setup(Map<String,Object> meta,GameEngine e) {
        for(BaseUnit u:new ArrayList<>(BaseUnit.bE))u.remove();GameObject.dL();
        // Remove unused player slots too: this case has no opponent or AI team.
        for(PlayerTeam team:PlayerTeam.getSortedTeams(true))if(team.teamId>=0)team.removeFromTeamRegistry();
        float spawn=((Number)meta.get("mapSize")).floatValue()*TILE/2f;
        GameTeam self=new GameTeam(0);self.teamName="Agent";self.teamColorId=0;self.credits=((Number)meta.get("initialCredits")).doubleValue();
        e.playerTeam=self;e.networkEngine.localPlayerTeam=self;e.networkEngine.roomSettings.incomeMultiplier=1;
        e.tileMap.fogEnabled=false;e.tileMap.fogPeriodicMaintenanceEnabled=false;e.tileMap.fogRenderActive=false;
        e.hasWonGame=false;e.hasLostGame=false;e.isStopped=false;e.isPaused=false;e.currentTick=0;e.gameTimeMillis=0;
        e.networkEngine.nextUnitId=1000;
        OrderableUnit builder=(OrderableUnit)UnitTypeEnum.builder.a();builder.setUnitTeam(self);
        builder.posX=spawn;builder.posY=spawn;builder.rotationSpeed=0;builder.buildProgress=1;builder.n();PlayerTeam.c(builder);
        GameObject.dL();e.unitSpatialIndex.a();e.pathfindingEngine.a(e.tileMap,false);
        UnitType t1=type(1);OrderableUnit probe=(OrderableUnit)BaseUnit.getPrototypeForUnitType(t1);
        AiMapGraph land=AiMapGraph.of(UnitMovementType.LAND);
        if(land==null)throw new IllegalStateException("LAND path map is not ready");
        var approach=land.distanceFieldFrom(spawn,spawn);
        for(var p:nodes) {
            probe.posX=((Number)p.get("x")).floatValue();probe.posY=((Number)p.get("y")).floatValue();
            int distance=approachDistance(approach,((Number)p.get("tileX")).intValue(),((Number)p.get("tileY")).intValue());
            if(!probe.canPlaceAtCurrentPosition(self)||distance<0)
                throw new IllegalStateException("Invalid resource node: "+p.get("resourceId")+" placement="+probe.getPlacementFailureReason(false,self)+" approachDistance="+distance);
        }
        List<Map<String,Object>> economy=new ArrayList<>();List<String> allowed=new ArrayList<>(),forbidden=new ArrayList<>();
        AbstractUnitAction build=builder.findActionForUnitTypeWithQueueSize(t1,1,false);
        if(build==null)throw new IllegalStateException("builder cannot build extractorT1");
        allowed.add(build.getActionIdString());
        for(int level=1;level<=3;level++) {
            OrderableUnit unit=(OrderableUnit)BaseUnit.getPrototypeForUnitType(type(level));
            AbstractUnitAction action=level<3?upgrade(unit):null;
            if(action!=null)allowed.add(action.getActionIdString());
            else for(Object a:unit.getAvailableActions())forbidden.add(((AbstractUnitAction)a).getActionIdString());
            economy.add(map("level",level,"type",unit.r().v(),"incomePerSecond",unit.getCreditIncomeRate(),
                    "buildCost",level==1?build.getCostAmount():null,"buildWorkTicks",level==1?1.0/builder.getRepairProgressRate(unit):null,
                    "upgradeActionId",action==null?null:action.getActionIdString(),"upgradeCost",action==null?null:action.getCostAmount(),
                    "upgradeWorkTicks",action==null?null:1.0/((PopupQueueAction)action).K(),"upgradeRequires","completed, queue empty, sufficient credits"));
        }
        double maximum=((Number)economy.get(2).get("incomePerSecond")).doubleValue()*nodes.size();
        double initial=self.b(com.corrodinggames.rts.game.units.custom.condition.resources.Resource.D);
        int target=((Number)meta.get("targetIncomePerSecond")).intValue();
        if(initial>=target||maximum<target||self.credits<build.getCostAmount())
            throw new IllegalStateException("Unreachable LTE task: target="+target+" maximum="+maximum);
        meta.put("economy",economy);meta.put("allowedActionIds",allowed);meta.put("forbiddenActionIds",forbidden);
        meta.put("builderMoveSpeed",builder.getMoveSpeed());meta.put("builderAcceleration",builder.getMoveAccelerationSpeed());
        meta.put("builderWarmupTicks",builder.e(0));meta.put("initialIncomePerSecond",initial);meta.put("maximumIncomePerSecond",maximum);
        meta.put("builderBuilds",builder.getAvailableActions().stream().map(a->((AbstractUnitAction)a).getActionIdString()).toList());
    }
    /**
     * Bridson's Poisson-disk sampling: a random seed point, then candidates in the annulus
     * [radius, 2*radius] accepted only when they keep the radius from every earlier point.
     */
    private static List<double[]> poisson(long seed,int size,double radius) {
        Random random=new Random(seed);
        double cell=radius/Math.sqrt(2);
        int gridSize=(int)Math.ceil(size/cell)+1;
        int[] grid=new int[gridSize*gridSize];Arrays.fill(grid,-1);
        List<double[]> points=new ArrayList<>();List<Integer> active=new ArrayList<>();
        double margin=2;
        double x0=margin+random.nextDouble()*(size-2*margin), y0=margin+random.nextDouble()*(size-2*margin);
        points.add(new double[]{x0,y0});grid[cellIndex(x0,y0,cell,gridSize)]=0;active.add(0);
        while(!active.isEmpty()) {
            int slot=random.nextInt(active.size());
            double[] origin=points.get(active.get(slot));
            boolean found=false;
            for(int k=0;k<30;k++) {
                double angle=random.nextDouble()*Math.PI*2, distance=radius*(1+random.nextDouble());
                double x=origin[0]+Math.cos(angle)*distance, y=origin[1]+Math.sin(angle)*distance;
                if(x<margin||y<margin||x>size-margin||y>size-margin)continue;
                if(!farEnough(points,grid,cell,gridSize,x,y,radius))continue;
                points.add(new double[]{x,y});grid[cellIndex(x,y,cell,gridSize)]=points.size()-1;active.add(points.size()-1);
                found=true;break;
            }
            if(!found)active.remove(slot);
        }
        return points;
    }
    private static int cellIndex(double x,double y,double cell,int gridSize) {
        return Math.min(gridSize-1,(int)(x/cell))+Math.min(gridSize-1,(int)(y/cell))*gridSize;
    }
    private static boolean farEnough(List<double[]> points,int[] grid,double cell,int gridSize,double x,double y,double radius) {
        int gx=(int)(x/cell), gy=(int)(y/cell);
        for(int yy=Math.max(0,gy-2);yy<=Math.min(gridSize-1,gy+2);yy++)for(int xx=Math.max(0,gx-2);xx<=Math.min(gridSize-1,gx+2);xx++) {
            int index=grid[xx+yy*gridSize];
            if(index<0)continue;
            double[] other=points.get(index);
            if((other[0]-x)*(other[0]-x)+(other[1]-y)*(other[1]-y)<radius*radius)return false;
        }
        return true;
    }
    /**
     * Deterministic node layout: sample with the hard spacing, drop the builder's clearance disk,
     * then keep the first {@code count} points after a seeded shuffle. A subset of a Poisson-disk
     * set keeps the spacing, and retries only change the sampling seed, never the map size.
     */
    public static List<Map<String,Object>> nodes(long seed,int count,int size) {
        double centre=size/2.0;
        for(int attempt=0;attempt<MAX_SAMPLING_ATTEMPTS;attempt++) {
            List<double[]> points=poisson(seed*1_000_003L+attempt,size,SPACING);
            List<int[]> candidates=new ArrayList<>();
            for(double[] p:points) {
                int tx=(int)Math.floor(p[0]),ty=(int)Math.floor(p[1]);
                if(Math.hypot(tx+0.5-centre,ty+0.5-centre)<CENTER_CLEARANCE)continue;
                candidates.add(new int[]{tx,ty});
            }
            Collections.shuffle(candidates,new Random(seed*31+attempt));
            List<int[]> chosen=new ArrayList<>();
            for(int[] candidate:candidates) {
                boolean keep=true;
                for(int[] other:chosen)if(Math.hypot(candidate[0]-other[0],candidate[1]-other[1])<SPACING){keep=false;break;}
                if(keep){chosen.add(candidate);if(chosen.size()==count)break;}
            }
            if(chosen.size()<count)continue;
            chosen.sort(Comparator.comparingInt((int[] p)->p[1]).thenComparingInt(p->p[0]));
            List<Map<String,Object>> result=new ArrayList<>();
            for(int i=0;i<chosen.size();i++) {
                int[] p=chosen.get(i);
                result.add(map("resourceId","R"+(i+1),"tileX",p[0],"tileY",p[1],"x",p[0]*TILE+TILE/2,"y",p[1]*TILE+TILE/2));
            }
            return result;
        }
        throw new IllegalStateException("Poisson sampling could not place "+count+" nodes on a "+size+"x"+size+" map");
    }
}
