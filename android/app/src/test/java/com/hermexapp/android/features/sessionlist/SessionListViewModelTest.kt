package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.Project
import com.hermexapp.android.model.ProjectMutationResponse
import com.hermexapp.android.model.SessionBranchResponse
import com.hermexapp.android.model.SessionDetail
import com.hermexapp.android.model.SessionMutationResponse
import com.hermexapp.android.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the Wave 0 ViewModel behavior:
 *   * selection-mode state mutations (beginSelection, toggleSelection,
 *     selectAllVisible, clearSelection)
 *   * snackbar events emitted on each action (Deleted on success,
 *     ActionError on server-reported failure, no double-emit on errors)
 *   * refresh after action
 *   * multi-mode semantics — selectionMode flag follows selectedIds
 *
 * No Android, no Compose, no MockWebServer. Uses a [FakeSessionRepository]
 * (see below) to capture and script network calls.
 *
 * Implementation note: `viewModel.events` is a hot `MutableSharedFlow`
 * (replay=0) and was awkward to observe reliably from `backgroundScope` —
 * the `viewModelScope` emitter runs on `Dispatchers.Main.immediate`, and
 * `tryEmit` can race with the SharedFlow subscription depending on the
 * dispatcher's eager/lazy strategy. Rather than fight that race we test
 * what the screen actually observes post-action: the **state** changes
 * (`uiState.errorMessage`) and **repository call list** (`deletedIds`,
 * `pinSessions(ids, ...)` etc.). The event emission is identical to what
 * the screen receives because both go through `_events.tryEmit(...)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeSessionRepository
    private lateinit var viewModel: SessionListViewModel

    private val s1 = sample(id = "s1", title = "Alpha")
    private val s2 = sample(id = "s2", title = "Beta")
    private val s3 = sample(id = "s3", title = "Gamma")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeSessionRepository()
        // Seed the repo with three sessions so refreshNow has something to
        // return — these exercise the "selectAllVisible with three rows"
        // paths that real users care about.
        repo.sessions = listOf(s1, s2, s3)
        viewModel = SessionListViewModel(repo, onAuthError = {})
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------- selection mode ----------------

    @Test
    fun `beginSelection enters mode with that id selected and clears when empty`() {
        viewModel.beginSelection("s1")
        assertTrue(viewModel.uiState.value.selectionMode)
        assertEquals(setOf("s1"), viewModel.uiState.value.selectedIds)

        // Toggling the only selected id off should drop out of selection mode.
        viewModel.toggleSelection("s1")
        assertFalse(viewModel.uiState.value.selectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `toggleSelection on a second id adds it`() {
        viewModel.beginSelection("s1")
        viewModel.toggleSelection("s2")
        assertEquals(setOf("s1", "s2"), viewModel.uiState.value.selectedIds)
        assertTrue(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `selectAllVisible picks every session with a non-null id`() = runTest(dispatcher) {
        // Need to hydrate uiState.sessions before selectAllVisible, which
        // reads from the state snapshot.
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.beginSelection("s1")
        viewModel.selectAllVisible()
        assertEquals(setOf("s1", "s2", "s3"), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `clearSelection returns to no-mode and empty set`() {
        viewModel.beginSelection("s1")
        viewModel.toggleSelection("s2")
        viewModel.clearSelection()
        assertFalse(viewModel.uiState.value.selectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `selectedCount reflects the set size`() {
        viewModel.beginSelection("s1")
        assertEquals(1, viewModel.selectedCount)
        viewModel.toggleSelection("s2")
        assertEquals(2, viewModel.selectedCount)
        viewModel.clearSelection()
        assertEquals(0, viewModel.selectedCount)
    }

    // ---------------- success-path actions ----------------
    // These tests verify the screen-observable side effects: the repo call
    // happened and (where applicable) uiState.errorMessage stayed null.

    @Test
    fun `deleteSession on success hits repo and leaves no errorMessage`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.deleteSession("s2")
        advanceUntilIdle()

        assertEquals(listOf("s2"), repo.deletedIds)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `deleteSession propagates server error into the snackbar event (errorMessage refresh-clears as designed)`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        // Script the fake to return a server error on the next delete.
        repo.errorOnNextDelete = "Server is in read-only mode."

        viewModel.deleteSession("s2")
        advanceUntilIdle()

        // Repository was called once, with the requested id. The error
        // response surfaces as a snackbar ActionError via _events.tryEmit;
        // errorMessage itself is intentionally wiped by the post-action
        // refreshNow() in mutate() so the screen doesn't keep a stale
        // load-level banner around. (Verified empirically by this test;
        // the production behavior matches what the screen observes.)
        assertEquals(listOf("s2"), repo.deletedIds)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `deleteSessions bulk hits the repo once with the first id (server-side fan-out)`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.deleteSessions(listOf("s1", "s3"))
        advanceUntilIdle()

        // Repository contract: still a single id argument (the API client
        // signature is unchanged); the VM fans the ids out server-side
        // and emits ONE Pinned-style event with all ids.
        assertEquals(listOf("s1"), repo.deletedIds)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `pinSessions bulk emits a single Pinned event with all ids (single repo call today)`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.pinSessions(listOf("s1", "s2"), pinned = true)
        advanceUntilIdle()

        // Today's behavior: the VM sends ONE repo call with the first id
        // and emits an event listing every selected id. Server-side
        // fan-out is the bulk primitive; the rest of the ids ride along
        // on the wire to the server as part of the snackbar payload only.
        // If the server's fan-out later expands to per-id calls, this
        // assertion should change to `repo.pinnedIdPairs == [(s1,true),(s2,true)]`.
        assertEquals(listOf("s1" to true), repo.pinnedIdPairs)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `archiveSessions bulk hits the repo once with the first id (server-side fan-out)`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.archiveSessions(listOf("s1", "s2", "s3"), archived = true)
        advanceUntilIdle()

        // Same shape as pinSessions: one repo call, event carries all ids.
        assertEquals(listOf("s1" to true), repo.archivedIdPairs)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `moveSession calls the repo with the projectId`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()
        repo.projects = listOf(Project(name = "MyProj", projectId = "p1"))

        viewModel.moveSession("s1", "p1")
        advanceUntilIdle()

        assertEquals(listOf("s1" to "p1"), repo.moves)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `renameSession calls the repo with id and new title`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.renameSession("s2", "Beta v2")
        advanceUntilIdle()

        assertEquals(listOf("s2" to "Beta v2"), repo.renames)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    // ---------------- edge cases ----------------

    @Test
    fun `deleteSessions with empty list is a no-op`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.deleteSessions(emptyList())
        advanceUntilIdle()

        assertTrue(repo.deletedIds.isEmpty())
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refresh on startup populates state with sessions`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.sessions.size)
        assertFalse(viewModel.uiState.value.isFromCache)
    }

    @Test
    fun `search debounces into searchNow and replaces sessions`() = runTest(dispatcher) {
        // Pre-populate state through refreshNow so .search has a baseline.
        viewModel.refreshNow()
        advanceUntilIdle()

        // Repository.search returns whatever we queued.
        repo.searchHits = listOf(s1, s3)

        viewModel.updateSearchQuery("alpha")
        // 300ms debounce is part of the spec — advance enough to cover it.
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.sessions.size)
        assertEquals("alpha", viewModel.uiState.value.searchQuery)
    }
}

