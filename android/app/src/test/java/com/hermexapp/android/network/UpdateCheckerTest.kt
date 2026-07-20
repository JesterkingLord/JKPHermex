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
 * Tests the two-route update check: Releases API first, git-refs fallback
 * when the API rate-limits (the real-world phone failure mode on shared
 * carrier IPs — the phone's VPN/carrier IP gets HTTP 403 from GitHub's
 * 60 req/hour unauthenticated API quota).
 *
 * The git smart-HTTP fallback (`/info/refs?service=git-upload-pack`) is NOT
 * rate-limited and survives VPN interception, making it the reliable
 * secondary route.
 */
class UpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker
    private val client = OkHttpClient()

    /** Realistic pkt-line git smart-HTTP refs body. */
    private fun gitRefsBody(vararg tags: String): String = buildString {
        append("001e# service=git-upload-pack\n0000\n")
        for (tag in tags) {
            // sha + refname + optional peeled annotated-tag line
            append("0041c20abd67aaa92ba1628c9cd358132549166f631f refs/tags/$tag\n")
            append("0041003ee4c83897ab9047b82e73315b73f0e8f78ab9d242 refs/tags/$tag^{}\n")
        }
    }

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
    fun `API 403 rate-limit falls back to git refs`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limit"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(gitRefsBody("v0.5.0", "v9.9.9")),
        )
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue("expected fallback UpdateAvailable, got $result", result is UpdateResult.UpdateAvailable)
        assertEquals("v9.9.9", (result as UpdateResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun `API 403 then git refs same version reports UpToDate`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(gitRefsBody("v0.6.0-rc6")),
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
    fun `API 404 reports no-releases reason when git refs also empty`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("001e# service=git-upload-pack\n0000\n"))
        val result = checker.checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.Failed)
        assertTrue((result as UpdateResult.Failed).reason.contains("No releases"))
    }

    @Test
    fun `newestTagFromGitRefs picks highest semver tag`() {
        val body = gitRefsBody("v0.3.0", "v0.4.0", "v0.4.1", "v0.5.0", "v0.6.0-rc6")
        assertEquals("v0.6.0-rc6", UpdateChecker.newestTagFromGitRefs(body))
    }

    @Test
    fun `newestTagFromGitRefs orders pre-releases correctly (rc6 greater than rc1)`() {
        val body = gitRefsBody("v0.6.0-rc1", "v0.6.0-rc6", "v0.6.0-rc3")
        assertEquals("v0.6.0-rc6", UpdateChecker.newestTagFromGitRefs(body))
    }

    @Test
    fun `newestTagFromGitRefs prefers stable release over pre-release`() {
        val body = gitRefsBody("v1.0.0-rc1", "v1.0.0")
        assertEquals("v1.0.0", UpdateChecker.newestTagFromGitRefs(body))
    }

    @Test
    fun `newestTagFromGitRefs skips non-semver tags`() {
        val body = gitRefsBody("nightly-build", "v1.2.3", "latest")
        assertEquals("v1.2.3", UpdateChecker.newestTagFromGitRefs(body))
    }

    @Test
    fun `newestTagFromGitRefs returns null when no tags present`() {
        assertNull(UpdateChecker.newestTagFromGitRefs("001e# service=git-upload-pack\n0000\n"))
    }

    @Test
    fun `newestTagFromGitRefs handles natural numeric ordering (rc10 greater than rc9)`() {
        val body = gitRefsBody("v0.6.0-rc9", "v0.6.0-rc10", "v0.6.0-rc2")
        assertEquals("v0.6.0-rc10", UpdateChecker.newestTagFromGitRefs(body))
    }

    // ── Route 0: backend ──────────────────────────────────────────────────

    private fun backendChecker(): UpdateChecker {
        val base = server.url("/").toString().trimEnd('/')
        return UpdateChecker(
            owner = "JesterkingLord", repo = "JKPHermex",
            httpClient = client, apiBase = base, webBase = base,
            backendBaseUrl = base,
        )
    }

    @Test
    fun `backend route returns UpdateAvailable when tag is newer`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag":"v9.9.9","url":"https://x","source":"host","cached":false}""",
            ),
        )
        val result = backendChecker().checkAgainst("0.6.0-rc6")
        assertTrue("expected UpdateAvailable, got $result", result is UpdateResult.UpdateAvailable)
        assertEquals("v9.9.9", (result as UpdateResult.UpdateAvailable).latestVersion)
    }

    @Test
    fun `backend route returns UpToDate when tag matches`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag":"v0.6.0-rc6","url":"https://x","source":"host","cached":true}""",
            ),
        )
        val result = backendChecker().checkAgainst("0.6.0-rc6")
        assertTrue("expected UpToDate, got $result", result is UpdateResult.UpToDate)
    }

    @Test
    fun `backend unreachable falls back to API`() {
        // Route 0: backend fails.
        server.enqueue(MockResponse().setResponseCode(500))
        // Route 1: API succeeds.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"tag_name":"v9.9.9","name":"x","body":"","html_url":"","assets":[]}]""",
            ),
        )
        val result = backendChecker().checkAgainst("0.6.0-rc6")
        assertTrue("expected UpdateAvailable via API fallback, got $result", result is UpdateResult.UpdateAvailable)
    }

    @Test
    fun `backend returns null tag falls back to API`() {
        // Route 0: backend returns no tag.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag":null,"url":"","source":"host","cached":false}""",
            ),
        )
        // Route 1: API succeeds.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"tag_name":"v9.9.9","name":"x","body":"","html_url":"","assets":[]}]""",
            ),
        )
        val result = backendChecker().checkAgainst("0.6.0-rc6")
        assertTrue("expected UpdateAvailable via API fallback, got $result", result is UpdateResult.UpdateAvailable)
    }

    @Test
    fun `all routes fail reports the API reason`() {
        server.enqueue(MockResponse().setResponseCode(500)) // backend
        server.enqueue(MockResponse().setResponseCode(403)) // API
        server.enqueue(MockResponse().setResponseCode(500)) // git-refs
        val result = backendChecker().checkAgainst("0.6.0-rc6")
        assertTrue(result is UpdateResult.Failed)
        val reason = (result as UpdateResult.Failed).reason
        assertTrue("expected rate-limit reason, got: $reason", reason.contains("rate limit"))
    }
}
