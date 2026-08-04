package com.hermexapp.android.features.chat

import com.hermexapp.android.auth.InMemorySecretStore
import com.hermexapp.android.features.sessionlist.SessionRepositoryImpl
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.SessionCookieJar
import com.hermexapp.android.network.SseEvent
import com.hermexapp.android.network.SseStreaming
import com.hermexapp.android.persistence.InMemoryCacheStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * v0.8.14 — the composer's connection-failure path. The send affordance must
 * stay visible but inert while the transport reports the host offline/failed,
 * and the copy line beneath the composer must carry the spec wording.
 */
class ChatComposerOfflineTest {

    private class FakeSse : SseStreaming {
        override fun start(url: HttpUrl, onEvent: (SseEvent) -> Unit) = Unit
        override fun stop() = Unit
    }

    private var server: MockWebServer? = null
    private var viewModel: ChatViewModel? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        viewModel?.teardown()
        viewModel = null
        server?.shutdown()
        server = null
    }

    private fun viewModel(baseUrl: HttpUrl = server!!.url("/")): ChatViewModel {
        val client = ApiClient(
            baseUrl = baseUrl,
            httpClient = OkHttpClient.Builder()
                .cookieJar(SessionCookieJar(InMemorySecretStore()))
                .build(),
        )
        return ChatViewModel(
            sessionId = "abc",
            repository = SessionRepositoryImpl(client, InMemoryCacheStore()),
            client = client,
            sse = FakeSse(),
        ).also { viewModel = it }
    }

    @Test
    fun `offline transport keeps a typed draft from enabling send`() {
        assertEquals(
            ComposerPrimaryAction.DISABLED_SEND,
            composerPrimaryAction(
                isStreaming = false,
                hasDraft = true,
                connectionState = JkpConnectionState.OFFLINE,
            ),
        )
    }

    @Test
    fun `failed transport keeps a typed draft from enabling send`() {
        assertEquals(
            ComposerPrimaryAction.DISABLED_SEND,
            composerPrimaryAction(
                isStreaming = false,
                hasDraft = true,
                connectionState = JkpConnectionState.FAILED,
            ),
        )
    }

    @Test
    fun `offline transport without a draft stays disabled`() {
        assertEquals(
            ComposerPrimaryAction.DISABLED_SEND,
            composerPrimaryAction(
                isStreaming = false,
                hasDraft = false,
                connectionState = JkpConnectionState.OFFLINE,
            ),
        )
    }

    @Test
    fun `connected transport with a draft exposes send`() {
        assertEquals(
            ComposerPrimaryAction.SEND,
            composerPrimaryAction(
                isStreaming = false,
                hasDraft = true,
                connectionState = JkpConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `streaming still exposes stop while the transport is offline`() {
        assertEquals(
            ComposerPrimaryAction.STOP,
            composerPrimaryAction(
                isStreaming = true,
                hasDraft = false,
                connectionState = JkpConnectionState.OFFLINE,
            ),
        )
    }

    @Test
    fun `the offline copy line matches the spec wording`() {
        assertEquals(
            "The connection is offline; your message will send when the host is back.",
            COMPOSER_OFFLINE_COPY,
        )
    }

    @Test
    fun `viewmodel exposes a read-only connection state via the tiny wrapper`() {
        val vm = viewModel()
        assertEquals(JkpConnectionState.CONNECTED, vm.uiState.value.connectionState)

        vm.setConnectionState(JkpConnectionState.OFFLINE)
        assertEquals(JkpConnectionState.OFFLINE, vm.uiState.value.connectionState)

        vm.setConnectionState(JkpConnectionState.FAILED)
        assertEquals(JkpConnectionState.FAILED, vm.uiState.value.connectionState)
    }

    @Test
    fun `a failed transport marks the connection failed`() = runBlocking {
        // Host is gone before any traffic: the connect is refused.
        val url = server!!.url("/")
        server!!.shutdown()
        server = null

        val vm = viewModel(url)
        vm.updateComposerText("hi")
        vm.sendNow()

        assertEquals(JkpConnectionState.FAILED, vm.uiState.value.connectionState)
    }
}
