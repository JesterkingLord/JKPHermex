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
}
