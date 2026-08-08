package com.hermexapp.android.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The ceiling on an in-memory file download.
 *
 * `/api/media` enforces no size limit of its own — probed on the live host, it
 * served a 3 MB file whole, where `/api/file` refuses anything over 400,000
 * bytes with "File too large". So an image is only as bounded as this client
 * makes it, and without a ceiling one oversized file is a single allocation
 * large enough to take the app down.
 */
class GetBytesLimitTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun bodyOf(size: Int) = Buffer().write(ByteArray(size))

    @Test
    fun `a body under the ceiling is returned whole`() = runTest {
        server.enqueue(MockResponse().setBody(bodyOf(1_024)))

        val bytes = client.getBytes(Endpoint.MEDIA, mapOf("path" to "/x.png"), maxBytes = 4_096)

        assertEquals(1_024, bytes.size)
    }

    @Test
    fun `a body exactly at the ceiling is still allowed`() = runTest {
        // The limit is inclusive; an off-by-one here would reject a file the
        // message says is acceptable.
        server.enqueue(MockResponse().setBody(bodyOf(4_096)))

        val bytes = client.getBytes(Endpoint.MEDIA, mapOf("path" to "/x.png"), maxBytes = 4_096)

        assertEquals(4_096, bytes.size)
    }

    @Test
    fun `an oversized body is refused by its declared length`() = runTest {
        server.enqueue(MockResponse().setBody(bodyOf(8_192)))

        try {
            client.getBytes(Endpoint.MEDIA, mapOf("path" to "/big.png"), maxBytes = 4_096)
            fail("expected TooLarge rather than an 8 KB allocation")
        } catch (e: ApiError.TooLarge) {
            assertEquals(8_192L, e.declaredBytes)
            assertEquals(4_096L, e.maxBytes)
        }
    }

    @Test
    fun `an oversized chunked body is refused even with no declared length`() = runTest {
        // Content-Length absent is exactly where a guard that trusted the
        // header alone would let everything through.
        server.enqueue(MockResponse().setChunkedBody(bodyOf(8_192), 1_024))

        try {
            client.getBytes(Endpoint.MEDIA, mapOf("path" to "/big.png"), maxBytes = 4_096)
            fail("expected TooLarge for an undeclared oversized body")
        } catch (e: ApiError.TooLarge) {
            assertEquals(4_096L, e.maxBytes)
        }
    }

    @Test
    fun `a chunked body under the ceiling still arrives whole`() = runTest {
        // `request(n)` must stop at end of stream rather than throwing, which
        // is what `readByteArray(n)` would do for any body shorter than n.
        server.enqueue(MockResponse().setChunkedBody(bodyOf(2_000), 512))

        val bytes = client.getBytes(Endpoint.MEDIA, mapOf("path" to "/x.png"), maxBytes = 4_096)

        assertEquals(2_000, bytes.size)
    }

    @Test
    fun `too-large says the size, not that the network failed`() {
        // A retry cannot help, so the copy must not read like a transient
        // failure. The size is what tells the reader why.
        val message = ApiError.TooLarge(8L * 1024 * 1024, 4L * 1024 * 1024).userMessage

        assertTrue("expected the actual size, got: $message", "8.0 MB" in message)
        assertTrue("expected the limit, got: $message", "4 MB" in message)
    }

    @Test
    fun `bytes are not decoded as text`() = runTest {
        // The reason this exists at all: the JSON path calls body.string(),
        // which would mangle any byte that is not valid UTF-8 — the same
        // corruption /api/file inflicts one layer up.
        val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 0xFE.toByte())
        server.enqueue(MockResponse().setBody(Buffer().write(raw)))

        val bytes = client.getBytes(Endpoint.MEDIA, mapOf("path" to "/x.png"), maxBytes = 4_096)

        assertTrue("bytes must survive the round trip unchanged", raw.contentEquals(bytes))
    }

    @Test
    fun `a 403 surfaces as an http error, which is what a relative path gets`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            client.getBytes(Endpoint.MEDIA, mapOf("path" to "relative.png"), maxBytes = 4_096)
            fail("expected the 403 the live endpoint returns for a relative path")
        } catch (e: ApiError.Http) {
            assertEquals(403, e.statusCode)
        }
    }
}
