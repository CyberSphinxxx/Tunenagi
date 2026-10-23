package com.tunenagi.core.library

import com.tunenagi.core.database.TunenagiDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

/**
 * The library's matching repository — owned (TUN-0307).
 *
 * It exists because [SourceMatchRepository] is what the source inspector reads and writes, and
 * nothing in the graph supplied it before: the repository existed with tests but no construction
 * site outside them, so injecting it would have failed the build. The clock is the same
 * `Instant::now` the repository's own default expresses; tests build the repository directly with
 * a fixed clock instead of replacing this binding.
 *
 * A `@Provides` in `core.library` for the same structural reason `DatabaseModule` states: rule
 * B7 forbids `:app` from importing `core.database`, so the app could not name the types needed
 * to bind this itself.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun sourceMatchRepository(database: TunenagiDatabase): SourceMatchRepository =
        RoomSourceMatchRepository(database = database, clock = Instant::now)
}
