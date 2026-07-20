package com.hermexapp.android.network

/**
 * Shared client-facing error vocabulary aligned with JKP host
 * `jkp.client_errors` / `GET /v1/client-errors`.
 *
 * Pure helpers so unit tests can lock the codes without Compose or OkHttp.
 * UI and [ApiError.userMessage] should prefer these strings so Hermex, PWA,
 * and Telegram stay honest about the same failure families.
 */
object ClientErrorCatalog {

    data class Entry(
        val code: String,
        val message: String,
        val httpStatus: Int,
        val category: String,
    )

    val INVALID_DEVICE_GRANT = Entry(
        code = "invalid_device_grant",
        message = "This phone link is no longer authorized. Link it again from your JKP host.",
        httpStatus = 401,
        category = "auth",
    )

    /** Same product copy as invalid_device_grant — host catalog dual-code for 401 key errors. */
    val INVALID_API_KEY = Entry(
        code = "invalid_api_key",
        message = "This phone link is no longer authorized. Link it again from your JKP host.",
        httpStatus = 401,
        category = "auth",
    )

    val ADMIN_KEY_REQUIRED = Entry(
        code = "admin_key_required",
        message = "This action needs the host API key, not a phone link.",
        httpStatus = 401,
        category = "auth",
    )

    val BILLING_QUOTA = Entry(
        code = "billing_quota",
        message = "Model quota reached. Change model or add provider credits, then try again.",
        httpStatus = 402,
        category = "quota",
    )

    val RATE_LIMIT = Entry(
        code = "rate_limit",
        message = "The model is receiving too many requests. Wait a moment and try again.",
        httpStatus = 429,
        category = "rate_limit",
    )

    val POLICY_BLOCKED = Entry(
        code = "policy_blocked",
        message = "The model could not complete this request because of a provider policy.",
        httpStatus = 403,
        category = "policy",
    )

    val SERVER_ERROR = Entry(
        code = "server_error",
        message = "JKP had a server problem. Your message is preserved; try again.",
        httpStatus = 500,
        category = "server",
    )

    val NETWORK_UNREACHABLE = Entry(
        code = "network_unreachable",
        message = "JKP is unreachable. Check the host connection and try again.",
        httpStatus = 0,
        category = "network",
    )

    val APPROVAL_WAIT = Entry(
        code = "approval_wait",
        message = "Host may be waiting for command approval. Approve on desktop/Telegram or send /yolo there.",
        httpStatus = 0,
        category = "hang",
    )

    val UNKNOWN = Entry(
        code = "unknown",
        message = "JKP could not finish this response. Try again or change model.",
        httpStatus = 0,
        category = "unknown",
    )

    val ALL: List<Entry> = listOf(
        INVALID_DEVICE_GRANT,
        INVALID_API_KEY,
        ADMIN_KEY_REQUIRED,
        BILLING_QUOTA,
        RATE_LIMIT,
        POLICY_BLOCKED,
        SERVER_ERROR,
        NETWORK_UNREACHABLE,
        APPROVAL_WAIT,
        UNKNOWN,
    )

    /**
     * Codes every control-plane client must understand (host `jkp.client_errors`).
     * Unit tests lock this set so APK catalog cannot silently drift.
     */
    val REQUIRED_HOST_CODES: Set<String> = setOf(
        "invalid_device_grant",
        "invalid_api_key",
        "admin_key_required",
        "billing_quota",
        "rate_limit",
        "policy_blocked",
        "server_error",
        "network_unreachable",
        "approval_wait",
        "unknown",
    )

    fun byCode(code: String?): Entry? {
        if (code.isNullOrBlank()) return null
        return ALL.firstOrNull { it.code == code }
    }

    /**
     * Best-effort classify from HTTP status + optional body/code/offline flag.
     * Mirrors `jkp.client_errors.classify`.
     */
    fun classify(
        status: Int? = null,
        message: String? = null,
        code: String? = null,
        offline: Boolean = false,
    ): Entry {
        byCode(code)?.let { return it }

        val detail = (message ?: "").lowercase()
        val st = status ?: 0

        // Free-text / SSE payloads often embed catalog codes without a structured field.
        ALL.filter { it.code != "unknown" }
            .sortedByDescending { it.code.length }
            .firstOrNull { it.code in detail }
            ?.let { return it }

        if (offline || "network" in detail || "fetch" in detail || "unreachable" in detail) {
            return NETWORK_UNREACHABLE
        }

        // Policy before broad 403 auth — catalog marks policy_blocked as http 403;
        // bare 403 with policy/safety copy must not become INVALID_DEVICE_GRANT.
        if ("policy" in detail || "safety" in detail || "blocked content" in detail) {
            return POLICY_BLOCKED
        }

        val authSignal = "invalid" in detail &&
            ("key" in detail || "grant" in detail || "token" in detail)
        if (st in listOf(401, 403) || authSignal) {
            if ("admin" in detail) return ADMIN_KEY_REQUIRED
            if ("grant" in detail || "device" in detail || "authorized" in detail) {
                return INVALID_DEVICE_GRANT
            }
            if (st == 401 || "api key" in detail || "api_key" in detail ||
                "credential" in detail || "auth" in detail
            ) {
                return INVALID_DEVICE_GRANT
            }
            // invalid+key/grant/token without structured code — still auth family.
            // Do NOT fall through bare 403 (policy/unknown) into re-pair copy.
            if (authSignal) return INVALID_DEVICE_GRANT
        }

        if ("spending-limit" in detail || "quota" in detail || "credit" in detail || "billing" in detail) {
            return BILLING_QUOTA
        }

        if (st == 429 || "rate limit" in detail || "rate_limit" in detail || "rate-limit" in detail) {
            return RATE_LIMIT
        }

        if ("approval" in detail || "/yolo" in detail || "waiting for user" in detail) {
            return APPROVAL_WAIT
        }

        if (st >= 500) return SERVER_ERROR

        return UNKNOWN
    }

    fun userMessage(
        status: Int? = null,
        message: String? = null,
        code: String? = null,
        offline: Boolean = false,
    ): String = classify(status = status, message = message, code = code, offline = offline).message
}
