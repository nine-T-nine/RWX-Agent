package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.app.AppOptions
import io.github.rwx.app.GameFramePresenter
import io.github.rwx.app.installApp
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
import io.github.rwx.di.coreModule
import io.github.rwx.di.desktopModule
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.render.canvas.GameFontMetrics
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.skia.SkiaGameSession
import io.github.rwx.skia.SkiaGameView
import io.github.rwx.slick.SlickFramePresenter
import io.github.rwx.slick.SlickGameSession
import io.github.rwx.steam.SteamBridge
import io.github.rwx.ui.AppUiState
import io.github.rwx.ui.ColorSchemeRegistry
import io.github.rwx.ui.host.LoadingSceneHost
import io.github.rwx.ui.model.SettingsModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicReference

object DesktopMain : KoinComponent {
    @JvmStatic
    fun main(args: Array<String>) {
        val startup = AppOptions.parseArgs(args, isDesktop = true)
        startup.dataDir?.let { System.setProperty("launch.dir", java.io.File(it).absolutePath) }
        startup.cliPortFile?.let { System.setProperty("rwx.cli.portFile", java.io.File(it).absolutePath) }
        if (startup.headless) {
            io.github.rwx.headless.HeadlessMain.run(startup)
            return
        }
        // CLI 模式下先把标准输出切到 UTF-8：Windows 默认按 GBK 写日志，Python 等客户端
        // 按 UTF-8 读取时中文会乱码；必须早于日志系统初始化。
        if (args.any { it == "--rwx-cli" }) RwxCliTool.forceUtf8Stdio()
        configureDesktopLogging()
        val options = AppOptions.parseArgs(args, isDesktop = true)
        configureLwjglMemoryStack()
        System.setProperty("org.lwjgl.opengl.contextAPI", "native")
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isDesktopInitialized = true
        GameEngine.isJavaDesktopVersion = true
        GameEngine.isPCOrIOSVersion = true
        InputController.b = DesktopInputHandler()
        ensureDesktopOpenAlMusicFactory()
        GlobalContext.startKoin { modules(coreModule, desktopModule, module { single { options } }) }
        val storedBackendId =
            get<PreferenceStorage>().preference(PREFERENCE_NAME).getString("desktopRenderBackend", "slick")
        val renderBackend = DesktopRenderBackend.selectedId(options.backendId, storedBackendId)
        (get<CrashReporter>() as? FileCrashReporter)?.installAsDefaultUncaughtExceptionHandler()
        GameFontMetrics.install(DesktopFontMetrics(get<PlatformStorage>()))
        SettingsEngine.getInstance().save()
        val settings = SettingsModel().also {
            get<GameSettingsRepository>().loadInto(it)
            get<GameSettingsRepository>().saveFrom(it)
        }
        LocaleSettings.initialize()
        if (options.colorSchemeId != ColorSchemeRegistry.defaultSchemeId) {
            settings.selectedColorSchemeId.value = options.colorSchemeId
        }
        val bridge = get<PlatformBridge>()
        val steamBridge = SteamBridge()
        if (steamBridge.init(options.noSteam)) {
            GameEngine.isSteamModeEnabled = true
            logger.info { "Steam initialized; playtime and overlay enabled" }
        } else {
            logger.info { "Steam unavailable; running without Steam features" }
        }
        Runtime.getRuntime().addShutdownHook(Thread { steamBridge.shutdown() })
        SwingUtilities.invokeLater {
            val cliTool = AtomicReference<RwxCliTool?>(null)
            val host = when (renderBackend) {
                DesktopRenderBackend.Skia -> SwingAppHost.create(
                    fullscreen = SettingsEngine.getInstance().slick2dFullScreen,
                    shutdownRenderer = { get<SkiaGameSession>().close() },
                    skiaGameContent = { inputEnabled ->
                        SkiaGameView(session = get<SkiaGameSession>(), inputEnabled = inputEnabled)
                    },
                )

                DesktopRenderBackend.Slick -> SwingAppHost.create(
                    fullscreen = SettingsEngine.getInstance().slick2dFullScreen
                )
            }
            bridge.filePickerHost = host
            host.closeCompletion.whenComplete { _, error ->
                if (error != null) {
                    logger.error(error) { "Desktop shutdown failed; native frame was not disposed" }
                } else {
                    cliTool.getAndSet(null)?.close()
                    bridge.filePickerHost = null
                    GlobalContext.stopKoin()
                    kotlin.system.exitProcess(0)
                }
            }
            host.installComposeStartupUi(AppUiState(
                composeEnabled = true,
                colorSchemeId = settings.selectedColorSchemeId.value,
                overlayOpacity = settings.overlayOpacity.value,
                loading = LoadingSceneHost(settings).snapshot(),
            ))
            try {
                val presenter: GameFramePresenter = when (renderBackend) {
                    DesktopRenderBackend.Skia -> GameFramePresenter { }
                    DesktopRenderBackend.Slick -> SlickFramePresenter { host.presentSnapshot(get<SlickGameSession>().currentSnapshot()) }
                }
                val session = installApp(
                    viewportProvider = { host.viewport },
                    scheduler = SwingFrameScheduler(framePump = steamBridge::pump),
                    presenter = presenter,
                    options = options,
                    onQuit = host::requestClose,
                )
                host.installComposeUi(session)
                if (options.rwxCliEnabled) {
                    cliTool.set(RwxCliTool.start { command, arguments -> session.executeCliCommand(command, arguments).get() })
                }
            } catch (error: Throwable) {
                bridge.filePickerHost = null
                host.dispose()
                throw error
            }
        }
    }
}
