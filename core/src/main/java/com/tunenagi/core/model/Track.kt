package com.tunenagi.core.model

import java.util.Locale

/**
 * Sorting keys — owned.
 *
 * `SPEC/DATA_MODEL.md` stores `sortTitle`/`sortName` alongside the display text, so a library list
 * can be ordered without recomputing a normalisation in a SQL expression. The rules are fixed here
 * once, as pure functions, because two callers deriving them differently is exactly how a list
 * ends up sorted inconsistently between the library screen and the search screen.
 */
object TitleSort {

    /**
     * Articles skipped when they lead a title. Deliberately a small, explicit list: guessing at
     * every language's articles is how "The The" ends up under "T".
     */
    private val LEADING_ARTICLES = setOf("a", "an", "the", "el", "la", "le", "les", "los", "las", "der", "die", "das")

    /** Characters that carry no sorting meaning; letters, digits and spaces survive. */
    private val IGNORED = Regex("[\\p{Punct}\\p{S}]")

    /**
     * Deterministic sort key for a title or artist name.
     *
     * Lowercased with [Locale.ROOT] so the result cannot depend on the device locale, punctuation
     * dropped, whitespace collapsed, and a leading article removed **only when something follows
     * it** — a track actually named "The" must keep its name rather than sorting under nothing.
     */
    fun key(display: String): String {
        val collapsed = display.trim().replace(Regex("\\s+"), " ")
        val stripped = IGNORED.replace(collapsed, "").replace(Regex("\\s+"), " ").trim()
        val lowered = stripped.lowercase(Locale.ROOT)
        val space = lowered.indexOf(' ')
        if (space > 0 && lowered.substring(0, space) in LEADING_ARTICLES) {
            val rest = lowered.substring(space + 1).trim()
            return rest.ifEmpty { lowered }
        }
        return lowered
    }
}

/** One recording in the local library. Provider-independent by construction. */
data class Track(
    val id: TrackId,
    val title: String,
    val sortTitle: String = TitleSort.key(title),
    val durationMs: Long? = null,
    val albumId: AlbumId? = null,
    val isrc: String? = null,
    val mbid: String? = null,
    val artworkRef: String? = null,
    val artistHandle: String? = null,
    val permalink: String? = null,
    val artworkUrl: String? = null,
    val genre: String? = null
) {
    init {
        require(title.isNotBlank()) { "Track title must not be blank" }
        require(durationMs == null || durationMs > 0) {
            "Track duration must be positive when known, but was $durationMs"
        }
    }
}

data class Artist(
    val id: ArtistId,
    val name: String,
    val sortName: String = TitleSort.key(name),
    val mbid: String? = null,
    val handle: String? = null
) {
    init {
        require(name.isNotBlank()) { "Artist name must not be blank" }
    }
}

data class Album(
    val id: AlbumId,
    val title: String,
    val sortTitle: String = TitleSort.key(title),
    val albumArtistId: ArtistId? = null,
    val year: Int? = null,
    val mbid: String? = null,
    val artworkRef: String? = null
) {
    init {
        require(title.isNotBlank()) { "Album title must not be blank" }
        require(year == null || year in 1850..2200) { "Album year $year is not plausible" }
    }
}

/** How a credited artist participated. `SPEC/DATA_MODEL.md` stores the role, not only the name. */
enum class ArtistRole {
    Primary,
    Featured,
    Album,
    Composer,
    Remixer;

    companion object {
        /** Unknown persisted values are loud rather than silently mapped to [Primary]. */
        fun fromPersisted(raw: String): ArtistRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown artist role '$raw'")
    }
}

/** A credit row: which artist, in what order, in what role. */
data class TrackArtist(
    val trackId: TrackId,
    val artistId: ArtistId,
    val order: Int,
    val role: ArtistRole
) {
    init {
        require(order >= 0) { "Credit order must not be negative, but was $order" }
    }
}
