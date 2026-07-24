package com.hermexapp.android.features.notes

import com.hermexapp.android.persistence.InMemoryNoteStore
import com.hermexapp.android.persistence.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [NotesViewModel]. Runs against an in-memory
 * [com.hermexapp.android.persistence.NoteStore] via
 * [Dispatchers.setMain] so viewModelScope can collect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryNoteStore
    private var now: Long = 1_000L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = InMemoryNoteStore(clock = { now })
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = NotesViewModel(store, clock = { now })

    @Test fun `empty store yields empty UiState with selectionMode false`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.notes.isEmpty())
        assertEquals(0, s.totalNotes)
        assertFalse(s.selectionMode)
        assertTrue(s.selection.isEmpty())
    }

    @Test fun `upsert then load shows the note`() = runTest(dispatcher) {
        val vm = newVm()
        now = 2000L
        vm.upsert(
            NoteEntity(
                id = "x", title = "Hello", body = "World",
                colorHex = "#fff", pinned = false,
                updatedAtMillis = now, createdAtMillis = now,
            )
        )
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(1, s.totalNotes)
        assertEquals("Hello", s.notes.first().title)
    }

    @Test fun `setQuery filters notes by title and body substring`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", title = "alpha", body = "unrelated"))
        now = 200; vm.upsert(mk("b", title = "beta",   body = "phrases alpaca here"))
        now = 300; vm.upsert(mk("c", title = "gamma",  body = "no match"))
        advanceUntilIdle()
        vm.setQuery("alp")
        advanceUntilIdle()
        val ids = vm.uiState.value.notes.map { it.id }.toSet()
        // Body match (b) and title-prefix (a) both surface; gamma is filtered out.
        assertEquals(setOf("a", "b"), ids)
    }

    @Test fun `setQuery blank restores full list`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", title = "alpha"))
        now = 200; vm.upsert(mk("b", title = "beta"))
        advanceUntilIdle()
        vm.setQuery("be")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.notes.size)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.notes.size)
    }

    @Test fun `toggleSelection flips selectionMode and accumulates ids`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a"))
        now = 200; vm.upsert(mk("b"))
        advanceUntilIdle()
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.selectionMode)
        assertEquals(setOf("a", "b"), s.selection)
    }

    @Test fun `clearSelection empties and exits selection mode`() = runTest(dispatcher) {
        val vm = newVm()
        vm.toggleSelection("a")
        advanceUntilIdle()
        vm.clearSelection()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.selectionMode)
        assertTrue(s.selection.isEmpty())
    }

    @Test fun `delete drops from store and from selection`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a"))
        advanceUntilIdle()
        vm.toggleSelection("a")
        advanceUntilIdle()
        vm.delete("a")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.notes.isEmpty())
        assertFalse(s.selectionMode)
    }

    @Test fun `deleteSelected removes every selected note in one call`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a"))
        now = 200; vm.upsert(mk("b"))
        now = 300; vm.upsert(mk("c"))
        advanceUntilIdle()
        vm.toggleSelection("a")
        vm.toggleSelection("c")
        advanceUntilIdle()
        vm.deleteSelected()
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.uiState.value.notes.map { it.id })
    }

    @Test fun `togglePinned reflects inversion in UiState`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", pinned = false))
        advanceUntilIdle()
        vm.togglePinned("a", currentPinned = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notes.first { it.id == "a" }.pinned)
    }

    @Test fun `createEmptyNote returns a UUID id then upserts into store`() = runTest(dispatcher) {
        val vm = newVm()
        val id = vm.createEmptyNote()
        advanceUntilIdle()
        assertTrue(id.isNotBlank())
        val s = vm.uiState.value
        assertNotNull(s.notes.firstOrNull { it.id == id })
    }

    private fun mk(id: String, title: String = id, body: String = "", pinned: Boolean = false) =
        NoteEntity(
            id = id, title = title, body = body,
            colorHex = NotesViewModel.DEFAULT_COLOR_HEX,
            pinned = pinned,
            updatedAtMillis = now, createdAtMillis = now,
        )

    // ─── Wave 9 (AI Notes) tests ─────────────────────────────────────

    @Test fun `setStatus updates the note on disk`() = runTest(dispatcher) {
        val vm = newVm()
        now = 1000L
        vm.upsert(mk("a"))
        advanceUntilIdle()
        vm.setStatus("a", "plan")
        advanceUntilIdle()
        assertEquals("plan", vm.uiState.value.notes.first { it.id == "a" }.status)
    }

    @Test fun `setStatus cycles through IDEA to PLAN to ACTION`() = runTest(dispatcher) {
        val vm = newVm()
        now = 1000L
        vm.upsert(mk("a"))
        advanceUntilIdle()
        vm.setStatus("a", "plan")
        advanceUntilIdle()
        vm.setStatus("a", "action")
        advanceUntilIdle()
        assertEquals("action", vm.uiState.value.notes.first { it.id == "a" }.status)
    }

    @Test fun `buildImplementationPrompt wraps body with a step-by-step instruction`() {
        val vm = newVm()
        val prompt = vm.buildImplementationPrompt(
            mk("a", title = "Add logout", body = "1. Add /logout route\n2. Clear cookies")
        )
        assertTrue(
            "Prompt should carry the body block. Was: $prompt",
            prompt.contains("1. Add /logout route") &&
                prompt.contains("2. Clear cookies"),
        )
        assertTrue("Prompt should carry the title.", prompt.contains("Add logout"))
        assertTrue("Prompt should ask for step-by-step reasoning.", prompt.contains("step by step"))
    }

    @Test fun `buildImplementationPrompt handles empty body but non-empty title`() {
        val vm = newVm()
        val prompt = vm.buildImplementationPrompt(mk("a", title = "Refactor login", body = ""))
        assertTrue(
            "Empty body should fall through to a plan note. Was: $prompt",
            prompt.contains("Refactor login"),
        )
        // With no body, the prompt tells the LLM to *describe* what it
        // would do rather than ask it to execute empty steps.
        assertTrue(prompt.contains("describe what you would do"))
    }

    @Test fun `NoteStatus displayFor maps each value to a glyph and label`() {
        assertEquals("💡", com.hermexapp.android.persistence.NoteStatus.displayFor("idea").glyph)
        assertEquals("Idea",
            com.hermexapp.android.persistence.NoteStatus.displayFor("idea").label)
        assertEquals("🧭", com.hermexapp.android.persistence.NoteStatus.displayFor("plan").glyph)
        assertEquals("Plan",
            com.hermexapp.android.persistence.NoteStatus.displayFor("plan").label)
        assertEquals("⚡", com.hermexapp.android.persistence.NoteStatus.displayFor("action").glyph)
        assertEquals("Action",
            com.hermexapp.android.persistence.NoteStatus.displayFor("action").label)
        // Unknown values should fall back to "idea" with a 💡 glyph so a
        // future schema-add never crashes the UI.
        assertEquals("💡", com.hermexapp.android.persistence.NoteStatus.displayFor("garbage").glyph)
    }

    @Test fun `NoteStatus ALL ordering suggests cycle direction`() {
        val all = com.hermexapp.android.persistence.NoteStatus.ALL
        // The editor chip cycles through ALL in order, so the sequence
        // should be readable left-to-right as "more actionable".
        assertEquals(listOf("idea", "plan", "action"), all)
    }
}
