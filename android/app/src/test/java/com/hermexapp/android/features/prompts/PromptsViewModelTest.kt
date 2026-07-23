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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [PromptsViewModel]. Mirrors NotesViewModelTest
 * but exercises the prompt-only behavior (lastInsertedBody, bumpUsage).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptsViewModelTest {

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

    private fun mk(
        id: String,
        name: String = id,
        body: String = "body of $id",
        tags: String = "",
        pinned: Boolean = false,
        usageCount: Int = 0,
    ) = PromptEntity(
        id = id, name = name, body = body, tags = tags, pinned = pinned,
        usageCount = usageCount,
        updatedAtMillis = now, createdAtMillis = now,
    )

    @Test fun `empty store yields empty UiState`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.prompts.isEmpty())
        assertEquals(0, s.totalPrompts)
        assertFalse(s.selectionMode)
    }

    @Test fun `upsert then load shows the prompt`() = runTest(dispatcher) {
        val vm = newVm()
        now = 2000L
        vm.upsert(mk("a", name = "alpha", body = "do a thing"))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.totalPrompts)
        assertEquals("alpha", vm.uiState.value.prompts.first().name)
    }

    @Test fun `setQuery filters prompts by name body and tags`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", name = "summary",   body = "x", tags = ""))
        now = 200; vm.upsert(mk("b", name = "rewrite",   body = "y", tags = ""))
        now = 300; vm.upsert(mk("c", name = "translate", body = "z", tags = "lang"))
        advanceUntilIdle()
        vm.setQuery("lan")
        advanceUntilIdle()
        // "translate" matches via tag "lang"; the others don't.
        assertEquals(listOf("c"), vm.uiState.value.prompts.map { it.id })
    }

    @Test fun `setQuery blank restores full list`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a"))
        now = 200; vm.upsert(mk("b"))
        advanceUntilIdle()
        vm.setQuery("body of b")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.prompts.size)
        vm.setQuery("")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.prompts.size)
    }

    @Test fun `requestInsert publishes body and bumps usage`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", body = "hello {{name}}", usageCount = 0))
        advanceUntilIdle()
        now = 200
        vm.requestInsert(vm.uiState.value.prompts.first())
        advanceUntilIdle()
        assertEquals("hello {{name}}", vm.lastInsertedBody.value)
        assertEquals(1, vm.uiState.value.prompts.first().usageCount)
    }

    @Test fun `acknowledgeInsert clears the last-inserted body`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", body = "hi"))
        advanceUntilIdle()
        vm.requestInsert(vm.uiState.value.prompts.first())
        advanceUntilIdle()
        assertNotNull(vm.lastInsertedBody.value)
        vm.acknowledgeInsert()
        advanceUntilIdle()
        assertNull(vm.lastInsertedBody.value)
    }

    @Test fun `togglePinned reflects the inversion`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a", pinned = false))
        advanceUntilIdle()
        vm.togglePinned("a", currentPinned = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.prompts.first { it.id == "a" }.pinned)
    }

    @Test fun `delete drops from store and from selection`() = runTest(dispatcher) {
        val vm = newVm()
        now = 100; vm.upsert(mk("a"))
        advanceUntilIdle()
        vm.toggleSelection("a")
        advanceUntilIdle()
        vm.delete("a")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.prompts.isEmpty())
        assertFalse(vm.uiState.value.selectionMode)
    }

    @Test fun `deleteSelected removes every selected prompt in one call`() = runTest(dispatcher) {
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
        assertEquals(listOf("b"), vm.uiState.value.prompts.map { it.id })
    }

    @Test fun `createBlank returns a UUID id then materialises into the store`() = runTest(dispatcher) {
        val vm = newVm()
        val id = vm.createBlank()
        advanceUntilIdle()
        assertTrue(id.isNotBlank())
        assertNotNull(vm.uiState.value.prompts.firstOrNull { it.id == id })
    }
}
