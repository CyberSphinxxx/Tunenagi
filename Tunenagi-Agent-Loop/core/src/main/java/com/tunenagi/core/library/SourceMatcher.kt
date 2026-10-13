package com.tunenagi.core.library

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.TitleSort

/**
 * Deciding whether a provider row is the same recording as a local track — owned.
 *
 * `SPEC/DATA_MODEL.md` is unusually explicit about the bar, and the design follows it literally:
 *
 * - Prefer a verified identifier (the same provider row, an ISRC or an MBID) plus secondary checks.
 * - "A title/artist similarity alone is not enough to automatically merge editions."
 * - "Manual overrides trump heuristics."
 *
 * So [MatchDecision.AutoAccept] is deliberately hard to reach: it needs title **and** artist **and**
 * duration agreement. Anything less is offered to the user as [MatchDecision.NeedsConfirmation], and
 * two different editions of one song (a radio edit against a live take) are separate rows that the
 * matcher refuses to choose between — the test `duplicateEditionsAreNeverAutomaticallyMerged` is the
 * assertion that keeps it that way.
 */
object SourceMatcher {

    /** Title, artists and duration together: the minimum bar for an automatic merge. */
    const val AUTO_ACCEPT_THRESHOLD = 0.85

    /** Enough to be worth asking about, not enough to act on. */
    const val CONFIRM_THRESHOLD = 0.60

    /** Durations inside this window are the same recording, not just a similar one. */
    const val SAME_RECORDING_TOLERANCE_MS = 2_000L

    /** Beyond this the recordings are different edits, whatever the title says. */
    const val DIFFERENT_EDITION_GAP_MS = 30_000L

    /** Weights; the numbers are a policy, so they are named and in one place. */
    private const val WEIGHT_TITLE_EXACT = 0.35
    private const val WEIGHT_TITLE_PARTIAL = 0.15
    private const val WEIGHT_ARTIST = 0.25
    private const val WEIGHT_DURATION_CLOSE = 0.20
    private const val WEIGHT_DURATION_NEAR = 0.08
    private const val WEIGHT_ALBUM = 0.10
    private const val WEIGHT_IDENTIFIER = 0.10

    /** What the app knows about the track it is trying to match. */
    data class Query(
        val title: String,
        val artists: List<String>,
        val durationMs: Long? = null,
        val isrc: String? = null,
        val mbid: String? = null,
        val albumName: String? = null
    ) {
        init {
            require(title.isNotBlank()) { "A match query needs a title" }
        }
    }

    enum class MatchDecision { AutoAccept, NeedsConfirmation, Reject }

    data class Candidate(
        val external: ExternalTrack,
        val score: Double,
        val reasons: List<String>,
        val decision: MatchDecision
    )

    /** The result of resolving a track against everything a provider offered. */
    sealed interface Resolution {
        /** A user-confirmed match exists; nothing else may replace it. */
        data class Locked(val confirmed: Candidate) : Resolution

        data class Accepted(val candidate: Candidate) : Resolution

        /** Worth asking: the user sees this ordered list and picks. */
        data class NeedsChoice(val ranked: List<Candidate>) : Resolution

        data object None : Resolution
    }

    /** A match the user has already confirmed (or that a stored lock points at). */
    data class Confirmed(
        val providerId: ProviderId,
        val externalId: String,
        val manuallyConfirmed: Boolean = true
    )

    fun score(query: Query, candidate: ExternalTrack, confirmed: Confirmed? = null): Candidate {
        val reasons = mutableListOf<String>()
        var score = 0.0

        val queryTitle = TitleSort.key(query.title)
        val candidateTitle = TitleSort.key(candidate.title)
        when {
            queryTitle == candidateTitle -> {
                score += WEIGHT_TITLE_EXACT
                reasons += "title matches exactly"
            }
            tokenOverlap(queryTitle, candidateTitle) >= 0.6 -> {
                score += WEIGHT_TITLE_PARTIAL
                reasons += "title overlaps but is not identical"
            }
        }

        if (artistsOverlap(query.artists, candidate.artists)) {
            score += WEIGHT_ARTIST
            reasons += "artist matches"
        }

        val durationGap = durationGapMs(query.durationMs, candidate.durationMs)
        when {
            durationGap == null -> reasons += "duration is unknown on one side"
            durationGap <= SAME_RECORDING_TOLERANCE_MS -> {
                score += WEIGHT_DURATION_CLOSE
                reasons += "duration agrees within ${SAME_RECORDING_TOLERANCE_MS / 1000}s"
            }
            durationGap <= 10_000 -> {
                score += WEIGHT_DURATION_NEAR
                reasons += "duration is close but not equal"
            }
            else -> reasons += "duration differs by ${durationGap / 1000}s"
        }

        if (query.albumName != null && candidate.albumName != null &&
            TitleSort.key(query.albumName) == TitleSort.key(candidate.albumName)
        ) {
            score += WEIGHT_ALBUM
            reasons += "album matches"
        }

        if (hasVerifiedIdentifier(query, candidate)) {
            score += WEIGHT_IDENTIFIER
            reasons += "a verified identifier agrees"
        }

        val rounded = (score * 1000).toInt() / 1000.0
        return Candidate(
            external = candidate,
            score = rounded,
            reasons = reasons,
            decision = decide(query, candidate, rounded, confirmed)
        )
    }

