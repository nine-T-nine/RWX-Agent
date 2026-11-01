package com.corrodinggames.rts.game.ai.custom.micro;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MicroData {
    private MicroData() { }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
    public static double number(Map<?,?> map,String key,double fallback) {
        Object value=map.get(key);
        return value instanceof Number ? ((Number)value).doubleValue() : fallback;
    }
}