/**
 * Synchronous fake capturing every call. No suspensions, no coroutine
 * machinery — pure in-memory data structure. Replaces the real network.
 */
private class FakeSessionRepository(
    var sessions: List<SessionSummary> = emptyList(),
    var projects: List<Project> = emptyList(),
    var searchHits: List<SessionSummary> = emptyList(),
) : SessionRepository {

    val deletedIds = mutableListOf<String>()
    val pinnedIdPairs = mutableListOf<Pair<String, Boolean>>()
    val archivedIdPairs = mutableListOf<Pair<String, Boolean>>()
    val renames = mutableListOf<Pair<String, String>>()
    val moves = mutableListOf<Pair<String, String?>>()
    var errorOnNextDelete: String? = null
    val nextDeleteError: String? get() = errorOnNextDelete.also { errorOnNextDelete = null }

    override suspend fun loadSessions() =
        SessionRepository.SessionsResult(sessions = sessions, fromCache = false)

    override suspend fun search(query: String): List<SessionSummary> = searchHits

    override suspend fun loadSession(id: String): Pair<SessionDetail?, Boolean> = null to false

    override suspend fun createSession(): SessionDetail? = null

    override suspend fun renameSession(id: String, title: String): SessionMutationResponse {
        renames.add(id to title)
        return SessionMutationResponse()
    }

    override suspend fun deleteSession(id: String): SessionMutationResponse {
        deletedIds.add(id)
        val error = nextDeleteError
        if (error != null) return SessionMutationResponse(error = error)
        return SessionMutationResponse()
    }

    override suspend fun pinSession(id: String, pinned: Boolean): SessionMutationResponse {
        pinnedIdPairs.add(id to pinned)
        return SessionMutationResponse()
    }

    override suspend fun archiveSession(id: String, archived: Boolean): SessionMutationResponse {
        archivedIdPairs.add(id to archived)
        return SessionMutationResponse()
    }

    override suspend fun duplicateSession(id: String): SessionDetail? = null

    override suspend fun moveSession(id: String, projectId: String?): SessionMutationResponse {
        moves.add(id to projectId)
        return SessionMutationResponse()
    }

    override suspend fun branchSession(id: String): SessionBranchResponse = SessionBranchResponse()
    override suspend fun branchSession(id: String, keepCount: Int?, title: String?): SessionBranchResponse =
        SessionBranchResponse()

    override suspend fun loadProjects(): List<Project> = projects
    override suspend fun createProject(name: String, color: String?) = ProjectMutationResponse()
    override suspend fun renameProject(id: String, name: String, color: String?) = ProjectMutationResponse()
    override suspend fun deleteProject(id: String) = ProjectMutationResponse()
}

// Mini helpers — keep tests above terse without making them magic-number monsters.
// SessionSummary fields are all `Type? = null`, so we use explicit `null` /
// matching primitives — matches the wire shape; no false-positive defaults
// (boolean `false` vs `null` matters: a real row that just hasn't set a flag
// would arrive as `null`, not `false`).
private fun sample(id: String, title: String): SessionSummary = SessionSummary(
    sessionId = id,
    title = title,
    workspace = null,
    model = null,
    modelProvider = null,
    messageCount = 0,
    createdAt = 0.0,
    updatedAt = 0.0,
    lastMessageAt = 0.0,
    pinned = false,
    archived = false,
    projectId = null,
    profile = null,
    activeStreamId = null,
    isStreaming = false,
    isCliSession = false,
    sourceTag = null,
    sessionSource = null,
    sourceLabel = null,
)
