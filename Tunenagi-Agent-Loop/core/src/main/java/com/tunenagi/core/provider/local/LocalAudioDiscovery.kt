package com.tunenagi.core.provider.local

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.tunenagi.core.model.ProviderId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finding audio that is already on the device — owned.
 *
 * The local adapter has two grants, and they fail independently, so each discovery declares the
 * *variant* it fills: `mediastore` for the system library, `saf:<treeId>` for one folder the user
 * picked. That value is what lands in `TrackSource.sourceVariant`, which is the whole reason a
 * revoked folder grant can mark its own files unreadable without touching the on-device library
 * (`SPEC/PROVIDERS.md`: non-destructive when permission is revoked).
 *
 * Rows are read through [AudioRowSource] rather than a `ContentResolver` held as a field. That is
 * the seam that lets the mapping, the query it builds and the revocation paths be tested on the JVM
 * with a real `Cursor`: what the tests exercise is the code that ships, and only the provider's own
 * answer is supplied by the test (no Android runtime exists here — ADR-008).
 */
data class DiscoveredAudio(
    /** The provider's own stable id: a MediaStore `_ID`, or a SAF document id. */
    val externalId: String,
    /** The uri playback will actually open. */
    val uri: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val mimeType: String?,
    /** Where the artwork can be read from, when the provider offers one. */
    val artworkUri: String?
)

/** A row that was read but deliberately not turned into audio, with the reason it was not. */
data class SkippedRow(val reason: String, val detail: String)

/**
 * The outcome of one discovery pass.
 *
 * `PermissionRequired` and `Failed` are separate because they ask different things of the user: the
 * first needs a grant (and the files are still there), the second needs a working provider. Neither
 * deletes anything.
 */
sealed interface LocalDiscoveryResult {

    data class Found(
        val entries: List<DiscoveredAudio>,
        val rowsRead: Int,
        val skipped: List<SkippedRow>
    ) : LocalDiscoveryResult

    data class PermissionRequired(val reason: String) : LocalDiscoveryResult

    data class Failed(val reason: String) : LocalDiscoveryResult
}

/** One grant's worth of discovery: MediaStore, or one SAF folder. */
interface LocalAudioDiscovery {

    /** The provider the found sources belong to — `builtin.local` for both local grants. */
    val providerId: ProviderId

    /** The variant stamped on every source this discovery owns; see the file comment. */
    val variant: String

    /** A human-readable name for the grant, used in reports and diagnostics. */
    val label: String

    suspend fun discover(): LocalDiscoveryResult
}

/**
 * The one row-reading function the discoveries need: `ContentResolver.query`, with the arguments the
 * caller built, and the cursor closed again by the implementation that opened it.
 *
 * The cursor is consumed inside [read] rather than handed out on purpose. A returned cursor makes
 * the caller responsible for closing it, which is both a leak waiting to happen and something no one
 * reading one call site can check; taking a block means the `use` and the `query` are adjacent, and
 * `null` is unambiguously "the provider gave us nothing".
 */
interface AudioRowSource {
    suspend fun <T> read(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        sort: String?,
        block: (Cursor) -> T
    ): T?
}

/**
 * The production [AudioRowSource].
 *
 * The query runs on `Dispatchers.IO`: `SPEC/PROVIDERS.md` requires the scan off the main thread, and
 * putting it here means no caller can forget it. A revoked grant reaches us as `SecurityException`
 * from the provider, which is the real revocation signal on Android — nothing here guesses at
 * permissions from a manifest.
 */
class ContentResolverAudioRows(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : AudioRowSource {

    override suspend fun <T> read(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        sort: String?,
        block: (Cursor) -> T
    ): T? = withContext(io) { resolver.query(uri, projection, selection, args, sort)?.use(block) }
}

/**
 * The device's own library, through MediaStore.
 *
 * `IS_MUSIC != 0` is the selection because the collection is shared with notifications, ringtones
 * and voice recordings; indexing those as music is the sort of thing a user only finds later. The
 * projection is the smallest set of columns the mapping needs, and the artwork uri is derived from
 * the row's album id rather than stored raw, so a re-scan cannot bake in a stale id.
 */
class MediaStoreAudioDiscovery(
    private val rows: AudioRowSource,
    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
) : LocalAudioDiscovery {

    override val providerId: ProviderId = ProviderId.Local
    override val variant: String = MEDIASTORE_VARIANT
    override val label: String = "device library"

    override suspend fun discover(): LocalDiscoveryResult {
        val found = try {
            rows.read(collection, PROJECTION, SELECTION, null, SORT) { cursor -> read(cursor) }
        } catch (denied: SecurityException) {
            return LocalDiscoveryResult.PermissionRequired(
                denied.message ?: "the app is not allowed to read the device's audio library"
            )
        } catch (failure: RuntimeException) {
            return LocalDiscoveryResult.Failed("the media store could not be queried: $failure")
        }
        return found ?: LocalDiscoveryResult.Failed("the media store returned no cursor")
    }

    private fun read(cursor: Cursor): LocalDiscoveryResult.Found {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val displayColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

        val found = mutableListOf<DiscoveredAudio>()
        val skipped = mutableListOf<SkippedRow>()
        var read = 0
        while (cursor.moveToNext()) {
            read++
            val id = cursor.getLong(idColumn)
            val mime = cursor.getString(mimeColumn)
            val displayName = cursor.getString(displayColumn)
            if (mime == null || !mime.startsWith(AUDIO_MIME_PREFIX)) {
                skipped += SkippedRow("not-audio", "media row $id reports mime '$mime'")
                continue
            }
            val title = firstNonBlank(cursor.getString(titleColumn), displayName?.withoutExtension())
            if (title == null) {
                skipped += SkippedRow("untitled", "media row $id has neither a title nor a file name")
                continue
            }
            val duration = cursor.getLong(durationColumn)
            found += DiscoveredAudio(
                externalId = id.toString(),
                uri = ContentUris.withAppendedId(collection, id).toString(),
                title = title,
                artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() },
                durationMs = duration.takeIf { it > 0 },
                mimeType = mime,
                artworkUri = cursor.getLong(albumIdColumn)
                    .takeIf { !cursor.isNull(albumIdColumn) && it > 0 }
                    ?.let { ContentUris.withAppendedId(ALBUM_ART, it).toString() }
            )
        }
        return LocalDiscoveryResult.Found(found, read, skipped)
    }

    companion object {
        const val MEDIASTORE_VARIANT = "mediastore"
        private const val AUDIO_MIME_PREFIX = "audio/"
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

        /** Every column the mapping reads, in one place so the query and the reader cannot drift. */
        internal val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        internal const val SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        internal const val SORT = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE, ${MediaStore.Audio.Media._ID}"
    }
}

