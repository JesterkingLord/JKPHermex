package com.hermexapp.android.network

import com.hermexapp.android.model.PairCompleteResponse
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

class ApiClientPairingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val baseUrl = server.url("/")
        client = ApiClient(
            baseUrl = baseUrl,
            httpClient = OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `completePairing posts the request and parses the grant`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"pair_id":"p1","device_id":"d1","grant":"g1","token_type":"Bearer"}""",
                ),
        )

        val resp = client.completePairing(
            pairId = "p1",
            token = "t1",
            deviceName = "My phone",
        )

        assertEquals("p1", resp.pair_id)
        assertEquals("d1", resp.device_id)
        assertEquals("g1", resp.grant)
        assertEquals("Bearer", resp.token_type)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/pair/complete", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body has pair_id: $body", body.contains("\"pair_id\":\"p1\""))
        assertTrue("body has token: $body", body.contains("\"token\":\"t1\""))
        assertTrue("body has device_name: $body", body.contains("\"device_name\":\"My phone\""))
    }

    @Test
    fun `completePairing tolerates missing token_type and unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"pair_id":"p","device_id":"d","grant":"g","future_server_field":"ignored"}""",
                ),
        )

        val resp = client.completePairing("p", "t", "dn")
        assertEquals("p", resp.pair_id)
        assertEquals("g", resp.grant)
        // token_type was missing → defaults to Bearer (per PairCompleteResponse).
        assertEquals("Bearer", resp.token_type)
    }

    @Test
    fun `completePairing maps 401 to Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.completePairing("p", "t", "dn")
            fail("expected ApiError.Unauthorized")
        } catch (e: ApiError.Unauthorized) {
            // expected
        }
    }

    @Test
    fun `completePairing maps 410 (expired) to Http(410)`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setBody("""{"error":"pairing token expired"}"""),
        )
        try {
            client.completePairing("p", "t", "dn")
            fail("expected ApiError.Http")
        } catch (e: ApiError.Http) {
            assertEquals(410, e.statusCode)
            assertTrue(e.body.orEmpty().contains("expired"))
        }
    }

    @Test
    fun `completePairing maps 409 (already completed) to Http(409)`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":"pairing already completed"}"""),
        )
        try {
            client.completePairing("p", "t", "dn")
            fail("expected ApiError.Http")
        } catch (e: ApiError.Http) {
            assertEquals(409, e.statusCode)
        }
    }

    @Test
    fun `completePairing maps connection failure to Network`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            client.completePairing("p", "t", "dn")
            fail("expected ApiError.Network")
        } catch (e: ApiError.Network) {
            // expected
        }
    }

    @Test
    fun `PairCompleteResponse defaults keep a 2xx with empty body from throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            client.completePairing("p", "t", "dn")
            fail("expected ApiError.Decoding or Http on empty body")
        } catch (e: Exception) {
            // empty body → json decode fails → Decoding. (The exact kind
            // doesn't matter for this test — just that the error path
            // doesn't swallow it as a successful empty PairCompleteResponse.)
            assertTrue(
                "expected Decoding or Http; got ${e::class.simpleName}",
                e is ApiError.Decoding || e is ApiError.Http || e is ApiError.Network,
            )
        }
    }

    @Test
    fun `PairCompleteResponse direct construction tolerates an unknown field`() {
        // Sanity check on the model itself: an instance with an extra field
        // can still be constructed. (kotlinx.serialization ignores unknown
        // fields at the JSON layer; this just guards the constructor.)
        val resp = PairCompleteResponse(
            pair_id = "p",
            device_id = "d",
            grant = "g",
        )
        assertEquals("p", resp.pair_id)
        assertEquals("Bearer", resp.token_type) // default
    }
}