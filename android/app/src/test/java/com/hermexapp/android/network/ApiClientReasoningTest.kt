package com.hermexapp.android.network

import com.hermexapp.android.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for the `/api/reasoning` API client extension. Mirrors the wire
 * contract implemented by the desktop gateway (see
 * `hermes_constants.parse_reasoning_effort` + the `slash_commands`
 * `_handle_reasoning_command` handler).
 */
class ApiClientReasoningTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── GET /api/reasoning ───────────────────────────────────────────────

    @Test
    fun `reasoning GET decodes the snake_case payload`() = runBlocking {
        server.enqueue(
            json(200, """{"ok":true,"show_reasoning":true,"reasoning_effort":"high"}"""),
        )
        val status = client.reasoning()
        assertEquals(true, status.ok)
        assertEquals(true, status.effectiveShowReasoning)
        assertEquals(ReasoningEffort.HIGH, status.effectiveEffort)
        assertEquals("/api/reasoning", server.takeRequest().path)
    }

    @Test
    fun `reasoning GET also accepts the camelCase payload`() = runBlocking {
        server.enqueue(
            json(200, """{"ok":true,"showReasoning":false,"reasoningEffort":"low"}"""),
        )
        val status = client.reasoning()
        assertEquals(false, status.effectiveShowReasoning)
        assertEquals(ReasoningEffort.LOW, status.effectiveEffort)
    }

    @Test
    fun `reasoning GET on an empty server response falls back to AUTO and show`() = runBlocking {
        server.enqueue(json(200, """{}"""))
        val status = client.reasoning()
        assertEquals(ReasoningEffort.AUTO, status.effectiveEffort)
        assertEquals(true, status.effectiveShowReasoning)
    }

    // ── POST /api/reasoning { effort } ──────────────────────────────────

    @Test
    fun `saveReasoningEffort posts the wire value and reads back the effective value`() = runBlocking {
        server.enqueue(
            json(200, """{"ok":true,"reasoning_effort":"medium"}"""),
        )
        val status = client.saveReasoningEffort(ReasoningEffort.HIGH)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/reasoning", sent.path)
        assertEquals("""{"effort":"high"}""", sent.body.readUtf8())
        // The server clamped HIGH to MEDIUM; the call returns the effective value.
        assertEquals(ReasoningEffort.MEDIUM, status.effectiveEffort)
    }

    @Test
    fun `saveReasoningEffort AUTO does not POST — it just re-reads the server state`() = runBlocking {
        server.enqueue(
            json(200, """{"ok":true,"reasoning_effort":"medium"}"""),
        )
        val status = client.saveReasoningEffort(ReasoningEffort.AUTO)
        // Exactly one request: the GET. No POST is sent.
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals(ReasoningEffort.MEDIUM, status.effectiveEffort)
    }

    @Test
    fun `saveReasoningEffort sends none for the off value`() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"reasoning_effort":"none"}"""))
        client.saveReasoningEffort(ReasoningEffort.NONE)
        val sent = server.takeRequest()
        assertEquals("""{"effort":"none"}""", sent.body.readUtf8())
    }

    // ── POST /api/reasoning { display } ─────────────────────────────────

    @Test
    fun `saveReasoningDisplay SHOW sends the canonical short form`() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"show_reasoning":true}"""))
        val status = client.saveReasoningDisplay(ReasoningDisplay.SHOW)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("""{"display":"show"}""", sent.body.readUtf8())
        assertEquals(true, status.effectiveShowReasoning)
    }

    @Test
    fun `saveReasoningDisplay HIDE sends hide`() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"show_reasoning":false}"""))
        val status = client.saveReasoningDisplay(ReasoningDisplay.HIDE)
        assertEquals("""{"display":"hide"}""", server.takeRequest().body.readUtf8())
        assertEquals(false, status.effectiveShowReasoning)
    }

    @Test
    fun `ReasoningDisplay fromServer normalises on off show hide`() {
        assertEquals(ReasoningDisplay.SHOW, ReasoningDisplay.fromServer("show"))
        assertEquals(ReasoningDisplay.SHOW, ReasoningDisplay.fromServer("on"))
        assertEquals(ReasoningDisplay.HIDE, ReasoningDisplay.fromServer("hide"))
        assertEquals(ReasoningDisplay.HIDE, ReasoningDisplay.fromServer("off"))
        assertEquals(null, ReasoningDisplay.fromServer(null))
        assertEquals(null, ReasoningDisplay.fromServer(""))
        assertEquals(null, ReasoningDisplay.fromServer("nope"))
    }

    // ── Error paths ──────────────────────────────────────────────────────

    @Test
    fun `saveReasoningEffort propagates 401 as ApiError Unauthorized`() = runBlocking {
        server.enqueue(json(401, "{\"error\":\"unauthorized\"}"))
        try {
            client.saveReasoningEffort(ReasoningEffort.LOW)
            fail("expected ApiError")
        } catch (e: ApiError.Unauthorized) {
            // 401 is special-cased to the Unauthorized singleton so the
            // session-expiry handler can pick it up uniformly. Copy is the
            // shared JKP client-error catalog (invalid_device_grant family).
            assertEquals(ClientErrorCatalog.INVALID_DEVICE_GRANT.message, e.userMessage)
        }
    }

    @Test
    fun `saveReasoningEffort propagates 500 as ApiError Http with status 500`() = runBlocking {
        server.enqueue(json(500, "{\"error\":\"boom\"}"))
        try {
            client.saveReasoningEffort(ReasoningEffort.LOW)
            fail("expected ApiError")
        } catch (e: ApiError.Http) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun `saveReasoningEffort network failure surfaces IOException`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            client.saveReasoningEffort(ReasoningEffort.LOW)
            fail("expected IOException or ApiError")
        } catch (e: Exception) {
            // ApiError wraps the IO failure with a user-friendly message.
            assertTrue(
                "expected ApiError or IOException, got ${e::class.simpleName}",
                e is ApiError || e is java.io.IOException,
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun json(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
