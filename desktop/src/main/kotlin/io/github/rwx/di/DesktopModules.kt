package io.github.rwx.di

import io.github.rwx.*
import io.github.rwx.app.AppOptions
import io.github.rwx.p2p.DesktopWebRtcTunnelProxy
import io.github.rwx.p2p.DesktopMapWorkerLauncher
import io.github.rwx.map.multiplayer.MapWorkerLauncher
import io.github.rwx.p2p.WebRtcTunnelProxy
import io.github.rwx.session.GameSession
import io.github.rwx.slick.SlickGameSession
import io.github.rwx.skia.SkiaGameSession
import io.github.rwx.headless.HeadlessAppSession
import io.github.rwx.headless.HeadlessGameSession
import org.koin.dsl.module

val desktopModule = module {
    WebRtcTunnelProxy.registerFactory { config -> DesktopWebRtcTunnelProxy(config) }
    single<PlatformBridge> { DesktopPlatformBridge(headless = runCatching { get<AppOptions>().headless }.getOrDefault(false)) }
    single<PlatformStorage> { get<PlatformBridge>().storage }
    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
    single<MapWorkerLauncher> { DesktopMapWorkerLauncher(get()) }
    single<AppMetadata> { get<PlatformBridge>().appMetadata }
    single<AppLogger> { get<PlatformBridge>().logger }
    single<CrashReporter> { get<PlatformBridge>().crashReporter }
    single { SlickGameSession(storage = get()) }
    single { SkiaGameSession(storage = get()) }
    // Two headless sessions with different drivers: the app-loop one is what --headless selects,
    // the caller-driven one is only used by the batch runner (--map / --ticks).
    single { HeadlessAppSession(storage = get()) }
    single { HeadlessGameSession(storage = get()) }
    single<GameSession> {
        if (get<AppOptions>().headless) return@single get<HeadlessAppSession>()
        val rendererId = runCatching { get<AppOptions>().backendId }.getOrNull()
        val stored = runCatching {
            get<PreferenceStorage>().preference(PREFERENCE_NAME).getString("desktopRenderBackend", "slick")
        }.getOrNull()
        when (DesktopRenderBackend.selectedId(rendererId, stored)) {
            DesktopRenderBackend.Skia -> get<SkiaGameSession>()
            DesktopRenderBackend.Slick -> get<SlickGameSession>()
            DesktopRenderBackend.Headless -> get<HeadlessAppSession>()
        }
    }
}
