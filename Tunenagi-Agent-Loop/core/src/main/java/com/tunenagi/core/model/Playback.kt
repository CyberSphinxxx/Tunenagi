package com.tunenagi.core.model

import java.time.Instant

/**
 * Playback domain types — owned.
 *
 * A resolved reference is deliberately *not* stored as the track's identity: `SPEC/DATA_MODEL.md`
 * says an expiring stream URL is never durable identity, so [PlayableReference] carries its own
 * expiry and is produced late, close to playback.
 */

/** Repeat behaviour. `All` wraps the queue, `One` repeats the current item, `Off` stops at the end. */
enum class RepeatMode {
    Off,
    All,
    One;

    /** The queue continues past the last item only in [All]. */
    val wrapsQueue: Boolean get() = this == All

    /**
     * A stored value that is not one of these is corrupt state, not a reason to silently fall back
     * to [Off]: falling back would lose the user's setting without telling anyone.
     */
    companion object {
        fun fromPersisted(raw: String): RepeatMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown repeat mode '$raw'")

        /** Cycle order used by the now-playing control: Off -> All -> One -> Off. */
        fun nextAfter(current: RepeatMode): RepeatMode = when (current) {
            Off -> All
            All -> One
            One -> Off
        }
    }
}

/** Where a queued item is inserted. `Next` is the "Play Next" action from `SPEC/UX_DESIGN.md`. */
enum class QueueAddPosition { Next, End }

/**
 * A URL or local uri that can be handed to the player right now.
 *
 * [expiresAt] is optional because a local file does not expire; a remote URL that does not declare
 * one is still checked immediately before playback rather than trusted.
 */
data class PlayableReference(
    val uri: String,
    val mimeType: String? = null,
    val sourceId: TrackSourceId,
    val expiresAt: Instant? = null
) {
    init {
        require(uri.isNotBlank()) { "PlayableReference uri must not be blank" }
    }

    fun isExpiredAt(now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)
}

/** What a resolver produced: a playable reference, or the typed reason it could not. */
sealed interface ResolveResult {
    data class Resolved(val reference: PlayableReference) : ResolveResult

    data class Failed(val error: ProviderError) : ResolveResult

    /** The source exists but needs a user action first, such as re-granting access. */
    data class NeedsUserAction(val availability: SourceAvailability) : ResolveResult
}
