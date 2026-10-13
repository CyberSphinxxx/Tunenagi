package com.tunenagi.core.model

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy is the part of the provider taxonomy other layers act on, so it is asserted
 * case by case instead of being documented in a comment and hoped for.
 */
class ProviderErrorTest {

    @Test
    fun transientFailuresAreRetryable() {
        listOf(
            ProviderError.NetworkUnavailable,
            ProviderError.Timeout,
            ProviderError.Throttled()
        ).forEach { assertTrue("$it should be retryable", it.retryable) }
    }

    /** Retrying a refusal or a malformed body wastes a provider's quota and fixes nothing. */
    @Test
    fun permanentFailuresAreNotRetryable() {
        listOf(
            ProviderError.NotFound,
            ProviderError.Restricted(),
            ProviderError.Unauthorized,
            ProviderError.MalformedPayload,
            ProviderError.UnsupportedFormat,
            ProviderError.Unknown("weird")
        ).forEach { assertFalse("$it must not be retryable", it.retryable) }
    }

    @Test
    fun everyErrorCarriesAUserFacingMessageAndNoSecret() {
        val errors = listOf(
            ProviderError.NetworkUnavailable,
            ProviderError.Timeout,
            ProviderError.Throttled(Duration.ofSeconds(30)),
            ProviderError.NotFound,
            ProviderError.Restricted("region locked"),
            ProviderError.Unauthorized,
            ProviderError.MalformedPayload,
            ProviderError.UnsupportedFormat,
            ProviderError.Unknown("http-302")
        )
        errors.forEach { error ->
            assertTrue("$error needs a message", error.userMessage.isNotBlank())
            assertFalse("messages must not leak urls", error.userMessage.contains("http"))
        }
        assertTrue(ProviderError.Throttled(Duration.ofSeconds(30)).userMessage.contains("30"))
    }

    @Test
    fun throttledRemembersTheRetryAfterItWasGiven() {
        val throttled = ProviderError.Throttled(retryAfter = Duration.ofSeconds(12))
        assertEquals(Duration.ofSeconds(12), throttled.retryAfter)
        assertTrue(throttled.userMessage.contains("12"))
        assertEquals(null, ProviderError.Throttled().retryAfter)
    }

    @Test
    fun httpStatusMapsToTheRightBucket() {
        assertEquals(ProviderError.NotFound, ProviderError.fromHttpStatus(404))
        assertEquals(ProviderError.Unauthorized, ProviderError.fromHttpStatus(401))
        assertEquals(ProviderError.Restricted(), ProviderError.fromHttpStatus(403))
        assertEquals(ProviderError.Timeout, ProviderError.fromHttpStatus(408))
        assertEquals(ProviderError.Throttled(Duration.ofSeconds(5)), ProviderError.fromHttpStatus(429, Duration.ofSeconds(5)))
        assertEquals(ProviderError.Timeout, ProviderError.fromHttpStatus(503))
        assertEquals(ProviderError.MalformedPayload, ProviderError.fromHttpStatus(418))
        assertEquals(ProviderError.Unknown("http-302"), ProviderError.fromHttpStatus(302))
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(Duration.ofMillis(500), ProviderError.backoffFor(1))
        assertEquals(Duration.ofMillis(1_000), ProviderError.backoffFor(2))
        assertEquals(Duration.ofMillis(2_000), ProviderError.backoffFor(3))
        assertEquals(Duration.ofMillis(30_000), ProviderError.backoffFor(20))
        assertEquals(Duration.ofMillis(1_100), ProviderError.backoffFor(2, jitterMillis = 100))
        assertEquals(Duration.ofMillis(30_000), ProviderError.backoffFor(6, jitterMillis = 60_000))
    }

    /** A negative jitter must not produce a negative delay, which would busy-loop the retry. */
    @Test
    fun backoffNeverGoesNegative() {
        assertEquals(Duration.ofMillis(0), ProviderError.backoffFor(1, jitterMillis = -10_000))
    }

    @Test
    fun backoffRejectsAnInvalidAttemptNumber() {
        assertThrows(IllegalArgumentException::class.java) { ProviderError.backoffFor(0) }
        assertThrows(IllegalArgumentException::class.java) { ProviderError.backoffFor(-1) }
        assertEquals(3, ProviderError.MAX_ATTEMPTS)
    }

    @Test
    fun providerResultMapsSuccessAndPassesFailureThrough() {
        val success: ProviderResult<Int> = ProviderResult.Success(2)
        assertEquals(ProviderResult.Success("2"), success.map { it.toString() })

        val failure: ProviderResult<Int> = ProviderResult.Failure(ProviderError.NotFound)
        assertTrue(failure.map { it.toString() } is ProviderResult.Failure)
    }
}
