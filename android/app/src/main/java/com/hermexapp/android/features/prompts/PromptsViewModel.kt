package com.hermexapp.android.features.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.persistence.PromptEntity
import com.hermexapp.android.persistence.PromptStore
import com.hermexapp.android.persistence.SentPrompt
import com.hermexapp.android.persistence.SentPromptsStore
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
    /**
     * Prompts the operator has actually sent. Optional so the screen still
     * works without a history attached (previews, tests).
     */
    private val sentPrompts: SentPromptsStore? = null,
) : ViewModel() {

    /** Recently sent prompts, newest first, that are not already saved. */
    val recentlySent: StateFlow<List<SentPrompt>> =
        sentPrompts?.history ?: MutableStateFlow(emptyList())

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

    /**
     * Promotes a prompt the operator already sent into the saved library.
     *
     * The name is seeded from the first line so the row is recognisable
     * immediately; the operator can rename it in the editor. The history entry
     * is dropped afterwards — it now lives in the library, and showing it in
     * both places would just be the same prompt twice.
     */
    fun saveSentToLibrary(entry: SentPrompt): String {
        val id = UUID.randomUUID().toString()
        val firstLine = entry.body.lineSequence().firstOrNull()?.trim().orEmpty()
        viewModelScope.launch {
            store.upsert(
                PromptEntity(
                    id = id,
                    name = firstLine.take(NAME_SEED_MAX_CHARS).ifBlank { "Saved prompt" },
                    body = entry.body,
                    tags = "",
                    pinned = false,
                    usageCount = entry.timesSent,
                    updatedAtMillis = clock(),
                    createdAtMillis = clock(),
                )
            )
        }
        sentPrompts?.forget(entry.body)
        return id
    }

    /** Drops a sent prompt from the history without saving it. */
    fun forgetSent(entry: SentPrompt) {
        sentPrompts?.forget(entry.body)
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
    class Factory(
        private val store: PromptStore,
        private val sentPrompts: SentPromptsStore? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PromptsViewModel::class.java)) {
                "PromptsViewModel.Factory cannot build $modelClass"
            }
            return PromptsViewModel(store, sentPrompts = sentPrompts) as T
        }
    }

    private companion object {
        /** Enough of the first line to recognise the prompt in a list row. */
        const val NAME_SEED_MAX_CHARS = 60
    }
}
