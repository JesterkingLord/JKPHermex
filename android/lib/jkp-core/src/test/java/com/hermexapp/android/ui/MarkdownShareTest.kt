package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 Slice 3.1 — round-trip tests for [markdownShareBody]. We exercise the
 * pure builder directly so the JVM doesn't need an Android Context.
 *
 * The Android-specific `Intent` glue (`shareAsMarkdown`) is verified by
 * compile + manual share-sheet check; the body contract is what callers
 * actually paste into chat apps.
 */
class MarkdownShareTest {

    @Test
    fun `body contains assistant text with surrounding quote and title heading`() {
        val body = markdownShareBody(
            assistantText = "Here is the answer.",
            surroundingUserText = "What is the answer?",
            sessionTitle = "Debug build",
        )
        // Title heading present
        assertTrue("title heading missing: $body", body.startsWith("# Debug build\n"))
        // User prompt is quoted
        assertTrue("user quote missing: $body", body.contains("> **You:** What is the answer?"))
        // Assistant text included
        assertTrue("assistant missing: $body", body.contains("Here is the answer."))
        // Single trailing newline enforced so apps don't append a blank line
        assertTrue("trailing newline missing: $body", body.endsWith("\n"))
    }

    @Test
    fun `omits title when blank`() {
        val body = markdownShareBody(
            assistantText = "hello",
            surroundingUserText = null,
            sessionTitle = null,
        )
        assertFalse(body.contains("# "))
        assertTrue(body.startsWith("hello"))
    }

    @Test
    fun `omits surrounding user line when blank`() {
        val body = markdownShareBody(
            assistantText = "abc",
            surroundingUserText = "   ",
            sessionTitle = "T",
        )
        // blank surroundingText is treated as absent (no empty blockquote)
        assertFalse(body.contains("> **You:**"))
        assertTrue(body.startsWith("# T\nabc"))
    }

    @Test
    fun `trims surrounding whitespace on assistant and surrounding lines`() {
        val body = markdownShareBody(
            assistantText = "  spaced  ",
            surroundingUserText = "   ask   ",
            sessionTitle = "S",
        )
        // Both surrounding lines are trimmed at the edges
        assertTrue(body.contains("> **You:** ask"))
        // Assistant text is trimmed at the edges (intentional: share-sheet
        // targets paste cleanly into chat boxes without stray newlines)
        assertTrue(body.contains("spaced"))
        assertFalse(body.endsWith("  \n"))
    }
}
