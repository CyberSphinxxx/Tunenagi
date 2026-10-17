package com.tunenagi.core.provider.local

import android.net.Uri
import com.tunenagi.core.model.PlayableReference
import com.tunenagi.core.model.PlaybackSourceResolver
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ResolveResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.Track
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId

/**
 * What one resolve attempt found, in the terms a diagnostics view can show.
 *
 * `SPEC/PROVIDERS.md` requires local, opt-in diagnostic events with timing and an error category,
 * and no telemetry upload. This record is the shape of that event — and, deliberately, it is the
 * *whole* shape: there is no uri, no file name and no track title in it, because a diagnostics
 * screen (or an exported log) must not be able to leak the user's library paths.
 */
data class ResolveAttempt(
    val providerId: ProviderId,
    val sourceId: TrackSourceId,
    val outcome: ResolveOutcome,
    val elapsedMillis: Long
)

/** The categories a resolve can land in. One per [ResolveResult] arm, plus the concrete reason. */
sealed interface ResolveOutcome {

    /** A reference the player can open, with the format the file's own bytes declared. */
    data class Playable(val mimeType: String) : ResolveOutcome

    /** The grant is gone; the file is still there and a re-grant restores it. */
    data object PermissionRequired : ResolveOutcome

    /** The file is no longer at its uri. Nothing is deleted: the source is marked, not removed. */
    data object StorageGone : ResolveOutcome

    /** Bytes the app recognised but does not play, or bytes it could not classify at all. */
    data class Unsupported(val detail: String) : ResolveOutcome

    /** A read that failed for a reason of its own; [category] is a stable, non-sensitive label. */
    data class Failed(val category: String) : ResolveOutcome
}

/** Where resolve attempts are reported. The default does nothing; the app installs the real sink. */
fun interface ResolveDiagnostics {

    fun record(attempt: ResolveAttempt)

    companion object {
        /** The no-op sink, for tests and for a caller that has not opted into diagnostics. */
        val NONE = ResolveDiagnostics { }
    }
}

/**
 * A bounded, in-memory record of the most recent resolves.
 *
 * Bounded on purpose: this runs on every track change, and an unbounded list would be a slow leak in
 * the one place a user can trigger thousands of events. Oldest-out is the right policy for a
 * diagnostic view that shows "what happened lately".
 *
 * Synchronised because the player can resolve from more than one coroutine (a queue of items, a
 * retry), and a window that loses an entry under concurrency would be a diagnostics tool that lies.
 */
