package com.hermexapp.android.network

import com.hermexapp.android.model.ApprovalChoice
import com.hermexapp.android.model.ApprovalPendingResponse
import com.hermexapp.android.model.ApprovalRespondResponse
import com.hermexapp.android.model.ClarificationPendingResponse
import com.hermexapp.android.model.ClarificationRespondResponse
import com.hermexapp.android.model.SessionRetryResponse
import com.hermexapp.android.model.TranscribeResponse
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Approval / clarification / retry / transcribe endpoints, mirroring the iOS
// APIClient+Chat and APIClient+Transcribe extensions.

suspend fun ApiClient.approvalPending(sessionId: String): ApprovalPendingResponse =
    getJson(Endpoint.APPROVAL_PENDING, mapOf("session_id" to sessionId))

fun ApiClient.approvalStreamUrl(sessionId: String): HttpUrl =
    url(Endpoint.APPROVAL_STREAM, mapOf("session_id" to sessionId))

suspend fun ApiClient.respondApproval(
    sessionId: String,
    choice: ApprovalChoice,
    approvalId: String?,
): ApprovalRespondResponse = postJson(
    Endpoint.APPROVAL_RESPOND,
    ApiJson.encodeToString(ApprovalRespondRequest(sessionId, choice.wire, approvalId)),
)

suspend fun ApiClient.clarifyPending(sessionId: String): ClarificationPendingResponse =
    getJson(Endpoint.CLARIFY_PENDING, mapOf("session_id" to sessionId))

fun ApiClient.clarifyStreamUrl(sessionId: String): HttpUrl =
    url(Endpoint.CLARIFY_STREAM, mapOf("session_id" to sessionId))

suspend fun ApiClient.respondClarification(
    sessionId: String,
    response: String,
    clarifyId: String?,
): ClarificationRespondResponse = postJson(
    Endpoint.CLARIFY_RESPOND,
    ApiJson.encodeToString(ClarificationRespondRequest(sessionId, response, clarifyId)),
)

/** Removes the last assistant turn so the same user prompt can be re-run. */
suspend fun ApiClient.retrySession(id: String): SessionRetryResponse =
    postJson(Endpoint.SESSION_RETRY, ApiJson.encodeToString(SessionIdBody(id)))

/**
 * Multipart speech-to-text: only a `file` field (no session_id), mirroring the
 * iOS `transcribeAudio`. The server returns `{error}` even for non-2xx, so decode
 * the body regardless of status and let the caller inspect `.error`.
 */
suspend fun ApiClient.transcribeAudio(data: ByteArray, filename: String): TranscribeResponse {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            filename,
            data.toRequestBody("application/octet-stream".toMediaType()),
        )
        .build()

    val request = Request.Builder()
        .url(url(Endpoint.TRANSCRIBE))
        .header("Accept", "application/json")
        .post(body)
        .build()

    return withContext(ioDispatcher) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        response.use {
            if (it.code == 401) throw ApiError.Unauthorized
            val text = it.body?.string().orEmpty()
            runCatching { decode<TranscribeResponse>(text) }.getOrElse {
                if (response.code !in 200..299) {
                    throw ApiError.Http(response.code, text.ifEmpty { null })
                }
                throw ApiError.Decoding(IllegalStateException("Unexpected transcribe body"))
            }
        }
    }
}

@Serializable
private data class ApprovalRespondRequest(
    @SerialName("session_id") val sessionId: String,
    val choice: String,
    @SerialName("approval_id") val approvalId: String?,
)

@Serializable
private data class ClarificationRespondRequest(
    @SerialName("session_id") val sessionId: String,
    val response: String,
    @SerialName("clarify_id") val clarifyId: String?,
)
