package com.hermexapp.android.network

import com.hermexapp.android.model.AuthStatusResponse
import com.hermexapp.android.model.HealthResponse
import com.hermexapp.android.model.LoginResponse
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The app's one tolerant Json configuration (hard rule #3): unknown keys are
 * ignored, lenient primitives are accepted (numbers-as-strings and vice
 * versa), explicit nulls fall back to field defaults, and nulls are omitted
 * when encoding (matching how the iOS encoder omits nil optionals).
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * The Android counterpart of the iOS `APIClient` actor: one instance per server
 * base URL, JSON in/out, tolerant decoding, and the same error mapping
 * (401 → [ApiError.Unauthorized], other non-2xx → [ApiError.Http], transport →
 * [ApiError.Network], parse → [ApiError.Decoding]).
 *
 * Endpoint families live in extension files mirroring the iOS split
 * (`ApiClientSessions.kt`, `ApiClientChat.kt`), built on [getJson]/[postJson].
 * Auth: session cookie via [SessionCookieJar]; JKP device grant via
 * [BearerAuthInterceptor] on the shared OkHttpClient (Hermex 0.6).
 */
class ApiClient(
    val baseUrl: HttpUrl,
    @PublishedApi internal val httpClient: OkHttpClient,
    @PublishedApi internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        // Defense in depth behind ServerUrlNormalizer: no client may exist for a
        // cleartext URL outside the allowed set (Android port plan §2).
        if (baseUrl.scheme == "http" && !CleartextPolicy.allowsCleartext(baseUrl.host)) {
            throw ApiError.CleartextNotAllowed(baseUrl.host)
        }
    }

    @PublishedApi internal val json: Json = ApiJson
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): HealthResponse = getJson(Endpoint.HEALTH)

    suspend fun authStatus(): AuthStatusResponse = getJson(Endpoint.AUTH_STATUS)

    suspend fun login(password: String): LoginResponse =
        postJson(Endpoint.LOGIN, json.encodeToString(LoginRequest(password)))

    suspend fun logout(): LoginResponse = postJson(Endpoint.LOGOUT, "{}")

    /** Builds an endpoint URL with query parameters, e.g. for the SSE stream. */
    fun url(endpoint: Endpoint, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder().encodedPath(endpoint.path)
        for ((name, value) in query) builder.addQueryParameter(name, value)
        return builder.build()
    }

    suspend inline fun <reified T> getJson(
        endpoint: Endpoint,
        query: Map<String, String> = emptyMap(),
    ): T = decode(executeGet(endpoint, query))

    suspend inline fun <reified T> postJson(endpoint: Endpoint, body: String): T =
        decode(executePost(endpoint, body))

    @PublishedApi
    internal suspend fun executeGet(endpoint: Endpoint, query: Map<String, String>): String =
        execute(requestBuilder(endpoint, query).get().build())

    /**
     * GETs raw bytes, for endpoints that answer with a file rather than JSON.
     *
     * Separate from [execute] because that one calls `body.string()`, which
     * decodes as text and would corrupt any image it touched. Error mapping is
     * kept identical so callers handle failures the same way everywhere.
     *
     * [maxBytes] is a hard ceiling on what will be held in memory, and it is
     * not optional caution: `/api/media` has **no size limit of its own**. Where
     * `/api/file` refuses at 400,000 bytes with "File too large", `/api/media`
     * served a 3 MB file in full when probed, and would serve a 500 MB one the
     * same way — straight into a single `ByteArray`.
     *
     * `Content-Length` is checked before the body is touched, so an oversized
     * response costs nothing but the headers. The server sends it on every
     * media response; when it is missing the read is capped anyway, because a
     * chunked response with no declared length is exactly where a guard that
     * trusted the header would let everything through. `HEAD` cannot be used to
     * ask in advance — the host answers it with 501.
     */
    suspend fun getBytes(
        endpoint: Endpoint,
        query: Map<String, String> = emptyMap(),
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    ): ByteArray = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(url(endpoint, query))
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }

        response.use {
            if (it.code == 401) throw ApiError.Unauthorized
            if (it.code !in 200..299) throw ApiError.Http(it.code, null)

            val body = it.body ?: return@use ByteArray(0)
            val declared = body.contentLength()
            if (declared > maxBytes) throw ApiError.TooLarge(declared, maxBytes)

            try {
                val source = body.source()
                // Buffer at most one byte past the ceiling. `request` stops at
                // end of stream instead of throwing, which `readByteArray(n)`
                // would do whenever the body is shorter than n — the ordinary
                // case when no Content-Length was sent.
                source.request(maxBytes + 1)
                val buffered = source.buffer.size
                if (buffered > maxBytes) throw ApiError.TooLarge(declared, maxBytes)
                source.readByteArray(buffered)
            } catch (e: IOException) {
                throw ApiError.Network(e)
            }
        }
    }

    @PublishedApi
    internal suspend fun executePost(endpoint: Endpoint, body: String): String =
        execute(requestBuilder(endpoint, emptyMap()).post(body.toRequestBody(jsonMediaType)).build())

    private fun requestBuilder(endpoint: Endpoint, query: Map<String, String>): Request.Builder =
        Request.Builder()
            .url(url(endpoint, query))
            .header("Accept", "application/json")
            // Mirror the iOS `reloadIgnoringLocalCacheData`: control-plane calls
            // must always hit the server.
            .header("Cache-Control", "no-cache")

    private suspend fun execute(request: Request): String = withContext(ioDispatcher) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }

        response.use {
            val bodyText = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ApiError.Network(e)
            }

            when {
                it.code == 401 -> throw ApiError.Unauthorized
                it.code !in 200..299 -> throw ApiError.Http(it.code, bodyText.ifEmpty { null })
                else -> bodyText
            }
        }
    }

    @PublishedApi
    internal inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw ApiError.Decoding(e)
    } catch (e: IllegalArgumentException) {
        throw ApiError.Decoding(e)
    }

    @Serializable
    private data class LoginRequest(val password: String)

    companion object {
        /**
         * Ceiling for a single in-memory file download, 25 MB.
         *
         * Chosen against what the workspace actually holds: phone screenshots
         * and ordinary renders sit far below it, so nothing everyday is
         * refused, while the multi-hundred-megabyte outputs a session can
         * produce are stopped before they become one allocation on a device
         * with far less headroom than the machine that wrote them.
         *
         * A limit is needed at all because `/api/media` enforces none of its
         * own — unlike `/api/file`, which stops at 400,000 bytes.
         */
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 25L * 1024 * 1024
    }
}
