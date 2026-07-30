package com.hermexapp.android.features.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.persistence.NoteStore
import com.hermexapp.android.persistence.PromptStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * InsertPaletteViewModel — backs the chat-composer's "insert from
 * note or prompt" sheet.
 *
 * Aggregates the prompt library + note library into one UiState so
 * the sheet can render recent / pinned / matching items across both
 * stores without doing two flows-by-coordinate dance moves in the
 * composable.
 *
 * Search is a single substring that scans both prompts and notes. The
 * composable renders the union sorted by recency (newest first),
 * with pinned always at the top.
 *
 * @param noteStore the notes seam (live).
 * @param promptStore the prompts seam (live).
 */
class InsertPaletteViewModel(
    private val noteStore: NoteStore,
    private val promptStore: PromptStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    // Wave 9: a typed "kind" filter. The composer's chip rail sets this
    // before opening the palette so "From note" only shows notes, "From
    // prompt" only shows prompts, and long-press send shows the union.
    private val filter = MutableStateFlow(Filter.ALL)

    val uiState: StateFlow<UiState> = combine(
        noteStore.observeAll(),
        promptStore.observeAll(),
        query,
        filter,
    ) { notes, prompts, q, f ->
        val itemPrompts = if (f != Filter.NOTES) prompts.map { InsertItem.Prompt(it) } else emptyList()
        val itemNotes = if (f != Filter.PROMPTS) notes.map { InsertItem.Note(it) } else emptyList()
        val all = (itemPrompts + itemNotes)
            .sortedWith(
                compareByDescending<InsertItem> { it.isPinned }
                    .thenByDescending { it.recentMillis }
            )
        val filtered = if (q.isBlank()) all
        else all.filter { it.matches(q) }
        UiState(items = filtered, totalCount = all.size, query = q, filter = f)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState(emptyList(), 0, "", Filter.ALL),
    )

    fun setQuery(q: String) { query.value = q }

    /** Narrow which subset of items the palette displays. ALL is the default. */
    fun setFilter(f: Filter) { filter.value = f }
    val currentFilter: Filter get() = filter.value

    /**
     * UiState — one card at a time. Empty list = the empty state.
     */
    data class UiState(
        val items: List<InsertItem>,
        val totalCount: Int,
        val query: String,
        val filter: Filter,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
        val hasUnfilteredResults: Boolean get() = totalCount > 0
        val filterActive: Boolean get() = query.isNotBlank()
    }

    /**
     * Filter — which subset of [InsertItem] the palette shows. ALL is the
     * default for the long-press-send palette; the chip rails in the
     * composer narrow it down to one type.
     */
    enum class Filter { ALL, NOTES, PROMPTS }

    class Factory(
        private val noteStore: NoteStore,
        private val promptStore: PromptStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InsertPaletteViewModel::class.java)) {
                "InsertPaletteViewModel.Factory cannot build $modelClass"
            }
            return InsertPaletteViewModel(noteStore, promptStore) as T
        }
    }
}

/**
 * InsertItem — a sealed wrapper that flattens prompts + notes into
 * one renderable shape. The sheet is one composable, the model is
 * two stores; the wrapper bridges them.
 */
sealed class InsertItem {
    abstract val id: String
    abstract val title: String
    abstract val body: String
    abstract val recentMillis: Long
    abstract val isPinned: Boolean
    abstract fun matches(query: String): Boolean

    data class Prompt(val source: com.hermexapp.android.persistence.PromptEntity) : InsertItem() {
        override val id: String = "prompt:${source.id}"
        override val title: String = source.name.ifBlank { source.body.take(40) }
        override val body: String = source.body
        override val recentMillis: Long = source.updatedAtMillis
        override val isPinned: Boolean = source.pinned
        override fun matches(query: String): Boolean =
            source.name.contains(query, ignoreCase = true) ||
                source.body.contains(query, ignoreCase = true) ||
                source.tags.contains(query, ignoreCase = true)
    }

    data class Note(val source: com.hermexapp.android.persistence.NoteEntity) : InsertItem() {
        override val id: String = "note:${source.id}"
        override val title: String = source.title.ifBlank { source.body.take(40).ifBlank { "(empty note)" } }
        override val body: String = source.body
        override val recentMillis: Long = source.updatedAtMillis
        override val isPinned: Boolean = source.pinned
        override fun matches(query: String): Boolean =
            source.title.contains(query, ignoreCase = true) ||
                source.body.contains(query, ignoreCase = true)
    }
}
