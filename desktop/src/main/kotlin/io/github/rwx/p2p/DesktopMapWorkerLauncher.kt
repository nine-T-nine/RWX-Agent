package io.github.rwx.p2p

import io.github.rwx.DesktopPlatformStorage
import io.github.rwx.PlatformStorage
import io.github.rwx.map.multiplayer.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A map authority always lives in a fresh JVM, even when the viewer uses Slick. */
class DesktopMapWorkerLauncher(
    private val storage: PlatformStorage,
    private val classpath: String = System.getProperty("java.class.path"),
    private val javaExecutable: File = File(
        System.getProperty("java.home"), "bin/" +
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    ),
) : MapWorkerLauncher {
    override fun launch(config: MapWorkerLaunch): MapWorkerProcess {
        // A worker owns private preference/cache storage. Resolve this machine's
        // virtual map path before crossing that process/storage boundary.
        val localConfig = storage.resolveVirtualPath(config.mapPath)?.takeIf { it.isFile }
            ?.let { config.copy(mapPath = it.absolutePath) } ?: config
        val workers = File(storage.cacheDir.file, "map-workers").apply { mkdirs() }
        val stateDirectory = Files.createTempDirectory(workers.toPath(), "world-").toFile()
        val ready = File(stateDirectory, "ready")
        val preferenceSource = File(storage.rootDir.file, "preferences.toml")
        if (preferenceSource.isFile) preferenceSource.copyTo(File(stateDirectory, "preferences.toml"))
        val process = ProcessBuilder(
            javaExecutable.absolutePath,
            "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED",
            "-Dlaunch.dir=${storage.rootDir.file.absolutePath}",
            "-Drwx.assetsDir=${DesktopPlatformStorage.resolveAssetRoot().absolutePath}",
            "-Drwx.log.dir=${File(stateDirectory, "logs").absolutePath}",
            "-cp", classpath, "io.github.rwx.MapWorkerMain", stateDirectory.absolutePath,
        ).directory(stateDirectory).redirectErrorStream(true)
            .redirectOutput(File(stateDirectory, "worker.log")).start()
        try {
            // Keep stdin open after the private launch frame. EOF is the owner-death signal.
            MapWorkerWire.writeLaunch(process.outputStream, localConfig)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!ready.isFile) {
                check(process.isAlive) {
                    "Map worker exited (${process.exitValue()}); see ${
                        File(
                            stateDirectory,
                            "worker.log"
                        )
                    }"
                }
                check(System.nanoTime() < deadline) {
                    "Map worker initialization timed out; see ${
                        File(
                            stateDirectory,
                            "worker.log"
                        )
                    }"
                }
                Thread.sleep(25)
            }
            val endpoint = ready.inputStream().use(MapWorkerWire::readEndpoint)
            return Child(process, endpoint, config)
        } catch (error: Throwable) {
            terminate(process)
            throw error
        }
    }

    private class Child(
        private val process: Process,
        override val endpoint: MapWorkerEndpoint,
        private val config: MapWorkerLaunch,
    ) : MapWorkerProcess {
        private val closed = AtomicBoolean()
        override fun isAlive(): Boolean = process.isAlive

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (process.isAlive) runCatching { MapWorkerClient(endpoint, config.world, config.controlToken).stop() }
            terminate(process)
        }
    }

    private companion object {
        fun terminate(process: Process) {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Map worker could not be terminated" }
                }
            }
        }
    }
}
