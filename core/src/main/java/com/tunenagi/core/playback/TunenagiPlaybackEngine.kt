package com.tunenagi.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one ExoPlayer in the process, and its lifecycle — owned.
 *
 * Building the player directly in the service would work, but it would leave the most important
 * claim in this task unassertable: Media3 does not expose whether a player has been released, and a
 * released `ExoPlayer` in this environment accepts calls silently (measured, not assumed). A leaked
 * player is a leaked audio session, so "was it released" needs an answer that a test can read:
 * [isReleased] is that answer, and [release] is the only way to set it.
 *
 * Audio attributes and audio focus live here too, so `SPEC/AUDIO_ENGINE.md`'s requirement that the
 * session — not the app — owns focus is satisfied in one reviewable place.
 */
class TunenagiPlaybackEngine(context: Context) {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            TunenagiPlayback.HANDLE_AUDIO_FOCUS
        )
        .build()

    /** The player itself. Never handed to the UI; the app talks to the session instead. */
    val player: Player get() = exoPlayer

    /** Whether [release] has run. A released engine must not be used again. */
    var isReleased: Boolean = false
        private set

    /**
     * Releases the player exactly once.
     *
     * Idempotent on purpose: a service can be destroyed after a stop, and a second release must not
     * be a crash in the shutdown path where nothing can report it.
     */
    fun release() {
        if (isReleased) return
        exoPlayer.release()
        isReleased = true
    }
}

/**
 * Playback defaults shared by the engine and the later focus/error tasks — owned.
 *
 * `handleAudioFocus = true` lets Media3 request, duck and abandon audio focus for this player,
 * which `SPEC/AUDIO_ENGINE.md` requires instead of a hand-rolled focus listener.
 */
object TunenagiPlayback {
    /** Media3 owns audio focus for this player; the app never requests it directly. */
    const val HANDLE_AUDIO_FOCUS = true
}
