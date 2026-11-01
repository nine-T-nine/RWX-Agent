package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** A process-held lock selects one primary instance; every instance also has its own port file. */
public final class RwxCliPortFile implements AutoCloseable {
    private final Path primary;
    private final Path instance;
    private final String value;
    private final FileChannel channel;
    private final ScheduledExecutorService election;
    private FileLock lock;
    private boolean closed;

    public RwxCliPortFile(Path primary, int port) throws IOException {
        this.primary = primary.toAbsolutePath().normalize();
        this.value = Integer.toString(port);
        Files.createDirectories(this.primary.getParent());
        Path instances = this.primary.resolveSibling(this.primary.getFileName() + ".instances");
        Files.createDirectories(instances);
        this.instance = instances.resolve(ProcessHandle.current().pid() + "-" + port + ".port");
        this.channel = FileChannel.open(this.primary.resolveSibling(this.primary.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        atomicWrite(instance, value);
        claimPrimary();
        election = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "RWX-cli-port-election");
            thread.setDaemon(true);
            return thread;
        });
        election.scheduleWithFixedDelay(this::claimPrimary, 1, 1, TimeUnit.SECONDS);
    }

    public Path instancePath() { return instance; }
    public Path primaryPath() { return primary; }

    private synchronized void claimPrimary() {
        if (closed || lock != null) return;
        try {
            lock = channel.tryLock();
            if (lock != null) atomicWrite(primary, value);
        } catch (OverlappingFileLockException ignored) {
            // Another instance in this JVM (also exercised by tests) owns it.
        } catch (IOException error) {
            System.err.println("RWX CLI port election failed: " + error.getMessage());
            if (lock != null) {
                try { lock.release(); } catch (IOException ignored) { }
                lock = null;
            }
        }
    }

    static void atomicWrite(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".rwx-port-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        election.shutdownNow();
        try {
            // Still hold the primary lock: no successor can publish between read and delete.
            if (lock != null && Files.isRegularFile(primary)
                    && value.equals(Files.readString(primary, StandardCharsets.US_ASCII))) {
                Files.deleteIfExists(primary);
            }
            Files.deleteIfExists(instance);
        } catch (IOException error) {
            System.err.println("RWX CLI port cleanup failed: " + error.getMessage());
        } finally {
            try { channel.close(); } catch (IOException ignored) { }
        }
    }
}
