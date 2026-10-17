package com.tunenagi.core.model

import java.time.Duration

/**
 * The provider error taxonomy from `SPEC/PROVIDERS.md` — owned.
 *
 * The taxonomy exists so that a failure can be *decided on* rather than string-matched. The one
 * decision the rest of the app needs is [retryable], and getting it wrong in either direction is
 * expensive: retrying a permanent `401` hammers a provider that has already said no, and treating
 * a throttled `429` as permanent turns a temporary limit into a broken track.
 *
 * Nothing here carries a URL, token or response body: `SPEC/SECURITY_LEGAL.md` forbids private
 * material in an error that may reach a log or the UI.
 */
sealed interface ProviderError {

    /** The message shown to a user. English per the product's UI language, never a raw response. */
    val userMessage: String

    /** Whether a bounded retry with backoff may help. */
    val retryable: Boolean

    data object NetworkUnavailable : ProviderError {
        override val userMessage = "No network connection."
        override val retryable = true
    }

    data object Timeout : ProviderError {
        override val userMessage = "The source took too long to answer."
        override val retryable = true
    }

    /**
     * `retryAfter` is honoured rather than guessed: the provider told us when to come back, and
     * `SPEC/PROVIDERS.md` requires respecting it even when the body is not JSON.
     */
    data class Throttled(val retryAfter: Duration? = null) : ProviderError {
        override val userMessage =
            retryAfter?.let { "The source asked us to wait ${it.seconds} seconds." }
                ?: "The source is rate limiting requests."
        override val retryable = true
    }

    data object NotFound : ProviderError {
        override val userMessage = "This track is no longer available at the source."
        override val retryable = false
    }

    /** Region-restricted, gated or otherwise not playable here. Never replaced with another copy. */
    data class Restricted(val reason: String? = null) : ProviderError {
        override val userMessage = reason?.takeIf { it.isNotBlank() }
            ?.let { "Not playable here: $it" }
            ?: "This track cannot be played from this source."
        override val retryable = false
    }

    data object Unauthorized : ProviderError {
        override val userMessage = "The source refused this request."
        override val retryable = false
    }

    data object MalformedPayload : ProviderError {
        override val userMessage = "The source sent a response this app cannot read."
        override val retryable = false
    }

    data object UnsupportedFormat : ProviderError {
        override val userMessage = "This device cannot play the format the source offered."
        override val retryable = false
    }

    data class Unknown(val category: String) : ProviderError {
        override val userMessage = "Something went wrong talking to the source."
        override val retryable = false
    }

    companion object {
        /** Retries allowed for a transient failure before the UI offers a manual retry. */
        const val MAX_ATTEMPTS = 3

        /** Jittered backoff base, doubling per attempt, capped so a screen never waits forever. */
        val BASE_BACKOFF: Duration = Duration.ofMillis(500)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        /**
         * Delay before attempt [attempt] (1-based). Deterministic given [jitterMillis] so the
         * policy is unit-testable; the caller supplies real jitter in production.
         */
        fun backoffFor(attempt: Int, jitterMillis: Long = 0): Duration {
            require(attempt >= 1) { "Attempt numbering starts at 1, but was $attempt" }
            val exponent = (attempt - 1).coerceAtMost(10)
            val base = BASE_BACKOFF.toMillis().shl(exponent)
            val bounded = base.coerceAtMost(MAX_BACKOFF.toMillis())
            val withJitter = (bounded + jitterMillis).coerceAtMost(MAX_BACKOFF.toMillis())
            return Duration.ofMillis(withJitter.coerceAtLeast(0))
        }

        /** Maps an HTTP status to the taxonomy. 4xx codes other than 429 are not retryable. */
        fun fromHttpStatus(status: Int, retryAfter: Duration? = null): ProviderError = when {
            status == 404 -> NotFound
            status == 401 -> Unauthorized
            status == 403 -> Restricted()
            status == 408 -> Timeout
            status == 429 -> Throttled(retryAfter)
            status in 500..599 -> Timeout
            status in 400..499 -> MalformedPayload
            else -> Unknown(category = "http-$status")
        }
    }
}

/** Result of an operation that can fail at a provider boundary, without exceptions in the flow. */
sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data class Failure(val error: ProviderError) : ProviderResult<Nothing>
}

inline fun <T, R> ProviderResult<T>.map(transform: (T) -> R): ProviderResult<R> = when (this) {
    is ProviderResult.Success -> ProviderResult.Success(transform(value))
    is ProviderResult.Failure -> this
}
