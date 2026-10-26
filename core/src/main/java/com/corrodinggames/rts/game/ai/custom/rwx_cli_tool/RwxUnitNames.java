package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig;
import com.corrodinggames.rts.gameFramework.file.FileHelper;
import com.corrodinggames.rts.gameFramework.utility.AssetInputStream;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 单位的英文名与中文名。
 *
 * <p>{@code UnitType.getUnitName()} 跟随当前语言（zh_CN 下直接是中文），百科需要稳定的
 * {@code name}（英文）和 {@code name_zh}（中文），所以从双语资源表按 {@code units.<key>.name}
 * 读取；缺表项时回退到当前语言显示名。</p>
 */
final class RwxUnitNames {
    private static ResourceBundle english;
    private static ResourceBundle chinese;
    private static boolean englishLoaded;
    private static boolean chineseLoaded;

    private RwxUnitNames() { }

    static String english(UnitType type, String fallback) {
        String value = lookup(englishBundle(), key(type));
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static String chinese(UnitType type) {
        String value = lookup(chineseBundle(), key(type));
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** 本地化键：自定义单位用 displayLocaleKey，原版单位用内部 id。 */
    private static String key(UnitType type) {
        if (type instanceof CustomUnitConfig) {
            String localeKey = ((CustomUnitConfig) type).displayLocaleKey;
            if (localeKey != null && !localeKey.trim().isEmpty()) return localeKey.trim();
        }
        return RwxUnitQueryCatalog.safeTypeId(type);
    }

    private static String lookup(ResourceBundle bundle, String key) {
        if (bundle == null || key == null) return null;
        try {
            return bundle.getString("units." + key + ".name");
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static synchronized ResourceBundle englishBundle() {
        if (!englishLoaded) {
            english = load("Strings.properties");
            englishLoaded = true;
        }
        return english;
    }

    private static synchronized ResourceBundle chineseBundle() {
        if (!chineseLoaded) {
            chinese = load("Strings_zh_cn.properties");
            chineseLoaded = true;
        }
        return chinese;
    }

    private static ResourceBundle load(String fileName) {
        try (AssetInputStream stream = FileHelper.openFileByPath("translations/" + fileName)) {
            if (stream == null) return null;
            return new PropertyResourceBundle(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception error) {
            return null;
        }
    }
}
