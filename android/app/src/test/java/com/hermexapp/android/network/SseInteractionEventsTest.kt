package com.hermexapp.android.network

import com.hermexapp.android.model.ContextWindowSnapshot
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** New SSE events (done usage, approval/clarify) parse the way the iOS decoder does. */
class SseInteractionEventsTest {

    @Test
    fun `done carries both session and usage`() {
        val event = SseEventParser.parse(
            "done",
            """{"session": {"session_id": "s"}, "usage": {"context_length": 200000, "last_prompt_tokens": 50000}}""",
        ) as SseEvent.Done

        assertTrue(event.session != null)
        val usage = ApiJson.decodeFromJsonElement(ContextWindowSnapshot.serializer(), event.usage!!)
        assertEquals(200000, usage.contextLength)
        assertEquals(25, ((usage.percentage ?: 0.0) * 100).toInt())
        assertEquals("25% context", usage.compactIndicator)
    }

    @Test
    fun `an initial event with question markers is a clarification`() {
        val event = SseEventParser.parse(
            "initial",
            """{"pending": {"clarify_id": "c1", "question": "Which environment?"}}""",
        )
        assertTrue(event is SseEvent.ClarificationPending)
        val obj = (event as SseEvent.ClarificationPending).payload.jsonObject
        assertTrue(obj.containsKey("pending"))
    }

    @Test
    fun `an initial event without clarification markers is an approval`() {
        val event = SseEventParser.parse(
            "initial",
            """{"pending": {"approval_id": "a1", "command": "rm -rf build"}}""",
        )
        assertTrue(event is SseEvent.ApprovalPending)
    }

    @Test
    fun `explicit approval and clarify event types map through`() {
        assertTrue(SseEventParser.parse("approval", """{"approval_id": "a"}""") is SseEvent.ApprovalPending)
        assertTrue(SseEventParser.parse("clarify", """{"clarify_id": "c"}""") is SseEvent.ClarificationPending)
    }

    @Test
    fun `context percentage is null without both fields`() {
        assertNull(ContextWindowSnapshot(contextLength = 1000).percentage)
        assertNull(ContextWindowSnapshot(lastPromptTokens = 500).compactIndicator)
    }
}
