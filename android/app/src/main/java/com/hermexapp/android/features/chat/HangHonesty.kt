package com.hermexapp.android.features.chat

import com.hermexapp.android.network.ClientErrorCatalog

/**
 * Hang honesty for long silent streams (JKPHermex 0.7.1 / host v1.13 slice 13.5b).
 *
 * When the host is waiting on a command approval, the phone must not look like
 * a crashed agent — show the same tip as Hermex PWA / desktop stall copy.
 * Pure helpers for unit tests; [ChatViewModel] drives the timer.
 */
object HangHonesty {

    /** Seconds of continuous streaming with no activity before the tip. */
    const val STALL_SECONDS: Int = 15

    val TIP: String = ClientErrorCatalog.APPROVAL_WAIT.message

    /**
     * Mid-stream transport drop (SSE onFailure). Prefer network catalog +
     * recover hint so operators don't read a raw OkHttp exception as "agent died".
     */
    val STREAM_INTERRUPTED: String =
        "${ClientErrorCatalog.NETWORK_UNREACHABLE.message} If a reply was in progress, open the session again or resend — partial text is usually kept on the host."

    /**
     * @param isStreaming run is open
     * @param silentSeconds seconds since last meaningful SSE activity
     * @param hasPendingApproval local approval overlay already up — skip tip
     */
    fun tipIfStalled(
        isStreaming: Boolean,
        silentSeconds: Int,
        hasPendingApproval: Boolean,
    ): String? {
        if (!isStreaming) return null
        if (hasPendingApproval) return null
        if (silentSeconds < STALL_SECONDS) return null
        return TIP
    }

    /** Elapsed wall-clock copy for banners: "Still working (22s). …" */
    fun banner(silentSeconds: Int): String {
        val tip = TIP
        if (silentSeconds < STALL_SECONDS) return tip
        return "Still working (${silentSeconds}s). $tip"
    }

    /**
     * Map a transport / SSE failure into operator-facing copy.
     * Pure so unit tests can lock the vocabulary without OkHttp.
     */
    fun transportFailureMessage(raw: String?): String {
        val detail = (raw ?: "").trim()
        if (detail.isEmpty()) return STREAM_INTERRUPTED
        // Already catalog-shaped — keep as-is.
        if (detail == ClientErrorCatalog.NETWORK_UNREACHABLE.message ||
            detail == STREAM_INTERRUPTED
        ) {
            return detail
        }
        val lower = detail.lowercase()
        if ("cancel" in lower || "closed" in lower || "reset" in lower ||
            "timeout" in lower || "unreachable" in lower || "failed to connect" in lower ||
            "connection" in lower || "socket" in lower
        ) {
            return STREAM_INTERRUPTED
        }
        // Unknown transport text: still surface network honesty + short raw tail.
        val clipped = if (detail.length > 120) detail.take(117) + "…" else detail
        return "$STREAM_INTERRUPTED ($clipped)"
    }

    /**
     * In-band SSE `error` event → host-aligned catalog copy (not raw JSON).
     * Pure entry for unit tests; [ChatViewModel] wires UI.
     */
    fun streamErrorMessage(raw: String?): String {
        val detail = (raw ?: "").trim()
        if (detail.isEmpty()) {
            return ClientErrorCatalog.UNKNOWN.message
        }
        // Prefer explicit catalog codes embedded in the payload.
        return ClientErrorCatalog.userMessage(message = detail)
    }

    /** True when stream/API text means the local pairing grant should be cleared. */
    fun isAuthFailureMessage(raw: String?): Boolean {
        val entry = ClientErrorCatalog.classify(message = raw)
        return entry.code == ClientErrorCatalog.INVALID_DEVICE_GRANT.code ||
            entry.code == ClientErrorCatalog.INVALID_API_KEY.code
    }

    /**
     * Stream-drop recovery decision (7.4 partial / excellence 13.9).
     *
     * Pure — does not invent reconnect APIs. Operators keep partial text and
     * get an honest resend/open-session tip (host still owns the agent run).
     */
    data class StreamDropRecovery(
        val keepPartial: Boolean,
        val offerResend: Boolean,
        val tip: String,
    )

    /**
     * @param hadPartialAssistantText true if any assistant tokens were already rendered
     * @param isTransportDrop true for SSE/OkHttp transport failure (vs in-band error event)
     */
    fun streamDropRecovery(
        hadPartialAssistantText: Boolean,
        isTransportDrop: Boolean,
    ): StreamDropRecovery {
        val tip = if (isTransportDrop) {
            STREAM_INTERRUPTED
        } else {
            ClientErrorCatalog.UNKNOWN.message +
                " If a reply was in progress, open the session again or resend."
        }
        return StreamDropRecovery(
            keepPartial = true, // never wipe operator-visible tokens on drop
            offerResend = hadPartialAssistantText || isTransportDrop,
            tip = tip,
        )
    }

    /** True when UI should retain assistant draft text after a failed stream. */
    fun shouldKeepPartialTranscript(hadPartialAssistantText: Boolean): Boolean {
        // keepPartial is always true in streamDropRecovery — partial never wiped.
        return streamDropRecovery(
            hadPartialAssistantText = hadPartialAssistantText,
            isTransportDrop = true,
        ).keepPartial
    }
}
