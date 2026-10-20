package com.tunenagi.core.provider

import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.ProviderResult
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy's promises, with time under the test's control.
 *
 * The sleeper is injected, so these tests assert the *schedule* (500ms, then 1s, or the provider's
 * `Retry-After`) rather than waiting for it. What is real here is the decision: which failures are
 * retried, how many attempts are allowed, when a wait is too long to take, and that cancellation
 * ends the loop instead of starting another request.
 */
class ProviderRetryTest {

    private val slept = mutableListOf<Duration>()

    private fun retry(attempts: Int = 3, jitterMillis: Long = 0) = ProviderRetry(
        maxAttempts = attempts,
        sleeper = { slept += it },
        jitter = { jitterMillis }
    )

    private fun success(value: String = "ok") = ProviderResult.Success(value)

    private fun failure(error: ProviderError) = ProviderResult.Failure(error)

    @Test
    fun aSuccessIsReturnedWithoutWaiting() = runBlocking {
        var calls = 0

        val result = retry().run { calls++; success() }

        assertEquals(success(), result)
        assertEquals(1, calls)
        assertTrue("a successful call must not sleep: $slept", slept.isEmpty())
    }

    @Test
    fun aTransientFailureIsRetriedWithExponentialBackoffUntilItSucceeds() = runBlocking {
        var calls = 0

        val result = retry().run {
            calls++
            if (calls == 1) failure(ProviderError.Timeout) else success()
        }

        assertEquals(success(), result)
        assertEquals(2, calls)
        assertEquals(listOf(Duration.ofMillis(500)), slept)
    }

    @Test
    fun offlineAndServerFailuresAreTransientButAPermanentRefusalIsNot() = runBlocking {
        var offlineCalls = 0
        retry().run { offlineCalls++; failure(ProviderError.NetworkUnavailable) }
        assertEquals("an offline blip may be retried", 3, offlineCalls)

        var serverCalls = 0
        retry().run { serverCalls++; failure(ProviderError.Timeout) }
        assertEquals("a server fault uses the same bounded budget", 3, serverCalls)

        var forbiddenCalls = 0
        val refused = retry().run { forbiddenCalls++; failure(ProviderError.Restricted()) }
        assertEquals(failure(ProviderError.Restricted()), refused)
        assertEquals("a permanent refusal must be answered once", 1, forbiddenCalls)

        var unauthorizedCalls = 0
        retry().run { unauthorizedCalls++; failure(ProviderError.Unauthorized) }
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun theAttemptBudgetIsBoundedAndKeepsTheLastTypedFailure() = runBlocking {
        var calls = 0

        val result = retry().run { calls++; failure(ProviderError.Timeout) }

        assertEquals(failure(ProviderError.Timeout), result)
        assertEquals(3, calls)
        assertEquals(listOf(Duration.ofMillis(500), Duration.ofMillis(1_000)), slept)
    }

    @Test
    fun aRetryAfterIsUsedInsteadOfBackoff() = runBlocking {
        var calls = 0

        val result = retry().run {
            calls++
            if (calls == 1) failure(ProviderError.Throttled(Duration.ofSeconds(7))) else success()
        }

        assertEquals(success(), result)
        assertEquals("the provider's own delay wins", listOf(Duration.ofSeconds(7)), slept)
    }

    @Test
    fun aRetryAfterBeyondTheBudgetIsSurfacedWithoutWaitingAndStaysRetryable() = runBlocking {
        var calls = 0
        val throttled = ProviderError.Throttled(Duration.ofMinutes(5))

        val result = retry().run { calls++; failure(throttled) }

        assertEquals(failure(throttled), result)
        assertEquals("too long a wait is not taken, and no request is made early", 1, calls)
        assertTrue(slept.isEmpty())
        val error = (result as ProviderResult.Failure).error as ProviderError.Throttled
        assertEquals(Duration.ofMinutes(5), error.retryAfter)
        assertTrue("a caller may still schedule its own later attempt", error.retryable)
    }

    @Test
    fun jitterIsAddedAndTheDelayNeverExceedsTheCap() = runBlocking {
        retry(jitterMillis = 250).run { failure(ProviderError.Timeout) }
        assertEquals(Duration.ofMillis(750), slept.first())

        slept.clear()
        retry(jitterMillis = 100_000).run { failure(ProviderError.Timeout) }
        assertEquals(
            "a large jitter is capped at MAX_BACKOFF rather than sleeping arbitrarily",
            listOf(ProviderError.MAX_BACKOFF, ProviderError.MAX_BACKOFF),
            slept
        )
    }

    @Test
    fun cancellationStopsTheLoopInsteadOfStartingAnotherAttempt() = runBlocking {
        var calls = 0

        val thrown = try {
            ProviderRetry(
                maxAttempts = 3,
                sleeper = { throw CancellationException("the screen is gone") },
                jitter = { 0 }
            ).run { calls++; failure(ProviderError.Timeout) }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertTrue("cancellation must propagate, not become a provider error", thrown != null)
        assertEquals("no attempt may follow a cancelled wait", 1, calls)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun anAttemptBudgetBelowOneIsRefused() {
        val thrown = runCatching { ProviderRetry(maxAttempts = 0) }.exceptionOrNull()

        assertTrue("maxAttempts=0 would make no call at all: $thrown", thrown is IllegalArgumentException)
    }
}
