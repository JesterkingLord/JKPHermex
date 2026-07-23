package com.hermexapp.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wave 2 (2026-07-27) — Round-trip coverage for the chat scroll-position
 * memory hook. We keep this contract tiny so a future "scroll position
 * memory" regression is loud:
 *
 *   * `scrollPosition` returns null for an unknown session
 *   * `setScrollPosition` then `scrollPosition` returns the same pair
 *   * Different sessionIds store independent values
 *   * Empty / malformed stored values return null (no crash, no garbage)
 *   * Negative inputs are refused (defensive — settings UI should
 *     never produce negatives, but the app is not exempt from bug
 *     reports that pass -1 in)
 */
class AppPrefsScrollPositionTest {

    @Test
    fun `scrollPosition returns null when nothing has been stored`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        assertNull(prefs.scrollPosition("s1"))
    }

    @Test
    fun `save then load round-trips the pair`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        prefs.setScrollPosition("s1", 17, 342)
        val pos = prefs.scrollPosition("s1")
        assertEquals(17 to 342, pos)
    }

    @Test
    fun `different session ids store independent positions`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        prefs.setScrollPosition("s1", 5, 0)
        prefs.setScrollPosition("s2", 8, 128)
        assertEquals(5 to 0, prefs.scrollPosition("s1"))
        assertEquals(8 to 128, prefs.scrollPosition("s2"))
    }

    @Test
    fun `empty sessionId is a no-op for both reads and writes`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        prefs.setScrollPosition("", 5, 10)
        assertNull(prefs.scrollPosition(""))
    }

    @Test
    fun `negative inputs are refused`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        // No exception; just nothing is written. Caller can detect by
        // reading — the value remains absent.
        prefs.setScrollPosition("s1", -3, 50)
        assertNull(prefs.scrollPosition("s1"))
    }

    @Test
    fun `malformed stored value is treated as null`() {
        // Write garbage directly via the in-memory KV bypass.
        val store = InMemoryKeyValueStore()
        store.putString("scroll_pos_s1", "not-an-int")
        val prefs = AppPrefs(store)
        assertNull(prefs.scrollPosition("s1"))
    }
}