    /**
     * Ranking is total and stable: equal scores fall back to the provider tuple, so asking twice, or
     * asking with the results in a different order, gives the same answer.
     */
    fun rank(query: Query, candidates: List<ExternalTrack>, confirmed: Confirmed? = null): List<Candidate> =
        candidates
            .map { score(query, it, confirmed) }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.external.providerId.value }
                    .thenBy { it.external.externalId }
            )

    fun resolve(query: Query, candidates: List<ExternalTrack>, confirmed: Confirmed? = null): Resolution {
        val ranked = rank(query, candidates, confirmed)
        // Only a *manual* confirmation locks. A stored suggestion is data from an earlier attempt,
        // not a decision: `manuallyConfirmed = false` must still reach the user as a question
        // (found by the test `anUnconfirmedSuggestionStillAsks`, which failed until this check
        // was added).
        if (confirmed != null && confirmed.manuallyConfirmed) {
            ranked.firstOrNull {
                it.external.providerId == confirmed.providerId && it.external.externalId == confirmed.externalId
            }?.let { return Resolution.Locked(it) }
        }
        val accepted = ranked.firstOrNull { it.decision == MatchDecision.AutoAccept }
        if (accepted != null) return Resolution.Accepted(accepted)

        val offered = ranked.filter { it.decision == MatchDecision.NeedsConfirmation }
        return if (offered.isEmpty()) Resolution.None else Resolution.NeedsChoice(offered)
    }

    private fun decide(
        query: Query,
        candidate: ExternalTrack,
        score: Double,
        confirmed: Confirmed?
    ): MatchDecision {
        if (confirmed != null && !confirmed.manuallyConfirmed &&
            confirmed.providerId == candidate.providerId && confirmed.externalId == candidate.externalId
        ) {
            // A stored but unconfirmed suggestion is exactly that: a suggestion.
            return MatchDecision.NeedsConfirmation
        }

        val gap = durationGapMs(query.durationMs, candidate.durationMs)
        if (gap != null && gap > DIFFERENT_EDITION_GAP_MS) {
            // Same words, different recording: never merged, however well the titles agree.
            return MatchDecision.Reject
        }
        if (score < CONFIRM_THRESHOLD) return MatchDecision.Reject

        // The automatic bar needs all three of title, artist and duration. Without a duration on
        // both sides this can only ever be a question, never a decision.
        val durationAgrees = gap != null && gap <= SAME_RECORDING_TOLERANCE_MS
        val titleAgrees = TitleSort.key(query.title) == TitleSort.key(candidate.title)
        val artistAgrees = artistsOverlap(query.artists, candidate.artists)
        return if (score >= AUTO_ACCEPT_THRESHOLD && durationAgrees && titleAgrees && artistAgrees) {
            MatchDecision.AutoAccept
        } else {
            MatchDecision.NeedsConfirmation
        }
    }

    private fun durationGapMs(left: Long?, right: Long?): Long? =
        if (left == null || right == null) null else kotlin.math.abs(left - right)

    /** A verified identifier means an ISRC or MBID the provider itself reports, not a guess. */
    private fun hasVerifiedIdentifier(query: Query, candidate: ExternalTrack): Boolean =
        (query.isrc != null && candidate.isrc != null && query.isrc.equals(candidate.isrc, ignoreCase = true)) ||
            (query.mbid != null && candidate.mbid != null && query.mbid.equals(candidate.mbid, ignoreCase = true))

    private fun artistsOverlap(left: List<String>, right: List<String>): Boolean {
        val leftKeys = left.map(TitleSort::key).filter { it.isNotEmpty() }.toSet()
        val rightKeys = right.map(TitleSort::key).filter { it.isNotEmpty() }.toSet()
        return leftKeys.isNotEmpty() && rightKeys.isNotEmpty() && leftKeys.any { it in rightKeys }
    }

    private fun tokenOverlap(left: String, right: String): Double {
        val leftTokens = left.split(' ').filter { it.isNotEmpty() }.toSet()
        val rightTokens = right.split(' ').filter { it.isNotEmpty() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val shared = leftTokens.intersect(rightTokens).size
        return shared.toDouble() / leftTokens.union(rightTokens).size
    }
}