class RecentResolveDiagnostics(
    private val capacity: Int = DEFAULT_CAPACITY
) : ResolveDiagnostics {

    init {
        require(capacity > 0) { "A diagnostics window must hold at least one attempt, but was $capacity" }
    }

    private val attempts = ArrayDeque<ResolveAttempt>(capacity)

    override fun record(attempt: ResolveAttempt) {
        synchronized(attempts) {
            if (attempts.size == capacity) attempts.removeFirst()
            attempts.addLast(attempt)
        }
    }

    /** Newest last. A copy, so a reader cannot mutate the window. */
    fun recent(): List<ResolveAttempt> = synchronized(attempts) { attempts.toList() }

    fun latest(): ResolveAttempt? = synchronized(attempts) { attempts.lastOrNull() }

    fun clear() {
        synchronized(attempts) { attempts.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}

/**
 * Resolves a local [TrackSource] to something the player can open — owned.
 *
 * Four decisions are being made here, and each one is a rule from the spec rather than a preference:
 *
 * 1. **The bytes decide the format.** `SPEC/PROVIDERS.md` requires pure offline availability and a
 *    readability check *before* resolve, and the header the file itself carries is the only evidence
 *    of what it is. A declared `contentType` from a scan (or a SAF provider's mime guess) is a hint
 *    that may be wrong; believing it produces a decoder failure deep in playback instead of a typed
 *    answer now.
 * 2. **Unsupported media is a typed failure, never a transcode.** `SPEC/AUDIO_ENGINE.md` forbids
 *    silently transcoding unsupported or protected media, so a container outside
 *    [LocalAudioContainer.PLAYABLE] ends in `ProviderError.UnsupportedFormat`.
 * 3. **Gone and denied are different states.** A revoked grant and a deleted file ask different
 *    things of the user, so they are `NeedsUserAction(PermissionRequired)` and
 *    `NeedsUserAction(StorageGone)` rather than one "cannot play" bucket.
 * 4. **A resolver answers only for its own provider.** Being handed another provider's source is a
 *    wiring bug, and guessing which uri was meant would be exactly the silent substitution
 *    `SPEC/PROVIDERS.md` forbids.
 *
 * Nothing here writes to the library or deletes anything: a failed resolve is a report. The caller
 * decides what to persist (see the library-backed resolution in `core.playback`, which records what
 * the attempt learned).
 */
class LocalSourceResolver(
    private val heads: FileHeadReader,
    private val diagnostics: ResolveDiagnostics = ResolveDiagnostics.NONE,
    private val nanoTime: () -> Long = System::nanoTime
) : PlaybackSourceResolver {

    override val providerId: ProviderId = ProviderId.Local

    override suspend fun resolve(track: Track, source: TrackSource): ResolveResult {
        val started = nanoTime()
        val uri = source.localUri
        val verdict = when {
            source.providerId != providerId -> Verdict(
                result = ResolveResult.Failed(ProviderError.Unknown(NOT_THIS_PROVIDER)),
                outcome = ResolveOutcome.Failed(NOT_THIS_PROVIDER)
            )

            // Unreachable for a source built by the domain (a local source must carry a uri), and
            // still answered rather than thrown: a row that got into the database some other way
            // must not crash playback.
            uri == null -> Verdict(
                result = ResolveResult.Failed(ProviderError.Unknown(NO_URI)),
                outcome = ResolveOutcome.Failed(NO_URI)
            )

            else -> read(source, uri)
        }
        diagnostics.record(
            ResolveAttempt(
                providerId = providerId,
                sourceId = source.id,
                outcome = verdict.outcome,
                elapsedMillis = elapsedMillis(started)
            )
        )
        return verdict.result
    }

    private suspend fun read(source: TrackSource, uri: String): Verdict {
        val parsed = runCatching { Uri.parse(uri) }.getOrElse {
            return Verdict(
                result = ResolveResult.Failed(ProviderError.Unknown(UNREADABLE_URI)),
                outcome = ResolveOutcome.Failed(UNREADABLE_URI)
            )
        }
        return when (val head = heads.read(parsed, LocalAudioSniffer.HEADER_BYTES)) {
            FileHead.Denied -> Verdict(
                result = ResolveResult.NeedsUserAction(SourceAvailability.PermissionRequired),
                outcome = ResolveOutcome.PermissionRequired
            )

            FileHead.Missing -> Verdict(
                result = ResolveResult.NeedsUserAction(SourceAvailability.StorageGone),
                outcome = ResolveOutcome.StorageGone
            )

            is FileHead.Unreadable -> Verdict(
                result = ResolveResult.Failed(ProviderError.Unknown(READ_FAILED)),
                outcome = ResolveOutcome.Failed(READ_FAILED)
            )

            is FileHead.Read -> classify(source, uri, head.bytes)
        }
    }

    private fun classify(source: TrackSource, uri: String, header: ByteArray): Verdict =
        when (val sniffed = LocalAudioSniffer.sniff(header)) {
            SniffedContainer.Empty -> unsupported(EMPTY_FILE)
            SniffedContainer.Unknown -> unsupported(UNRECOGNISED)
            is SniffedContainer.Recognised -> {
                val container = sniffed.container
                if (container in LocalAudioContainer.PLAYABLE) {
                    Verdict(
                        result = ResolveResult.Resolved(
                            PlayableReference(
                                uri = uri,
                                mimeType = container.mimeType,
                                sourceId = source.id
                            )
                        ),
                        // The reference carries the *sniffed* type, not the declared one: it is the
                        // one the player is told, and the one the library should keep.
                        outcome = ResolveOutcome.Playable(container.mimeType)
                    )
                } else {
                    unsupported(container.label)
                }
            }
        }

    private fun unsupported(detail: String) = Verdict(
        result = ResolveResult.Failed(ProviderError.UnsupportedFormat),
        outcome = ResolveOutcome.Unsupported(detail)
    )

    private fun elapsedMillis(started: Long): Long =
        ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    /** A result and the diagnostic category that produced it, kept together so they cannot drift. */
    private class Verdict(val result: ResolveResult, val outcome: ResolveOutcome)

    private companion object {
        const val NOT_THIS_PROVIDER = "not-a-local-source"
        const val NO_URI = "local-source-without-a-uri"
        const val UNREADABLE_URI = "unreadable-local-uri"
        const val READ_FAILED = "local-read-failed"
        const val EMPTY_FILE = "empty-file"
        const val UNRECOGNISED = "unrecognised-container"
    }
}
