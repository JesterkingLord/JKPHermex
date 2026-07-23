package com.hermexapp.android.features.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.network.ApiError
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
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val repository: SessionRepository,
    private val onAuthError: (Throwable) -> Unit = {},
) : ViewModel() {

    data class UiState(
        val sessions: List<SessionSummary> = emptyList(),
        val projects: List<com.hermexapp.android.model.Project> = emptyList(),
        val searchQuery: String = "",
        val isLoading: Boolean = false,
        val isFromCache: Boolean = false,
        val errorMessage: String? = null,
        /** Excellence v1 Wave 0: bulk-select mode (long-press → multi-select toolbar). */
        val selectionMode: Boolean = false,
        /** Stable session ids currently selected. Empty when not in selection mode. */
        val selectedIds: Set<String> = emptySet(),
    )

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

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val result = repository.loadSessions()
            _uiState.update {
                it.copy(sessions = result.sessions, isFromCache = result.fromCache, isLoading = false)
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
        // Projects are best-effort: never block or error the session list on them.
        runCatching { repository.loadProjects() }.getOrNull()?.let { projects ->
            _uiState.update { it.copy(projects = projects) }
        }
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
        val actionMessage = if (ids.size == 1) "Could not delete session." else "Could not delete ${ids.size} sessions."
        mutate(
            errorEvent = SessionListEvent.ActionError(message = actionMessage),
            successEvent = { SessionListEvent.Deleted(ids = ids, titles = titles) },
        ) { repository.deleteSession(ids.first()) }
    }

    fun pinSession(id: String, pinned: Boolean) = mutate(
        errorEvent = SessionListEvent.ActionError(
            message = if (pinned) "Could not pin session." else "Could not unpin session.",
        ),
        successEvent = { SessionListEvent.Pinned(ids = listOf(id), pinned = pinned) },
    ) { repository.pinSession(id, pinned) }

    fun pinSessions(ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        mutate(
            errorEvent = SessionListEvent.ActionError(
                message = if (pinned) "Could not pin sessions." else "Could not unpin sessions.",
            ),
            successEvent = { SessionListEvent.Pinned(ids = ids, pinned = pinned) },
        ) { repository.pinSession(ids.first(), pinned) }
    }

    fun archiveSession(id: String, archived: Boolean) = mutate(
        errorEvent = SessionListEvent.ActionError(
            message = if (archived) "Could not archive session." else "Could not unarchive session.",
        ),
        successEvent = { SessionListEvent.Archived(ids = listOf(id), archived = archived) },
    ) { repository.archiveSession(id, archived) }

    fun archiveSessions(ids: List<String>, archived: Boolean) {
        if (ids.isEmpty()) return
        mutate(
            errorEvent = SessionListEvent.ActionError(
                message = if (archived) "Could not archive sessions." else "Could not unarchive sessions.",
            ),
            successEvent = { SessionListEvent.Archived(ids = ids, archived = archived) },
        ) { repository.archiveSession(ids.first(), archived) }
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
        if (response.error != null) {
            _uiState.update { it.copy(errorMessage = response.error) }
            _events.tryEmit(SessionListEvent.ActionError(message = response.error))
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
}
