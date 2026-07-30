package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Approval / clarification / context-window shapes mirroring the iOS
// Approval.swift, Clarification.swift, and ContextWindowSnapshot.swift models.
// Verified against the pinned upstream approval/clarify handlers.

@Serializable
data class ApprovalPendingResponse(
    val pending: PendingApproval? = null,
    @SerialName("pending_count") val pendingCount: Int? = null,
)

@Serializable
data class PendingApproval(
    @SerialName("approval_id") val approvalId: String? = null,
    val command: String? = null,
    val description: String? = null,
    @SerialName("pattern_key") val patternKey: String? = null,
    @SerialName("pattern_keys") val patternKeys: List<String>? = null,
) {
    val displayPatternKeys: List<String>
        get() {
            val keys = patternKeys.orEmpty().filter { it.isNotBlank() }
            if (keys.isNotEmpty()) return keys
            return patternKey?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        }

    val isEmpty: Boolean
        get() = approvalId == null && command == null && description == null &&
            patternKey == null && patternKeys.isNullOrEmpty()
}

/** The four approval choices, matching the upstream `choice` vocabulary. */
enum class ApprovalChoice(val wire: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny"),
}

@Serializable
data class ApprovalRespondResponse(
    val ok: Boolean? = null,
    val choice: String? = null,
    val error: String? = null,
)

@Serializable
data class ClarificationPendingResponse(
    val pending: PendingClarification? = null,
    @SerialName("pending_count") val pendingCount: Int? = null,
)

@Serializable
data class PendingClarification(
    @SerialName("clarify_id") val clarifyId: String? = null,
    val question: String? = null,
    @SerialName("choices_offered") val choicesOffered: List<String>? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val kind: String? = null,
) {
    val displayChoices: List<String>
        get() = choicesOffered.orEmpty().mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }

    val displayQuestion: String
        get() = question?.trim()?.takeIf { it.isNotEmpty() }
            ?: "The agent needs more information before continuing."

    val isEmpty: Boolean
        get() = clarifyId == null && question == null && choicesOffered.isNullOrEmpty() &&
            sessionId == null && kind == null
}

@Serializable
data class ClarificationRespondResponse(
    val ok: Boolean? = null,
    val response: String? = null,
    val error: String? = null,
)

/** `usage` block on the `done` SSE event — mirrors iOS `ContextWindowSnapshot`. */
@Serializable
data class ContextWindowSnapshot(
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("threshold_tokens") val thresholdTokens: Int? = null,
    @SerialName("last_prompt_tokens") val lastPromptTokens: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("estimated_cost") val estimatedCost: Double? = null,
) {
    val tokensUsed: Int? get() = lastPromptTokens ?: inputTokens

    val percentage: Double?
        get() {
            val used = tokensUsed ?: return null
            val total = contextLength ?: return null
            if (total <= 0) return null
            return used.toDouble() / total.toDouble()
        }

    /** Compact "42% context" indicator, matching iOS `ContextWindowFormatter`. */
    val compactIndicator: String?
        get() = percentage?.let { "${(it * 100).toInt()}% context" }
}

/** `POST /api/session/retry` — removes the last assistant turn for regeneration. */
@Serializable
data class SessionRetryResponse(
    val ok: Boolean? = null,
    @SerialName("last_user_text") val lastUserText: String? = null,
    @SerialName("removed_count") val removedCount: Int? = null,
    val error: String? = null,
)

/** `POST /api/transcribe` (server speech-to-text). */
@Serializable
data class TranscribeResponse(
    val ok: Boolean? = null,
    val transcript: String? = null,
    val error: String? = null,
)

// ── Git mutation responses (mirror iOS GitWorkspace.swift) ──

@Serializable
data class GitMutationResponse(
    val ok: Boolean? = null,
    val git: GitStatus? = null,
    val error: String? = null,
)

@Serializable
data class GitCommitResponse(
    val ok: Boolean? = null,
    val commit: String? = null,
    val paths: List<String>? = null,
    val status: GitStatus? = null,
    val git: GitStatus? = null,
    val error: String? = null,
) {
    val resolvedStatus: GitStatus? get() = status ?: git
    val shortSha: String? get() = commit?.trim()?.take(8)
}

@Serializable
data class GitRemoteActionResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val git: GitStatus? = null,
    val error: String? = null,
)

@Serializable
data class GitCheckoutResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val status: GitStatus? = null,
    val git: GitStatus? = null,
    val branches: GitBranches? = null,
    @SerialName("current_branch") val currentBranch: String? = null,
    val error: String? = null,
) {
    val resolvedStatus: GitStatus? get() = status ?: git
}
