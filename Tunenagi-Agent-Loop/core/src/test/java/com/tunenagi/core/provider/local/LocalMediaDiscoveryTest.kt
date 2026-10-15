package com.tunenagi.core.provider.local

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.PlaylistEntryEntity
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.SourceAvailability
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Local discovery, indexing and grant loss, on the JVM.
 *
 * What is real here: the queries the production code builds (uri, projection, selection), the row
 * mapping over real `Cursor` objects, and a real Room database with the real unique index and foreign
 * keys. What is *supplied* is only the provider's answer — a `Cursor` the test writes — because this
 * project has no Android runtime (ADR-008). Nothing the assertions depend on is mocked away: the
 * revocation tests fail if `SecurityException` stops being read as a revoked grant, and the
 * incremental tests fail if a second scan writes anything at all.
 */
@RunWith(RobolectricTestRunner::class)
class LocalMediaDiscoveryTest {

    private val now: Instant = Instant.parse("2026-10-15T00:00:00Z")
    private val device = MediaStoreAudioDiscovery.MEDIASTORE_VARIANT
    private val folder: String = SafTreeAudioDiscovery.variantFor(
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    )

    private val treeUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    )

    // ---- MediaStore discovery ------------------------------------------------------------------

    private class RecordingRows(
        private val cursor: Cursor? = null,
        private val failure: RuntimeException? = null
    ) : AudioRowSource {
        var uri: Uri? = null
        var projection: Array<String>? = null
        var selection: String? = null

        override suspend fun <T> read(
            uri: Uri,
            projection: Array<String>,
            selection: String?,
            args: Array<String>?,
            sort: String?,
            block: (Cursor) -> T
        ): T? {
            this.uri = uri
            this.projection = projection
            this.selection = selection
            failure?.let { throw it }
            // Consumption is the production contract: the fake hands the query's result to the
            // caller the same way the resolver-backed implementation does.
            return cursor?.let(block)
        }
    }

    private fun mediaRows(vararg rows: Array<Any?>): MatrixCursor {
        val cursor = MatrixCursor(MediaStoreAudioDiscovery.PROJECTION)
        rows.forEach { cursor.addRow(it) }
        return cursor
    }

    private fun mediaRow(
        id: Long = 7L,
        title: String? = "Slow Tide",
        displayName: String? = "slow-tide.mp3",
        artist: String? = "Kaori Ito",
        albumId: Long = 3L,
        duration: Long = 190_000L,
        mime: String? = "audio/mpeg"
    ): Array<Any?> = arrayOf(id, title, displayName, artist, albumId, duration, mime)

    @Test
    fun mediaStoreRowsBecomeAudioWithTheMetadataAndArtworkTheProviderOffers() = runBlocking {
        val rows = RecordingRows(mediaRows(mediaRow()))

        val result = MediaStoreAudioDiscovery(rows).discover()

        val found = result as LocalDiscoveryResult.Found
        assertEquals(1, found.rowsRead)
        assertTrue(found.skipped.isEmpty())
        val audio = found.entries.single()
        assertEquals("7", audio.externalId)
        assertEquals("content://media/external/audio/media/7", audio.uri)
        assertEquals("Slow Tide", audio.title)
        assertEquals("Kaori Ito", audio.artist)
        assertEquals(190_000L, audio.durationMs)
        // Derived from the album id rather than copied from the row: a re-scan cannot bake in a
        // stale artwork id.
        assertEquals("content://media/external/audio/albumart/3", audio.artworkUri)

        // The query is part of the contract: the audio collection also holds ringtones and voice
        // recordings, and IS_MUSIC is what keeps those out of the library.
        assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rows.uri)
        assertEquals(MediaStore.Audio.Media.IS_MUSIC + " != 0", rows.selection)
        assertTrue(
            "the projection must stay bounded to what the mapping reads",
            rows.projection!!.toList() == MediaStoreAudioDiscovery.PROJECTION.toList()
        )
    }

    @Test
    fun rowsThatAreNotAudioAndRowsWithNoNameAtAllAreSkippedWithAReason() = runBlocking {
        val rows = RecordingRows(
            mediaRows(
                mediaRow(id = 1L, mime = "image/jpeg", displayName = "cover.jpg"),
                // No tag title: the file name is the honest fallback, not a placeholder.
                mediaRow(id = 2L, title = null, displayName = "field-recording.flac"),
                // Nothing a user could be shown: skipped, and said so.
                mediaRow(id = 3L, title = null, displayName = null, mime = "audio/mpeg")
            )
        )

        val found = MediaStoreAudioDiscovery(rows).discover() as LocalDiscoveryResult.Found

        assertEquals(1, found.entries.size)
        assertEquals("field-recording", found.entries.single().title)
        assertEquals(3, found.rowsRead)
        assertEquals(listOf("not-audio", "untitled"), found.skipped.map { it.reason })
    }

    @Test
    fun aDurationTheProviderCannotReportStaysNullRatherThanZero() = runBlocking {
        val rows = RecordingRows(mediaRows(mediaRow(duration = 0L)))

        val found = MediaStoreAudioDiscovery(rows).discover() as LocalDiscoveryResult.Found

        assertNull("0 would claim a zero-length track", found.entries.single().durationMs)
    }

    @Test
    fun aRevokedMediaPermissionIsReportedAsPermissionRequired() = runBlocking {
        val rows = RecordingRows(failure = SecurityException("READ_MEDIA_AUDIO denied"))

        val result = MediaStoreAudioDiscovery(rows).discover()

        val denied = result as LocalDiscoveryResult.PermissionRequired
        assertTrue(denied.reason.contains("READ_MEDIA_AUDIO"))
    }

    @Test
    fun aProviderThatFailsIsAFailureNotAnEmptyLibrary() = runBlocking {
        val rows = RecordingRows(failure = IllegalStateException("media provider died"))

        val result = MediaStoreAudioDiscovery(rows).discover()

        assertTrue(
            "an empty library and a broken provider must not look the same",
            result is LocalDiscoveryResult.Failed
        )
    }

    @Test
    fun aProviderThatReturnsNoCursorIsAlsoAFailure() = runBlocking {
        val result = MediaStoreAudioDiscovery(RecordingRows(cursor = null)).discover()

        assertTrue(result is LocalDiscoveryResult.Failed)
    }

    // ---- SAF discovery -------------------------------------------------------------------------

    private fun documentRows(vararg rows: Array<Any?>): MatrixCursor {
        val cursor = MatrixCursor(SafTreeAudioDiscovery.PROJECTION)
        rows.forEach { cursor.addRow(it) }
        return cursor
    }

    private fun documentRow(
        id: String? = "primary:Music/slow-tide.mp3",
        name: String? = "slow-tide.mp3",
        mime: String? = "audio/mpeg"
    ): Array<Any?> = arrayOf(id, name, mime)

    @Test
    fun aFolderGrantIsReadThroughItsChildrenUriAndNonAudioIsSkipped() = runBlocking {
        val rows = RecordingRows(
            documentRows(
                documentRow(
                    id = "primary:Music/album",
                    name = "album",
                    mime = DocumentsContract.Document.MIME_TYPE_DIR
                ),
                documentRow(id = "primary:Music/notes.txt", name = "notes.txt", mime = "text/plain"),
                documentRow()
            )
        )

        val found = SafTreeAudioDiscovery(rows, treeUri).discover() as LocalDiscoveryResult.Found

        assertEquals(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            ),
            rows.uri
        )
        val audio = found.entries.single()
        assertEquals("primary:Music/slow-tide.mp3", audio.externalId)
        assertEquals("slow-tide", audio.title)
        // SAF reports no artist, no duration and no artwork: those belong to the resolver, and a
        // guess here would be a lie the user cannot see through.
        assertNull(audio.artist)
        assertNull(audio.durationMs)
        assertNull(audio.artworkUri)
        assertEquals(listOf("directory", "not-audio"), found.skipped.map { it.reason })
        assertTrue(
            "the document uri must be built from the tree the user granted",
            audio.uri.startsWith("content://com.android.externalstorage.documents/tree/")
        )
    }

    @Test
    fun aRevokedFolderGrantIsReportedAsPermissionRequiredAndKeepsItsOwnVariant() = runBlocking {
        val rows = RecordingRows(failure = SecurityException("grant revoked"))
        val discovery = SafTreeAudioDiscovery(rows, treeUri)

        val result = discovery.discover()

        assertTrue(result is LocalDiscoveryResult.PermissionRequired)
        assertEquals("saf:primary:Music", discovery.variant)
        assertTrue(
            "a folder grant must not share a variant with the device library",
            discovery.variant != MediaStoreAudioDiscovery.MEDIASTORE_VARIANT
        )
    }

    // ---- indexing ------------------------------------------------------------------------------

    /**
     * The clock is a mutable field rather than a constant so a test can let time pass between two
     * scans; "the update time moved" is only assertable if the second scan happens later.
     */
    private var clockNow: Instant = now

    private fun indexer(database: TunenagiDatabase) =
        LocalLibraryIndexer(
            database = database,
            clock = { clockNow },
            newId = { UUID.randomUUID().toString() }
        )

    private fun audio(
        id: String = "7",
        uri: String = "content://media/external/audio/media/7",
        title: String = "Slow Tide",
        artist: String? = "Kaori Ito",
        durationMs: Long? = 190_000L,
        artworkUri: String? = "content://media/external/audio/albumart/3"
    ) = DiscoveredAudio(
        externalId = id,
        uri = uri,
        title = title,
        artist = artist,
        durationMs = durationMs,
        mimeType = "audio/mpeg",
        artworkUri = artworkUri
    )

    private suspend fun sources(database: TunenagiDatabase, variant: String = device) =
        database.sourceDao().forProviderVariant("builtin.local", variant)

    @Test
    fun indexingAFileCreatesATrackWithItsCreditAndAnAvailableSource() = runBlocking {
        val database = Fixtures.database()

        val report = indexer(database).index(ProviderId.Local, device, listOf(audio()))

        assertEquals(1, report.added)
        assertEquals(1, report.changes)
        val source = database.sourceDao().byIdentity("builtin.local", "7", device)!!
        val track = database.trackDao().byId(source.trackId)!!
        assertEquals("Slow Tide", track.title)
        assertEquals(190_000L, track.durationMs)
        assertEquals("content://media/external/audio/albumart/3", track.artworkRef)
        assertEquals(SourceAvailability.Available.name, source.availability)
        assertEquals("slow tide", track.sortTitle)
        val credits = database.trackCreditDao().forTrack(source.trackId)
        assertEquals(1, credits.size)
        assertEquals("Kaori Ito", database.artistDao().all().single().name)
    }

    @Test
    fun scanningAnUnchangedLibraryASecondTimeWritesNothing() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(ProviderId.Local, device, listOf(audio()))
        val before = database.trackDao().byId(sources(database).single().trackId)!!

        val second = indexer.index(ProviderId.Local, device, listOf(audio()))

        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(1, second.unchanged)
        assertEquals(0, second.changes)
        val after = database.trackDao().byId(before.id)!!
        assertEquals("an unchanged row must not be rewritten", before.updatedAt, after.updatedAt)
        assertEquals(1, database.trackDao().count())
        assertEquals(1, sources(database).size)
    }

    @Test
    fun changedTagsAreAppliedAndTheUpdateTimeMovesWithThem() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(ProviderId.Local, device, listOf(audio()))
        val before = database.trackDao().byId(sources(database).single().trackId)!!
        clockNow = now.plusSeconds(60)

        val report = indexer.index(
            ProviderId.Local,
            device,
            listOf(audio(title = "Slow Tide (Live)", durationMs = 200_000L))
        )

        assertEquals(1, report.updated)
        assertEquals(0, report.added)
        val after = database.trackDao().byId(before.id)!!
        assertEquals("Slow Tide (Live)", after.title)
        assertEquals(200_000L, after.durationMs)
        assertTrue(after.updatedAt > before.updatedAt)
    }

    @Test
    fun aFileThatIsNoLongerListedIsMarkedStorageGoneAndKeepsTheTrackAndItsPlaylistEntry() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(
            ProviderId.Local,
            device,
            listOf(
                audio(id = "7"),
                audio(id = "8", uri = "content://media/external/audio/media/8", title = "Low Pressure")
            )
        )
        val vanishedTrack = database.sourceDao().byIdentity("builtin.local", "7", device)!!.trackId
        // A user has this track in a playlist: losing the file must not take the entry with it.
        val playlist = Fixtures.playlist()
        database.playlistDao().insertPlaylist(TunenagiMappers.toPlaylistEntity(playlist))
        database.playlistDao().insertEntry(
            PlaylistEntryEntity(
                id = UUID.randomUUID().toString(),
                playlistId = playlist.id.value,
                trackId = vanishedTrack,
                position = 1,
                addedAt = now.toEpochMilli(),
                sourceLockId = null
            )
        )

        val report = indexer.index(
            ProviderId.Local,
            device,
            listOf(
                audio(id = "8", uri = "content://media/external/audio/media/8", title = "Low Pressure")
            )
        )

        assertEquals(1, report.gone)
        val vanished = database.sourceDao().byIdentity("builtin.local", "7", device)!!
        assertEquals(SourceAvailability.StorageGone.name, vanished.availability)
        assertNotNull("the track must survive its file", database.trackDao().byId(vanishedTrack))
        assertEquals(
            "the playlist entry must survive the file",
            1,
            database.playlistDao().entries(playlist.id.value).size
        )
        assertFalse(
            "a track whose only file is gone must not be advertised as playable",
            disappearedSources(database).any { it.availability == SourceAvailability.Available.name }
        )
    }

    private suspend fun disappearedSources(database: TunenagiDatabase) =
        database.sourceDao().withAvailability(SourceAvailability.StorageGone.name)

    @Test
    fun aSecondScanOfTheSameGoneFileDoesNotCountItAgain() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(ProviderId.Local, device, listOf(audio()))
        indexer.index(ProviderId.Local, device, emptyList())

        val again = indexer.index(ProviderId.Local, device, emptyList())

        assertEquals("a row already known to be gone is not moved again", 0, again.gone)
    }

    @Test
    fun aFileThatComesBackIsRestoredRatherThanReimported() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(ProviderId.Local, device, listOf(audio()))
        indexer.index(ProviderId.Local, device, emptyList())

        val restored = indexer.index(ProviderId.Local, device, listOf(audio()))

        assertEquals(1, restored.restored)
        assertEquals(0, restored.added)
        assertEquals(1, database.trackDao().count())
        assertEquals(
            SourceAvailability.Available.name,
            database.sourceDao().byIdentity("builtin.local", "7", device)!!.availability
        )
    }

    @Test
    fun aRepeatedRowInsideOnePassIsCountedInsteadOfAbortingTheScan() = runBlocking {
        val database = Fixtures.database()

        val report = indexer(database).index(ProviderId.Local, device, listOf(audio(), audio()))

        assertEquals(1, report.added)
        assertEquals(1, report.duplicates)
        assertEquals(1, sources(database).size)
    }

    @Test
    fun aRevokedGrantMarksOnlyItsOwnFiles() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(ProviderId.Local, device, listOf(audio()))
        indexer.index(
            ProviderId.Local,
            folder,
            listOf(audio(id = "primary:Music/slow-tide.mp3", uri = "content://docs/document/1"))
        )

        val marked = indexer.markUnavailable(ProviderId.Local, folder, SourceAvailability.PermissionRequired)

        assertEquals(1, marked)
        assertEquals(
            "losing a folder grant must not make the device library unreadable",
            SourceAvailability.Available.name,
            database.sourceDao().byIdentity("builtin.local", "7", device)!!.availability
        )
        assertEquals(
            SourceAvailability.PermissionRequired.name,
            database.sourceDao()
                .byIdentity("builtin.local", "primary:Music/slow-tide.mp3", folder)!!
                .availability
        )
        // Marking is idempotent: a scan that keeps failing does not keep writing rows.
        assertEquals(
            0,
            indexer.markUnavailable(ProviderId.Local, folder, SourceAvailability.PermissionRequired)
        )
    }

    @Test
    fun theScannerTurnsARevokedGrantIntoPermissionRequiredAndAFailedQueryIntoNothing() = runBlocking {
        val database = Fixtures.database()
        val indexer = indexer(database)
        indexer.index(
            ProviderId.Local,
            folder,
            listOf(audio(id = "primary:Music/slow-tide.mp3", uri = "content://docs/document/1"))
        )

        val revoked = LocalLibraryScanner(
            listOf(SafTreeAudioDiscovery(RecordingRows(failure = SecurityException("grant revoked")), treeUri)),
            indexer
        ).scan().single()

        assertTrue(revoked.result is LocalDiscoveryResult.PermissionRequired)
        assertEquals(1, revoked.report!!.gone)
        assertEquals(
            SourceAvailability.PermissionRequired.name,
            sources(database, folder).single().availability
        )

        val broken = LocalLibraryScanner(
            listOf(
                SafTreeAudioDiscovery(
                    RecordingRows(failure = IllegalStateException("provider died")),
                    treeUri
                )
            ),
            indexer
        ).scan().single()

        assertTrue(broken.result is LocalDiscoveryResult.Failed)
        assertNull("a provider error is not evidence that a file is gone", broken.report)
        assertEquals(
            SourceAvailability.PermissionRequired.name,
            sources(database, folder).single().availability
        )
    }

    @Test
    fun aFullScanOfBothGrantsAddsEachFileOnce() = runBlocking {
        val database = Fixtures.database()

        val summary = LocalLibraryScanner(
            discoveries = listOf(
                SafTreeAudioDiscovery(RecordingRows(documentRows(documentRow())), treeUri),
                MediaStoreAudioDiscovery(RecordingRows(mediaRows(mediaRow())))
            ),
            indexer = indexer(database)
        ).scanSummary()

        assertEquals(2, summary.added)
        assertEquals(2, summary.discovered)
        assertEquals(2, database.trackDao().count())
        assertEquals(2, database.sourceDao().withAvailability("Available").size)
        // Two grants, two variants: the same recording found twice stays two sources.
        assertEquals(
            2,
            sources(database) .size + sources(database, folder).size
        )
    }

    @Test
    fun theTrackIdentityIsTheAppsUuidNotTheProviderRowId() = runBlocking {
        val database = Fixtures.database()
        indexer(database).index(ProviderId.Local, device, listOf(audio()))

        val source = sources(database).single()

        // SPEC/DATA_MODEL.md: the app's UUID is the identity, never the provider's id.
        assertTrue(
            "the track id must be an app UUID, not the MediaStore row id",
            runCatching { UUID.fromString(source.trackId) }.isSuccess
        )
        assertEquals("7", source.externalId)
    }
}
