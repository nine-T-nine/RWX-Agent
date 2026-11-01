package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.strategy.AiDefinition;
import com.corrodinggames.rts.game.ai.strategy.AiRegistry;
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings;
import io.github.rwx.app.BattleRoomController;
import io.github.rwx.app.BattleRoomLaunchController;
import io.github.rwx.map.MapMetadata;
import io.github.rwx.session.BattleRoomSnapshot;
import io.github.rwx.session.BattleRoomTeamLayout;
import io.github.rwx.session.GameSession;
import io.github.rwx.session.MultiplayerChatSnapshot;
import io.github.rwx.ui.AppScreen;
import io.github.rwx.ui.ScreenNavigator;
import io.github.rwx.ui.model.BattleRoomPlayer;
import io.github.rwx.ui.model.MapEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code rwx-*} 战役室命令的全部实现。
 *
 * <p>解析、校验、状态改动和文案都集中在这里，app 层只负责把控制器交给构造函数
 * （见 {@code App.kt} 里的 {@code cliBattleRoomCommand}），因此这些自定义功能不会散落进
 * 游戏自身的 Kotlin 源码。</p>
 *
 * <p>本类只依赖 app 层公开的会话/控制器对象，并且总是由 UI 线程调用
 * （{@code AppSession.executeCliBattleRoomCommand} 内部经过 {@code runAtomic}），
 * 所以可以安全地修改房间状态。</p>
 *
 * <p>注意：房间里的玩家 id 就是出生点槽位（teamId），移动后会变，因此这里从不缓存 id，
 * 每次改动后都重新读取房间快照，并按出生点或名字定位玩家。</p>
 */
public final class RwxLocalBattleRoomCommands implements RwxCliCommandExecutor {
    /** team 参数的“自动分配”哨兵，等于 io.github.rwx.BATTLE_ROOM_AUTO_TEAM_VALUE。 */
    private static final int AUTO_TEAM_VALUE = -1;
    /** spawn 参数的旁观哨兵，等于 io.github.rwx.BATTLE_ROOM_SPECTATOR_SPAWN_VALUE。 */
    private static final int SPECTATOR_SPAWN_VALUE = -3;
    /** 地图名里已经写过的 "(3p)" 一类的玩家数标记。 */
    private static final Pattern PLAYER_COUNT_LABEL = Pattern.compile("\\(\\s*\\d+\\s*[pP]\\s*\\)");
    private static final String[] PLAYER_ACTIONS = {"--move", "--switch", "--kick", "--difficulty"};
    private static final Set<String> SETTING_OPTIONS = new LinkedHashSet<>(Arrays.asList(
            "--income", "--credits", "--fog", "--startunits", "--aidifficulty", "--revealedmap", "--nonukes",
            "--sharedcontrol", "--allowspectators", "--teamlock", "--roomlock", "--fixedallyteams",
            "--maxplayers", "--teamlayout"));
    /** startingCredits 档位 → 金额，与 NetworkEngine.e(int) 一致。 */
    private static final int[] STARTING_CREDITS = {4000, 0, 1000, 2000, 5000, 10000, 50000, 100000, 200000};
    private static final String[] FOG_NAMES = {"none", "basic", "los"};

    private final GameSession gameSession;
    private final BattleRoomController battleRoomController;
    private final BattleRoomLaunchController battleRoomLaunchController;
    private final ScreenNavigator navigator;

    public RwxLocalBattleRoomCommands(
            GameSession gameSession,
            BattleRoomController battleRoomController,
            BattleRoomLaunchController battleRoomLaunchController,
            ScreenNavigator navigator) {
        this.gameSession = gameSession;
        this.battleRoomController = battleRoomController;
        this.battleRoomLaunchController = battleRoomLaunchController;
        this.navigator = navigator;
    }

