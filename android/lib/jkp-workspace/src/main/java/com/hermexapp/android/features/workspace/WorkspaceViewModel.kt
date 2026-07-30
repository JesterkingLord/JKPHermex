package com.hermexapp.android.features.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.model.FileResponse
import com.hermexapp.android.model.GitBranches
import com.hermexapp.android.model.GitDiff
import com.hermexapp.android.model.WorkspaceEntry
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.model.GitStatus
import com.hermexapp.android.network.directoryList
import com.hermexapp.android.network.file
import com.hermexapp.android.network.gitBranches
import com.hermexapp.android.network.gitCheckout
import com.hermexapp.android.network.gitCommit
import com.hermexapp.android.network.gitDiff
import com.hermexapp.android.network.gitDiscard
import com.hermexapp.android.network.gitFetch
import com.hermexapp.android.network.gitPull
import com.hermexapp.android.network.gitPush
import com.hermexapp.android.network.gitStage
import com.hermexapp.android.network.gitStatus
import com.hermexapp.android.network.gitUnstage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 6: workspace file browser + read-only git views for one session.
 * Mirrors the read paths of the iOS FileBrowser/GitWorkspace view models;
 * git mutations (stage/commit/checkout) are a deferred slice.
 */
class WorkspaceViewModel(
    private val sessionId: String,
    private val client: ApiClient,
    private val onAuthError: (Throwable) -> Unit = {},
) : ViewModel() {

    data class UiState(
        val pathStack: List<String?> = listOf(null),
        val currentPath: String? = null,
        val entries: List<WorkspaceEntry> = emptyList(),
        val openFile: FileResponse? = null,
        val gitStatus: GitStatus? = null,
        val gitBranches: GitBranches? = null,
        val openDiff: GitDiff? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val noticeMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadDirectory(path: String? = null, push: Boolean = true) {
        viewModelScope.launch { loadDirectoryNow(path, push) }
    }

    suspend fun loadDirectoryNow(path: String? = null, push: Boolean = true) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, openFile = null) }
        try {
            val response = client.directoryList(sessionId, path)
            _uiState.update { state ->
                state.copy(
                    entries = response.entries.orEmpty()
                        .sortedWith(
                            compareByDescending<WorkspaceEntry> { it.isBrowsableDirectory }
                                .thenBy { it.name?.lowercase() ?: "" },
                        ),
                    currentPath = path,
                    pathStack = if (push) state.pathStack + listOf(path) else state.pathStack,
                    isLoading = false,
                    errorMessage = response.error,
                )
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    /** @return false when already at the workspace root (caller should close). */
    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size <= 1) return false
        val parent = stack[stack.size - 2]
        _uiState.update { it.copy(pathStack = stack.dropLast(1)) }
        viewModelScope.launch { loadDirectoryNow(parent, push = false) }
        return true
    }

    fun openFile(path: String) {
        viewModelScope.launch { openFileNow(path) }
    }

    suspend fun openFileNow(path: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val response = client.file(sessionId, path)
            _uiState.update {
                it.copy(openFile = response, isLoading = false, errorMessage = response.error)
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    fun closeFile() = _uiState.update { it.copy(openFile = null) }

    fun loadGit() {
        viewModelScope.launch { loadGitNow() }
    }

    suspend fun loadGitNow() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, openDiff = null) }
        try {
            val status = client.gitStatus(sessionId).git
            val branches = runCatching { client.gitBranches(sessionId).branches }.getOrNull()
            _uiState.update {
                it.copy(gitStatus = status, gitBranches = branches, isLoading = false)
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    fun openDiff(path: String, staged: Boolean?) {
        viewModelScope.launch { openDiffNow(path, staged) }
    }

    suspend fun openDiffNow(path: String, staged: Boolean?) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val kind = if (staged == true) "staged" else "unstaged"
            val response = client.gitDiff(sessionId, path, kind)
            _uiState.update { it.copy(openDiff = response.diff, isLoading = false) }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    fun closeDiff() = _uiState.update { it.copy(openDiff = null) }

    // ── Git mutations (Phase 6 deferred slice) ──

    fun stage(path: String) = gitAction { client.gitStage(sessionId, listOf(path)).git }
    fun unstage(path: String) = gitAction { client.gitUnstage(sessionId, listOf(path)).git }
    fun discard(path: String) = gitAction { client.gitDiscard(sessionId, listOf(path)).git }
    fun fetch() = gitAction { client.gitFetch(sessionId).git }
    fun pull() = gitAction { client.gitPull(sessionId).git }
    fun push() = gitAction { client.gitPush(sessionId).git }

    fun commit(message: String) = gitAction {
        val response = client.gitCommit(sessionId, message)
        _uiState.update {
            it.copy(noticeMessage = response.shortSha?.let { sha -> "Committed $sha" } ?: "Committed")
        }
        response.resolvedStatus
    }

    fun checkout(ref: String) = gitAction { client.gitCheckout(sessionId, ref).resolvedStatus }

    /**
     * Runs a git write and refreshes status from the response when it carries
     * one, else re-fetches. Errors surface without wiping the current view.
     */
    private fun gitAction(action: suspend () -> GitStatus?) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, noticeMessage = null) }
            try {
                val status = action()
                if (status != null) {
                    _uiState.update { it.copy(gitStatus = status) }
                } else {
                    loadGitNow()
                }
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }
}
