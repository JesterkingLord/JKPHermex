package com.hermexapp.android.features.chat

import com.hermexapp.android.config.AppPrefs
import com.hermexapp.android.config.InMemoryKeyValueStore
import com.hermexapp.android.features.sessionlist.SessionRepository
import com.hermexapp.android.model.ReasoningEffort
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.SseStreaming
import com.hermexapp.android.persistence.InMemoryCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reasoning-effort and show-reasoning state in [ChatViewModel]. Mirrors
 * the iOS `ChatViewModelReasoningTests` and the desktop
 * `parse_reasoning_effort` semantics — the server is the source of truth,
 * local prefs are the cache, and the user is informed of clamps.
 *
 * `viewModelScope` uses `Dispatchers.Main.immediate`; in unit tests there's
 * no Main dispatcher, so we install a `UnconfinedTestDispatcher` for the
 * duration of each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelReasoningTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private lateinit var prefs: AppPrefs
    private lateinit var viewModel: ChatViewModel
    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher

    @Before
    fun setUp() {
        // StandardTestDispatcher queues work so advanceUntilIdle can drain
        // it deterministically. UnconfinedTestDispatcher would let launched
        // coroutines run in the worker thread and escape our advance() calls.
        testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer().also { it.start() }
        client = ApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient.Builder().build(),
            // Route the IO dispatcher through the test scheduler so
            // advanceUntilIdle can drain the launched-coroutine HTTP call.
            ioDispatcher = testDispatcher,
        )
        prefs = AppPrefs(InMemoryKeyValueStore())
        viewModel = ChatViewModel(
            sessionId = "s1",
            repository = SessionRepository(client, InMemoryCacheStore()),
            client = client,
            sse = noopSse,
            prefs = prefs,
        )
    }

    @After
    fun tearDown() {
        // Drain any in-flight launches so a stray coroutine doesn't fail
        // the next test's setup (UncaughtExceptionsBeforeTest). Only safe
        // to call from @After — the testScheduler is bound to Main.
        try {
            testDispatcher.scheduler.advanceUntilIdle()
        } catch (_: Throwable) { /* shutdown race */ }
        Dispatchers.resetMain()
        server.shutdown()
    }

    // ── Defaults ─────────────────────────────────────────────────────────

    @Test
    fun `default state is AUTO and showReasoning`() {
        assertEquals(ReasoningEffort.AUTO, viewModel.uiState.value.selectedReasoningEffort)
        assertTrue(viewModel.uiState.value.showReasoning)
        assertNull(viewModel.uiState.value.reasoningErrorMessage)
    }

    @Test
    fun `prefs hydrate the initial UI state on construction`() = runTest {
        val store = InMemoryKeyValueStore()
        AppPrefs(store).apply {
            setReasoningEffort(ReasoningEffort.XHIGH)
            setShowReasoning(false)
        }
        val vm = ChatViewModel(
            sessionId = "s1",
            repository = SessionRepository(client, InMemoryCacheStore()),
            client = client,
            sse = noopSse,
            prefs = AppPrefs(store),
        )
        assertEquals(ReasoningEffort.XHIGH, vm.uiState.value.selectedReasoningEffort)
        assertFalse(vm.uiState.value.showReasoning)
    }

    @Test
    fun `null prefs keeps the defaults`() = runTest {
        val vm = ChatViewModel(
            sessionId = "s1",
            repository = SessionRepository(client, InMemoryCacheStore()),
            client = client,
            sse = noopSse,
            prefs = null,
        )
        assertEquals(ReasoningEffort.AUTO, vm.uiState.value.selectedReasoningEffort)
        assertTrue(vm.uiState.value.showReasoning)
    }

    // ── loadReasoningState (suspend, awaited directly) ─────────────────

    @Test
    fun `loadReasoningState reads the server and updates UI plus prefs`() = runTest {
        server.enqueue(json(STATUS_HIGH_HIDDEN))
        viewModel.loadReasoningState()
        // The suspend function awaited the call; state is updated.
        val state = viewModel.uiState.value
        assertEquals(ReasoningEffort.HIGH, state.selectedReasoningEffort)
        assertFalse(state.showReasoning)
        assertEquals(ReasoningEffort.HIGH, prefs.reasoningEffort.value)
        assertFalse(prefs.showReasoning.value)
        val req = server.takeRequest()
        assertEquals("/api/reasoning", req.path)
        assertEquals("GET", req.method)
    }

    // ── selectReasoningEffort — optimistic path ─────────────────────────

    @Test
    fun `selectReasoningEffort optimistically updates UI and persists to prefs`() = runTest {
        // Server returns the same effort we requested (no clamp) so the
        // optimistic update is preserved after the launch completes.
        server.enqueue(json(STATUS_HIGH_HIDDEN))
        viewModel.selectReasoningEffort(ReasoningEffort.HIGH)
        testScheduler.advanceUntilIdle()
        assertEquals(ReasoningEffort.HIGH, viewModel.uiState.value.selectedReasoningEffort)
        assertEquals(ReasoningEffort.HIGH, prefs.reasoningEffort.value)
    }

    @Test
    fun `selectReasoningEffort honours server clamp when model does not support the level`() = runTest {
        // The user's request for HIGH gets clamped to MEDIUM on the server.
        server.enqueue(json(STATUS_MEDIUM))
        viewModel.selectReasoningEffort(ReasoningEffort.HIGH)
        // Yield to let the server call complete.
        testScheduler.advanceUntilIdle()
        assertEquals(ReasoningEffort.MEDIUM, viewModel.uiState.value.selectedReasoningEffort)
        assertEquals(ReasoningEffort.MEDIUM, prefs.reasoningEffort.value)
        // A user-facing message explains the clamp.
        assertNotNull(viewModel.uiState.value.reasoningErrorMessage)
    }

    @Test
    fun `selectReasoningEffort does nothing when the value is unchanged`() {
        val before = viewModel.uiState.value.selectedReasoningEffort
        viewModel.selectReasoningEffort(before)
        assertEquals(before, viewModel.uiState.value.selectedReasoningEffort)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `setShowReasoning optimistically flips the state and persists`() = runTest {
        val before = viewModel.uiState.value.showReasoning
        server.enqueue(json(STATUS_HIDDEN_OK))
        viewModel.setShowReasoning(!before)
        testScheduler.advanceUntilIdle()
        assertEquals(!before, viewModel.uiState.value.showReasoning)
        assertEquals(!before, prefs.showReasoning.value)
    }

    @Test
    fun `setShowReasoning is a no-op when the value is unchanged`() {
        val before = viewModel.uiState.value.showReasoning
        viewModel.setShowReasoning(before)
        assertEquals(0, server.requestCount)
    }

    // ── start() / send() include reasoning_effort in the wire body ──────

    @Test
    fun `start sends reasoning_effort only when not AUTO`() = runTest {
        prefs.setReasoningEffort(ReasoningEffort.HIGH)
        // Reconstruct the VM so the new prefs value is hydrated.
        val vm = ChatViewModel(
            sessionId = "s1",
            repository = SessionRepository(client, InMemoryCacheStore()),
            client = client,
            sse = noopSse,
            prefs = prefs,
        )
        server.enqueue(json(200, BODY_STREAM_ID))
        vm.updateComposerText("hello")
        vm.sendNow()
        testScheduler.advanceUntilIdle()
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(
            "chat/start body must include reasoning_effort: $body",
            body.contains(REASONING_FIELD_HIGH),
        )
    }

    @Test
    fun `start omits reasoning_effort when AUTO`() = runTest {
        server.enqueue(json(200, BODY_STREAM_ID))
        viewModel.updateComposerText("hello")
        viewModel.sendNow()
        testScheduler.advanceUntilIdle()
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertFalse("AUTO should not send reasoning_effort: $body", body.contains("reasoning_effort"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private val noopSse = object : SseStreaming {
        override fun start(url: okhttp3.HttpUrl, onEvent: (com.hermexapp.android.network.SseEvent) -> Unit) {}
        override fun stop() {}
    }

    private fun json(body: String): MockResponse = json(200, body)
    private fun json(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        // Wire-format constants — escape-quote sequences are intentional;
        // they're the literal characters that go on the wire.
        const val STATUS_HIGH_HIDDEN =
            "{\"ok\":true,\"show_reasoning\":false,\"reasoning_effort\":\"high\"}"
        const val STATUS_MEDIUM = "{\"ok\":true,\"reasoning_effort\":\"medium\"}"
        const val STATUS_HIDDEN_OK = "{\"ok\":true,\"show_reasoning\":false}"
        const val BODY_STREAM_ID = "{\"stream_id\":\"st\",\"session_id\":\"s1\"}"
        const val REASONING_FIELD_HIGH = "\"reasoning_effort\":\"high\""
    }
}