    @Override
    public String execute(RwxCliCommands.Command command, List<String> arguments) {
        if (command == RwxCliCommands.Command.SKIRMISH) return enterSkirmish();
        // 只读 AI 注册表，不需要房间，所以在房间检查之前返回。
        if (command == RwxCliCommands.Command.BATTLE_ROOM_SHOW_AI) return showAi();
        BattleRoomSnapshot room = gameSession.currentBattleRoom(false);
        if (room == null) {
            throw new IllegalStateException("No active battle room");
        }
        String forwarded = RwxManagedRoom.forwardIfProxy(gameSession, command, arguments);
        if (forwarded != null) return forwarded;
        if (!room.isHost() && (command == RwxCliCommands.Command.BATTLE_ROOM_ADD_AI
                || command == RwxCliCommands.Command.BATTLE_ROOM_SELECT_MAP)) {
            throw new IllegalStateException("Only the room host can change AI players or the map");
        }
        switch (command) {
            case BATTLE_ROOM_SHOW:
                return show(room, arguments);
            case BATTLE_ROOM_ADD_AI:
                return addAi(room, arguments);
            case BATTLE_ROOM_SET_PLAYER:
                return setPlayer(room, arguments);
            case BATTLE_ROOM_SELECT_MAP:
                return selectMap(arguments);
            case BATTLE_ROOM_SETTINGS:
                return configureSettings(room, arguments);
            case BATTLE_ROOM_CHAT:
                return chat(arguments);
            case BATTLE_ROOM_QUIT:
                // 对局还在引擎里时不能直接关房间：桌面版 Slick 渲染线程仍在画这份对局，
                // 引擎被停掉后会抛 NullPointerException（GameLogic.drawGame），
                // 整个渲染会话判定为“map load failed”并退出进程。
                requireNoLoadedGame(command.commandName());
                navigator.navigateTo(battleRoomController.closeRoom());
                return "Battle room closed";
            case BATTLE_ROOM_START:
                if (!room.isHost()) {
                    throw new IllegalStateException("Only the room host can start the game");
                }
                if (isGameLoaded()) {
                    throw new IllegalStateException(
                            "A game is already loaded; use rwx-game-restart to replay this map, "
                                    + "or rwx-game-surrender to end it before starting another");
                }
                boolean replay = false;
                for (String argument : arguments) {
                    if ("--record".equalsIgnoreCase(argument)) {
                        replay = true;
                    } else {
                        throw new IllegalArgumentException(
                                "Unsupported option for " + command.commandName() + ": " + argument
                                        + " (only --record is accepted)");
                    }
                }
                if (replay) {
                    com.corrodinggames.rts.gameFramework.ReplayEngine.requestLocalRecording();
                }
                battleRoomLaunchController.startBattleRoomGame();
                return replay
                        ? "已请求开始游戏\n已请求完整回放录制：从 tick 0 开始，结束后文件写入 replays/ 目录"
                        : "已请求开始游戏";
            default:
                return "error: command is not implemented: " + command.commandName();
        }
    }

    /** 进入本地战役室：优先 King of the Middle，否则第一张遭遇战地图。 */
    private String enterSkirmish() {
        requireNoLoadedGame(RwxCliCommands.Command.SKIRMISH.commandName());
        MapEntry map = battleRoomController.selectCommandSkirmishMap();
        if (map == null) {
            throw new IllegalStateException("No skirmish map is available");
        }
        battleRoomController.prepareForMap(map, false, AppScreen.MainMenu);
        navigator.navigateTo(AppScreen.BattleRoom);
        BattleRoomSnapshot room = gameSession.currentBattleRoom(false);
        if (room == null) {
            throw new IllegalStateException("Unable to prepare local battle room");
        }
        return "[INFO]已进入本地战役室，当前玩家列表：" + playerNames(room) + "，选择地图：" + room.getMapDisplayName();
    }

    /** 引擎里是否已经载入了一局（菜单背景局不算）。 */
    private boolean isGameLoaded() {
        return gameSession.canResume();
    }

    /**
     * 对局仍在引擎里时拒绝关房间/开新房间。
     *
     * <p>桌面版的对局由 Slick 渲染线程持续绘制；关闭房间会让引擎进入 stop 状态，下一帧
     * {@code GameLogic.drawGame} 就会因为对象列表已清空而 NPE，渲染会话随后把整个进程带走
     * （日志里表现为 {@code Embedded Slick map load failed} + 进程正常退出）。在渲染器生命周期
     * 修好之前，这里用明确错误代替“看起来成功、实际上把进程搞崩”。</p>
     */
    private void requireNoLoadedGame(String command) {
        if (!isGameLoaded()) return;
        throw new IllegalStateException(
                command + " is unavailable while a game is loaded: stopping the engine here makes the "
                        + "Slick renderer throw (GameLogic.drawGame NPE) and ends the process. "
                        + "Use rwx-game-restart to replay this map, or rwx-game-surrender "
                        + "to end it before changing the room");
    }

    private String show(BattleRoomSnapshot room, List<String> arguments) {
        Set<String> flags = flags(arguments, "--player", "--currentmap", "--enabledmap", "--settings", "--chathistory");
        Set<String> display = flags.isEmpty()
                ? new LinkedHashSet<>(Arrays.asList("--player", "--currentmap"))
                : flags;
        List<String> blocks = new ArrayList<>();
        if (display.contains("--player")) {
            blocks.add(playerList(room));
        }
        if (display.contains("--currentmap")) {
            blocks.add(currentMap(room));
        }
        if (display.contains("--enabledmap")) {
            blocks.add(enabledMapList(battleRoomController.enabledMaps()));
        }
        if (display.contains("--settings")) {
            blocks.add(settings(room));
        }
        if (display.contains("--chathistory")) {
            blocks.add(chatHistory(takeLast(gameSession.multiplayerChatHistory(), 15), "聊天记录"));
        }
        return String.join("\n\n", blocks);
    }

    /**
     * 列出所有可添加的 AI：编号、显示名（中/英）、稳定 id。
     *
     * <p>数据直接来自 {@link AiRegistry}，所以新增自定义 AI 只需注册进
     * {@code CustomAiCatalog}，这里和文档都不用跟着改。</p>
     */
    static String showAi() {
        StringBuilder text = new StringBuilder("可添加的 AI（")
                .append(AiRegistry.definitions().size()).append(" 个）：");
        appendAiGroup(text, "原版", false);
        appendAiGroup(text, "自定义", true);
        text.append("\n用法: ").append(RwxCliCommands.Command.BATTLE_ROOM_ADD_AI.commandName())
                .append(" --difficulty <编号|id|显示名>，默认 Hard (1)")
                .append("\n编号、稳定 id（可省略 rwx. / rwx.original. 前缀）和中英文显示名都能用；")
                .append("大小写、空格、_、- 不敏感。");
        return text.toString();
    }

