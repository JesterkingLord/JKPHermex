package com.hermexapp.android.features.sessionlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermexapp.android.model.Project
import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.ui.CircleButton
import com.hermexapp.android.ui.FastScrollbar
import com.hermexapp.android.ui.HermexPickerSheet
import com.hermexapp.android.ui.HermexWordmark
import com.hermexapp.android.ui.PickerRow
import com.hermexapp.android.ui.PickerSection
import com.hermexapp.android.ui.relativeTimeAgo
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.launch

/**
 * The iOS home screen: HERMEX wordmark, panel menu rows, a "Sessions" section
 * with relative timestamps, and the floating "✎ Chat" pill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onOpenSession: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val palette = LocalHermexPalette.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Excellence v1 Wave 1: LazyListState for the FastScrollbar drag
    // gestures. The session list is the scrollbar's first consumer;
    // Wave 2 reuses the same state pattern for the chat timeline.
    val listState = rememberLazyListState()
    // Letter-jump map for the Gmail-style rail. Recompute only when the
    // session list itself changes (cheap; O(N) on list mutation but the
    // list rarely exceeds ~100 rows in practice).
    val letterIndex: Map<Char, Int> = remember(state.sessions) {
        // Reduce titles to first-letter bucket indexes. `buildList`+Pair
        // was wrong because Pair doesn't have `.key`; just use a
        // MutableMap directly so `putIfAbsent` does exactly what we want.
        val out = sortedMapOf<Char, Int>()
        for ((idx, title) in state.sessions.withIndex()) {
            val trimmed = title.title?.trim()?.takeIf(String::isNotEmpty) ?: continue
            val first = trimmed.first().uppercaseChar()
            if (first.isLetter()) out.putIfAbsent(first, idx)
        }
        out
    }
    var searchVisible by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var moveTarget by remember { mutableStateOf<SessionSummary?>(null) }
    /** Confirm-only state: holds the session-id list we are about to bulk-delete.
     *  Different from [deleteTarget] which is for the single-row confirm flow. */
    var bulkDeleteOpen by remember { mutableStateOf(false) }

    // Excellence v1 Wave 0: snackbar event collector. We launch a single
    // long-lived collection so each VM-emitted event fires exactly one snackbar.
    // Selection-mode action snackbars are filtered out (the toolbar already
    // shows "Deleting..." progress — duplicating in snackbar is noise).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (state.selectionMode) return@collect
            val message = SessionListSnackbar.messageFor(event) ?: return@collect
            val duration = when (event) {
                is SessionListEvent.ActionError -> SessionListSnackbar.ERROR_DURATION
                else -> SessionListSnackbar.SUCCESS_DURATION
            }
            // showSnackbar suspends until the snackbar is dismissed; we don't
            // need to await its result here (we offer no UNDO action).
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = duration,
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { viewModel.createSessionNow()?.let(onOpenSession) }
                },
                color = palette.pillBackground,
                contentColor = palette.pillForeground,
                shape = CircleShape,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✎", fontWeight = FontWeight.Bold)
                    Text("Chat", style = MaterialTheme.typography.titleSmall, color = palette.pillForeground)
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
            ) {
            // Excellence v1 Wave 0: contextual action bar takes the header row
            // when bulk-select mode is active. Otherwise the existing wordmark +
            // search + settings row stays put. (Replaces the previous single
            // Row block; behaviour otherwise identical.)
            if (state.selectionMode) {
                item(key = "bulk-bar") {
                    BulkSessionActionsBar(
                        selectedCount = state.selectedIds.size,
                        totalVisible = state.sessions.count { it.sessionId != null },
                        onCancel = { viewModel.clearSelection() },
                        onToggleSelectAll = {
                            if (state.selectedIds.size == state.sessions.count { it.sessionId != null }) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectAllVisible()
                            }
                        },
                        onPin = {
                            viewModel.pinSessions(state.selectedIds.toList(), pinned = true)
                            viewModel.clearSelection()
                        },
                        onArchive = {
                            // Use archive=true for the bulk path; mixed state
                            // is rare and the server's archive endpoint is
                            // idempotent on idempotent=true.
                            viewModel.archiveSessions(state.selectedIds.toList(), archived = true)
                            viewModel.clearSelection()
                        },
                        onDelete = {
                            bulkDeleteOpen = true
                        },
                    )
                }
            } else {
                item(key = "wordmark-row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HermexWordmark()
                        Spacer(Modifier.weight(1f))
                        CircleButton(
                            onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) viewModel.updateSearchQuery("")
                            },
                            icon = Icons.Filled.Search,
                            size = 40,
                        )
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(palette.accent, CircleShape)
                                .clickable(onClick = onOpenSettings),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = palette.canvas,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            if (searchVisible) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        placeholder = { Text("Search sessions", color = palette.textSecondary) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.card,
                            unfocusedContainerColor = palette.card,
                            focusedBorderColor = palette.card,
                            unfocusedBorderColor = palette.card,
                        ),
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    MenuRow(Icons.AutoMirrored.Filled.List, "Projects") { onOpenProjects() }
                    MenuRow(Icons.Filled.DateRange, "Tasks") { onOpenPanel("TASKS") }
                    MenuRow(Icons.Filled.Build, "Skills") { onOpenPanel("SKILLS") }
                    MenuRow(Icons.Filled.Face, "Memory") { onOpenPanel("MEMORY") }
                    MenuRow(Icons.Filled.Info, "Insights") { onOpenPanel("INSIGHTS") }
                }
            }

            item {
                Text(
                    "Sessions",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            item {
                AnimatedVisibility(visible = state.isFromCache) {
                    Text(
                        "Offline — showing cached sessions.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.warning,
                    )
                }
            }

            item {
                AnimatedVisibility(visible = state.errorMessage != null) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.destructive,
                        )
                        TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
            }

            when {
                state.isLoading && state.sessions.isEmpty() -> item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = palette.accent) }
                }

                state.sessions.isEmpty() && state.errorMessage == null -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (state.searchQuery.isBlank()) "No sessions yet" else "No matches",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (state.searchQuery.isBlank()) "Tap Chat to start one." else "Try another search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }

                else -> items(state.sessions, key = { it.stableId }) { session ->
                    val sessionId = session.sessionId ?: return@items
                    val isSelected = state.selectedIds.contains(sessionId)
                    SwipeableSessionRow(
                        session = session,
                        isSelected = isSelected,
                        selectionMode = state.selectionMode,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelection(sessionId)
                            } else {
                                onOpenSession(sessionId)
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.selectionMode) {
                                viewModel.toggleSelection(sessionId)
                            } else {
                                // First long-press enters selection mode + selects this row.
                                viewModel.beginSelection(sessionId)
                            }
                        },
                        onArchive = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.archiveSession(sessionId, session.archived != true)
                        },
                        onDelete = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            deleteTarget = session
                        },
                    )
                }
            }
            }
            // Excellence v1 Wave 1: FastScrollbar overlays the list on the
            // right edge. Hidden when there are fewer than 20 items (no UX
            // value and saves the hit zone). Letter-jump rail snaps to the
            // first index of each letter group's title. Drag is handled by
            // the FastScrollbar's internal pointerInput; on release it tells
            // us which row to scroll to, and we drive `listState.scrollToItem`
            // via the scope.
            FastScrollbar(
                itemCount = state.sessions.size,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                estimatedItemHeightPx = 72,
                letterIndex = letterIndex,
                onScrollToIndex = { target ->
                    scope.launch { listState.scrollToItem(target) }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    actionTarget?.let { session ->
        SessionActionsDialog(
            session = session,
            onDismiss = { actionTarget = null },
            onRename = { renameTarget = session; actionTarget = null },
            onDelete = { deleteTarget = session; actionTarget = null },
            onPinToggle = {
                session.sessionId?.let { viewModel.pinSession(it, session.pinned != true) }
                actionTarget = null
            },
            onArchiveToggle = {
                session.sessionId?.let { viewModel.archiveSession(it, session.archived != true) }
                actionTarget = null
            },
            onMove = { moveTarget = session; actionTarget = null },
            onDuplicate = {
                actionTarget = null
                session.sessionId?.let { id ->
                    scope.launch { viewModel.duplicateSessionNow(id)?.let(onOpenSession) }
                }
            },
            onFork = {
                actionTarget = null
                session.sessionId?.let { id ->
                    scope.launch { viewModel.branchSessionNow(id)?.let(onOpenSession) }
                }
            },
        )
    }

    moveTarget?.let { session ->
        MoveToProjectSheet(
            projects = state.projects,
            currentProjectId = session.projectId,
            onPick = { projectId ->
                session.sessionId?.let { viewModel.moveSession(it, projectId) }
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    renameTarget?.let { session ->
        RenameDialog(
            initial = session.title.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                session.sessionId?.let { viewModel.renameSession(it, title) }
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text("\"${session.title ?: "Untitled"}\" will be removed from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    session.sessionId?.let(viewModel::deleteSession)
                    deleteTarget = null
                }) { Text("Delete", color = palette.destructive) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    // Wave 0: bulk-delete confirm. `bulkDeleteOpen` is set true by the bar's
    // Delete icon, which stashes the *current* selectedIds via the screen's
    // own state (we snapshot at that moment so the dialog is stable even if
    // the user taps Cancel elsewhere during the dialog).
    if (bulkDeleteOpen) {
        val snapshot = state.selectedIds
        AlertDialog(
            onDismissRequest = { bulkDeleteOpen = false },
            title = { Text("Delete ${snapshot.size} session${if (snapshot.size == 1) "" else "s"}?") },
            text = {
                Text(
                    if (snapshot.isEmpty()) "No sessions selected."
                    else "These sessions will be removed from the server. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = snapshot.isNotEmpty(),
                    onClick = {
                        viewModel.deleteSessions(snapshot.toList())
                        viewModel.clearSelection()
                        bulkDeleteOpen = false
                    },
                ) { Text("Delete", color = palette.destructive) }
            },
            dismissButton = {
                TextButton(onClick = { bulkDeleteOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/** The icon + label menu rows under the wordmark (Tasks / Skills / Memory / Insights). */
@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SessionActionsDialog(
    session: SessionSummary,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPinToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onFork: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = {
            Text(
                session.title ?: "Untitled session",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onMove) { Text("Move to project") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onFork) { Text("Fork") }
                TextButton(onClick = onPinToggle) {
                    Text(if (session.pinned == true) "Unpin" else "Pin")
                }
                TextButton(onClick = onArchiveToggle) {
                    Text(if (session.archived == true) "Unarchive" else "Archive")
                }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** Project picker for "Move to project", with a "No project" un-file row. */
@Composable
private fun MoveToProjectSheet(
    projects: List<Project>,
    currentProjectId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Sentinel for the "no project" row — HermexPickerSheet keys on the value.
    val noProject = ""
    HermexPickerSheet(
        title = "Move to project",
        sections = listOf(
            PickerSection(
                header = null,
                rows = buildList {
                    add(PickerRow("No project", noProject))
                    projects.forEach { p ->
                        add(PickerRow(p.name?.ifBlank { null } ?: "Untitled", p.projectId ?: return@forEach))
                    }
                },
            ),
        ),
        isSelected = { value -> value == (currentProjectId ?: noProject) },
        onPick = { value -> onPick(value.ifBlank { null }) },
        onDismiss = onDismiss,
        searchable = false,
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * iOS-style swipe actions: swipe right to Archive, swipe left to Delete. Neither
 * gesture actually dismisses the row — both snap back and let the list refresh
 * reflect the change (delete waits for the confirm dialog).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionRow(
    session: SessionSummary,
    isSelected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalHermexPalette.current
    // Wave 0: in bulk-select mode the swipe gestures are disabled. The
    // SessionRow itself is the only hit area, and tap toggles selection,
    // long-press toggles too. No SwipeToDismissBox wrapper, no haptics
    // piling up from accidental swipes.
    if (selectionMode) {
        Surface(
            color = if (isSelected) palette.accent.copy(alpha = 0.12f) else palette.canvas,
            modifier = modifier.fillMaxWidth(),
        ) {
            SessionRow(
                session = session,
                isSelected = isSelected,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onArchive()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // never settle dismissed; the list refresh handles the change
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val toEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (toEnd) palette.warning else palette.destructive
            val label = if (toEnd) (if (session.archived == true) "Unarchive" else "Archive") else "Delete"
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
        },
    ) {
        Surface(color = palette.canvas) {
            SessionRow(
                session = session,
                isSelected = false,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
    }
}

/** iOS session row: bold title, "N messages · workspace" caption, relative time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalHermexPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Wave 0: a small leading checkmark slot. When isSelected it shows a
        // filled accent dot (✓); when not (and not in selection mode) the
        // slot is invisible — keeps spacing identical to the old row.
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(palette.accent, CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        "✓",
                        color = palette.canvas,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (session.pinned == true) {
                    Text("📌", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    session.title?.ifBlank { null } ?: "Untitled session",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(
                    session.messageCount?.let { "$it messages" },
                    session.workspace?.substringAfterLast('/')?.ifBlank { null }
                        ?: session.profile,
                    if (session.isCronSession) "cron" else null,
                    if (session.isCliSession == true) "cli" else null,
                    if (session.archived == true) "archived" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                relativeTimeAgo(session.lastMessageAt ?: session.updatedAt ?: session.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
            if (session.isStreaming == true || session.activeStreamId != null) {
                Box(Modifier.size(8.dp).background(palette.success, CircleShape))
            }
        }
    }
}
