package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.List;
import java.util.Locale;

/**
 * CLI 文本的共用小工具：数字、时间与 id 列表的格式化。
 *
 * <p>这些格式原先在 {@link RwxLocalGameCommands} 和 {@link RwxEngineLocalGameService}
 * 里各有一份拷贝，数字判定条件还略有差异（一处比较 {@code rint}，一处留 0.001 容差），
 * 容易出现同一条命令在不同段落里显示成 {@code 3} 和 {@code 3.0}。集中一份后两边口径一致。</p>
 */
final class RwxCliText {
    private RwxCliText() { }

    /** {@code 12} / {@code 3.5}：整数不带小数点，非整数保留一位。 */
    static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** {@code mm:ss}，超过一小时用 {@code h:mm:ss}。 */
    static String time(int millis) {
        int totalSeconds = Math.max(0, millis) / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /** {@code 1,2,3}；空列表与 null 都是 {@code none}。 */
    static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "none";
        StringBuilder text = new StringBuilder();
        for (Long id : ids) {
            if (text.length() > 0) text.append(',');
            text.append(id);
        }
        return text.toString();
    }

    /** Escapes a value for a JSON string literal; null becomes the empty string. */
    static String json(String value) {
        if (value == null) return "";
        StringBuilder text = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"': text.append("\\\""); break;
                case '\\': text.append("\\\\"); break;
                case '\n': text.append("\\n"); break;
                case '\r': text.append("\\r"); break;
                case '\t': text.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        text.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        text.append(ch);
                    }
            }
        }
        return text.toString();
    }
}
