package com.tunenagi.core.provider.local

import android.net.Uri
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The local resolver, on the JVM.
 *
 * What is real here: the file header contract (exactly how many bytes are read, and from which uri),
 * the container classification, the mapping from a read outcome to the typed result, and the
 * diagnostics record. What is *supplied* is the file's own bytes, because this project has no
 * Android runtime and no Android decoders (ADR-008) — so the tests below prove the decision the
 * resolver makes about real bytes, and they cannot prove that a decoder would then render them.
 * That gap is stated in `OPERATIONS/DEFERRED_VERIFICATION.md` rather than papered over.
 */
@RunWith(RobolectricTestRunner::class)
class LocalSourceResolverTest {

    private val recorded = mutableListOf<ResolveAttempt>()
    private val diagnostics = ResolveDiagnostics { attempt -> recorded += attempt }

    private val track = Track(id = TrackId.random(), title = "Slow Tide", durationMs = 190_000)

    private class StubHeads(private val answer: (Uri) -> FileHead) : FileHeadReader {
        val reads = mutableListOf<Pair<Uri, Int>>()

        override suspend fun read(uri: Uri, bytes: Int): FileHead {
            reads += uri to bytes
            return answer(uri)
        }
    }

    private fun localSource(
        uri: String = LOCAL_URI,
        contentType: String? = "audio/mpeg",
        availability: SourceAvailability = SourceAvailability.Available
    ) = TrackSource(
        id = TrackSourceId.random(),
        trackId = track.id,
        providerId = ProviderId.Local,
        externalId = "7",
        localUri = uri,
        sourceVariant = "mediastore",
        contentType = contentType,
        availability = availability
    )

