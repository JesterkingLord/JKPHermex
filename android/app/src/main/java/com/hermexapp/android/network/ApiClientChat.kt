package com.hermexapp.android.network

import com.hermexapp.android.model.ChatCancelResponse
import com.hermexapp.android.model.ChatStartResponse
import com.hermexapp.android.model.ChatSteerResponse
import com.hermexapp.android.model.ReasoningEffort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl

// Chat endpoints, mirroring the iOS `APIClient+Chat`.

/**
 * Per-message override for the server-side reasoning effort. When the
 * user has picked a non-AUTO effort in the composer, we send it on
 * `/api/chat/start` so the server can route the run to the matching
 * model + parameters. The setting is also persisted on the server
 * (see `POST /api/reasoning`) so the next message inherits it; we
 * keep the per-message value here so a "Medium" picker can override
 * a previously-persisted "High" until the user explicitly changes
 * it.
 */
suspend fun ApiClient.startChat(
    sessionId: String,
    message: String,
    workspace: String? = null,
    model: String? = null,
    modelProvider: String? = null,
    profile: String? = null,
    attachments: List<JsonElement>? = null,
    reasoningEffort: ReasoningEffort? = null,
): ChatStartResponse = postJson(
    Endpoint.CHAT_START,
    ApiJson.encodeToString(
        ChatStartRequest(
            sessionId,
            message,
            workspace,
            model,
            modelProvider,
            profile,
            attachments,
            reasoningEffort = reasoningEffort?.wireValue,
        ),
    ),
)

fun ApiClient.chatStreamUrl(streamId: String): HttpUrl =
    url(Endpoint.CHAT_STREAM, mapOf("stream_id" to streamId))

suspend fun ApiClient.cancelChat(streamId: String): ChatCancelResponse =
    getJson(Endpoint.CHAT_CANCEL, mapOf("stream_id" to streamId))

suspend fun ApiClient.steerChat(sessionId: String, text: String): ChatSteerResponse =
    postJson(
        Endpoint.CHAT_STEER,
        ApiJson.encodeToString(ChatSteerRequest(sessionId, text)),
    )

@Serializable
internal data class ChatStartRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
    val workspace: String? = null,
    val model: String? = null,
    @SerialName("model_provider") val modelProvider: String? = null,
    val profile: String? = null,
    val attachments: List<JsonElement>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
private data class ChatSteerRequest(
    @SerialName("session_id") val sessionId: String,
    val text: String,
)
