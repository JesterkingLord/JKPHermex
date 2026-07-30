package com.hermexapp.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStatusTest {

    @Test
    fun `every ReasoningEffort wire value is unique and stable`() {
        // Wire values are the public API the server sees; changing them is a
        // breaking change. This test trips if anyone reorders the enum or
        // edits a wireValue string.
        val wires = ReasoningEffort.entries.map { it.wireValue }
        assertEquals(wires.size, wires.toSet().size)
    }

    @Test
    fun `AUTO is the only effort with a null wireValue`() {
        assertNull(ReasoningEffort.AUTO.wireValue)
        ReasoningEffort.entries.filter { it != ReasoningEffort.AUTO }
            .forEach { assertTrue("wire value for ${it.name} is null", it.wireValue != null) }
    }

    @Test
    fun `fromServer maps every documented effort`() {
        listOf(
            "none" to ReasoningEffort.NONE,
            "minimal" to ReasoningEffort.MINIMAL,
            "low" to ReasoningEffort.LOW,
            "medium" to ReasoningEffort.MEDIUM,
            "high" to ReasoningEffort.HIGH,
            "xhigh" to ReasoningEffort.XHIGH,
        ).forEach { (wire, expected) ->
            assertEquals("wire '$wire' should map to $expected", expected, ReasoningEffort.fromServer(wire))
        }
    }

    @Test
    fun `fromServer is case-insensitive and trims whitespace`() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromServer(" HIGH "))
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.fromServer("Medium"))
    }
    @Test
    fun `fromServer falls back to AUTO for null blank or unknown`() {
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromServer(null))
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromServer(""))
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromServer("   "))
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromServer("tropical"))
        // Critical: we never accept the literal "auto" — that's a client-only
        // sentinel and would round-trip weirdly.
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromServer("auto"))
    }

    @Test
    fun `effectiveEffort prefers the snake_case field then camelCase then 'effort'`() {
        val snake = ReasoningStatus(reasoningEffort = "low")
        assertEquals(ReasoningEffort.LOW, snake.effectiveEffort)

        val camel = ReasoningStatus(reasoningEffortCamel = "high")
        assertEquals(ReasoningEffort.HIGH, camel.effectiveEffort)

        val legacy = ReasoningStatus(effort = "minimal")
        assertEquals(ReasoningEffort.MINIMAL, legacy.effectiveEffort)

        // snake_case wins when both are present.
        val both = ReasoningStatus(reasoningEffort = "low", reasoningEffortCamel = "high")
        assertEquals(ReasoningEffort.LOW, both.effectiveEffort)
    }

    @Test
    fun `effectiveShowReasoning defaults to true when the server omits it`() {
        // The server doesn't always echo the display toggle; we default to
        // showing reasoning because hiding silently drops content.
        assertTrue(ReasoningStatus().effectiveShowReasoning)
    }

    @Test
    fun `effectiveShowReasoning accepts both naming styles`() {
        assertTrue(ReasoningStatus(showReasoning = true).effectiveShowReasoning)
        assertTrue(!ReasoningStatus(showReasoning = false).effectiveShowReasoning)
        assertTrue(!ReasoningStatus(showReasoningCamel = false).effectiveShowReasoning)
    }

    @Test
    fun `unknown fields are tolerated so the server can extend`() {
        // A status with only the canonical fields decodes fine; the real
        // tolerance guarantee comes from `ApiJson.ignoreUnknownKeys` in
        // production. This test simply verifies the data class isn't
        // accidentally marked strict somewhere.
        val status = ReasoningStatus(ok = true, reasoningEffort = "xhigh")
        assertEquals(ReasoningEffort.XHIGH, status.effectiveEffort)
        assertEquals(true, status.ok)
    }
}
