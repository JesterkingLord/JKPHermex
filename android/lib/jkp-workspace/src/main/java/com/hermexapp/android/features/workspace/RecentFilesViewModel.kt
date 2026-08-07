package com.hermexapp.android.features.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.network.directoryList
import com.hermexapp.android.network.sessions
import com.hermexapp.android.persistence.UploadLedger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Files from across your recent sessions, in one list.
 *
 * Deliberately refresh-only. A background sweep of ten sessions is a burst of
 * requests the operator never asked for, and the session list already polls
 * for freshness — so this loads once on entry and then only when asked.
 */
class RecentFilesViewModel(
    private val client: ApiClient,
    private val onAuthError: (Throwable) -> Unit = {},
    private val sessionLimit: Int = DEFAULT_SESSION_SCAN_LIMIT,
    /**
     * What this device uploaded. Null means the "Uploaded from this device"
     * filter has nothing to consult, so it is hidden rather than shown empty
     * and blamed on there being no uploads.
     */
    private val uploadLedger: UploadLedger? = null,
) : ViewModel() {

    data class UiState(
        val files: List<RecentFile> = emptyList(),
        /** Sessions we tried. Shown so "8 of 10" can be honest about the 10. */
        val sessionsAttempted: Int = 0,
        val sessionsFailed: Int = 0,
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val errorMessage: String? = null,
        val filter: RecentFileFilter = RecentFileFilter.ALL,
        /** False when there is no ledger to consult, which hides the filter. */
        val canFilterByUpload: Boolean = false,
    ) {
        /** True when some sessions could not be read but others could. */
        val isPartial: Boolean get() = sessionsFailed > 0 && sessionsFailed < sessionsAttempted
    }

    /** Switches the visible set. Pure state — never refetches. */
    fun setFilter(filter: RecentFileFilter) = _uiState.update { it.copy(filter = filter) }

    /** The files the list should show, after [UiState.filter]. */
    fun visibleFiles(state: UiState): List<RecentFile> = filterRecentFiles(
        files = state.files,
        filter = state.filter,
        wasUploadedHere = { path -> uploadLedger?.wasUploadedFromThisDevice(path) == true },
    )

    private val _uiState = MutableStateFlow(UiState(canFilterByUpload = uploadLedger != null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val sources = try {
            recentSessionsToScan(client.sessions().sessions.orEmpty(), sessionLimit)
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update {
                it.copy(isLoading = false, hasLoaded = true, errorMessage = e.userMessage)
            }
            return
        }

        if (sources.isEmpty()) {
            _uiState.update {
                it.copy(files = emptyList(), sessionsAttempted = 0, sessionsFailed = 0,
                    isLoading = false, hasLoaded = true)
            }
            return
        }

        val listings = mutableListOf<SessionListing>()
        var failed = 0
        var lastError: ApiError? = null

        for (source in sources) {
            try {
                val response = client.directoryList(source.sessionId, null)
                // A per-session `error` is a failure even though the call
                // returned: the entries list is then empty for a reason the
                // user should be told about, not silently counted as success.
                if (response.error != null) {
                    failed += 1
                } else {
                    listings += SessionListing(source, response.entries.orEmpty())
                }
            } catch (e: ApiError) {
                // One unreadable session must not empty the whole view: a list
                // from eight of ten sessions is worth more than an error page.
                failed += 1
                lastError = e
            }
        }

        // Only escalate to the auth handler when nothing worked. A single 403
        // on one session is not a reason to sign the user out.
        if (listings.isEmpty() && lastError != null) onAuthError(lastError)

        val merged = mergeRecentFiles(listings)
        _uiState.update {
            it.copy(
                files = merged,
                sessionsAttempted = sources.size,
                sessionsFailed = failed,
                isLoading = false,
                hasLoaded = true,
                errorMessage = if (listings.isEmpty()) {
                    lastError?.userMessage ?: "No sessions could be read."
                } else {
                    null
                },
            )
        }
    }
}
