package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

/**
 * Game-facing operations available to the RWX command endpoint.
 * Implementations must schedule mutations on the game/UI owner thread.
 */
@FunctionalInterface
public interface RwxCliCommandExecutor {
    String execute(RwxCliCommands.Command command, java.util.List<String> arguments) throws Exception;
}
