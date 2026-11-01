package com.corrodinggames.rts.game.ai.custom.micro.case1;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** Seed-local layout generator. Cliff patches and water pools grow from spread-out sampled centres. */
public final class Case1Map {
    public record Layout(int width,int height,int[] tiles,int ax,int ay,int bx,int by,int retries,List<Map<String,Object>> regions) { }
    /** Cliff tileset gid base: tile index 0..7 is one of the eight facing directions. */
    private static final int CLIFF_GID=200;
    private static final int[][] FOUR={{1,0},{0,1},{-1,0},{0,-1}};
    /** Heuristic fraction of the ideal spacing used as the centre-to-centre minimum. */
    private static final double CENTRE_SPACING=0.65;
    /** Minimum distance between any two cells of different patches. */
    private static final int PATCH_GAP=5;
    /** Distance every patch cell keeps from either spawn. */
    private static final double SPAWN_CLEARANCE=8;
    private Case1Map(){ }
    public static Layout generate(long seed,String task) {
        for(int retry=0;retry<128;retry++) {
            Random random=new Random(seed^((long)task.hashCode()<<32)^((long)retry*0x9E3779B97F4A7C15L));
            int w=80+random.nextInt(41),h=80+random.nextInt(41);
            int ax=w>=h?w/4:w/2,ay=w>=h?h/2:h/4,bx=w>=h?3*w/4:w/2,by=w>=h?h/2:3*h/4;
            // Counts first: five to ten cliffs plus five to ten pools, then one shared scatter.
            int cliffs=5+random.nextInt(6),pools=5+random.nextInt(6);
            // One pool is anchored on our side of the approach: at 1/3 or 1/4 of the way from the
            // mech spawn to the enemy spawn, so A-water--B always opens with water near us. The
            // remaining kinds are shuffled so cliffs and pools still mix freely elsewhere.
            List<Boolean> kinds=new ArrayList<>();
            kinds.add(Boolean.FALSE);
            List<Boolean> tail=new ArrayList<>();
            for(int i=0;i<cliffs;i++)tail.add(Boolean.TRUE);
            for(int i=1;i<pools;i++)tail.add(Boolean.FALSE);
            Collections.shuffle(tail,random);
            kinds.addAll(tail);
            int divisor=random.nextBoolean()?3:4;
            int[] anchor={ax+(bx-ax)/divisor,ay+(by-ay)/divisor};
            List<int[]> centres=sampleCentres(random,w,h,kinds.size(),ax,ay,bx,by,anchor);
            if(centres==null)continue;
            int[] tiles=new int[w*h];Arrays.fill(tiles,1);
            Set<Integer> occupied=new HashSet<>();List<Map<String,Object>> regions=new ArrayList<>();
            boolean ok=true;
            for(int i=0;i<kinds.size()&&ok;i++) {
                boolean cliff=kinds.get(i);
                int cx=centres.get(i)[0],cy=centres.get(i)[1];
                Set<Integer> cells=new LinkedHashSet<>();
                boolean placed=false;
                for(int attempt=0;attempt<60&&!placed;attempt++) {
                    cells.clear();
                    if(!growPatch(cells,cx,cy,w,h,10+random.nextInt(11),random,
                            k->freeCell(k,w,h,occupied,ax,ay,bx,by)))continue;
                    placed=true;
                }
                if(!placed){ok=false;break;}
                if(cliff) {
                    // Each cell points its sprite away from the patch centre, so a patch uses all
                    // eight facing sprites where its outline demands them.
                    List<Map<String,Object>> edge=new ArrayList<>();
                    double mx=0,my=0;for(int k:cells){mx+=k%w;my+=k/w;}
                    mx/=cells.size();my/=cells.size();
                    for(int k:cells) {
                        int facing=Math.floorMod((int)Math.round(Math.atan2(k/w-my,k%w-mx)/(Math.PI/4)),8);
                        tiles[k]=CLIFF_GID+facing;
                        edge.add(map("x",k%w,"y",k/w,"facing",facing));
                    }
                    regions.add(map("kind","cliff","seedX",cx,"seedY",cy,"area",cells.size(),
                            "anchor",i==0,"cells",new ArrayList<>(cells),"edge",edge));
                } else {
                    for(int k:cells)tiles[k]=100;
                    regions.add(map("kind","water","seedX",cx,"seedY",cy,"area",cells.size(),
                            "anchor",i==0,"cells",new ArrayList<>(cells)));
                }
                occupied.addAll(cells);
            }
            if(ok&&connected(w,h,tiles,ax,ay,bx,by))return new Layout(w,h,tiles,ax,ay,bx,by,retry,regions);
        }
        throw new IllegalStateException("Could not generate a valid map after 128 deterministic retries");
    }
    /**
     * Best-candidate sampling of patch centres: each slot scores a handful of random candidates by
     * the distance to the nearest already chosen centre, jittered by up to 15%, and keeps the best.
     * That is Poisson-disk-like (no clumping) while staying irregular, and the target spacing is
     * {@code 0.65 * sqrt(WH/N)}. Candidates too close to a spawn are rejected outright; when the
     * spacing cannot be met the best remaining candidate still wins so generation always progresses.
     * {@code anchor} is taken as the first centre verbatim (the near-side water seed), so the
     * remaining slots are spread around it. Returns null when a slot has no candidate at all
     * (caller retries).
     */
    private static List<int[]> sampleCentres(Random random,int w,int h,int count,int ax,int ay,int bx,int by,int[] anchor) {
        double spacing=CENTRE_SPACING*Math.sqrt((double)w*h/count);
        List<int[]> centres=new ArrayList<>();
        int margin=4;
        if(anchor!=null)centres.add(new int[]{anchor[0],anchor[1]});
        for(int slot=centres.size();slot<count;slot++) {
            int[] best=null;double bestScore=-1;
            for(int candidate=0;candidate<64;candidate++) {
                int x=margin+random.nextInt(Math.max(1,w-margin*2)),y=margin+random.nextInt(Math.max(1,h-margin*2));
                if(Math.hypot(x-ax,y-ay)<=SPAWN_CLEARANCE||Math.hypot(x-bx,y-by)<=SPAWN_CLEARANCE)continue;
                double nearest=Double.POSITIVE_INFINITY;
                for(int[] other:centres)nearest=Math.min(nearest,Math.hypot(x-other[0],y-other[1]));
                if(centres.isEmpty())nearest=spacing;
                double score=nearest*(0.85+random.nextDouble()*0.30);
                if(nearest>=spacing)score+=spacing;
                if(score>bestScore){bestScore=score;best=new int[]{x,y};}
            }
            if(best==null)return null;
            centres.add(best);
        }
        return centres;
    }
    /** True when a patch may claim this cell: inside the map, clear of both spawns and every patch. */
    private static boolean freeCell(int k,int w,int h,Set<Integer> occupied,int ax,int ay,int bx,int by) {
        int x=k%w,y=k/w;
        if(x<1||y<1||x>=w-1||y>=h-1||occupied.contains(k))return false;
        if(Math.hypot(x-ax,y-ay)<=SPAWN_CLEARANCE||Math.hypot(x-bx,y-by)<=SPAWN_CLEARANCE)return false;
        for(int other:occupied) {
            double dx=x-other%w,dy=y-other/w;
            if(dx*dx+dy*dy<PATCH_GAP*PATCH_GAP)return false;
        }
        return true;
    }
    /**
     * Random boundary growth: repeatedly pick one random cell on the current outline and absorb it,
     * then let its free neighbours join the outline. Not FIFO, so patches are lumpy rather than
     * breadth-first discs. Returns false when the outline runs out before reaching {@code area}.
     */
    private static boolean growPatch(Set<Integer> cells,int cx,int cy,int w,int h,int area,Random random,
            java.util.function.Predicate<Integer> allowed) {
        if(!allowed.test(cy*w+cx))return false;
        List<Integer> outline=new ArrayList<>();
        cells.add(cy*w+cx);
        addOutline(cells,outline,cy*w+cx,w,h);
        while(cells.size()<area&&!outline.isEmpty()) {
            int k=outline.remove(random.nextInt(outline.size()));
            if(cells.contains(k)||!allowed.test(k))continue;
            cells.add(k);
            addOutline(cells,outline,k,w,h);
        }
        return cells.size()==area;
    }
    private static void addOutline(Set<Integer> cells,List<Integer> outline,int k,int w,int h) {
        for(int[] d:FOUR) {
            int x=k%w+d[0],y=k/w+d[1];
            if(x<1||y<1||x>=w-1||y>=h-1)continue;
            int next=y*w+x;
            if(!cells.contains(next)&&!outline.contains(next))outline.add(next);
        }
    }
    public static boolean connected(int w,int h,int[] tiles,int ax,int ay,int bx,int by) {
        boolean[] seen=new boolean[w*h];ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(ay*w+ax);
        while(!queue.isEmpty()){int k=queue.remove();if(seen[k]||tiles[k]!=1)continue;seen[k]=true;
            if(k==by*w+bx)return true;int x=k%w,y=k/w;
            if(x>0)queue.add(k-1);if(x<w-1)queue.add(k+1);if(y>0)queue.add(k-w);if(y<h-1)queue.add(k+w);}
        return false;
    }
    public static void write(Layout layout,Path path)throws Exception {
        ByteBuffer bytes=ByteBuffer.allocate(layout.tiles.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int tile:layout.tiles)bytes.putInt(tile);
        String ground=encode(bytes.array());
        String items=encode(new byte[layout.tiles.length*4]);
        StringBuilder cliff=new StringBuilder();
        for(int i=0;i<8;i++)cliff.append("<tile id=\"").append(i).append("\"><properties><property name=\"cliff\"/><property name=\"randomTileBy\" value=\"0\"/></properties></tile>");
        String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><map version=\"1.2\" orientation=\"orthogonal\" width=\""+layout.width+"\" height=\""+layout.height+"\" tilewidth=\"20\" tileheight=\"20\">"
                +"<tileset firstgid=\"1\" name=\"grass\" tilewidth=\"20\" tileheight=\"20\"><image source=\"bitmaps/longgrass.png\" width=\"100\" height=\"160\"/><tile id=\"0\"><properties><property name=\"randomTileBy\" value=\"0\"/></properties></tile></tileset>"
                +"<tileset firstgid=\"100\" name=\"water\" tilewidth=\"20\" tileheight=\"20\"><image source=\"bitmaps/water.png\" width=\"20\" height=\"60\"/><tile id=\"0\"><properties><property name=\"water\"/><property name=\"randomTileBy\" value=\"0\"/></properties></tile></tileset>"
                +"<tileset firstgid=\""+CLIFF_GID+"\" name=\"cliff\" tilewidth=\"20\" tileheight=\"20\"><image source=\"bitmaps/mountain.png\" width=\"140\" height=\"100\"/>"+cliff+"</tileset>"
                +"<layer name=\"Ground\" width=\""+layout.width+"\" height=\""+layout.height+"\"><data encoding=\"base64\" compression=\"gzip\">"+ground+"</data></layer>"
                +"<layer name=\"Items\" width=\""+layout.width+"\" height=\""+layout.height+"\"><data encoding=\"base64\" compression=\"gzip\">"+items+"</data></layer>"
                +"<objectgroup name=\"triggers\"><object name=\"map_info\" x=\"0\" y=\"0\" width=\"1\" height=\"1\"><properties><property name=\"type\" value=\"skirmish\"/><property name=\"winCondition\" value=\"none\"/></properties></object></objectgroup></map>";
        Files.writeString(path,xml);
    }
    private static String encode(byte[] raw)throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(output)){gzip.write(raw);}
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