    private fun header(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun isoBaseMedia(brand: String): ByteArray {
        val header = ByteArray(12)
        "ftyp".forEachIndexed { index, character -> header[4 + index] = character.code.toByte() }
        brand.forEachIndexed { index, character -> header[8 + index] = character.code.toByte() }
        return header
    }

    private fun riffWave(): ByteArray {
        val header = ByteArray(12)
        "RIFF".forEachIndexed { index, character -> header[index] = character.code.toByte() }
        "WAVE".forEachIndexed { index, character -> header[8 + index] = character.code.toByte() }
        return header
    }

    private fun resolver(heads: FileHeadReader): PlaybackSourceResolver =
        LocalSourceResolver(heads = heads, diagnostics = diagnostics)

    private fun resolve(source: TrackSource, heads: FileHeadReader): ResolveResult =
        runBlocking { resolver(heads).resolve(track, source) }

    private fun lastOutcome(): ResolveOutcome = recorded.last().outcome

    /** A reader that hands the same header back for every uri, and records what it was asked for. */
    private fun heads(header: ByteArray) = StubHeads { FileHead.Read(header) }

    // ---- playable containers --------------------------------------------------------------------

    @Test
    fun anMp3ResolvesToAReferenceWithTheFormatItsBytesDeclare() = runBlocking {
        val source = localSource()
        val rows = heads(header(0x49, 0x44, 0x33, 0x03, 0x00))

        val result = resolver(rows).resolve(track, source)

        val resolved = result as ResolveResult.Resolved
        assertEquals(LOCAL_URI, resolved.reference.uri)
        assertEquals("audio/mpeg", resolved.reference.mimeType)
        assertEquals(source.id, resolved.reference.sourceId)
        // The reference is not something that expires: a local file is available for as long as it
        // is on the device.
        assertEquals(null, resolved.reference.expiresAt)
        assertEquals(ResolveOutcome.Playable("audio/mpeg"), lastOutcome())
    }

    @Test
    fun aTaglessMpegFrameAlsoResolves() = runBlocking {
        val result = resolve(localSource(), heads(header(0xFF, 0xFB, 0x90, 0x00)))

        assertEquals("audio/mpeg", (result as ResolveResult.Resolved).reference.mimeType)
    }

    @Test
    fun theFormatsTheAppShipsAllResolveToTheirOwnType() {
        val cases = mapOf(
            header(0x66, 0x4C, 0x61, 0x43) to "audio/flac",
            header(0x4F, 0x67, 0x67, 0x53) to "audio/ogg",
            isoBaseMedia("M4A ") to "audio/mp4",
            riffWave() to "audio/wav",
            header(0xFF, 0xF1) to "audio/aac"
        )

        cases.forEach { (bytes, mimeType) ->
            val result = resolve(localSource(), heads(bytes))

            assertEquals(
                "these header bytes should resolve as $mimeType",
                mimeType,
                (result as ResolveResult.Resolved).reference.mimeType
            )
        }
    }

    /** The header is read, never the file: a resolve on a large library must stay cheap. */
    @Test
    fun theResolverReadsABoundedHeaderFromTheSourceUri() {
        val rows = heads(header(0x49, 0x44, 0x33))

        resolve(localSource(), rows)

        assertEquals(listOf(Uri.parse(LOCAL_URI) to LocalAudioSniffer.HEADER_BYTES), rows.reads)
    }

    // ---- unsupported media ----------------------------------------------------------------------

    @Test
    fun aContainerTheAppKnowsButDoesNotPlayIsAnUnsupportedFormatError() {
        val unplayable = mapOf(
            LocalAudioContainer.Aiff to header(0x46, 0x4F, 0x52, 0x4D, 0, 0, 0, 0, 0x41, 0x49, 0x46, 0x46),
            LocalAudioContainer.Asf to header(0x30, 0x26, 0xB2, 0x75),
            LocalAudioContainer.Midi to header(0x4D, 0x54, 0x68, 0x64)
        )

        unplayable.forEach { (container, bytes) ->
            val result = resolve(localSource(), heads(bytes))

            assertEquals(
                "$container must be refused rather than handed to a decoder",
                ResolveResult.Failed(ProviderError.UnsupportedFormat),
                result
            )
            assertEquals(ResolveOutcome.Unsupported(container.label), lastOutcome())
        }
    }

    @Test
    fun bytesThatAreNotAContainerAtAllAreUnsupportedRatherThanUnreadable() = runBlocking {
        val result = resolver(heads("this is a readme, not audio".toByteArray()))
            .resolve(track, localSource())

        assertEquals(ResolveResult.Failed(ProviderError.UnsupportedFormat), result)
        assertEquals(ResolveOutcome.Unsupported("unrecognised-container"), lastOutcome())
    }

    @Test
    fun anEmptyFileIsReportedAsAnEmptyFileRatherThanAsAnUnknownFormat() = runBlocking {
        val result = resolver(heads(ByteArray(0))).resolve(track, localSource())

        assertEquals(ResolveResult.Failed(ProviderError.UnsupportedFormat), result)
        assertEquals(ResolveOutcome.Unsupported("empty-file"), lastOutcome())
    }

    /**
     * The reason this task reads bytes at all. A scan records a content type from the provider, and
     * a SAF provider guesses one from the file name; both can be wrong, and a wrong label would
     * surface as a decoder error mid-playback instead of an answer here.
     */
    @Test
    fun theBytesDecideTheFormatWhenTheDeclaredTypeDisagrees() = runBlocking {
        val source = localSource(contentType = "audio/mpeg")

        val result = resolver(heads(header(0x66, 0x4C, 0x61, 0x43))).resolve(track, source)

        assertEquals("audio/flac", (result as ResolveResult.Resolved).reference.mimeType)
    }

    @Test
    fun aDeclaredPlayableTypeCannotMakeAnUnplayableFilePlayable() = runBlocking {
        val source = localSource(contentType = "audio/mpeg")

        val result = resolver(heads(header(0x30, 0x26, 0xB2, 0x75))).resolve(track, source)

        assertEquals(ResolveResult.Failed(ProviderError.UnsupportedFormat), result)
    }

    // ---- files that are not there ---------------------------------------------------------------

    @Test
    fun aFileThatIsNoLongerListedAsksForNothingAndIsReportedAsGone() = runBlocking {
        val source = localSource()

        val result = resolver(StubHeads { FileHead.Missing }).resolve(track, source)

        assertEquals(ResolveResult.NeedsUserAction(SourceAvailability.StorageGone), result)
        assertEquals(ResolveOutcome.StorageGone, lastOutcome())
    }

    /** A revoked grant is recoverable by the user, which is why it is not the same state as gone. */
    @Test
    fun aRevokedGrantAsksForThePermissionBack() = runBlocking {
        val source = localSource()

        val result = resolver(StubHeads { FileHead.Denied }).resolve(track, source)

        assertEquals(ResolveResult.NeedsUserAction(SourceAvailability.PermissionRequired), result)
        assertEquals(ResolveOutcome.PermissionRequired, lastOutcome())
    }

    @Test
    fun aReadThatFailsForAnotherReasonIsATypedFailureWithItsOwnCategory() = runBlocking {
        val source = localSource()

        val result = resolver(StubHeads { FileHead.Unreadable("input/output error") })
            .resolve(track, source)

        assertEquals(ResolveResult.Failed(ProviderError.Unknown("local-read-failed")), result)
        assertEquals(ResolveOutcome.Failed("local-read-failed"), lastOutcome())
        // The provider's own message is not carried into the result: it can contain a path.
        assertFalse(result.toString().contains("input/output error"))
    }

    // ---- provider discipline --------------------------------------------------------------------

    @Test
    fun aResolverRefusesToAnswerForAnotherProvidersSource() = runBlocking {
        val foreign = TrackSource(
            id = TrackSourceId.random(),
            trackId = track.id,
            providerId = ProviderId.Audius,
            externalId = "D7KyD",
            contentType = "audio/mpeg",
            availability = SourceAvailability.Available
        )

        val result = resolver(heads(header(0x49, 0x44, 0x33))).resolve(track, foreign)

        assertEquals(ResolveResult.Failed(ProviderError.Unknown("not-a-local-source")), result)
        assertEquals(ResolveOutcome.Failed("not-a-local-source"), lastOutcome())
    }

    // ---- diagnostics ----------------------------------------------------------------------------

    @Test
    fun everyAttemptIsRecordedWithItsProviderItsSourceOutcomeAndTiming() {
        val source = localSource()

        resolve(source, heads(header(0x66, 0x4C, 0x61, 0x43)))

        val attempt = recorded.single()
        assertEquals(ProviderId.Local, attempt.providerId)
        assertEquals(source.id, attempt.sourceId)
        assertEquals(ResolveOutcome.Playable("audio/flac"), attempt.outcome)
        assertTrue("elapsed time must be measured, not invented", attempt.elapsedMillis >= 0)
    }

    /**
     * A diagnostics record must be safe to show and to export: `SPEC/PROVIDERS.md` allows local
     * events and forbids leaking the user's library. The record has no field for a uri or a title,
     * and this test is the guard that keeps it that way.
     */
    @Test
    fun aRecordedAttemptCarriesNoUriAndNoFileName() {
        resolve(localSource(), heads(header(0x49, 0x44, 0x33)))

        val rendered = recorded.joinToString("\n")
        assertFalse(rendered.contains("content://"))
        assertFalse(rendered.contains("/storage"))
        assertFalse(rendered.contains("audio/media"))
    }

    @Test
    fun theDiagnosticsWindowKeepsOnlyTheMostRecentAttempts() {
        val window = RecentResolveDiagnostics(capacity = 3)
        val source = localSource()

        repeat(5) { index ->
            window.record(
                ResolveAttempt(
                    providerId = ProviderId.Local,
                    sourceId = source.id,
                    outcome = ResolveOutcome.Failed("attempt-$index"),
                    elapsedMillis = index.toLong()
                )
            )
        }

        assertEquals(3, window.recent().size)
        assertEquals(
            listOf("attempt-2", "attempt-3", "attempt-4"),
            window.recent().map { (it.outcome as ResolveOutcome.Failed).category }
        )
        assertEquals("attempt-4", (window.latest()!!.outcome as ResolveOutcome.Failed).category)
        window.clear()
        assertEquals(0, window.recent().size)
        assertEquals(null, window.latest())
    }

    @Test
    fun aDiagnosticsWindowThatCouldNotHoldAnythingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RecentResolveDiagnostics(capacity = 0) }
    }

    private companion object {
        const val LOCAL_URI = "content://media/external/audio/media/7"
    }
}
