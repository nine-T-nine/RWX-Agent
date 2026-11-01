package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.micro.case1.Case1Duel;

public final class MicroCaseRegistry {
    private MicroCaseRegistry() { }
    public static MicroCase find(String id) {
        if("CASE1-duel".equals(id)) return new Case1Duel();
        if("CASE2-LTE".equals(id)) return new com.corrodinggames.rts.game.ai.custom.micro.case2.Case2Lte();
        if("CASE3-Transport".equals(id)) return new com.corrodinggames.rts.game.ai.custom.micro.case3.Case3Transport();
        if("CASE4-Retreat".equals(id)) return new com.corrodinggames.rts.game.ai.custom.micro.case4.Case4Retreat();
        throw new IllegalArgumentException("Unknown micro case: "+id);
    }
}
