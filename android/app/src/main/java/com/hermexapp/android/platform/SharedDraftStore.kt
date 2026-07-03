package com.hermexapp.android.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 9 share target: text shared into the app (ACTION_SEND) parks here until
 * the connected UI consumes it into a new chat — the Android counterpart of the
 * iOS `SharedDraftStore` bridging the share extension. In-memory only; a share
 * into a signed-out app is simply dropped after onboarding (a durable handoff
 * can come with the share-extension parity slice).
 */
class SharedDraftStore {

    private val _pendingText = MutableStateFlow<String?>(null)
    val pendingText: StateFlow<String?> = _pendingText

    fun offer(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isNotEmpty()) _pendingText.value = trimmed
    }

    /** Returns and clears the pending draft. */
    fun consume(): String? = _pendingText.value.also { _pendingText.value = null }
}
