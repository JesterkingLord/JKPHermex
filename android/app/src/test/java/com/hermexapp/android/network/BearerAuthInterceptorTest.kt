package com.hermexapp.android.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BearerAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds Authorization Bearer when grant is available`() {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { "jkp_device_test_grant" })
            .build()

        client.newCall(Request.Builder().url(server.url("/health")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer jkp_device_test_grant", recorded.getHeader("Authorization"))
        // Grant must never appear in the path/query.
        assertNull(recorded.requestUrl?.query)
        assertEquals("/health", recorded.path)
    }

    @Test
    fun `skips Authorization when grant is blank`() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { "  " })
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `does not overwrite existing Authorization header`() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { "jkp_device_should_not_win" })
            .build()

        client.newCall(
            Request.Builder()
                .url(server.url("/x"))
                .header("Authorization", "Bearer admin-key")
                .build(),
        ).execute().close()

        assertEquals("Bearer admin-key", server.takeRequest().getHeader("Authorization"))
    }
}
