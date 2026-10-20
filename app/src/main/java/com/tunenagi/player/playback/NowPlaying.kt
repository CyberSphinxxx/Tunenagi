package com.tunenagi.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.tunenagi.core.playback.PlaybackFailure
import com.tunenagi.player.ui.shell.MiniPlayerState

/**
 * What the app currently considers "playing" — owned.
 *
 * It is a snapshot taken from the session's player through the `MediaController`, never an
 * intention: no field here is set by the UI. `SPEC/AUDIO_ENGINE.md` warns about a fake `isPlaying`
 * flag, and this type is the reason there is not one — [isPlaying] is copied from
 * `Player.isPlaying`, which is the platform's own answer (and is `false` while buffering, which is
 * the correct, non-optimistic report).
 */
data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    /** The player is waiting for data; the control stays enabled but the bar says so. */
    val isBuffering: Boolean = false,
    /** Why playback stopped, when it did. Read from the player, never from an intention. */
    val failure: PlaybackFailure? = null
)

/**
 * Reads the current item off a player, or `null` when there is nothing to show.
 *
 * Takes the `Player` interface rather than a `MediaController` so the mapping can be tested against
 * a real `ExoPlayer` on the JVM without a session, a service or a device (ADR-008).
 *
 * A missing title falls back to the media id instead of an empty string: showing the id is ugly,
 * but an empty mini-player looks like a bug and a placeholder would hide a metadata problem.
 */
fun Player.nowPlayingOrNull(): NowPlaying? {
    val item = currentMediaItem ?: return null
    val metadata = item.mediaMetadata
    return NowPlaying(
        mediaId = item.mediaId,
        title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId,
        artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
        isPlaying = isPlaying,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        failure = playerError?.let { PlaybackFailure.from(it) }
    )
}

/**
 * The typed failure the player is currently stopped on, or `null`.
 *
 * Extracted from [nowPlayingOrNull] so the classification can be tested with a constructed
 * exception: a real player cannot be asked to fail on demand in a JVM test, and fabricating a
 * `NowPlaying` snapshot would test nothing. The player error itself is read from Media3, and this
 * only translates it.
 */
fun PlaybackException?.toPlaybackFailure(): PlaybackFailure? =
    this?.let { PlaybackFailure.from(it) }

/** Renders the snapshot in the shell's mini-player slot. */
fun NowPlaying.toMiniPlayerState(): MiniPlayerState = MiniPlayerState.Track(
    title = title,
    artist = artist.orEmpty(),
    isPlaying = isPlaying,
    isBuffering = isBuffering,
    failure = failure
)
