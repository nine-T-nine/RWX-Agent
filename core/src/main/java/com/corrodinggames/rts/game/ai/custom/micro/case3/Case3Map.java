package com.corrodinggames.rts.game.ai.custom.micro.case3;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * 130x130 magma map with two irregular grass islands (target top-left, base bottom-right).
 *
 * <p>Both islands are ellipses whose outline is perturbed by one-dimensional Perlin-style value
 * noise sampled along the angle, so the coastline is wavy but the shape stays star-shaped: no necks,
 * no isolated tiles and no holes. The long axis lies on the {@code y=-x} diagonal (perpendicular to
 * the line joining the two island centres) and the short axis on {@code y=x}, with a 2:1 axis ratio.
 * The noise is
 * fixed per seed and the long semi-axis is binary-searched until the tile count lands inside the
 * area budget (1375..1688 for the target, 700..800 for the base). Ground tiles come from the stock
 * Lava/Long Grass tilesets, exactly like the skirmish maps.</p>
 */
final class Case3Map {
    static final int SIZE=130;
    static final int TILE=20;
    static final int TARGET_MIN_AREA=1375,TARGET_MAX_AREA=1688;
    static final int BASE_MIN_AREA=700,BASE_MAX_AREA=800;
    static final int TARGET_CX=30,TARGET_CY=30;
    static final int BASE_CX=106,BASE_CY=106;
    /** Short semi-axis as a fraction of the long one: a 2:1 ellipse whose long axis is y=-x. */
    static final double ELLIPSE_RATIO=0.5;
    /** Bounding half-extent along x or y per unit long semi-axis, for a 45-degree rotated ellipse. */
    static final double ELLIPSE_EXTENT=Math.sqrt((1+ELLIPSE_RATIO*ELLIPSE_RATIO)/2);
    private static final double INV_SQRT2=1/Math.sqrt(2);
    /** Coastline wobble as a fraction of the disc radius. */
    private static final double EDGE_AMPLITUDE=0.22;
    private static final int NOISE_POINTS=24;
    private static final int NOISE_OCTAVES=3;
    private static final int MARGIN=2;
    private static final int LAVA_GID=1+4;
    private static final int GRASS_GID=20;
    private Case3Map(){}

    static Set<Integer> targetIsland(long seed){return island(seed^0x51ED270BL,TARGET_MIN_AREA,TARGET_MAX_AREA,TARGET_CX,TARGET_CY);}
    static Set<Integer> baseIsland(long seed){return island(seed^0x2C9277B5L,BASE_MIN_AREA,BASE_MAX_AREA,BASE_CX,BASE_CY);}

    /**
     * Ellipse of the requested area with a Perlin-perturbed coastline. The long axis lies on the
     * {@code y=-x} diagonal (perpendicular to the line between the two island centres) and the short
     * axis on {@code y=x}; the short semi-axis is {@link #ELLIPSE_RATIO} of the long one.
     */
    static Set<Integer> island(long seed,int minArea,int maxArea,int cx,int cy){
        Random random=new Random(seed);
        double[] control=new double[NOISE_POINTS];
        for(int i=0;i<control.length;i++)control[i]=random.nextDouble()*2-1;
        double target=(minArea+maxArea)/2.0;
        // The tile count grows monotonically with the semi-major axis, so a plain bisection is
        // enough. The cap is the largest axis whose rotated bounding box still fits the margin.
        double low=3,high=(Math.min(cx,cy)-MARGIN-1)/ELLIPSE_EXTENT;
        Set<Integer> tiles=rasterize(control,cx,cy,high);
        if(tiles.size()<minArea)throw new IllegalStateException("island radius is capped by the map margin");
        for(int step=0;step<40;step++){
            double mid=(low+high)/2;
            Set<Integer> candidate=rasterize(control,cx,cy,mid);
            if(candidate.size()<target)low=mid;else{high=mid;tiles=candidate;}
        }
        if(tiles.size()<minArea||tiles.size()>maxArea)
            throw new IllegalStateException("island area "+tiles.size()+" is outside "+minArea+".."+maxArea);
        return Collections.unmodifiableSet(tiles);
    }

    /**
     * Samples one ellipse into tiles. {@code axis} is the long semi-axis: the boundary is
     * {@code (u/axis')² + (v/(axis'*ratio))² = 1} in the rotated frame, with {@code u} along
     * {@code u} along {@code y=-x}, {@code v} along {@code y=x} and the same Perlin wobble applied to both axes so
     * the shape stays star-shaped about the centre.
     */
    private static Set<Integer> rasterize(double[] control,int cx,int cy,double axis){
        Set<Integer> tiles=new LinkedHashSet<>();
        int reach=(int)Math.ceil(axis*ELLIPSE_EXTENT*(1+EDGE_AMPLITUDE))+1;
        for(int y=cy-reach;y<=cy+reach;y++){
            if(y<MARGIN||y>=SIZE-MARGIN)continue;
            for(int x=cx-reach;x<=cx+reach;x++){
                if(x<MARGIN||x>=SIZE-MARGIN)continue;
                double dx=x-cx,dy=y-cy;
                double angle=Math.atan2(dy,dx)/(Math.PI*2);
                if(angle<0)angle+=1;
                double wobble=1+EDGE_AMPLITUDE*noise(control,angle);
                double longAxis=axis*wobble,shortAxis=longAxis*ELLIPSE_RATIO;
                // Rotate by -45 degrees: u runs along y=-x (long axis, lower-left to upper-right),
                // v along y=x (short axis, the line between the two island centres).
                double u=(dx-dy)*INV_SQRT2,v=(dx+dy)*INV_SQRT2;
                if(u*u/(longAxis*longAxis)+v*v/(shortAxis*shortAxis)<=1)tiles.add(y*SIZE+x);
            }
        }
        return tiles;
    }

