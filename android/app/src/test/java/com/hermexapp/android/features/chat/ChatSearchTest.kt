package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 4 Slice 4.1 — pure tests for the in-chat find algorithm.
 *
 * No CoroutineScope, no ViewModel, no MockWebServer — just substring
 * matching over a list of entry texts. If these break, the chat search
 * UX breaks; if they pass, the LiveData paths reuse the same logic.
 */
class ChatSearchTest {

    @Test
    fun `empty query returns no matches`() {
        assertEquals(emptyList<Int>(), findMatches(listOf("hello", "world"), ""))
        assertEquals(emptyList<Int>(), findMatches(listOf("hello"), "   "))
    }

    @Test
    fun `substring matches at the entry level are returned in order`() {
        val entries = listOf(
            "The quick brown fox",
            "jumped over the lazy dog",
            "the end",
        )
        // All three entries contain a case-insensitive hit for `the`.
        assertEquals(listOf(0, 1, 2), findMatches(entries, "the"))
        // A specific phrase `the end` matches only index 2.
        assertEquals(listOf(2), findMatches(entries, "the end"))
        // `quick` is a single-entry hit at index 0.
        assertEquals(listOf(0), findMatches(entries, "quick"))
    }

    @Test
    fun `entries without a hit are omitted from the result`() {
        val entries = listOf(
            "alpha",
            "beta",
            "alphas",
        )
        // `alpha` matches index 0 and index 2.
        assertEquals(listOf(0, 2), findMatches(entries, "alpha"))
        // `beta` matches only index 1.
        assertEquals(listOf(1), findMatches(entries, "beta"))
        // `gamma` matches nothing — empty result, not null.
        assertEquals(emptyList<Int>(), findMatches(entries, "gamma"))
    }

    @Test
    fun `findMatchesWithRanges returns the first hit per entry`() {
        val entries = listOf("alice alice alice", "bob")
        val out = findMatchesWithRanges(entries, "alice")
        assertEquals(1, out.size)
        assertEquals(0, out[0].first)
        assertEquals(0..4, out[0].second)
    }

    @Test
    fun `findMatchesWithRanges is case-insensitive and trims whitespace`() {
        val entries = listOf("  Hello world  ", "goodbye")
        val out = findMatchesWithRanges(entries, "HELLO")
        assertEquals(1, out.size)
        // 'H' is at index 2 (after two leading spaces).
        assertEquals(2..6, out[0].second)
    }

    @Test
    fun `findMatchesWithRanges on empty query returns empty`() {
        assertEquals(emptyList<Pair<Int, IntRange>>(), findMatchesWithRanges(listOf("a", "b"), ""))
    }
}
