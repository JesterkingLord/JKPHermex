package com.hermexapp.android.features.chat

/**
 * v0.8.14 — transport reachability for the chat composer.
 *
 * The Android client has no host heartbeat yet, so this state is fed by the
 * real signals the chat transport already produces: a cache-served transcript
 * or a transport failure on load/send/stream means the host is (or was just)
 * unreachable. The composer keeps the send affordance visible but inert while
 * the connection is not [CONNECTED], with honest copy beneath the composer.
 */
enum class JkpConnectionState {
    /** Last host exchange succeeded; the send affordance is active. */
    CONNECTED,

    /** The host is known to be unreachable (e.g. transcript came from cache). */
    OFFLINE,

    /** The last transport attempt failed (e.g. `ApiError.Network`, SSE drop). */
    FAILED,
}