    /** Fractal value noise on the unit circle: periodic in {@code t}, so the seam at 0 matches. */
    static double noise(double[] control,double t){
        double sum=0,amplitude=1,frequency=1,normaliser=0;
        for(int octave=0;octave<NOISE_OCTAVES;octave++){
            sum+=amplitude*sample(control,(t*frequency)%1.0);
            normaliser+=amplitude;
            amplitude*=0.5;
            frequency*=2;
        }
        return sum/normaliser;
    }
    private static double sample(double[] control,double t){
        int size=control.length;
        double position=t*size;
        int index=(int)Math.floor(position);
        double fraction=position-index;
        double smooth=fraction*fraction*(3-2*fraction);
        double left=control[((index%size)+size)%size];
        double right=control[(((index+1)%size)+size)%size];
        return left+(right-left)*smooth;
    }

    static int neighbours(Set<Integer> tiles,int key){
        int n=0;for(int k:around(key))if(tiles.contains(k))n++;return n;
    }
    /** Breadth-first distance from the coastline, used to offset the turret ring inwards. */
    static Map<Integer,Integer> coastDepth(Set<Integer> tiles){
        Map<Integer,Integer> depth=new HashMap<>();
        Deque<Integer> queue=new ArrayDeque<>();
        for(int key:tiles)if(neighbours(tiles,key)<4){depth.put(key,0);queue.add(key);}
        while(!queue.isEmpty()){
            int key=queue.poll();
            int next=depth.get(key)+1;
            for(int neighbour:around(key)){
                if(!tiles.contains(neighbour)||depth.containsKey(neighbour))continue;
                depth.put(neighbour,next);queue.add(neighbour);
            }
        }
        return depth;
    }
    static boolean inBounds(int key){
        int x=key%SIZE,y=key/SIZE;
        return x>=MARGIN&&y>=MARGIN&&x<SIZE-MARGIN&&y<SIZE-MARGIN;
    }
    static List<Integer> around(int key){
        int x=key%SIZE,y=key/SIZE;
        List<Integer> out=new ArrayList<>(4);
        if(x>0)out.add(key-1);
        if(x<SIZE-1)out.add(key+1);
        if(y>0)out.add(key-SIZE);
        if(y<SIZE-1)out.add(key+SIZE);
        return out;
    }
    static int x(int key){return key%SIZE;}
    static int y(int key){return key/SIZE;}
    static float worldX(int tile){return tile*TILE+TILE/2f;}
    static float worldY(int tile){return tile*TILE+TILE/2f;}

    static void write(Path file,Set<Integer> target,Set<Integer> base)throws Exception{
        ByteBuffer ground=ByteBuffer.allocate(SIZE*SIZE*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<SIZE*SIZE;i++)ground.putInt(base.contains(i)||target.contains(i)?GRASS_GID:LAVA_GID);
        ByteBuffer items=ByteBuffer.allocate(SIZE*SIZE*4).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder xml=new StringBuilder(64_000);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<map version=\"1.0\" tiledversion=\"2017.07.26\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
           .append(SIZE).append("\" height=\"").append(SIZE).append("\" tilewidth=\"20\" tileheight=\"20\">\n")
           .append(" <tileset firstgid=\"1\" name=\"Lava\" tilewidth=\"20\" tileheight=\"20\" tilecount=\"15\" columns=\"3\">\n")
           .append("  <image source=\"bitmaps/lava.png\" trans=\"008a76\" width=\"60\" height=\"100\"/>\n");
        for(int id=0;id<15;id++){
            if(id==4)xml.append("  <tile id=\"4\"><properties><property name=\"lava\"/></properties></tile>\n");
            else xml.append("  <tile id=\"").append(id).append("\"><properties><property name=\"lava-cliff\"/></properties></tile>\n");
        }
        xml.append(" </tileset>\n")
           .append(" <tileset firstgid=\"20\" name=\"grass\" tilewidth=\"20\" tileheight=\"20\" tilecount=\"40\" columns=\"5\">\n")
           .append("  <image source=\"bitmaps/longgrass.png\" width=\"100\" height=\"160\"/>\n")
           .append("  <tile id=\"0\"><properties><property name=\"randomTileBy\" value=\"0\"/></properties></tile>\n")
           .append(" </tileset>\n")
           .append(" <layer name=\"Ground\" width=\"").append(SIZE).append("\" height=\"").append(SIZE).append("\">\n")
           .append("  <data encoding=\"base64\" compression=\"gzip\">").append(gzip(ground.array())).append("</data>\n")
           .append(" </layer>\n")
           .append(" <layer name=\"Items\" width=\"").append(SIZE).append("\" height=\"").append(SIZE).append("\">\n")
           .append("  <data encoding=\"base64\" compression=\"gzip\">").append(gzip(items.array())).append("</data>\n")
           .append(" </layer>\n")
           .append("</map>\n");
        Files.writeString(file,xml.toString(),StandardCharsets.UTF_8);
    }
    private static String gzip(byte[] raw)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(raw);}
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
