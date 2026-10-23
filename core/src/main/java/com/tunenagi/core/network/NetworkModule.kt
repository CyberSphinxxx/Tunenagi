package com.tunenagi.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The shared HTTP gateway's wiring — owned (TUN-0305).
 *
 * Every provider reads through one transport and therefore one identity, timeout and body budget:
 * a second instance would mean two different policies answering for what the app calls "the
 * network", and TUN-0302's attribution promises would be qualified by whichever happened to serve
 * a request. The identifier names the product and says it is the Android client, which is what
 * `SPEC/PROVIDERS.md` requires of a User-Agent.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun httpTransport(): HttpTransport = OkHttpTransport(
        HttpTransportConfig(userAgent = "Tunenagi/1.0 (Android)")
    )
}
