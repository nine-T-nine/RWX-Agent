package io.github.rwx.app

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier
import io.github.rwx.ui.*
import io.github.rwx.ui.model.LevelSelectMode
import io.github.rwx.ui.model.SettingsPage

data class AppOptions(
    val initialScreen: AppScreen = AppScreen.MainMenu,
    val colorSchemeId: ColorSchemeId = ColorSchemeRegistry.defaultSchemeId,
    val levelSelectMode: LevelSelectMode? = null,
    val showDemoDialog: Boolean = false,
    val settingsPage: SettingsPage? = null,
    val autoStartSinglePlayerFromUi: Boolean = false,
    val autoStartBattleRoom: Boolean = false,
    val autoStartBattleRoomTwice: Boolean = false,
    val autoReturnMainMenuAfterGameReady: Boolean = false,
    val joinServer: String? = null,
    val isDesktop: Boolean = false,
    val noSteam: Boolean = false,
    val backendId: String? = null,
    val autoReplay: String? = null,
    /**
     * `--sandbox-map=<path>`: open the sandbox battle room with this map file already selected, so a map that
     * lives outside the usual map directories can be inspected or played without copying it in.
     */
    val sandboxMapPath: String? = null,
    val rwxCliEnabled: Boolean = false,
    val evaluate: Boolean = false,
    val dataDir: String? = null,
    val cliPortFile: String? = null,
    val evaluateCase: String? = null,
    val caseArgs: String? = null,
    val microConfig: String? = null,
    val window: Boolean = false,
    /** Game-time calibration: `realtime` (default), `slow[:0.5]`, or a bare multiplier. */
    val speedProfile: String? = null,
    /** Harness capability level for auto-attached alerts: `l1` (default) or `l0`. */
    val alertDelivery: String? = null,
    /** Evaluation tier (1 easy / 2 default / 3 hard); null keeps the default or `-Drwx.tier`. */
    val tier: Int? = null,
    /** Batch headless input: map to load directly, instead of a room, replay or server. */
    val headlessMapPath: String? = null,
    /** Batch headless stop condition: run this many frames and exit. */
    val headlessTickLimit: Int? = null,
) {
    /**
     * `--headless` and `--render-backend=headless` are the same thing: the flag only pins the
     * backend id, so the session, the platform bridge and the renderer all read one field.
     */
    val headless: Boolean get() = backendId == "headless"

    /**
     * `--evaluate` turns the built-in instrumentation on for one measured match: engine evidence collection for the
     * second-round detectors and, through [rwxCliEnabled], the CLI command recorder. Normal play keeps every hook off,
     * because per-frame sampling and socket recording cost frames. An explicit `-Drwx.evaluate.dir` still wins.
     */
    fun applyEvaluationDefaults() {
        // All three are run-level calibration knobs, so they must be in force before any session
        // exists: the delivery object is built with the app session and the sampler reads the
        // profile from the first tick on. Installing here also means one place decides, not three.
        com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.install(
            tier?.let { com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.of(it) }
                ?: com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.parse(
                    System.getProperty(com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier.PROPERTY),
                ),
        )
        com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.install(
            com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.parse(speedProfile),
        )
        alertDelivery?.let { level ->
            System.setProperty(
                com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDelivery.SWITCH_PROPERTY, level,
            )
        }
        if (evaluateCase != null || microConfig != null) {
            com.corrodinggames.rts.game.ai.custom.micro.MicroRuntime.configure(evaluateCase, caseArgs, microConfig)
            return
        }
        if (!evaluate || System.getProperty(EVALUATE_DIR_PROPERTY) != null) return
        val directory = RwxCliTool.repoRoot().resolve(EVALUATE_EVIDENCE_DIR)
        System.setProperty(EVALUATE_DIR_PROPERTY, directory.toString())
        println("RWX_EVALUATION enabled evidence=$directory")
    }

    companion object {
        private const val SCREEN_PREFIX = "--screen="
        private const val COLOR_SCHEME_PREFIX = "--color-scheme="
        private const val LEVEL_MODE_PREFIX = "--level-mode="
        private const val DEMO_DIALOG_ARG = "--demo-dialog"
        private const val NO_STEAM_ARG = "--no-steam"
        private const val SETTINGS_PAGE_PREFIX = "--settings-page="
        private const val AUTO_START_SINGLE_PLAYER_UI_ARG = "--auto-start-singleplayer-ui"
        private const val AUTO_START_BATTLE_ROOM_ARG = "--auto-start-battleroom"
        private const val AUTO_START_BATTLE_ROOM_TWICE_ARG = "--auto-start-battleroom-twice"
        private const val AUTO_RETURN_MAIN_MENU_AFTER_GAME_READY_ARG = "--auto-return-main-menu-after-game-ready"
        private const val JOIN_SERVER_PREFIX = "--join-server="
        private const val BACKEND_PREFIX = "--render-backend="
        private const val AUTO_REPLAY_PREFIX = "--auto-replay="
        private const val SANDBOX_MAP_ARG = "--sandbox-map"
        private const val SANDBOX_MAP_PREFIX = "--sandbox-map="
        private const val RWX_CLI_ARG = "--rwx-cli"
        private const val EVALUATE_ARG = "--evaluate"
        private const val WINDOW_ARG = "--window"
        private const val SPEED_PROFILE_PREFIX = "--speed-profile="
        private const val TIER_ARG = "--tier"
        private const val TIER_PREFIX = "--tier="
        private const val ALERT_DELIVERY_PREFIX = "--alert-delivery="
        private const val EVALUATE_DIR_PROPERTY = "rwx.evaluate.dir"
        private const val EVALUATE_EVIDENCE_DIR = "evaluate/output/events-live"
        private const val HEADLESS_ARG = "--headless"
        private const val MAP_PREFIX = "--map="
        private const val TICKS_PREFIX = "--ticks="

        private val screensByArgument = mapOf(
            "main-menu" to AppScreen.MainMenu,
            "single-player" to AppScreen.MainMenu,
            "level-select" to AppScreen.LevelSelect,
            "replay-select" to AppScreen.ReplaySelect,
            "replays" to AppScreen.ReplaySelect,
            "settings" to AppScreen.Settings,
            "loading" to AppScreen.Loading,
            "paused" to AppScreen.Paused,
            "in-game" to AppScreen.InGame,
            "multiplayer" to AppScreen.Multiplayer,
            "mods" to AppScreen.Mods,
            "resource-browser" to AppScreen.ResourceBrowser,
            "resources" to AppScreen.ResourceBrowser,
            "battleroom" to AppScreen.BattleRoom,
        )
        private val levelModesByArgument = mapOf(
            "campaign" to LevelSelectMode.Campaign,
            "skirmish" to LevelSelectMode.Skirmish,
            "challenge" to LevelSelectMode.Challenge,
            "survival" to LevelSelectMode.Survival,
            "custom" to LevelSelectMode.CustomMaps,
            "custom-maps" to LevelSelectMode.CustomMaps,
        )
        private val settingsPagesByArgument = mapOf(
            "display" to SettingsPage.Display,
            "audio" to SettingsPage.Audio,
            "gameplay" to SettingsPage.Gameplay,
            "keys" to SettingsPage.KeyBindings,
            "keybindings" to SettingsPage.KeyBindings,
            "key-bindings" to SettingsPage.KeyBindings,
            "theme" to SettingsPage.Theme,
        )

        fun parseArgs(args: Array<String>, isDesktop: Boolean = false): AppOptions {
            var initialScreen = AppScreen.MainMenu
            var colorSchemeId = ColorSchemeRegistry.defaultSchemeId
            var levelSelectMode: LevelSelectMode? = null
            var showDemoDialog = false
            var settingsPage: SettingsPage? = null
            var autoStartSinglePlayerFromUi = false
            var autoStartBattleRoom = false
            var autoStartBattleRoomTwice = false
            var autoReturnMainMenuAfterGameReady = false
            var joinServer: String? = null
            var noSteam = false
            var rendererId: String? = null
            var autoReplay: String? = null
            var sandboxMapPath: String? = null
            var rwxCliEnabled = false
            var evaluate = false
            var headless = false
            var headlessMapPath: String? = null
            var headlessTickLimit: Int? = null
            var dataDir: String? = null
            var cliPortFile: String? = null
            var evaluateCase: String? = null
            var caseArgs: String? = null
            var microConfig: String? = null
            var window = false
            var speedProfile: String? = null
            var alertDelivery: String? = null
            var tier: Int? = null

            var index = 0
            fun nextValue(name: String): String {
                require(index < args.size && !args[index].startsWith("--")) { "$name requires a value" }
                return args[index++].also { require(it.isNotBlank()) { "$name requires a value" } }
            }
            while (index < args.size) {
                val arg = args[index++]
                when {
                    arg.startsWith(SCREEN_PREFIX) -> initialScreen = parseScreen(arg.removePrefix(SCREEN_PREFIX))
                    arg.startsWith(COLOR_SCHEME_PREFIX) -> {
                        colorSchemeId = parseColorScheme(arg.removePrefix(COLOR_SCHEME_PREFIX))
                    }

                    arg.startsWith(LEVEL_MODE_PREFIX) -> {
                        levelSelectMode = parseLevelMode(arg.removePrefix(LEVEL_MODE_PREFIX))
                    }

                    arg == DEMO_DIALOG_ARG -> showDemoDialog = true
                    arg == NO_STEAM_ARG -> noSteam = true
                    arg == HEADLESS_ARG -> headless = true
                    arg.startsWith(MAP_PREFIX) -> {
                        headlessMapPath = arg.removePrefix(MAP_PREFIX).trim().also {
                            require(it.isNotEmpty()) { "Map path must not be empty" }
                        }
                    }
                    arg.startsWith(TICKS_PREFIX) -> {
                        headlessTickLimit = arg.removePrefix(TICKS_PREFIX).toIntOrNull().also {
                            require(it != null && it > 0) { "Tick limit must be a positive integer" }
                        }
                    }
                    arg == AUTO_START_SINGLE_PLAYER_UI_ARG -> autoStartSinglePlayerFromUi = true
                    arg == AUTO_START_BATTLE_ROOM_ARG -> autoStartBattleRoom = true
                    arg == AUTO_START_BATTLE_ROOM_TWICE_ARG -> {
                        autoStartBattleRoom = true
                        autoStartBattleRoomTwice = true
                    }

                    arg == AUTO_RETURN_MAIN_MENU_AFTER_GAME_READY_ARG -> autoReturnMainMenuAfterGameReady = true
                    arg.startsWith(JOIN_SERVER_PREFIX) -> {
                        joinServer = arg.removePrefix(JOIN_SERVER_PREFIX).trim().also {
                            require(it.isNotEmpty()) { "Server address must not be empty" }
                        }
                    }

                    arg.startsWith(SETTINGS_PAGE_PREFIX) -> {
                        settingsPage = parseSettingsPage(arg.removePrefix(SETTINGS_PAGE_PREFIX))
                    }

                    arg.startsWith(BACKEND_PREFIX) -> {
                        rendererId = parseRendererId(arg.removePrefix(BACKEND_PREFIX))
                    }

                    arg.startsWith(AUTO_REPLAY_PREFIX) -> {
                        autoReplay = arg.removePrefix(AUTO_REPLAY_PREFIX).trim().also {
                            require(it.isNotEmpty()) { "Replay file name must not be empty" }
                        }
                    }

                    arg == SANDBOX_MAP_ARG -> sandboxMapPath = nextValue(arg).trim().also(::requireSandboxMap)
                    arg.startsWith(SANDBOX_MAP_PREFIX) -> {
                        sandboxMapPath = arg.removePrefix(SANDBOX_MAP_PREFIX).trim().also(::requireSandboxMap)
                    }

                    arg == RWX_CLI_ARG -> rwxCliEnabled = true
                    arg == EVALUATE_ARG -> {
                        evaluate = true
                        // `--evaluate 2` selects an evaluation tier; `--evaluate CASE1-duel` a case.
                        if (index < args.size && !args[index].startsWith("--")) {
                            val value = nextValue(arg)
                            if (RwxTier.looksLikeTier(value)) tier = RwxTier.parse(value).level
                            else evaluateCase = value
                        }
                    }
                    arg.startsWith("--evaluate=") -> {
                        evaluate = true
                        val value = arg.substringAfter('=').also { require(it.isNotBlank()) }
                        if (RwxTier.looksLikeTier(value)) tier = RwxTier.parse(value).level
                        else evaluateCase = value
                    }
                    arg == TIER_ARG -> tier = RwxTier.parse(nextValue(arg)).level
                    arg.startsWith(TIER_PREFIX) -> tier = RwxTier.parse(arg.substringAfter('=')).level
                    arg == "--case-args" -> caseArgs = nextValue(arg)
                    arg.startsWith("--case-args=") -> caseArgs = arg.substringAfter('=')
                    arg == "--micro-config" -> microConfig = nextValue(arg)
                    arg.startsWith("--micro-config=") -> microConfig = arg.substringAfter('=').also { require(it.isNotBlank()) }
                    arg == WINDOW_ARG -> window = true
                    arg.startsWith(SPEED_PROFILE_PREFIX) -> speedProfile =
                        arg.substringAfter('=').also { require(it.isNotBlank()) { "Speed profile must not be empty" } }

                    arg.startsWith(ALERT_DELIVERY_PREFIX) -> alertDelivery =
                        arg.substringAfter('=').also { require(it.isNotBlank()) { "Alert delivery must not be empty" } }

                    arg.startsWith("--data-dir=") -> dataDir = arg.substringAfter('=').also { require(it.isNotBlank()) }
                    arg.startsWith("--cli-port-file=") -> cliPortFile = arg.substringAfter('=').also { require(it.isNotBlank()) }

                    else -> throw IllegalArgumentException("Unsupported app argument: $arg")
                }
            }
            require(caseArgs == null || evaluateCase != null || microConfig != null) { "--case-args requires a micro case" }
            require(!(window && headless)) { "--window and --headless are mutually exclusive" }
            // The sandbox lobby is a GUI flow: it needs the battle-room scene, which headless mode skips.
            require(sandboxMapPath == null || !headless) { "--sandbox-map needs the GUI; --headless has no lobby" }
            require(sandboxMapPath == null || listOfNotNull(autoReplay, joinServer, evaluateCase, microConfig).isEmpty()) {
                "--sandbox-map cannot be combined with a replay, server or micro case"
            }
            // Validate both calibration knobs here so a typo fails at launch, not mid-match.
            speedProfile?.let { com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile.parse(it) }
            alertDelivery?.let { level ->
                require(level.lowercase() in setOf("l0", "l1", "l1-lite", "l1lite", "lite", "minimal")) {
                    "--alert-delivery must be l0, l1 or l1-lite"
                }
            }
            if (evaluateCase != null || microConfig != null) {
                com.corrodinggames.rts.game.ai.custom.micro.MicroConfig.parse(evaluateCase, caseArgs, microConfig)
                require(autoReplay == null && joinServer == null) { "Micro cases cannot join a server or play a replay" }
            }

            if (headless) {
                require(rendererId == null || rendererId == "headless") {
                    "--headless conflicts with the selected render backend"
                }
                rendererId = "headless"
            }
            if (rendererId == "headless" || headlessMapPath != null || headlessTickLimit != null) {
                require(isDesktop && rendererId == "headless") { "Headless arguments require desktop headless mode" }
                require(listOfNotNull(headlessMapPath, autoReplay, joinServer).size <= 1) {
                    "Select only one headless map, replay or server"
                }
            }

            return AppOptions(
                initialScreen = initialScreen,
                colorSchemeId = colorSchemeId,
                levelSelectMode = levelSelectMode,
                showDemoDialog = showDemoDialog,
                settingsPage = settingsPage,
                autoStartSinglePlayerFromUi = autoStartSinglePlayerFromUi,
                autoStartBattleRoom = autoStartBattleRoom,
                autoStartBattleRoomTwice = autoStartBattleRoomTwice,
                autoReturnMainMenuAfterGameReady = autoReturnMainMenuAfterGameReady,
                joinServer = joinServer,
                isDesktop = isDesktop,
                noSteam = noSteam,
                backendId = rendererId,
                autoReplay = autoReplay,
                sandboxMapPath = sandboxMapPath,
                rwxCliEnabled = rwxCliEnabled || evaluate || headless || microConfig != null,
                evaluate = evaluate,
                dataDir = dataDir,
                cliPortFile = cliPortFile,
                evaluateCase = evaluateCase,
                caseArgs = caseArgs,
                microConfig = microConfig,
                window = window,
                speedProfile = speedProfile,
                alertDelivery = alertDelivery,
                tier = tier,
                headlessMapPath = headlessMapPath,
                headlessTickLimit = headlessTickLimit,
            )
        }

        private fun parseScreen(value: String): AppScreen = requireNotNull(screensByArgument[value]) {
            "Unsupported app screen: $value"
        }

        /** Fail at launch for an obvious typo instead of opening an empty sandbox room later. */
        private fun requireSandboxMap(value: String) {
            require(value.isNotEmpty()) { "--sandbox-map requires a .tmx path" }
            require(value.endsWith(".tmx", ignoreCase = true)) { "--sandbox-map expects a .tmx file: $value" }
        }

        private fun parseColorScheme(value: String): ColorSchemeId {
            val id = ColorSchemeId(value)
            require(ColorSchemeRegistry.schemes.any { it.id == id }) {
                "Unsupported app color scheme: $value"
            }
            return id
        }

        private fun parseLevelMode(value: String): LevelSelectMode = requireNotNull(levelModesByArgument[value]) {
            "Unsupported app level mode: $value"
        }

        private fun parseSettingsPage(value: String): SettingsPage = requireNotNull(settingsPagesByArgument[value]) {
            "Unsupported app settings page: $value"
        }

        private fun parseRendererId(value: String): String {
            val normalized = value.trim().lowercase()
            require(normalized.isNotEmpty()) { "Render backend id must not be empty" }
            return normalized
        }
    }
}
