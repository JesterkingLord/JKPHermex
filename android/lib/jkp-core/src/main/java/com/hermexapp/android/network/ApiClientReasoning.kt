package com.hermexapp.android.network

import com.hermexapp.android.model.ReasoningDisplayRequest
import com.hermexapp.android.model.ReasoningEffort
import com.hermexapp.android.model.ReasoningEffortRequest
import com.hermexapp.android.model.ReasoningStatus
import kotlinx.serialization.encodeToString

/**
 * `/api/reasoning` — gateway endpoint that manages the agent's reasoning
 * effort and the timeline's reasoning-display toggle.
 *
 * Mirrors the iOS `APIClient+ServerPanels.swift` (saveReasoningEffort /
 * saveReasoningDisplay). The server round-trips the *effective* state,
 * so callers can persist the response instead of their request value
 * (handles "unsupported by current model" → server clamps to nearest
 * supported effort).
 *
 * The [ReasoningEffort.AUTO] value is **local-only**; sending the
 * literal string "auto" would 400 on the server. We omit the field
 * entirely in that case (and the server picks the default `medium`).
 */

suspend fun ApiClient.reasoning(): ReasoningStatus =
    getJson(Endpoint.REASONING, emptyMap())

suspend fun ApiClient.saveReasoningEffort(effort: ReasoningEffort): ReasoningStatus {
    val wire = effort.wireValue
        ?: return reasoning() // AUTO → just re-read, no body needed
    return postJson(
        Endpoint.REASONING,
        ApiJson.encodeToString(ReasoningEffortRequest(effort = wire)),
    )
}

/**
 * `display` is "show" or "hide" on the wire (the iOS side also accepts
 * "on"/"off" and normalises; we send the canonical short form to match
 * what the desktop CLI uses).
 */
suspend fun ApiClient.saveReasoningDisplay(display: ReasoningDisplay): ReasoningStatus =
    postJson(
        Endpoint.REASONING,
        ApiJson.encodeToString(ReasoningDisplayRequest(display = display.wireValue)),
    )

/** Show/hide the model's reasoning blocks in the timeline. */
enum class ReasoningDisplay(val wireValue: String) {
    SHOW("show"),
    HIDE("hide");

    companion object {
        /** Server may return either form; this normalises. */
        fun fromServer(value: String?): ReasoningDisplay? = when (value?.lowercase()) {
            "show", "on" -> SHOW
            "hide", "off" -> HIDE
            else -> null
        }
    }
}
