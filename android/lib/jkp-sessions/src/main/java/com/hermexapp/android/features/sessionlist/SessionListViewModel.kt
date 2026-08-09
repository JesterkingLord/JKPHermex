package com.hermexapp.android.features.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.network.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun filterSessions(
    sessions: List<SessionSummary>,
    mode: SessionListViewModel.FilterMode,
): List<SessionSummary> = when (mode) {
    SessionListViewModel.FilterMode.All -> sessions
    SessionListViewModel.FilterMode.Pinned -> sessions.filter { it.pinned == true }
    SessionListViewModel.FilterMode.Archived -> sessions.filter { it.archived == true }
}

/**
 * v0.8.15 — live-indicator dot rule moved to [SessionListScreen]
 * (shouldShowStreamingDot) so the row composable owns the predicate;
 * see SessionRowStreamingDotTest.
 */
class SessionListViewModel(
    private val repository: SessionRepository,
    private val onAuthError: (Throwable) -> Unit = {},
    /**
     * Supplier returning the server URL the app is currently configured to
     * talk to (e.g. `http://100.88.54.29:8787`). Wired by [MainActivity]
     * from [com.hermexapp.android.AppContainer.currentBaseUrl] so the
     * Error banner can always say "JKP is unreachable (tried
     * http://…) — change server?" without forcing the user to dig into
     * Settings to figure out which URL failed.
     */
    private val currentBaseUrlProvider: () -> String? = { null },
    /**
     * v0.8.15: reports whether the OS power-save mode is active. Wired by
     * [MainActivity] from [android.os.PowerManager.isPowerSaveMode] so the
     * background refresh loop can stay on its longest (60s) cadence while
     * the battery is being conserved — an overnight charge must not be
     * drained by a 15s poll loop. Kept as a provider (rather than reaching
     * for a Context inside the ViewModel) so the pure-JVM unit tests can
     * script both branches.
     */
    private val isPowerSaveModeProvider: () -> Boolean = { false },
) : ViewModel() {

    data class UiState(
        val sessions: List<SessionSummary> = emptyList(),
        val projects: List<com.hermexapp.android.model.Project> = emptyList(),
        val searchQuery: String = "",
        val isLoading: Boolean = false,
        val isFromCache: Boolean = false,
        val errorMessage: String? = null,
        /**
         * The server URL the refresh failed against, captured at the
         * moment the request returned. Surfaced on the home-screen error
         * banner so the user can see "JKP is unreachable — tried
         * http://…" and tap "Change server" without leaving the screen.
         * `null` when no request has failed yet, or when no server has
         * been configured (the onboarding flow handles that path).
         */
        val lastFailedServer: String? = null,
        /**
         * Excellence v1 Wave 0: bulk-select mode (long-press → multi-select toolbar).
         */
        val selectionMode: Boolean = false,
        /**
         * Stable session ids currently selected. Empty when not in selection mode.
         */
        val selectedIds: Set<String> = emptySet(),
        /**
         * Wave 7 Slice 7.2 — sidebar filter pill, choosing which sessions
         * show in the list. `All` is the default (no filtering). `Pinned`
         * and `Archived` are subset filters; they don't replace search.
         */
        val filterMode: FilterMode = FilterMode.All,
    )

    /**
     * Wave 7 Slice 7.2 — sidebar filter pill states. The pill row above
     * the wordmark renders one chip per non-empty state; tapping a chip
     * swaps [UiState.filterMode]. Tapping the currently-active chip
     * returns to [FilterMode.All] (toggle-off affordance).
     *
     * Only states the model can express today are listed. `Shared`
     * (multi-user invite) is reserved for a future model field.
     */
    enum class FilterMode {
        All,
        Pinned,
        Archived,
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * One-shot events the screen collects in a `LaunchedEffect` to show
     * snackbars. Buffered with `BufferOverflow.DROP_OLDEST` so an unexpected
     * burst (e.g. a 50-row bulk select-then-archive) doesn't suspend callers.
     * The screen draws at most one snackbar at a time (M3 `SnackbarHost` does
     * this by contract), so the only events that can be dropped are repeats
     * that the operator wouldn't have seen anyway.
     */
    private val _events = MutableSharedFlow<SessionListEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SessionListEvent> = _events.asSharedFlow()

    private var searchJob: Job? = null

    /**
     * v0.8.15: background polling loop. Started by the screen's
     * `DisposableEffect` and cancelled when the screen leaves composition,
     * so the session list keeps itself fresh without requiring a manual
     * pull-to-refresh (the operator's core "the list feels stuck" pain).
     */
    private var pollJob: Job? = null

    /**
     * v0.8.15: tick rate for the background poll loop, read on every
     * iteration so the screen can switch between the 15s foreground and
     * 60s background cadences without restarting the loop. Initial value
     * is the slowest cadence; [onScreenResumed] promotes it to 15s the
     * moment the screen is (re)entered.
     */
    private val _currentPollIntervalMs = MutableStateFlow(60_000L)

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        // Resolve the URL ONCE per refresh and snapshot it into UiState.
        // Reading it lazily on the failure branch is too late for the
        // success path to populate it (though success clears it), and the
        // provider may rotate (User switched servers via Settings) mid-flight,
        // so we want the URL that the request actually went out against,
        // not whatever the provider returns after the request returned.
        val serverAtRequestTime = currentBaseUrlProvider()?.trimEnd('/')
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, lastFailedServer = null)
        }
        try {
            val result = repository.loadSessions()
            _uiState.update {
                it.copy(
                    sessions = result.sessions,
                    isFromCache = result.fromCache,
                    // Clear the last-failed marker on a successful refresh;
                    // a previous failure should not bleed into the next
                    // attempt's empty state.
                    lastFailedServer = null,
                )
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update {
                it.copy(
                    errorMessage = e.userMessage,
                    lastFailedServer = serverAtRequestTime,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            // Repository boundaries include local Room/cache work as well as
            // HTTP. An unexpected local failure must become a retryable UI
            // state instead of escaping the coroutine and leaving the pull-
            // to-refresh indicator spinning forever.
            _uiState.update {
                it.copy(
                    errorMessage = "Sessions couldn't be loaded. Pull down to retry.",
                    lastFailedServer = serverAtRequestTime,
                )
            }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
        // Projects are best-effort: never block or error the session list on them.
        runCatching { repository.loadProjects() }.getOrNull()?.let { projects ->
            _uiState.update { it.copy(projects = projects) }
        }
    }

    /**
     * v0.8.15: background polling loop. Tick rate is read from
     * [_currentPollIntervalMs] on each iteration so the screen can switch
     * between 15s foreground and 60s background cadences without
     * restarting the loop.
     *
     * Power-save awareness: when BatteryManager.isPowerSaveMode is true,
     * use the longest interval (60s) regardless of foreground/background.
     * That keeps the operator's battery from draining overnight.
     */
    fun startBackgroundRefresh() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(_currentPollIntervalMs.value)
                // Never clobber an in-progress search with the full list —
                // the search field stays visible while sessions swap under
                // it, which reads as "search is broken".
                if (_uiState.value.searchQuery.isNotBlank()) continue
                // Only refresh if the most recent refresh isn't still in-flight.
                if (!_uiState.value.isLoading) {
                    runCatching { refreshNow() }
                }
            }
        }
    }

    fun stopBackgroundRefresh() {
        pollJob?.cancel()
        pollJob = null
    }

    fun onScreenResumed() {
        val powerSave = runCatching { isPowerSaveModeProvider() }.getOrDefault(false)
        val next = if (powerSave) 60_000L else 15_000L
        val cadenceChanged = _currentPollIntervalMs.value != next
        _currentPollIntervalMs.value = next

        // Coming back to the list is exactly when the operator expects it to
        // be current — it is what they were getting by pulling to refresh by
        // hand. Waiting out a whole tick first is what made the list feel
        // stuck when nothing was actually wrong.
        if (!_uiState.value.isLoading) refresh()

        // A wait already in flight was scheduled against the old cadence, so
        // dropping from 60s to 15s would otherwise not take effect until the
        // old minute had run out. Restarting the loop re-arms it at the new
        // rate immediately.
        if (cadenceChanged && pollJob?.isActive == true) {
            stopBackgroundRefresh()
            startBackgroundRefresh()
        }
    }

    fun onScreenPaused() {
        _currentPollIntervalMs.value = 60_000L
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            refresh()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce typing before hitting the server
            searchNow(query)
        }
    }

    suspend fun searchNow(query: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val sessions = repository.search(query)
            _uiState.update { it.copy(sessions = sessions, isFromCache = false, isLoading = false) }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    /** Creates a session on the server and returns its id for navigation. */
    suspend fun createSessionNow(): String? = try {
        val created = repository.createSession()
        refreshNow()
        created?.sessionId
    } catch (e: ApiError) {
        onAuthError(e)
        _uiState.update { it.copy(errorMessage = e.userMessage) }
        null
    }

    fun renameSession(id: String, title: String) {
        val previousTitle = _uiState.value.sessions.firstOrNull { it.sessionId == id }?.title
        mutate(
            errorEvent = SessionListEvent.ActionError(message = "Could not rename session."),
            successEvent = { SessionListEvent.Renamed(sessionTitle = previousTitle ?: title, newTitle = title) },
        ) { repository.renameSession(id, title) }
    }

    /**
     * Deletes a single session. Emits a [SessionListEvent.Deleted] on success
     * so the screen can show a Material 3 snackbar with the count (1) and the
     * session's title. UNDO is intentionally not offered — see the top-level
     * note in [SessionListEvent].
     */
    fun deleteSession(id: String) {
        val title = _uiState.value.sessions.firstOrNull { it.sessionId == id }?.title
        mutate(
            errorEvent = SessionListEvent.ActionError(message = "Could not delete session."),
            successEvent = {
                SessionListEvent.Deleted(ids = listOf(id), titles = listOfNotNull(title))
            },
        ) { repository.deleteSession(id) }
    }

    /**
     * Bulk variant for the multi-select toolbar. Optimistically fires ONE
     * [SessionListEvent.Deleted] (with all ids) when the first network call
     * succeeds. Per-session server fan-out is server-side today (the delete
     * endpoint accepts a list); repository call still wraps a single id
     * because the API client signature is unchanged. If the bulk call fails
     * the whole batch raises [SessionListEvent.ActionError] with the count
     * and the user can retry from selection — refresh now reflects whatever
     * actually landed.
     */
    fun deleteSessions(ids: List<String>) {
        if (ids.isEmpty()) return
        val titles = _uiState.value.sessions
            .filter { it.sessionId in ids }
            .mapNotNull { it.title }
        mutateEach(
            ids = ids,
            errorEvent = { failed ->
                SessionListEvent.ActionError(
                    message = if (failed == 1) "Could not delete 1 session."
                    else "Could not delete $failed sessions.",
                )
            },
            successEvent = { done ->
                SessionListEvent.Deleted(
                    ids = done,
                    titles = _uiState.value.sessions
                        .filter { it.sessionId in done }
                        .mapNotNull { it.title }
                        .ifEmpty { titles },
                )
            },
        ) { id -> repository.deleteSession(id) }
    }

    fun pinSession(id: String, pinned: Boolean) = mutate(
        errorEvent = SessionListEvent.ActionError(
            message = if (pinned) "Could not pin session." else "Could not unpin session.",
        ),
        successEvent = { SessionListEvent.Pinned(ids = listOf(id), pinned = pinned) },
    ) { repository.pinSession(id, pinned) }

    fun pinSessions(ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        mutateEach(
            ids = ids,
            errorEvent = { failed ->
                SessionListEvent.ActionError(
                    message = if (pinned) "Could not pin $failed sessions."
                    else "Could not unpin $failed sessions.",
                )
            },
            successEvent = { done -> SessionListEvent.Pinned(ids = done, pinned = pinned) },
        ) { id -> repository.pinSession(id, pinned) }
    }

    fun archiveSession(id: String, archived: Boolean) = mutate(
        errorEvent = SessionListEvent.ActionError(
            message = if (archived) "Could not archive session." else "Could not unarchive session.",
        ),
        successEvent = { SessionListEvent.Archived(ids = listOf(id), archived = archived) },
    ) { repository.archiveSession(id, archived) }

    fun archiveSessions(ids: List<String>, archived: Boolean) {
        if (ids.isEmpty()) return
        mutateEach(
            ids = ids,
            errorEvent = { failed ->
                SessionListEvent.ActionError(
                    message = if (archived) "Could not archive $failed sessions."
                    else "Could not unarchive $failed sessions.",
                )
            },
            successEvent = { done -> SessionListEvent.Archived(ids = done, archived = archived) },
        ) { id -> repository.archiveSession(id, archived) }
    }

    fun moveSession(id: String, projectId: String?) {
        val sessionTitle = _uiState.value.sessions.firstOrNull { it.sessionId == id }?.title
        val projectName = projectId?.let { pid ->
            _uiState.value.projects.firstOrNull { it.projectId == pid }?.name
        }
        mutate(
            errorEvent = SessionListEvent.ActionError(message = "Could not move session."),
            successEvent = {
                SessionListEvent.Moved(sessionTitle = sessionTitle ?: "", projectName = projectName)
            },
        ) { repository.moveSession(id, projectId) }
    }

    /** Duplicates a session server-side; returns the copy's id for navigation. */
    suspend fun duplicateSessionNow(id: String): String? = try {
        val created = repository.duplicateSession(id)
        val originalTitle = _uiState.value.sessions.firstOrNull { it.sessionId == id }?.title
        _events.tryEmit(SessionListEvent.Duplicated(originalTitle = originalTitle))
        refreshNow()
        created?.sessionId
    } catch (e: ApiError) {
        onAuthError(e)
        _uiState.update { it.copy(errorMessage = e.userMessage) }
        null
    }

    /** Forks a session from the full history; returns the fork's id. */
    suspend fun branchSessionNow(id: String): String? = try {
        val response = repository.branchSession(id)
        val errorMessage = response.error
        if (errorMessage != null) {
            _uiState.update { it.copy(errorMessage = errorMessage) }
            _events.tryEmit(SessionListEvent.ActionError(message = errorMessage))
        } else {
            val originalTitle = _uiState.value.sessions.firstOrNull { it.sessionId == id }?.title
            _events.tryEmit(SessionListEvent.Forked(originalTitle = originalTitle))
        }
        refreshNow()
        response.sessionId
    } catch (e: ApiError) {
        onAuthError(e)
        _uiState.update { it.copy(errorMessage = e.userMessage) }
        null
    }

    fun createProject(name: String, color: String?) = projectMutate {
        val r = repository.createProject(name, color); r.error
    }

    fun renameProject(id: String, name: String, color: String?) = projectMutate {
        val r = repository.renameProject(id, name, color); r.error
    }

    fun deleteProject(id: String) = projectMutate {
        val r = repository.deleteProject(id); r.error
    }

    /** Runs a project mutation (returns a nullable error string), then refreshes. */
    private fun projectMutate(action: suspend () -> String?) {
        viewModelScope.launch {
            try {
                val error = action()
                if (error != null) {
                    _uiState.update { it.copy(errorMessage = error) }
                    _events.tryEmit(SessionListEvent.ActionError(message = error))
                }
                refreshNow()
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    /**
     * Applies [action] to every id, one request each, and reports what landed.
     *
     * The bulk paths used to call the single-id endpoint with `ids.first()`,
     * on the belief that it accepted a list. It does not: `/api/session/delete`,
     * `/pin` and `/archive` each read one `session_id` and answer
     * "session_id is required" without it — verified against the **deployed**
     * host, not only the newer upstream tree. So "Delete 5 sessions?" deleted
     * one, and the snackbar still said five. The user was told the work was
     * done while four sessions were still there.
     *
     * Partial failure is reported as partial: the success event carries only
     * the ids that actually succeeded, so the snackbar counts what happened
     * rather than what was asked for.
     *
     * A per-item error (the host refusing one read-only session) continues to
     * the rest. An [ApiError] does not — a dropped connection or an expired
     * grant will fail every remaining id identically, and there is no reason
     * to make fifty doomed requests to discover that.
     */
    private fun mutateEach(
        ids: List<String>,
        errorEvent: (failed: Int) -> SessionListEvent.ActionError,
        successEvent: (succeeded: List<String>) -> SessionListEvent,
        action: suspend (String) -> com.hermexapp.android.model.SessionMutationResponse,
    ) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val succeeded = mutableListOf<String>()
            var firstError: String? = null
            for (id in ids) {
                try {
                    val response = action(id)
                    if (response.error != null) {
                        firstError = firstError ?: response.error
                    } else {
                        succeeded += id
                    }
                } catch (e: ApiError) {
                    onAuthError(e)
                    firstError = firstError ?: e.userMessage
                    break
                }
            }
            if (succeeded.isNotEmpty()) _events.tryEmit(successEvent(succeeded))
            if (succeeded.size < ids.size) {
                firstError?.let { message ->
                    _uiState.update { it.copy(errorMessage = message) }
                }
                _events.tryEmit(errorEvent(ids.size - succeeded.size))
            }
            refreshNow()
        }
    }

    /** Single-id action: run the network call, surface `errorMessage` on failure, refresh,
     *  then emit the **success event** (only if the server didn't return an error). */
    private fun mutate(
        errorEvent: SessionListEvent.ActionError,
        successEvent: () -> SessionListEvent,
        action: suspend () -> com.hermexapp.android.model.SessionMutationResponse,
    ) {
        viewModelScope.launch {
            try {
                val response = action()
                if (response.error != null) {
                    _uiState.update { it.copy(errorMessage = response.error) }
                    _events.tryEmit(errorEvent)
                } else {
                    // Only fire the success snackbar on a clean response. We
                    // intentionally fire BEFORE refreshNow so the screen
                    // sees the snackbar event concurrently with the new
                    // sessions list — the snackbar host renders even while
                    // the list is recomposing.
                    _events.tryEmit(successEvent())
                }
                refreshNow()
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    // ----- Bulk-selection (Wave 0) -----

    /**
     * Enters bulk-select mode with [id] selected. If already in select mode,
     * the existing selection is preserved (so long-press adds and tap toggles).
     */
    fun beginSelection(id: String) {
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedIds = if (it.selectedIds.isEmpty()) setOf(id) else (it.selectedIds + id),
            )
        }
    }

    /** Toggles one id's selection. Exits mode when the set becomes empty. */
    fun toggleSelection(id: String) {
        _uiState.update {
            val next = it.selectedIds.toMutableSet().apply {
                if (!add(id)) remove(id)
            }
            it.copy(
                selectionMode = next.isNotEmpty(),
                selectedIds = next,
            )
        }
    }

    /** Selects every session in the current load (server'd sessions, not including cached-only rows). */
    fun selectAllVisible() {
        _uiState.update { state ->
            state.copy(
                selectionMode = true,
                selectedIds = state.sessions.mapNotNull { it.sessionId }.toSet(),
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    /** Convenience accessor used by the screen's snackbar formatting. */
    val selectedCount: Int get() = _uiState.value.selectedIds.size

    /**
     * Wave 7 Slice 7.2 — set the sidebar filter pill. [mode] replaces
     * the current filter; passing the already-active mode is a no-op
     * (the screen renders toggle-off separately by re-tapping the chip
     * itself, which calls this with [FilterMode.All]).
     *
     * Pure local state — does not hit the network. The grouped-bucket
     * computed view (see [filteredSessions]) re-derives from
     * [UiState.sessions] on every read.
     */
    fun setFilterMode(mode: FilterMode) {
        _uiState.update { it.copy(filterMode = mode) }
    }

    /**
     * Wave 7 Slice 7.2 — sessions visible under the current
     * [UiState.filterMode]. Empty list is preserved verbatim (the
     * LazyColumn already handles the empty-state item). [FilterMode.All]
     * returns the full list unsorted — the caller's grouping helper
     * owns the actual ordering.
     */
    val filteredSessions: List<SessionSummary>
        get() = filterSessions(_uiState.value.sessions, _uiState.value.filterMode)

}
