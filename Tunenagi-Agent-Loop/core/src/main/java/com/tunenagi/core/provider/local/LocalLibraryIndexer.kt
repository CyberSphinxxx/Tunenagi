package com.tunenagi.core.provider.local

import androidx.room.withTransaction
import com.tunenagi.core.database.ArtistEntity
import com.tunenagi.core.database.TrackArtistEntity
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ArtistId
import com.tunenagi.core.model.ArtistRole
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackArtist
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant
import java.util.UUID

/**
 * Turning discovered files into library rows — owned.
 *
 * This is the half of local discovery that has to *survive*: `SPEC/PROVIDERS.md` requires the scan
 * to be incremental and non-destructive, and both of those are properties of the writes, not of the
 * query. So the report distinguishes what actually changed (added / updated / restored / gone) from
 * what was merely seen again, and the tests assert that a second scan of an unchanged library writes
 * nothing at all.
 *
 * Nothing here deletes. A file that is no longer listed has its source marked `StorageGone`, and a
 * grant that no longer works marks its sources `PermissionRequired`; the track, its credits, its
 * playlists and its history stay (`SPEC/DATA_MODEL.md`, and BUG-0002's rule that a source may
 * disappear without taking the track with it).
 */
data class LocalIndexReport(
    val added: Int = 0,
    val updated: Int = 0,
    val restored: Int = 0,
    val unchanged: Int = 0,
    val gone: Int = 0,
    val duplicates: Int = 0,
    val skipped: List<SkippedRow> = emptyList()
) {
    /** How many rows a rescan actually touched; the honest measure of an incremental scan. */
    val changes: Int get() = added + updated + restored + gone

    /** How many discovered files this scan accounts for. */
    val discovered: Int get() = added + updated + restored + unchanged

    fun plus(other: LocalIndexReport): LocalIndexReport = LocalIndexReport(
        added = added + other.added,
        updated = updated + other.updated,
        restored = restored + other.restored,
        unchanged = unchanged + other.unchanged,
        gone = gone + other.gone,
        duplicates = duplicates + other.duplicates,
        skipped = skipped + other.skipped
    )

    companion object {
        val Empty = LocalIndexReport()
    }
}

/**
 * Applies discovered files to the library.
 *
 * The identity used for the lookup is the one the database enforces —
 * `(providerId, externalId, sourceVariant)` — so re-scanning cannot import the same file twice: the
 * variant is the grant, which is what keeps a MediaStore row and a SAF document for the same file
 * from colliding, and keeps two folders apart.
 */
