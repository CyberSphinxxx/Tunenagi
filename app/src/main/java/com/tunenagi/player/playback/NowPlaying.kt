package com.tunenagi.player.playback

import androidx.media3.common.Player
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
    val isPlaying: Boolean
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
        isPlaying = isPlaying
    )
}

/** Renders the snapshot in the shell's mini-player slot. */
fun NowPlaying.toMiniPlayerState(): MiniPlayerState = MiniPlayerState.Track(
    title = title,
    artist = artist.orEmpty(),
    isPlaying = isPlaying
)
