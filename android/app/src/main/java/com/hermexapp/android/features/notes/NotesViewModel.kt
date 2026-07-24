package com.hermexapp.android.features.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.persistence.NoteEntity
import com.hermexapp.android.persistence.NoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * NotesViewModel — keeps the [UiState] in sync with [NoteStore] and
 * exposes the four CRUD verbs the [NotesScreen] needs:
 * upsert / setPinned / delete / search.
 *
 * Search is layered ON TOP of the store's live Flow; the VM owns the
 * current query string. An empty query returns the full list verbatim,
 * a non-empty query filters by title-or-body substring match.
 *
 * Why a StateFlow with combine(): the search box text must update the
 * list instantly as the user types, but the underlying store changes
 * on a different cadence (creates, edits). StateFlow + combine gives
 * us a single "what to render" stream that re-fires on either input.
 *
 * @param store the storage seam; tests pass an [InMemoryNoteStore].
 * @param clock epoch-ms supplier; lets tests pin timestamps.
 */
class NotesViewModel(
    private val store: NoteStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    /** Mutable search query — kept here so process death + VM rebirth work. */
    private val query = MutableStateFlow("")

    /** Selection state for bulk-delete (Phase 8.x; for now read-only). */
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<UiState> = combine(
        store.observeAll(),
        query,
        selection,
    ) { notes, q, sel ->
        val filtered = if (q.isBlank()) notes
        else notes.filter { n ->
            n.title.contains(q, ignoreCase = true) ||
                n.body.contains(q, ignoreCase = true) ||
                n.colorHex.contains(q, ignoreCase = true)
        }
        UiState(
            notes = filtered,
            totalNotes = notes.size,
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

    fun createEmptyNote(): String {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            store.upsert(
                NoteEntity(
                    id = id,
                    title = "",
                    body = "",
                    colorHex = DEFAULT_COLOR_HEX,
                    pinned = false,
                    updatedAtMillis = clock(),
                    createdAtMillis = clock(),
                )
            )
        }
        return id
    }

    fun upsert(note: NoteEntity) {
        viewModelScope.launch {
            store.upsert(
                note.copy(updatedAtMillis = clock())
            )
        }
    }

    fun togglePinned(id: String, currentPinned: Boolean) {
        viewModelScope.launch { store.setPinned(id, !currentPinned) }
    }

    /**
     * Wave 9: cycle the note's lifecycle stage.
     *
     * The editor exposes a tap-to-cycle chip — IDEA → PLAN → ACTION →
     * IDEA. Long-pressing the chip opens a picker for explicit selection.
     * Set is delegated straight through so the store owns the timestamp;
     * the VM doesn't double-bookkeep the value.
     */
    fun setStatus(id: String, status: String) {
        viewModelScope.launch { store.setStatus(id, status) }
    }

    /**
     * Wave 9: build the "implement this note" prompt we pre-fill the
     * chat composer with when the user taps 🤖 Implement on a note.
     * Returns a single string the caller drops into the composer (and
     * optionally auto-sends).
     */
    fun buildImplementationPrompt(note: NoteEntity): String {
        val title = note.title.takeIf { it.isNotBlank() }
        val body = note.body.takeIf { it.isNotBlank() }
        val header = if (title != null) "# ${title.trim()}\n\n" else ""
        val bodyBlock = body?.trim().orEmpty()
        return if (bodyBlock.isBlank()) {
            "Follow this note (titled \"${title.orEmpty().ifBlank { "Untitled" }}\"):\n\n${header}— describe what you would do, step by step."
        } else {
            "Implement this note step by step. Follow each instruction exactly. " +
                "Show your reasoning and ship clean diffs:\n\n${header}${bodyBlock}"
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            // Dropping the row from selection clears the modal state too.
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
     * UiState is a plain @Immutable-by-convention data class; the StateFlow
     * already diffs emissions for Compose, so a stable surface is what
     * callers get.
     */
    data class UiState(
        val notes: List<NoteEntity>,
        val totalNotes: Int,
        val query: String,
        val selectionMode: Boolean,
        val selection: Set<String>,
    ) {
        val isEmpty: Boolean get() = notes.isEmpty()
        val filterActive: Boolean get() = query.isNotBlank()
    }

    companion object {
        /**
         * Default note color: a soft yellow swatch matching the
         * Hermex default accent (GOLD). Operators can override per-note.
         */
        const val DEFAULT_COLOR_HEX = "#FFE9A2"
    }

    /**
     * Factory that ties a [NoteStore] (real or test) to a [NotesViewModel].
     * Constructed once per [NotesScreen] entry so a single VM survives
     * drawer toggle/close (Process lifecycle) without re-fetching.
     */
    class Factory(private val store: NoteStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NotesViewModel::class.java)) {
                "NotesViewModel.Factory cannot build $modelClass"
            }
            return NotesViewModel(store) as T
        }
    }
}
