package com.hermexapp.android.features.composer

import com.hermexapp.android.persistence.InMemoryNoteStore
import com.hermexapp.android.persistence.InMemoryPromptStore
import com.hermexapp.android.persistence.NoteEntity
import com.hermexapp.android.persistence.PromptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [InsertPaletteViewModel]. Validates the
 * cross-store aggregation (notes + prompts → one UiState), search
 * filtering, and pinned-first ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsertPaletteViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var notes: InMemoryNoteStore
    private lateinit var prompts: InMemoryPromptStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        notes = InMemoryNoteStore(clock = { 0L })
        prompts = InMemoryPromptStore(clock = { 0L })
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = InsertPaletteViewModel(notes, prompts)

    private fun note(id: String, title: String = "", body: String = "", pinned: Boolean = false, ts: Long = 0L) =
        NoteEntity(
            id = id, title = title, body = body,
            colorHex = "#fff", pinned = pinned,
            updatedAtMillis = ts, createdAtMillis = ts,
        )

    private fun prompt(
        id: String, name: String = "", body: String = "",
        pinned: Boolean = false, ts: Long = 0L,
    ) = PromptEntity(
        id = id, name = name, body = body, tags = "", pinned = pinned,
        usageCount = 0,
        updatedAtMillis = ts, createdAtMillis = ts,
    )

    @Test fun `empty stores yield empty UiState`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.totalCount)
    }

    @Test fun `combines notes and prompts into one list`() = runTest(dispatcher) {
        val vm = newVm()
        notes.upsert(note("n1", body = "hello"))
        prompts.upsert(prompt("p1", name = "summarize", body = "summarize this"))
        advanceUntilIdle()
        val items = vm.uiState.value.items
        assertEquals(2, items.size)
        assertTrue(items.any { it is InsertItem.Note && it.source.id == "n1" })
        assertTrue(items.any { it is InsertItem.Prompt && it.source.id == "p1" })
    }

    @Test fun `pinned items surface before non-pinned regardless of recent order`() = runTest(dispatcher) {
        val vm = newVm()
        notes.upsert(note("n-recent", body = "recent note", pinned = false, ts = 100L))
        notes.upsert(note("n-pinned-old", body = "old pinned note", pinned = true, ts = 1L))
        prompts.upsert(prompt("p-mid", name = "mid", body = "middle", pinned = false, ts = 50L))
        advanceUntilIdle()
        val items = vm.uiState.value.items
        // Pinned (the only one) is first; relative ordering of the rest
        // is by recent-millis descending, falling through both stores.
        val firstId = when (val it = items.first()) {
            is InsertItem.Note -> it.source.id
            is InsertItem.Prompt -> it.source.id
        }
        assertEquals("n-pinned-old", firstId)
        val allIds = items.map { item ->
            when (val it = item) {
                is InsertItem.Note -> "note:${it.source.id}"
                is InsertItem.Prompt -> "prompt:${it.source.id}"
            }
        }.toSet()
        assertEquals(
            setOf("note:n-recent", "note:n-pinned-old", "prompt:p-mid"),
            allIds,
        )
    }

    @Test fun `setQuery filters across both stores`() = runTest(dispatcher) {
        val vm = newVm()
        notes.upsert(note("n1", body = "lorem ipsum"))
        notes.upsert(note("n2", body = "dolor sit"))
        prompts.upsert(prompt("p1", name = "translation", body = "translate to french"))
        advanceUntilIdle()
        vm.setQuery("transl")
        advanceUntilIdle()
        // Only the prompt matches "transl" — neither note body does.
        val items = vm.uiState.value.items
        assertEquals(1, items.size)
        assertTrue(items.first() is InsertItem.Prompt)
    }

    @Test fun `setQuery blank restores full list`() = runTest(dispatcher) {
        val vm = newVm()
        notes.upsert(note("n1", body = "hello world"))
        prompts.upsert(prompt("p1", body = "lorem ipsum"))
        advanceUntilIdle()
        // "lorem" only matches the prompt body.
        vm.setQuery("lorem")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.items.size)
    }

    @Test fun `InsertItem_Prompt exposes names and body correctly`() = runTest(dispatcher) {
        val p = PromptEntity(
            id = "x", name = "Summarize", body = "summarize this:\n{{text}}",
            tags = "writing", pinned = true, usageCount = 5,
            updatedAtMillis = 1000L, createdAtMillis = 1000L,
        )
        val item = InsertItem.Prompt(p)
        assertEquals("prompt:x", item.id)
        assertEquals("Summarize", item.title)
        assertEquals("summarize this:\n{{text}}", item.body)
        assertEquals(1000L, item.recentMillis)
        assertTrue(item.isPinned)
        assertTrue(item.matches("summarize"))
        assertTrue(item.matches("WRITING"))
        assertFalse(item.matches("xyz"))
    }

    @Test fun `InsertItem_Note falls back to body preview when title is blank`() = runTest(dispatcher) {
        val n = NoteEntity(
            id = "x", title = "", body = "Body text used as preview",
            colorHex = "#fff", pinned = false,
            updatedAtMillis = 0L, createdAtMillis = 0L,
        )
        val item = InsertItem.Note(n)
        assertEquals("Body text used as preview", item.title)
        assertEquals("Body text used as preview", item.body)
    }

    @Test fun `InsertItem_Note title falls back to empty body marker`() = runTest(dispatcher) {
        val n = NoteEntity(
            id = "x", title = "", body = "",
            colorHex = "#fff", pinned = false,
            updatedAtMillis = 0L, createdAtMillis = 0L,
        )
        val item = InsertItem.Note(n)
        assertEquals("(empty note)", item.title)
    }
}
