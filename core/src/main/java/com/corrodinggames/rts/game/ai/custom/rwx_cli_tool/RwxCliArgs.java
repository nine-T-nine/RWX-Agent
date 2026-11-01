package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code rwx-query-*} 命令的参数解析小工具。
 *
 * <p>与 {@link RwxLocalGameCommands} 里的私有解析逻辑保持一致的容错规则：
 * 选项名大小写不敏感、每个取值选项恰好一个值、未知选项与多余位置参数立即报错。
 * 单独抽出来是因为查询命令有十四个入口，复制十几份校验代码没有必要。</p>
 */
final class RwxCliArgs {
    private RwxCliArgs() { }

    /** 读取某个选项的值；未出现返回 null，重复出现或缺少值则报错。 */
    static String value(List<String> arguments, String flag) {
        String value = null;
        for (int index = 0; index < arguments.size(); index++) {
            if (!flag.equalsIgnoreCase(arguments.get(index))) continue;
            if (value != null || index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
                throw new IllegalArgumentException(flag + " 需要且只能有一个值");
            }
            value = arguments.get(index + 1);
        }
        return value;
    }

    static String required(List<String> arguments, String flag) {
        String value = value(arguments, flag);
        if (value == null) throw new IllegalArgumentException("缺少参数 " + flag);
        return value;
    }

    static boolean flag(List<String> arguments, String flag) {
        boolean found = false;
        for (String argument : arguments) {
            if (!flag.equalsIgnoreCase(argument)) continue;
            if (found) throw new IllegalArgumentException(flag + " 只能出现一次");
            found = true;
        }
        return found;
    }

    static int integer(List<String> arguments, String flag, int defaultValue) {
        Integer value = optionalInteger(arguments, flag);
        return value == null ? defaultValue : value;
    }

    static Integer optionalInteger(List<String> arguments, String flag) {
        String value = value(arguments, flag);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(flag + " 必须是整数");
        }
    }

    /** 校验参数表只包含给定的取值选项与布尔开关，并拒绝位置参数。 */
    static void validate(List<String> arguments, String[] valueOptions, String[] flagOptions) {
        Set<String> values = new HashSet<>();
        for (String option : valueOptions) values.add(option.toLowerCase(Locale.ROOT));
        Set<String> flags = new HashSet<>();
        for (String option : flagOptions) flags.add(option.toLowerCase(Locale.ROOT));
        for (int index = 0; index < arguments.size(); index++) {
            String token = arguments.get(index);
            String normalized = token.toLowerCase(Locale.ROOT);
            if (values.contains(normalized)) {
                if (index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
                    throw new IllegalArgumentException(token + " 需要且只能有一个值");
                }
                index++;
            } else if (flags.contains(normalized)) {
                // 布尔开关，不跟随值。
            } else if (token.startsWith("--")) {
                throw new IllegalArgumentException("未知选项: " + token);
            } else {
                throw new IllegalArgumentException("多余的参数: " + token);
            }
        }
    }
}
