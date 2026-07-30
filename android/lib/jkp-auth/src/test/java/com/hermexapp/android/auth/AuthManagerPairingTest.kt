package com.hermexapp.android.auth

import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.SessionCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthManagerPairingTest {

    private lateinit var server: MockWebServer
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var cookieJar: SessionCookieJar
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        secretStore = InMemorySecretStore()
        cookieJar = SessionCookieJar(secretStore)
        httpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun makeManager() = AuthManager(
        secretStore = secretStore,
        cookieJar = cookieJar,
        clientFactory = { ApiClient(it, httpClient) },
        logoutTimeoutMillis = 2_000,
    )

    private fun serverUrlString() = "http://${server.hostName}:${server.port}"

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun completePairingUrl(pairId: String, token: String): String =
        "${serverUrlString()}/v1/pair/connect?pair_id=$pairId&token=$token"

    @Test
    fun `pairAndConfigure with full URL posts complete and persists grant`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"pair_id":"p1","device_id":"dev1","grant":"g-secret","token_type":"Bearer"}""",
            ),
        )

        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            rawText = completePairingUrl("p1", "t-secret"),
            deviceName = "Test phone",
        )

        assertTrue("expected Paired, got $outcome", outcome is PairingOutcome.Paired)
        val paired = outcome as PairingOutcome.Paired
        assertEquals(server.hostName, paired.serverUrl.host)
        assertEquals("dev1", paired.deviceId)
        assertTrue(manager.state.value is AuthManager.State.LoggedIn)
        assertNull(manager.lastErrorMessage.value)

        // Grant + device id persisted per-server, server URL saved.
        assertEquals(
            "g-secret",
            secretStore.load(SecretStore.Key.PAIR_GRANT, scope = server.hostName),
        )
        assertEquals(
            "dev1",
            secretStore.load(SecretStore.Key.PAIR_DEVICE_ID, scope = server.hostName),
        )
        assertNotNull(secretStore.load(SecretStore.Key.SERVER_URL))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/pair/complete", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body has pair_id: $body", body.contains("\"pair_id\":\"p1\""))
        assertTrue("body has token: $body", body.contains("\"token\":\"t-secret\""))
        assertTrue("body has device_name: $body", body.contains("\"device_name\":\"Test phone\""))
    }

    @Test
    fun `pairAndConfigure with URL missing pair id returns Prefilled and does not call`() = runBlocking {
        // No enqueue → if a request is made, MockWebServer will close
        // the connection. We assert it isn't called.
        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            rawText = "${serverUrlString()}/v1/pair/connect?token=t",
            deviceName = "Test phone",
        )
        assertTrue("expected Prefilled, got $outcome", outcome is PairingOutcome.Prefilled)
        assertNull(manager.lastErrorMessage.value)
        assertTrue(manager.state.value is AuthManager.State.Unconfigured)
        // Nothing should be persisted.
        assertNull(secretStore.load(SecretStore.Key.SERVER_URL))
    }

    @Test
    fun `pairAndConfigure with plain URL (no pair path) returns Prefilled`() = runBlocking {
        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            rawText = "${serverUrlString()}/api/sessions",
            deviceName = "Test phone",
        )
        assertTrue(outcome is PairingOutcome.Prefilled)
        assertNull(manager.lastErrorMessage.value)
        assertNull(secretStore.load(SecretStore.Key.SERVER_URL))
    }

    @Test
    fun `pairAndConfigure with empty input returns Failed and surfaces error`() = runBlocking {
        val manager = makeManager()
        val outcome = manager.pairAndConfigure("", "Test phone")
        assertTrue(outcome is PairingOutcome.Failed)
        assertNotNull(manager.lastErrorMessage.value)
        assertTrue(manager.state.value is AuthManager.State.Unconfigured)
    }

    @Test
    fun `pairAndConfigure with invalid URL returns Failed`() = runBlocking {
        val manager = makeManager()
        val outcome = manager.pairAndConfigure("not a url", "Test phone")
        assertTrue(outcome is PairingOutcome.Failed)
        assertNotNull(manager.lastErrorMessage.value)
    }

    @Test
    fun `pairAndConfigure on 410 expired reports error and does not persist`() = runBlocking {
        server.enqueue(
            jsonResponse(410, """{"error":"pairing token expired"}"""),
        )
        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            completePairingUrl("p", "t"),
            "Test phone",
        )
        assertTrue(outcome is PairingOutcome.Failed)
        assertNotNull(manager.lastErrorMessage.value)
        assertTrue(manager.state.value is AuthManager.State.Unconfigured)
        assertNull(secretStore.load(SecretStore.Key.SERVER_URL))
        assertNull(secretStore.load(SecretStore.Key.PAIR_GRANT, scope = server.hostName))
    }

    @Test
    fun `pairAndConfigure on connection failure returns Failed and reports`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            completePairingUrl("p", "t"),
            "Test phone",
        )
        assertTrue(outcome is PairingOutcome.Failed)
        assertNotNull(manager.lastErrorMessage.value)
        assertTrue(manager.state.value is AuthManager.State.Unconfigured)
    }

    @Test
    fun `pairAndConfigure on empty grant in response returns Failed and does not persist`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"pair_id":"p","device_id":"","grant":"","token_type":"Bearer"}""",
            ),
        )
        val manager = makeManager()
        val outcome = manager.pairAndConfigure(
            completePairingUrl("p", "t"),
            "Test phone",
        )
        assertTrue(outcome is PairingOutcome.Failed)
        assertTrue(
            "error mentions empty grant: ${manager.lastErrorMessage.value}",
            manager.lastErrorMessage.value.orEmpty().contains("grant", ignoreCase = true),
        )
        assertNull(secretStore.load(SecretStore.Key.SERVER_URL))
    }

    @Test
    fun `signOut clears pair grant and device id for the active server`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"pair_id":"p","device_id":"dev","grant":"g","token_type":"Bearer"}""",
            ),
        )
        val manager = makeManager()
        manager.pairAndConfigure(completePairingUrl("p", "t"), "Test phone")

        // Grant + device id should be persisted.
        assertEquals(
            "g",
            secretStore.load(SecretStore.Key.PAIR_GRANT, scope = server.hostName),
        )
        assertEquals(
            "dev",
            secretStore.load(SecretStore.Key.PAIR_DEVICE_ID, scope = server.hostName),
        )

        // signOut should drop them per-server.
        manager.signOut()
        assertNull(
            secretStore.load(SecretStore.Key.PAIR_GRANT, scope = server.hostName),
        )
        assertNull(
            secretStore.load(SecretStore.Key.PAIR_DEVICE_ID, scope = server.hostName),
        )
        assertNull(secretStore.load(SecretStore.Key.SERVER_URL))
    }
}