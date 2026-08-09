package com.hermexapp.android.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.config.AppPrefs
import com.hermexapp.android.config.StreamingSendBehavior
import com.hermexapp.android.persistence.SentPromptsStore
import com.hermexapp.android.persistence.UploadLedger
import com.hermexapp.android.features.sessionlist.SessionRepository
import com.hermexapp.android.model.ApprovalChoice
import com.hermexapp.android.model.ChatMessage
import com.hermexapp.android.model.ContextWindowSnapshot
import com.hermexapp.android.model.PendingApproval
import com.hermexapp.android.model.PendingClarification
import com.hermexapp.android.model.ReasoningEffort
import com.hermexapp.android.model.SessionDetail
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.network.ApiJson
import com.hermexapp.android.network.ReasoningDisplay
import com.hermexapp.android.network.SseEvent
import com.hermexapp.android.network.SseStreaming
import com.hermexapp.android.network.cancelChat
import com.hermexapp.android.network.chatStreamUrl
import com.hermexapp.android.network.reasoning
import com.hermexapp.android.network.respondApproval
import com.hermexapp.android.network.respondClarification
import com.hermexapp.android.network.retrySession
import com.hermexapp.android.network.saveReasoningDisplay
import com.hermexapp.android.network.saveReasoningEffort
import com.hermexapp.android.network.startChat
import com.hermexapp.android.network.steerChat
import com.hermexapp.android.network.uploadFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Mobile is an authenticated control surface; keep command approvals session-scoped and non-modal. */
private const val AUTO_APPROVE_MOBILE_COMMANDS = true

/** v0.8.15 slice 5 — seconds of stream silence before the "Working…" chip appears. */
private const val WORKING_CHIP_SILENT_SECONDS = 8

/** v0.8.15 slice 5 — how long "Reply ready" stays visible after a `done` event. */
private const val REPLY_READY_DURATION_MS = 2_000L

/**
 * Phase 4 chat state machine: transcript load, send → `/api/chat/start` →
 * SSE stream, token/reasoning/tool events into a timeline, steer-while-
 * running, and stop via `/api/chat/cancel`. Rendering is plain text for now —
 * streaming markdown is the plan's flagged risk and lands as its own slice.
 *
 * SSE events arrive on OkHttp's reader thread; every mutation goes through
 * `MutableStateFlow.update`, which is atomic, so no main-thread hop is needed.
 */

