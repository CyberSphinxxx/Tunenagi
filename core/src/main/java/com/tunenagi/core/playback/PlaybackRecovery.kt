package com.tunenagi.core.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why a track stopped, in the vocabulary the UI needs — owned.
 *
 * Media3 reports an `int` error code and a developer-facing message. Neither is a user-facing
 * state: the code has to be grouped (a `404` and a deleted file are the same problem to a
 * listener) and the message must never reach a screen or a log. This sealed type is that grouping,
 * and [retryable] is the one decision the recovery loop makes from it.
 *
 * Retryability is deliberately narrow. A timeout or a dropped connection may go away on its own;
 * a missing file, a revoked permission or a codec this device does not have will not, and retrying
 * them would spin against a permanent answer. [Unknown] is not retried automatically either:
 * without a classification there is no evidence the next attempt can succeed, so the user gets the
 * manual retry instead of an unbounded loop.
 */
sealed interface PlaybackFailure {

    /** Whether an automatic bounded retry may help. */
    val retryable: Boolean

    /** The source did not answer in time. */
    data object Timeout : PlaybackFailure {
        override val retryable = true
    }

    /** The connection failed or a server answered with an unusable status. */
    data object Network : PlaybackFailure {
        override val retryable = true
    }

    /** The local file is gone (deleted, moved, or the stored pointer is stale). */
    data object MissingSource : PlaybackFailure {
        override val retryable = false
    }

    /** Access to the file or folder was revoked. Only the user can restore it. */
    data object AccessLost : PlaybackFailure {
        override val retryable = false
    }

    /** The device cannot decode this container/codec, or the file is damaged. */
    data object UnsupportedFormat : PlaybackFailure {
        override val retryable = false
    }

    /** Something failed that this taxonomy does not recognise. */
    data class Unknown(val errorCode: Int) : PlaybackFailure {
        override val retryable = false
    }

    companion object {
        /**
         * Groups a Media3 error code.
         *
         * Kept as a pure function of the code rather than a method on the exception so the
         * taxonomy is testable without constructing a player, and so adding a category is a
         * change in exactly one place.
         */
        fun from(exception: PlaybackException): PlaybackFailure =
            fromErrorCode(exception.errorCode)

        /** The classification itself; see [from]. */
        fun fromErrorCode(errorCode: Int): PlaybackFailure = when (errorCode) {
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Timeout

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Network

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> MissingSource

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PERMISSION_DENIED -> AccessLost

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> UnsupportedFormat

            else -> Unknown(errorCode)
        }
    }
}

/**
 * What the recovery loop is doing — owned.
 *
 * The UI does not render this type yet (it reads the controller's own error), but the session
 * exposes it so the retry loop is observable and testable instead of a hidden timer. [Failed] say
 * whether another automatic attempt is already scheduled, which is the distinction between "wait a
 * second" and "this needs the user".
 */
sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus

    data object Buffering : PlaybackStatus

    data object Ready : PlaybackStatus

    data class Failed(
        val failure: PlaybackFailure,
        /** Automatic attempts made for the current item, 0-based count of retries. */
        val automaticRetries: Int,
        /** True when a retry is scheduled; false when the budget is spent or no retry can help. */
        val willRetryAutomatically: Boolean
    ) : PlaybackStatus
}

/** A scheduled action that can be cancelled. */
fun interface PendingRetry {
    fun cancel()

    companion object {
        /** The cancel of "there is nothing scheduled". */
        val None = PendingRetry {}
    }
}

/** Where a delayed retry is scheduled. Injected so tests do not depend on wall-clock time. */
fun interface RetryScheduler {
    fun schedule(delayMillis: Long, retry: () -> Unit): PendingRetry
}

/**
 * The production scheduler: a main-thread handler.
 *
 * The player may only be touched on the main thread (Media3 enforces it), and retries call
 * `prepare()`, so the delay must deliver on that same thread rather than a background one.
 */
class HandlerRetryScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : RetryScheduler {

    override fun schedule(delayMillis: Long, retry: () -> Unit): PendingRetry {
        val runnable = Runnable { retry() }
        handler.postDelayed(runnable, delayMillis)
        return PendingRetry { handler.removeCallbacks(runnable) }
    }
}

