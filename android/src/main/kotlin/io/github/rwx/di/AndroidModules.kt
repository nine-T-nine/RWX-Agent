package io.github.rwx.di

import android.content.Context
import io.github.rwx.*
import io.github.rwx.p2p.WebRtcTunnelProxy
import io.github.rwx.map.multiplayer.MapWorkerLauncher
import io.github.rwx.session.GameSession
import io.github.rwx.settings.KEY_ANDROID_OPENGL_RENDERER
import org.koin.dsl.module

fun androidModule(context: Context, mapWorker: Boolean = false) = module {
    if (!mapWorker) WebRtcTunnelProxy.registerFactory { config -> AndroidWebRtcTunnelProxy(context, config) }
    single<PlatformBridge> { if (mapWorker) AndroidMapWorkerBridge(context) else AndroidPlatformBridge(context) }
    single<PlatformStorage> { get<PlatformBridge>().storage }
    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
    single<AppMetadata> { get<PlatformBridge>().appMetadata }
    single<AppLogger> { get<PlatformBridge>().logger }
    single<CrashReporter> { get<PlatformBridge>().crashReporter }
    single<MapWorkerLauncher> { AndroidMapWorkerLauncher(context) }
    single {
        AndroidGameSession(
            renderBackend = selectedAndroidRenderBackend(),
        )
    }
    single<GameSession> { get<AndroidGameSession>() }
}

private fun org.koin.core.scope.Scope.selectedAndroidRenderBackend(): AndroidRenderBackend {
    val preferences = get<PreferenceStorage>().preference(PREFERENCE_NAME)
    return selectedAndroidRenderBackend(
        useOpenGlPreference = preferences.getBoolean(KEY_ANDROID_OPENGL_RENDERER, false),
        incompleteLoadAttempts = preferences.getInt("numIncompleteLoadAttempts", 0),
        loadsSinceNormalExit = preferences.getInt("numLoadsSinceRunningGameOrNormalExit", 0),
    )
}

internal fun selectedAndroidRenderBackend(
    useOpenGlPreference: Boolean,
    incompleteLoadAttempts: Int = 0,
    loadsSinceNormalExit: Int = 0,
): AndroidRenderBackend =
    when {
        useOpenGlPreference && incompleteLoadAttempts <= 3 && loadsSinceNormalExit <= 15 ->
            AndroidRenderBackend.OPENGL_ES

        else -> AndroidRenderBackend.CANVAS
    }
