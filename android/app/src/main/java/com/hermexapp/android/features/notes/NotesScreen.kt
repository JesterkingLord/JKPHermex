package com.hermexapp.android.features.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.persistence.NoteEntity
import com.hermexapp.android.persistence.NoteStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * NotesScreen — the operator-facing notes screen.
 *
 * State machine:
 * - [UiState.selectionMode] = false (default): single-tap a row to open
 *   the inline editor at the top of the screen; long-press enters
 *   selection-mode (Wave 7's BulkX bar pattern).
 * - [UiState.selectionMode] = true: rows show selection state; bottom
 *   bar offers bulk delete.
 *
 * Search bar (toggled by 🔍 icon in top-right): filters the visible
 * list against the query, leaves the store untouched.
 *
 * The FAB "Add" creates a fresh note (UUID assigned by the VM) and
 * pops the editor open with that note ready to type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onClose: () -> Unit,
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier,
    /**
     * Wave 9 (AI Notes): when the user taps 🤖 Implement on a note,
     * this callback fires with the resolved NoteEntity. The caller
     * (MainActivity) is expected to drop the user into a chat with
     * the composer pre-filled via [NotesViewModel.buildImplementationPrompt]
     * — though the VM build is here so the NotesScreen can keep its
     * own concerns tidy. When null, the 🤖 button is hidden so screens
     * that don't have a chat to drop into (e.g. the Settings variant)
     * don't render a dead-button affordance.
     */
    onImplementNote: ((NoteEntity) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    var editor by remember { mutableStateOf<EditorState>(EditorState.Closed) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // With the editor full-screen, back must close the note first. Leaving
    // Notes entirely from an open note would look like the edit was discarded.
    BackHandler(enabled = editor is EditorState.Open) { editor = EditorState.Closed }

    Scaffold(
        modifier = modifier.testTag("notes_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.selectionMode -> "${state.selection.size} selected"
                            editor is EditorState.Open -> "Note"
                            else -> "Notes"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (editor is EditorState.Open) editor = EditorState.Closed else onClose()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(
                            onClick = { showSearch = !showSearch },
                            modifier = Modifier.testTag("notes_search_toggle"),
                        ) {
                            Icon(
                                if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (showSearch) "Hide search" else "Show search",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selectionMode) {
                BulkNotesBar(
                    count = state.selection.size,
                    onDelete = {
                        val dropped = state.selection.size
                        viewModel.deleteSelected()
                        scope.launch { snackbarHost.showSnackbar("Deleted $dropped note${if (dropped != 1) "s" else ""}.") }
                    },
                )
            }
        },
        floatingActionButton = {
            // No "New note" button floating over a note you are already
            // writing — it belongs to the list.
            if (!state.selectionMode && editor !is EditorState.Open) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val id = viewModel.createEmptyNote()
                        editor = EditorState.Open(id)
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New note") },
                    modifier = Modifier.testTag("notes_fab"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
        val openEditor = editor as? EditorState.Open
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            AnimatedVisibility(visible = showSearch && openEditor == null) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search notes…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("notes_search_field"),
                )
            }

            // Open a note and it takes the screen, the way Keep does. It used
            // to be a card stacked above the list, so an open note competed
            // with a scrolling list of every other note — and until the row
            // was filtered out you saw the same note twice.
            (openEditor)?.let { open ->
                NoteEditor(
                    noteId = open.noteId,
                    viewModel = viewModel,
                    state = state,
                    onClose = { editor = EditorState.Closed },
                    onImplement = { note ->
                        viewModel.setStatus(note.id, NoteStatus.ACTION)
                        onImplementNote?.invoke(note)
                        editor = EditorState.Closed
                    },
                )
            }

            if (openEditor != null) return@Column

            when {
                state.isEmpty && !state.filterActive -> NotesEmpty(modifier = Modifier.fillMaxSize())
                state.isEmpty && state.filterActive -> NoSearchHits(
                    query = state.query,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> NotesList(
                    state = state,
                    viewModel = viewModel,
                    onEdit = { id -> editor = EditorState.Open(id) },
                    editingNoteId = (editor as? EditorState.Open)?.noteId,
                    onSwipeDelete = { id ->
                        // Capture the row BEFORE delete so we can restore on UNDO.
                        val deleted = state.notes.firstOrNull { it.id == id }
                        viewModel.delete(id)
                        scope.launch {
                            val result = snackbarHost.showSnackbar(
                                message = "Note deleted.",
                                actionLabel = "Undo",
                                withDismissAction = true,
                                duration = androidx.compose.material3.SnackbarDuration.Short,
                            )
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed
                                && deleted != null
                            ) {
                                viewModel.upsert(deleted)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Editor-state sealed type. Open(noteId) is the only shape for Wave 8.3;
 * future Wave 9.0 may add ReadOnly(noteId) for shared notes.
 */
private sealed class EditorState {
    data object Closed : EditorState()
    data class Open(val noteId: String) : EditorState()
}

/**
 * NoteEditor — the small two-field card (title + body) that appears at
 * the top of the screen when [editor] is Open. Saves are debounced into
 * the VM via upsert(NoteEntity).
 *
 * Wave 9: gained the status chip (cycle on tap) and a 🤖 Implement
 * button. The button becomes live when status=ACTION and routes
 * through [onImplement] so the caller can put the note body into a
 * chat composer. When [onImplement] is null the button is hidden —
 * places that don't have a chat to drop into can use this editor
 * without rendering a dead control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    noteId: String,
    viewModel: NotesViewModel,
    state: com.hermexapp.android.features.notes.NotesViewModel.UiState,
    onClose: () -> Unit,
    onImplement: (NoteEntity) -> Unit = {},
) {
    val existing = state.notes.firstOrNull { it.id == noteId }
    var title by remember(noteId) { mutableStateOf(existing?.title ?: "") }
    var body by remember(noteId) { mutableStateOf(existing?.body ?: "") }

    // The note exactly as it was when this editor opened. Every keystroke
    // autosaves, so without this snapshot there is nothing to go back to — a
    // mistyped edit is simply the note now. Captured once per note id, so it
    // survives recomposition but resets when a different note is opened.
    val opened = remember(noteId) { existing }
    val edited = noteHasUnsavedEdits(opened, title, body)
    val colorHex = existing?.colorHex ?: com.hermexapp.android.features.notes.NotesViewModel.DEFAULT_COLOR_HEX
    val status = existing?.status ?: NoteStatus.IDEA

    // No sync-back from the store while the editor is open.
    //
    // Every keystroke calls upsert(), the store then emits the saved note, and
    // this used to copy that value straight back into the field. Under normal
    // typing speed the write lands between two keystrokes and overwrites the
    // newer one, so characters are silently dropped: typing "working on EFURC
    // until it's finished" produced "wng on EFURC unio  fisher" on a real
    // device. That is data loss, not a cosmetic glitch.
    //
    // While the editor is open the field is the source of truth; the store is
    // downstream of it. `remember(noteId)` above already re-seeds the fields
    // when a different note is opened, which is the only moment the stored
    // value should win.

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("note_editor"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(colorHex)))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                TextFieldInline(
                    value = title,
                    onChange = {
                        title = it
                        viewModel.upsert(
                            NoteEntity(
                                id = noteId,
                                title = it,
                                body = body,
                                colorHex = colorHex,
                                pinned = existing?.pinned == true,
                                status = status,
                                updatedAtMillis = System.currentTimeMillis(),
                                createdAtMillis = existing?.createdAtMillis
                                    ?: System.currentTimeMillis(),
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = "Title",
                    singleLine = true,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("note_editor_close"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close editor")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextFieldInline(
                value = body,
                onChange = {
                    body = it
                    viewModel.upsert(
                        NoteEntity(
                            id = noteId,
                            title = title,
                            body = it,
                            colorHex = colorHex,
                            pinned = existing?.pinned == true,
                            status = status,
                            updatedAtMillis = System.currentTimeMillis(),
                            createdAtMillis = existing?.createdAtMillis
                                ?: System.currentTimeMillis(),
                        )
                    )
                },
                placeholder = "Start typing…",
                singleLine = false,
                fontWeight = FontWeight.Normal,
            )
            // Its own line, not the status row: that row already holds the
            // status chip and the Implement button, and a third control
            // squeezed "Mark action first" into a circle with its label
            // wrapped across three lines.
            //
            // Only offered once something actually changed, so it is not a
            // permanently lit button that usually does nothing.
            if (edited && opened != null) {
                TextButton(
                    onClick = {
                        title = opened.title
                        body = opened.body
                        viewModel.upsert(opened)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("note_editor_revert"),
                ) { Text("Undo edits") }
            }

            // Wave 9: status chip + 🤖 Implement button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteStatusChip(
                    currentStatus = status,
                    onCycle = { next ->
                        viewModel.setStatus(noteId, next)
                    },
                )
                Spacer(Modifier.weight(1f))
                val canImplement = existing != null
                Button(
                    onClick = { existing?.let(onImplement) },
                    enabled = canImplement,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.testTag("note_editor_implement"),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (status) {
                            NoteStatus.ACTION -> "🤖 Implement"
                            else -> "Mark action first"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Wave 9: tap-to-cycle status chip.
 *
 * The user is most likely going to flip through IDEA → PLAN → ACTION
 * sequentially, so tap cycles to the next stage; long-press opens a
 * picker. We only show the cycle (not the long-press) for now — the
 * picker is reserved for a future wave where we'd have many statuses
 * to choose from. Three statuses is fine to cycle through.
 */
@Composable
private fun NoteStatusChip(
    currentStatus: String,
    onCycle: (String) -> Unit,
) {
    val display = NoteStatus.displayFor(currentStatus)
    val next = NoteStatus.ALL.let { all ->
        val idx = all.indexOf(currentStatus).coerceAtLeast(0)
        all[(idx + 1) % all.size]
    }
    AssistChip(
        onClick = { onCycle(next) },
        label = {
            Text(
                "${display.glyph} ${display.label}",
                fontWeight = FontWeight.SemiBold,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = when (currentStatus) {
                NoteStatus.ACTION -> MaterialTheme.colorScheme.primary
                NoteStatus.PLAN -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            labelColor = when (currentStatus) {
                NoteStatus.ACTION -> MaterialTheme.colorScheme.onPrimary
                NoteStatus.PLAN -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = Modifier.testTag("note_status_chip_$currentStatus"),
    )
}

@Composable
private fun TextFieldInline(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = fontWeight,
            fontSize = if (fontWeight == FontWeight.SemiBold) 18.sp else 14.sp,
        ),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

@Composable
private fun NotesEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("No notes yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap New note to start one. Notes live in the app; they don't sync to the server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoSearchHits(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("No notes match \"$query\"", style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesList(
    state: NotesViewModel.UiState,
    viewModel: NotesViewModel,
    onSwipeDelete: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The note currently open in the editor, hidden from the list below it. */
    editingNoteId: String? = null,
) {
    LazyColumn(
        modifier = modifier.testTag("notes_list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The open note is already on screen as the editor card directly
        // above. Leaving it in the list too showed the same note twice, with
        // two different renderings of the text you were typing.
        val rows = state.notes.filterNot { it.id == editingNoteId }
        val pinnedRows = rows.filter { it.pinned }
        val otherRows = rows.filterNot { it.pinned }

        if (showSectionHeaders(pinnedRows.size, otherRows.size)) {
            item(key = "hdr_pinned") { NotesSectionHeader("Pinned") }
        }
        items(pinnedRows, key = { it.id }) { note ->
            NoteRow(
                note = note,
                selected = note.id in state.selection,
                selectionMode = state.selectionMode,
                onClick = {
                    // Tapping a note opens it. Previously this was a no-op
                    // outside selection mode and the FAB only created new
                    // notes, which left an existing note with no route to the
                    // editor at all — the note could be read, pinned, or
                    // deleted, but never edited.
                    if (state.selectionMode) viewModel.toggleSelection(note.id) else onEdit(note.id)
                },
                onLongPress = { viewModel.toggleSelection(note.id) },
                onTogglePin = { viewModel.togglePinned(note.id, note.pinned) },
                onSwipeDelete = { onSwipeDelete(note.id) },
            )
        }

        if (showSectionHeaders(pinnedRows.size, otherRows.size)) {
            item(key = "hdr_others") { NotesSectionHeader("Others") }
        }
        items(otherRows, key = { it.id }) { note ->
            NoteRow(
                note = note,
                selected = note.id in state.selection,
                selectionMode = state.selectionMode,
                onClick = {
                    // Tapping a note opens it. Previously this was a no-op
                    // outside selection mode and the FAB only created new
                    // notes, which left an existing note with no route to the
                    // editor at all — the note could be read, pinned, or
                    // deleted, but never edited.
                    if (state.selectionMode) viewModel.toggleSelection(note.id) else onEdit(note.id)
                },
                onLongPress = { viewModel.toggleSelection(note.id) },
                onTogglePin = { viewModel.togglePinned(note.id, note.pinned) },
                onSwipeDelete = { onSwipeDelete(note.id) },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: NoteEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete()
                true
            } else false
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        modifier = Modifier.testTag("note_row_${note.id}"),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickableSafe(onClick, onLongPress),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(note.colorHex)))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val displayTitle = note.title.ifBlank { "(untitled)" }
                    Text(
                        displayTitle,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (note.body.isNotBlank()) {
                        Text(
                            note.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatRelative(note.updatedAtMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Wave 9: small status badge so users can tell at a
                        // glance which of their notes are *actionable* (🤖)
                        // vs. plans (🧭) vs. ideas (💡).
                        if (!selectionMode && note.status != NoteStatus.IDEA) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                NoteStatus.displayFor(note.status).glyph,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (!selectionMode && note.pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * Modifier extension that detects both click and long-click for the row.
 * Built on top of [androidx.compose.foundation.combinedClickable]; kept
 * as a tiny inline helper so each NoteRow caller stays terse.
 */
@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
private fun Modifier.combinedClickableSafe(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
    )

@Composable
private fun BulkNotesBar(
    count: Int,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count selected",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onDelete, enabled = count > 0) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Delete")
        }
    }
}

private fun formatRelative(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Just now"
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "Just now"
        diff < hour -> "${diff / minute} min ago"
        diff < day -> "${diff / hour}h ago"
        diff < 7 * day -> "${diff / day}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
    }
}

/**
 * Whether the list should label its two groups.
 *
 * Pinned notes already sort to the top, but with no label the boundary is
 * invisible — the list just looks arbitrarily ordered. Headers only earn their
 * space when both groups exist: an all-pinned or all-unpinned list would show
 * a single header over everything, which says nothing.
 */
internal fun showSectionHeaders(pinnedCount: Int, otherCount: Int): Boolean =
    pinnedCount > 0 && otherCount > 0

@Composable
private fun NotesSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * Whether the editor holds changes that differ from the note it opened with.
 *
 * The editor autosaves on every keystroke, so "unsaved" is the wrong word for
 * what this detects — the store is already updated. What it answers is whether
 * there is an earlier version worth offering back. A brand-new note (no
 * [opened]) has nothing to revert to.
 */
internal fun noteHasUnsavedEdits(
    opened: com.hermexapp.android.persistence.NoteEntity?,
    title: String,
    body: String,
): Boolean {
    if (opened == null) return false
    return opened.title != title || opened.body != body
}
