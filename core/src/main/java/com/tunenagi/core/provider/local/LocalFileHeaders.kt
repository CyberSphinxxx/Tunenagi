package com.tunenagi.core.provider.local

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading the first bytes of one local file — owned.
 *
 * `SPEC/PROVIDERS.md` requires the local adapter to verify a file is readable *before* resolving it,
 * and to report a failed read as `StorageGone` or `PermissionRequired`. That check needs exactly one
 * thing from Android: an `InputStream` for a uri. Making it a seam keeps the resolver itself plain
 * Kotlin and testable on the JVM (ADR-008: there is no Android runtime here), while the production
 * implementation below is the only place that touches the content resolver.
 *
 * The header is read, not the file. A resolver that downloaded a whole file to decide what it is
 * would be unusable on a library of thousands of entries, and the four-to-sixteen bytes a signature
 * needs are all [LocalAudioSniffer] can use anyway.
 */
sealed interface FileHead {

    /** The file opened and the first bytes came back. Fewer than requested means a short file. */
    class Read(val bytes: ByteArray) : FileHead {
        /** Only for assertions and logs; a header is bytes, not text. */
        override fun toString(): String = "Read(${bytes.size} bytes)"
    }

    /** The provider has nothing at this uri any more: the file was deleted, moved or unmounted. */
    data object Missing : FileHead

    /** The app holds no grant for this uri: the user revoked it, or it was never persisted. */
    data object Denied : FileHead

    /** The provider answered badly for a reason that is neither "gone" nor "not allowed". */
    data class Unreadable(val reason: String) : FileHead
}

/** The one file-reading operation a local resolver needs. */
interface FileHeadReader {

    /**
     * Reads at most [bytes] bytes from [uri].
     *
     * Never throws for a file that is gone or not permitted: those are the two outcomes the caller
     * has to act on, so they are values. A caller that received an exception instead would have to
     * guess which of them it was from a message.
     */
    suspend fun read(uri: Uri, bytes: Int): FileHead
}

/**
 * The production [FileHeadReader], over the real [ContentResolver].
 *
 * Three boundaries are being mapped here and each is deliberate:
 *
 * - `SecurityException` is Android's own signal that a grant is gone (a revoked SAF folder, or a
 *   media permission removed while the app was backgrounded), and it is *not* the same as a missing
 *   file: the bytes are still on the device and a re-grant restores them.
 * - `FileNotFoundException` is what the framework throws for a uri that no longer resolves — a
 *   MediaStore row whose file was deleted, or a document id that is gone. Whether the underlying
 *   provider reported it as absence or as a lost grant, this app cannot tell them apart, and it must
 *   not claim to: [Missing] is the honest reading of that answer, and the next scan re-checks.
 * - Anything else is [FileHead.Unreadable] — reported with its reason rather than swallowed.
 *
 * The read happens on `Dispatchers.IO`: the scan-side rule from TUN-0202 (never touch a provider on
 * the main thread) applies just as much at playback time, and putting it here means no caller can
 * forget it.
 */
class ContentResolverFileHeadReader(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : FileHeadReader {

    override suspend fun read(uri: Uri, bytes: Int): FileHead {
        require(bytes > 0) { "A header read must ask for at least one byte, but asked for $bytes" }
        return withContext(io) {
            try {
                // A null stream is the provider saying it has nothing here; a null *cursor* equivalent
                // would be an empty library, and this is the same distinction TUN-0202 draws.
                resolver.openInputStream(uri)?.use { stream -> FileHead.Read(stream.readAtMost(bytes)) }
                    ?: FileHead.Missing
            } catch (denied: SecurityException) {
                FileHead.Denied
            } catch (gone: FileNotFoundException) {
                FileHead.Missing
            } catch (failure: IOException) {
                FileHead.Unreadable(failure.message ?: failure::class.java.simpleName)
            }
        }
    }
}

/**
 * Fills up to [count] bytes, or as many as the stream offers before ending.
 *
 * Hand-rolled rather than `readNBytes`, which is API 33+ while this app supports API 26; and a
 * single `read` call is not enough because a stream may return fewer bytes than asked for without
 * being at its end, which would silently turn a valid header into a truncated one.
 */
private fun InputStream.readAtMost(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled == count) buffer else buffer.copyOf(filled)
}
