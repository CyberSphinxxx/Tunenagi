package com.tunenagi.core.model

import java.time.Instant

/**
 * One playable version of a [Track] at one provider — owned.
 *
 * `SPEC/PROVIDERS.md`: a metadata record describes a track, a `TrackSource` describes a
 * particular playable version. It may disappear (file removed, permission revoked, creator
 * restricted the track) without deleting the track, its playlists or its history, which is why
 * availability is a field on the source rather than a delete of the row.
 */
data class TrackSource(
    val id: TrackSourceId,
    val trackId: TrackId,
    val providerId: ProviderId,
    val externalId: String,
    val localUri: String? = null,
    val sourceVariant: String? = null,
    val contentType: String? = null,
    val availability: SourceAvailability = SourceAvailability.Unknown,
    val lastCheckedAt: Instant? = null,
    val preferred: Boolean = false,
    val locked: Boolean = false
) {
    init {
        require(externalId.isNotBlank()) { "TrackSource externalId must not be blank" }
        require(providerId != ProviderId.Local || localUri != null) {
            "A local TrackSource must carry the uri it points at"
        }
    }

    /**
     * `(providerId, externalId, sourceVariant)` is the uniqueness contract the database enforces.
     * Exposing it as a value means the rule can be tested without a database, and a duplicate can
     * be compared before the insert that would violate the unique index.
     */
    val identity: SourceIdentity
        get() = SourceIdentity(providerId, externalId, sourceVariant)
}

/** The tuple that makes a source unique within one provider. */
data class SourceIdentity(
    val providerId: ProviderId,
    val externalId: String,
    val sourceVariant: String? = null
)

/**
 * What a resolve attempt last found. `PermissionRequired` and `StorageGone` are separate from
 * `Unavailable` because they are recoverable by a user action and must be reported differently.
 */
enum class SourceAvailability {
    Unknown,
    Available,
    Unavailable,
    Restricted,
    PermissionRequired,
    StorageGone;

    val isPlayable: Boolean get() = this == Available

    companion object {
        fun fromPersisted(raw: String): SourceAvailability =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown source availability '$raw'")
    }
}
