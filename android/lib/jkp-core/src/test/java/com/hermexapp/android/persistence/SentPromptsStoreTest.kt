package com.hermexapp.android.persistence

import com.hermexapp.android.config.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt library only ever contained prompts typed into it by hand, so a
 * prompt that worked well was lost unless the operator saved it at the time.
 * This history records sends as they happen.
 */
class SentPromptsStoreTest {

    @Test
    fun `records a sent prompt`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("summarise the release notes", 1_000)

        assertEquals(listOf("summarise the release notes"), store.history.value.map { it.body })
        assertEquals(1, store.history.value.single().timesSent)
    }

    @Test
    fun `re-sending the same prompt counts it instead of duplicating it`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("run the tests", 1_000)
        store.record("run the tests", 2_000)
        store.record("run the tests", 3_000)

        val only = store.history.value.single()
        assertEquals(3, only.timesSent)
        assertEquals(3_000, only.lastSentAtMillis)
    }

    @Test
    fun `the most recently sent prompt comes first`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("first", 1_000)
        store.record("second", 2_000)
        store.record("first", 3_000) // re-used, so it moves back to the front

        assertEquals(listOf("first", "second"), store.history.value.map { it.body })
    }

    @Test
    fun `whitespace-only sends are not history`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("   ", 1_000)
        store.record("", 2_000)

        assertTrue(store.history.value.isEmpty())
    }

    @Test
    fun `slash commands are instructions to the app, not prompts`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("/status", 1_000)
        store.record("/queue check this later", 2_000)
        store.record("a real prompt", 3_000)

        assertEquals(listOf("a real prompt"), store.history.value.map { it.body })
    }

    @Test
    fun `the history is capped so it cannot grow without bound`() {
        val store = SentPromptsStore(InMemoryKeyValueStore(), maxEntries = 3)
        (1..5).forEach { store.record("prompt $it", it * 1_000L) }

        assertEquals(3, store.history.value.size)
        assertEquals(
            "the cap must drop the oldest, not the newest",
            listOf("prompt 5", "prompt 4", "prompt 3"),
            store.history.value.map { it.body },
        )
    }

    @Test
    fun `history survives a restart`() {
        val backing = InMemoryKeyValueStore()
        SentPromptsStore(backing).record("remember me", 1_000)

        val reloaded = SentPromptsStore(backing)

        assertEquals(listOf("remember me"), reloaded.history.value.map { it.body })
    }

    @Test
    fun `forget drops a single entry and keeps the rest`() {
        val store = SentPromptsStore(InMemoryKeyValueStore())
        store.record("keep", 1_000)
        store.record("drop", 2_000)

        store.forget("drop")

        assertEquals(listOf("keep"), store.history.value.map { it.body })
    }

    @Test
    fun `a payload written by an older build is dropped, not fatal`() {
        val backing = InMemoryKeyValueStore()
        backing.putString(SentPromptsStore.KEY, "{not the array we wrote}")

        val store = SentPromptsStore(backing)

        assertTrue(store.history.value.isEmpty())
        // and it still works afterwards
        store.record("fresh start", 1_000)
        assertEquals(1, store.history.value.size)
    }
}
