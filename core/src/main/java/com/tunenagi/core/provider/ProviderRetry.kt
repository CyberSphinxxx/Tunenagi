package com.tunenagi.core.provider

import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderResult
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The one bounded retry policy every provider call may use — owned (TUN-0304).
 *
 * `SPEC/PROVIDERS.md` asks for "exponential backoff with jitter honoring Retry-After" and for
 * permanent refusals never to be retried. Both halves are decided by the taxonomy, not by a
 * provider: [ProviderError.retryable] says whether another attempt may help, and a `429` carries the
 * delay the provider itself named.
 *
 * Four properties are deliberate:
 *
 * 1. **Bounded.** At most [maxAttempts] calls, ever. A provider that keeps failing costs the user
 *    two short waits, not an unbounded loop.
 * 2. **`Retry-After` wins over backoff.** When the provider says "come back in 7 seconds", waiting
 *    500ms and asking again is a request the provider already told us not to send. The header's
 *    value is used as the delay instead of the exponential step.
 * 3. **A wait longer than the budget is not taken.** If the provider asks for more than [maxDelay]
 *    (30 seconds by default), retrying would either hammer it early or freeze a screen, so the
 *    throttled result is surfaced immediately — it still carries its `retryAfter` and remains
 *    `retryable`, so a caller may schedule a later attempt on its own terms.
 * 4. **Cancellation propagates.** A cancelled screen cancels its call; no attempt is started, and no
 *    delay swallows the cancellation. The policy never catches `CancellationException`.
 *
 * [sleeper] and [jitter] are injectable so the policy's timing is a deterministic unit test rather
 * than a wall-clock test: the production values are `delay(...)` and a fresh random jitter.
 */
class ProviderRetry(
    val maxAttempts: Int = ProviderError.MAX_ATTEMPTS,
    private val maxDelay: Duration = ProviderError.MAX_BACKOFF,
    private val sleeper: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val jitter: () -> Long = { Random.nextLong(0, JITTER_MILLIS + 1) }
) {

    init {
        require(maxAttempts >= 1) { "A retry policy must allow at least one attempt, but was $maxAttempts" }
        require(!maxDelay.isNegative && !maxDelay.isZero) {
            "A retry budget must be positive, but was $maxDelay"
        }
    }

    /**
     * Runs [call] and retries it only while the taxonomy says another attempt may help.
     *
     * The returned result is the last attempt's result: success as soon as one succeeds, otherwise
     * the final typed failure with its original category.
     */
    suspend fun <T> run(call: suspend () -> ProviderResult<T>): ProviderResult<T> {
        var attempt = 1
        while (true) {
            // A caller that was cancelled between attempts must not open another socket.
            currentCoroutineContext().ensureActive()
            val result = call()
            if (result is ProviderResult.Success) return result
            val error = (result as ProviderResult.Failure).error
            if (!error.retryable || attempt >= maxAttempts) return result
            val pause = pauseFor(error, attempt) ?: return result
            sleeper(pause)
            // `delay` is cancellable, but an injected sleeper may not be; check again before the
            // next attempt so cancellation is never lost.
            currentCoroutineContext().ensureActive()
            attempt++
        }
    }

    /** The wait before the next attempt, or `null` when the provider asked for more than we allow. */
    private fun pauseFor(error: ProviderError, attempt: Int): Duration? {
        val pause = if (error is ProviderError.Throttled && error.retryAfter != null) {
            error.retryAfter
        } else {
            ProviderError.backoffFor(attempt, jitter())
        }
        return pause.takeIf { it <= maxDelay }
    }

    companion object {
        /** Adds at most a quarter second, so two clients do not retry in lockstep. */
        const val JITTER_MILLIS = 250L

        /** Production policy: three attempts, real delays, real jitter. */
        val STANDARD = ProviderRetry()

        /** Exactly one attempt. Useful for a call whose failure the caller must see immediately. */
        val NONE = ProviderRetry(maxAttempts = 1)
    }
}
