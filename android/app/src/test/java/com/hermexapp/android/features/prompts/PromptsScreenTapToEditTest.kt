package com.hermexapp.android.features.prompts

import com.hermexapp.android.persistence.InMemoryPromptStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the tap-to-edit flow at the ViewModel seam.
 *
 * The screen's row tap opens the inline editor for a prompt id; the
 * editor then loads that prompt and upserts edits back through the
 * same [PromptsViewModel.upsert]. These tests pin that seam:
 * createBlank() returns a non-empty id (so a row tap has something to
 * open), and upserting an edited entity by id persists the changes
 * the editor's "Save & close" button would write.
 *
 * The Compose row itself is intentionally not exercised here — this
 * slice's contract is the id → open → upsert path, which is fully
 * covered in pure JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptsScreenTapToEditTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryPromptStore
    private var now: Long = 1_000L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = InMemoryPromptStore(clock = { now })
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = PromptsViewModel(store, clock = { now })

    @Test fun `createBlank returns a non-empty id that a row tap can open`() = runTest(dispatcher) {
        val vm = newVm()
        val id = vm.createBlank()
        advanceUntilIdle()
        assertTrue("row tap needs a real id to open the editor", id.isNotBlank())
        assertNotNull(vm.uiState.value.prompts.firstOrNull { it.id == id })
    }

    @Test fun `editing an existing prompt by id persists like the editor save does`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100L
        vm.upsert(
            PromptEntity(
                id = "p1", name = "old name", body = "old body", tags = "",
                pinned = false, usageCount = 0, updatedAtMillis = now, createdAtMillis = now,
            )
        )
        advanceUntilIdle()

        // Tap-to-edit: the row tap resolves the prompt by id, then the
        // editor upserts the edited entity on keystroke / on save.
        val opened = vm.uiState.value.prompts.firstOrNull { it.id == "p1" }
        assertNotNull(opened)

        now = 200L
        vm.upsert(opened!!.copy(name = "new name", body = "new body {{x}}", tags = "writing"))
        advanceUntilIdle()

        val saved = vm.uiState.value.prompts.first { it.id == "p1" }
        assertEquals("new name", saved.name)
        assertEquals("new body {{x}}", saved.body)
        assertEquals("writing", saved.tags)
        assertFalse(saved.pinned)
    }

    @Test fun `save and close keeps the edited prompt in the list`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100L
        val id = vm.createBlank()
        advanceUntilIdle()

        // FAB path: blank prompt exists, user types a name + body, then
        // taps "Save & close" (which upserts the current state and closes).
        val blank = vm.uiState.value.prompts.first { it.id == id }
        now = 200L
        vm.upsert(blank.copy(name = "Summarize article", body = "Summarize this: {{text}}"))
        advanceUntilIdle()

        val saved = vm.uiState.value.prompts.first { it.id == id }
        assertEquals("Summarize article", saved.name)
        assertEquals("Summarize this: {{text}}", saved.body)
        assertEquals(1, vm.uiState.value.totalPrompts)
    }
}
