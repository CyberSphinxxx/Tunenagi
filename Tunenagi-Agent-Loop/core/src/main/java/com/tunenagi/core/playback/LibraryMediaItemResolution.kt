package com.tunenagi.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.database.TrackSourceEntity
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The media ids this app's session understands — owned.
 *
 * A media id is the only thing that survives the process boundary between a controller and the
 * session (`SPEC/ARCHITECTURE.md`: only the id and the metadata cross it), so it *is* the durable
 * handle for "play this": a source id, not a uri. A uri would be stale by the next launch (a
 * resolved stream expires and a content uri needs a grant), which is exactly why
 * `SPEC/DATA_MODEL.md` refuses to treat one as identity.
 *
 * The prefix is not decoration. It makes a media id self-describing, so an id of the wrong kind (a
 * raw uuid, an id from a future browsable tree, or a client's typo) is rejected as *not a source
 * id* instead of being looked up and failing somewhere less clear. It also gives later phases
 * somewhere to add other kinds of id without a format change.
 */
object MediaItemIds {

    /** Prefixed form of a source id, as it appears in [MediaItem.mediaId]. */
    const val SOURCE_PREFIX = "tunenagi:source:"

    /** The media id that plays one library source. */
    fun source(id: TrackSourceId): String = SOURCE_PREFIX + id.value

    /** The source id an item names, or `null` when the id is not one this app issued. */
    fun sourceIdOrNull(mediaId: String?): TrackSourceId? {
        if (mediaId == null || !mediaId.startsWith(SOURCE_PREFIX)) return null
        val raw = mediaId.removePrefix(SOURCE_PREFIX)
        // A parsing failure is a rejection, not a crash: this value arrives from another process.
        return runCatching { TrackSourceId.parse(raw) }.getOrNull()
    }
}

/**
 * Resolves a requested item out of the on-device library — owned.
 *
 * This is the object that makes local playback real: the session hands it an item whose media id is
 * a [TrackSourceId], and it finds the track and source in Room, asks the resolver registered for
 * that source's provider for a fresh [com.tunenagi.core.model.PlayableReference], and returns an
 * item the player can open — or a typed failure that says which part was missing.
 *
 * Three choices are worth naming:
 *
 * - **The library is the authority, not the caller.** Only the media id and whatever metadata the
 *   controller chose to send can cross the process boundary, so the title, duration and artwork are
 *   read from the database rather than trusted from the item.
 * - **A resolve is also a check.** `SPEC/PROVIDERS.md` wants availability to reflect what actually
 *   happened. When a resolve finds a file gone or a grant revoked, the source's availability is
 *   written back (and a working file that had been marked gone is marked available again) so the
 *   library reflects reality after one playback attempt instead of only after a rescan. Nothing is
 *   ever deleted: a source that cannot be read keeps its track, credits, playlists and history.
 * - **A learned format is kept.** The resolver reads the container from the file's own bytes; when
 *   that disagrees with what the scan recorded, the sniffed value wins and is stored, because it is
 *   the one piece of evidence either way.
 *
 * The work runs on the injected [scope] and the result is a [ListenableFuture] because Media3's
 * callback is asynchronous and the session's thread must never wait on a database or a provider.
 */
class LibraryMediaItemResolution(
    private val database: TunenagiDatabase,
    private val resolvers: List<PlaybackSourceResolver>,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now
) : MediaItemResolution {

    override fun resolve(requested: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            try {
                future.set(requested.map { one(it) })
            } catch (failure: Throwable) {
                // One failed item fails the whole request, which is Media3's contract for
                // `onAddMediaItems` and the safer behaviour: a half-resolved queue would play part
                // of what the user asked for and leave a broken entry to surface mid-playback.
                future.setException(failure)
            }
        }
        return future
    }

    private suspend fun one(requested: MediaItem): MediaItem {
        // An item that already carries a playable uri needs no lookup: that is how a file the user
        // picked plays, and it keeps the pure pass-through case free of a database read.
        if (requested.localConfiguration?.uri != null) return requested

        val sourceId = MediaItemIds.sourceIdOrNull(requested.mediaId)
            ?: throw UnresolvedMediaItemException(requested.mediaId, NOT_A_SOURCE_ID)
        val stored = database.sourceDao().byId(sourceId.value)
            ?: throw UnresolvedMediaItemException(requested.mediaId, UNKNOWN_SOURCE)
        val storedTrack = database.trackDao().byId(stored.trackId)
            ?: throw UnresolvedMediaItemException(requested.mediaId, MISSING_TRACK)
        val resolver = resolvers.firstOrNull { it.providerId.value == stored.providerId }
            ?: throw UnresolvedMediaItemException(
                requested.mediaId,
                "no resolver is installed for provider '${stored.providerId}'"
            )

        val track = TunenagiMappers.toTrack(storedTrack)
        val source = TunenagiMappers.toTrackSource(stored)
        return when (val result = resolver.resolve(track, source)) {
            is ResolveResult.Resolved -> {
                learnPlayable(stored, result.reference.mimeType)
                playableItem(requested, result.reference.uri, result.reference.mimeType, track)
            }

            is ResolveResult.NeedsUserAction -> {
                learnAvailability(stored, result.availability)
                throw UnresolvedMediaItemException(
                    mediaId = requested.mediaId,
                    availability = result.availability
                )
            }

            is ResolveResult.Failed -> throw UnresolvedMediaItemException(
                mediaId = requested.mediaId,
                error = result.error
            )
        }
    }

    /** Records what a successful resolve proved: the file is readable, and this is the container. */
    private suspend fun learnPlayable(stored: TrackSourceEntity, mimeType: String?) {
        val uri = stored.localUri
        if (uri != null && mimeType != null && mimeType != stored.contentType) {
            database.sourceDao().updateLocation(stored.id, uri, mimeType)
        }
        if (stored.availability != SourceAvailability.Available.name) {
            database.sourceDao().updateAvailability(
                stored.id,
                SourceAvailability.Available.name,
                clock().toEpochMilli()
            )
        }
    }

    /**
     * Records a state the user has to change.
     *
     * A source already in that state is not written again — the same rule the indexer follows, so a
     * track that keeps failing to resolve does not churn a row on every attempt.
     */
    private suspend fun learnAvailability(stored: TrackSourceEntity, availability: SourceAvailability) {
        if (stored.availability == availability.name) return
        database.sourceDao().updateAvailability(stored.id, availability.name, clock().toEpochMilli())
    }

    /**
     * Builds the item the player will hold.
     *
     * The requested item is the base, so a controller that set its own metadata keeps it; the
     * library fills in a title only when the caller did not supply one. The artwork is only a *uri*
     * here — loading, downsampling and caching it is TUN-0505's task, and a decode in this path
     * would put an unbounded image read on the resolve critical path.
     */
    private fun playableItem(
        requested: MediaItem,
        uri: String,
        mimeType: String?,
        track: Track
    ): MediaItem {
        val builder = requested.buildUpon()
            .setUri(uri)
            .setMimeType(mimeType)
        if (requested.mediaMetadata.title == null) {
            builder.setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setIsPlayable(true)
                    .setArtworkUri(track.artworkRef?.let(Uri::parse))
                    .build()
            )
        }
        return builder.build()
    }

    private companion object {
        const val NOT_A_SOURCE_ID = "the media id is not a Tunenagi source id"
        const val UNKNOWN_SOURCE = "this source is not in the library"
        const val MISSING_TRACK = "the source's track is not in the library"
    }
}
