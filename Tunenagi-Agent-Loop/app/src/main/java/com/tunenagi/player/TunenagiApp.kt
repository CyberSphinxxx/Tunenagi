package com.tunenagi.player

import android.app.Application
import com.tunenagi.core.playback.MediaItemResolution
import com.tunenagi.core.playback.PlaybackResolution
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Tunenagi application entry point — owned.
 *
 * Hilt annotation is the only runtime wiring the app needs at the top level for the
 * initial phase. Feature modules extend this with their own `@Module` contributions
 * as they are added; do not centralize all bindings here.
 *
 * One thing does belong here: handing the playback service the resolution its session resolves
 * through. The service is created by the operating system, so `onCreate` of the Application — which
 * always runs first — is the only place that can put it in place before a session exists (ADR-013).
 * The graph itself is assembled from Hilt modules in `:core` (the database) and in the local
 * provider adapter (the resolver), so this class installs a value it did not have to build.
 */
@HiltAndroidApp
class TunenagiApp : Application() {

    @Inject
    lateinit var mediaItemResolution: MediaItemResolution

    override fun onCreate() {
        super.onCreate()
        PlaybackResolution.install(mediaItemResolution)
    }
}
