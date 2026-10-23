package com.tunenagi.core.provider.local

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.tunenagi.core.database.Fixtures
import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.model.LocalImportResult
import com.tunenagi.core.model.SourceAvailability
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bridge from a folder grant to queueable tracks — TUN-0202's missing half.
 *
 * The provider is the only thing faked: the indexer, the database, the identity lookup and the
 * incremental behaviour are the production ones, so "a second import writes nothing" is a property
 * of the code that ships rather than of a double.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFolderImportTest {

    private val at: Instant = Instant.parse("2026-10-20T00:00:00Z")
    private val treeUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    )
    private val variant = SafTreeAudioDiscovery.variantFor(treeUri)
    private val database: TunenagiDatabase = Fixtures.databaseOnCallerThread()

    /**
     * A provider whose answer is the same on every query.
     *
     * A fresh cursor per `read`, not one shared instance: a real provider returns a new cursor for
     * each query, and a reused one would be exhausted by the first scan - which would make the
     * "second import is unchanged" test fail for the fake's reason instead of the library's.
     */
    private class FolderRows(
        private val rows: List<Array<Any?>> = emptyList(),
        private val failure: RuntimeException? = null
    ) : AudioRowSource {
        override suspend fun <T> read(
            uri: Uri,
            projection: Array<String>,
            selection: String?,
            args: Array<String>?,
            sort: String?,
            block: (Cursor) -> T
        ): T? {
            failure?.let { throw it }
            val cursor = MatrixCursor(SafTreeAudioDiscovery.PROJECTION)
            rows.forEach { cursor.addRow(it) }
            return cursor.use(block)
        }
    }

    private fun document(
        id: String,
        name: String,
        mime: String = "audio/mpeg"
    ): Array<Any?> = arrayOf(id, name, mime)

    private fun importer(rows: AudioRowSource) = LocalFolderImport(
        database = database,
        rows = rows,
        clock = { at }
    )

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun importingAFolderIndexesItsAudioAndReturnsTheTracksInFolderOrder() = runBlocking {
        val rows = FolderRows(
            listOf(
                document("primary:Music/one.mp3", "one.mp3"),
                document("primary:Music/notes.txt", "notes.txt", mime = "text/plain"),
                document("primary:Music/two.flac", "two.flac", mime = "audio/flac")
            )
        )

        val result = importer(rows).importFolder(treeUri.toString())

        val imported = result as LocalImportResult.Imported
        assertEquals("both audio files are indexed", 2, imported.added)
        assertEquals(2, imported.trackIds.size)
        assertEquals(
            "a queue must start with the first file the folder listed",
            listOf("one", "two"),
            imported.trackIds.map { id ->
                database.trackDao().byId(id.value)!!.title
            }
        )

        val sources = database.sourceDao().forProviderVariant("builtin.local", variant)
        assertEquals(2, sources.size)
        assertTrue(
            "the folder grant is the variant, so a revoked grant marks only its own files",
            sources.all { it.sourceVariant == variant }
        )
        assertTrue(
            "an imported file is playable immediately",
            sources.all { it.availability == SourceAvailability.Available.name }
        )
    }

    @Test
    fun importingTheSameFolderAgainWritesNothingButStillOffersItsTracks() = runBlocking {
        val rows = FolderRows(listOf(document("primary:Music/one.mp3", "one.mp3")))
        val importer = importer(rows)

        importer.importFolder(treeUri.toString())
        val second = importer.importFolder(treeUri.toString()) as LocalImportResult.Imported

        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(1, second.unchanged)
        assertEquals(1, second.trackIds.size)
        assertEquals("a rescan must not duplicate the library", 1, database.trackDao().count())
    }

    @Test
    fun aFolderWithNoPlayableAudioImportsNothingAndSaysSoWithAnEmptyTrackList() = runBlocking {
        val rows = FolderRows(
            listOf(document("primary:Music/cover.jpg", "cover.jpg", mime = "image/jpeg"))
        )

        val imported = importer(rows).importFolder(treeUri.toString()) as LocalImportResult.Imported

        assertEquals(0, imported.added)
        assertTrue("nothing to queue, and no invented track", imported.trackIds.isEmpty())
    }

    @Test
    fun aRevokedGrantIsReportedWithoutWritingAnything() = runBlocking {
        val result = importer(FolderRows(failure = SecurityException("grant revoked")))
            .importFolder(treeUri.toString())

        assertEquals(LocalImportResult.PermissionRequired, result)
        assertEquals(0, database.trackDao().count())
    }

    @Test
    fun aUriThatIsNotAFolderGrantFailsCleanlyInsteadOfThrowing() = runBlocking {
        val result = importer(FolderRows())
            .importFolder("not-a-content-uri")

        assertTrue("a malformed grant is a typed failure ($result)", result is LocalImportResult.Failed)
    }

    @Test
    fun theVariantIsDerivedFromTheGrantSoTwoFoldersStaySeparate() {
        val other = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3APodcasts"
        )

        assertEquals("saf:primary:Music", variant)
        assertEquals("saf:primary:Podcasts", SafTreeAudioDiscovery.variantFor(other))
        assertTrue(
            "a folder must not share a variant with MediaStore",
            variant != MediaStoreAudioDiscovery.MEDIASTORE_VARIANT
        )
    }

}
