package com.tunenagi.core.provider.audius

import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.library.RemoteLibraryWriter
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.RemoteCatalog
import com.tunenagi.core.network.HttpTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The Audius adapter's wiring — owned (TUN-0305).
 *
 * It contributes four bindings and invents none of them: the documented [AudiusApi], the stream
 * resolver the session resolves through (the same contribution [com.tunenagi.core.provider.local]
 * makes for local files), the neutral [RemoteCatalog] discovery reads, and the library writer both
 * of them share. The app injects the domain interfaces and never names `core.provider` (B7).
 *
 * The single-stream budget note: discovery reads go through the API's own bounded `STANDARD` retry
 * policy, and the resolver stays deliberately single-attempt, exactly as those two classes were
 * built (TUN-0303/TUN-0304). This module adds no third policy.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudiusProviderModule {

    /**
     * The client identity the documented API expects. It names the product, not a device: two
     * installs identify the same way, which is what a public, key-free `app_name` is for (ADR-017).
     */
    const val APP_NAME = "Tunenagi"

    @Provides
    @Singleton
    fun audiusApi(transport: HttpTransport): AudiusApi =
        AudiusApi(transport = transport, appName = APP_NAME)

    @Provides
    @IntoSet
    @Singleton
    fun audiusStreamResolver(transport: HttpTransport): PlaybackSourceResolver =
        AudiusStreamResolver(transport = transport, appName = APP_NAME)

    @Provides
    @Singleton
    fun remoteLibraryWriter(database: TunenagiDatabase): RemoteLibraryWriter =
        RemoteLibraryWriter(database = database)

    @Provides
    @Singleton
    fun remoteCatalog(api: AudiusApi, writer: RemoteLibraryWriter): RemoteCatalog =
        AudiusRemoteCatalog(api = api, writer = writer)
}
