package com.tunenagi.core.provider.local

import android.content.Context
import com.tunenagi.core.model.PlaybackSourceResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The local adapter's wiring — owned.
 *
 * The adapter declares itself here instead of the app listing adapters it happens to know about:
 * the provider layer already depends on the platform (that is what it is for, rule B11), so it is
 * the honest owner of "how do I read a local file", and adding a provider later does not mean
 * editing the app's graph. `core.playback` consumes the result through
 * [PlaybackSourceResolver] — the domain interface — and never names a provider.
 *
 * The diagnostic sink is a singleton because a window that resets per resolve would only ever hold
 * one entry; and it is in memory only, because `SPEC/PROVIDERS.md` allows local diagnostic events
 * and forbids telemetry uploads.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalProviderModule {

    @Provides
    @Singleton
    fun recentResolveDiagnostics(): RecentResolveDiagnostics = RecentResolveDiagnostics()

    @Provides
    @IntoSet
    @Singleton
    fun localSourceResolver(
        @ApplicationContext context: Context,
        diagnostics: RecentResolveDiagnostics
    ): PlaybackSourceResolver = LocalSourceResolver(
        heads = ContentResolverFileHeadReader(context.contentResolver),
        diagnostics = diagnostics
    )
}
