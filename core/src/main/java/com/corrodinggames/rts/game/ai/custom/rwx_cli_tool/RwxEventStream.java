package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;

/** Local delivery journal over snapshots from the authoritative event store. */
final class RwxEventStream implements AutoCloseable {
    private static volatile RwxEventStream active;
    private static final int FRAME_LIMIT = 64, JOURNAL_LIMIT = 1000;
    private static final String PROTOCOL = "rwx-events-v1";
    private final ServerSocket server;
    private final RwxCliPortFile portFile;
    private final String session = UUID.randomUUID().toString();
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(FRAME_LIMIT);
    private final ArrayDeque<Message> journal = new ArrayDeque<>();
    private final ExecutorService clients = new ThreadPoolExecutor(16, 16, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> daemon(r, "RWX-events-client"));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private Map<GameEvent, State> previous = new IdentityHashMap<>();
    private long nextId, sequence;
    private volatile boolean closed;
    private final AtomicBoolean overflow = new AtomicBoolean();
    private int now;
    private Thread processor;

    private record Frame(List<GameEvent> events, int now, boolean reset) { }
    /** `lastTime` is the newest contributing occurrence, used to scope identity fallbacks. */
    private record State(long id, int revision, EventRecord record, String side, int lastTime) { }
    private record Message(long seq, int time, String type, String side, String json) { }

    static synchronized RwxEventStream start(Path path) throws IOException {
        if (active != null) throw new IOException("Event service already running in this process");
        ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        RwxCliPortFile file;
        try { file = new RwxCliPortFile(path, socket.getLocalPort()); }
        catch (IOException error) { socket.close(); throw error; }
        RwxEventStream stream = new RwxEventStream(socket, file);
        active = stream;
        stream.processor = daemon(stream::process, "RWX-events-processor");
        stream.processor.start();
        daemon(stream::accept, "RWX-events-accept").start();
        System.out.println("RWX_EVENTS_READY port=" + socket.getLocalPort() + " host=127.0.0.1 portFile="
                + file.primaryPath() + " instancePortFile=" + file.instancePath());
        return stream;
    }

    private RwxEventStream(ServerSocket server, RwxCliPortFile portFile) {
        this.server = server; this.portFile = portFile;
    }

    static boolean isRunning() { return active != null; }
    static void publish(List<GameEvent> events, int now) {
        RwxEventStream stream = active;
        if (stream != null && !stream.frames.offer(new Frame(events, now, false))) stream.overflow.set(true);
    }
    static void reset() {
        RwxEventStream stream = active;
        if (stream != null && !stream.frames.offer(new Frame(List.of(), 0, true))) {
            stream.frames.clear();
            stream.overflow.set(true);
            stream.frames.offer(new Frame(List.of(), 0, true));
        }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }

    private void process() {
        while (!closed) {
            try {
                Frame frame = frames.take();
                if (overflow.getAndSet(false)) {
                    add("overflow", frame.now, null, null, "\"message\":\"event processing queue overflow; intermediate revisions may be missing\"");
                }
                if (frame.reset || frame.now < now) {
                    previous = new IdentityHashMap<>(); now = 0;
                    synchronized (this) { journal.clear(); }
                    add("reset", frame.now, null, null, "\"message\":\"event history reset\"");
                    if (frame.reset) continue;
                }
                now = frame.now;
                Map<GameEvent, State> current = new IdentityHashMap<>();
                Set<GameEvent> matched = Collections.newSetFromMap(new IdentityHashMap<>());
                collect(frame, false, List.of(EventType.values()), current, matched);
                collect(frame, true, List.of(EventType.LOSSES, EventType.DESTROYED), current, matched);
                for (Map.Entry<GameEvent, State> row : previous.entrySet()) {
                    if (!matched.contains(row.getKey())) {
                        State state = row.getValue();
                        boolean retained = state.record.contributors.stream().anyMatch(frame.events::contains);
                        String reason = retained ? "replaced" : "expired";
                        add("remove", frame.now, state.record.type.name().toLowerCase(Locale.ROOT), state.side,
                                "\"eventId\":" + state.id + ",\"revision\":" + (state.revision + 1)
                                        + ",\"reason\":\"" + reason + "\"");
                    }
                }
                previous = current;
                prune(frame.now);
            } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
            catch (RuntimeException error) {
                add("overflow", now, null, null, "\"message\":\"event processing failed: "
                        + RwxCliText.json(error.toString()) + "\"");
                System.err.println("RWX event processing failed: " + error);
            }
        }
    }

    private void collect(Frame frame, boolean allies, List<EventType> types, Map<GameEvent, State> result,
                         Set<GameEvent> matched) {
        EventPage page = RwxEventProcessor.query(frame.events, frame.now, new EventQuery(types, 180, allies), Integer.MAX_VALUE);
        for (EventRecord record : page.items) {
            GameEvent source = record.source;
            String side = allies ? "ally" : "self";
            GameEvent previousKey = previous.containsKey(source) && !matched.contains(source) ? source : null;
            if (previousKey == null) {
                for (Map.Entry<GameEvent, State> candidate : previous.entrySet()) {
                    if (!matched.contains(candidate.getKey()) && candidate.getValue().side.equals(side)
                            && overlaps(candidate.getValue(), record)) {
                        previousKey = candidate.getKey();
                        break;
                    }
                }
            }
            State prior = previousKey == null ? null : previous.get(previousKey);
            if (previousKey != null) matched.add(previousKey);
            long id = prior == null ? ++nextId : prior.id;
            int revision = prior == null ? 1 : prior.revision;
            if (prior == null || !prior.record.description.equals(record.description)
                    || prior.record.gameTimeMillis != record.gameTimeMillis) {
                if (prior != null) revision++;
                String eventType = record.type.name().toLowerCase(Locale.ROOT);
                String payload = "\"eventId\":" + id + ",\"revision\":" + revision
                        + ",\"tick\":" + (source.tick == null ? "null" : source.tick)
                        + ",\"gameTimeMillis\":" + record.gameTimeMillis
                        + ",\"side\":\"" + side + "\",\"event\":{\"type\":\"" + eventType
                        + "\",\"description\":\"" + RwxCliText.json(record.description) + "\"}";
                add(prior == null ? "event" : "update", frame.now, eventType, side, payload);
            }
            result.put(source, new State(id, revision, record, side, lastContributor(record)));
        }
    }

