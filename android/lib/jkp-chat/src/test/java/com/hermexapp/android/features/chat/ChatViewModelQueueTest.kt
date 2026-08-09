package com.hermexapp.android.features.chat

import com.hermexapp.android.auth.InMemorySecretStore
import com.hermexapp.android.features.chat.ChatViewModel.TimelineEntry
import com.hermexapp.android.features.sessionlist.SessionRepositoryImpl
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.SessionCookieJar
import com.hermexapp.android.network.SseEvent
import com.hermexapp.android.network.SseStreaming
import com.hermexapp.android.persistence.InMemoryCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.8.15 — client-side queue state machine behind /queue, /interrupt, and
 * the stream-end drain. The queue lives in the ViewModel because the Hermes
 * server has no native queue route; these tests lock the enqueue / atFront /
 * drain contract without any server-side queue involvement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQueueTest {

    private class FakeSse : SseStreaming {
        var startedUrl: HttpUrl? = null
        private var listener: ((SseEvent) -> Unit)? = null

        override fun start(url: HttpUrl, onEvent: (SseEvent) -> Unit) {
            startedUrl = url
            listener = onEvent
        }

        override fun stop() = Unit

        fun emit(event: SseEvent) = listener!!.invoke(event)
    }

    private lateinit var server: MockWebServer
    private lateinit var viewModel: ChatViewModel
    private lateinit var sse: FakeSse

    @Before
    fun setUp() {
        // send() launches on viewModelScope (Dispatchers.Main) — install a
        // test Main dispatcher so drained sends run eagerly on this thread.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        val client = ApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient.Builder()
                .cookieJar(SessionCookieJar(InMemorySecretStore()))
                .build(),
        )
        sse = FakeSse()
        viewModel = ChatViewModel(
            sessionId = "abc",
            repository = SessionRepositoryImpl(client, InMemoryCacheStore()),
            client = client,
            sse = sse,
        )
    }

    @After
    fun tearDown() {
        viewModel.teardown()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun enqueueStartResponse() {
        server.enqueue(MockResponse().setBody("""{"stream_id": "st1", "session_id": "abc"}"""))
    }

    /** Polls until [condition] holds (drained sends hop to the IO dispatcher). */
    private fun waitUntil(condition: () -> Boolean) {
        runBlocking {
            withTimeout(3_000) {
                while (!condition()) delay(10)
            }
        }
    }

    @Test
    fun `enqueueMessage appends and updates the chip label`() {
        viewModel.enqueueMessage("msg1", emptyList())
        var state = viewModel.uiState.value
        assertEquals(1, state.queuedMessages.size)
        assertEquals("msg1", state.queuedMessages.first().text)
        assertTrue(state.queuedMessagesLabel.contains("#1"))

        viewModel.enqueueMessage("msg2", emptyList())
        state = viewModel.uiState.value
        assertEquals(2, state.queuedMessages.size)
        assertEquals("msg2", state.queuedMessages.last().text)
        assertTrue(state.queuedMessagesLabel.contains("#2"))
    }

    @Test
    fun `enqueueMessage with atFront puts the message at the head`() {
        viewModel.enqueueMessage("msg1", emptyList())
        viewModel.enqueueMessage("msg2", emptyList(), atFront = true)

        val state = viewModel.uiState.value
        assertEquals(2, state.queuedMessages.size)
        assertEquals("msg2", state.queuedMessages.first().text)
        assertEquals("msg1", state.queuedMessages.last().text)
    }

    @Test
    fun `drainQueuedMessages sends the head and keeps the rest`() {
        viewModel.enqueueMessage("first", emptyList())
        viewModel.enqueueMessage("second", emptyList())
        enqueueStartResponse()

        viewModel.drainQueuedMessages()

        // Queue bookkeeping is synchronous inside drain.
        var state = viewModel.uiState.value
        assertEquals(1, state.queuedMessages.size)
        assertEquals("second", state.queuedMessages.first().text)
        assertTrue(state.queuedMessagesLabel.contains("#1"))

        // The head went out as a fresh user turn (async send).
        waitUntil {
            viewModel.uiState.value.entries.any {
                it is TimelineEntry.UserMessage && it.text == "first"
            }
        }
    }

    @Test
    fun `drainQueuedMessages is a no-op on an empty queue`() {
        viewModel.drainQueuedMessages()

        val state = viewModel.uiState.value
        assertTrue(state.queuedMessages.isEmpty())
        assertTrue(state.queuedMessagesLabel.isEmpty())
        assertTrue(state.entries.isEmpty())
        assertTrue(!state.isStreaming)
    }

    @Test
    fun `stream end drains the queue into a fresh run`() = runBlocking {
        enqueueStartResponse()
        viewModel.updateComposerText("first turn")
        viewModel.sendNow()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.enqueueMessage("follow-up", emptyList())
        assertEquals(1, viewModel.uiState.value.queuedMessages.size)

        // Start response for the drained follow-up run.
        enqueueStartResponse()
        sse.emit(SseEvent.StreamEnd)

        // Queue emptied synchronously; the follow-up is a user turn and a
        // new run started (async send observed via polling).
        waitUntil {
            val state = viewModel.uiState.value
            state.queuedMessages.isEmpty() &&
                state.entries.any { it is TimelineEntry.UserMessage && it.text == "follow-up" } &&
                state.isStreaming
        }
    }

    @Test
    fun `cancel drains the queue into a fresh run, not a steer on the dead one`() = runBlocking {
        // The Cancelled branch drained the queue while isStreaming was still
        // true, so send() routed the queued message to steerNow() — steering a
        // run that had just been cancelled. It never became a turn of its own
        // and its attachments were dropped. Only StreamEnd finished the run
        // first; nothing covered Cancelled at all.
        enqueueStartResponse()
        viewModel.updateComposerText("first turn")
        viewModel.sendNow()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.enqueueMessage("after cancel", emptyList())
        assertEquals(1, viewModel.uiState.value.queuedMessages.size)

        // A start response is only consumed if the drain begins a NEW run.
        // Were it steered instead, this would go unused and the assertion
        // below would never see the message as a user turn.
        enqueueStartResponse()
        sse.emit(SseEvent.Cancelled)

        waitUntil { viewModel.uiState.value.queuedMessages.isEmpty() }

        // Assert on the wire, not the timeline: steerNow() also appends a user
        // entry, so a timeline check passes either way and proves nothing.
        // Only the endpoint distinguishes a new turn from a steer.
        val paths = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }
            .map { it.path.orEmpty() }
            .takeWhile { it.isNotEmpty() }
            .toList()
        assertTrue(
            "the drained message must start a run, not steer the cancelled one; saw $paths",
            paths.any { "/api/chat/start" in it },
        )
        assertTrue(
            "nothing may be steered into a cancelled run; saw $paths",
            paths.none { "/api/chat/steer" in it },
        )
    }

    @Test
    fun `cancel with an empty queue stops streaming instead of hanging`() = runBlocking {
        // Cancelled never called sse.stop()/finishStreaming(), so a host that
        // closed without a stream_end left the run "streaming" for good — STOP
        // button live, stall watch armed, no way back.
        enqueueStartResponse()
        viewModel.updateComposerText("only turn")
        viewModel.sendNow()
        assertTrue(viewModel.uiState.value.isStreaming)

        sse.emit(SseEvent.Cancelled)

        waitUntil { !viewModel.uiState.value.isStreaming }
    }
}
