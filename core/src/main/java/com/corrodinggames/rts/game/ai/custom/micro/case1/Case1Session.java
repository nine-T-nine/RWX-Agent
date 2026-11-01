package com.corrodinggames.rts.game.ai.custom.micro.case1;

import com.corrodinggames.rts.game.ai.custom.micro.MicroRuntime;
import com.corrodinggames.rts.game.ai.custom.micro.MicroSession;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.OrderableUnit;
import java.util.List;

/**
 * CASE1 answers its commands through {@link MicroCommands}; this adapter gives the runtime a single
 * command path and keeps the duel's ending rule. Micro cases have no builders, so the engine's
 * "tech defeat" latch has to be cleared or every order would be refused after the first check.
 */
final class Case1Session implements MicroSession {
    private final MicroRuntime run;
    Case1Session(MicroRuntime run){this.run=run;}
    @Override public String execute(Command command,List<String> arguments)throws Exception {
        return run.commands().execute(command,arguments);
    }
    /** CASE1 validates ids/HP itself and then reuses the stock order path. */
    @Override public boolean acceptsServiceOrders(){return true;}
    /** CASE1's authority is {@link MicroCommands}; the engine-level gate must not block its orders. */
    @Override public boolean allow(com.corrodinggames.rts.gameFramework.Command command){return true;}
    /** A disallowed command: turns are the only thing that depends on the track. */
    @Override public String rejectCommand(String name,List<String> arguments) {
        if(name.equalsIgnoreCase(Command.MICRO_NEXT.commandName())&&!run.config.track.equals("pause"))
            return MicroRuntime.error("not available in realtime track");
        return MicroRuntime.error("unknown command");
    }
    @Override public void afterTick() {
        if(run.evidence()!=null)run.evidence().sample(false);
        MicroRuntime.clearTechDefeat(run.selfTeam);MicroRuntime.clearTechDefeat(run.enemyTeam);
        int self=0,enemy=0;
        for(OrderableUnit unit:run.units.values())if(MicroRuntime.alive(unit)){if(unit.team.teamId==run.selfTeam)self++;else enemy++;}
        if(self==0||enemy==0)run.end(self==0?(enemy==0?"draw":"loss"):"win","elimination");
    }
}
