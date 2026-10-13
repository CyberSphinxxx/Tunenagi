package com.tunenagi.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAndSearchTest {

    @Test
    fun repeatModeCyclesAndOnlyAllWrapsTheQueue() {
        assertEquals(RepeatMode.All, RepeatMode.nextAfter(RepeatMode.Off))
        assertEquals(RepeatMode.One, RepeatMode.nextAfter(RepeatMode.All))
        assertEquals(RepeatMode.Off, RepeatMode.nextAfter(RepeatMode.One))

        assertTrue(RepeatMode.All.wrapsQueue)
        assertFalse(RepeatMode.One.wrapsQueue)
        assertFalse(RepeatMode.Off.wrapsQueue)
    }

    /** Corrupt persisted state must surface, not silently become "repeat off" and lose the setting. */
    @Test
    fun repeatModeRejectsUnknownPersistedValues() {
        assertEquals(RepeatMode.One, RepeatMode.fromPersisted("one"))
        assertThrows(IllegalArgumentException::class.java) { RepeatMode.fromPersisted("forever") }
    }

    @Test
    fun playableReferenceKnowsWhenItHasExpired() {
        val sourceId = TrackSourceId.random()
        val now = Instant.parse("2026-10-14T00:00:00Z")
        val expiring = PlayableReference("https://example.invalid/stream", sourceId = sourceId, expiresAt = now)

        // Exactly at the expiry instant the reference is already treated as expired: a URL that
        // expires *now* is not safe to hand to the player.
        assertTrue(expiring.isExpiredAt(now))
        assertTrue(expiring.isExpiredAt(now.plusSeconds(1)))
        assertFalse(expiring.isExpiredAt(now.minusSeconds(1)))

        val local = PlayableReference("content://media/external/audio/1", sourceId = sourceId)
        assertFalse(local.isExpiredAt(now.plusSeconds(86_400)))
        assertNull(local.mimeType)
    }

    @Test
    fun playableReferenceRequiresAUri() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayableReference("  ", sourceId = TrackSourceId.random())
        }
    }

    @Test
    fun resolveResultKeepsFailureReasonsTyped() {
        val resolved: ResolveResult = ResolveResult.Resolved(
            PlayableReference("content://audio/1", sourceId = TrackSourceId.random())
        )
        val failed: ResolveResult = ResolveResult.Failed(ProviderError.Restricted("gated"))
        val needsAction: ResolveResult = ResolveResult.NeedsUserAction(SourceAvailability.PermissionRequired)

        assertTrue(resolved is ResolveResult.Resolved)
        assertEquals(SourceAvailability.PermissionRequired, (needsAction as ResolveResult.NeedsUserAction).availability)
        // A failed remote resolve must not be reported as a source that needs a user action.
        assertFalse(failed is ResolveResult.NeedsUserAction)
        assertEquals("Not playable here: gated", (failed as ResolveResult.Failed).error.userMessage)
    }

    @Test
    fun searchQueryNormalizesAndBoundsTheTerm() {
        assertEquals("slow tide", SearchQuery("  slow   tide ").normalized)
        assertEquals(TrackSearchFilter.All, SearchQuery("tide").filter)

        assertThrows(IllegalArgumentException::class.java) { SearchQuery("   ") }
        assertThrows(IllegalArgumentException::class.java) { SearchQuery("x".repeat(SearchQuery.MAX_LENGTH + 1)) }
        assertEquals(SearchQuery.MAX_LENGTH, SearchQuery("x".repeat(SearchQuery.MAX_LENGTH)).text.length)
    }

    @Test
    fun pageReportsMoreOnlyWhenACursorExists() {
        val next = PageKey("cursor-2")
        val page = Page(items = listOf("a", "b"), nextKey = next, total = 10)
        assertTrue(page.hasMore)
        assertFalse(Page(items = listOf("a"), total = 1).hasMore)

        assertThrows(IllegalArgumentException::class.java) { Page(items = listOf("a", "b"), total = 1) }
        assertThrows(IllegalArgumentException::class.java) { PageKey(" ") }
    }

    @Test
    fun externalTrackValidatesAndExposesItsProviderIdentity() {
        val track = ExternalTrack(
            providerId = ProviderId.Audius,
            externalId = "D7KyD",
            title = "Slow Tide",
            artists = listOf("Kaori Ito"),
            durationMs = 190_000
        )
        assertEquals(SourceIdentity(ProviderId.Audius, "D7KyD"), track.identity)
        assertEquals("Kaori Ito", track.primaryArtist)
        assertEquals(SourceAvailability.Unknown, track.availability)

        assertThrows(IllegalArgumentException::class.java) { track.copy(externalId = " ") }
        assertThrows(IllegalArgumentException::class.java) { track.copy(title = "") }
        assertThrows(IllegalArgumentException::class.java) { track.copy(artists = listOf(" ")) }
        assertThrows(IllegalArgumentException::class.java) { track.copy(durationMs = 0) }
        assertNull(ExternalTrack(ProviderId.Audius, "x1", "Untitled", emptyList()).primaryArtist)
    }
}
