package com.tunenagi.core.provider.local

import android.net.Uri
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.model.LocalFolderImporter
import com.tunenagi.core.model.LocalImportResult
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.TrackId
import java.time.Instant

/**
 * The user's folder, from grant to library rows — owned (TUN-0202's missing half).
 *
 * TUN-0202 delivered discovery and indexing as capabilities that nothing in the app could reach:
 * there was no grant request, no scan and therefore no queue a user could start. This is the
 * smallest honest bridge. It does not invent a second scanner and it does not write the library
 * itself - it composes [SafTreeAudioDiscovery] with [LocalLibraryIndexer], which is exactly the pair
 * the tests of both already exercise.
 *
 * Two things it deliberately does not do:
 *
 * - **It does not start playback.** `core.provider` may not import `core.playback` (B11), and the
 *   player belongs to the session. The caller gets the track ids and commands the session.
 * - **It does not scan MediaStore.** A folder the user picked is a grant this app can persist and
 *   explain; the device-wide library needs a runtime permission request that belongs with the
 *   Library screen (phase 04), not inside an import button that would then have to explain two
 *   different consent flows.
 */
class LocalFolderImport(
    private val database: TunenagiDatabase,
    private val rows: AudioRowSource,
    private val clock: () -> Instant = Instant::now
) : LocalFolderImporter {

    override suspend fun importFolder(treeUri: String): LocalImportResult {
        val tree = runCatching { Uri.parse(treeUri) }
            .getOrElse { return LocalImportResult.Failed(UNREADABLE_GRANT) }
        val discovery = SafTreeAudioDiscovery(rows = rows, treeUri = tree)
        return when (val discovered = discovery.discover()) {
            is LocalDiscoveryResult.PermissionRequired -> LocalImportResult.PermissionRequired
            is LocalDiscoveryResult.Failed -> LocalImportResult.Failed(discovered.reason)
            is LocalDiscoveryResult.Found -> {
                val report = LocalLibraryIndexer(database = database, clock = clock)
                    .index(
                        providerId = discovery.providerId,
                        variant = discovery.variant,
                        entries = discovered.entries,
                        skipped = discovered.skipped
                    )
                LocalImportResult.Imported(
                    trackIds = playableTrackIds(discovery.variant, discovered.entries),
                    added = report.added,
                    updated = report.updated,
                    restored = report.restored,
                    unchanged = report.unchanged,
                    gone = report.gone,
                    duplicates = report.duplicates
                )
            }
        }
    }

    /**
     * The tracks a queue should be built from, in the order the folder listed them.
     *
     * Resolved back through the source identity rather than trusting the insert path: the indexer
     * may have matched an existing source (the same file already imported through another scan),
     * and that source's track is the one the library already has. Only sources that are actually
     * available are returned, so a folder that has just been emptied does not offer tracks that
     * cannot play.
     */
    private suspend fun playableTrackIds(
        variant: String,
        entries: List<DiscoveredAudio>
    ): List<TrackId> {
        val sourceDao = database.sourceDao()
        return entries
            .mapNotNull { entry ->
                sourceDao.byIdentity(ProviderId.Local.value, entry.externalId, variant)
                    ?.takeIf { it.availability == SourceAvailability.Available.name }
                    ?.let { TrackId.parse(it.trackId) }
            }
            .distinct()
    }

    private companion object {
        const val UNREADABLE_GRANT = "that folder grant could not be read"
    }
}
