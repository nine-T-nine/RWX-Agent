package io.github.rwx

import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.MusicManager
import com.corrodinggames.rts.gameFramework.NullMusicFactory
import io.github.rwx.di.coreModule
import io.github.rwx.geometry.Point
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.map.multiplayer.*
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.SkiaGraphicsEngine
import io.github.rwx.render.canvas.GameFontMetrics
import io.github.rwx.ui.InGameMenuController
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Headless entry point: raster resources are loaded, but no window or audio device is opened. */
object MapWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "A private worker state directory is required" }
        val stateDirectory = File(args.single()).canonicalFile
        val config = MapWorkerWire.readLaunch(System.`in`)
        val bridge = WorkerBridge(stateDirectory)
        startKoin {
            modules(coreModule, module {
                single<PlatformBridge> { bridge }
                single<PlatformStorage> { bridge.storage }
                single<PreferenceStorage> { bridge.preferenceStorage }
                single<AppMetadata> { bridge.appMetadata }
                single<AppLogger> { bridge.logger }
                single<CrashReporter> { bridge.crashReporter }
            })
        }
        bridge.storage.createDirectories()
        (bridge.crashReporter as FileCrashReporter).installAsDefaultUncaughtExceptionHandler()
        GameFontMetrics.install(DesktopFontMetrics(bridge.storage))
        MusicManager.musicFactory = NullMusicFactory()
        val graphics = SkiaGraphicsEngine(bridge.storage)
        val runtime = MapWorkerRuntime(config, stateDirectory) { createEngine(graphics) }
        Runtime.getRuntime().addShutdownHook(Thread({ runtime.close() }, "map-worker-shutdown"))
        thread(name = "map-worker-owner", isDaemon = true) {
            runtime.use {
                while (System.`in`.read() >= 0) { /* Only EOF has meaning after launch. */
                }
            }
        }
        var exitCode = 0
        try {
            val endpoint = runtime.start()
            val pending = File(stateDirectory, "ready.pending")
            pending.outputStream().use { MapWorkerWire.writeEndpoint(it, endpoint) }
            Files.move(pending.toPath(), File(stateDirectory, "ready").toPath(), StandardCopyOption.ATOMIC_MOVE)
            runtime.awaitTermination()
        } catch (error: Throwable) {
            error.printStackTrace()
            exitCode = 1
        } finally {
            runtime.close()
            graphics.close()
        }
        // The legacy engine owns additional timer threads; never retain them after a world ends.
        exitProcess(exitCode)
    }

    private fun createEngine(graphics: SkiaGraphicsEngine): GameLogic {
        GameEngine.externalGameLoopDriver = true
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isPCOrIOSVersion = false
        GameEngine.screenSize = Point(640, 480)
        GameEngine.graphicsEngine = graphics
        val engine = GameEngine.createGameEngine(null) as GameLogic
        engine.renderGraphicsEngine = graphics
        engine.minimap?.bindGraphicsBackend(graphics)
        TileMap.layerBufferManager.bindGraphicsBackend(graphics)
        TileMap.bindGraphicsBackend(graphics)
        engine.colorizeLogMessage(WorkerView(), false)
        engine.updateWindowResolution(640, 480)
        return engine
    }

    private class WorkerView : CoreGameView {
        private val pointers = MultiTouchPointerState()
        private val menu = InGameMenuController()
        override fun pause() = Unit
        override fun isActive() = true
        override fun isContinuousRendering() = true
        override fun isRendering() = true
        override fun getInGameMenuController() = menu
        override fun onResume() = Unit
        override fun getSettings() = pointers
        override fun onSizeChanged() = Unit
        override fun stopRender() = Unit
    }

    private class WorkerBridge(stateDirectory: File) : PlatformBridge {
        override val storage = MapWorkerPlatformStorage(DesktopPlatformStorage(), stateDirectory)
        override val preferenceStorage = MapWorkerPreferenceStorage(
            TomlPreferenceStorage(StorageLocation("/preferences", File(stateDirectory, "preferences.toml")))
        )
        override val appMetadata = AppMetadata()
        override val logger: AppLogger = DesktopLogger()
        override val crashReporter: CrashReporter = FileCrashReporter.get(
            File(stateDirectory, "crash.log"), mapOf("platform" to "Desktop map worker")
        )
        override val isMobilePlatform = false
        override var filePickerHost: PlatformFilePickerHost? = null
        override fun createModClassLoader(modFile: File, parent: ClassLoader): ClassLoader =
            URLClassLoader(arrayOf(modFile.toURI().toURL()), parent)
    }
}
