package com.tunenagi.core.library

import com.tunenagi.core.database.TunenagiDatabase
import com.tunenagi.core.database.TunenagiMappers
import com.tunenagi.core.model.ExternalTrack
import com.tunenagi.core.model.MetadataMatch
import com.tunenagi.core.model.ProviderId
import com.tunenagi.core.model.TrackId
import com.tunenagi.core.model.TrackSource
import com.tunenagi.core.model.TrackSourceId
import java.time.Instant

/**
 * Persisted source matching — owned.
 *
 * The scoring in [SourceMatcher] is a pure function, deliberately, so it can be tested exhaustively.
 * Everything that has to *survive* lives here: which match the user confirmed, and which source they
 * locked. `SPEC/DATA_MODEL.md`: "manual overrides trump heuristics", so a lock is consulted before
 * any score, and confirming a match is a write, not a UI state.
 */
interface SourceMatchRepository {

    /** The confirmed match for a track, if the user (or an earlier import) made one. */
    suspend fun confirmedMatch(trackId: TrackId): MetadataMatch?

    /** Records a confirmation. [manuallyConfirmed] is true because a user asked for it. */
    suspend fun confirmMatch(
        trackId: TrackId,
        providerId: ProviderId,
        externalId: String,
        score: Double,
        at: Instant
    ): MetadataMatch

    suspend fun forgetMatch(trackId: TrackId)

    /** Locks (or unlocks) one stored source, which is what makes a choice stick. */
    suspend fun setSourceLocked(sourceId: TrackSourceId, locked: Boolean)

    suspend fun lockedSource(trackId: TrackId): TrackSource?

    /**
     * Which source playback should use: a locked one if the user locked one, else the preferred one,
     * else nothing. A provider failure therefore cannot silently move a user's choice.
     */
    suspend fun preferredSource(trackId: TrackId): TrackSource?

    /**
     * Scores candidates against a track, honouring any stored confirmation. The track id is part of
     * the signature on purpose: resolving *without* the stored lock is the bug this repository exists
     * to prevent, so there is no overload that lets a caller skip it.
     */
    suspend fun resolveForTrack(
        trackId: TrackId,
        query: SourceMatcher.Query,
        candidates: List<ExternalTrack>
    ): SourceMatcher.Resolution
}

class RoomSourceMatchRepository(
    private val database: TunenagiDatabase,
    private val clock: () -> Instant
) : SourceMatchRepository {

    override suspend fun confirmedMatch(trackId: TrackId): MetadataMatch? =
        database.metadataMatchDao().forTrack(trackId.value)?.let(TunenagiMappers::toMetadataMatch)

    override suspend fun confirmMatch(
        trackId: TrackId,
        providerId: ProviderId,
        externalId: String,
        score: Double,
        at: Instant
    ): MetadataMatch {
        val match = MetadataMatch(
            trackId = trackId,
            provider = providerId,
            externalId = externalId,
            score = score.coerceIn(0.0, 1.0),
            manuallyConfirmed = true,
            confirmedAt = at
        )
        database.metadataMatchDao().upsert(TunenagiMappers.toMetadataMatchEntity(match))

        // If the chosen provider row is already stored as a source for this track, lock it too, so
        // the choice holds even if a later scan finds something that scores higher.
        database.sourceDao()
            .byIdentity(providerId.value, externalId, variant = "")
            ?.takeIf { it.trackId == trackId.value }
            ?.let { database.sourceDao().setLocked(it.id, true) }
        return match
    }

    override suspend fun forgetMatch(trackId: TrackId) {
        database.metadataMatchDao().delete(trackId.value)
    }

    override suspend fun setSourceLocked(sourceId: TrackSourceId, locked: Boolean) {
        require(database.sourceDao().byId(sourceId.value) != null) {
            "Cannot lock a source that does not exist: $sourceId"
        }
        database.sourceDao().setLocked(sourceId.value, locked)
    }

    override suspend fun lockedSource(trackId: TrackId): TrackSource? =
        database.sourceDao().lockedFor(trackId.value).firstOrNull()?.let(TunenagiMappers::toTrackSource)

    override suspend fun preferredSource(trackId: TrackId): TrackSource? {
        val confirmed = confirmedMatch(trackId)
        val sources = database.sourceDao().forTrack(trackId.value).map(TunenagiMappers::toTrackSource)
        sources.firstOrNull { it.locked }?.let { return it }
        if (confirmed != null) {
            sources.firstOrNull {
                it.providerId == confirmed.provider && it.externalId == confirmed.externalId
            }?.let { return it }
        }
        return sources.firstOrNull { it.preferred }
    }

    override suspend fun resolveForTrack(
        trackId: TrackId,
        query: SourceMatcher.Query,
        candidates: List<ExternalTrack>
    ): SourceMatcher.Resolution {
        // The confirmation is carried into scoring, not applied afterwards, so a caller cannot
        // accidentally treat a locked match as a fresh suggestion.
        val confirmed = confirmedMatch(trackId)?.let {
            SourceMatcher.Confirmed(it.provider, it.externalId, it.manuallyConfirmed)
        }
        return SourceMatcher.resolve(query, candidates, confirmed)
    }

    fun now(): Instant = clock()
}
