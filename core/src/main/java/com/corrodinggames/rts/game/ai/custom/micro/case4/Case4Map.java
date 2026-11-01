package com.corrodinggames.rts.game.ai.custom.micro.case4;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * Rectangular continuous grassland, written as a stock TMX with the Long Grass tileset.
 *
 * <p>Both sides are drawn from {@code [75,80)} so a run gets a slightly non-square battlefield; the
 * exact size is fixed by the seed. The draft defines no terrain detail and asks for one continuous
 * walkable land mass, so the map carries no water, lava, cliffs or resource pools: every tile is
 * plain ground and the only obstacles are the buildings themselves.</p>
 */
final class Case4Map {
    static final int MIN_SIZE=75,MAX_SIZE=80;
    static final int TILE=20;
    private static final int GRASS_GID=20;
    private Case4Map(){}

    /** Width in tiles, always inside {@code [75,80)}. */
    static int width(long seed){return MIN_SIZE+(int)Math.floorMod(seed,MAX_SIZE-MIN_SIZE);}
    /** Height in tiles, always inside {@code [75,80)}. */
    static int height(long seed){return MIN_SIZE+(int)Math.floorMod(seed/(MAX_SIZE-MIN_SIZE),MAX_SIZE-MIN_SIZE);}

    static float world(int tile){return tile*TILE+TILE/2f;}

    static void write(Path file,int width,int height)throws Exception{
        ByteBuffer ground=ByteBuffer.allocate(width*height*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<width*height;i++)ground.putInt(GRASS_GID);
        ByteBuffer items=ByteBuffer.allocate(width*height*4).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder xml=new StringBuilder(64_000);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<map version=\"1.0\" tiledversion=\"2017.07.26\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
           .append(width).append("\" height=\"").append(height).append("\" tilewidth=\"20\" tileheight=\"20\">\n")
           .append(" <tileset firstgid=\"20\" name=\"grass\" tilewidth=\"20\" tileheight=\"20\" tilecount=\"40\" columns=\"5\">\n")
           .append("  <image source=\"bitmaps/longgrass.png\" width=\"100\" height=\"160\"/>\n")
           .append("  <tile id=\"0\"><properties><property name=\"randomTileBy\" value=\"0\"/></properties></tile>\n")
           .append(" </tileset>\n")
           .append(" <layer name=\"Ground\" width=\"").append(width).append("\" height=\"").append(height).append("\">\n")
           .append("  <data encoding=\"base64\" compression=\"gzip\">").append(gzip(ground.array())).append("</data>\n")
           .append(" </layer>\n")
           .append(" <layer name=\"Items\" width=\"").append(width).append("\" height=\"").append(height).append("\">\n")
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
