package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import java.util.List;
import java.util.Map;

/**
 * Per-case command handling, native-command authority and lifecycle evidence. The runtime owns
 * transport, clocks, outcome reporting and replay; a case only answers its own commands.
 */
public interface MicroSession extends AutoCloseable {
    String execute(Command command,List<String> arguments)throws Exception;
    default void requestFinished(MicroRuntime.Request request,boolean error,String status) { }
    /** Records a pre-execution rejection; cases override it to write violations.jsonl. */
    default String reject(String raw,List<Long> actors,Object target,String type,String reason) { return "error: violation: "+reason; }
    /**
     * Answer for a command this case does not accept. The default keeps the stock "unknown command"
     * reply; cases with their own rejection vocabulary (a violations file) override it.
     */
    default String rejectCommand(String name,List<String> arguments) { return MicroRuntime.error("unknown command"); }
    default boolean allow(com.corrodinggames.rts.gameFramework.Command command) { return false; }
    /**
     * True when this case deliberately routes its own orders through the shared game service
     * (CASE1 validates ids and HP first, then reuses the stock order path). Every other case builds
     * its native commands itself, so a service order would be a bypass.
     */
    default boolean acceptsServiceOrders() { return false; }
    default void recorded(com.corrodinggames.rts.gameFramework.Command command) { }
    default void applied(com.corrodinggames.rts.gameFramework.Command command) { }
    default void created(BaseUnit building,OrderableUnit actor,double paid) { }
    default void waiting(OrderableUnit actor) { }
    /** Real engine damage hook: HP and shield are the values observed before the hit. */
    default void damage(BaseUnit victim,BaseUnit attacker,float healthBefore,float shieldBefore) { }
    default void afterTick() { }
    default void finish(String outcome) { }
    default Map<String,Object> result() { return Map.of(); }
    @Override default void close() throws Exception { }
}
