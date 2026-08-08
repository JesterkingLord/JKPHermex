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
import com.hermexapp.android.network.mediaBytes
import com.hermexapp.android.network.session
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
        /**
         * The path we asked for, kept because the server does not always send
         * `name` back — without it the header read "Files" while a file was on
         * screen, which is the one moment it should say which file.
         */
        val openFilePath: String? = null,
        /**
         * Absolute path for `/api/media` when the open file is one we preview
         * rather than read as text. Null for everything else, including an
         * image whose workspace root we never learned — see
         * [workspaceAbsolutePath] for why that must not become a request.
         */
        val openMediaPath: String? = null,
        /**
         * Set when the open file is one `/api/file` could only return as
         * mojibake and that we cannot render either — video, audio, archives,
         * PDFs. The screen says so instead of showing the garbage.
         */
        val openUnreadableKind: WorkspaceFileKind? = null,
        /** Absolute workspace root, from the session; see [loadWorkspaceRoot]. */
        val workspaceRoot: String? = null,
        val gitStatus: GitStatus? = null,
        val gitBranches: GitBranches? = null,
        val openDiff: GitDiff? = null,
        val sort: WorkspaceSort = WorkspaceSort.NAME,
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
        _uiState.update {
            // openMediaPath clears with openFile: a listing that left the
            // preview mounted would draw the old image over the new directory.
            it.copy(
                isLoading = true,
                errorMessage = null,
                openFile = null,
                openMediaPath = null,
                openUnreadableKind = null,
            )
        }
        try {
            val response = client.directoryList(sessionId, path)
            _uiState.update { state ->
                state.copy(
                    entries = sortWorkspaceEntries(response.entries.orEmpty(), state.sort),
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

    /** Crumbs for the current directory, root first. */
    val crumbs: List<WorkspaceCrumb> get() = workspaceCrumbs(_uiState.value.currentPath)

    /**
     * Re-orders what is already loaded. No refetch: the entries in hand are
     * the same ones the server would return, and a network round trip to
     * change a sort would make the control feel broken.
     */
    fun setSort(sort: WorkspaceSort) {
        _uiState.update { it.copy(sort = sort, entries = sortWorkspaceEntries(it.entries, sort)) }
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
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        if (workspaceFileKind(name) == WorkspaceFileKind.IMAGE) {
            val absolute = workspaceAbsolutePath(_uiState.value.workspaceRoot, path)
            if (absolute != null) {
                // Deliberately no /api/file call: it decodes bytes as UTF-8 with
                // errors='replace', so reading an image there yields a screen of
                // replacement characters and no error to explain them.
                _uiState.update {
                    it.copy(
                        openFile = null,
                        openFilePath = path,
                        openMediaPath = absolute,
                        openUnreadableKind = null,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                return
            }
            // No root, so no absolute path, so nothing /api/media would accept.
            // Fall through and read it as text: mojibake is poor, but it is
            // better than a blank screen that says nothing happened.
        }

        if (workspaceIsUnreadableAsText(name)) {
            // No text read at all. There is nothing to render for these, but
            // saying so beats a screen of replacement characters that looks
            // like the file itself is corrupt.
            _uiState.update {
                it.copy(
                    openFile = null,
                    openFilePath = path,
                    openMediaPath = null,
                    openUnreadableKind = workspaceFileKind(name),
                    isLoading = false,
                    errorMessage = null,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                openMediaPath = null,
                openUnreadableKind = null,
            )
        }
        try {
            val response = client.file(sessionId, path)
            _uiState.update {
                it.copy(
                    openFile = response,
                    openFilePath = path,
                    isLoading = false,
                    errorMessage = response.error,
                )
            }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    /**
     * Learns the workspace root, which the deployed `/api/list` does not send.
     *
     * Uses `/api/session`, **not** `/api/session/status`. Status looked like the
     * cheaper choice and is wrong: probed against the live host, it answers
     * `404 Session not found` for messaging-sourced sessions — including the
     * operator's own `source_tag: telegram` session — while returning the
     * workspace happily for WebUI-native ones. Sourcing the root from it meant
     * previews silently degrading to the text read on exactly the sessions in
     * daily use, which is indistinguishable from the feature not working.
     *
     * `/api/session` returns the workspace for both kinds. `messages=0` keeps
     * it cheap: the transcript is not wanted, only the root.
     *
     * Failure stays silent on purpose. Without a root images fall back to the
     * text read — the behaviour that shipped before previews existed — and an
     * error banner would report a degraded preview as a broken browser.
     */
    suspend fun loadWorkspaceRoot() {
        if (_uiState.value.workspaceRoot != null) return
        val root = runCatching {
            client.session(sessionId, includeMessages = false, messageLimit = null)
                .session?.workspace
        }.getOrNull()
        if (!root.isNullOrBlank()) _uiState.update { it.copy(workspaceRoot = root) }
    }

    fun closeFile() = _uiState.update {
        it.copy(
            openFile = null,
            openFilePath = null,
            openMediaPath = null,
            openUnreadableKind = null,
        )
    }

    /** Bytes for the open preview, fetched by the UI layer that decodes them. */
    suspend fun mediaBytes(absolutePath: String): ByteArray = client.mediaBytes(absolutePath)

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
