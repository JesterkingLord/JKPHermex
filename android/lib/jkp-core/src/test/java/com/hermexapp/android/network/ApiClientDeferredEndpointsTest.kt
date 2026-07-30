package com.hermexapp.android.network

import com.hermexapp.android.auth.InMemorySecretStore
import com.hermexapp.android.model.ApprovalChoice
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract paths/bodies for the deferred-feature endpoints, matching the iOS Endpoint enum. */
class ApiClientDeferredEndpointsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient.Builder()
                .cookieJar(SessionCookieJar(InMemorySecretStore()))
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `approval and clarify respond post the iOS shapes`() = runBlocking {
        server.enqueue(json("""{"ok": true, "choice": "session"}"""))
        client.respondApproval("abc", ApprovalChoice.SESSION, "ap1")
        val approve = server.takeRequest()
        assertEquals("/api/approval/respond", approve.path)
        assertEquals(
            """{"session_id":"abc","choice":"session","approval_id":"ap1"}""",
            approve.body.readUtf8(),
        )

        server.enqueue(json("""{"ok": true, "response": "prod"}"""))
        client.respondClarification("abc", "prod", "cl1")
        val clarify = server.takeRequest()
        assertEquals("/api/clarify/respond", clarify.path)
        assertEquals(
            """{"session_id":"abc","response":"prod","clarify_id":"cl1"}""",
            clarify.body.readUtf8(),
        )
    }

    @Test
    fun `retry posts session_id and returns last user text`() = runBlocking {
        server.enqueue(json("""{"ok": true, "last_user_text": "do the thing", "removed_count": 2}"""))
        val response = client.retrySession("abc")
        assertEquals("do the thing", response.lastUserText)
        val request = server.takeRequest()
        assertEquals("/api/session/retry", request.path)
        assertEquals("""{"session_id":"abc"}""", request.body.readUtf8())
    }

    @Test
    fun `transcribe posts only a file part and surfaces the transcript`() = runBlocking {
        server.enqueue(json("""{"ok": true, "transcript": "hello world"}"""))
        val response = client.transcribeAudio(byteArrayOf(1, 2, 3), "note.m4a")
        assertEquals("hello world", response.transcript)
        val request = server.takeRequest()
        assertEquals("/api/transcribe", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"note.m4a\""))
        assertTrue(!body.contains("session_id"))
    }

    @Test
    fun `transcribe surfaces an error body even on a 503`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error": "STT not configured"}"""))
        val response = client.transcribeAudio(byteArrayOf(1), "a.m4a")
        assertEquals("STT not configured", response.error)
    }

    @Test
    fun `git mutations post the iOS bodies`() = runBlocking {
        server.enqueue(json("""{"ok": true, "git": {"is_git": true, "branch": "main"}}"""))
        client.gitStage("abc", listOf("a.txt"))
        val stage = server.takeRequest()
        assertEquals("/api/git/stage", stage.path)
        assertEquals("""{"session_id":"abc","paths":["a.txt"]}""", stage.body.readUtf8())

        server.enqueue(json("""{"ok": true, "commit": "abc1234", "status": {"is_git": true}}"""))
        val commit = client.gitCommit("abc", "message here")
        assertEquals("abc1234", commit.shortSha)
        val commitReq = server.takeRequest()
        assertEquals("/api/git/commit", commitReq.path)
        assertEquals("""{"session_id":"abc","message":"message here"}""", commitReq.body.readUtf8())

        server.enqueue(json("""{"ok": true, "git": {"is_git": true}}"""))
        client.gitPush("abc")
        assertEquals("/api/git/push", server.takeRequest().path)
    }

    @Test
    fun `cron create and update post the iOS bodies`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        client.createCron(prompt = "check things", schedule = "0 9 * * *", name = "Morning")
        val create = server.takeRequest()
        assertEquals("/api/crons/create", create.path)
        val createBody = create.body.readUtf8()
        assertTrue(createBody.contains(""""prompt":"check things""""))
        assertTrue(createBody.contains(""""schedule":"0 9 * * *""""))
        assertTrue(createBody.contains(""""name":"Morning""""))

        server.enqueue(json("""{"ok": true}"""))
        client.updateCron(jobId = "j1", prompt = "new prompt")
        val update = server.takeRequest()
        assertEquals("/api/crons/update", update.path)
        assertEquals("""{"job_id":"j1","prompt":"new prompt"}""", update.body.readUtf8())
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
