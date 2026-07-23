package com.hermexapp.android.features.notes

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
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
) {
    val state by viewModel.uiState.collectAsState()
    var editor by remember { mutableStateOf<EditorState>(EditorState.Closed) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.testTag("notes_screen"),
        topBar = {
            TopAppBar(
                title = { Text(if (state.selectionMode) "${state.selection.size} selected" else "Notes") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
            if (!state.selectionMode) {
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
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            AnimatedVisibility(visible = showSearch) {
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

            // The editor is rendered at the top of the list when open.
            // It's a self-contained card above the LazyColumn — collapsing
            // it sets editor = Closed.
            (editor as? EditorState.Open)?.let { open ->
                NoteEditor(
                    noteId = open.noteId,
                    viewModel = viewModel,
                    state = state,
                    onClose = { editor = EditorState.Closed },
                )
            }

            when {
                state.isEmpty && !state.filterActive -> NotesEmpty(modifier = Modifier.fillMaxSize())
                state.isEmpty && state.filterActive -> NoSearchHits(
                    query = state.query,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> NotesList(
                    state = state,
                    viewModel = viewModel,
                    onSwipeDelete = { id ->
                        viewModel.delete(id)
                        scope.launch { snackbarHost.showSnackbar("Note deleted.") }
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    noteId: String,
    viewModel: NotesViewModel,
    state: com.hermexapp.android.features.notes.NotesViewModel.UiState,
    onClose: () -> Unit,
) {
    val existing = state.notes.firstOrNull { it.id == noteId }
    var title by remember(noteId) { mutableStateOf(existing?.title ?: "") }
    var body by remember(noteId) { mutableStateOf(existing?.body ?: "") }
    val colorHex = existing?.colorHex ?: com.hermexapp.android.features.notes.NotesViewModel.DEFAULT_COLOR_HEX

    // Whenever the backing store changes (other edits, refresh), refresh
    // local field state ONLY when it differs — otherwise we'd overwrite
    // the user's in-flight keystrokes.
    LaunchedEffectIfChanged(existing?.title) { title = existing?.title.orEmpty() }
    LaunchedEffectIfChanged(existing?.body) { body = existing?.body.orEmpty() }

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
        }
    }
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

/**
 * LaunchedEffect that re-runs only when [key] changes (compared with
 * Equals), used to pull updated store values into editor-local state
 * without clobbering keystrokes.
 */
@Composable
private fun LaunchedEffectIfChanged(key: Any?, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("notes_list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.notes, key = { it.id }) { note ->
            NoteRow(
                note = note,
                selected = note.id in state.selection,
                selectionMode = state.selectionMode,
                onClick = {
                    if (state.selectionMode) viewModel.toggleSelection(note.id)
                    // No-op otherwise — wave 8.3 doesn't open the editor on
                    // row tap (the editor is opened via the FAB or the
                    // permanent editor when an existing note is being
                    // edited).
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
                    Text(
                        formatRelative(note.updatedAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
