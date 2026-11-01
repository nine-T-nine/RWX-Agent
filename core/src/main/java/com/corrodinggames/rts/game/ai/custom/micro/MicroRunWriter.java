package com.corrodinggames.rts.game.ai.custom.micro;

import io.github.rwx.app.MicroJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Evidence only: no metrics, scores or policy decisions. */
public final class MicroRunWriter implements AutoCloseable {
    public final Path directory;
    private final BufferedWriter gateway;
    public MicroRunWriter(Path directory)throws IOException {
        this.directory=directory;Files.createDirectories(directory);
        gateway=Files.newBufferedWriter(directory.resolve("gateway.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    public synchronized void gateway(Map<String,Object> row) {line(gateway,row);}
    public static void line(BufferedWriter writer,Object row) {
        try{writer.write(MicroJson.encode(row));writer.newLine();writer.flush();}
        catch(IOException e){throw new IllegalStateException("Could not write micro evidence",e);}
    }
    public void json(String name,Object value) {
        try {
            Path path=directory.resolve(name),temporary=directory.resolve(name+".tmp");
            Files.writeString(temporary,MicroJson.encode(value)+"\n",StandardCharsets.UTF_8);
            try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){throw new IllegalStateException("Could not finalize micro evidence",e);}
    }
    @Override public synchronized void close()throws IOException{gateway.close();}
}
