package io.github.rwx

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.GameLogic
import dalvik.system.DexClassLoader
import io.github.rwx.geometry.Point
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.map.multiplayer.*
import io.github.rwx.platform.CoreGameView
import io.github.rwx.ui.InGameMenuController
import org.koin.mp.KoinPlatform.getKoin
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import kotlin.concurrent.thread

/** One authority per isolated process. A new game gets a new process and engine singleton. */
class MapSimulationService : Service() {
    private val closing = AtomicBoolean()
    private val thread = HandlerThread("map-worker-bootstrap")
    private lateinit var incoming: Messenger

    @Volatile
    private var runtime: MapWorkerRuntime? = null
    private var launchReceived = false

    override fun onCreate() {
        super.onCreate()
        check(android.app.Application.getProcessName().endsWith(":map_simulation"))
        thread.start()
        incoming = Messenger(Handler(thread.looper) { message ->
            if (message.what == LAUNCH) {
                startWorker(message)
                true
            } else false
        })
    }

    override fun onBind(intent: Intent): IBinder = incoming.binder

    private fun startWorker(message: Message) {
        val reply = message.replyTo ?: return
        try {
            check(!launchReceived && !closing.get()) { "A map worker is already owned" }
            launchReceived = true
            val bytes = requireNotNull(message.data.getByteArray(CONFIG)) { "Missing worker configuration" }
            require(bytes.size <= MAX_CONFIG_BYTES) { "Worker configuration is too large" }
            val launch = MapWorkerWire.readLaunch(bytes.inputStream())
            val bridge = getKoin().get<PlatformBridge>() as AndroidMapWorkerBridge
            val worker = MapWorkerRuntime(launch, bridge.stateDirectory) { createEngine() }
            runtime = worker
            check(!closing.get()) { "Map owner disconnected during initialization" }
            val endpoint = worker.start()
            val data = ByteArrayOutputStream().also { MapWorkerWire.writeEndpoint(it, endpoint) }.toByteArray()
            reply.send(
                Message.obtain(null, READY).apply { this.data = Bundle().apply { putByteArray(ENDPOINT, data) } })
            thread(name = "map-worker-lifetime", isDaemon = true) {
                worker.awaitTermination()
                Handler(Looper.getMainLooper()).post { stopSelf(); shutdownProcess() }
            }
        } catch (error: Throwable) {
            logger.error(error) { "Map simulation initialization failed" }
            runCatching { reply.send(Message.obtain(null, FAILED)) }
            Handler(Looper.getMainLooper()).post { stopSelf(); shutdownProcess() }
        }
    }

    private fun createEngine(): GameLogic {
        GameEngine.externalGameLoopDriver = true
        GameEngine.isMenuBackgroundDisabled = true
        GameEngine.isNonAndroidVersion = true
        GameEngine.isPCOrIOSVersion = false
        GameEngine.screenSize = Point(640, 480)
        val graphics = AndroidGraphicsEngine(applicationContext, AndroidRenderBackend.CANVAS)
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

    override fun onUnbind(intent: Intent): Boolean {
        shutdownProcess()
        return false
    }

    override fun onDestroy() {
        shutdownProcess()
        super.onDestroy()
    }

    private fun shutdownProcess() {
        if (!closing.compareAndSet(false, true)) return
        thread(name = "map-worker-cleanup") {
            // A stuck legacy engine must not retain its process or be reused on the next bind.
            thread(name = "map-worker-stop-deadline", isDaemon = true) {
                Thread.sleep(5_000)
                Process.killProcess(Process.myPid())
            }
            try {
                runtime?.close()
            } finally {
                thread.quitSafely(); Process.killProcess(Process.myPid())
            }
        }
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

    internal companion object {
        const val LAUNCH = 1
        const val READY = 2
        const val FAILED = 3
        const val CONFIG = "config"
        const val ENDPOINT = "endpoint"
        const val MAX_CONFIG_BYTES = 512 * 1024
    }
}

/** Application-process bootstrap must not create the normal audio, log or preference services. */
internal class AndroidMapWorkerBridge(context: Context) : PlatformBridge {
    private val appContext = context.applicationContext
    val stateDirectory = File(appContext.cacheDir, "map-workers/${UUID.randomUUID()}").apply { mkdirs() }
    override val storage = MapWorkerPlatformStorage(AndroidPlatformStorage(appContext), stateDirectory)
    override val preferenceStorage = MapWorkerPreferenceStorage(AndroidPreferenceStorage(appContext))
    override val appMetadata = AppMetadata(packageName = appContext.packageName, installerPackageName = "android")
    override val displayDensity = appContext.resources.displayMetrics.density
    override val isMobilePlatform = true
    override val logger: AppLogger = AndroidLogger()
    override val crashReporter: CrashReporter = FileCrashReporter.get(
        File(stateDirectory, "crash.log"), androidCrashEnvironment(appContext, appMetadata)
    )
    override var filePickerHost: PlatformFilePickerHost? = null

    override fun createModClassLoader(modFile: File, parent: ClassLoader): ClassLoader {
        val directory = File(stateDirectory, "dex/${UUID.randomUUID()}").apply { mkdirs() }
        val dexFiles = JarFile(modFile).use { jar ->
            jar.entries().asSequence().filter { !it.isDirectory && Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) }
                .map { entry ->
                    File(directory, entry.name).also { target ->
                        target.outputStream().use { output ->
                            // Android 14 requires loaded dex files to be read-only.
                            check(target.setReadOnly()) { "Cannot protect worker dex file" }
                            jar.getInputStream(entry).use { input -> input.copyTo(output) }
                        }
                    }
                }.toList()
        }
        check(dexFiles.isNotEmpty()) { "Mod does not contain Android dex code" }
        val optimized = File(directory, "optimized").apply { mkdirs() }
        return DexClassLoader(dexFiles.joinToString(File.pathSeparator), optimized.absolutePath, null, parent)
    }
}
