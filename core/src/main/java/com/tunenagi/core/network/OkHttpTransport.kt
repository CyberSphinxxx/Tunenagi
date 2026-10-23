package com.tunenagi.core.network

import com.tunenagi.core.model.ProviderError
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The production [HttpTransport] — owned.
 *
 * Four policies live here because getting any of them subtly wrong is a defect every provider would
 * inherit:
 *
 * 1. **The body is read with a budget.** A provider response is parsed by a provider, and a
 *    provider has no way to know a body is hostile. Reading stops at
 *    [HttpTransportConfig.maxBodyBytes] and the answer becomes `MalformedPayload` rather than
 *    growing a `String` until the process dies.
 * 2. **Cancellation reaches the socket.** The call is enqueued and a cancelled coroutine cancels
 *    the `Call`; a screen that leaves a search does not leave the request running.
 * 3. **Redirects are followed by hand.** OkHttp's own redirect following cannot be told "only these
 *    hosts over HTTPS", and a redirect is exactly where a provider URL can silently become something
 *    else. The loop is bounded by [HttpTransportConfig.maxRedirects], refuses anything that is not
 *    HTTPS, refuses a host outside [HttpTransportConfig.allowedRedirectHosts], and says so with a
 *    typed error instead of surfacing the URL.
 * 4. **Failures are the provider taxonomy.** A timeout is [ProviderError.Timeout] and a name that
 *    does not resolve is [ProviderError.NetworkUnavailable]; neither carries a URL, a token or a
 *    response body.
 */
class OkHttpTransport(
    private val config: HttpTransportConfig,
    client: OkHttpClient = OkHttpClient()
) : HttpTransport {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(config.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .callTimeout(config.callTimeout.toMillis(), TimeUnit.MILLISECONDS)
        // The redirect policy is this class's, not OkHttp's; see the class note.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun get(request: HttpRequest): HttpResult {
        val target = buildTarget(request)
            ?: return HttpResult.Failure(ProviderError.Unknown("invalid-request-url"))
        return follow(target, request, redirectsLeft = config.maxRedirects)
    }

    private fun buildTarget(request: HttpRequest): HttpUrl? {
        val base = request.url.toHttpUrlOrNull() ?: return null
        if (!isPermitted(base)) return null
        val builder = base.newBuilder()
        request.query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    /**
     * HTTPS everywhere, plus the explicitly opted-in loopback exception for contract tests.
     *
     * The exception is narrow on purpose: it names the three loopback spellings and nothing else,
     * so a misconfigured production client cannot silently send a provider request in clear text.
     */
    private fun isPermitted(url: HttpUrl): Boolean =
        url.isHttps || (config.allowInsecureLoopback && url.host in LOOPBACK_HOSTS)

    private suspend fun follow(url: HttpUrl, request: HttpRequest, redirectsLeft: Int): HttpResult {
        when (val result = execute(url, request)) {
            is HttpResult.Failure -> return result
            is HttpResult.Response -> {
                if (result.status !in REDIRECT_MIN..REDIRECT_MAX) return result
                val location = result.location
                    ?: return HttpResult.Failure(ProviderError.Unknown("redirect-without-location"))
                if (redirectsLeft <= 0) {
                    return HttpResult.Failure(ProviderError.Unknown("too-many-redirects"))
                }
                val next = url.resolve(location)
                    ?: return HttpResult.Failure(ProviderError.Unknown("redirect-unreadable"))
                if (!isPermitted(next)) {
                    return HttpResult.Failure(ProviderError.Unknown("redirect-insecure"))
                }
                if (next.host !in config.allowedRedirectHosts) {
                    return HttpResult.Failure(ProviderError.Unknown("redirect-host"))
                }
                return follow(next, request, redirectsLeft - 1)
            }
        }
    }

    private suspend fun execute(url: HttpUrl, request: HttpRequest): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder()
                .url(url)
                .header(USER_AGENT, config.userAgent)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            val call = client.newCall(builder.get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, failure: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(HttpResult.Failure(failureOf(failure)))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use { read(it) }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            )
        }

    private fun read(response: Response): HttpResult {
        val retryAfter = retryAfter(response.header(RETRY_AFTER))
        val location = response.header(LOCATION)
        if (response.code in REDIRECT_MIN..REDIRECT_MAX) {
            // A redirect body is never parsed, and some servers send an HTML page with it.
            return HttpResult.Response(response.code, body = null, retryAfter = retryAfter, location = location)
        }
        val body = when (val read = readBounded(response)) {
            is BodyRead.Text -> read.value
            BodyRead.TooLarge -> return HttpResult.Failure(ProviderError.MalformedPayload)
        }
        return HttpResult.Response(response.code, body, retryAfter, location)
    }

    private fun readBounded(response: Response): BodyRead {
        val body = response.body ?: return BodyRead.Text("")
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > config.maxBodyBytes) return BodyRead.TooLarge
                output.write(buffer, 0, read)
            }
        }
        return BodyRead.Text(String(output.toByteArray(), charset))
    }

    /** The header is seconds or an HTTP-date; both are documented, so both are honoured. */
    private fun retryAfter(raw: String?): Duration? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return runCatching {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(Instant.now(), at).coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }

    private fun failureOf(failure: IOException): ProviderError = when (failure) {
        is SocketTimeoutException -> ProviderError.Timeout
        is InterruptedIOException -> ProviderError.Timeout
        else -> ProviderError.NetworkUnavailable
    }

    private sealed interface BodyRead {
        data class Text(val value: String) : BodyRead

        data object TooLarge : BodyRead
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
        const val RETRY_AFTER = "Retry-After"
        const val LOCATION = "Location"
        const val REDIRECT_MIN = 300
        const val REDIRECT_MAX = 399
        const val BUFFER_BYTES = 8 * 1024
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
