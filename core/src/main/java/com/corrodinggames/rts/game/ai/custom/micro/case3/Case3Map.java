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
 * 130x130 magma map with two compact grass islands (target top-left, base bottom-right).
 *
 * <p>The islands start as rectangles with the exact required tile count and are then jittered by
 * moving boundary tiles; every move keeps 4-connectivity and refuses tiles with fewer than two
 * island neighbours, so no one-tile necks or isolated grass tiles appear. Ground tiles come from
 * the stock Lava/Long Grass tilesets, exactly like the skirmish maps.</p>
 */
final class Case3Map {
    static final int SIZE=130;
    static final int TILE=20;
    static final int TARGET_AREA=400;
    static final int BASE_AREA=625;
    static final int TARGET_X=8,TARGET_Y=8;
    static final int BASE_X=96,BASE_Y=96;
    static final int TARGET_W=20,TARGET_H=20;
    static final int BASE_W=25,BASE_H=25;
    private static final int LAVA_GID=1+4;
    private static final int GRASS_GID=20;
    private Case3Map(){}

    static Set<Integer> targetIsland(long seed){return island(seed^0x51ED270BL,TARGET_AREA,TARGET_X,TARGET_Y,TARGET_W,TARGET_H);}
    static Set<Integer> baseIsland(long seed){return island(seed^0x2C9277B5L,BASE_AREA,BASE_X,BASE_Y,BASE_W,BASE_H);}

    static Set<Integer> island(long seed,int area,int ox,int oy,int w,int h){
        if(w*h!=area)throw new IllegalArgumentException("island rectangle must match its area");
        Set<Integer> tiles=new LinkedHashSet<>();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)tiles.add((oy+y)*SIZE+(ox+x));
        Random random=new Random(seed);
        int moves=area/16;
        for(int m=0;m<moves;m++){
            List<Integer> removable=new ArrayList<>();
            for(int key:tiles)if(neighbours(tiles,key)>=2&&connectedWithout(tiles,key))removable.add(key);
            if(removable.isEmpty())break;
            int remove=removable.get(random.nextInt(removable.size()));
            List<Integer> candidates=new ArrayList<>();
            for(int key:around(remove))if(!tiles.contains(key)&&inBounds(key)&&neighbours(tiles,key)>=2)candidates.add(key);
            if(candidates.isEmpty())break;
            int add=candidates.get(random.nextInt(candidates.size()));
            tiles.remove(remove);tiles.add(add);
        }
        return Collections.unmodifiableSet(tiles);
    }
    static int neighbours(Set<Integer> tiles,int key){
        int n=0;for(int k:around(key))if(tiles.contains(k))n++;return n;
    }
    static boolean connectedWithout(Set<Integer> tiles,int removed){
        int start=-1;for(int key:tiles)if(key!=removed){start=key;break;}
        if(start<0)return false;
        Set<Integer> seen=new HashSet<>();Deque<Integer> stack=new ArrayDeque<>();stack.push(start);seen.add(start);
        while(!stack.isEmpty()){
            int key=stack.pop();
            for(int k:around(key))if(k!=removed&&tiles.contains(k)&&seen.add(k))stack.push(k);
        }
        return seen.size()==tiles.size()-1;
    }
    static boolean inBounds(int key){
        int x=key%SIZE,y=key/SIZE;
        return x>=2&&y>=2&&x<SIZE-2&&y<SIZE-2;
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
