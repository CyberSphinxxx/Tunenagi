package com.tunenagi.core.provider.audius

import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.ProviderResult
import com.tunenagi.core.model.SourceAvailability
import com.tunenagi.core.model.SourceIdentity
import com.tunenagi.core.model.TitleSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusNormalizationTest {

    private fun candidate(
        providerId: String = "D7KyD",
        title: String? = "Slow Tide",
        artist: String? = "Kaori Ito",
        artistHandle: String? = "kaori",
        durationMs: Long? = 190_000L,
        genre: String? = "Electronic",
        artworkUrl: String? = "https://images.audius.co/480.jpg",
        permalink: String? = "/kaori/slow-tide",
        isStreamable: Boolean = true,
        isDeleted: Boolean = false
    ): AudiusTrack = AudiusTrack(
        id = providerId,
        title = title,
        artist = artist,
        artistHandle = artistHandle,
        durationMs = durationMs,
        genre = genre,
        artworkUrl = artworkUrl,
        permalink = permalink,
        isStreamable = isStreamable,
        isDeleted = isDeleted
    )

    private fun success(track: AudiusTrack): ExternalTrack =
        (AudiusNormalization.normalize(track) as ProviderResult.Success).value

    private fun page(
        vararg tracks: AudiusTrack,
        received: Int = tracks.size,
        skipped: Int = 0,
        nextOffset: Int? = null
    ) = AudiusPage(
        tracks = tracks.toList(),
        nextOffset = nextOffset,
        received = received,
        skipped = skipped
    )

    // ---- provider-neutral mapping --------------------------------------------------------------

    @Test
    fun aPlayableTrackBecomesExternalTrackWithProviderIdentity() {
        val providerTrack = candidate()
        val external = success(providerTrack)

        assertEquals(ProviderId.Audius, external.providerId)
        assertEquals(providerTrack.id, external.externalId)
        assertEquals(SourceIdentity(ProviderId.Audius, "D7KyD"), external.identity)
        assertEquals("Slow Tide", external.title)
        assertEquals(190_000L, external.durationMs)
        assertEquals("https://images.audius.co/480.jpg", external.artworkUrl)
        assertEquals(listOf("Kaori Ito"), external.artists)
        assertEquals("Kaori Ito", external.primaryArtist)
    }

    @Test
    fun aPlayableTrackIsAdvertisedAvailableAndCreditsTheProvider() {
        val external = success(candidate())

        assertEquals(SourceAvailability.Available, external.availability)
        assertTrue(external.availability.isPlayable)
        assertEquals("Audius", external.providerAttribution)
    }

    @Test
    fun theNormalizationDoesNotLeakAudiusFieldNamesOrUnits() {
        val external = success(candidate(durationMs = 190_000L))

        // The domain model has milliseconds and neutral names; nothing here is provider-shaped.
        assertEquals(190_000L, external.durationMs)
        assertEquals("Slow Tide", external.title)
    }

    // ---- entitlement ---------------------------------------------------------------------------

    @Test
    fun aDeletedTrackIsRefusedAsGoneRatherThanMapped() {
        val result = AudiusNormalization.normalize(candidate(isDeleted = true, isStreamable = true))

        val failure = result as ProviderResult.Failure
        assertTrue(failure.error is ProviderError.NotFound)
        assertFalse(failure.error.retryable)
        assertEquals(
            "This track is no longer available at the source.",
            failure.error.userMessage
        )
    }

    @Test
    fun deletedTakesPriorityOverUnstreamable() {
        val result = AudiusNormalization.normalize(candidate(isDeleted = true, isStreamable = false))

        val failure = result as ProviderResult.Failure
        assertTrue(failure.error is ProviderError.NotFound)
    }

    @Test
    fun aTrackTheProviderDoesNotStreamIsKeptAsRestrictedRatherThanHidden() {
        val providerTrack = candidate(isStreamable = false, isDeleted = false)
        val external = success(providerTrack)

        assertEquals(SourceAvailability.Restricted, external.availability)
        assertFalse(external.availability.isPlayable)
        // The row is still attributable and identifiable, which is what a disabled result needs.
        assertEquals(providerTrack.id, external.externalId)
        assertEquals("Slow Tide", external.title)
        assertEquals(listOf("Kaori Ito"), external.artists)
    }

    @Test
    fun aRowWithoutAnAddressableIdIsRefusedAsMalformed() {
        for (blank in listOf("", "   ")) {
            val result = AudiusNormalization.normalize(candidate(providerId = blank))

            val failure = result as ProviderResult.Failure
            assertTrue(failure.error is ProviderError.MalformedPayload)
        }
    }

    // ---- attribution and honest gaps -----------------------------------------------------------

    @Test
    fun aTrackWithNoTitleGetsAnExplicitPlaceholderNotAFabricatedName() {
        val external = success(candidate(title = null))

        assertEquals("Untitled", external.title)
        assertEquals("untitled", TitleSort.key(external.title))
    }

    @Test
    fun aBlankTitleIsTreatedAsMissing() {
        assertEquals("Untitled", success(candidate(title = "   ")).title)
    }

    @Test
    fun aHandleAloneIsCreditedAsTheHandleNotPromotedToAName() {
        val external = success(candidate(artist = null, artistHandle = "anonymous"))

        assertEquals(listOf("@anonymous"), external.artists)
        assertEquals("@anonymous", external.primaryArtist)
    }

    @Test
    fun aRowWithNoArtistAtAllStaysUnattributedRatherThanInvented() {
        val external = success(candidate(artist = null, artistHandle = null))

        assertTrue(external.artists.isEmpty())
        assertNull(external.primaryArtist)
    }

    @Test
    fun aBlankArtistNameIsTreatedAsMissing() {
        val external = success(candidate(artist = "  ", artistHandle = "kaori"))

        assertEquals(listOf("@kaori"), external.artists)
    }

    @Test
    fun missingArtworkStaysMissingRatherThanInvented() {
        assertNull(success(candidate(artworkUrl = null)).artworkUrl)
        assertNull(success(candidate(artworkUrl = "  ")).artworkUrl)
    }

    @Test
    fun aZeroDurationIsDroppedBecauseItCannotDescribeATrack() {
        assertNull(success(candidate(durationMs = 0L)).durationMs)
        assertNull(success(candidate(durationMs = -1L)).durationMs)
    }

    // ---- pages ---------------------------------------------------------------------------------

    @Test
    fun aPageOfPlayableTracksIsNormalizedWithDiagnostics() {
        val normalized = AudiusNormalization.normalizePage(
            page(candidate(providerId = "a"), candidate(providerId = "b"), nextOffset = 2)
        )

        assertEquals(2, normalized.items.size)
        assertEquals(2, normalized.received)
        assertEquals(0, normalized.skipped)
        assertEquals(2, normalized.nextOffset)
        assertEquals(2, normalized.playableCount)
        assertFalse(normalized.isEmpty)
        assertEquals(listOf("a", "b"), normalized.items.map { it.externalId })
    }

    @Test
    fun aPageKeepsRestrictedRowsVisibleAndDropsOnlyDeletedOnes() {
        val normalized = AudiusNormalization.normalizePage(
            page(
                candidate(providerId = "ok"),
                candidate(providerId = "gated", isStreamable = false),
                candidate(providerId = "gone", isDeleted = true)
            )
        )

        assertEquals(listOf("ok", "gated"), normalized.items.map { it.externalId })
        assertEquals(SourceAvailability.Available, normalized.items[0].availability)
        assertEquals(SourceAvailability.Restricted, normalized.items[1].availability)
        assertEquals(3, normalized.received)
        assertEquals(1, normalized.skipped)
        assertEquals(1, normalized.playableCount)
    }

    @Test
    fun parserSkipsAndNormalizationRefusalsAreBothCounted() {
        val normalized = AudiusNormalization.normalizePage(
            page(
                candidate(providerId = "ok"),
                candidate(providerId = "gone", isDeleted = true),
                received = 3,
                skipped = 1
            )
        )

        assertEquals(1, normalized.items.size)
        assertEquals(3, normalized.received)
        assertEquals(2, normalized.skipped)
    }

    @Test
    fun aRowWithoutAnAddressableIdIsSkippedRatherThanKept() {
        val normalized = AudiusNormalization.normalizePage(
            page(
                candidate(providerId = "", title = "No id"),
                candidate(providerId = "ok", title = "Fine")
            )
        )

        assertEquals(1, normalized.items.size)
        assertEquals("ok", normalized.items.single().externalId)
        assertEquals("Fine", normalized.items.single().title)
        assertEquals(2, normalized.received)
        assertEquals(1, normalized.skipped)
    }

    @Test
    fun anEmptyPageStaysEmptyAndEndsPagination() {
        val normalized = AudiusNormalization.normalizePage(page())

        assertTrue(normalized.isEmpty)
        assertTrue(normalized.items.isEmpty())
        assertEquals(0, normalized.received)
        assertEquals(0, normalized.skipped)
        assertEquals(0, normalized.playableCount)
        assertNull(normalized.nextOffset)
    }
}
