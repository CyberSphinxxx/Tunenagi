package com.tunenagi.core.database

import androidx.room.Room
import com.tunenagi.core.model.Album
import com.tunenagi.core.model.AlbumId
import com.tunenagi.core.model.Artist
import com.tunenagi.core.model.ArtistId
import com.tunenagi.core.model.ArtistRole
import com.tunenagi.core.model.Playlist
import com.tunenagi.core.model.PlaylistEntry
import com.tunenagi.core.model.PlaylistEntryId
import com.tunenagi.core.model.PlaylistId
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackArtist
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import org.robolectric.RuntimeEnvironment

/**
 * Test fixtures — owned test code.
 *
 * The database under test is a real Room database with real SQLite, created in memory so each test
 * starts from a known state and no file survives the run. Nothing about the schema is stubbed: the
 * foreign keys, unique indices and transactions asserted below are the ones the app will use.
 */
internal object Fixtures {

    val now: Instant = Instant.parse("2026-10-14T00:00:00Z")

    fun database(): TunenagiDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TunenagiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun album(id: AlbumId = AlbumId.random(), title: String = "Dawn") =
        Album(id = id, title = title, year = 2026)

    fun artist(id: ArtistId = ArtistId.random(), name: String = "Kaori Ito") =
        Artist(id = id, name = name)

    fun track(id: TrackId = TrackId.random(), title: String = "The Slow Tide", albumId: AlbumId? = null) =
        Track(id = id, title = title, durationMs = 190_000, albumId = albumId)

    fun source(
        trackId: TrackId,
        providerId: ProviderId = ProviderId.Audius,
        externalId: String = "D7KyD",
        variant: String? = null,
        availability: SourceAvailability = SourceAvailability.Available
    ) = TrackSource(
        id = TrackSourceId.random(),
        trackId = trackId,
        providerId = providerId,
        externalId = externalId,
        localUri = if (providerId == ProviderId.Local) "content://media/external/audio/1" else null,
        sourceVariant = variant,
        contentType = "audio/mpeg",
        availability = availability
    )

    fun playlist(id: PlaylistId = PlaylistId.random(), name: String = "Favorites Mix", isSystem: Boolean = false) =
        Playlist(id = id, name = name, createdAt = now, updatedAt = now, isSystem = isSystem)

    fun entry(
        playlistId: PlaylistId,
        trackId: TrackId,
        position: Int,
        addedAt: Instant = now
    ) = PlaylistEntry(
        id = PlaylistEntryId.random(),
        playlistId = playlistId,
        trackId = trackId,
        position = position,
        addedAt = addedAt
    )

    fun credit(trackId: TrackId, artistId: ArtistId, order: Int = 0, role: ArtistRole = ArtistRole.Primary) =
        TrackArtist(trackId = trackId, artistId = artistId, order = order, role = role)
}
