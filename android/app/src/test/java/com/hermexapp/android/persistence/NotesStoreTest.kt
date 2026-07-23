package com.hermexapp.android.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [NoteStore] contract, running against
 * [InMemoryNoteStore]. Picks up the same fields the production
 * Room store binds to ([NoteEntity], [NotesDao]).
 *
 * Why two stores (memory + Room): the in-memory path runs on the
 * local JVM in milliseconds; the Room path is exercised end-to-end on
 * device (instrumented tests would belong in androidTest/, not here).
 */
class NotesStoreTest {

    private fun make(
        id: String,
        title: String = "t-$id",
        body: String = "b-$id",
        colorHex: String = "#FFE9A2",
        pinned: Boolean = false,
        updatedAtMillis: Long = 0L,
        createdAtMillis: Long = 0L,
    ) = NoteEntity(
        id = id,
        title = title,
        body = body,
        colorHex = colorHex,
        pinned = pinned,
        updatedAtMillis = updatedAtMillis,
        createdAtMillis = createdAtMillis,
    )

    @Test fun `upsert then get returns the same entity`() = runBlocking {
        val store = InMemoryNoteStore()
        val note = make("a", updatedAtMillis = 1L)
        store.upsert(note)
        assertEquals(note, store.get("a"))
    }

    @Test fun `get on missing id returns null`() = runBlocking {
        val store = InMemoryNoteStore()
        assertNull(store.get("nope"))
    }

    @Test fun `setPinned flips pin and bumps updatedAt via clock`() = runBlocking {
        var now = 100L
        val store = InMemoryNoteStore(clock = { now })
        store.upsert(make("a", updatedAtMillis = 1L, pinned = false))
        now = 200L
        store.setPinned("a", pinned = true)
        val after = store.get("a")!!
        assertTrue(after.pinned)
        assertEquals(200L, after.updatedAtMillis)
    }

    @Test fun `delete removes the note`() = runBlocking {
        val store = InMemoryNoteStore()
        store.upsert(make("a"))
        store.delete("a")
        assertNull(store.get("a"))
    }

    @Test fun `last write wins when upsert lands with newer updatedAt`() = runBlocking {
        val store = InMemoryNoteStore()
        store.upsert(make("a", title = "old", updatedAtMillis = 10L))
        store.upsert(make("a", title = "new", updatedAtMillis = 20L))
        assertEquals("new", store.get("a")?.title)
    }

    @Test fun `last write wins refuses older payload to overwrite newer state`() = runBlocking {
        val store = InMemoryNoteStore()
        store.upsert(make("a", title = "newer", updatedAtMillis = 30L))
        store.upsert(make("a", title = "stale", updatedAtMillis = 10L))
        assertEquals("newer", store.get("a")?.title)
    }

    @Test fun `count tracks upserts and deletes`() = runBlocking {
        val store = InMemoryNoteStore()
        assertEquals(0, store.count())
        store.upsert(make("a"))
        store.upsert(make("b"))
        assertEquals(2, store.count())
        store.delete("a")
        assertEquals(1, store.count())
    }

    @Test fun `observeAll emits pinned-first then newest-first by updatedAt`() = runBlocking {
        val store = InMemoryNoteStore(clock = { 0L })
        store.upsert(make("a", pinned = false, updatedAtMillis = 30L))
        store.upsert(make("b", pinned = false, updatedAtMillis = 10L))
        store.upsert(make("c", pinned = true, updatedAtMillis = 1L))
        store.upsert(make("d", pinned = true, updatedAtMillis = 100L))
        val all = store.observeAll().first()
        assertEquals(listOf("d", "c", "a", "b"), all.map { it.id })
    }

    @Test fun `observeAll emits updates after a mutation`() = runBlocking {
        val store = InMemoryNoteStore()
        store.upsert(make("a", title = "v1"))
        store.upsert(make("a", title = "v2", updatedAtMillis = 1L))
        val list = store.observeAll().first()
        assertEquals("v2", list.first { it.id == "a" }.title)
    }
}
