package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Local command terminal, loopback command protocol, and command dispatch for RWX. */
public final class RwxCliTool implements AutoCloseable {
    /**
     * Clients are served on a small pool: a single long-running command (a 10-minute
     * {@code step}, a stalled socket) used to block the accept loop, so every other caller —
     * including the PowerShell terminal — waited behind it.
     */
    private static final int MAX_CLIENT_THREADS = 4;

    private final ServerSocket listener;
    private final ExecutorService clients;
    private final RwxCliPortFile portFile;
    private final int port;
    private final RwxCliCommandCapture commandCapture;
    private final RwxEventStream eventStream;

    private RwxCliTool(ServerSocket listener, ExecutorService clients,
                       RwxCliPortFile portFile, int port, RwxCliCommandCapture commandCapture,
                       RwxEventStream eventStream) {
        this.listener = listener;
        this.clients = clients;
        this.portFile = portFile;
        this.port = port;
        this.commandCapture = commandCapture;
        this.eventStream = eventStream;
    }

    private static boolean stdioForcedUtf8;

    /**
     * 把本进程的 stdout/stderr 固定为 UTF-8。
     *
     * <p>Windows 上 JVM 默认按系统代码页写标准输出（简体中文是 GBK），日志被 Python 等工具
     * 按 UTF-8 读取时，中文就会变成 “ȫ��” 这类乱码。回环 socket 回复本来就是 UTF-8，
     * 这里让进程日志与命令行回显也保持一致。首次调用生效，后续调用忽略。</p>
     */
    public static synchronized void forceUtf8Stdio() {
        if (stdioForcedUtf8) return;
        stdioForcedUtf8 = true;
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
                    StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // 宿主已经拿走标准流时保持原样。
        }
    }

    /** Starts the loopback command endpoint and opens the Windows command terminal when available. */
    public static RwxCliTool start(RwxCliCommandExecutor executor) throws IOException {
        return start(executor, !Boolean.getBoolean("java.awt.headless")
                && Boolean.parseBoolean(System.getProperty("rwx.cli.terminal", "true")));
    }

    public static RwxCliTool start(RwxCliCommandExecutor executor, boolean commandTerminal) throws IOException {
        forceUtf8Stdio();
        ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        int port = listener.getLocalPort();
        ExecutorService clients = Executors.newFixedThreadPool(MAX_CLIENT_THREADS, task -> {
            Thread worker = new Thread(task, "RWX-cli-client");
            worker.setDaemon(true);
            return worker;
        });
        RwxCliCommandCapture commandCapture = RwxCliCommandCapture.open(repoRoot(), port);
        Thread thread = new Thread(() -> serve(listener, executor, clients, commandCapture), "RWX-cli-tool-" + port);
        thread.setDaemon(true);
        thread.start();
        String explicit = System.getProperty("rwx.cli.portFile", System.getenv("RWX_CLI_PORT_FILE"));
        java.nio.file.Path target = explicit == null || explicit.isBlank()
                ? repoRoot().resolve("agent-operation/tmp/port") : java.nio.file.Path.of(explicit);
        RwxCliPortFile portFile;
        try {
            portFile = new RwxCliPortFile(target, port);
        } catch (IOException error) {
            listener.close();
            clients.shutdownNow();
            commandCapture.close();
            throw error;
        }
        RwxEventStream eventStream;
        try {
            eventStream = RwxEventStream.start(target.toAbsolutePath().resolveSibling("events-port"));
        } catch (IOException error) {
            portFile.close(); listener.close(); clients.shutdownNow(); commandCapture.close();
            throw new IOException("Cannot start RWX event service: " + error.getMessage(), error);
        }
        System.out.println("RWX_CLI_TOOL_READY port=" + port + " host=127.0.0.1"
                + " portFile=" + portFile.primaryPath() + " instancePortFile=" + portFile.instancePath());
        if (commandTerminal) openCommandTerminal(port);
        RwxCliTool tool = new RwxCliTool(listener, clients, portFile, port, commandCapture, eventStream);
        Runtime.getRuntime().addShutdownHook(new Thread(tool::close, "RWX-cli-shutdown"));
        return tool;
    }

    @Override
    public void close() {
        clients.shutdownNow();
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        portFile.close();
        commandCapture.close();
        eventStream.close();
    }

    /** Walks up from the working directory to the checkout root (marker: settings.gradle.kts). */
    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path current = java.nio.file.Path.of(System.getProperty("launch.dir", "")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (java.nio.file.Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return java.nio.file.Path.of(System.getProperty("launch.dir", "")).toAbsolutePath().normalize();
    }

    private static void serve(ServerSocket listener, RwxCliCommandExecutor executor,
                              ExecutorService clients, RwxCliCommandCapture commandCapture) {
        while (!listener.isClosed()) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException error) {
                if (!listener.isClosed()) {
                    System.err.println("RWX CLI tool accept failed: " + error.getMessage());
                }
                continue;
            }
            try {
                clients.submit(() -> serveClient(client, executor, commandCapture));
            } catch (RejectedExecutionException saturated) {
                // Refuse politely instead of running inline (which would re-serialise the loop)
                // or leaking a socket nobody will ever read.
                try (Socket rejected = client) {
                    rejected.getOutputStream().write(
                            "error: too many concurrent CLI clients\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The client went away before it could be told; nothing to do.
                }
            }
        }
    }

    private static void serveClient(Socket client, RwxCliCommandExecutor executor,
                                    RwxCliCommandCapture commandCapture) {
        try (Socket socket = client;
             java.io.PushbackInputStream rawInput = new java.io.PushbackInputStream(
                     new java.io.BufferedInputStream(socket.getInputStream()), 1);
             PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setTcpNoDelay(true);
            String line;
            // A persistent client ("!!framed") gets length-independent replies: each answer is
            // wrapped in ASCII RS (0x1E), so multi-line output needs no trailing marker and the
            // client never has to reconnect per command. One-shot clients keep the old
            // line-per-command behaviour.
            boolean framed = false;
            while ((line = readCommandLine(rawInput)) != null) {
                if (line.trim().equals(FRAMED_HANDSHAKE)) {
                    framed = true;
                    output.print(FRAMED_READY);
                    output.flush();
                    continue;
                }
                commandCapture.receive(line);
                String message = handle(line, executor);
                if (framed) {
                    output.print(RECORD_SEPARATOR);
                    output.print(message.replace(RECORD_SEPARATOR, ' '));
                    output.print(RECORD_SEPARATOR);
                    output.flush();
                } else {
                    output.println(message);
                }
            }
        } catch (IOException error) {
            System.err.println("RWX CLI tool client failed: " + error.getMessage());
        }
    }

    /**
     * Reads one UTF-8 command line from the socket.
     *
     * <p>Replies are always UTF-8. Input is decoded strictly as UTF-8 first; a client on a Chinese
     * Windows console that still writes GBK is decoded with GBK instead of producing replacement
     * characters. This keeps old hand-typed terminals working without ever emitting non-UTF-8
     * replies.</p>
     */
    private static String readCommandLine(java.io.PushbackInputStream input) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(128);
        boolean any = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (!any) return null;
                break;
            }
            any = true;
            if (value == '\n') break;
            if (value == '\r') {
                // CRLF ends the line and consumes the LF; a lone CR ends it too, leaving the next
                // byte for the following command.
                int next = input.read();
                if (next >= 0 && next != '\n') input.unread(next);
                break;
            }
            buffer.write(value);
        }
        byte[] bytes = buffer.toByteArray();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    .replaceFirst("^\\uFEFF", "");
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"))
                    .replaceFirst("^\\uFEFF", "");
        }
    }

    /** First line a persistent client sends; server answers {@link #FRAMED_READY}. */
    private static final String FRAMED_HANDSHAKE = "!!framed";
    private static final char RECORD_SEPARATOR = '\u001e';
    private static final String FRAMED_READY = RECORD_SEPARATOR + "ready" + RECORD_SEPARATOR;

    private static String handle(String line, RwxCliCommandExecutor executor) {
        try {
            String normalized = line.trim().replaceFirst("^\\uFEFF", "");
            String command;
            java.util.List<String> arguments;
            int separator = normalized.indexOf('\t');
            if (separator >= 0) {
                // Canonical transport: tab + base64 argv, so values may contain spaces.
                command = normalized.substring(0, separator).trim().toLowerCase();
                arguments = decodeArguments(normalized.substring(separator + 1));
            } else {
                // Plain-text fallback for a hand-typed line or a simpler client. Quotes group a
                // value containing spaces, exactly as help.md documents.
                java.util.List<String> parts = RwxCommandLine.split(normalized);
                command = parts.isEmpty() ? "" : parts.get(0).toLowerCase();
                arguments = parts.isEmpty() ? java.util.Collections.<String>emptyList()
                        : parts.subList(1, parts.size());
            }
            if (command.isEmpty()) {
                throw new IllegalArgumentException("empty command");
            }
            RwxCliCommands.Command registered = RwxCliCommands.find(command);
            if (registered == null) {
                return "error: unknown command '" + command + "' (send: "
                        + RwxCliCommands.Command.HELP.commandName() + ")";
            }
            if (registered == RwxCliCommands.Command.PING) return "pong";
            if (registered == RwxCliCommands.Command.HELP) return helpDocument(arguments);

            String message = executor.execute(registered, arguments);
            System.out.println(message);
            return message;
        } catch (Exception error) {
            Throwable effective = unwrap(error);
            // Validation errors are expected; anything else is a bug. Keep the reply short but
            // put the stack into the run log so it can be traced (e.g. the sampler NPE showed
            // only "NullPointerException" on the terminal).
            if (!(effective instanceof IllegalArgumentException)
                    && !(effective instanceof IllegalStateException)
                    && !(effective instanceof UnsupportedOperationException)) {
                System.err.println("RWX CLI command failed: " + line.trim());
                effective.printStackTrace();
                return "error: " + describe(error) + " (" + effective.getClass().getSimpleName()
                        + "; stack trace in the game log)";
            }
            return "error: " + describe(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause != null
                && (cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? error : cause;
    }

    /**
     * Flattens the async wrapper exceptions added by {@code CompletableFuture} so the terminal
     * shows the real message instead of {@code java.lang.IllegalArgumentException: ...}.
     */
    private static String describe(Throwable error) {
        Throwable effective = unwrap(error);
        String message = effective.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = effective.getClass().getSimpleName();
        }
        return message;
    }

    /** Help text filtered by numbered heading or module flag; no arguments show the manual. */
    static String helpDocument(java.util.List<String> arguments) {
        String document;
        try (java.io.InputStream input = RwxCliTool.class.getResourceAsStream("help.md")) {
            if (input == null) {
                return "error: help.md is not bundled; see agent-operation/help.md";
            }
            document = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "error: unable to read help: " + error.getMessage();
        }

        return helpDocument(document, arguments);
    }

    /** Uses the same parser for the bundled manual and documents supplied by tests. */
    static String helpDocument(String document, java.util.List<String> arguments) {
        java.util.List<HelpSection> sections = helpSections(document);
        if (sections.isEmpty()) return document.trim();

        java.util.Set<String> selectedIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> selectedModules = new java.util.LinkedHashSet<>();
        boolean list = false;
        int depth = 1;
        for (int index = 0; index < arguments.size(); index++) {
            String raw = arguments.get(index);
            String argument = raw == null ? "" : raw.trim();
            if (argument.isEmpty()) continue;
            if ("--list".equalsIgnoreCase(argument) || "list".equalsIgnoreCase(argument)) {
                if (list) return "error: specify --list only once";
                list = true;
                if (index + 1 < arguments.size()) {
                    String next = arguments.get(index + 1);
                    if (next != null && next.trim().matches("[+-]?\\d+")) {
                        java.math.BigInteger requested = new java.math.BigInteger(next.trim());
                        if (requested.signum() <= 0) {
                            return "error: --list depth must be a positive integer";
                        }
                        depth = requested.min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
                        index++;
                    }
                }
                continue;
            }
            String name = argument.startsWith("--") ? argument.substring(2) : argument;
            HelpSection match = null;
            for (HelpSection section : sections) {
                if (section.id.equals(name)
                        || (!section.flag.isEmpty() && section.flag.equalsIgnoreCase(name))) {
                    match = section;
                    break;
                }
            }
            if (match == null) {
                return "error: unknown help section '" + argument + "' (use: rwx-help --list)";
            }
            if (match.id.equals(name)) selectedIds.add(match.id);
            else selectedModules.add(match.id);
        }

        java.util.Set<String> selected = selectedIds.isEmpty() ? selectedModules : selectedIds;
        if (list) return helpSectionList(sections, selected, depth);
        if (selected.isEmpty()) return document.trim();
        StringBuilder output = new StringBuilder();
        int coveredEnd = -1;
        for (HelpSection section : sections) {
            if (!selected.contains(section.id) || section.start < coveredEnd) continue;
            if (output.length() > 0) output.append("\n\n");
            output.append(document.substring(section.start, section.end).trim());
            coveredEnd = section.end;
        }
        return output.toString();
    }

    private static String helpSectionList(java.util.List<HelpSection> sections,
                                          java.util.Set<String> selected, int depth) {
        StringBuilder output = new StringBuilder();
        int selectedEnd = -1;
        for (HelpSection section : sections) {
            if (selected.contains(section.id)) selectedEnd = Math.max(selectedEnd, section.end);
            if (!selected.isEmpty() && section.start >= selectedEnd) continue;
            if (section.depth > depth) continue;
            if (output.length() > 0) output.append('\n');
            output.append("  ".repeat(section.depth - 1))
                    .append(section.heading.replaceFirst("^#+\\s+", ""));
        }
        return output.toString();
    }

    private static final class HelpSection {
        final String id;
        final String flag;
        final String heading;
        final int depth;
        final int start;
        int end;

        HelpSection(String id, String flag, String heading, int depth, int start, int end) {
            this.id = id;
            this.flag = flag;
            this.heading = heading;
            this.depth = depth;
            this.start = start;
            this.end = end;
        }
    }

    /** Document order is DFS order; each section ends at the next sibling or ancestor. */
    private static java.util.List<HelpSection> helpSections(String document) {
        java.util.List<HelpSection> sections = new java.util.ArrayList<>();
        java.util.Deque<HelpSection> ancestors = new java.util.ArrayDeque<>();
        java.util.regex.Pattern headingPattern = java.util.regex.Pattern
                .compile("^(#{2,})[ \\t]+(\\d+(?:\\.\\d+)*)(?:\\.)?[ \\t]+(.+)$");
        java.util.regex.Pattern fencePattern = java.util.regex.Pattern
                .compile("^ {0,3}(`{3,}|~{3,})(.*)$");
        java.util.regex.Matcher lines = java.util.regex.Pattern
                .compile("(?m)^([^\\r\\n]*)(?:\\r?\\n|$)")
                .matcher(document);
        char fenceChar = 0;
        int fenceLength = 0;
        while (lines.find()) {
            String line = lines.group(1);
            java.util.regex.Matcher fence = fencePattern.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceChar == 0) {
                    fenceChar = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceChar && marker.length() >= fenceLength
                        && fence.group(2).isBlank()) {
                    fenceChar = 0;
                }
                continue;
            }
            if (fenceChar != 0) continue;
            java.util.regex.Matcher heading = headingPattern.matcher(line);
            if (!heading.matches()) continue;
            int depth = heading.group(1).length() - 1;
            while (!ancestors.isEmpty() && ancestors.peek().depth >= depth) {
                ancestors.pop().end = lines.start();
            }
            String flag = "";
            if (depth == 1) {
                java.util.regex.Matcher alias = java.util.regex.Pattern.compile("^([A-Za-z]+)\\b")
                        .matcher(heading.group(3));
                if (alias.find()) flag = alias.group(1).toLowerCase(java.util.Locale.ROOT);
            }
            HelpSection section = new HelpSection(heading.group(2), flag, line.trim(), depth,
                    lines.start(), document.length());
            sections.add(section);
            ancestors.push(section);
        }
        return sections;
    }

    /** Decodes the lossless argv representation emitted by the PowerShell terminal helpers. */
    private static java.util.List<String> decodeArguments(String encoded) {
        if (encoded.isEmpty()) return java.util.Collections.emptyList();
        String[] values = encoded.split(",", -1);
        java.util.List<String> arguments = new java.util.ArrayList<>(values.length);
        for (String value : values) {
            arguments.add(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
        }
        return arguments;
    }

    private static void openCommandTerminal(int port) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        String script = """
                $Host.UI.RawUI.WindowTitle = 'RWX Command Terminal'
                function Invoke-RwxCommand([string]$command, [Parameter(ValueFromRemainingArguments=$true)][string[]]$arguments) {
                  $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', __PORT__)
                  try {
                    $writer = [System.IO.StreamWriter]::new($client.GetStream(), [System.Text.Encoding]::UTF8)
                    $writer.AutoFlush = $true
                    $reader = [System.IO.StreamReader]::new($client.GetStream(), [System.Text.Encoding]::UTF8)
                    $argumentList = @()
                    if ($null -ne $arguments) { $argumentList = @($arguments) }
                    $encodedArguments = @($argumentList | ForEach-Object { [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($_)) }) -join ','
                    $writer.WriteLine($command + [char]9 + $encodedArguments)
                    $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
                    $reader.ReadToEnd().TrimEnd()
                  } finally { $client.Dispose() }
                }
                __FUNCTIONS__
                $rwxCliParentPid = $PID
                Start-Job -ArgumentList __PORT__, $rwxCliParentPid -ScriptBlock { param($port, $parentPid) while ($true) { try { $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $port); $client.Dispose() } catch { Stop-Process -Id $parentPid -Force; break }; Start-Sleep -Seconds 1 } } | Out-Null
                Write-Host 'RWX command terminal ready. Run: rwx-help  (then: rwx-skirmish)'
                """.replace("__PORT__", Integer.toString(port))
                .replace("__FUNCTIONS__", terminalFunctions());
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            new ProcessBuilder(
                    "cmd.exe", "/c", "start", "RWX Command Terminal", "powershell.exe",
                    "-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded
            ).start();
            System.out.println("RWX command terminal opened; enter: rwx-skirmish");
        } catch (IOException error) {
            System.err.println("RWX command terminal could not be opened: " + error.getMessage());
        }
    }

    /**
     * One PowerShell wrapper per registered command, generated from {@link RwxCliCommands} so the
     * terminal can never drift from the command registry.
     */
    private static String terminalFunctions() {
        StringBuilder functions = new StringBuilder();
        for (RwxCliCommands.Command command : RwxCliCommands.Command.values()) {
            if (command.domain() == RwxCliCommands.Domain.PROTOCOL) continue;
            if (functions.length() > 0) functions.append('\n');
            functions.append("function ").append(command.commandName())
                    .append(" { Invoke-RwxCommand '").append(command.commandName()).append("' @args }");
        }
        return functions.toString();
    }
}