    private static void appendAiGroup(StringBuilder text, String title, boolean custom) {
        text.append('\n').append(title).append('：');
        for (AiDefinition definition : AiRegistry.definitions()) {
            if (definition.isCustom() != custom) continue;
            text.append('\n').append(String.format(Locale.ROOT, "%3d", definition.code()))
                    .append(" | ").append(definition.displayName())
                    .append(" / ").append(definition.displayName(Locale.SIMPLIFIED_CHINESE))
                    .append(" | ").append(definition.id());
        }
    }

    private String addAi(BattleRoomSnapshot room, List<String> arguments) {
        String requested = optionalValue(arguments, "--difficulty");
        int difficulty = requested == null ? 1 : difficulty(requested);
        Set<String> beforeIds = new LinkedHashSet<>();
        for (BattleRoomPlayer player : room.getPlayers()) {
            beforeIds.add(player.getId());
        }
        if (!gameSession.addBattleRoomAi(1)) {
            throw new IllegalStateException("Unable to add AI");
        }
        BattleRoomSnapshot added = gameSession.currentBattleRoom(false);
        BattleRoomPlayer created = added == null ? null : firstPlayerOutside(added, beforeIds);
        if (created == null) {
            throw new IllegalStateException("Unable to identify added AI");
        }
        String addedId = created.getId();
        if (!gameSession.configureBattleRoomPlayer(addedId, null, null, null, difficulty)) {
            throw new IllegalStateException("Unable to configure added AI");
        }
        battleRoomController.updateFromNetwork(true);
        // 名字由难度和槽位推出，配置完成后重新读取，回执才不会和实际难度对不上。
        BattleRoomSnapshot refreshed = gameSession.currentBattleRoom(false);
        BattleRoomPlayer configured = refreshed == null ? null : playerById(refreshed, addedId);
        return "已添加 AI：" + (configured == null ? addedId : configured.getName())
                + "，难度：" + difficultyName(difficulty);
    }

    private String setPlayer(BattleRoomSnapshot room, List<String> arguments) {
        String selector = optionalValue(arguments, "--player");
        String named = optionalValue(arguments, "--name");
        if ((selector != null) == (named != null)) {
            throw new IllegalArgumentException("Specify exactly one of --player or --name");
        }
        BattleRoomPlayer player = selector == null ? playerNamed(room, named) : playerBySelector(room, selector);
        String[] action = singlePlayerAction(arguments);
        switch (action[0]) {
            case "--move":
                return movePlayer(room, player, spawnPoint(action[1], room));
            case "--switch":
                return switchPlayer(room, player, teamOrSpectator(action[1]));
            case "--kick":
                if (player.isLocal()) {
                    throw new IllegalStateException("Cannot kick the local player");
                }
                if (!gameSession.kickBattleRoomPlayer(player.getId())) {
                    throw new IllegalStateException("Unable to kick player");
                }
                battleRoomController.updateFromNetwork(true);
                return "已移除 " + player.getName();
            case "--difficulty": {
                if (!player.isAI()) {
                    throw new IllegalStateException(player.getName() + " is not an AI");
                }
                int difficulty = difficulty(action[1]);
                if (!gameSession.configureBattleRoomPlayer(player.getId(), null, null, null, difficulty)) {
                    throw new IllegalStateException("Unable to set AI difficulty");
                }
                battleRoomController.updateFromNetwork(true);
                BattleRoomSnapshot updated = gameSession.currentBattleRoom(false);
                BattleRoomPlayer renamed = updated == null ? null : playerById(updated, player.getId());
                return "已将 " + (renamed == null ? player.getName() : renamed.getName())
                        + " 的难度设置为 " + difficultyName(difficulty);
            }
            default:
                throw new IllegalStateException("Unsupported player action");
        }
    }

    private String movePlayer(BattleRoomSnapshot room, BattleRoomPlayer player, int targetSpawn) {
        BattleRoomPlayer target = playerAtSpawn(room, targetSpawn);
        if (target != null && target.getId().equals(player.getId())) {
            target = null;
        }
        if (target == null) {
            // 从旁观位回到出生点必须显式给阵营，否则引擎保留旁观颜色，出生点永远显示不出来。
            Integer team = spawnOf(player) == null ? AUTO_TEAM_VALUE : null;
            if (!gameSession.configureBattleRoomPlayer(player.getId(), targetSpawn, team, null, null)) {
                throw new IllegalStateException("Unable to move " + player.getName());
            }
            battleRoomController.updateFromNetwork(true);
            // id 跟着槽位走，所以移动后按出生点读回名字，而不是按旧 id。
            BattleRoomPlayer moved = playerAtSpawn(gameSession.currentBattleRoom(false), targetSpawn);
            String renamed = moved != null && !moved.getName().equals(player.getName())
                    ? "（现名 " + moved.getName() + "）"
                    : "";
            return "已将 " + player.getName() + " 移动到出生点 " + targetSpawn + renamed;
        }
        Integer sourceSpawn = spawnOf(player);
        if (sourceSpawn == null) {
            throw new IllegalStateException(player.getName() + " 正在旁观；请先把它移动到一个空的出生点");
        }
        // 引擎会把占位玩家换回被腾空的出生点（阵营一起换），所以一次调用就是完整的交换。
        if (!gameSession.configureBattleRoomPlayer(player.getId(), targetSpawn, teamValue(target), null, null)) {
            throw new IllegalStateException("Unable to move " + player.getName());
        }
        battleRoomController.updateFromNetwork(true);
        BattleRoomSnapshot updated = gameSession.currentBattleRoom(false);
        BattleRoomPlayer moved = playerAtSpawn(updated, targetSpawn);
        BattleRoomPlayer swapped = playerAtSpawn(updated, sourceSpawn);
        return "已将 " + player.getName() + " 与 " + target.getName() + " 交换出生点（"
                + targetSpawn + " ↔ " + sourceSpawn + "），现为 "
                + (moved == null ? player.getName() : moved.getName()) + " / "
                + (swapped == null ? target.getName() : swapped.getName());
    }

