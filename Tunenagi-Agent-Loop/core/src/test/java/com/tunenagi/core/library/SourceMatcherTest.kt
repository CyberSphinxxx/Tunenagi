package com.tunenagi.core.library

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher decides whether two rows are the same recording. Every test here is a "no" it has to
 * be able to say, because the failure that hurts is merging two different editions, not failing to
 * merge two identical ones.
 */
class SourceMatcherTest {

    private val audius = ProviderId.Audius

    private val local = SourceMatcher.Query(
        title = "The Slow Tide",
        artists = listOf("Kaori Ito"),
        durationMs = 190_000,
        albumName = "Dawn"
    )

    private fun external(
        externalId: String = "D7KyD",
        title: String = "The Slow Tide",
        artists: List<String> = listOf("Kaori Ito"),
        durationMs: Long? = 190_000,
        albumName: String? = "Dawn",
        isrc: String? = null,
        mbid: String? = null,
        providerId: ProviderId = audius
    ) = ExternalTrack(
        providerId = providerId,
        externalId = externalId,
        title = title,
        artists = artists,
        albumName = albumName,
        durationMs = durationMs,
        isrc = isrc,
        mbid = mbid
    )

    @Test
    fun anExactAgreementOnTitleArtistAndDurationIsAccepted() {
        val candidate = SourceMatcher.score(local, external())

        assertEquals(SourceMatcher.MatchDecision.AutoAccept, candidate.decision)
        assertTrue("score was ${candidate.score}", candidate.score >= SourceMatcher.AUTO_ACCEPT_THRESHOLD)
        assertTrue(candidate.reasons.any { it.contains("title") })
    }

    /** Without a duration on both sides nothing may be merged automatically, whatever the titles say. */
    @Test
    fun aMissingDurationCanNeverBeAcceptedAutomatically() {
        val unknown = SourceMatcher.score(local, external(durationMs = null))

        assertEquals(SourceMatcher.MatchDecision.NeedsConfirmation, unknown.decision)
        assertTrue(unknown.reasons.any { it.contains("duration is unknown") })
    }

    @Test
    fun theSameTitleByAnotherArtistIsOnlyAQuestion() {
        val other = SourceMatcher.score(local, external(artists = listOf("Someone Else")))

        assertEquals(SourceMatcher.MatchDecision.NeedsConfirmation, other.decision)
        assertTrue(other.score < SourceMatcher.AUTO_ACCEPT_THRESHOLD)
    }

    @Test
    fun anUnrelatedTrackIsRejected() {
        val unrelated = SourceMatcher.score(
            local,
            external(title = "Ocean Floor", artists = listOf("Another Band"), durationMs = 240_000, albumName = null)
        )

        assertEquals(SourceMatcher.MatchDecision.Reject, unrelated.decision)
    }

    /**
     * The rule the design exists for: a radio edit and a live take share every word of their title
     * and artist and are still different recordings.
     */
    @Test
    fun duplicateEditionsAreNeverAutomaticallyMerged() {
        val radioEdit = external(externalId = "edit", durationMs = 190_000)
        val liveTake = external(externalId = "live", title = "The Slow Tide (Live at Nakameguro)", durationMs = 245_000)

        val live = SourceMatcher.score(local, liveTake)
        assertEquals(SourceMatcher.MatchDecision.Reject, live.decision)
        assertTrue(live.reasons.any { it.contains("duration differs") })

        val resolution = SourceMatcher.resolve(local, listOf(radioEdit, liveTake))
        assertTrue("the two editions must not be silently merged", resolution is SourceMatcher.Resolution.Accepted)
        assertEquals("edit", (resolution as SourceMatcher.Resolution.Accepted).candidate.external.externalId)
    }

    @Test
    fun aVerifiedIdentifierIsReportedAndRaisesTheScore() {
        val without = SourceMatcher.score(local, external(isrc = null))
        val with = SourceMatcher.score(
            local.copy(isrc = "JPAB12345678"),
            external(isrc = "jpab12345678")
        )

        assertTrue("${with.score} should exceed ${without.score}", with.score >= without.score)
        assertTrue(with.reasons.any { it.contains("verified identifier") })
        assertEquals(SourceMatcher.MatchDecision.AutoAccept, with.decision)
    }

    @Test
    fun rankingIsTotalAndIndependentOfInputOrder() {
        val a = external(externalId = "a", title = "The Slow Tide", durationMs = 190_000)
        val b = external(externalId = "b", title = "The Slow Tide", durationMs = 190_500)
        val c = external(externalId = "c", title = "Slow Tide", durationMs = 191_000, albumName = null)

        val first = SourceMatcher.rank(local, listOf(a, b, c)).map { it.external.externalId }
        val second = SourceMatcher.rank(local, listOf(c, a, b)).map { it.external.externalId }

        assertEquals(first, second)
        assertEquals(listOf("a", "b", "c"), first)
    }

    @Test
    fun tiesFallBackToTheProviderTupleRatherThanSortOrder() {
        val first = external(externalId = "zzz")
        val second = external(externalId = "aaa")

        val ranked = SourceMatcher.rank(local, listOf(first, second)).map { it.external.externalId }

        assertEquals(listOf("aaa", "zzz"), ranked)
    }

    @Test
    fun aManuallyConfirmedMatchIsLockedAndOutranksTheBestScore() {
        val best = external(externalId = "best")
        val chosen = external(externalId = "chosen", durationMs = 195_000, albumName = null)
        val confirmed = SourceMatcher.Confirmed(audius, "chosen")

        val resolution = SourceMatcher.resolve(local, listOf(best, chosen), confirmed)

        assertTrue(resolution is SourceMatcher.Resolution.Locked)
        assertEquals("chosen", (resolution as SourceMatcher.Resolution.Locked).confirmed.external.externalId)
    }

    /** A stored suggestion is not a confirmation and must not be treated as one. */
    @Test
    fun anUnconfirmedSuggestionStillAsks() {
        val candidate = external(externalId = "suggested")
        val confirmed = SourceMatcher.Confirmed(audius, "suggested", manuallyConfirmed = false)

        val resolution = SourceMatcher.resolve(local, listOf(candidate), confirmed)

        assertTrue(resolution is SourceMatcher.Resolution.NeedsChoice)
    }

    @Test
    fun aConfirmationThatIsNotAmongTheCandidatesFallsBackToNormalMatching() {
        val confirmed = SourceMatcher.Confirmed(audius, "vanished")
        val resolution = SourceMatcher.resolve(local, listOf(external(externalId = "other")), confirmed)

        assertTrue(resolution is SourceMatcher.Resolution.Accepted)
    }

    @Test
    fun nothingWorthOfferingReportsNone() {
        val resolution = SourceMatcher.resolve(
            local,
            listOf(external(externalId = "x", title = "Ocean Floor", artists = listOf("Another Band"), durationMs = null))
        )

        assertEquals(SourceMatcher.Resolution.None, resolution)
    }

    @Test
    fun aQueryWithoutATitleIsRejected() {
        val thrown = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SourceMatcher.Query(title = "  ", artists = listOf("Kaori Ito"))
        }
        assertTrue(thrown.message.orEmpty().contains("title"))
    }
}