class ChatViewModel(
    private val sessionId: String,
    private val repository: SessionRepository,
    private val client: ApiClient,
    private val sse: SseStreaming,
    val prefs: AppPrefs? = null,
    private val onAuthError: (Throwable) -> Unit = {},
    /**
     * v0.8.15 slice 5 — injectable wall clock. Production uses the real
     * clock; JVM tests inject a virtual-clock source so the stall watch
     * and the "Reply ready" window are deterministic under runTest.
     */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Rolling history of prompts the operator actually sent, so a prompt that
     * worked well can be promoted into the library afterwards instead of
     * having to be saved before it was known to be any good. Optional: chat
     * works exactly the same without it.
     */
    private val sentPrompts: SentPromptsStore? = null,
    /**
     * Records what this device uploads, so the file library can label it.
     * The host keeps no provenance of its own — `/api/list` reports a name, a
     * size and an mtime and nothing about how the file arrived — so this is
     * the only place that knowledge exists. Optional: uploads behave exactly
     * the same without it, they simply go unlabelled.
     */
    private val uploadLedger: UploadLedger? = null,
    /**
     * v0.8.15 slice 5 — injectable dispatcher for the stall-watch loop.
     * Production uses [Dispatchers.Default]; tests inject the runTest
     * scheduler's dispatcher to drive 8s of silence with virtual time.
     */
    private val hangDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    sealed class TimelineEntry {
        abstract val id: String

        data class UserMessage(override val id: String, val text: String) : TimelineEntry()

        data class AssistantMessage(
            override val id: String,
            val text: String,
            val isStreaming: Boolean = false,
        ) : TimelineEntry()

        data class Reasoning(
            override val id: String,
            val text: String,
            val isStreaming: Boolean = false,
        ) : TimelineEntry()

        data class ToolCall(
            override val id: String,
            val name: String?,
            val preview: String?,
            val isRunning: Boolean,
            val isError: Boolean = false,
            val durationSeconds: Double? = null,
        ) : TimelineEntry()

        data class Notice(override val id: String, val text: String) : TimelineEntry()
    }

/**
 * v0.8.15: one buffered follow-up message. Enqueued by the /queue and
 * /interrupt slash commands and by long-press send while a run is
 * streaming ([StreamingSendBehavior.QUEUE] appends at the back, INTERRUPT
 * inserts at the front). Drained by [ChatViewModel.drainQueuedMessages]
 * when the current run ends.
 */
data class QueuedMessage(
    val id: String,
    val text: String,
    val attachments: List<PendingAttachment> = emptyList(),
)

    data class UiState(
        val title: String? = null,
        val entries: List<TimelineEntry> = emptyList(),
        val composerText: String = "",
        val isLoading: Boolean = false,
        val isStreaming: Boolean = false,
        val isFromCache: Boolean = false,
        val errorMessage: String? = null,
        val composerConfig: ComposerConfig = ComposerConfig(),
        val attachments: List<PendingAttachment> = emptyList(),
        val isUploadingAttachment: Boolean = false,
        /**
         * v0.8.15: client-side queue of follow-up messages waiting for the
         * current run to finish. The Hermes server has no native
         * `/api/chat/queue` HTTP route, so we buffer in the ViewModel and
         * drain on [SseEvent.Done] / [SseEvent.Cancelled]. Filled by the
         * /queue and /interrupt slash commands and by long-press send
         * ([StreamingSendBehavior.QUEUE] appends, INTERRUPT inserts first).
         */
        val queuedMessages: List<QueuedMessage> = emptyList(),
        /** Pre-computed "Queued for next turn (#N)" label for the chip; empty when none. */
        val queuedMessagesLabel: String = "",
        /** Set once per completed run so the screen can fire a completion haptic/notification. */
        val finishedRunCount: Int = 0,
        /** Context-window usage from the last `done` event (Phase 9.2 indicator). */
        val contextWindow: ContextWindowSnapshot? = null,
        val pendingClarification: PendingClarification? = null,
        /**
         * Currently-selected reasoning effort (mirrors the iOS
         * `selectedReasoningEffort`). `AUTO` is the default — we let the
         * server pick. Persisted across restarts via [AppPrefs].
         */
        val selectedReasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
        /**
         * When false, reasoning blocks are hidden from the timeline entirely
         * (they're still received from the server so toggling on is instant).
         * Persisted via [AppPrefs] as `show_reasoning`. Default true.
         */
        val showReasoning: Boolean = true,
        /**
         * Last non-fatal error from a `/api/reasoning` call (e.g. "wait for
         * the current response to finish before changing reasoning"). Cleared
         * the next time the picker is opened. The composer shows this as a
         * non-blocking banner.
         */
        val reasoningErrorMessage: String? = null,
        /**
         * Hang honesty (0.7.1): retained for non-approval stalls and transport
         * recovery. Mobile command approvals are resolved session-scoped.
         */
        val hangTip: String? = null,
        /**
         * Excellence v1 Wave 0: draft text from the most recently failed send,
         * surfaced as a retry affordance in the composer. `null` when there
         * is nothing to retry. Cleared on a successful send so a stale
         * banner doesn't linger indefinitely.
         */
        val lastFailedDraft: String? = null,
        /**
         * Excellence 13.10 / 7.4: after a stream drop, invite resend from the
         * composer without inventing reconnect APIs.
         */
        val streamRecoveryOffer: Boolean = false,
        val streamRecoveryTip: String? = null,
        /**
         * Wave 4 — in-chat find. `searchActive` toggles the [ChatSearchBar]
         * overlay. `searchQuery` is the live substring; `searchMatchEntries`
         * is the chronological list of entry indexes that contain a hit
         * (recomputed in [setSearchQuery] when the query or timeline
         * changes). `searchCurrentIndex` is the cursor inside
         * `searchMatchEntries`; `-1` when there is no current match
         * (either no query, or zero matches, or the cursor is between
         * matches).
         */
        val searchActive: Boolean = false,
        val searchQuery: String = "",
        val searchMatchEntries: List<Int> = emptyList(),
        val searchCurrentIndex: Int = -1,
        /**
         * Wave 5 Slice 5.2 — count of new timeline entries that arrived
         * while the user was scrolled up. The Chat screen bumps this via
         * [bumpUnreadCount] when a new entry lands while the user is not
         * at the bottom, and resets via [markSeen] when the user scrolls
         * near the bottom. Surfaced as a "↓ N new" pill above the JumpFab.
         */
        val unreadCount: Int = 0,
        /**
         * Wave 5 Slice 5.1 — wall-clock ms of the most recent empty-send
         * attempt. The Chat screen reads this and shows a "Type something
         * first" caption for ~2 seconds. `null` means no recent attempt.
         */
        val lastEmptySendAtMs: Long? = null,
        /**
         * v0.8.14 — transport reachability for the composer. Defaults to
         * [JkpConnectionState.CONNECTED] so existing behavior (send enabled
         * once a session is selected) is preserved until a load/send/stream
         * signal says otherwise. Read-only for the UI; mutated by the tiny
         * [setConnectionState] wrapper and the transport-failure wiring.
         */
        val connectionState: JkpConnectionState = JkpConnectionState.CONNECTED,
        /**
         * v0.8.15: lightweight non-modal status chip shown above the composer
         * while the agent is working. Differs from [hangTip] in three ways:
         * (1) surfaces immediately on activity drop (not after a long stall),
         * (2) shows a friendly "Working…" instead of an apology,
         * (3) clears the moment any SSE event lands.
         */
        val streamStatusChip: String? = null,

        /**
         * Non-null for ~2 seconds when a `done` event lands, so a user scrolled
         * up notices the reply. Empty after that.
         */
        val replyReadyUntilMs: Long = 0L,
    ) {
        val slashSuggestions: List<com.hermexapp.android.model.AgentCommand>
            get() = composerConfig.slashSuggestions(composerText)

        /** "3 / 12" or "No matches" — precomputed for the search bar footer. */
        val searchStatusText: String
            get() = when {
                !searchActive -> ""
                searchQuery.isBlank() -> "Type to search"
                searchMatchEntries.isEmpty() -> "No matches"
                else -> "${searchCurrentIndex + 1} / ${searchMatchEntries.size}"
            }

        /** "3 new" — precomputed pill label, empty string when 0. */
        val unreadCountLabel: String
            get() = when {
                unreadCount <= 0 -> ""
                unreadCount == 1 -> "1 new"
                else -> "$unreadCount new"
            }
    }

    private val _uiState = MutableStateFlow(
        UiState(
            selectedReasoningEffort = prefs?.reasoningEffort?.value ?: ReasoningEffort.AUTO,
            showReasoning = prefs?.showReasoning?.value ?: true,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var activeStreamId: String? = null
    private var entryCounter = 0
    /** Wall-clock ms of last SSE activity while streaming (for hang honesty). */
    private var lastActivityAtMs: Long = 0L
    private var stallWatchJob: Job? = null
    /**
     * Hang-honesty timer uses Default (not Main) so JVM unit tests without a
     * Main dispatcher still exercise [sendNow] / stream paths.
     */
    private val hangScope = CoroutineScope(SupervisorJob() + hangDispatcher)

    /**
     * Excellence 13.9 / 7.4: dedicated Default-dispatcher scope for best-effort
     * post-drop session re-fetch. Uses Default (not Main) so JVM unit tests
     * without a Main dispatcher still exercise the recovery path. SupervisorJob
     * isolates a recovery failure from the rest of the ViewModel.
     */
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Visible to JVM lifecycle tests; both scopes must be dead after teardown. */
    internal val hasActiveBackgroundWork: Boolean
        get() = hangScope.isActive || reconnectScope.isActive

    /** Reconnect attempts used for the current run (reset in [sendNow]). */
    private var reconnectAttemptCount: Int = 0

    private fun nextId(prefix: String): String = "$prefix-${entryCounter++}"

    private fun markStreamActivity() {
        lastActivityAtMs = nowMs()
        // Clear transient chips on new activity so they re-arm after another
        // silent stretch (hangTip + v0.8.15 streamStatusChip).
        val state = _uiState.value
        if (state.hangTip != null || state.streamStatusChip != null) {
            _uiState.update { it.copy(hangTip = null, streamStatusChip = null) }
        }
    }

    private fun startStallWatch() {
        stallWatchJob?.cancel()
        lastActivityAtMs = nowMs()
        stallWatchJob = hangScope.launch {
            while (isActive) {
                delay(1_000L)
                val state = _uiState.value
                if (!state.isStreaming) break
                val silent = ((nowMs() - lastActivityAtMs) / 1000L).toInt()
                val tip = HangHonesty.tipIfStalled(
                    isStreaming = true,
                    silentSeconds = silent,
                    // Mobile approvals are resolved in the background for the
                    // paired session, so never tell the user to approve on a
                    // surface that intentionally has no approval modal.
                    hasPendingApproval = AUTO_APPROVE_MOBILE_COMMANDS,
                )
                val banner = if (tip != null) HangHonesty.banner(silent) else null
                // v0.8.15 slice 5: faster feedback than the 15s HangHonesty
                // tip — a friendly "Working…" chip after 8s of silence.
                val working = if (silent >= WORKING_CHIP_SILENT_SECONDS) HangHonesty.WORKING_CHIP else null
                if (state.hangTip != banner || state.streamStatusChip != working) {
                    _uiState.update { it.copy(hangTip = banner, streamStatusChip = working) }
                }
            }
        }
    }

    private fun stopStallWatch() {
        stallWatchJob?.cancel()
        stallWatchJob = null
        if (_uiState.value.hangTip != null) {
            _uiState.update { it.copy(hangTip = null) }
        }
    }

    override fun onCleared() {
        teardown()
        super.onCleared()
    }

    fun updateComposerText(value: String) = _uiState.update { it.copy(composerText = value) }

    /**
     * v0.8.14 — tiny wrapper for the composer's transport reachability.
     * Read-only on the UI side; callers (tests today, a heartbeat later)
     * surface the host connection state here.
     */
    fun setConnectionState(state: JkpConnectionState) {
        _uiState.update { it.copy(connectionState = state) }
    }

    /** v0.8.14 — transport failures (host unreachable) mark the connection failed. */
    private fun markConnectionFailedIfTransport(e: ApiError) {
        if (e is ApiError.Network) {
            _uiState.update { it.copy(connectionState = JkpConnectionState.FAILED) }
        }
    }

    /** Appends dictated text (from on-device speech recognition) into the composer. */
    fun appendDictatedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _uiState.update {
            val separator = if (it.composerText.isBlank() || it.composerText.endsWith(" ")) "" else " "
            it.copy(composerText = it.composerText + separator + trimmed)
        }
    }

    fun load() {
        viewModelScope.launch { loadNow() }
        viewModelScope.launch { loadComposerConfigNow() }
        viewModelScope.launch { loadReasoningState() }
    }

    /**
     * Syncs the server's reported reasoning state (`GET /api/reasoning`)
     * into the UI. The server is the source of truth — what we persisted
     * in [AppPrefs] may be stale, or the user may have changed it from
     * another device via the desktop CLI.
     */
    suspend fun loadReasoningState() {
        try {
            val status = client.reasoning()
            val serverEffort = status.effectiveEffort
            val serverShow = status.effectiveShowReasoning
            prefs?.setReasoningEffort(serverEffort)
            prefs?.setShowReasoning(serverShow)
            _uiState.update {
                it.copy(
                    selectedReasoningEffort = serverEffort,
                    showReasoning = serverShow,
                    reasoningErrorMessage = null,
                )
            }
        } catch (e: ApiError) {
            // Non-fatal: the composer still has the local value.
            onAuthError(e)
        }
    }

    /**
     * Picks a reasoning effort for the next message. Optimistically updates
     * local state + prefs, then posts to `POST /api/reasoning` so the
     * server also persists it. On failure, the UI rolls back to the
     * previous effort and surfaces the error.
     *
     * Mirrors the iOS `selectReasoningEffort(_:)`:
     *   - Auto/Auto is rejected while a run is in flight (iOS #824)
     *   - All changes are blocked while `isStreaming` (iOS #828)
     */
    fun selectReasoningEffort(value: ReasoningEffort) {
        val previous = _uiState.value.selectedReasoningEffort
        if (previous == value) return
        if (_uiState.value.isStreaming) {
            _uiState.update {
                it.copy(reasoningErrorMessage = "Wait for the current response to finish before changing reasoning.")
            }
            return
        }
        // Optimistic update.
        _uiState.update {
            it.copy(selectedReasoningEffort = value, reasoningErrorMessage = null)
        }
        prefs?.setReasoningEffort(value)
        viewModelScope.launch {
            try {
                val response = client.saveReasoningEffort(value)
                val effective = response.effectiveEffort
                if (effective != value) {
                    // Server clamped (model doesn't support the requested level).
                    prefs?.setReasoningEffort(effective)
                    _uiState.update {
                        it.copy(
                            selectedReasoningEffort = effective,
                            reasoningErrorMessage = "Server adjusted to $effective (not supported by current model).",
                        )
                    }
                }
            } catch (e: ApiError) {
                prefs?.setReasoningEffort(previous)
                _uiState.update {
                    it.copy(
                        selectedReasoningEffort = previous,
                        reasoningErrorMessage = e.userMessage,
                    )
                }
                onAuthError(e)
            }
        }
    }

    /**
     * Toggles whether reasoning blocks appear in the timeline. Persists
     * locally immediately, then mirrors to the server so the desktop
     * / iOS stay in sync. On failure, rolls back.
     */
    fun setShowReasoning(value: Boolean) {
        val previous = _uiState.value.showReasoning
        if (previous == value) return
        _uiState.update { it.copy(showReasoning = value) }
        prefs?.setShowReasoning(value)
        viewModelScope.launch {
            try {
                val display = if (value) ReasoningDisplay.SHOW else ReasoningDisplay.HIDE
                val response = client.saveReasoningDisplay(display)
                val effective = response.effectiveShowReasoning
                if (effective != value) {
                    prefs?.setShowReasoning(effective)
                    _uiState.update { it.copy(showReasoning = effective) }
                }
            } catch (e: ApiError) {
                prefs?.setShowReasoning(previous)
                _uiState.update { it.copy(showReasoning = previous) }
                onAuthError(e)
            }
        }
    }

    suspend fun loadComposerConfigNow() {
        val config = loadComposerConfig(client)
        _uiState.update { state ->
            // Do not promote catalog default into selectedModelId — that would
            // race loadNow and mask session.model (7.3). Display falls back via
            // ComposerConfig.selectedModelDisplayName → defaultModel.
            state.copy(
                composerConfig = config.copy(
                    selectedModelId = state.composerConfig.selectedModelId,
                    selectedProviderId = state.composerConfig.selectedProviderId,
                    selectedProfile = state.composerConfig.selectedProfile
                        ?: config.activeProfile,
                    selectedWorkspace = state.composerConfig.selectedWorkspace
                        ?: config.lastWorkspace,
                ),
            )
        }
    }

    fun selectModel(modelId: String?, providerId: String?) = _uiState.update {
        it.copy(
            composerConfig = it.composerConfig.copy(
                selectedModelId = modelId,
                selectedProviderId = providerId,
            ),
        )
    }

    fun selectProfile(name: String?) = _uiState.update {
        it.copy(composerConfig = it.composerConfig.copy(selectedProfile = name))
    }

    fun selectWorkspace(path: String?) = _uiState.update {
        it.copy(composerConfig = it.composerConfig.copy(selectedWorkspace = path))
    }

    /** Replaces the slash token with the picked command, keeping any argument text. */
    fun applySlashCommand(command: com.hermexapp.android.model.AgentCommand) {
        val name = command.name ?: return
        val slash = if (name.startsWith("/")) name else "/$name"
        _uiState.update { it.copy(composerText = "$slash ") }
    }

    /** Uploads picked bytes and adds the result to the pending strip. */
    suspend fun addAttachmentNow(data: ByteArray, filename: String) {
        if (data.size > PendingAttachment.MAX_UPLOAD_BYTES) {
            _uiState.update {
                it.copy(errorMessage = "$filename is too large. Attachments must be 20 MB or smaller.")
            }
            return
        }
        _uiState.update { it.copy(isUploadingAttachment = true, errorMessage = null) }
        try {
            val response = client.uploadFile(sessionId, data, filename)
            if (response.error != null || response.path.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = response.error ?: "The upload failed.")
                }
                return
            }
            // Only now, past the error and empty-path guards above: a ledger
            // that lists uploads which never landed would label a file the
            // operator never sent, which is worse than labelling nothing.
            uploadLedger?.record(
                path = response.path!!,
                filename = response.filename ?: filename,
                atMillis = nowMs(),
            )
            val attachment = PendingAttachment(
                name = response.filename ?: filename,
                path = response.path!!,
                mime = response.mime ?: "application/octet-stream",
                size = response.size ?: data.size,
                isImage = response.isImage == true,
            )
            _uiState.update { it.copy(attachments = it.attachments + attachment) }
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage) }
        } finally {
            _uiState.update { it.copy(isUploadingAttachment = false) }
        }
    }

    fun removeAttachment(attachment: PendingAttachment) = _uiState.update {
        it.copy(attachments = it.attachments - attachment)
    }

    fun send() {
        viewModelScope.launch { sendNow() }
    }
    /**
     * v0.8.15: the single entry point for the composer's primary send
     * affordance. Drafts that start with `/` are routed through the local
     * slash-command dispatcher ([SlashCommand]); everything else falls
     * through to [send] unchanged.
     */
    fun submitDraft() {
        val draft = _uiState.value.composerText.trim()
        if (draft.isEmpty() && _uiState.value.attachments.isEmpty()) {
            _uiState.update { it.copy(lastEmptySendAtMs = System.currentTimeMillis()) }
            return
        }
        when (val parsed = SlashCommand.parse(draft)) {
            null -> send()
            else -> {
                val (command, args) = parsed
                when (command) {
                    SlashCommand.STEER -> dispatchSteer(args)
                    SlashCommand.QUEUE -> dispatchQueue(args)
                    SlashCommand.INTERRUPT -> dispatchInterrupt(args)
                    SlashCommand.RENAME -> dispatchRename(args)
                    SlashCommand.STATUS -> {
                        appendStatusNotice()
                        _uiState.update { it.copy(composerText = "") }
                    }
                    SlashCommand.HELP -> {
                        appendHelpNotice()
                        _uiState.update { it.copy(composerText = "") }
                    }
                }
            }
        }
    }

    private fun dispatchSteer(text: String) {
        if (text.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Usage: /steer <message>") }
            return
        }
        if (_uiState.value.isStreaming) {
            viewModelScope.launch { steerNow(text) }
        } else {
            _uiState.update { it.copy(composerText = text) }
            send()
        }
    }

    private fun dispatchQueue(text: String) {
        if (text.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Usage: /queue <message>") }
            return
        }
        if (_uiState.value.isStreaming) {
            enqueueMessage(text, _uiState.value.attachments)
            _uiState.update { it.copy(composerText = "", attachments = emptyList()) }
        } else {
            _uiState.update { it.copy(composerText = text) }
            send()
        }
    }

    private fun dispatchInterrupt(text: String) {
        if (text.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Usage: /interrupt <message>") }
            return
        }
        if (_uiState.value.isStreaming) {
            enqueueMessage(text, _uiState.value.attachments, atFront = true)
            _uiState.update { it.copy(composerText = "", attachments = emptyList()) }
            viewModelScope.launch { stopNow() }
        } else {
            _uiState.update { it.copy(composerText = text) }
            send()
        }
    }

    private fun dispatchRename(newTitle: String) {
        if (newTitle.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Usage: /title <new title>") }
            return
        }
        viewModelScope.launch {
            try {
                repository.renameSession(sessionId, newTitle)
                _uiState.update { it.copy(title = newTitle, composerText = "") }
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    /**
     * v0.8.15: long-press send. Honors the operator's [StreamingSendBehavior]
     * preference stored in AppPrefs. Order: steer → interrupt → queue → drop.
     * Each fallback returns control to the screen via the existing
     * `errorMessage` field so the operator sees the reason.
     *
     * Called by the send-circle's long-press gesture; the regular tap still
     * uses [send] (which already handles slash commands and the
     * streaming/idle state correctly).
     */
    fun submitLongPressDraft() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty() && _uiState.value.attachments.isEmpty()) {
            _uiState.update { it.copy(lastEmptySendAtMs = System.currentTimeMillis()) }
            return
        }
        val behavior = prefs?.streamingSendBehavior?.value ?: StreamingSendBehavior.STEER
        when (behavior) {
            StreamingSendBehavior.STEER -> {
                if (_uiState.value.isStreaming) {
                    viewModelScope.launch { steerNow(text) }
                } else {
                    send()
                }
            }
            StreamingSendBehavior.INTERRUPT -> {
                if (_uiState.value.isStreaming) {
                    enqueueMessage(text, _uiState.value.attachments, atFront = true)
                    _uiState.update { it.copy(composerText = "", attachments = emptyList()) }
                    viewModelScope.launch { stopNow() }
                } else {
                    send()
                }
            }
            StreamingSendBehavior.QUEUE -> {
                if (_uiState.value.isStreaming) {
                    enqueueMessage(text, _uiState.value.attachments)
                    _uiState.update { it.copy(composerText = "", attachments = emptyList()) }
                } else {
                    send()
                }
            }
        }
    }

    /**
     * v0.8.15: buffers a follow-up message while a run is streaming — used
     * by the /queue and /interrupt slash commands and by long-press send.
     * [StreamingSendBehavior.INTERRUPT] (and /interrupt) insert at the
     * front, the run they are about to cancel; QUEUE (and /queue) append at
     * the back. Drained by [drainQueuedMessages] / [flushQueuedMessages]
     * when the run ends.
     */
    fun enqueueMessage(text: String, attachments: List<PendingAttachment>, atFront: Boolean = false) {
        val new = QueuedMessage(
            id = nextId("queued"),
            text = text,
            attachments = attachments,
        )
        _uiState.update {
            val list = if (atFront) listOf(new) + it.queuedMessages
                       else it.queuedMessages + new
            it.copy(
                queuedMessages = list,
                queuedMessagesLabel = if (list.isEmpty()) "" else "Queued for next turn (#${list.size})",
            )
        }
    }

    fun stop() {
        viewModelScope.launch { stopNow() }
    }

    suspend fun loadNow() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val (detail, fromCache) = repository.loadSession(sessionId)
            _uiState.update { state ->
                // 7.3: seed composer from host session model when user has not picked.
                val resolved = resolveSessionModelSelection(
                    sessionModel = detail?.model,
                    sessionProvider = detail?.modelProvider,
                    selectedModelId = state.composerConfig.selectedModelId,
                    selectedProviderId = state.composerConfig.selectedProviderId,
                    defaultModel = state.composerConfig.defaultModel,
                )
                state.copy(
                    title = detail?.title,
                    entries = entriesFromDetail(detail),
                    isFromCache = fromCache,
                    isLoading = false,
                    // v0.8.14: a cache-served transcript means the host was
                    // unreachable — the composer reports OFFLINE.
                    connectionState =
                        if (fromCache) JkpConnectionState.OFFLINE else JkpConnectionState.CONNECTED,
                    composerConfig = state.composerConfig.copy(
                        selectedModelId = resolved.modelId,
                        selectedProviderId = resolved.providerId,
                    ),
                )
            }
        } catch (e: ApiError) {
            onAuthError(e)
            markConnectionFailedIfTransport(e)
            _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
        }
    }

    suspend fun sendNow() {
        val state = _uiState.value
        val draft = state.composerText.trim()
        if (draft.isEmpty() && state.attachments.isEmpty()) {
            // Wave 5 Slice 5.1 — surface empty-send attempts to the user
            // instead of silently dropping. The Chat screen reads
            // [lastEmptySendAtMs] and shows a transient caption.
            _uiState.update { it.copy(lastEmptySendAtMs = System.currentTimeMillis()) }
            return
        }

        if (state.isStreaming) {
            // Compose exactly as the non-streaming path below does. Passing the
            // raw draft meant a mid-run message referenced none of its
            // attachments and left them sitting in the strip, where the next
            // send picked them up again. It also made an image-only draft a
            // dead button: steerNow returns early on empty text, so the tap did
            // nothing and explained nothing.
            steerNow(PendingAttachment.messageText(draft, state.attachments))
            if (state.attachments.isNotEmpty()) {
                _uiState.update { it.copy(attachments = emptyList()) }
            }
            return
        }

        val attachments = state.attachments
        val message = PendingAttachment.messageText(draft, attachments)
        val config = state.composerConfig

        // Remembered on send, not on success: the operator wrote it either
        // way, and a prompt worth reusing is not made less so by a transport
        // failure. Recording must never break sending.
        runCatching { sentPrompts?.record(draft, nowMs()) }

        _uiState.update {
            it.copy(
                composerText = "",
                attachments = emptyList(),
                errorMessage = null,
                // Wave 0: clear any leftover retry state at the start of a
                // fresh send so the previous banner never lingers.
                lastFailedDraft = null,
                streamRecoveryOffer = false,
                streamRecoveryTip = null,
                entries = it.entries + TimelineEntry.UserMessage(nextId("user"), message),
            )
        }

        // Reset the reconnect budget for this fresh run (13.9 / 7.4).
        reconnectAttemptCount = 0
        try {
            val response = client.startChat(
                sessionId = sessionId,
                message = message,
                workspace = config.selectedWorkspace,
                model = config.selectedModelId,
                modelProvider = config.selectedProviderId,
                profile = config.selectedProfile,
                attachments = attachments.map { it.toJsonElement() }.takeIf { it.isNotEmpty() },
                reasoningEffort = state.selectedReasoningEffort.takeIf { it != ReasoningEffort.AUTO },
            )
            val streamId = response.streamId
            if (streamId.isNullOrEmpty()) {
                _uiState.update {
                    it.copy(
                        errorMessage = response.error ?: "The server did not start a run.",
                        // Wave 0: surface a Retry affordance by stashing the
                        // pre-composer-clear text. The user message is already
                        // committed to `entries`, so re-issuing will append
                        // a second user bubble — fine, the timeline is
                        // append-only and matches the existing regen-again
                        // idiom.
                        lastFailedDraft = message,
                    )
                }
                return
            }
            activeStreamId = streamId
            // v0.8.14: a successful start means the host just answered.
            _uiState.update {
                it.copy(
                    isStreaming = true,
                    hangTip = null,
                    connectionState = JkpConnectionState.CONNECTED,
                    // v0.8.15 slice 5: a fresh run clears any stale chip so
                    // "Reconnecting…" / "Reply ready" never leak across runs.
                    streamStatusChip = null,
                    replyReadyUntilMs = 0L,
                )
            }
            startStallWatch()
            sse.start(client.chatStreamUrl(streamId), ::onSseEvent)
        } catch (e: ApiError) {
            onAuthError(e)
            markConnectionFailedIfTransport(e)
            _uiState.update {
                it.copy(
                    errorMessage = e.userMessage,
                    lastFailedDraft = message,
                )
            }
        }
    }

    /**
     * Wave 0: re-issues the draft that previously failed. Restores the
     * composer text from [UiState.lastFailedDraft], clears the retry state,
     * and calls [send] so the usual happy-path flow handles everything.
     *
     * No-op when there's nothing to retry (defensive — keeps the screen
     * safe if the button gets tapped while a send is in flight and clears
     * the saved draft).
     */
    fun retryLastSend() {
        val draft = _uiState.value.lastFailedDraft ?: return
        _uiState.update {
            it.copy(
                composerText = draft,
                lastFailedDraft = null,
                errorMessage = null,
            )
        }
        send()
    }

    /** Wave 0: clears the retry banner when the user dismisses it explicitly. */
    fun dismissRetryBanner() {
        _uiState.update { it.copy(lastFailedDraft = null, errorMessage = null) }
    }

    /** Mid-run message → `/api/chat/steer`, like the iOS composer while streaming. */
    suspend fun steerNow(text: String) {
        if (text.isEmpty()) return
        _uiState.update {
            it.copy(
                composerText = "",
                entries = it.entries + TimelineEntry.UserMessage(nextId("steer"), text),
            )
        }
        try {
            client.steerChat(sessionId, text)
        } catch (e: ApiError) {
            onAuthError(e)
            _uiState.update { it.copy(errorMessage = e.userMessage) }
        }
    }

    suspend fun stopNow() {
        val streamId = activeStreamId ?: return
        try {
            client.cancelChat(streamId)
            // The stream delivers `cancel` + `stream_end`, which finish the state.
        } catch (e: ApiError) {
            onAuthError(e)
            // Server unreachable — end the run locally rather than hang.
            sse.stop()
            finishStreaming()
            _uiState.update { it.copy(errorMessage = e.userMessage) }
        }
    }

    /**
     * v0.8.15: pop the head of the queue into the composer and send it as
     * a fresh turn. Called when the current run ends (SSE `stream_end` /
     * `cancel`). No-op when the queue is empty.
     */
    internal fun drainQueuedMessages() {
        val head = _uiState.value.queuedMessages.firstOrNull() ?: return
        _uiState.update { state ->
            state.copy(
                queuedMessages = state.queuedMessages.drop(1),
                queuedMessagesLabel = if (state.queuedMessages.size <= 1) ""
                    else "Queued for next turn (#${state.queuedMessages.size - 1})",
            )
        }
        _uiState.update { it.copy(composerText = head.text, attachments = head.attachments) }
        send()
    }

    private fun appendStatusNotice() {
        val state = _uiState.value
        val queued = state.queuedMessages.size
        val streaming = if (state.isStreaming) "Yes" else "No"
        val title = state.title ?: "(no title)"
        val line = "Session: $title · Streaming: $streaming · Queued: $queued"
        _uiState.update { it.copy(entries = it.entries + TimelineEntry.Notice(nextId("notice"), line)) }
    }

    private fun appendHelpNotice() {
        val lines = LOCAL_SLASH_COMMANDS.joinToString("\n") { "  /${it.verb} ${it.argHint} — ${it.description}" }
        val notice = "Available slash commands:\n$lines"
        _uiState.update { it.copy(entries = it.entries + TimelineEntry.Notice(nextId("notice"), notice)) }
    }

    /** Stops the SSE stream when the screen goes away. */
    fun teardown() {
        stopStallWatch()
        sse.stop()
        hangScope.cancel()
        reconnectScope.cancel()
    }

    /**
     * Wave 2 (2026-07-27) — scroll position memory.
     *
     * The chat screen passes the LazyListState's `firstVisibleItemIndex` /
     * `firstVisibleItemScrollOffset` here whenever the user leaves the
     * screen, and we hydrate them on the next visit. Storage is keyed by
     * `sessionId` so different chats don't interfere.
     */
    fun saveScrollPosition(index: Int, offset: Int) {
        val sid = sessionId ?: return
        prefs?.setScrollPosition(sid, index, offset)
    }

    /** Returns the stored `(firstVisibleItemIndex, firstVisibleItemScrollOffset)`, or `null`. */
    fun loadScrollPosition(): Pair<Int, Int>? {
        val sid = sessionId ?: return null
        return prefs?.scrollPosition(sid)
    }

    // ── SSE event application (called on OkHttp's reader thread) ──

    internal fun onSseEvent(event: SseEvent) {
        // Any real stream event counts as activity for hang-honesty timing.
        when (event) {
            SseEvent.Ignored -> Unit
            else -> if (_uiState.value.isStreaming) markStreamActivity()
        }
        when (event) {
            is SseEvent.Token -> appendToDraft(event.text)
            is SseEvent.Reasoning -> appendToReasoning(event.text)
            is SseEvent.InterimAssistant -> applyInterim(event)
            is SseEvent.ToolStarted -> upsertTool(event.tool, running = true)
            is SseEvent.ToolCompleted -> upsertTool(event.tool, running = false)
            is SseEvent.Title -> _uiState.update { it.copy(title = event.title ?: it.title) }
            is SseEvent.Done -> applyDone(event)
            is SseEvent.ApprovalPending -> applyApproval(event.payload)
            is SseEvent.ClarificationPending -> applyClarification(event.payload)
            is SseEvent.PendingSteerLeftover ->
                _uiState.update { it.copy(composerText = event.text) }
            SseEvent.StreamEnd -> {
                sse.stop()
                finishStreaming()
                drainQueuedMessages()
            }
            SseEvent.Cancelled -> {
                // Same order as StreamEnd above, and the order is the point:
                // drainQueuedMessages() goes through send(), which routes to
                // steerNow() while isStreaming is still true. Draining before
                // finishStreaming() therefore steered the queued message into
                // the run that had just been cancelled — it never became a
                // turn of its own, and its attachments were dropped on the
                // way. Skipping sse.stop() also left the run "streaming"
                // forever whenever the host closed without a stream_end.
                sse.stop()
                finishStreaming()
                _uiState.update {
                    it.copy(entries = it.entries + TimelineEntry.Notice(nextId("notice"), "Run stopped."))
                }
                drainQueuedMessages()
            }
            is SseEvent.Error -> {
                // Catalog honesty: never surface raw server error JSON to operators.
                val honest = HangHonesty.streamErrorMessage(event.message)
                failStream(honest)
                if (HangHonesty.isAuthFailureMessage(event.message)) {
                    onAuthError(ApiError.Unauthorized)
                }
            }
            is SseEvent.TransportError ->
                if (_uiState.value.isStreaming) {
                    // 0.6.0-rc3 / 7.4 / 13.9: honest stream-drop copy, not raw OkHttp.
                    // failStream runs first so the honest banner shows immediately;
                    // then best-effort reconnect re-fetches a turn that may have
                    // completed on the host while the wire was down (13.9 / 7.4).
                    failStream(
                        HangHonesty.transportFailureMessage(event.message),
                        isTransportDrop = true,
                    )
                    // v0.8.14: the drop is a transport failure — the composer
                    // stays visible but inert until the host answers again.
                    _uiState.update {
                        it.copy(
                            connectionState = JkpConnectionState.FAILED,
                            // v0.8.15 slice 5: recovery (1/2/4s backoff +
                            // session re-fetch) is about to start — tell the
                            // operator why the stream went quiet.
                            streamStatusChip = HangHonesty.RECONNECTING_CHIP,
                        )
                    }
                    reconnectScope.launch {
                        maybeAttemptReconnectRecovery(reconnectAttemptCount++)
                    }
                } else {
                    Unit
                }
            SseEvent.Ignored -> Unit
        }
    }

    private fun appendToDraft(text: String) {
        if (text.isEmpty()) return
        _uiState.update { state ->
            val entries = state.entries.toMutableList()
            val last = entries.lastOrNull()
            if (last is TimelineEntry.AssistantMessage && last.isStreaming) {
                entries[entries.lastIndex] = last.copy(text = last.text + text)
            } else {
                entries += TimelineEntry.AssistantMessage(nextId("assistant"), text, isStreaming = true)
            }
            state.copy(entries = entries)
        }
    }

    private fun appendToReasoning(text: String) {
        if (text.isEmpty()) return
        _uiState.update { state ->
            val entries = state.entries.toMutableList()
            val last = entries.lastOrNull()
            if (last is TimelineEntry.Reasoning && last.isStreaming) {
                entries[entries.lastIndex] = last.copy(text = last.text + text)
            } else {
                entries += TimelineEntry.Reasoning(nextId("reasoning"), text, isStreaming = true)
            }
            state.copy(entries = entries)
        }
    }

    /**
     * An interim assistant message closes out the current turn segment
     * (typically between tool calls). `already_streamed == true` means its text
     * is the draft we already accumulated — just finalize; otherwise it's a
     * complete message we haven't seen — append it whole.
     */
    private fun applyInterim(event: SseEvent.InterimAssistant) {
        _uiState.update { state ->
            var entries = finalizeDrafts(state.entries)
            val streamedText = event.text
            if (event.alreadyStreamed != true && !streamedText.isNullOrBlank()) {
                entries = entries + TimelineEntry.AssistantMessage(nextId("assistant"), streamedText)
            }
            state.copy(entries = entries)
        }
    }

    private fun upsertTool(tool: com.hermexapp.android.network.ToolStreamEvent, running: Boolean) {
        _uiState.update { state ->
            val entries = state.entries.toMutableList()
            val key = tool.stableId
            val index = entries.indexOfLast { entry ->
                entry is TimelineEntry.ToolCall && when {
                    key != null -> entry.id == "tool-$key"
                    else -> entry.isRunning && entry.name == tool.name
                }
            }

            val updated = TimelineEntry.ToolCall(
                id = if (key != null) "tool-$key" else nextId("tool"),
                name = tool.name,
                preview = tool.preview,
                isRunning = running,
                isError = tool.isError == true,
                durationSeconds = tool.duration,
            )

            if (index >= 0) {
                val existing = entries[index] as TimelineEntry.ToolCall
                entries[index] = updated.copy(
                    id = existing.id,
                    name = tool.name ?: existing.name,
                    preview = tool.preview ?: existing.preview,
                )
            } else {
                entries += updated
            }
            state.copy(entries = entries)
        }
    }

    /** `done` carries the authoritative session + usage — rebuild + update the context indicator. */
    private fun applyDone(event: SseEvent.Done) {
        val usage = event.usage?.let {
            try {
                ApiJson.decodeFromJsonElement(ContextWindowSnapshot.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        val session = event.session?.let {
            try {
                ApiJson.decodeFromJsonElement(SessionDetail.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }

        _uiState.update { state ->
            state.copy(
                title = session?.title ?: state.title,
                entries = if (session != null) entriesFromDetail(session) else state.entries,
                contextWindow = usage ?: state.contextWindow,
                // v0.8.15 slice 5: "Reply ready" chip for ~2s after done.
                replyReadyUntilMs = nowMs() + REPLY_READY_DURATION_MS,
            )
        }
    }

    private fun applyApproval(payload: JsonElement) {
        val response = try {
            ApiJson.decodeFromJsonElement(
                com.hermexapp.android.model.ApprovalPendingResponse.serializer(), payload,
            )
        } catch (_: Exception) {
            null
        }
        val pending = response?.pending
            ?: try { ApiJson.decodeFromJsonElement(PendingApproval.serializer(), payload) } catch (_: Exception) { null }
        if (pending != null && !pending.isEmpty) {
            // The paired phone is an authenticated operator surface. Resolve
            // dangerous-command prompts for this session instead of blocking
            // the run behind a modal that cannot be answered from Telegram-like
            // mobile flow. SESSION is deliberately narrower than ALWAYS.
            _uiState.update { it.copy(hangTip = null) }
            if (AUTO_APPROVE_MOBILE_COMMANDS) {
                viewModelScope.launch {
                    try {
                        client.respondApproval(
                            sessionId,
                            ApprovalChoice.SESSION,
                            pending.approvalId,
                        )
                    } catch (e: ApiError) {
                        onAuthError(e)
                        _uiState.update { it.copy(errorMessage = e.userMessage) }
                    }
                }
            }
        }
    }

    private fun applyClarification(payload: JsonElement) {
        val response = try {
            ApiJson.decodeFromJsonElement(
                com.hermexapp.android.model.ClarificationPendingResponse.serializer(), payload,
            )
        } catch (_: Exception) {
            null
        }
        val pending = response?.pending
            ?: try { ApiJson.decodeFromJsonElement(PendingClarification.serializer(), payload) } catch (_: Exception) { null }
        if (pending != null && !pending.isEmpty) {
            _uiState.update { it.copy(pendingClarification = pending) }
        }
    }

    /** Answers a pending clarification (free text or a picked choice), then clears the overlay. */
    fun respondToClarification(answer: String) {
        val pending = _uiState.value.pendingClarification ?: return
        _uiState.update { it.copy(pendingClarification = null) }
        viewModelScope.launch {
            try {
                client.respondClarification(sessionId, answer, pending.clarifyId)
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    /**
     * Wave 3 Slice 3.2: load a past user message into the composer so the
     * user can edit and re-send. Per the standing plan, edit-and-resend is
     * implemented as **append-as-new-turn** — we never mutate history, we
     * just pre-fill the composer. Returns the resolved text (trimmed) so the
     * UI can decide whether to open the edit dialog at all.
     */
    fun loadUserMessageIntoComposer(id: String): String? {
        val entry = _uiState.value.entries.firstOrNull { it.id == id } ?: return null
        if (entry !is TimelineEntry.UserMessage) return null
        val text = entry.text
        _uiState.update { it.copy(composerText = text, errorMessage = null) }
        return text
    }

    /** Wave 3 Slice 3.2: replace composer text (called by Edit dialog after edits). */
    fun setComposerText(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    /**
     * Wave 3 Slice 3.2: fire `sendNow()` from outside the UI thread so the
     * long-press → edit → resend flow survives composition. Guarded against
     * emptiness so a Cancel that clears the text can't accidentally send.
     */
    fun resendFromComposer() {
        viewModelScope.launch { sendNow() }
    }

    // ─── Wave 4 — in-chat find (Search) ─────────────────────────────

    /** Open the search overlay. Clears any stale query so the user starts fresh. */
    fun openSearch() {
        _uiState.update {
            it.copy(
                searchActive = true,
                searchQuery = "",
                searchMatchEntries = emptyList(),
                searchCurrentIndex = -1,
            )
        }
    }

    /** Close the search overlay and forget the query. */
    fun closeSearch() {
        _uiState.update {
            it.copy(
                searchActive = false,
                searchQuery = "",
                searchMatchEntries = emptyList(),
                searchCurrentIndex = -1,
            )
        }
    }

    /**
     * Update the search substring. Recomputes [searchMatchEntries] from the
     * current timeline. Resets the cursor to the first match (or `-1` when
     * there are no hits). Pure: no IO, no coroutine scope; safe to call on
     * every keystroke from the TextField callback.
     */
    fun setSearchQuery(query: String) {
        val entries = _uiState.value.entries
        val matches = findMatches(
            texts = entries.map { entry -> entryTextForSearch(entry) },
            query = query,
        )
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchMatchEntries = matches,
                // Cursor lands on the first hit (or -1 if no hits).
                searchCurrentIndex = if (matches.isEmpty()) -1 else 0,
            )
        }
    }

    /** Advance the cursor to the next match, wrapping at the end. */
    fun nextSearchMatch() {
        _uiState.update { state ->
            val size = state.searchMatchEntries.size
            if (size <= 0 || !state.searchActive) return@update state
            val next = ((state.searchCurrentIndex + 1) % size + size) % size
            state.copy(searchCurrentIndex = next)
        }
    }

    /** Move the cursor to the previous match, wrapping at the start. */
    fun prevSearchMatch() {
        _uiState.update { state ->
            val size = state.searchMatchEntries.size
            if (size <= 0 || !state.searchActive) return@update state
            val prev = (state.searchCurrentIndex - 1 + size) % size
            state.copy(searchCurrentIndex = prev)
        }
    }

    /**
     * Returns the timeline entry index of the *current* match, or null when
     * there is none. Used by the Chat screen to scroll the LazyColumn.
     */
    fun currentSearchEntryIndex(): Int? {
        val state = _uiState.value
        val i = state.searchCurrentIndex
        if (!state.searchActive) return null
        if (i < 0 || i >= state.searchMatchEntries.size) return null
        return state.searchMatchEntries[i]
    }

    /** Resolves an entry's plain text — used by the search indexer. */
    private fun entryTextForSearch(entry: TimelineEntry): String = when (entry) {
        is TimelineEntry.UserMessage -> entry.text
        is TimelineEntry.AssistantMessage -> entry.text
        is TimelineEntry.Reasoning -> entry.text
        is TimelineEntry.Notice -> entry.text
        is TimelineEntry.ToolCall -> entry.preview.orEmpty()
    }

    /** Pure: the entry id matching the current cursor, or null. */
    fun currentSearchEntryId(): String? {
        val idx = currentSearchEntryIndex() ?: return null
        return _uiState.value.entries.getOrNull(idx)?.id
    }

    // ─── Wave 5 — pro polish: smart auto-scroll unread pill ──────────

    /** Increment the unread counter when a new entry arrives while the user
     *  is not at the bottom. Called from the Chat screen, which owns the
     *  scroll-position snapshot. Pure side-effect on UI state. */
    fun bumpUnreadCount() {
        _uiState.update { it.copy(unreadCount = it.unreadCount + 1) }
    }

    /** Reset the unread counter when the user scrolls near the bottom or
     *  taps the JumpFab. */
    fun markSeen() {
        _uiState.update { it.copy(unreadCount = 0) }
    }

    /**
     * Wave 3 Slice 3.1: resolves a timeline entry's plain text by id so the
     * Share-as-Markdown dialog can re-read the latest snapshot of a message
     * (the dialog itself holds a stale closure). Returns null for unknown
     * or non-text entries so callers can `orEmpty()` defensively.
     */
    fun findEntryText(id: String): String? {
        val entry = _uiState.value.entries.firstOrNull { it.id == id } ?: return null
        return when (entry) {
            is TimelineEntry.UserMessage -> entry.text
            is TimelineEntry.AssistantMessage -> entry.text
            is TimelineEntry.Reasoning -> entry.text
            is TimelineEntry.Notice -> entry.text
            is TimelineEntry.ToolCall -> entry.preview
        }
    }

    /**
     * Regenerate: drop the last assistant turn via `/api/session/retry`, then
     * re-run the returned user text through the normal start/stream path
     * (mirrors the iOS regenerate flow).
     */
    fun regenerate() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            try {
                val retry = client.retrySession(sessionId)
                val text = retry.lastUserText
                if (retry.ok != true || text.isNullOrBlank()) {
                    _uiState.update { it.copy(errorMessage = retry.error ?: "Nothing to regenerate.") }
                    return@launch
                }
                // Reload the truncated transcript, then send the prior prompt again.
                loadNow()
                _uiState.update { it.copy(composerText = text) }
                sendNow()
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    private fun failStream(message: String, isTransportDrop: Boolean = false) {
        // Capture partial before finishStreaming marks drafts complete (7.4 / 13.9–13.10).
        val hadPartial = _uiState.value.entries.any { entry ->
            entry is TimelineEntry.AssistantMessage && entry.text.isNotBlank()
        }
        sse.stop()
        finishStreaming()
        val recovery = HangHonesty.streamDropRecovery(
            hadPartialAssistantText = hadPartial,
            isTransportDrop = isTransportDrop,
        )
        // keepPartial is always true by contract; finalizeDrafts already retained text.
        val err = when {
            message.isNotBlank() -> message
            else -> recovery.tip
        }
        _uiState.update {
            it.copy(
                errorMessage = err,
                streamRecoveryOffer = recovery.offerResend,
                streamRecoveryTip = if (recovery.offerResend) {
                    "Partial reply kept. Edit or resend from the composer if needed."
                } else {
                    null
                },
            )
        }
    }

    /**
     * Excellence 13.9 / 7.4 — after a transport drop, attempt to recover a turn
     * that completed on the host while the wire was down.
     *
     * Ground truth (verified against the live JKP host `api_server.py`,
     * 2026-07-21): the chat-start + SSE path CANCEL the agent run on client
     * disconnect and have NO mid-stream resume. So "reconnect" never means
     * resume-mid-token — it means re-fetch the session to recover a turn that
     * finished just as we dropped. This NEVER re-POSTs the user message (that
     * would duplicate it on the host). Auth failures are routed to onAuthError
     * by the caller and are never auto-retried here.
     *
     * Best-effort: on any failure the honest [failStream] banner already shown
     * stays in place. The pure decision (backoff 1/2/4s, never-retry on auth)
     * lives in [HangHonesty.reconnectPolicy], shared with desktop stream-
     * reconnect.ts and the PWA `reconnectPolicy`.
     *
     * `internal` + suspend so JVM unit tests can drive it deterministically via
     * `runTest` (virtual clock) without a Main dispatcher or wall-clock delays.
     */
    internal suspend fun maybeAttemptReconnectRecovery(
        attempt: Int,
        delaysS: List<Int> = HangHonesty.RECONNECT_DELAYS_S,
    ) {
        val partial = _uiState.value.entries
            .filterIsInstance<TimelineEntry.AssistantMessage>()
            .lastOrNull()
            ?.text
            .orEmpty()
            .trim()
        val policy = HangHonesty.reconnectPolicy(
            isTransportDrop = true,
            isAuthFailure = false, // auth drops go to onAuthError, never retried here
            attempt = attempt,
            delaysS = delaysS,
        )
        if (!policy.shouldReconnect) {
            // v0.8.15 slice 5: recovery budget exhausted — drop the
            // "Reconnecting…" chip so it never lingers past the last attempt.
            _uiState.update { it.copy(streamStatusChip = null) }
            return
        }
        delay(policy.delayS * 1000L)
        // Re-fetch the session (network; falls back to cache offline). Best-effort:
        // if the host is still unreachable this throws and we keep the banner.
        val recovered = try {
            repository.loadSession(sessionId)
        } catch (_: Exception) {
            null
        } ?: return
        val (recoveredDetail, fromCache) = recovered
        // v0.8.14: the re-fetch resolved the transport state — a network hit
        // means the host answered again, a cache fallback means it is still
        // unreachable.
        _uiState.update {
            it.copy(
                connectionState = if (fromCache) JkpConnectionState.OFFLINE else JkpConnectionState.CONNECTED,
                // v0.8.15 slice 5: the host answered again — reconnect is over.
                streamStatusChip = if (fromCache) it.streamStatusChip else null,
            )
        }
        val full = entriesFromDetail(recoveredDetail)
            .filterIsInstance<TimelineEntry.AssistantMessage>()
            .lastOrNull()
            ?.text
            ?.trim()
            .orEmpty()
        // Same-turn guard: only recover a completed turn that extends what we
        // already rendered (the host finished the reply just as the wire dropped).
        if (full.isNotBlank() && partial.isNotBlank() && full.startsWith(partial) && full.length > partial.length) {
            _uiState.update { state ->
                val entries = state.entries.toMutableList()
                val idx = entries.indexOfLast { it is TimelineEntry.AssistantMessage }
                if (idx >= 0) {
                    entries[idx] = TimelineEntry.AssistantMessage(entries[idx].id, full)
                }
                state.copy(
                    entries = entries,
                    errorMessage = null,
                    streamRecoveryOffer = false,
                    streamRecoveryTip = null,
                )
            }
        }
    }

    private fun finishStreaming() {
        val wasStreaming = activeStreamId != null
        activeStreamId = null
        stopStallWatch()
        _uiState.update { state ->
            state.copy(
                isStreaming = false,
                hangTip = null,
                // v0.8.15 slice 5: never let a status chip outlive the run.
                streamStatusChip = null,
                entries = finalizeDrafts(state.entries),
                finishedRunCount = state.finishedRunCount + if (wasStreaming) 1 else 0,
            )
        }
        // v0.8.15: a run just ended — send the oldest long-press queued
        // message (QUEUE semantics: auto-send when the run completes;
        // INTERRUPT semantics: the cancelled message goes out as the next turn).
        flushQueuedMessages()
    }

    /**
     * v0.8.15: promotes the oldest queued long-press draft back into the
     * composer and sends it through the normal pipeline (slash commands,
     * attachments, error handling all behave like a typed send). No-op when
     * the queue is empty, so the streaming lifecycle is untouched for
     * operators who never long-press-send.
     */
    private fun flushQueuedMessages() {
        val queued = _uiState.value.queuedMessages
        if (queued.isEmpty()) return
        val next = queued.first()
        _uiState.update { state ->
            val rest = state.queuedMessages.drop(1)
            state.copy(
                queuedMessages = rest,
                queuedMessagesLabel = if (rest.isEmpty()) "" else "Queued for next turn (#${rest.size})",
                composerText = next.text,
                attachments = next.attachments,
            )
        }
        viewModelScope.launch { sendNow() }
    }

    private fun finalizeDrafts(entries: List<TimelineEntry>): List<TimelineEntry> =
        entries.map { entry ->
            when {
                entry is TimelineEntry.AssistantMessage && entry.isStreaming ->
                    entry.copy(isStreaming = false)
                entry is TimelineEntry.Reasoning && entry.isStreaming ->
                    entry.copy(isStreaming = false)
                else -> entry
            }
        }

    private fun entriesFromDetail(detail: SessionDetail?): List<TimelineEntry> {
        val messages = detail?.chatMessages(ApiJson) ?: return emptyList()
        return messages.mapIndexedNotNull { index, message -> entryFromMessage(message, index) }
    }

    private fun entryFromMessage(message: ChatMessage, index: Int): TimelineEntry? {
        val text = message.textContent
        return when (message.role) {
            "user" ->
                // Tool-result rows are user-role with no visible text — skip them.
                if (!text.isNullOrBlank()) {
                    TimelineEntry.UserMessage("history-$index-${message.stableId}", text)
                } else {
                    null
                }
            "assistant" ->
                if (!text.isNullOrBlank()) {
                    TimelineEntry.AssistantMessage("history-$index-${message.stableId}", text)
                } else {
                    val reasoningText = message.reasoning
                    if (!reasoningText.isNullOrBlank()) {
                        TimelineEntry.Reasoning("history-$index-${message.stableId}", reasoningText)
                    } else {
                        null
                    }
                }
            else -> null
        }
    }
}
