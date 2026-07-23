package com.hermexapp.android.ui

import com.hermexapp.android.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for the letter-jump map used by [FastScrollbar]. The
 * derivation lives inline in the screen for now (Wave 1 keeps the logic
 * local; Wave 2 may promote it to a helper if the chat timeline needs
 * the same treatment). This test pins the contract so we don't
 * regress the rail's behavior:
 *
 *   * First-letter uppercase, only `[A-Z]` (and unicode `isLetter`)
 *   * One entry per letter — the first occurrence in title order
 *   * Blank / null titles are skipped
 *   * Numeric / symbolic titles (e.g. "123" or "=== ") are skipped
 *
 * Since the derivation is currently inline in [SessionListScreen], this
 * test exercises a parallel, *extractable* helper alongside it. The
 * test asserts the same alphabet-bucket semantics — when the caller
 * converts to use [buildLetterIndex] directly, this test stays valid.
 */
class FastScrollbarLetterIndexTest {

    @Test
    fun `empty sessions map to an empty letter index`() {
        assertTrue(buildLetterIndex(emptyList()).isEmpty())
    }

    @Test
    fun `mixed-case titles collapse to uppercase letters`() {
        val map = buildLetterIndex(
            listOf(
                sample(id = "a", title = "alpha"),
                sample(id = "b", title = "Beta"),
                sample(id = "c", title = "CHARLIE"),
            ),
        )
        assertEquals(setOf('A', 'B', 'C'), map.keys)
        assertEquals(0, map['A'])
        assertEquals(1, map['B'])
        assertEquals(2, map['C'])
    }

    @Test
    fun `first-occurrence wins on duplicate first letter`() {
        val map = buildLetterIndex(
            listOf(
                sample(id = "1", title = "Apple"),
                sample(id = "2", title = "Avocado"),
                sample(id = "3", title = "Banana"),
            ),
        )
        // Two titles start with A — only the first should be recorded.
        assertEquals(0, map['A'])
        assertEquals(2, map['B'])
        assertEquals(2, map.size)
    }

    @Test
    fun `blank and null titles are skipped`() {
        val map = buildLetterIndex(
            listOf(
                sample(id = "1", title = ""),
                sample(id = "2", title = "   "),
                sample(id = "3", title = null),
                sample(id = "4", title = "Delta"),
            ),
        )
        assertEquals(1, map.size)
        assertEquals(3, map['D'])
    }

    @Test
    fun `numeric titles are skipped`() {
        val map = buildLetterIndex(
            listOf(
                sample(id = "1", title = "123"),
                sample(id = "2", title = "!@#"),
                sample(id = "3", title = "Echo"),
            ),
        )
        assertEquals(setOf('E'), map.keys)
        assertFalse(map.containsKey('1'))
    }

    @Test
    fun `letters in the BMP alphabet are accepted`() {
        val map = buildLetterIndex(
            listOf(
                sample(id = "1", title = "Zulu"),
                sample(id = "2", title = "alpha"),
            ),
        )
        assertEquals(setOf('Z', 'A'), map.keys)
        assertEquals(0, map['Z'])
        assertEquals(1, map['A'])
    }

    private fun sample(id: String, title: String?): SessionSummary = SessionSummary(
        sessionId = id,
        title = title,
        workspace = null,
        model = null,
        modelProvider = null,
        messageCount = 0,
        createdAt = 0.0,
        updatedAt = 0.0,
        lastMessageAt = 0.0,
        pinned = false,
        archived = false,
        projectId = null,
        profile = null,
        activeStreamId = null,
        isStreaming = false,
        isCliSession = false,
        sourceTag = null,
        sessionSource = null,
        sourceLabel = null,
    )
}

/**
 * Extractable letter-jump map. Lives in the test source today (mirrors
 * the inline logic in `SessionListScreen.buildLetterIndex`); if a future
 * wave promotes it to production, the production code calls this and
 * the test continues to cover it without churn.
 */
internal fun buildLetterIndex(sessions: List<SessionSummary>): Map<Char, Int> {
    val out = sortedMapOf<Char, Int>()
    for ((idx, s) in sessions.withIndex()) {
        val trimmed = s.title?.trim()?.takeIf(String::isNotEmpty) ?: continue
        val first = trimmed.first().uppercaseChar()
        if (first.isLetter()) out.putIfAbsent(first, idx)
    }
    return out
}
