package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.SessionSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.15 — live-indicator dot rule for [SessionRow].
 *
 * The dot itself is rendered by the private [SessionRow] composable
 * (a pulsing 8dp green circle next to the title, driven by an infinite
 * alpha animation). The show/hide decision is the extracted pure
 * predicate [shouldShowStreamingDot] so it can be pinned in plain JVM
 * tests — this module has no Compose UI-test runner (no Robolectric /
 * instrumentation, and adding test-only dependencies is out of policy),
 * so the same pattern as BulkSessionActionsBarTest applies: test the
 * decision rule, keep the rendering thin.
 *
 * Contract: the dot renders ONLY while the session is live-streaming —
 * either the `is_streaming` flag from the server, or the historical
 * `active_stream_id` marker. A plain (non-streaming) session must never
 * show it, regardless of how the nullable flag is spelled.
 */
class SessionRowStreamingDotTest {

    private fun session(
        isStreaming: Boolean? = null,
        activeStreamId: String? = null,
    ) = SessionSummary(isStreaming = isStreaming, activeStreamId = activeStreamId)

    @Test
    fun `dot renders when isStreaming is true`() {
        assertTrue(shouldShowStreamingDot(session(isStreaming = true)))
    }

    @Test
    fun `dot renders when the session carries an active stream id`() {
        // Historical live marker: the server sets active_stream_id while a
        // stream is in flight even when is_streaming is absent. The dot
        // must keep lighting up for those sessions (pre-v0.8.15 behavior).
        assertTrue(shouldShowStreamingDot(session(activeStreamId = "stream-42")))
        assertTrue(shouldShowStreamingDot(session(isStreaming = false, activeStreamId = "stream-42")))
    }

    @Test
    fun `dot does not render when isStreaming is false and no stream is active`() {
        assertFalse(shouldShowStreamingDot(session(isStreaming = false)))
    }

    @Test
    fun `dot does not render when the streaming flag is absent and no stream is active`() {
        // Default construction: both markers null (a plain, idle session).
        assertFalse(shouldShowStreamingDot(session()))
        assertFalse(shouldShowStreamingDot(session(isStreaming = null)))
    }
}
