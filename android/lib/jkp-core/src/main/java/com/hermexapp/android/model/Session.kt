package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Shapes mirror the iOS models (Session.swift), which are verified against the
// pinned upstream `api/models.py` / `api/routes.py`. Every field is nullable
// with a default (hard rule #3); the app's shared Json config additionally
// ignores unknown keys, accepts lenient primitives, and coerces nulls.

/** `GET /api/sessions`. */
@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummary>? = null,
    @SerialName("cli_count") val cliCount: Int? = null,
)

/** `GET /api/sessions/search?q=…&content=…&depth=…`. */
@Serializable
data class SessionSearchResponse(
    val sessions: List<SessionSummary>? = null,
    val query: String? = null,
    val count: Int? = null,
)

/** `GET /api/session?session_id=…` and `POST /api/session/new`. */
@Serializable
data class SessionResponse(
    val session: SessionDetail? = null,
    val error: String? = null,
)

@Serializable
data class SessionSummary(
    @SerialName("session_id") val sessionId: String? = null,
    val title: String? = null,
    val workspace: String? = null,
    val model: String? = null,
    @SerialName("model_provider") val modelProvider: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("last_message_at") val lastMessageAt: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("project_id") val projectId: String? = null,
    val profile: String? = null,
    @SerialName("active_stream_id") val activeStreamId: String? = null,
    @SerialName("is_streaming") val isStreaming: Boolean? = null,
    @SerialName("is_cli_session") val isCliSession: Boolean? = null,
    @SerialName("source_tag") val sourceTag: String? = null,
    @SerialName("session_source") val sessionSource: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    /**
     * Set on imported sessions the server refuses to mutate. Absent on many
     * rows that are still un-writable — read [isReadOnly], not this.
     */
    @SerialName("read_only") val readOnly: Boolean? = null,
) {
    /** Stable list identity mirroring the iOS `SessionSummary.id`. */
    val stableId: String
        get() = sessionId?.takeIf { it.isNotEmpty() }
            ?: "session-${title?.trim() ?: "untitled"}-${createdAt ?: updatedAt ?: lastMessageAt ?: 0.0}"

    /**
     * True when this row originates from a scheduled cron job — mirrors the
     * iOS `isCronSession` (upstream `is_cron_session` in `api/models.py`).
     */
    val isCronSession: Boolean
        get() {
            if (sessionId?.trim()?.lowercase()?.startsWith("cron_") == true) return true
            return listOf(sessionSource, sourceTag, sourceLabel)
                .mapNotNull { it?.trim()?.lowercase() }
                .contains("cron")
        }

    /**
     * True when the server will refuse to write to this session.
     *
     * **The `read_only` flag alone is not the rule.** The host guard
     * (`routes.py`, `PermissionError("read-only imported session")`) rejects
     * an explicit `read_only` *and*, separately, any messaging-source record —
     * agent rows normalise messaging sources without ever setting the flag. On
     * the live host the operator's own telegram session has `read_only: null`
     * with `session_source: "messaging"`, so a client testing only the flag
     * still offers a composer the server refuses.
     *
     * Measured against the live host: 44 of 78 sessions carry the flag (every
     * `claude_code` and `subagent` import), and the union with messaging is
     * 45 — well over half the list.
     */
    val isReadOnly: Boolean
        get() = readOnly == true || sessionSource?.trim()?.lowercase() == "messaging"
}

@Serializable
data class SessionDetail(
    @SerialName("session_id") val sessionId: String? = null,
    val title: String? = null,
    val workspace: String? = null,
    val model: String? = null,
    @SerialName("model_provider") val modelProvider: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("last_message_at") val lastMessageAt: Double? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("project_id") val projectId: String? = null,
    val profile: String? = null,
    @SerialName("active_stream_id") val activeStreamId: String? = null,
    @SerialName("pending_user_message") val pendingUserMessage: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("is_cli_session") val isCliSession: Boolean? = null,
    // Raw elements so one malformed message never throws away the whole
    // transcript — mirrors the iOS `decodeMessagesTolerantly`.
    val messages: List<JsonElement>? = null,
    @SerialName("_messages_truncated") val messagesTruncated: Boolean? = null,
    @SerialName("_messages_offset") val messagesOffset: Int? = null,
) {
    /** Per-element tolerant decode of the transcript. */
    fun chatMessages(json: Json): List<ChatMessage> =
        messages.orEmpty().mapNotNull { element ->
            try {
                json.decodeFromJsonElement(ChatMessage.serializer(), element)
            } catch (_: Exception) {
                null
            }
        }
}

/** `GET /api/session/status?session_id=…`. */
@Serializable
data class SessionStatusResponse(
    @SerialName("session_id") val sessionId: String? = null,
    /**
     * Absolute workspace root, e.g. `C:\Users\me\workspace`.
     *
     * The session is the only dependable source for it: the deployed
     * `/api/list` sends no `workspace` (see [DirectoryListResponse]) while
     * `/api/media` requires an absolute path. Confirmed present here on the
     * live host for both WebUI-native and CLI-backed sessions.
     */
    val workspace: String? = null,
    @SerialName("active_stream_id") val activeStreamId: String? = null,
    @SerialName("is_streaming") val isStreaming: Boolean? = null,
    @SerialName("pending_user_message") val pendingUserMessage: String? = null,
    val error: String? = null,
)