    private static int lastContributor(EventRecord record) {
        int last = record.gameTimeMillis;
        for (GameEvent contributor : record.contributors) {
            last = Math.max(last, contributor.gameTimeMillis);
        }
        return last;
    }

    /**
     * Identity of an aggregate across frames. Contributors are the same immutable objects while the
     * raw store keeps them, so a shared object is proof of the same aggregate. When the store drops a
     * contributor (retention, capacity, or a re-discovery replacing an earlier occurrence), fall back
     * to the subject inside one aggregation window only: an ancient record must never be revived as
     * a revision of a fresh encounter.
     */
    private static boolean overlaps(State prior, EventRecord next) {
        if (prior.record.type != next.type) return false;
        for (GameEvent old : prior.record.contributors) for (GameEvent fresh : next.contributors) {
            if (old == fresh) return true;
        }
        if ((long) next.gameTimeMillis - prior.lastTime > 3000) return false;
        for (GameEvent old : prior.record.contributors) for (GameEvent fresh : next.contributors) {
            if ((next.type == EventType.NEW || next.type == EventType.DESTROYED || next.type == EventType.LOSSES)
                    && old.subject != null && fresh.subject != null && old.subject.id == fresh.subject.id) {
                return true;
            }
        }
        return false;
    }

    private synchronized void add(String kind, int time, String type, String side, String payload) {
        long seq = ++sequence;
        String json = "{\"protocol\":\"" + PROTOCOL + "\",\"kind\":\"" + kind
                + "\",\"session\":\"" + session + "\",\"seq\":" + seq + "," + payload + "}";
        journal.addLast(new Message(seq, time, type, side, json));
        prune(time);
        notifyAll();
    }

    private synchronized void prune(int time) {
        while (!journal.isEmpty() && (journal.size() > JOURNAL_LIMIT
                || (long) time - journal.peekFirst().time > RwxEventStore.RETENTION_MILLIS)) journal.removeFirst();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = server.accept(); sockets.add(socket);
                try { clients.execute(() -> serve(socket)); }
                catch (RejectedExecutionException error) { sockets.remove(socket); socket.close(); }
            } catch (IOException error) {
                if (!closed) System.err.println("RWX event accept failed: " + error);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10000);
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            int b;
            while ((b = input.read()) >= 0 && b != '\n' && request.size() < 8192) request.write(b);
            String line = request.toString(StandardCharsets.UTF_8);
            if (b != '\n' || !line.contains("\"rwx-events-v1\"") || !line.contains("\"subscribe\"")) return;
            Matcher after = Pattern.compile("\\\"after\\\"\\s*:\\s*(\\d+)").matcher(line);
            long cursor = after.find() ? Long.parseLong(after.group(1)) : 0;
            Matcher sessionMatch = Pattern.compile("\\\"session\\\"\\s*:\\s*\\\"([a-f0-9-]+)\\\"").matcher(line);
            String requested = sessionMatch.find() ? sessionMatch.group(1) : null;
            boolean info = line.matches("(?s).*\\\"info\\\"\\s*:\\s*true.*");
            boolean allies = line.matches("(?s).*\\\"allies\\\"\\s*:\\s*true.*");
            OutputStream output = socket.getOutputStream();
            write(output, "{\"protocol\":\"" + PROTOCOL + "\",\"kind\":\"hello\",\"session\":\"" + session + "\"}");
            if (requested != null && !requested.equals(session)) {
                write(output, control("gap", "\"message\":\"previous server session unavailable\""));
                cursor = 0;
            }
            socket.setSoTimeout(0);
            while (!closed) {
                List<Message> batch;
                long first, latest;
                synchronized (this) {
                    while (!closed && cursor == sequence) wait(1000);
                    if (closed) break;
                    first = journal.isEmpty() ? sequence + 1 : journal.peekFirst().seq;
                    latest = sequence;
                    batch = new ArrayList<>(journal);
                }
                if (cursor > latest) {
                    write(output, control("gap", "\"message\":\"requested sequence is ahead of server\""));
                    cursor = first - 1;
                }
                if (cursor < first - 1) {
                    write(output, control("gap", "\"from\":" + (cursor + 1) + ",\"to\":" + (first - 1)));
                    cursor = first - 1;
                }
                for (Message message : batch) {
                    if (message.seq <= cursor) continue;
                    boolean filtered = ("info".equals(message.type) && !info)
                            || (message.side != null && ((allies && "self".equals(message.side)
                            && ("losses".equals(message.type) || "destroyed".equals(message.type)))
                            || (!allies && "ally".equals(message.side))));
                    write(output, filtered ? control("cursor", "\"seq\":" + message.seq) : message.json);
                    cursor = message.seq;
                }
            }
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { sockets.remove(socket); }
    }

    private String control(String kind, String fields) {
        return "{\"protocol\":\"" + PROTOCOL + "\",\"kind\":\"" + kind
                + "\",\"session\":\"" + session + "\"," + fields + "}";
    }
    private static void write(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active == this) active = null;
        try { server.close(); } catch (IOException ignored) { }
        if (processor != null) processor.interrupt();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow(); portFile.close(); notifyAll();
    }
}