    private String switchPlayer(BattleRoomSnapshot room, BattleRoomPlayer player, Integer team) {
        if (team == null) {
            if (!gameSession.configureBattleRoomPlayer(player.getId(), SPECTATOR_SPAWN_VALUE, null, null, null)) {
                throw new IllegalStateException("Unable to switch player team");
            }
            battleRoomController.updateFromNetwork(true);
            return "已将 " + player.getName() + " 切换为旁观";
        }
        if (spawnOf(player) == null) {
            Integer spawn = firstFreeSpawn(room);
            if (spawn == null) {
                throw new IllegalStateException("No free spawn point to leave spectator mode");
            }
            if (!gameSession.configureBattleRoomPlayer(player.getId(), spawn, team, null, null)) {
                throw new IllegalStateException("Unable to switch player team");
            }
            battleRoomController.updateFromNetwork(true);
            return "已将 " + player.getName() + " 从旁观移回出生点 " + spawn;
        }
        if (!gameSession.configureBattleRoomPlayer(player.getId(), null, team, null, null)) {
            throw new IllegalStateException("Unable to switch player team");
        }
        battleRoomController.updateFromNetwork(true);
        return "已修改 " + player.getName() + " 的阵营";
    }

    private String selectMap(List<String> arguments) {
        String requested = requiredValue(arguments, "--name");
        MapEntry selected = null;
        for (MapEntry map : battleRoomController.enabledMaps()) {
            if (map.getDisplayName().equalsIgnoreCase(requested)
                    || map.getFileName().equalsIgnoreCase(requested)
                    || map.getMapAssetPath().equalsIgnoreCase(requested)) {
                selected = map;
                break;
            }
        }
        if (selected == null) {
            // A near miss should not force the caller to re-list every map; suggest instead of guessing.
            java.util.List<MapEntry> candidates = fuzzyMapCandidates(requested);
            StringBuilder message = new StringBuilder("Map is not enabled: ").append(requested);
            if (candidates.isEmpty()) {
                message.append(" (use ").append(RwxCliCommands.Command.BATTLE_ROOM_SHOW.commandName())
                        .append(" to list the enabled maps)");
            } else {
                message.append("\n你是不是要找：");
                for (MapEntry candidate : candidates) {
                    message.append("\n  · ").append(candidate.getDisplayName())
                            .append(" (").append(candidate.getFileName()).append(')');
                }
                message.append("\n先确认名字，再用 ").append(RwxCliCommands.Command.BATTLE_ROOM_SELECT_MAP.commandName())
                        .append(" --name <name> 选择。");
            }
            throw new IllegalStateException(message.toString());
        }
        battleRoomController.selectBattleRoomMap(selected);
        BattleRoomSnapshot updated = gameSession.currentBattleRoom(false);
        if (updated == null) {
            throw new IllegalStateException("Unable to select map");
        }
        return currentMap(updated);
    }

    /** Up to five enabled maps whose display name/file name is close to {@code requested}. */
    private java.util.List<MapEntry> fuzzyMapCandidates(String requested) {
        String needle = normalizeMapName(requested);
        java.util.List<MapEntry> matches = new java.util.ArrayList<>();
        java.util.Map<MapEntry, Integer> scores = new java.util.HashMap<>();
        for (MapEntry map : battleRoomController.enabledMaps()) {
            String display = normalizeMapName(map.getDisplayName());
            String file = normalizeMapName(map.getFileName());
            String asset = normalizeMapName(map.getMapAssetPath());
            int best = Integer.MAX_VALUE;
            for (String candidate : new String[] {display, file, asset}) {
                if (candidate.isEmpty()) continue;
                if (candidate.contains(needle) || needle.contains(candidate)) {
                    best = Math.min(best, 0);
                } else {
                    int distance = editDistance(needle, candidate);
                    if (distance <= Math.max(2, needle.length() / 3)) best = Math.min(best, distance);
                }
            }
            if (best != Integer.MAX_VALUE) {
                matches.add(map);
                scores.put(map, best);
            }
        }
        matches.sort((left, right) -> {
            int byScore = Integer.compare(scores.get(left), scores.get(right));
            return byScore != 0 ? byScore
                    : left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
        });
        if (matches.size() > 5) return new java.util.ArrayList<>(matches.subList(0, 5));
        return matches;
    }

