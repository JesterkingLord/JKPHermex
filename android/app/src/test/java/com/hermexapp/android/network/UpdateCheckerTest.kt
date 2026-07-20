package com.hermexapp.android.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the two-route update check: Releases API first, tags-page fallback
 * when the API rate-limits (the real-world phone failure mode on shared
 * carrier IPs).
 */
class UpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        checker = UpdateChecker(
            owner = "JesterkingLord",
            repo = "JKPHermex",
            httpClient = client,
            apiBase = base,
            webBase = base,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `API 200 with newer tag reports UpdateAvailable`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"tag_name":"v9.9.9","name":"v9.9.9","body":"","html_url":"https://x","assets":[]}]""",
            ),
        )
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.UpdateAvailable)
        assertEquals("v9.9.9", (result as UpdateResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun `API 200 with same tag reports UpToDate`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"tag_name":"v0.6.0-rc6","name":"x","body":"","html_url":"","assets":[]}]""",
            ),
        )
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue("expected UpToDate, got $result", result is UpdateResult.UpToDate)
    }

    @Test
    fun `API 403 rate-limit falls back to tags page`() {
        // Route 1: rate-limited.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limit"}"""))
        // Route 2: tags page with the newest tag.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<a href="/JesterkingLord/JKPHermex/releases/tag/v9.9.9">v9.9.9</a>""",
            ),
        )
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue("expected fallback UpdateAvailable, got $result", result is UpdateResult.UpdateAvailable)
        assertEquals("v9.9.9", (result as UpdateResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun `API 403 then tags page same version reports UpToDate`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<a href="/releases/tag/v0.6.0-rc6">v0.6.0-rc6</a>""",
            ),
        )
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.UpToDate)
    }

    @Test
    fun `both routes fail reports the API reason`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(500))
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.Failed)
        val reason = (result as UpdateResult.Failed).reason
        assertTrue("expected rate-limit reason, got: $reason", reason.contains("rate limit"))
    }

    @Test
    fun `API 404 reports no-releases reason when tags page also empty`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>no tags</html>"))
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.Failed)
        assertTrue((result as UpdateResult.Failed).reason.contains("No releases"))
    }

    @Test
    fun `newestTagFromHtml picks first semver tag in document order`() {
        val html = """
            <a href="/o/r/releases/tag/v0.5.0">v0.5.0</a>
            <a href="/o/r/releases/tag/v0.6.0-rc6">v0.6.0-rc6</a>
        """.trimIndent()
        assertEquals("v0.5.0", UpdateChecker.newestTagFromHtml(html))
    }

    @Test
    fun `newestTagFromHtml skips non-semver tags`() {
        val html = """
            <a href="/o/r/releases/tag/nightly-build">nightly</a>
            <a href="/o/r/releases/tag/v1.2.3">v1.2.3</a>
        """.trimIndent()
        assertEquals("v1.2.3", UpdateChecker.newestTagFromHtml(html))
    }

    @Test
    fun `newestTagFromHtml returns null when no tags present`() {
        assertNull(UpdateChecker.newestTagFromHtml("<html>nothing here</html>"))
    }
}
