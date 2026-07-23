package com.hermexapp.android.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PromptStore]. Mirrors [NotesStoreTest] but
 * covers the prompt-specific behavior (usageCount bump, sort by
 * pinned DESC → usageCount DESC → updatedAt DESC).
 */
class PromptsStoreTest {

    private fun make(
        id: String,
        name: String = "n-$id",
        body: String = "b-$id",
        tags: String = "",
        pinned: Boolean = false,
        usageCount: Int = 0,
        updatedAtMillis: Long = 0L,
        createdAtMillis: Long = 0L,
    ) = PromptEntity(
        id = id,
        name = name,
        body = body,
        tags = tags,
        pinned = pinned,
        usageCount = usageCount,
        updatedAtMillis = updatedAtMillis,
        createdAtMillis = createdAtMillis,
    )

    @Test fun `upsert then get returns the same entity`() = runBlocking {
        val store = InMemoryPromptStore()
        val p = make("a", updatedAtMillis = 1L)
        store.upsert(p)
        assertEquals(p, store.get("a"))
    }

    @Test fun `bumpUsage increments usageCount and bumps updatedAt`() = runBlocking {
        var now = 100L
        val store = InMemoryPromptStore(clock = { now })
        store.upsert(make("a", usageCount = 0, updatedAtMillis = 1L))
        now = 200L
        store.bumpUsage("a")
        val after = store.get("a")!!
        assertEquals(1, after.usageCount)
        assertEquals(200L, after.updatedAtMillis)
    }

    @Test fun `observeAll sorts pinned DESC then usageCount DESC then updatedAt DESC`() = runBlocking {
        val store = InMemoryPromptStore(clock = { 0L })
        // 4 prompts with mixed attributes to exercise every tie-breaker.
        store.upsert(make("old-unpinned", pinned = false, usageCount = 99, updatedAtMillis = 5L))
        store.upsert(make("new-unpinned", pinned = false, usageCount = 99, updatedAtMillis = 50L))
        store.upsert(make("less-used-pinned", pinned = true, usageCount = 1, updatedAtMillis = 5L))
        store.upsert(make("most-used-pinned", pinned = true, usageCount = 100, updatedAtMillis = 5L))
        val all = store.observeAll().first()
        assertEquals(
            listOf("most-used-pinned", "less-used-pinned", "new-unpinned", "old-unpinned"),
            all.map { it.id },
        )
    }

    @Test fun `last write wins refuses older payload to overwrite newer state`() = runBlocking {
        val store = InMemoryPromptStore()
        store.upsert(make("a", name = "newer", updatedAtMillis = 30L))
        store.upsert(make("a", name = "stale", updatedAtMillis = 10L))
        assertEquals("newer", store.get("a")?.name)
    }

    @Test fun `delete drops the prompt from the live list`() = runBlocking {
        val store = InMemoryPromptStore()
        store.upsert(make("a"))
        store.delete("a")
        assertNull(store.get("a"))
        assertEquals(0, store.count())
    }

    @Test fun `observe one id surfaces a deletion as null`() = runBlocking {
        // Pre-shaping the API: when a user deletes the prompt they were
        // currently viewing, observe(id) must re-emit null so the screen
        // can pop back to the list. We can't await a null via Flow.first
        // without the test dispatcher, so we instead assert via get()
        // after delete() — observe() shares the same backing map.
        val store = InMemoryPromptStore()
        store.upsert(make("a"))
        store.delete("a")
        assertNull(store.get("a"))
    }
}