    private static String normalizeMapName(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\.(tmx|map|json)$", "")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "");
    }

    /** Levenshtein distance with a two-row buffer; names are short, so this stays cheap. */
    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * 修改房间设置；读取当前设置请用 {@code battleroom-show --settings}。
     *
     * <p>走的是 GUI 房间设置对话框同一条路径（{@code applyBattleRoomOptions} /
     * {@code setBattleRoomMaxPlayers} / {@code applyBattleRoomTeamLayout}），先全部校验再一次性提交，
     * 任何一个参数非法都不会留下半套改动。</p>
     */
    private String configureSettings(BattleRoomSnapshot room, List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("no settings given; read the current values with "
                    + RwxCliCommands.Command.BATTLE_ROOM_SHOW.commandName() + " --settings");
        }
        if (!room.isHost()) {
            throw new IllegalStateException("Only the room host can change room settings");
        }
        requireNoLoadedGame(RwxCliCommands.Command.BATTLE_ROOM_SETTINGS.commandName());
        java.util.Map<String, String> values = optionValues(arguments, SETTING_OPTIONS);
        GameRoomSettings options = room.getRoom().getOptions().clone();
        Integer maxPlayers = null;
        BattleRoomTeamLayout layout = null;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "--income": options.incomeMultiplier = incomeMultiplier(value); break;
                case "--credits": options.startingCredits = startingCreditsCode(value); break;
                case "--fog": options.fogMode = fogMode(value); break;
                case "--startunits": options.startingUnits = intInRange(value, "--startUnits", 1, 5); break;
                case "--aidifficulty": options.aiDifficulty = difficulty(value); break;
                case "--revealedmap": options.revealedMap = bool(value, "--revealedMap"); break;
                case "--nonukes": options.noNukes = bool(value, "--noNukes"); break;
                case "--sharedcontrol": options.sharedControl = bool(value, "--sharedControl"); break;
                case "--allowspectators": options.allowSpectators = bool(value, "--allowSpectators"); break;
                case "--teamlock": options.teamLock = bool(value, "--teamLock"); break;
                case "--roomlock": options.roomLock = bool(value, "--roomLock"); break;
                case "--fixedallyteams": options.fixedAllyTeams = bool(value, "--fixedAllyTeams"); break;
                case "--maxplayers": maxPlayers = intInRange(value, "--maxPlayers", 10, 100); break;
                case "--teamlayout": layout = teamLayout(value); break;
                default: throw new IllegalArgumentException("Unsupported option: " + entry.getKey());
            }
        }
        if (!gameSession.applyBattleRoomOptions(options)) {
            throw new IllegalStateException("Game session rejected battle room options");
        }
        if (maxPlayers != null && !gameSession.setBattleRoomMaxPlayers(maxPlayers)) {
            throw new IllegalStateException("Unable to set max players");
        }
        if (layout != null && !gameSession.applyBattleRoomTeamLayout(layout)) {
            throw new IllegalStateException("Unable to apply team layout");
        }
        battleRoomController.updateFromNetwork(true);
        BattleRoomSnapshot updated = gameSession.currentBattleRoom(false);
        if (updated == null) {
            throw new IllegalStateException("Unable to read battle room after applying settings");
        }
        String result = "已更新房间设置\n" + settings(updated);
        if (layout == null) return result;
        String layoutNote = layout == BattleRoomTeamLayout.Ffa
                ? "\n注意: FFA 由引擎随机重排 teamId（即出生点），房主出生点可能变化；下方玩家列表是最终分配。"
                : "";
        return result + layoutNote + "\n\n" + playerList(updated);
    }

    private String chat(List<String> arguments) {
        String text = String.join(" ", arguments).trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("chat text is required");
        }
        if (!gameSession.sendBattleRoomMessage(text)) {
            throw new IllegalStateException("Unable to send chat");
        }
        List<MultiplayerChatSnapshot> history = gameSession.multiplayerChatHistory();
        List<MultiplayerChatSnapshot> visible = history;
        String last = history.isEmpty() ? null : history.get(history.size() - 1).getText();
        // 聊天记录带发送者前缀（"Player: 内容"），所以按结尾匹配，避免重复显示同一条。
        if (last == null || !last.endsWith(text)) {
            visible = new ArrayList<>(history);
            visible.add(new MultiplayerChatSnapshot(text, -1));
        }
        return chatHistory(takeLast(visible, 3), "最新聊天");
    }

    // ---------------------------------------------------------------- 参数解析

    private static Set<String> flags(List<String> arguments, String... allowed) {
        Set<String> flags = new LinkedHashSet<>();
        for (String argument : arguments) {
            flags.add(argument.toLowerCase(Locale.ROOT));
        }
        Set<String> allowedFlags = new LinkedHashSet<>(Arrays.asList(allowed));
        for (String flag : flags) {
            if (!allowedFlags.contains(flag)) {
                throw new IllegalArgumentException("Unsupported option: " + flag);
            }
        }
        return flags;
    }

    private static String requiredValue(List<String> arguments, String flag) {
        String value = optionalValue(arguments, flag);
        if (value == null) {
            throw new IllegalStateException(flag + " requires a value");
        }
        return value;
    }

    private static String optionalValue(List<String> arguments, String flag) {
        int found = -1;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i).equalsIgnoreCase(flag)) {
                if (found >= 0) {
                    throw new IllegalArgumentException(flag + " was specified more than once");
                }
                found = i;
            }
        }
        if (found < 0) {
            return null;
        }
        int valueIndex = found + 1;
        if (valueIndex >= arguments.size() || arguments.get(valueIndex).startsWith("--")) {
            throw new IllegalStateException(flag + " requires a value");
        }
        return arguments.get(valueIndex);
    }

    /** 返回 {操作, 值}；--kick 的值为 null。 */
    private static String[] singlePlayerAction(List<String> arguments) {
        String action = null;
        int matches = 0;
        for (String candidate : PLAYER_ACTIONS) {
            for (String argument : arguments) {
                if (argument.equalsIgnoreCase(candidate)) {
                    action = candidate;
                    matches++;
                    break;
                }
            }
        }
        if (matches != 1) {
            throw new IllegalArgumentException("Specify exactly one of --move, --switch, --kick, or --difficulty");
        }
        String value = "--kick".equals(action) ? null : requiredValue(arguments, action);
        Set<String> accepted = new LinkedHashSet<>(Arrays.asList("--player", "--name", action));
        for (String argument : arguments) {
            String lower = argument.toLowerCase(Locale.ROOT);
            if (lower.startsWith("--") && !accepted.contains(lower)) {
                throw new IllegalArgumentException("Unsupported option: " + lower);
            }
        }
        return new String[]{action, value};
    }

    /**
     * Accepts a registered AI code (original {@code -2..3} or custom {@code >=4}), a stable id
     * (with or without the {@code rwx.}/{@code rwx.original.} prefix), or the English/Chinese
     * display name. Matching ignores case, spaces, underscores and hyphens.
     */
    static int resolveDifficulty(String value) {
        Integer parsed = parseIntOrNull(value);
        if (parsed != null && AiRegistry.find(parsed) != null) return parsed;
        if (parsed == null && value != null) {
            String wanted = normalizeAiName(value);
            for (AiDefinition definition : AiRegistry.definitions()) {
                if (wanted.equals(normalizeAiName(definition.id()))
                        || wanted.equals(normalizeAiName(definition.id().replaceFirst("^rwx\\.(original\\.)?", "")))
                        || wanted.equals(normalizeAiName(definition.displayName()))
                        || wanted.equals(normalizeAiName(
                                definition.displayName(Locale.SIMPLIFIED_CHINESE)))) {
                    return definition.code();
                }
            }
        }
        StringBuilder known = new StringBuilder();
        for (AiDefinition definition : AiRegistry.definitions()) {
            known.append("\n  ").append(definition.code()).append(" | ")
                    .append(definition.displayName()).append(" / ")
                    .append(definition.displayName(Locale.SIMPLIFIED_CHINESE))
                    .append(" | ").append(definition.id());
        }
        throw new IllegalStateException(
                "--difficulty must be a registered AI code (original -2..3, custom >=4), "
                        + "stable id, or English/Chinese name:" + known);
    }

    private static int difficulty(String value) {
        return resolveDifficulty(value);
    }

    private static String normalizeAiName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }

    private static String difficultyName(int difficulty) {
        switch (difficulty) {
            case -2: return "非常简单";
            case -1: return "简单";
            case 0: return "中等";
            case 1: return "困难";
            case 2: return "非常困难";
            case 3: return "不可能";
            default: return AiRegistry.displayName(difficulty);
        }
    }

    /**
     * 地图自带的出生点上限（文件名里的 {@code [p3]}）。房间上限通常是从 10 起的独立设置，
     * 把玩家放进地图没有的出生点会让该阵营开局没有任何单位，所以选点要取两者的较小值。
     */
    private static int spawnLimit(BattleRoomSnapshot room) {
        int max = room.getMaxPlayers();
        String mapPath = room.getRoom() == null ? null : room.getRoom().getMapPath();
        int inMap = mapPath == null || mapPath.isBlank() ? -1 : MapMetadata.getNumberOfPlayersInMap(mapPath);
        return inMap > 0 ? Math.min(max, inMap) : max;
    }

    private static int spawnPoint(String value, BattleRoomSnapshot room) {
        Integer parsed = parseIntOrNull(value);
        int limit = spawnLimit(room);
        if (parsed == null || parsed < 1 || parsed > limit) {
            throw new IllegalStateException("spawn point must be an integer from 1 to " + limit);
        }
        return parsed;
    }

    /** null 表示旁观；返回值使用 UI 的 1 起算阵营编号。 */
    private static Integer teamOrSpectator(String value) {
        if (value.equalsIgnoreCase("spectator") || value.equalsIgnoreCase("spec")) {
            return null;
        }
        if (value.length() == 1) {
            char letter = Character.toUpperCase(value.charAt(0));
            if (letter >= 'A' && letter <= 'J') {
                return letter - 'A' + 1;
            }
        }
        Integer team = parseIntOrNull(value);
        if (team != null && team >= 1 && team <= 10) {
            return team;
        }
        throw new IllegalStateException("team must be A-J, 1-10, or spectator");
    }

    /** 解析 "--flag 值" 序列：每个选项必须带值、只能出现一次、必须在允许列表里。键为小写。 */
    private static java.util.Map<String, String> optionValues(List<String> arguments, Set<String> allowed) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < arguments.size(); i += 2) {
            String flag = arguments.get(i).toLowerCase(Locale.ROOT);
            if (!allowed.contains(flag)) {
                throw new IllegalArgumentException("Unsupported option: " + arguments.get(i));
            }
            if (i + 1 >= arguments.size() || arguments.get(i + 1).startsWith("--")) {
                throw new IllegalStateException(arguments.get(i) + " requires a value");
            }
            if (values.put(flag, arguments.get(i + 1)) != null) {
                throw new IllegalArgumentException(arguments.get(i) + " was specified more than once");
            }
        }
        return values;
    }

    private static float incomeMultiplier(String value) {
        float parsed;
        try {
            parsed = Float.parseFloat(value.trim().replaceFirst("[xX]$", ""));
        } catch (NumberFormatException error) {
            parsed = Float.NaN;
        }
        if (!Float.isFinite(parsed) || parsed < 0.1f || parsed > 100f) {
            throw new IllegalStateException("--income must be a number from 0.1 to 100 (e.g. 2.5)");
        }
        return parsed;
    }

    /** 房间里存的是档位编号，CLI 接受金额（或 default）再换算回档位。 */
    private static int startingCreditsCode(String value) {
        String normalized = value.trim().replace("$", "");
        if (normalized.equalsIgnoreCase("default")) return 0;
        Integer amount = parseIntOrNull(normalized);
        if (amount != null) {
            for (int code = 0; code < STARTING_CREDITS.length; code++) {
                if (STARTING_CREDITS[code] == amount) return code;
            }
        }
        throw new IllegalStateException("--credits must be default or one of "
                + "0, 1000, 2000, 4000, 5000, 10000, 50000, 100000, 200000");
    }

    private static int fogMode(String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "0": case "none": return 0;
            case "1": case "basic": return 1;
            case "2": case "los": return 2;
            default: throw new IllegalStateException("--fog must be none|basic|los (0-2)");
        }
    }

    private static BattleRoomTeamLayout teamLayout(String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "2": return BattleRoomTeamLayout.TwoSides;
            case "3": return BattleRoomTeamLayout.ThreeSides;
            case "ffa": return BattleRoomTeamLayout.Ffa;
            case "spectators": return BattleRoomTeamLayout.Spectators;
            case "random": return BattleRoomTeamLayout.Random;
            case "allvsai": return BattleRoomTeamLayout.AllVsAi;
            case "allvs2": return BattleRoomTeamLayout.AllVs2;
            default: throw new IllegalStateException("--teamLayout must be 2|3|ffa|spectators|random|allvsai|allvs2");
        }
    }

    private static int intInRange(String value, String flag, int min, int max) {
        Integer parsed = parseIntOrNull(value);
        if (parsed == null || parsed < min || parsed > max) {
            throw new IllegalStateException(flag + " must be an integer from " + min + " to " + max);
        }
        return parsed;
    }

    private static boolean bool(String value, String flag) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "on": case "1": case "yes": return true;
            case "false": case "off": case "0": case "no": return false;
            default: throw new IllegalStateException(flag + " must be true or false");
        }
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 玩家定位

    /** --player 接受出生点编号或玩家名；--name 只接受玩家名。 */
    private static BattleRoomPlayer playerBySelector(BattleRoomSnapshot room, String value) {
        String selector = value.trim();
        if (selector.isEmpty()) {
            throw new IllegalArgumentException("--player requires a spawn point or a player name");
        }
        Integer spawn = parseIntOrNull(selector);
        if (spawn == null) {
            return playerNamed(room, selector);
        }
        if (spawn < 1 || spawn > room.getMaxPlayers()) {
            throw new IllegalArgumentException("spawn point must be an integer from 1 to " + room.getMaxPlayers());
        }
        BattleRoomPlayer player = playerAtSpawn(room, spawn);
        if (player == null) {
            throw new IllegalStateException("No player at spawn point " + spawn);
        }
        return player;
    }

    private static BattleRoomPlayer playerNamed(BattleRoomSnapshot room, String rawName) {
        String requested = rawName == null ? "" : rawName.trim();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("--name requires a player name");
        }
        List<BattleRoomPlayer> matches = new ArrayList<>();
        for (BattleRoomPlayer player : room.getPlayers()) {
            if (player.getName().equalsIgnoreCase(requested)) {
                matches.add(player);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("No player named " + requested + "; available: " + playerNames(room));
        }
        throw new IllegalStateException("Player name is ambiguous: " + requested);
    }

    private static BattleRoomPlayer playerAtSpawn(BattleRoomSnapshot room, int spawn) {
        if (room == null) {
            return null;
        }
        for (BattleRoomPlayer player : room.getPlayers()) {
            Integer playerSpawn = parseIntOrNull(player.getSpawnLabel());
            if (playerSpawn != null && playerSpawn == spawn) {
                return player;
            }
        }
        return null;
    }

    private static BattleRoomPlayer playerById(BattleRoomSnapshot room, String playerId) {
        if (room == null) {
            return null;
        }
        for (BattleRoomPlayer player : room.getPlayers()) {
            if (player.getId().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    private static BattleRoomPlayer firstPlayerOutside(BattleRoomSnapshot room, Set<String> excludedIds) {
        for (BattleRoomPlayer player : room.getPlayers()) {
            if (!excludedIds.contains(player.getId())) {
                return player;
            }
        }
        return null;
    }

    private static Integer firstFreeSpawn(BattleRoomSnapshot room) {
        for (int spawn = 1, limit = spawnLimit(room); spawn <= limit; spawn++) {
            if (playerAtSpawn(room, spawn) == null) {
                return spawn;
            }
        }
        return null;
    }

    /** 玩家所在出生点；旁观者为 null。 */
    private static Integer spawnOf(BattleRoomPlayer player) {
        return parseIntOrNull(player.getSpawnLabel());
    }

    /** 1 起算阵营编号；旁观者为 null。 */
    private static Integer teamValue(BattleRoomPlayer player) {
        return player.getTeamColorIndex() >= 0 ? player.getTeamColorIndex() + 1 : null;
    }

    // ---------------------------------------------------------------- 文本输出

    private static String playerNames(BattleRoomSnapshot room) {
        StringBuilder names = new StringBuilder();
        for (BattleRoomPlayer player : room.getPlayers()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(player.getName());
        }
        return names.toString();
    }

    private static String playerList(BattleRoomSnapshot room) {
        StringBuilder text = new StringBuilder("玩家：");
        for (BattleRoomPlayer player : room.getPlayers()) {
            text.append('\n').append(player.getName())
                    .append(" | 阵营 ").append(player.getTeamLabel())
                    .append(" | 出生点 ").append(player.getSpawnLabel())
                    .append(" | 颜色 ").append(colorName(player));
        }
        return text.toString();
    }

    /** Uses the engine's configurable palette name instead of maintaining a separate CLI colour table. */
    private static String colorName(BattleRoomPlayer player) {
        int colorIndex = player.getNameColorIndex();
        return PlayerTeam.j(colorIndex) + " (" + colorIndex + ")";
    }

    private String currentMap(BattleRoomSnapshot room) {
        Integer playerCount = null;
        String mapPath = room.getRoom().getMapPath();
        for (MapEntry map : battleRoomController.enabledMaps()) {
            if (map.getMapAssetPath().equals(mapPath)) {
                playerCount = map.getPlayerCount();
                break;
            }
        }
        return "当前地图：" + withPlayerCount(room.getMapDisplayName(), playerCount);
    }

    private static String enabledMapList(List<MapEntry> maps) {
        StringBuilder text = new StringBuilder("可选地图：");
        for (MapEntry map : maps) {
            text.append('\n').append(withPlayerCount(map.getDisplayName(), map.getPlayerCount()));
        }
        return text.toString();
    }

    /** 地图名通常已经带 "(3p)"，只有没带时再补房间的玩家数。 */
    private static String withPlayerCount(String name, Integer playerCount) {
        if (playerCount == null || PLAYER_COUNT_LABEL.matcher(name).find()) {
            return name;
        }
        return name + " (" + playerCount + "P)";
    }

    private static String settings(BattleRoomSnapshot room) {
        GameRoomSettings options = room.getRoom().getOptions();
        int credits = options.startingCredits >= 0 && options.startingCredits < STARTING_CREDITS.length
                ? STARTING_CREDITS[options.startingCredits]
                : -1;
        return "房间设置："
                + "\n收入倍数 (--income): " + options.incomeMultiplier
                + "\n初始资金 (--credits): " + (credits < 0 ? "档位 " + options.startingCredits : "$" + credits)
                + (options.startingCredits == 0 ? "（默认）" : "")
                + "\n初始单位 (--startUnits): " + options.startingUnits
                + "\n雾效 (--fog): " + FOG_NAMES[Math.max(0, Math.min(2, options.fogMode))] + " (" + options.fogMode + ")"
                + "\n地图已探明 (--revealedMap): " + options.revealedMap
                + "\n默认 AI 难度 (--aiDifficulty): " + AiRegistry.displayName(options.aiDifficulty)
                + " (" + options.aiDifficulty + ")"
                + "\n最大玩家数 (--maxPlayers): " + room.getMaxPlayers()
                + "\n禁用核武器 (--noNukes): " + options.noNukes
                + "\n共享控制 (--sharedControl): " + options.sharedControl
                + "\n允许旁观 (--allowSpectators): " + options.allowSpectators
                + "\n锁定阵营 (--teamLock): " + options.teamLock
                + "\n锁定房间 (--roomLock): " + options.roomLock
                + "\n固定同盟 (--fixedAllyTeams): " + options.fixedAllyTeams;
    }

    private static String chatHistory(List<MultiplayerChatSnapshot> history, String title) {
        if (history.isEmpty()) {
            return title + "：无";
        }
        StringBuilder text = new StringBuilder(title).append('：');
        for (MultiplayerChatSnapshot message : history) {
            text.append('\n').append(message.getText());
        }
        return text.toString();
    }

    private static List<MultiplayerChatSnapshot> takeLast(List<MultiplayerChatSnapshot> messages, int count) {
        if (messages.size() <= count) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - count, messages.size()));
    }
}