/**
 * One folder the user picked through the system picker (SAF), read through its persistable grant.
 *
 * SAF has no `IS_MUSIC` and no album ids, so the filter is the document mime type and the artwork
 * reference is left null rather than guessed: `SPEC/PROVIDERS.md` puts embedded art and download
 * bounds in the resolver's job (TUN-0203), and inventing a uri here would make a later artwork task
 * read the wrong thing.
 */
class SafTreeAudioDiscovery(
    private val rows: AudioRowSource,
    private val treeUri: Uri
) : LocalAudioDiscovery {

    override val providerId: ProviderId = ProviderId.Local
    override val variant: String = variantFor(treeUri)
    override val label: String = "folder ${treeUri.lastPathSegment ?: treeUri.toString()}"

    override suspend fun discover(): LocalDiscoveryResult {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (malformed: RuntimeException) {
            return LocalDiscoveryResult.Failed("'$treeUri' is not a folder grant: $malformed")
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val found = try {
            rows.read(children, PROJECTION, null, null, null) { cursor -> read(cursor) }
        } catch (denied: SecurityException) {
            return LocalDiscoveryResult.PermissionRequired(
                denied.message ?: "the grant for '$label' was revoked or expired"
            )
        } catch (failure: RuntimeException) {
            return LocalDiscoveryResult.Failed("the folder '$label' could not be read: $failure")
        }
        return found ?: LocalDiscoveryResult.Failed("the folder '$label' returned no listing")
    }

    private fun read(cursor: Cursor): LocalDiscoveryResult.Found {
        val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

        val found = mutableListOf<DiscoveredAudio>()
        val skipped = mutableListOf<SkippedRow>()
        var read = 0
        while (cursor.moveToNext()) {
            read++
            val documentId = cursor.getString(idColumn)
            val displayName = cursor.getString(nameColumn)
            val mime = cursor.getString(mimeColumn)
            if (documentId.isNullOrBlank()) {
                skipped += SkippedRow("row-without-document-id", "a row in '$label' has no document id")
                continue
            }
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                skipped += SkippedRow("directory", "$displayName in '$label' is a folder")
                continue
            }
            if (mime == null || !mime.startsWith(AUDIO_MIME_PREFIX)) {
                skipped += SkippedRow("not-audio", "$displayName in '$label' reports mime '$mime'")
                continue
            }
            val title = firstNonBlank(displayName?.withoutExtension())
            if (title == null) {
                skipped += SkippedRow("untitled", "$documentId in '$label' has no file name")
                continue
            }
            found += DiscoveredAudio(
                externalId = documentId,
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                title = title,
                // A file name is not an artist: any tag reading belongs in the resolver, where it can
                // be wrong honestly rather than invented here.
                artist = null,
                // DocumentsContract exposes no duration column; the resolver reads it from the file.
                durationMs = null,
                mimeType = mime,
                artworkUri = null
            )
        }
        return LocalDiscoveryResult.Found(found, read, skipped)
    }

    companion object {
        private const val AUDIO_MIME_PREFIX = "audio/"

        internal val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        /**
         * The variant for one grant.
         *
         * The tree's document id, not the whole uri: the uri carries the authority and the user id,
         * and a variant that changes when the same folder is picked again through a different uri
         * form would duplicate the library. Two different folders stay distinct, which is what a
         * revoked grant needs.
         */
        fun variantFor(treeUri: Uri): String =
            "saf:" + runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                .getOrElse { treeUri.toString() }
    }
}

private fun String.withoutExtension(): String =
    substringBeforeLast('.', missingDelimiterValue = this).takeIf { it.isNotBlank() } ?: this

private fun firstNonBlank(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
