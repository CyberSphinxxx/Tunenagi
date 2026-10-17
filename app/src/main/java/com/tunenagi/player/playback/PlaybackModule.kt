package com.tunenagi.player.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wiring for the playback facade — owned.
 *
 * The screens ask for [NowPlayingSource] and get the bound-service-backed implementation. The
 * indirection is deliberate: it is what lets the shell be driven in a JVM test without a service,
 * and it keeps `androidx.media3` out of the screens entirely.
 *
 * The binding is a singleton because a controller is a connection, not a value: a new instance per
 * screen would be a second bound service client with its own lifecycle and its own release.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindNowPlayingSource(connection: PlaybackConnection): NowPlayingSource
}
