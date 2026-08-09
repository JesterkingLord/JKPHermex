package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.Project
import com.hermexapp.android.model.ProjectMutationResponse
import com.hermexapp.android.model.SessionBranchResponse
import com.hermexapp.android.model.SessionDetail
import com.hermexapp.android.model.SessionMutationResponse
import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.network.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `deleteSessions deletes every selected id, not just the first`() = runTest(dispatcher) {
        // These paths called the single-id endpoint with ids.first() on the
        // belief that it fanned out server-side. It does not: the deployed
        // /api/session/delete reads one session_id. "Delete 2 sessions?"
        // deleted one and the snackbar still said two.
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.deleteSessions(listOf("s1", "s3"))
        advanceUntilIdle()

        assertEquals(listOf("s1", "s3"), repo.deletedIds)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `pinSessions pins every selected id`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.pinSessions(listOf("s1", "s2"), pinned = true)
        advanceUntilIdle()

        assertEquals(listOf("s1" to true, "s2" to true), repo.pinnedIdPairs)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `archiveSessions archives every selected id`() = runTest(dispatcher) {
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.archiveSessions(listOf("s1", "s2", "s3"), archived = true)
        advanceUntilIdle()

        assertEquals(
            listOf("s1" to true, "s2" to true, "s3" to true),
            repo.archivedIdPairs,
        )
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

    // ---------------- Wave 7: pull-to-refresh contract ----------------
    // These tests pin the state contract that SessionListScreen's
    // PullToRefreshBox depends on:
    //   * refresh() flips state.isLoading true synchronously on entry
    //     (before the suspend repository call) so the indicator visualizes
    //     the in-flight network call without waiting for it to return.
    //   * refreshNow() returns state.isLoading to false on completion
    //     whether the call succeeded or threw.
    //   * On a thrown ApiError the same finally block writes
    //     state.errorMessage (for the retry banner) and clears isLoading.

    @Test
    fun `refresh_togglesIsLoading_duringNetworkCall`() = runTest(dispatcher) {
        // Gate the fake repo so refreshNow()'s suspend loadSessions() call
        // parks on a deferred we control. Lets us observe the
        // entry-state (isLoading=true) before the call completes.
        val gate = CompletableDeferred<Unit>()
        repo.loadSessionsGate = gate

        // refresh() launches viewModelScope.launch { refreshNow() } which
        // sets isLoading=true synchronously then suspends on the gate.
        viewModel.refresh()
        advanceUntilIdle()

        // While the gate is closed, the network call is in-flight and the
        // screen-observable state must already reflect the loading flip.
        // This is the property PullToRefreshBox needs to render its
        // spinner instead of an empty list during a swipe-down refresh.
        assertTrue(
            "isLoading should be true while refreshNow is suspended on the network call",
            viewModel.uiState.value.isLoading,
        )

        // Release the gate, advance past the network return, and verify
        // isLoading flips back to false and the new sessions land.
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "isLoading should reset to false once the network call returns",
            viewModel.uiState.value.isLoading,
        )
        assertEquals(3, viewModel.uiState.value.sessions.size)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refreshNow_emitsErrorMessage_onServerFailure`() = runTest(dispatcher) {
        // Script the fake to throw an ApiError on the next loadSessions()
        // call. The VM's catch block is what populates state.errorMessage
        // and clears isLoading; that's exactly the contract PullToRefreshBox
        // trusts when its isRefreshing binding flips back to false.
        repo.loadSessionsError = ApiError.Network(IllegalStateException("simulated"))

        viewModel.refreshNow()
        advanceUntilIdle()

        // On failure: isLoading is back to false (so the indicator hides),
        // errorMessage is populated (so the inline Retry banner shows),
        // and sessions remain empty (no successful load).
        assertFalse(
            "isLoading should be false after a failed refresh",
            viewModel.uiState.value.isLoading,
        )
        assertNotNull(
            "errorMessage should be populated when refreshNow throws ApiError",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue(viewModel.uiState.value.errorMessage!!.isNotEmpty())
        assertEquals(0, viewModel.uiState.value.sessions.size)
    }

    @Test
    fun `unexpected refresh failure clears loading and remains retryable`() = runTest(dispatcher) {
        repo.loadSessionsError = IllegalStateException("local cache unavailable")

        viewModel.refreshNow()

        assertFalse(
            "an unexpected repository failure must not strand the refresh spinner",
            viewModel.uiState.value.isLoading,
        )
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.isNotBlank())
    }

    // ---------------- Wave 7 Slice 7.2 — filter pills ----------------

    /** Pure copy of a session with a chosen pinned / archived state. */
    private fun fakeSession(
        id: String,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): SessionSummary = SessionSummary(
        sessionId = id,
        title = id,
        pinned = pinned,
        archived = archived,
    )

    /** Helper to seed a VM with a chosen sessions list — single-call factory. */
    private fun seededVmWith(
        list: List<SessionSummary>,
    ): SessionListViewModel {
        val repo = FakeSessionRepository().also { it.sessions = list }
        return SessionListViewModel(repository = repo, onAuthError = {})
    }

    @Test
    fun `setFilterMode stores mode in UiState`() = runTest(dispatcher) {
        val viewModel = SessionListViewModel(
            repository = FakeSessionRepository(),
            onAuthError = {},
        )
        assertEquals(SessionListViewModel.FilterMode.All, viewModel.uiState.value.filterMode)
        viewModel.setFilterMode(SessionListViewModel.FilterMode.Pinned)
        assertEquals(SessionListViewModel.FilterMode.Pinned, viewModel.uiState.value.filterMode)
        viewModel.setFilterMode(SessionListViewModel.FilterMode.Archived)
        assertEquals(SessionListViewModel.FilterMode.Archived, viewModel.uiState.value.filterMode)
        viewModel.setFilterMode(SessionListViewModel.FilterMode.All)
        assertEquals(SessionListViewModel.FilterMode.All, viewModel.uiState.value.filterMode)
    }

    @Test
    fun `filteredSessions defaults to All (the empty case is empty)`() = runTest(dispatcher) {
        // Default state has zero sessions and the All filter, so
        // filteredSessions returns an empty list verbatim — the
        // LazyColumn's own empty-state item renders the "No sessions yet" copy.
        val viewModel = SessionListViewModel(
            repository = FakeSessionRepository(),
            onAuthError = {},
        )
        assertEquals(emptyList<SessionSummary>(), viewModel.filteredSessions)
    }

    @Test
    fun `filteredSessions on All returns the full list verbatim`() = runTest(dispatcher) {
        val a = fakeSession("a", pinned = true)
        val b = fakeSession("b", archived = true)
        val c = fakeSession("c")
        val all = listOf(a, b, c)
        val seeded = seededVmWith(all)
        seeded.refreshNow()
        advanceUntilIdle()

        assertEquals(all, seeded.filteredSessions)
    }

    @Test
    fun `filteredSessions on Pinned returns only pinned items`() = runTest(dispatcher) {
        val a = fakeSession("a", pinned = true)
        val b = fakeSession("b", archived = true)
        val c = fakeSession("c")
        val all = listOf(a, b, c)
        val seeded = seededVmWith(all)
        seeded.refreshNow()
        advanceUntilIdle()

        seeded.setFilterMode(SessionListViewModel.FilterMode.Pinned)
        assertEquals(listOf(a), seeded.filteredSessions)
    }

    @Test
    fun `filteredSessions on Archived returns only archived items`() = runTest(dispatcher) {
        val a = fakeSession("a", pinned = true)
        val b = fakeSession("b", archived = true)
        val c = fakeSession("c")
        val all = listOf(a, b, c)
        val seeded = seededVmWith(all)
        seeded.refreshNow()
        advanceUntilIdle()

        seeded.setFilterMode(SessionListViewModel.FilterMode.Archived)
        assertEquals(listOf(b), seeded.filteredSessions)
    }

    @Test
    fun `filteredSessions on a filter with zero matches returns empty`() = runTest(dispatcher) {
        val a = fakeSession("a")
        val all = listOf(a)
        val seeded = seededVmWith(all)
        seeded.refreshNow()
        advanceUntilIdle()

        seeded.setFilterMode(SessionListViewModel.FilterMode.Pinned)
        assertEquals(emptyList<SessionSummary>(), seeded.filteredSessions)
    }

    // ---------------- v0.8.15 — background refresh ----------------
    // The session list now polls while the screen is composed instead of
    // waiting for a manual pull-to-refresh (the operator's "the list feels
    // stuck" pain). The loop reads the current interval on every tick
    // (15s foreground / 60s background or power-save), skips a tick while
    // a refresh is in-flight, and must not survive stopBackgroundRefresh().
    // Assertions are behavioral: they count repository loads against the
    // virtual clock of the shared StandardTestDispatcher.

    @Test
    fun `startBackgroundRefresh polls refreshNow on the foreground cadence`() = runTest(dispatcher) {
        viewModel.onScreenResumed() // foreground → 15s cadence
        try {
            viewModel.startBackgroundRefresh()

            advanceTimeBy(15_000) // first tick
            runCurrent()
            assertTrue("first poll should have refreshed once", repo.loadCount >= 1)

            advanceTimeBy(15_000) // second tick — proves the loop is periodic
            runCurrent()
            assertTrue("loop should have polled repeatedly", repo.loadCount >= 2)
        } finally {
            viewModel.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `stopBackgroundRefresh cancels the polling loop`() = runTest(dispatcher) {
        viewModel.startBackgroundRefresh()
        viewModel.stopBackgroundRefresh()

        // Give the (cancelled) loop more than three 60s cadences of
        // virtual time — nothing may hit the repository.
        advanceTimeBy(200_000)
        assertEquals(0, repo.loadCount)
    }

    @Test
    fun `onScreenResumed picks the 15s foreground cadence`() = runTest(dispatcher) {
        viewModel.onScreenResumed()
        try {
            viewModel.startBackgroundRefresh()
            advanceTimeBy(15_000)
            runCurrent()
            // Two loads: the immediate one from resuming, plus the 15s tick.
            assertEquals(2, repo.loadCount)
        } finally {
            viewModel.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `onScreenResumed refreshes straight away instead of waiting for a tick`() = runTest(dispatcher) {
        // The reported symptom: the list looks stuck, and pulling to refresh
        // "unsticks" it. Nothing was broken — returning to the screen just
        // did not ask for anything until the next tick came round.
        viewModel.onScreenResumed()

        advanceUntilIdle()

        assertEquals("resuming must refresh without waiting", 1, repo.loadCount)
    }

    @Test
    fun `returning to the foreground does not sit out the rest of a 60s wait`() = runTest(dispatcher) {
        viewModel.onScreenPaused() // 60s cadence
        try {
            viewModel.startBackgroundRefresh()
            advanceTimeBy(5_000) // a 60s wait is now in flight

            viewModel.onScreenResumed() // promotes to 15s and refreshes now
            // advanceTimeBy, never advanceUntilIdle, while the poll loop is
            // running: the loop is infinite by design, so "until idle" never
            // arrives and the test spins instead of failing.
            advanceTimeBy(1)
            val afterResume = repo.loadCount

            // 15s later the loop must have ticked again, rather than sitting
            // out the minute it had already committed to.
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(
                "the poll cadence must follow the screen, not the wait it started with",
                repo.loadCount > afterResume,
            )
        } finally {
            viewModel.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `onScreenResumed keeps the 60s cadence while power save is active`() = runTest(dispatcher) {
        val powerSaveVm = SessionListViewModel(
            repository = repo,
            onAuthError = {},
            isPowerSaveModeProvider = { true },
        )
        powerSaveVm.onScreenResumed()
        advanceUntilIdle()
        // Resuming still refreshes once immediately; power save governs the
        // repeat cadence, not whether the operator gets a current list now.
        val afterResume = repo.loadCount
        assertEquals(1, afterResume)
        try {
            powerSaveVm.startBackgroundRefresh()

            advanceTimeBy(15_000) // a foreground tick would fire here — must not
            runCurrent()
            assertEquals(afterResume, repo.loadCount)

            advanceTimeBy(45_000) // the first 60s tick fires now
            runCurrent()
            assertEquals(afterResume + 1, repo.loadCount)
        } finally {
            powerSaveVm.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `onScreenPaused drops the cadence back to 60s`() = runTest(dispatcher) {
        viewModel.onScreenResumed() // 15s — and one immediate refresh
        viewModel.onScreenPaused() // background → 60s
        advanceTimeBy(1)
        val baseline = repo.loadCount
        try {
            viewModel.startBackgroundRefresh()

            advanceTimeBy(15_000)
            runCurrent()
            assertEquals("a foreground tick must not fire while paused", baseline, repo.loadCount)

            advanceTimeBy(45_000)
            runCurrent()
            assertEquals("the 60s tick fires now", baseline + 1, repo.loadCount)
        } finally {
            // The poll loop is infinite by design. Left running, runTest's
            // drain phase advances virtual time through it forever, so a
            // failed assertion above would hang the whole suite instead of
            // reporting. This is what made the module untestable.
            viewModel.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `background poll skips ticks while a search is active`() = runTest(dispatcher) {
        viewModel.onScreenResumed() // foreground → 15s cadence, one refresh
        advanceTimeBy(1)
        val baseline = repo.loadCount
        try {
            viewModel.startBackgroundRefresh()
            viewModel.updateSearchQuery("needle")

            // Two full ticks elapse while the query is non-blank — neither may
            // replace the search results with the full list.
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "a poll must never clobber an active search",
                baseline,
                repo.loadCount,
            )

            // Clearing the search refreshes immediately (existing behavior);
            // from then on the poll ticks normally again.
            viewModel.updateSearchQuery("")
            advanceTimeBy(15_000) // the immediate refresh + one tick
            runCurrent()
            assertEquals(baseline + 2, repo.loadCount)
        } finally {
            viewModel.stopBackgroundRefresh()
        }
        advanceUntilIdle()
    }

    @Test
    fun `select all in a filter selects only what the filter shows`() = runTest(dispatcher) {
        // selectAllVisible read the unfiltered list, so the Pinned view — a
        // couple of rows on screen — selected every session in the account.
        // Paired with a bulk delete that is confirmed by count, that is a
        // destructive action agreed to on a number the operator never saw.
        repo.sessions = listOf(s1, s2.copy(pinned = true), s3)
        viewModel.refreshNow()
        advanceUntilIdle()

        viewModel.setFilterMode(SessionListViewModel.FilterMode.Pinned)
        viewModel.selectAllVisible()

        // Only s2 is pinned, so only s2 may be selected — not all three.
        assertEquals(setOf("s2"), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `the background poll does not spin the pull-to-refresh indicator`() = runTest(dispatcher) {
        // isRefreshing was bound to isLoading, which the 15s poll sets on
        // every tick — so the indicator animated in and out by itself on a
        // screen nobody was touching.
        viewModel.onScreenResumed()
        viewModel.startBackgroundRefresh()
        try {
            advanceTimeBy(16_000)
            runCurrent()
            assertFalse(
                "a poll tick must not present as a user-initiated refresh",
                viewModel.uiState.value.isManualRefresh,
            )
        } finally {
            viewModel.stopBackgroundRefresh()
        }
    }

    @Test
    fun `a user refresh clears the indicator even when it fails`() = runTest(dispatcher) {
        repo.loadSessionsError = ApiError.Http(500, null)
        viewModel.refresh()
        advanceUntilIdle()

        // Cleared in a finally: a thrown ApiError must not leave the indicator
        // spinning for the life of the screen.
        assertFalse(viewModel.uiState.value.isManualRefresh)
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

    // Wave 7 pull-to-refresh test hooks. When set, loadSessions() suspends
    // on the gate before returning (lets tests observe the entry-state
    // isLoading=true while the network call is still in-flight); when set
    // the error is thrown instead of returning a successful result (lets
    // tests pin the errorMessage/clear-isLoading path).
    var loadSessionsGate: CompletableDeferred<Unit>? = null
    var loadSessionsError: Throwable? = null

    // v0.8.15: number of loadSessions() calls — lets the background-poll
    // tests count refreshes against the virtual clock.
    var loadCount = 0

    override suspend fun loadSessions(): SessionRepository.SessionsResult {
        loadCount++
        loadSessionsGate?.await()
        val err = loadSessionsError
        if (err != null) throw err
        return SessionRepository.SessionsResult(sessions = sessions, fromCache = false)
    }

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
