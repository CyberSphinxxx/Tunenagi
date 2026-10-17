package com.tunenagi.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Opening the library database — owned.
 *
 * `TunenagiDatabase.open` is the only supported way to build the database, and this module is the
 * only place application code asks for it, so the migrations registered there cannot be bypassed by
 * a second, hand-rolled builder somewhere else. It is a singleton because two Room instances over
 * one file are two caches of the same rows: a change written through one is not guaranteed to be
 * visible to the other until it re-reads.
 *
 * A `@Provides` in `core.database` rather than a `@Binds` in the app for a structural reason: rule
 * B7 forbids `:app` from importing this package, so the app could not even name the type to bind it.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun tunenagiDatabase(@ApplicationContext context: Context): TunenagiDatabase =
        TunenagiDatabase.open(context)
}
