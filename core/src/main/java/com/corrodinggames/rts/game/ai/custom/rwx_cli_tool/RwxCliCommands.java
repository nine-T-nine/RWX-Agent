package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI command registry.
 *
 * <p>This is the sole source of truth for command names, aliases, and routing domains.
 * Transport and command implementations must use {@link #find(String)} instead of comparing
 * user-supplied command text themselves.</p>
 *
 * <p>Naming: {@code rwx-<domain>-<object>-<verb>} for match commands, with the verb being
 * {@code list} (many objects), {@code show} (one object) or {@code status} (feature state). The
 * static encyclopedia uses the shorter {@code rwx-query-*} prefix because it never touches a
 * running match.</p>
 */
public final class RwxCliCommands {
    public enum Domain {
        PROTOCOL,
        HELP,
        SESSION,
        BATTLE_ROOM,
        LOCAL_GAME,
        UNIT_QUERY
    }

    public enum Command {
        PING("rwx-ping", Domain.PROTOCOL),
        HELP("rwx-help", Domain.HELP),
        CONNECT("rwx-connect", Domain.SESSION),
        CONNECTION_STATUS("rwx-connection-status", Domain.SESSION),
        DISCONNECT("rwx-disconnect", Domain.SESSION),
        HOST("rwx-host", Domain.SESSION),
        SERVER_LIST("rwx-server-list", Domain.SESSION),
        EXIT("rwx-exit", Domain.SESSION),

        SKIRMISH("rwx-skirmish", Domain.BATTLE_ROOM),
        BATTLE_ROOM_SHOW("rwx-battleroom-show", Domain.BATTLE_ROOM),
        BATTLE_ROOM_ADD_AI("rwx-battleroom-add-ai", Domain.BATTLE_ROOM),
        BATTLE_ROOM_SHOW_AI("rwx-battleroom-show-ai", Domain.BATTLE_ROOM),
        BATTLE_ROOM_SET_PLAYER("rwx-battleroom-set-player", Domain.BATTLE_ROOM),
        BATTLE_ROOM_SELECT_MAP("rwx-battleroom-select-map", Domain.BATTLE_ROOM),
        BATTLE_ROOM_SETTINGS("rwx-battleroom-settings", Domain.BATTLE_ROOM),
        BATTLE_ROOM_CHAT("rwx-battleroom-chat", Domain.BATTLE_ROOM),
        BATTLE_ROOM_QUIT("rwx-battleroom-quit", Domain.BATTLE_ROOM),
        BATTLE_ROOM_START("rwx-battleroom-start", Domain.BATTLE_ROOM),

        PAUSE("rwx-game-pause", Domain.LOCAL_GAME),
        SPEED("rwx-game-speed", Domain.LOCAL_GAME),
        STATE("rwx-game-state", Domain.LOCAL_GAME),
        TEAM_LIST("rwx-game-team-list", Domain.LOCAL_GAME),
        UNIT_LIST("rwx-game-unit-list", Domain.LOCAL_GAME),
        UNIT_SHOW("rwx-game-unit-show", Domain.LOCAL_GAME),
        ACTIONS("rwx-game-unit-actions", Domain.LOCAL_GAME),
        MAP_SHOW("rwx-game-map-show", Domain.LOCAL_GAME),
        MAP_REACH("rwx-game-map-path", Domain.LOCAL_GAME),
        MAP_PLACE("rwx-game-map-place", Domain.LOCAL_GAME),
        ORDER_MOVE("rwx-game-order-move", Domain.LOCAL_GAME),
        ORDER_ATTACK_MOVE("rwx-game-order-attack-move", Domain.LOCAL_GAME),
        ORDER_ATTACK("rwx-game-order-attack", Domain.LOCAL_GAME),
        ORDER_STOP("rwx-game-order-stop", Domain.LOCAL_GAME),
        ORDER_PATROL("rwx-game-order-patrol", Domain.LOCAL_GAME),
        ORDER_GUARD("rwx-game-order-guard", Domain.LOCAL_GAME),
        ORDER_GUARD_AT("rwx-game-order-guard-at", Domain.LOCAL_GAME),
        ORDER_FOLLOW("rwx-game-order-follow", Domain.LOCAL_GAME),
        ORDER_REPAIR("rwx-game-order-repair", Domain.LOCAL_GAME),
        ORDER_RECLAIM("rwx-game-order-reclaim", Domain.LOCAL_GAME),
        ORDER_LOAD_INTO("rwx-game-order-load-into", Domain.LOCAL_GAME),
        ORDER_LOAD_UP("rwx-game-order-load-up", Domain.LOCAL_GAME),
        ORDER_BUILD("rwx-game-order-build", Domain.LOCAL_GAME),
        ORDER_ACTION("rwx-game-order-action", Domain.LOCAL_GAME),
        ORDER_CANCEL("rwx-game-order-cancel", Domain.LOCAL_GAME),
        ORDER_ASSIST("rwx-game-order-assist", Domain.LOCAL_GAME),
        ORDER_ATTACK_MODE("rwx-game-order-attack-mode", Domain.LOCAL_GAME),
        ORDER_RALLY("rwx-game-order-rally", Domain.LOCAL_GAME),
        PUSH("rwx-game-order-push", Domain.LOCAL_GAME),
        EVENTS("rwx-game-events", Domain.LOCAL_GAME),
        THREATS("rwx-game-threat-list", Domain.LOCAL_GAME),
        REPLAY_START("rwx-game-replay-start", Domain.LOCAL_GAME),
        REPLAY_STOP("rwx-game-replay-stop", Domain.LOCAL_GAME),
        REPLAY_STATUS("rwx-game-replay-status", Domain.LOCAL_GAME),
        STEP("rwx-game-step", Domain.LOCAL_GAME),
        RESTART("rwx-game-restart", Domain.LOCAL_GAME),
        SURRENDER("rwx-game-surrender", Domain.LOCAL_GAME),
        SNAPSHOT("rwx-game-camera", Domain.LOCAL_GAME),
        NUKE_REPORT("rwx-game-nuke-status", Domain.LOCAL_GAME),
        GROUP_SAVE("rwx-game-group-save", Domain.LOCAL_GAME),
        GROUP_LIST("rwx-game-group-list", Domain.LOCAL_GAME),
        GROUP_SHOW("rwx-game-group-show", Domain.LOCAL_GAME),
        GROUP_DELETE("rwx-game-group-delete", Domain.LOCAL_GAME),
        OBSERVE_RESET("rwx-game-observe-reset", Domain.LOCAL_GAME),
        PENDING_LIST("rwx-game-pending-list", Domain.LOCAL_GAME),
        PENDING_CLEAR("rwx-game-pending-clear", Domain.LOCAL_GAME),
        KEEP("rwx-game-keep-set", Domain.LOCAL_GAME),
        KEEP_LIST("rwx-game-keep-list", Domain.LOCAL_GAME),
        KEEP_CLEAR("rwx-game-keep-clear", Domain.LOCAL_GAME),
        AUTO_SET("rwx-game-auto-set", Domain.LOCAL_GAME),
        AUTO_LIST("rwx-game-auto-list", Domain.LOCAL_GAME),
        AUTO_SHOW("rwx-game-auto-show", Domain.LOCAL_GAME),
        AUTO_CLEAR("rwx-game-auto-clear", Domain.LOCAL_GAME),
        AUTO_RESET("rwx-game-auto-reset", Domain.LOCAL_GAME),

        QUERY_UNIT("rwx-query-unit", Domain.UNIT_QUERY),
        QUERY_BUILDING("rwx-query-building", Domain.UNIT_QUERY),
        QUERY_PRODUCTION("rwx-query-production", Domain.UNIT_QUERY);

        private final String commandText;
        private final Domain domain;
        private final String[] aliases;

        Command(String name, Domain domain, String... aliases) {
            this.commandText = name;
            this.domain = domain;
            this.aliases = aliases;
        }

        public String commandName() {
            return commandText;
        }

        public Domain domain() {
            return domain;
        }
    }

    private static final Map<String, Command> BY_NAME = buildIndex();

    private RwxCliCommands() { }

    public static Command find(String text) {
        return text == null ? null : BY_NAME.get(text.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isInDomain(Command command, Domain domain) {
        return command != null && command.domain() == domain;
    }

    public static java.util.List<Command> commandsIn(Domain domain) {
        java.util.List<Command> result = new java.util.ArrayList<>();
        for (Command command : Command.values()) {
            if (command.domain() == domain) result.add(command);
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Command> buildIndex() {
        Map<String, Command> commands = new LinkedHashMap<>();
        for (Command command : Command.values()) {
            register(commands, command.commandName(), command);
            for (String alias : command.aliases) register(commands, alias, command);
        }
        return Collections.unmodifiableMap(commands);
    }

    private static void register(Map<String, Command> commands, String name, Command command) {
        Command previous = commands.put(name.toLowerCase(java.util.Locale.ROOT), command);
        if (previous != null) throw new IllegalStateException("Duplicate CLI command name: " + name);
    }
}
