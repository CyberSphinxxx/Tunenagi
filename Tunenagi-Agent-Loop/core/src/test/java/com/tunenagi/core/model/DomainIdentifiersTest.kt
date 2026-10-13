package com.tunenagi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure-path oriented tests for the TUN-0101 domain surface. Each `assertThrows` is a rule from
 * `SPEC/DATA_MODEL.md` that would otherwise be a convention someone could break silently.
 */
class DomainIdentifiersTest {

    @Test
    fun providerIdAcceptsNamespacedIdentifiers() {
        assertEquals("builtin.local", ProviderId("builtin.local").value)
        assertEquals("builtin.audius", ProviderId("builtin.audius").value)
        assertEquals("external.example", ProviderId("external.example").value)
        assertTrue(ProviderId.Audius.isBuiltin)
        assertTrue(ProviderId("external.some-plugin").isExternal)
    }

    /** A bare name is ambiguous between two providers, so it is rejected rather than guessed at. */
    @Test
    fun providerIdRejectsMalformedNamespaces() {
        listOf("", "audius", "Audius", "builtin.", ".local", "audius..x", "audius x", "audius/1").forEach { raw ->
            assertThrows("'$raw' should be rejected", IllegalArgumentException::class.java) { ProviderId(raw) }
        }
    }

    @Test
    fun providerIdHasABoundedLength() {
        val long = "builtin." + "a".repeat(80)
        assertThrows(IllegalArgumentException::class.java) { ProviderId(long) }
    }

    /** The trap this test exists for: `UUID.fromString` happily accepts `1-1-1-1-1` and pads it. */
    @Test
    fun trackIdRejectsAnythingThatIsNotACanonicalUuid() {
        listOf("", "not-a-uuid", "audius:12345", "1-1-1-1-1", "123e4567-e89b-12d3-a456-42661417400").forEach { raw ->
            assertThrows("'$raw' should be rejected", IllegalArgumentException::class.java) {
                TrackId.parse(raw)
            }
        }
    }

    @Test
    fun generatedIdsAreDistinctAndRoundTrip() {
        val first = TrackId.random()
        val second = TrackId.random()
        assertNotEquals(first, second)
        assertEquals(first, TrackId.parse(first.value))
        assertEquals(second.value, TrackId.parse(second.value.uppercase()).value)
    }

    @Test
    fun everyEntityIdRejectsAProviderId() {
        listOf(
            { TrackSourceId.parse("audius:1") },
            { ArtistId.parse("mbid:abc") },
            { AlbumId.parse("audius:album") },
            { PlaylistId.parse("favorites") },
            { PlaylistEntryId.parse("entry-1") },
            { QueueItemId.parse("queue-1") }
        ).forEach { attempt ->
            assertThrows(IllegalArgumentException::class.java) { attempt() }
        }
    }

    @Test
    fun sortKeysDemoteLeadingArticles() {
        assertEquals("slow tide", TitleSort.key("The Slow Tide"))
        assertEquals("song", TitleSort.key("A Song"))
        assertEquals("niño", TitleSort.key("El Niño"))
        assertTrue("a leading article must sort titles together", TitleSort.key("The Beatles") < TitleSort.key("Zoe"))
    }

    /** An article with nothing after it is the title, not a prefix to delete. */
    @Test
    fun sortKeyKeepsATitleThatIsOnlyAnArticle() {
        assertEquals("the", TitleSort.key("The"))
        assertEquals("a", TitleSort.key(" A "))
    }

    @Test
    fun sortKeyIsDeterministicAndUnicodeSafe() {
        assertEquals("multiple spaces", TitleSort.key("  Multiple   Spaces  "))
        // Case folding is locale-independent and lossless: Latin-1/Greek/Cyrillic letters keep
        // their identity (Æ -> æ) rather than being transliterated, so two different titles can
        // never collapse into one sort key.
        assertEquals("æther reprise", TitleSort.key("Æther (Reprise)"))
        assertEquals("øya", TitleSort.key("ØYA"))
        assertEquals("粋な夜", TitleSort.key("粋な夜"))
        assertEquals(TitleSort.key("The Slow Tide"), TitleSort.key("the  slow  tide!"))
    }

    @Test
    fun trackRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId.random(), "   ") }
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId.random(), "Tide", durationMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { Track(TrackId.random(), "Tide", durationMs = -5) }

        val track = Track(TrackId.random(), "The Slow Tide", durationMs = 190_000)
        assertEquals("slow tide", track.sortTitle)
    }

    @Test
    fun albumYearIsBoundedAndArtistRoleIsATypedValue() {
        val albumId = AlbumId.random()
        assertThrows(IllegalArgumentException::class.java) { Album(albumId, "Dawn", year = 1849) }
        assertThrows(IllegalArgumentException::class.java) { Album(albumId, "Dawn", year = 2201) }
        assertEquals(2200, Album(albumId, "Dawn", year = 2200).year)

        assertEquals(ArtistRole.Featured, ArtistRole.fromPersisted("featured"))
        assertThrows(IllegalArgumentException::class.java) { ArtistRole.fromPersisted("producer") }
        assertThrows(IllegalArgumentException::class.java) {
            TrackArtist(TrackId.random(), ArtistId.random(), order = -1, role = ArtistRole.Primary)
        }
    }

    @Test
    fun sourceIdentityIsTheProviderTuple() {
        val trackId = TrackId.random()
        val base = TrackSource(
            id = TrackSourceId.random(),
            trackId = trackId,
            providerId = ProviderId.Audius,
            externalId = "D7KyD",
            sourceVariant = "stream"
        )
        val sameIdentity = base.copy(id = TrackSourceId.random())
        val otherVariant = base.copy(sourceVariant = "download")
        val otherProvider = base.copy(providerId = ProviderId.Local, localUri = "content://audio/1")

        assertEquals(base.identity, sameIdentity.identity)
        assertNotEquals(base.identity, otherVariant.identity)
        assertNotEquals(base.identity, otherProvider.identity)
        assertEquals(SourceIdentity(ProviderId.Audius, "D7KyD", "stream"), base.identity)
    }

    @Test
    fun trackSourceRejectsBlankExternalIdAndALocalSourceWithoutAUri() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackSource(TrackSourceId.random(), TrackId.random(), ProviderId.Audius, "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackSource(TrackSourceId.random(), TrackId.random(), ProviderId.Local, "1", localUri = null)
        }
    }

    @Test
    fun availabilityDistinguishesRecoverableStatesAndRejectsUnknownOnes() {
        assertTrue(SourceAvailability.Available.isPlayable)
        assertTrue(!SourceAvailability.StorageGone.isPlayable)
        assertEquals(SourceAvailability.PermissionRequired, SourceAvailability.fromPersisted("permissionrequired"))
        assertThrows(IllegalArgumentException::class.java) { SourceAvailability.fromPersisted("maybe") }
    }
}
