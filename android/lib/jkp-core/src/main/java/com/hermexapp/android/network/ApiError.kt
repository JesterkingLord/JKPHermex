package com.hermexapp.android.network

/**
 * Error taxonomy mirroring the iOS `APIError`: one case per failure family so
 * UI code can branch (session expiry vs. unreachable vs. server bug) without
 * string matching. `userMessage` mirrors the iOS copy for the states the
 * onboarding flow surfaces.
 */
sealed class ApiError : Exception() {

    object InvalidServerUrl : ApiError() {
        private fun readResolve(): Any = InvalidServerUrl
    }

    /** Plain-HTTP URL to a host outside the allowed set (Tailscale CGNAT, localhost). */
    data class CleartextNotAllowed(val host: String) : ApiError()

    data class Network(override val cause: Throwable) : ApiError()

    data class Http(val statusCode: Int, val body: String?) : ApiError()

    data class Decoding(override val cause: Throwable) : ApiError()

    object Unauthorized : ApiError() {
        private fun readResolve(): Any = Unauthorized
    }

    /**
     * A file body larger than the caller will hold in memory.
     *
     * Its own case rather than a [Network] failure, because it is not one: the
     * server did nothing wrong and a retry fails identically. The UI has to say
     * the file is too big, not offer to try again.
     *
     * [declaredBytes] is the server's `Content-Length`, or -1 when it sent none
     * and the ceiling was reached while reading.
     */
    data class TooLarge(val declaredBytes: Long, val maxBytes: Long) : ApiError()

    val userMessage: String
        get() = when (this) {
            is InvalidServerUrl ->
                "Enter a valid server URL, for example https://hermes.yourdomain.com or http://<server-tailscale-ip>:8787."
            is CleartextNotAllowed ->
                "Plain http:// is only allowed for Tailscale addresses (100.64.x.x–100.127.x.x) and localhost. Use https:// for $host."
            is Network ->
                // Prefer shared JKP catalog so APK / PWA / host use the same network copy.
                ClientErrorCatalog.NETWORK_UNREACHABLE.message
            is Unauthorized ->
                ClientErrorCatalog.INVALID_DEVICE_GRANT.message
            is Decoding ->
                "The server response could not be read. Check that the URL points to a Hermes Web UI server."
            is TooLarge -> {
                val mb = maxBytes.toDouble() / (1024 * 1024)
                if (declaredBytes >= 0) {
                    val actual = declaredBytes.toDouble() / (1024 * 1024)
                    "This file is %.1f MB, over the %.0f MB preview limit.".format(actual, mb)
                } else {
                    "This file is over the %.0f MB preview limit.".format(mb)
                }
            }
            is Http -> {
                // Prefer host-aligned catalog when status/body map cleanly; keep
                // a few endpoint-not-found strings that are more specific than
                // the generic server_error entry.
                val catalogMsg = ClientErrorCatalog.userMessage(
                    status = statusCode,
                    message = body,
                )
                // Read once into a local: a custom getter is not smart-cast, so
                // a null check on the property alone would not compile.
                val reason = serverReason
                when {
                    // A 404 that explains itself is worth more than our guess.
                    // The host answers "Session not found" or "File not found"
                    // in the body; telling the user to check their server URL
                    // instead sends them to fix a setting that is already
                    // correct. Only fall back to that advice when the body says
                    // nothing — which is what a genuinely wrong host returns.
                    statusCode == 404 && reason != null -> reason
                    statusCode == 404 ->
                        "The server endpoint was not found. Check that the URL points to a Hermes Web UI server."
                    statusCode == 408 ->
                        "The server took too long to respond. Check that the machine is awake and the server is running."
                    catalogMsg != ClientErrorCatalog.UNKNOWN.message -> catalogMsg
                    statusCode == 403 ->
                        "The server refused access. Check the server password and permissions."
                    else -> "The server returned an unexpected response (HTTP $statusCode)."
                }
            }
        }

    /**
     * The server's own explanation, when it sent one.
     *
     * The host replies to a failed read with `{"error": "Session not found"}`.
     * Parsed by hand rather than with a serializer because a body that is not
     * JSON at all — an HTML error page from a proxy, say — must yield null and
     * not an exception; this runs while an error is already being reported.
     *
     * Anything long or multi-line is rejected: a stack trace or an HTML page
     * that happens to contain the word is not a sentence to show a user.
     */
    private val serverReason: String?
        get() {
            val raw = (this as? Http)?.body?.trim() ?: return null
            if (!raw.startsWith("{")) return null
            val match = SERVER_ERROR_FIELD.find(raw) ?: return null
            val reason = match.groupValues[1].trim()
            // A newline arrives inside JSON as the two characters \ and n, so
            // checking for a real '\n' here would never match — which is how a
            // multi-line traceback slipped through the first version of this.
            return reason.takeIf {
                it.isNotEmpty() && it.length <= 160 && !it.contains('\n') && !it.contains("\\n")
            }
        }

    override val message: String get() = userMessage

    private companion object {
        /** `"error": "…"`, tolerating whitespace and escaped quotes. */
        val SERVER_ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
