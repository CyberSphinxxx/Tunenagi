package com.tunenagi.core.library

import androidx.room.withTransaction
import com.tunenagi.core.database.ArtistEntity
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ArtistId
import com.tunenagi.core.model.ArtistRole
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackArtist
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import java.util.UUID

/**
 * Putting provider rows into the library — owned (TUN-0305).
 *
 * Tapping a remote search result must work like importing a local folder does: the row becomes a
 * canonical track plus one source, and playing it hands the session an id the resolver turns into a
 * stream at play time. Nothing about the resolved URL is stored here — persisting a stream URL
 * would be exactly the "temporary stream URL mixed into canonical DB identity" the phase's common
 * traps warn about.
 *
 * Writes follow the same rules the local indexer established:
 *
 * - **Identity, not insertion order.** The lookup is the database's own unique identity
 *   `(providerId, externalId, sourceVariant)`, so re-searching the same track appends nothing
 *   twice and a second tap re-resolves the *same* library track.
 * - **A second look only refreshes what it is evidence for.** Re-encountering a row updates the
 *   availability claim the provider makes right now; the user's credits, album link and manual
 *   choices are not overwritten by a page of search results.
 * - **Nothing is deleted.** A row a later search no longer returns stays exactly as it is.
 */
class RemoteLibraryWriter(
    private val database: TunenagiDatabase,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * One `TrackId` per [items] entry, in order, inside one transaction: a batch is either in the
     * library or not, so a screen never queues a half-applied selection.
     */
    suspend fun persist(items: List<ExternalTrack>): List<TrackId> = database.withTransaction {
        val now = clock()
        items.map { item -> persistOne(item, now) }
    }

    private suspend fun persistOne(item: ExternalTrack, now: Instant): TrackId {
        require(item.title.isNotBlank()) { "A row with no title cannot be persisted" }
        val stored = database.sourceDao()
            .byIdentity(item.providerId.value, item.externalId, STREAM_VARIANT)
        if (stored != null) {
            // A fresh row is evidence only for the provider's current entitlement claim. Writing a
            // duplicate track, or re-crediting artists, would churn the library on every tap.
            if (stored.availability != item.availability.name) {
                database.sourceDao()
                    .updateAvailability(stored.id, item.availability.name, now.toEpochMilli())
            }
            return TrackId.parse(stored.trackId)
        }

        val trackId = TrackId.parse(newId())
        database.trackDao().insert(
            TunenagiMappers.toTrackEntity(
                Track(
                    id = trackId,
                    title = item.title,
                    durationMs = item.durationMs,
                    artworkRef = item.artworkUrl
                ),
                now,
                now
            )
        )
        item.artists.forEachIndexed { order, name -> credit(name, order, trackId) }
        database.sourceDao().insert(
            TunenagiMappers.toTrackSourceEntity(
                TrackSource(
                    id = TrackSourceId.parse(newId()),
                    trackId = trackId,
                    providerId = item.providerId,
                    externalId = item.externalId,
                    sourceVariant = STREAM_VARIANT,
                    availability = item.availability,
                    lastCheckedAt = now
                )
            )
        )
        return trackId
    }

    /**
     * Credits the provider's own attribution, in the order the row listed it. Naming is exact, as
     * the local indexer's comment explains: `@handle` and a display name are different identities,
     * and silently merging them is a wrong merge matching would have to undo.
     */
    private suspend fun credit(name: String, order: Int, trackId: TrackId) {
        val artists = database.artistDao()
        val existing = artists.all().firstOrNull { it.name.equals(name, ignoreCase = true) }
        val artistId = existing?.id ?: newId().also { id ->
            artists.insert(ArtistEntity(id = id, name = name, sortName = name, mbid = null))
        }
        database.trackCreditDao().insert(
            TunenagiMappers.toTrackArtistEntity(
                TrackArtist(
                    trackId = trackId,
                    artistId = ArtistId.parse(artistId),
                    order = order,
                    role = ArtistRole.Primary
                )
            )
        )
    }

    companion object {
        /**
         * Every remote source this layer writes is addressed by the provider's own id. Storing a
         * URL here would let an expiring stream become the durable identity of a track, which
         * `SPEC/DATA_MODEL.md` forbids.
         */
        const val STREAM_VARIANT = "stream"
    }
}
