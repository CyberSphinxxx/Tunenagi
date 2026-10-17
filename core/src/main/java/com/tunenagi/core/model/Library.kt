package com.tunenagi.core.model

import java.time.Instant

/**
 * Library-side domain models — owned.
 *
 * These are the types the repositories (TUN-0105, TUN-0106) hand to the UI. They are kept
 * deliberately plain: no Room types, no Android types, so a repository can be tested against an
 * in-memory database and the UI can hold them without leaking the storage layer upward.
 */

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isSystem: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Playlist name must not be blank" }
        require(!updatedAt.isBefore(createdAt)) { "Playlist updatedAt must not precede createdAt" }
    }
}

/**
 * One occurrence of a track inside a playlist. [position] is an integer because that is what drag
 * reordering produces; [addedAt] is the deterministic tie-breaker.
 */
data class PlaylistEntry(
    val id: PlaylistEntryId,
    val playlistId: PlaylistId,
    val trackId: TrackId,
    val position: Int,
    val addedAt: Instant,
    val sourceLockId: TrackSourceId? = null
) {
    init {
        require(position >= 0) { "PlaylistEntry position must not be negative, but was $position" }
    }
}

data class Favorite(val trackId: TrackId, val addedAt: Instant)

/** Cached lyrics with their provenance, so the UI can always say where the text came from. */
data class LyricsRecord(
    val trackId: TrackId,
    val provider: ProviderId,
    val language: String? = null,
    val syncedLrc: String? = null,
    val plainText: String? = null,
    val provenanceUrl: String? = null,
    val fetchedAt: Instant,
    val etag: String? = null,
    val matchConfidence: Double? = null
) {
    init {
        require(syncedLrc != null || plainText != null) {
            "A cached lyrics record must carry synced or plain text"
        }
        require(matchConfidence == null || matchConfidence in 0.0..1.0) {
            "matchConfidence must be within 0..1, but was $matchConfidence"
        }
    }

    val isSynced: Boolean get() = syncedLrc != null
}

/** A metadata match that has been accepted for a track. A manual confirmation outranks scoring. */
data class MetadataMatch(
    val trackId: TrackId,
    val provider: ProviderId,
    val externalId: String,
    val score: Double,
    val manuallyConfirmed: Boolean = false,
    val confirmedAt: Instant
) {
    init {
        require(externalId.isNotBlank()) { "MetadataMatch externalId must not be blank" }
        require(score in 0.0..1.0) { "MetadataMatch score must be within 0..1, but was $score" }
    }
}

/** An installed declarative plugin and the domains the user approved for it. */
data class PluginInstallation(
    val id: String,
    val version: String,
    val manifestHash: String,
    val enabled: Boolean,
    val approvedDomains: List<String>,
    val installOrigin: String,
    val installedAt: Instant,
    val manifestJson: String
) {
    init {
        require(id.isNotBlank()) { "Plugin installation id must not be blank" }
        require(manifestHash.isNotBlank()) { "Plugin manifest hash must not be blank" }
    }
}
