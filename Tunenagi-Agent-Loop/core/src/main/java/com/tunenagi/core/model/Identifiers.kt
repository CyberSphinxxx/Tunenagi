package com.tunenagi.core.model

import java.util.UUID

/**
 * Identifiers — owned.
 *
 * `SPEC/DATA_MODEL.md` fixes two rules, and the type system enforces both instead of trusting a
 * reviewer to spot a violation:
 *
 * 1. A Tunenagi id is a UUID the app generated. A provider's own id (an Audius id, an MBID, a
 *    MusicBrainz recording id) is never a primary key.
 * 2. `ProviderId` is a namespaced lowercase identifier — `builtin.local`, `builtin.audius`,
 *    `external.example`.
 *
 * Because they are distinct types, passing a provider id where a Tunenagi id belongs is a
 * compile error rather than a data-model bug discovered in a later phase.
 */

private const val MAX_PROVIDER_ID_LENGTH = 64

/** `builtin.audius`, `external.example` — lowercase words joined by dots or dashes, at least two. */
private val PROVIDER_ID_PATTERN = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")

/**
 * `UUID.fromString` is far more permissive than the canonical form: it accepts `"1-1-1-1-1"` and
 * pads the missing groups. Round-tripping through the parsed value is what actually rejects a
 * short or malformed id, and it is the check the failure-path tests exercise.
 */
private fun canonicalUuid(raw: String, label: String): String {
    val parsed = runCatching { UUID.fromString(raw) }.getOrNull()
    val canonical = parsed?.toString()
    require(canonical != null && canonical == raw.lowercase()) {
        "$label must be a canonical UUID, but was '$raw'"
    }
    return canonical
}

/** A namespaced, stable identifier for a provider (`builtin.local`, `builtin.audius`). */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.length in 3..MAX_PROVIDER_ID_LENGTH && PROVIDER_ID_PATTERN.matches(value)) {
            "ProviderId must be a namespaced lowercase identifier such as 'builtin.audius', but was '$value'"
        }
    }

    /** Built-in adapters ship with the app; `external.*` ids belong to installed plugins. */
    val isBuiltin: Boolean get() = value.startsWith("builtin.")

    val isExternal: Boolean get() = value.startsWith("external.")

    override fun toString(): String = value

    companion object {
        val Local = ProviderId("builtin.local")
        val Audius = ProviderId("builtin.audius")
    }
}

/** Identifies one [Track] in the local library. */
@JvmInline
value class TrackId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): TrackId = TrackId(UUID.randomUUID().toString())

        /** Throws [IllegalArgumentException] when [raw] is not a canonical UUID. */
        fun parse(raw: String): TrackId = TrackId(canonicalUuid(raw, "TrackId"))
    }
}

/** Identifies one [TrackSource] — one playable version of a track at one provider. */
@JvmInline
value class TrackSourceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): TrackSourceId = TrackSourceId(UUID.randomUUID().toString())

        fun parse(raw: String): TrackSourceId = TrackSourceId(canonicalUuid(raw, "TrackSourceId"))
    }
}

@JvmInline
value class ArtistId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): ArtistId = ArtistId(UUID.randomUUID().toString())

        fun parse(raw: String): ArtistId = ArtistId(canonicalUuid(raw, "ArtistId"))
    }
}

@JvmInline
value class AlbumId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): AlbumId = AlbumId(UUID.randomUUID().toString())

        fun parse(raw: String): AlbumId = AlbumId(canonicalUuid(raw, "AlbumId"))
    }
}

@JvmInline
value class PlaylistId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): PlaylistId = PlaylistId(UUID.randomUUID().toString())

        fun parse(raw: String): PlaylistId = PlaylistId(canonicalUuid(raw, "PlaylistId"))
    }
}

/**
 * Identifies one *occurrence* in a playlist, so the same track may appear twice and still be
 * reordered or removed unambiguously.
 */
@JvmInline
value class PlaylistEntryId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): PlaylistEntryId = PlaylistEntryId(UUID.randomUUID().toString())

        fun parse(raw: String): PlaylistEntryId = PlaylistEntryId(canonicalUuid(raw, "PlaylistEntryId"))
    }
}

/** Identifies one occurrence in the playback queue (duplicates are allowed, so ids must differ). */
@JvmInline
value class QueueItemId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): QueueItemId = QueueItemId(UUID.randomUUID().toString())

        fun parse(raw: String): QueueItemId = QueueItemId(canonicalUuid(raw, "QueueItemId"))
    }
}
