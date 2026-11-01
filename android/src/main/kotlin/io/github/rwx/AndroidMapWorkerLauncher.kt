package io.github.rwx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import io.github.rwx.map.multiplayer.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Binding owns the process; room teardown must close the returned handle. */
class AndroidMapWorkerLauncher(context: Context) : MapWorkerLauncher {
    private val context = context.applicationContext

    override fun launch(config: MapWorkerLaunch): MapWorkerProcess {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Launch map workers off the Android UI thread" }
        check(owned.compareAndSet(false, true)) { "This device already owns a map worker" }
        val worker = BoundWorker(context, config)
        try {
            worker.bind()
            return worker
        } catch (error: Throwable) {
            worker.close()
            throw error
        }
    }

    private class BoundWorker(private val context: Context, private val config: MapWorkerLaunch) : MapWorkerProcess {
        private val closed = AtomicBoolean()
        private val ready = CompletableFuture<MapWorkerEndpoint>()
        private val died = CountDownLatch(1)
        private val replyThread = HandlerThread("map-worker-replies").apply { start() }

        @Volatile
        private var binder: IBinder? = null
        private var bound = false
        override val endpoint: MapWorkerEndpoint get() = ready.getNow(null) ?: error("Map worker is not ready")
        private val recipient = Messenger(Handler(replyThread.looper) { message ->
            when (message.what) {
                MapSimulationService.READY -> {
                    runCatching {
                        MapWorkerWire.readEndpoint(requireNotNull(message.data.getByteArray(MapSimulationService.ENDPOINT)).inputStream())
                    }.onSuccess(ready::complete).onFailure(ready::completeExceptionally)
                    true
                }

                MapSimulationService.FAILED -> {
                    ready.completeExceptionally(IllegalStateException("Map simulation initialization failed"))
                    true
                }

                else -> false
            }
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                try {
                    service.linkToDeath({ markDead() }, 0)
                    if (closed.get()) return
                    val bytes = ByteArrayOutputStream().also { MapWorkerWire.writeLaunch(it, config) }.toByteArray()
                    require(bytes.size <= MapSimulationService.MAX_CONFIG_BYTES) { "Worker configuration is too large" }
                    Messenger(service).send(Message.obtain(null, MapSimulationService.LAUNCH).apply {
                        replyTo = recipient
                        data = Bundle().apply { putByteArray(MapSimulationService.CONFIG, bytes) }
                    })
                } catch (error: Throwable) {
                    ready.completeExceptionally(error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = markDead()
            override fun onBindingDied(name: ComponentName) = markDead()
            override fun onNullBinding(name: ComponentName) = markDead()
        }

        fun bind() {
            bound = context.bindService(
                Intent(context, MapSimulationService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            check(bound) { "Could not bind map simulation service" }
            ready.get(120, TimeUnit.SECONDS)
        }

        override fun isAlive(): Boolean = !closed.get() && binder?.isBinderAlive == true && died.count > 0

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                if (ready.isDone && !ready.isCompletedExceptionally && binder?.isBinderAlive == true) {
                    runCatching { MapWorkerClient(endpoint, config.world, config.controlToken).stop() }
                }
                if (bound) {
                    context.unbindService(connection)
                    bound = false
                    // onUnbind kills the isolated engine process before a new game may bind.
                    if (binder != null) check(died.await(8, TimeUnit.SECONDS)) { "Map simulation process did not exit" }
                }
            } finally {
                replyThread.quitSafely()
                if (binder == null || binder?.isBinderAlive != true) owned.set(false)
            }
        }

        private fun markDead() {
            died.countDown()
            ready.completeExceptionally(IllegalStateException("Map simulation process disconnected"))
            if (closed.get()) owned.set(false)
        }
    }

    private companion object {
        val owned = AtomicBoolean()
    }
}
