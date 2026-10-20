package com.tunenagi.core.network

import com.tunenagi.core.model.ProviderError
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transport's promises, against a real HTTP server on loopback.
 *
 * `MockWebServer` is a real server speaking real HTTP over a real socket, so this file covers the
 * parts a provider-level test with a fake transport cannot: the `User-Agent` actually sent, body
 * budgets, socket timeouts, `Retry-After` parsing and the redirect loop. The one test-only
 * concession is `allowInsecureLoopback`, which the production config leaves false.
 */
class OkHttpTransportTest {

    private lateinit var server: MockWebServer
    private var running = false

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        running = true
    }

    @After
    fun stopServer() {
        if (running) server.shutdown()
    }

    private fun config(
        readTimeout: Duration = Duration.ofSeconds(2),
        callTimeout: Duration = Duration.ofSeconds(5),
        maxBodyBytes: Int = 1 shl 20,
        maxRedirects: Int = 3,
        allowedRedirectHosts: Set<String> = setOf("127.0.0.1"),
        allowInsecureLoopback: Boolean = true
    ) = HttpTransportConfig(
        userAgent = "Tunenagi/0.1 (contract-test)",
        readTimeout = readTimeout,
        callTimeout = callTimeout,
        maxBodyBytes = maxBodyBytes,
        maxRedirects = maxRedirects,
        allowedRedirectHosts = allowedRedirectHosts,
        allowInsecureLoopback = allowInsecureLoopback
    )

    private fun url(path: String = "/v1/tracks/search"): String = server.url(path).toString()

    @Test
    fun aRequestCarriesTheConfiguredIdentityAndEncodedQuery() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val transport = OkHttpTransport(config())

        val result = transport.get(
            HttpRequest(url(), query = listOf("query" to "slow tide", "limit" to "2"))
        )

        assertEquals(HttpResult.Response(200, """{"data":[]}"""), result)
        val recorded = server.takeRequest()
        assertEquals("Tunenagi/0.1 (contract-test)", recorded.getHeader("User-Agent"))
        assertEquals("slow tide", recorded.requestUrl!!.queryParameter("query"))
        assertEquals("2", recorded.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun aBodyLargerThanTheBudgetIsRefusedRatherThanBuffered() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(4_096)))
        val transport = OkHttpTransport(config(maxBodyBytes = 64))

        val result = transport.get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.MalformedPayload), result)
    }

    @Test
    fun aRetryAfterInSecondsIsParsed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"error":"slow down"}""")
        )

        val result = OkHttpTransport(config()).get(HttpRequest(url()))

        val response = result as HttpResult.Response
        assertEquals(429, response.status)
        assertEquals(Duration.ofSeconds(7), response.retryAfter)
    }

    @Test
    fun aRetryAfterAsAnHttpDateIsParsedToo() = runBlocking {
        val at = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5)
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(at))
                .setBody("unavailable")
        )

        val response = OkHttpTransport(config()).get(HttpRequest(url())) as HttpResult.Response

        val retryAfter = response.retryAfter!!
        assertTrue("expected roughly five seconds, got $retryAfter", retryAfter.seconds in 0..6)
    }

    @Test
    fun aSilentServerBecomesATimeoutNotAGenericFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val transport = OkHttpTransport(
            config(readTimeout = Duration.ofMillis(150), callTimeout = Duration.ofSeconds(1))
        )

        val result = transport.get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.Timeout), result)
    }

    @Test
    fun aClosedServerBecomesNetworkUnavailable() = runBlocking {
        val target = url()
        server.shutdown()
        running = false

        val result = OkHttpTransport(config()).get(HttpRequest(target))

        assertEquals(HttpResult.Failure(ProviderError.NetworkUnavailable), result)
    }

    @Test
    fun aRedirectToAnAllowedHostIsFollowed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/v1/tracks/trending")
        )
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        val result = OkHttpTransport(config()).get(HttpRequest(url()))

        assertEquals(HttpResult.Response(200, """{"data":[]}"""), result)
        assertEquals("/v1/tracks/search", server.takeRequest().path)
        assertEquals("/v1/tracks/trending", server.takeRequest().path)
    }

    @Test
    fun aRedirectOffTheAllowedHostIsRefused() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://evil.example/steal")
        )

        val result = OkHttpTransport(config()).get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.Unknown("redirect-host")), result)
    }

    @Test
    fun aRedirectToPlainHttpIsRefusedEvenWhenTheHostIsAllowed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://192.0.2.10/track")
        )
        val transport = OkHttpTransport(
            config(allowedRedirectHosts = setOf("127.0.0.1", "192.0.2.10"))
        )

        val result = transport.get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.Unknown("redirect-insecure")), result)
    }

    @Test
    fun redirectsAreBounded() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/one"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/two"))
        server.enqueue(MockResponse().setBody("should not be reached"))

        val result = OkHttpTransport(config(maxRedirects = 1)).get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.Unknown("too-many-redirects")), result)
    }

    @Test
    fun aNonHttpsRequestIsRefusedUnlessLoopbackIsExplicitlyAllowed() = runBlocking {
        server.enqueue(MockResponse().setBody("should not be reached"))
        val transport = OkHttpTransport(config(allowInsecureLoopback = false))

        val result = transport.get(HttpRequest(url()))

        assertEquals(HttpResult.Failure(ProviderError.Unknown("invalid-request-url")), result)
    }
}
