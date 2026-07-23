package com.hermexapp.android.features.chat

/**
 * Wave 4 Slice 4.1 — pure in-chat substring search.
 *
 * Given an iteration of texts (one per timeline entry, in chronological
 * order) and a query string, [findMatches] returns the indices of the
 * entries that contain at least one case-insensitive substring match —
 * one index per *entry*, not per match. We're navigating by entry, not by
 * occurrence, because users want to step past whole messages, not count
 * every `the` in a paragraph.
 *
 * Algorithm intentionally simple: O(N) over entries, O(M) per match (KMP
 * not needed at chat-message scale). Empty query = empty result.
 *
 * Exposed as a top-level function (not a member of [ChatViewModel]) so the
 * JVM unit-test suite can exercise it without a CoroutineScope, ViewModel
 * dispatcher, or MockWebServer.
 */
internal fun findMatches(
    texts: List<String>,
    query: String,
): List<Int> {
    if (query.isBlank()) return emptyList()
    val needle = query.trim()
    return texts.mapIndexedNotNull { index, text ->
        if (text.contains(needle, ignoreCase = true)) index else null
    }
}

/**
 * Resolve which timeline entry indexes contain a match for [query], in
 * chronological order, with the *first* match range per entry returned
 * alongside the entry index. UI uses the range to underline the hit
 * visually (yellow box on the matched substring).
 *
 * Returns a list of (entryIndex, charRange). One entry maps to one range —
 * see [findMatches] rationale. Use [findMatches] when you don't need the
 * range (cheap; just N boolean tests).
 */
internal fun findMatchesWithRanges(
    texts: List<String>,
    query: String,
): List<Pair<Int, IntRange>> {
    if (query.isBlank()) return emptyList()
    val needle = query.trim()
    val out = mutableListOf<Pair<Int, IntRange>>()
    texts.forEachIndexed { index, text ->
        val hit = text.indexOf(needle, startIndex = 0, ignoreCase = true)
        if (hit >= 0) out += index to (hit until hit + needle.length)
    }
    return out
}
