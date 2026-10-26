package com.corrodinggames.rts.game.ai.custom.ai_s;

import com.corrodinggames.rts.gameFramework.GameEngine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** One UTF-8 record per callback, with a bounded history for each team and cycle kind. */
final class AiSCycleLog {
    private static final int MAX_CYCLES = 300;
    private static final Map<String, ArrayDeque<String>> HISTORY = new HashMap<>();
    private static boolean warned;

    private AiSCycleLog() { }

    static synchronized void write(int teamId, String kind, int gameMillis, String details) {
        String name = "AI_S_team_" + teamId + "_" + kind + ".log";
        File directory = new File("logs");
        File target = new File(directory, name);
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
            ArrayDeque<String> lines = HISTORY.get(name);
            if (lines == null) {
                lines = readExisting(target);
                HISTORY.put(name, lines);
            }
            lines.addLast("gameMs=" + gameMillis + " team=" + teamId + " cycle=" + kind + " " + details);
            while (lines.size() > MAX_CYCLES) lines.removeFirst();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(target, false), StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException ex) {
            if (!warned) {
                warned = true;
                GameEngine.log("AI_S cannot write cycle logs: " + ex.getMessage());
            }
        }
    }

    private static ArrayDeque<String> readExisting(File target) throws IOException {
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (!target.isFile()) return lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(target), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > MAX_CYCLES) lines.removeFirst();
            }
        }
        return lines;
    }
}