/**
 * Bounded automatic recovery for the session's player — owned (TUN-0207).
 *
 * It listens to the real player rather than being told about failures by a caller, so a codec,
 * file or network error reaches this one place no matter which thread Media3 raised it on. The
 * policy is small on purpose:
 *
 * - A [PlaybackFailure.retryable] failure schedules `prepare()` after a short backoff, at most
 *   [maxAutomaticRetries] times per item. Attempt 1 waits 1s, attempt 2 waits 2s.
 * - A non-retryable failure is reported immediately with no timer, because waiting would only
 *   delay the explanation.
 * - Reaching `STATE_READY` clears both the failure and the budget, so a later error on the same
 *   item gets its own bounded attempts instead of inheriting an exhausted one.
 * - A media-item transition clears the failure and cancels any pending retry: a retry meant for the
 *   previous track must not `prepare()` the next one. This is the race the generation counter
 *   exists for, and it is asserted in `PlaybackRecoveryTest`.
 * - Every new error invalidates the previous generation, so two errors arriving close together
 *   leave at most one scheduled retry and count both attempts.
 *
 * Threading: Media3 delivers callbacks on the application thread, but the scheduler and a test may
 * both touch the state, so the fields are guarded by one lock. The retry itself runs outside the
 * lock; a retry callback that immediately fails re-enters [onPlayerError] and must not deadlock.
 */
class PlaybackRecovery(
    /** Returns the player to the failed item. In production this is `player.prepare()`. */
    private val retry: () -> Unit,
    private val scheduler: RetryScheduler,
    private val maxAutomaticRetries: Int = DEFAULT_MAX_AUTOMATIC_RETRIES,
    private val backoffMillis: (attempt: Int) -> Long = { attempt ->
        DEFAULT_BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5)
    }
) : Player.Listener {

    private val lock = Any()
    private var generation = 0L
    private var automaticRetries = 0
    private var pending: PendingRetry? = null

    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)

    /** The current recovery state; see [PlaybackStatus]. */
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    override fun onPlayerError(error: PlaybackException) {
        val failure = PlaybackFailure.from(error)
        synchronized(lock) {
            // Whatever was scheduled belongs to the previous generation. Cancel it before the
            // counter moves so a callback already dispatched cannot pass the generation check.
            pending?.cancel()
            pending = null
            generation++

            val retryAllowed = failure.retryable && automaticRetries < maxAutomaticRetries
            if (!retryAllowed) {
                _status.value = PlaybackStatus.Failed(
                    failure = failure,
                    automaticRetries = automaticRetries,
                    willRetryAutomatically = false
                )
                return
            }

            automaticRetries++
            val scheduledFor = generation
            val attempt = automaticRetries
            _status.value = PlaybackStatus.Failed(
                failure = failure,
                automaticRetries = attempt,
                willRetryAutomatically = true
            )
            pending = scheduler.schedule(backoffMillis(attempt)) {
                val stillCurrent = synchronized(lock) {
                    if (generation == scheduledFor) {
                        pending = null
                        true
                    } else {
                        false
                    }
                }
                if (stillCurrent) retry()
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        synchronized(lock) {
            when (playbackState) {
                Player.STATE_READY -> {
                    pending?.cancel()
                    pending = null
                    generation++
                    automaticRetries = 0
                    _status.value = PlaybackStatus.Ready
                }

                Player.STATE_BUFFERING -> {
                    // Never downgrade a failure to "buffering": the error is what the user needs
                    // to see, and Media3 reports BUFFERING while it tears a failed load down.
                    if (_status.value !is PlaybackStatus.Failed) {
                        _status.value = PlaybackStatus.Buffering
                    }
                }

                Player.STATE_ENDED -> {
                    // The queue finished normally. There is nothing to recover, and any retry
                    // timer from an earlier item is already invalidated by the transition.
                    if (_status.value !is PlaybackStatus.Failed) {
                        _status.value = PlaybackStatus.Idle
                    }
                }

                Player.STATE_IDLE -> {
                    if (_status.value !is PlaybackStatus.Failed) {
                        _status.value = PlaybackStatus.Idle
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        synchronized(lock) {
            pending?.cancel()
            pending = null
            generation++
            automaticRetries = 0
            // The new item starts from a clean slate. Its own BUFFERING/READY transition follows.
            _status.value = PlaybackStatus.Idle
        }
    }

    /**
     * Stops the loop and invalidates any scheduled callback.
     *
     * Called when the service's player is released: a retry that outlived its player would call
     * `prepare()` on a released object, which is silent in this environment and therefore worse
     * than a crash.
     */
    fun cancel() {
        synchronized(lock) {
            generation++
            pending?.cancel()
            pending = null
        }
    }

    companion object {
        /** Two automatic attempts, then the user decides. */
        const val DEFAULT_MAX_AUTOMATIC_RETRIES = 2

        /** First retry after one second; the second after two. */
        const val DEFAULT_BASE_BACKOFF_MS = 1_000L
    }
}
