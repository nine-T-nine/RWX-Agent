package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import java.util.*;

/**
 * Command classification (A/B/C, the single source for the gateway) plus the per-case command set.
 * A case declares the commands it accepts in {@link MicroCase#profile(MicroConfig)}; this class
 * never decides case membership by command-name prefix.
 */
public final class MicroCommandProfile {
    public record Entry(String kind,Command delegate) { }
    private static final Map<Command,Entry> ENTRIES=new EnumMap<>(Command.class);
    static {
        entry(Command.MICRO_STATE,"A",null);entry(Command.MICRO_UNITS,"A",Command.UNIT_LIST);
        entry(Command.MICRO_UNIT,"A",Command.UNIT_SHOW);entry(Command.MICRO_MAP,"A",Command.MAP_SHOW);
        entry(Command.MICRO_PATH,"A",Command.MAP_REACH);
        entry(Command.MICRO_MOVE,"B",Command.ORDER_MOVE);entry(Command.MICRO_ATTACK_MOVE,"B",Command.ORDER_ATTACK_MOVE);
        entry(Command.MICRO_ATTACK,"B",Command.ORDER_ATTACK);entry(Command.MICRO_STOP,"B",Command.ORDER_STOP);
        entry(Command.MICRO_MODE,"B",Command.ORDER_ATTACK_MODE);entry(Command.MICRO_BATCH,"B",null);
        entry(Command.MICRO_BEGIN,"C",null);entry(Command.MICRO_NEXT,"C",null);
        entry(Command.MICRO_HELP,"C",null);entry(Command.PING,"C",null);
        entry(Command.LTE_STATE,"A",null);entry(Command.LTE_BUILDER,"A",null);entry(Command.LTE_RESOURCES,"A",null);
        entry(Command.LTE_BUILD,"B",null);entry(Command.LTE_UPGRADE,"B",null);entry(Command.LTE_BEGIN,"C",null);entry(Command.LTE_HELP,"C",null);
        entry(Command.TRANSPORT_STATE,"A",null);entry(Command.TRANSPORT_GOAL,"A",null);
        entry(Command.TRANSPORT_PRODUCTION,"A",null);entry(Command.TRANSPORT_FLEET,"A",null);
        entry(Command.TRANSPORT_PRODUCE,"B",null);entry(Command.TRANSPORT_LOAD,"B",null);
        entry(Command.TRANSPORT_MOVE,"B",null);entry(Command.TRANSPORT_UNLOAD,"B",null);
        entry(Command.TRANSPORT_HELP,"C",null);
        entry(Command.RETREAT_STATE,"A",null);entry(Command.RETREAT_BEGIN,"C",null);entry(Command.RETREAT_HELP,"C",null);
        // Full-game read-only observations a case may borrow instead of duplicating them.
        entry(Command.MAP_SHOW,"A",null);entry(Command.MAP_REACH,"A",null);
        entry(Command.UNIT_LIST,"A",null);entry(Command.UNIT_SHOW,"A",null);
        entry(Command.THREATS,"A",null);
        // Full-game order commands a case may gate itself (per-unit authority lives in its session).
        entry(Command.ORDER_MOVE,"B",null);entry(Command.ORDER_ATTACK_MOVE,"B",null);
        entry(Command.ORDER_ATTACK,"B",null);entry(Command.ORDER_STOP,"B",null);
    }
    private static void entry(Command command,String kind,Command delegate){ENTRIES.put(command,new Entry(kind,delegate));}

    private final String track;
    private final Set<Command> allowed=EnumSet.noneOf(Command.class);

    /** A profile that accepts nothing but {@code rwx-ping}; cases normally pass their own set. */
    public MicroCommandProfile(String track) {this.track=track;}

    public MicroCommandProfile(String track,Command... commands) {
        this(track);
        Collections.addAll(allowed,commands);
    }

    /** Adds one command; returns this profile so a case can build its set fluently. */
    public MicroCommandProfile accept(Command... commands) {
        Collections.addAll(allowed,commands);
        return this;
    }

    public boolean contains(Command command){
        if(command==null)return false;
        if(command==Command.PING)return true;
        if(!allowed.contains(command))return false;
        // Turn commands only exist on the pause track.
        if(command==Command.MICRO_NEXT)return track.equals("pause");
        return true;
    }
    public static Entry entry(Command command){return ENTRIES.get(command);}
    public static String kind(Command command){Entry entry=ENTRIES.get(command);return entry==null?"C":entry.kind;}
}
