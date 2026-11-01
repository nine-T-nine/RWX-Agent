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
 * 180x180 continuous grassland, written as a stock TMX with the Long Grass tileset.
 *
 * <p>The draft defines no terrain detail and asks for one continuous walkable land mass, so the
 * map carries no water, lava, cliffs or resource pools: every tile is plain ground and the only
 * obstacles are the buildings themselves.</p>
 */
final class Case4Map {
    static final int SIZE=180;
    static final int TILE=20;
    private static final int GRASS_GID=20;
    private Case4Map(){}

    static float world(int tile){return tile*TILE+TILE/2f;}

    static void write(Path file)throws Exception{
        ByteBuffer ground=ByteBuffer.allocate(SIZE*SIZE*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<SIZE*SIZE;i++)ground.putInt(GRASS_GID);
        ByteBuffer items=ByteBuffer.allocate(SIZE*SIZE*4).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder xml=new StringBuilder(64_000);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<map version=\"1.0\" tiledversion=\"2017.07.26\" orientation=\"orthogonal\" renderorder=\"right-down\" width=\"")
           .append(SIZE).append("\" height=\"").append(SIZE).append("\" tilewidth=\"20\" tileheight=\"20\">\n")
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
