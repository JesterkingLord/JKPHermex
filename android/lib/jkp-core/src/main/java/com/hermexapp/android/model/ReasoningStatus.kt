package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server's view of reasoning-effort state, mirrored from the iOS
 * `ReasoningStatusResponse` and the desktop `hermes_constants.VALID_REASONING_EFFORTS`.
 *
 * Valid efforts per the gateway (`hermes_constants.parse_reasoning_effort`):
 *   "none"     → reasoning disabled (`{"enabled": false}`)
 *   "minimal"  → {enabled: true, effort: "minimal"}
 *   "low"      → {enabled: true, effort: "low"}
 *   "medium"   → {enabled: true, effort: "medium"}  (server default)
 *   "high"     → {enabled: true, effort: "high"}
 *   "xhigh"    → {enabled: true, effort: "xhigh"}  (extended-thinking models only)
 *
 * Any other value is treated as the server default (`medium`).
 *
 * On the client we add one more value, [ReasoningEffort.AUTO], which means
 * "let the server pick" — this maps to the server default and is the
 * recommended default for new users. AUTO is local-only and never sent
 * as a literal `"auto"` string to the server.
 */
enum class ReasoningEffort(val wireValue: String?, val displayName: String) {
    AUTO(wireValue = null, displayName = "Auto"),
    NONE(wireValue = "none", displayName = "Off"),
    MINIMAL(wireValue = "minimal", displayName = "Minimal"),
    LOW(wireValue = "low", displayName = "Low"),
    MEDIUM(wireValue = "medium", displayName = "Medium"),
    HIGH(wireValue = "high", displayName = "High"),
    XHIGH(wireValue = "xhigh", displayName = "Extra high");

    companion object {
        /**
         * Parse a value coming back from the server. The server echoes back
         * the effective effort (or `null` when none is set, meaning "default").
         * Unknown values fall back to [AUTO] so a future server addition
         * (or a typo) never crashes the app.
         */
        fun fromServer(value: String?): ReasoningEffort {
            if (value.isNullOrBlank()) return AUTO
            val normalised = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalised } ?: AUTO
        }
    }
}

/**
 * `GET  /api/reasoning`           → current server-side state
 * `POST /api/reasoning { effort }` → set effort for the active session
 * `POST /api/reasoning { display}` → toggle show/hide reasoning in the timeline
 *
 * Mirrors the iOS `ReasoningStatusResponse` exactly (both fields are
 * accepted because the desktop gateway has shipped both names at
 * different points — server returns whichever the client understands).
 */
@Serializable
data class ReasoningStatus(
    val ok: Boolean? = null,
    @SerialName("show_reasoning") val showReasoning: Boolean? = null,
    @SerialName("showReasoning") val showReasoningCamel: Boolean? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("reasoningEffort") val reasoningEffortCamel: String? = null,
    val effort: String? = null,
    val display: String? = null,
    val error: String? = null,
) {
    /** True when the server told us to show reasoning blocks in the timeline. */
    val effectiveShowReasoning: Boolean
        get() = showReasoning ?: showReasoningCamel ?: true

    /** The server-reported effort as one of our typed values; null when unset. */
    val effectiveEffort: ReasoningEffort
        get() = ReasoningEffort.fromServer(reasoningEffort ?: reasoningEffortCamel ?: effort)
}

/** Wire body for `POST /api/reasoning` effort changes. */
@Serializable
data class ReasoningEffortRequest(
    val effort: String,
)

/** Wire body for `POST /api/reasoning` display-toggle changes. */
@Serializable
data class ReasoningDisplayRequest(
    val display: String,
)
