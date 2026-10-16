package com.tunenagi.core.playback

import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.model.PlaybackSourceResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The playback layer's wiring — owned.
 *
 * Two layers meet here and the meeting point is deliberately in `:core`: the resolution needs the
 * database (which a provider adapter and the app may not both reach — B7 forbids the app importing
 * `core.database`) and it needs the provider resolvers, which `core.playback` may not construct
 * (B11 keeps a provider from importing playback, and the rule that keeps providers out of playback
 * equally keeps playback from hard-coding a provider). So the resolution is *provided* here from a
 * database it is allowed to see and a set of resolvers it only knows as the domain interface, and
 * each adapter contributes itself (see `LocalProviderModule`).
 *
 * The scope is the application's, not a screen's: resolve work outlives any composable, and a scope
 * cancelled when a UI screen goes away would cancel the resolve that is starting playback.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun mediaItemResolution(
        database: TunenagiDatabase,
        resolvers: Set<@JvmSuppressWildcards PlaybackSourceResolver>
    ): MediaItemResolution = LibraryMediaItemResolution(
        database = database,
        resolvers = resolvers.toList(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    /**
     * The persisted queue the session's commands write through.
     *
     * One instance for the process: it is stateless over the database, but a second one would be a
     * second place a caller could hold and write, and the queue's whole value is that there is one
     * order everybody agrees on.
     */
    @Provides
    @Singleton
    fun queueRepository(database: TunenagiDatabase): QueueRepository = RoomQueueRepository(database)

    /** The pair the service is handed at start-up; see [PlaybackResolution]. */
    @Provides
    @Singleton
    fun playbackGraph(resolution: MediaItemResolution, queue: QueueRepository): PlaybackGraph =
        PlaybackGraph(resolution = resolution, queue = queue)
}
