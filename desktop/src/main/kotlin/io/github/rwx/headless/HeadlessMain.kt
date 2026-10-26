package io.github.rwx.headless

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.MusicManager
import com.corrodinggames.rts.gameFramework.NullMusicFactory
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
import io.github.rwx.*
import io.github.rwx.app.*
import io.github.rwx.di.coreModule
import io.github.rwx.di.desktopModule
import io.github.rwx.i18n.LocaleSettings
import io.github.rwx.render.frame.GameViewport
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

object HeadlessMain {
    fun run(options: AppOptions) {
        System.setProperty("java.awt.headless", "true")
        RwxCliTool.forceUtf8Stdio()
        configureDesktopLogging()
        GameEngine.isHeadlessMode = true
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isDesktopInitialized = true
        GameEngine.isJavaDesktopVersion = true
        // Keep the same PC protocol and simulation rules as GUI clients.
        GameEngine.isPCOrIOSVersion = true
        MusicManager.musicFactory = NullMusicFactory()
        GlobalContext.startKoin { modules(coreModule, desktopModule, module { single { options } }) }
        val koin = GlobalContext.get()
        koin.get<PlatformStorage>().createDirectories()
        LocaleSettings.initialize()
        val game = koin.get<HeadlessGameSession>()
        val viewport = GameViewport(1280, 720)
        val done = CountDownLatch(1)
        var cli: RwxCliTool? = null
        var app: AppSession? = null
        SwingUtilities.invokeAndWait {
            game.preload(viewport)
            app = installApp(
                viewportProvider = { viewport },
                scheduler = SwingFrameScheduler(framePump = {
                    try { game.pump() } catch (error: Throwable) {
                        logger.error(error) { "Headless simulation failed" }
                        kotlin.system.exitProcess(1)
                    }
                }),
                presenter = GameFramePresenter { },
                options = options,
                onQuit = { cli?.close(); app?.close(); done.countDown() },
            )
            cli = RwxCliTool.start({ command, arguments -> app!!.executeCliCommand(command, arguments).join() }, false)
            println("RWX_HEADLESS_READY pid=${ProcessHandle.current().pid()} renderer=none audio=none")
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            cli?.close()
            GameEngine.getInstance()?.networkEngine?.disconnectNetworking("process exiting")
        })
        done.await()
        GlobalContext.stopKoin()
        kotlin.system.exitProcess(0)
    }
}
