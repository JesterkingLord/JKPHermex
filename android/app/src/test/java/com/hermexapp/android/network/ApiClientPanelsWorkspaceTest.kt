package com.hermexapp.android.network

import com.hermexapp.android.auth.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract paths/bodies for the phase 5–7 endpoints, mirroring the iOS Endpoint enum. */
class ApiClientPanelsWorkspaceTest {

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
    fun `catalog endpoints hit the right paths`() = runBlocking {
        server.enqueue(json("""{"groups": [], "default_model": "m1"}"""))
        assertEquals("m1", client.models().defaultModel)
        assertEquals("/api/models", server.takeRequest().path)

        server.enqueue(json("""{"commands": [{"name": "compact", "description": "d"}]}"""))
        assertEquals("compact", client.commands().commands!!.single().name)
        assertEquals("/api/commands", server.takeRequest().path)

        server.enqueue(json("""{"profiles": [{"name": "default", "is_active": true}], "active": "default"}"""))
        assertEquals("default", client.profiles().active)
        assertEquals("/api/profiles", server.takeRequest().path)

        server.enqueue(json("""{"workspaces": [{"path": "/w", "name": "w"}], "last": "/w"}"""))
        assertEquals("/w", client.workspaces().last)
        assertEquals("/api/workspaces", server.takeRequest().path)

        server.enqueue(json("""{"ok": true, "model": "m2"}"""))
        client.saveDefaultModel("m2")
        val save = server.takeRequest()
        assertEquals("/api/default-model", save.path)
        assertEquals("""{"model":"m2"}""", save.body.readUtf8())
    }

    @Test
    fun `model groups parse from the raw group payload`() = runBlocking {
        server.enqueue(
            json(
                """
                {"groups": [
                    {"provider_id": "openai", "name": "OpenAI",
                     "models": [{"id": "gpt-x", "name": "GPT X"}, {"id": "gpt-y"}]},
                    {"name": "Broken group with no models", "models": []}
                 ],
                 "default_model": "gpt-x"}
                """,
            ),
        )

        val groups = client.models().catalogGroups

        assertEquals(1, groups.size)
        assertEquals("OpenAI", groups[0].name)
        assertEquals(listOf("GPT X", "gpt-y"), groups[0].models.map { it.displayName })
        assertEquals("openai", groups[0].models[0].providerId)
    }

    @Test
    fun `upload posts multipart with session_id and file parts`() = runBlocking {
        server.enqueue(json("""{"filename": "a.png", "path": "uploads/a.png", "is_image": true}"""))

        val response = client.uploadFile("abc", byteArrayOf(1, 2, 3), "a.png")

        assertEquals("uploads/a.png", response.path)
        val request = server.takeRequest()
        assertEquals("/api/upload", request.path)
        val contentType = request.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"session_id\""))
        assertTrue(body.contains("abc"))
        assertTrue(body.contains("name=\"file\"; filename=\"a.png\""))
    }

    @Test
    fun `workspace and git endpoints carry the session id`() = runBlocking {
        server.enqueue(json("""{"entries": [{"name": "src", "type": "dir"}], "path": null}"""))
        client.directoryList("abc")
        assertEquals("/api/list?session_id=abc", server.takeRequest().path)

        server.enqueue(json("""{"content": "hello", "name": "readme.md"}"""))
        client.file("abc", "readme.md")
        assertEquals("/api/file?session_id=abc&path=readme.md", server.takeRequest().path)

        server.enqueue(json("""{"git": {"is_git": true, "branch": "main", "files": []}}"""))
        assertEquals("main", client.gitStatus("abc").git?.branch)
        assertEquals("/api/git/status?session_id=abc", server.takeRequest().path)

        server.enqueue(json("""{"diff": {"path": "a.txt", "diff": "+x", "additions": 1}}"""))
        assertEquals("+x", client.gitDiff("abc", "a.txt", "unstaged").diff?.diff)
        assertEquals(
            "/api/git/diff?session_id=abc&path=a.txt&kind=unstaged",
            server.takeRequest().path,
        )
    }

    @Test
    fun `panel endpoints decode upstream-shaped payloads`() = runBlocking {
        server.enqueue(
            json(
                """
                {"jobs": [{"job_id": "j1", "name": "Nightly", "schedule_display": "daily 9am",
                           "enabled": true, "last_status": "ok",
                           "schedule": {"kind": "cron", "expr": "0 9 * * *"}}]}
                """,
            ),
        )
        val jobs = client.crons().jobs!!
        assertEquals("Nightly", jobs.single().name)
        assertEquals("/api/crons", server.takeRequest().path)

        server.enqueue(json("""{"ok": true}"""))
        client.runCron("j1")
        val run = server.takeRequest()
        assertEquals("/api/crons/run", run.path)
        assertEquals("""{"job_id":"j1"}""", run.body.readUtf8())

        server.enqueue(json("""{"skills": [{"name": "review", "category": "dev"}]}"""))
        assertEquals("review", client.skills().skills!!.single().name)
        assertEquals("/api/skills", server.takeRequest().path)

        server.enqueue(json("""{"name": "review", "content": "# Review"}"""))
        assertEquals("# Review", client.skillContent("review").content)
        assertEquals("/api/skills/content?name=review", server.takeRequest().path)

        server.enqueue(json("""{"memory": "notes", "memory_path": "/m.md"}"""))
        assertEquals("notes", client.memory().memory)
        assertEquals("/api/memory", server.takeRequest().path)

        server.enqueue(json("""{"period_days": 30, "total_sessions": 5, "total_cost": 1.25}"""))
        assertEquals(5, client.insights().totalSessions)
        assertEquals("/api/insights?days=30", server.takeRequest().path)
    }

    @Test
    fun `session mutations follow the iOS shapes`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        client.renameSession("abc", "New title")
        val rename = server.takeRequest()
        assertEquals("/api/session/rename", rename.path)
        assertEquals("""{"session_id":"abc","title":"New title"}""", rename.body.readUtf8())

        server.enqueue(json("""{"ok": true}"""))
        client.pinSession("abc", true)
        val pin = server.takeRequest()
        assertEquals("/api/session/pin", pin.path)
        assertEquals("""{"session_id":"abc","pinned":true}""", pin.body.readUtf8())

        server.enqueue(json("""{"ok": true}"""))
        client.deleteSession("abc")
        assertEquals("/api/session/delete", server.takeRequest().path)

        server.enqueue(json("""{"ok": true}"""))
        client.archiveSession("abc", true)
        assertEquals("/api/session/archive", server.takeRequest().path)
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.trimIndent())
}
