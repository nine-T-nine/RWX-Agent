package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the plain-text command form into argv.
 *
 * <p>The canonical transport is a tab followed by base64 values, which is lossless and needs no
 * quoting. Hand-typed lines and simpler clients still send whitespace-separated text, so this
 * splitter implements the quoting the manual already promised: double or single quotes group a
 * value with spaces ({@code --name "Lake (2p)"}), and a backslash escapes the next character.
 * Quotes are removed, so the server sees the value exactly as typed.</p>
 *
 * <p>This is deliberately a small POSIX-like splitter, not a shell: no variable expansion, no
 * globbing, no command substitution.</p>
 */
final class RwxCommandLine {
    private RwxCommandLine() { }

    /** Returns one token per argument; throws on an unterminated quote. */
    static List<String> split(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) return tokens;
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        boolean hasToken = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (escaped) {
                current.append(ch);
                hasToken = true;
                escaped = false;
                continue;
            }
            if (ch == '\\' && !inSingle) {
                escaped = true;
                hasToken = true;
                continue;
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
                continue;
            }
            if (!inSingle && !inDouble && Character.isWhitespace(ch)) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            current.append(ch);
            hasToken = true;
        }
        if (escaped) {
            // A trailing backslash is a literal backslash, not an unfinished escape.
            current.append('\\');
        }
        if (inSingle || inDouble) {
            throw new IllegalArgumentException("unterminated quote in command line");
        }
        if (hasToken) tokens.add(current.toString());
        return tokens;
    }
}
