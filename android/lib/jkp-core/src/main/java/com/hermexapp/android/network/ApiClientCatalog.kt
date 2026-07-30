package com.hermexapp.android.network

import com.hermexapp.android.model.CommandsResponse
import com.hermexapp.android.model.DefaultModelResponse
import com.hermexapp.android.model.ModelsResponse
import com.hermexapp.android.model.ProfileSwitchResponse
import com.hermexapp.android.model.ProfilesResponse
import com.hermexapp.android.model.SettingsResponse
import com.hermexapp.android.model.UploadResponse
import com.hermexapp.android.model.WorkspacesResponse
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Catalog + composer-config endpoints, mirroring iOS `APIClient+ServerPanels`.

suspend fun ApiClient.models(): ModelsResponse = getJson(Endpoint.MODELS)

suspend fun ApiClient.commands(): CommandsResponse = getJson(Endpoint.COMMANDS)

suspend fun ApiClient.profiles(): ProfilesResponse = getJson(Endpoint.PROFILES)

suspend fun ApiClient.switchProfile(name: String): ProfileSwitchResponse =
    postJson(Endpoint.PROFILE_SWITCH, ApiJson.encodeToString(ProfileSwitchRequest(name)))

suspend fun ApiClient.workspaces(): WorkspacesResponse = getJson(Endpoint.WORKSPACES)

suspend fun ApiClient.defaultModel(): DefaultModelResponse = getJson(Endpoint.DEFAULT_MODEL)

suspend fun ApiClient.saveDefaultModel(model: String): DefaultModelResponse =
    postJson(Endpoint.DEFAULT_MODEL, ApiJson.encodeToString(DefaultModelRequest(model)))

suspend fun ApiClient.serverSettings(): SettingsResponse = getJson(Endpoint.SETTINGS)

/**
 * `POST /api/upload` — multipart with a `session_id` text field and a `file`
 * part, byte-compatible with the iOS `MultipartFormData` encoding (the file
 * part is sent as `application/octet-stream`; the server sniffs the type).
 */
suspend fun ApiClient.uploadFile(
    sessionId: String,
    data: ByteArray,
    filename: String,
): UploadResponse {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("session_id", sessionId)
        .addFormDataPart(
            "file",
            filename,
            data.toRequestBody("application/octet-stream".toMediaType()),
        )
        .build()

    val request = Request.Builder()
        .url(url(Endpoint.UPLOAD))
        .header("Accept", "application/json")
        .post(body)
        .build()

    val responseBody = withContext(ioDispatcher) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            when {
                it.code == 401 -> throw ApiError.Unauthorized
                it.code !in 200..299 -> throw ApiError.Http(it.code, text.ifEmpty { null })
                else -> text
            }
        }
    }
    return decode(responseBody)
}

@Serializable
private data class ProfileSwitchRequest(val name: String)

@Serializable
private data class DefaultModelRequest(val model: String)

@Serializable
internal data class SessionIdBody(@SerialName("session_id") val sessionId: String)
