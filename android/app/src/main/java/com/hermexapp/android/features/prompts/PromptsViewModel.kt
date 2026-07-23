package com.hermexapp.android.features.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.persistence.PromptEntity
import com.hermexapp.android.persistence.PromptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * PromptsViewModel — the ViewModel backing [PromptsScreen].
 *
 * Mirrors [com.hermexapp.android.features.notes.NotesViewModel] in shape
 * but adds [bumpUsage] so the list can re-order by recency when a
 * prompt is inserted into the chat composer.
 *
 * Insertion: the screen never reaches the chat composer directly — the
 * VM exposes a [lastInsertedBody] StateFlow the parent subscribes to.
 * When the user taps "Insert" inside the prompt editor, the screen
 * updates [lastInsertedBody]; an external collector (e.g. ChatComposer)
 * listens and on receipt clears it back to null. This pattern keeps
 * the ViewModel UI-framework-agnostic — no Compose lambdas leak into
 * the VM, and tests don't need a Compose runtime.
 *
 * @param store the storage seam.
 * @param clock epoch-ms supplier.
 */
class PromptsViewModel(
    private val store: PromptStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    /**
     * One-shot "insert this prompt body into the chat composer" event.
     * The screen sets this when the user taps Insert; the chat
     * composer's `LaunchedEffect`-style collector reads + clears.
     *
     * Held as a regular MutableStateFlow (not SharedFlow) for simplicity
     * — the chat composer and prompts screen are both attached to the
     * same VM lifecycle in MainActivity.
     */
    private val _lastInsertedBody = MutableStateFlow<String?>(null)
    val lastInsertedBody: StateFlow<String?> get() = _lastInsertedBody

    val uiState: StateFlow<UiState> = combine(
        store.observeAll(),
        query,
        selection,
    ) { prompts, q, sel ->
        val filtered = if (q.isBlank()) prompts
        else prompts.filter { p ->
            p.name.contains(q, ignoreCase = true) ||
                p.body.contains(q, ignoreCase = true) ||
                p.tags.contains(q, ignoreCase = true)
        }
        UiState(
            prompts = filtered,
            totalPrompts = prompts.size,
            query = q,
            selectionMode = sel.isNotEmpty(),
            selection = sel,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState(emptyList(), 0, "", false, emptySet()),
    )

    fun setQuery(q: String) { query.value = q }
    fun toggleSelection(id: String) {
        val cur = selection.value.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        selection.value = cur
    }
    fun clearSelection() { selection.value = emptySet() }

    fun createBlank(): String {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            store.upsert(
                PromptEntity(
                    id = id,
                    name = "",
                    body = "",
                    tags = "",
                    pinned = false,
                    usageCount = 0,
                    updatedAtMillis = clock(),
                    createdAtMillis = clock(),
                )
            )
        }
        return id
    }

    fun upsert(prompt: PromptEntity) {
        viewModelScope.launch { store.upsert(prompt.copy(updatedAtMillis = clock())) }
    }

    fun togglePinned(id: String, currentPinned: Boolean) {
        viewModelScope.launch { store.setPinned(id, !currentPinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            val cur = selection.value.toMutableSet()
            if (id in cur) {
                cur.remove(id)
                selection.value = cur
            }
        }
    }

    fun deleteSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { store.delete(it) }
            selection.value = emptySet()
        }
    }

    /**
     * Public API for the prompt editor's Insert button. The prompt body
     * is published through [lastInsertedBody] and the usage counter is
     * incremented so the list re-orders (most-used floats up).
     */
    fun requestInsert(prompt: PromptEntity) {
        viewModelScope.launch {
            store.bumpUsage(prompt.id)
            _lastInsertedBody.value = prompt.body
        }
    }

    /**
     * Called by the chat composer's collector after consuming the body.
     * Idempotent — clearing null is a no-op so callers can fire-and-forget.
     */
    fun acknowledgeInsert() { _lastInsertedBody.value = null }

    data class UiState(
        val prompts: List<PromptEntity>,
        val totalPrompts: Int,
        val query: String,
        val selectionMode: Boolean,
        val selection: Set<String>,
    ) {
        val isEmpty: Boolean get() = prompts.isEmpty()
        val filterActive: Boolean get() = query.isNotBlank()
    }

    /**
     * Identity factory — same shape as the Notes one. Kept independent
     * so a future Profile screen can drop in a custom store without
     * smuggling through a generic interface.
     */
    class Factory(private val store: PromptStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PromptsViewModel::class.java)) {
                "PromptsViewModel.Factory cannot build $modelClass"
            }
            return PromptsViewModel(store) as T
        }
    }
}
