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
            is Http -> {
                // Prefer host-aligned catalog when status/body map cleanly; keep
                // a few endpoint-not-found strings that are more specific than
                // the generic server_error entry.
                val catalogMsg = ClientErrorCatalog.userMessage(
                    status = statusCode,
                    message = body,
                )
                when {
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

    override val message: String get() = userMessage
}