class LocalLibraryIndexer(
    private val database: TunenagiDatabase,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * Indexes one grant's worth of files.
     *
     * A duplicate external id inside a single pass is counted, not inserted twice: the unique index
     * would reject the second insert and abort the whole transaction, and losing a whole scan
     * because a provider repeated a row is worse than reporting it.
     */
    suspend fun index(
        providerId: ProviderId,
        variant: String,
        entries: List<DiscoveredAudio>,
        skipped: List<SkippedRow> = emptyList()
    ): LocalIndexReport = database.withTransaction {
        val now = clock()
        var added = 0
        var updated = 0
        var restored = 0
        var unchanged = 0
        var duplicates = 0
        val discoveredIds = mutableSetOf<String>()

        for (entry in entries) {
            if (!discoveredIds.add(entry.externalId)) {
                duplicates++
                continue
            }
            val stored = database.sourceDao().byIdentity(providerId.value, entry.externalId, variant)
            if (stored == null) {
                insert(entry, providerId, variant, now)
                added++
            } else {
                when (refresh(stored.id, stored.trackId, entry, now)) {
                    Change.Updated -> updated++
                    Change.Restored -> restored++
                    Change.Unchanged -> unchanged++
                }
            }
        }

        val gone = markMissing(
            providerId = providerId,
            variant = variant,
            discoveredIds = discoveredIds,
            now = now
        )

        LocalIndexReport(
            added = added,
            updated = updated,
            restored = restored,
            unchanged = unchanged,
            gone = gone,
            duplicates = duplicates,
            skipped = skipped
        )
    }

    /**
     * Marks everything one grant owns as unreadable, for the case where the grant itself failed.
     *
     * `PermissionRequired` is deliberately not `Unavailable`: the files are still on the device and
     * a re-grant restores them, which is the difference the UI has to show. Rows already in that
     * state are not written again, so a scan that keeps failing does not keep churning them.
     */
    suspend fun markUnavailable(
        providerId: ProviderId,
        variant: String,
        availability: SourceAvailability
    ): Int = database.withTransaction {
        val now = clock()
        database.sourceDao()
            .forProviderVariant(providerId.value, variant)
            .count { stored ->
                stored.availability != availability.name &&
                    database.sourceDao()
                        .updateAvailability(stored.id, availability.name, now.toEpochMilli()) > 0
            }
    }

    private enum class Change { Updated, Restored, Unchanged }

    private suspend fun insert(
        entry: DiscoveredAudio,
        providerId: ProviderId,
        variant: String,
        now: Instant
    ) {
        val trackId = TrackId.parse(newId())
        val track = Track(
            id = trackId,
            title = entry.title,
            durationMs = entry.durationMs,
            artworkRef = entry.artworkUri
        )
        database.trackDao().insert(TunenagiMappers.toTrackEntity(track, now, now))
        entry.artist?.let { credit(it, trackId, now) }
        database.sourceDao().insert(
            TunenagiMappers.toTrackSourceEntity(
                TrackSource(
                    id = TrackSourceId.parse(newId()),
                    trackId = trackId,
                    providerId = providerId,
                    externalId = entry.externalId,
                    localUri = entry.uri,
                    sourceVariant = variant,
                    contentType = entry.mimeType,
                    availability = SourceAvailability.Available,
                    lastCheckedAt = now
                )
            )
        )
    }

    /**
     * Credits the artist named by the file's tags.
     *
     * Matching is by exact name, and a missing row is created. Fuzzy matching belongs to the
     * matching task, where a wrong merge is a decision the user can undo; guessing here would create
     * the duplicate artists that matching then has to clean up.
     */
    private suspend fun credit(name: String, trackId: TrackId, now: Instant) {
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
                    order = 0,
                    role = ArtistRole.Primary
                )
            )
        )
    }

    private suspend fun refresh(
        sourceId: String,
        trackId: String,
        entry: DiscoveredAudio,
        now: Instant
    ): Change {
        val storedTrack = database.trackDao().byId(trackId)
        val storedSource = database.sourceDao().byId(sourceId)
        if (storedTrack == null || storedSource == null) return Change.Unchanged

        val wasPlayable = storedSource.availability == SourceAvailability.Available.name
        val metadataChanged = storedTrack.title != entry.title ||
            storedTrack.durationMs != entry.durationMs ||
            storedTrack.artworkRef != entry.artworkUri
        val locationChanged = storedSource.localUri != entry.uri ||
            storedSource.contentType != entry.mimeType

        if (metadataChanged) {
            database.trackDao().applyScannedMetadata(
                id = trackId,
                title = entry.title,
                sortTitle = Track(id = TrackId.parse(trackId), title = entry.title).sortTitle,
                durationMs = entry.durationMs,
                artworkRef = entry.artworkUri,
                updatedAt = now.toEpochMilli()
            )
        }
        if (locationChanged) {
            database.sourceDao().updateLocation(sourceId, entry.uri, entry.mimeType)
        }
        if (!wasPlayable) {
            database.sourceDao()
                .updateAvailability(sourceId, SourceAvailability.Available.name, now.toEpochMilli())
            return Change.Restored
        }
        if (metadataChanged || locationChanged) {
            database.sourceDao()
                .updateAvailability(sourceId, SourceAvailability.Available.name, now.toEpochMilli())
            return Change.Updated
        }
        // Seen again, exactly as it was: no write, so `updatedAt` on the track stays meaning "the
        // last time something about it actually changed".
        return Change.Unchanged
    }

    /**
     * The files this grant used to offer and no longer does.
     *
     * Only sources that were `Available` are moved: a row already marked gone staying gone is what
     * keeps the count honest on a second scan, and it means an unreadable SD card never inflates the
     * report.
     */
    private suspend fun markMissing(
        providerId: ProviderId,
        variant: String,
        discoveredIds: Set<String>,
        now: Instant
    ): Int {
        val sources = database.sourceDao().forProviderVariant(providerId.value, variant)
        return sources.count { stored ->
            stored.externalId !in discoveredIds &&
                stored.availability == SourceAvailability.Available.name &&
                database.sourceDao()
                    .updateAvailability(stored.id, SourceAvailability.StorageGone.name, now.toEpochMilli()) > 0
        }
    }
}

/** One grant's scan, with what discovery saw and what the library did about it. */
data class LocalScanOutcome(
    val label: String,
    val providerId: ProviderId,
    val variant: String,
    val result: LocalDiscoveryResult,
    val report: LocalIndexReport?
)

/**
 * Runs every grant's discovery, then applies the outcomes.
 *
 * The mapping from an outcome to a library change lives here rather than in each caller, because it
 * is a rule: a revoked grant marks its own files `PermissionRequired` (and only its own — losing a
 * folder grant must not mark the device library unreadable), while a query that merely *failed*
 * changes nothing, since a provider error is not evidence that a file is gone.
 */
class LocalLibraryScanner(
    private val discoveries: List<LocalAudioDiscovery>,
    private val indexer: LocalLibraryIndexer
) {

    suspend fun scan(): List<LocalScanOutcome> = discoveries.map { discovery ->
        val result = discovery.discover()
        LocalScanOutcome(
            label = discovery.label,
            providerId = discovery.providerId,
            variant = discovery.variant,
            result = result,
            report = when (result) {
                is LocalDiscoveryResult.Found -> indexer.index(
                    providerId = discovery.providerId,
                    variant = discovery.variant,
                    entries = result.entries,
                    skipped = result.skipped
                )

                is LocalDiscoveryResult.PermissionRequired -> LocalIndexReport(
                    gone = indexer.markUnavailable(
                        providerId = discovery.providerId,
                        variant = discovery.variant,
                        availability = SourceAvailability.PermissionRequired
                    )
                )

                is LocalDiscoveryResult.Failed -> null
            }
        )
    }

    /** The combined report of one [scan], for a caller that only shows a summary. */
    suspend fun scanSummary(): LocalIndexReport =
        scan().mapNotNull { it.report }.fold(LocalIndexReport.Empty) { total, report -> total.plus(report) }
}
