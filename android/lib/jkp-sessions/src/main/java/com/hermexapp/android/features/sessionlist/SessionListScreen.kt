package com.hermexapp.android.features.sessionlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/**
 * The iOS home screen: HERMEX wordmark, panel menu rows, a "Sessions" section
 * with relative timestamps, and the floating "✎ Chat" pill.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onOpenSession: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit = {},
    onOpenMenu: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val palette = LocalHermexPalette.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isPhone = LocalConfiguration.current.screenWidthDp < 600
    // Excellence v1 Wave 1: LazyListState for the FastScrollbar drag
    // gestures. The session list is the scrollbar's first consumer;
    // Wave 2 reuses the same state pattern for the chat timeline.
    val listState = rememberLazyListState()
    // Letter-jump map for the Gmail-style rail. Recompute only when the
    // Wave 7 Slice 7.2 — display list obeys the current filter pill; the
    // bulk-action toolbar keeps using the full set so cross-filter
    // selection isn't lost when the user toggles the pill mid-selection.
    // Derive from the Compose-collected snapshot. Reading
    // `viewModel.uiState.value` here left this top-level calculation outside
    // Compose observation, so a cold-start result could reach the ViewModel
    // while the UI remained frozen on its initial empty list.
    val visibleSessions = filterSessions(state.sessions, state.filterMode)

    // session list itself changes (cheap; O(N) on list mutation but the
    // list rarely exceeds ~100 rows in practice).
    val letterIndex: Map<Char, Int> = remember(visibleSessions) {
        // Reduce titles to first-letter bucket indexes. `buildList`+Pair
        // was wrong because Pair doesn't have `.key`; just use a
        // MutableMap directly so `putIfAbsent` does exactly what we want.
        val out = sortedMapOf<Char, Int>()
        for ((idx, title) in visibleSessions.withIndex()) {
            val trimmed = title.title?.trim()?.takeIf(String::isNotEmpty) ?: continue
            val first = trimmed.first().uppercaseChar()
            if (first.isLetter()) out.putIfAbsent(first, idx)
        }
        out
    }
    // Wave 6 Slice 6.2 — date-grouped section buckets from the pure
    // SessionGroups helper. Recompute only when the underlying session
    // list itself changes so LazyColumn keys stay stable; the bucketing
    // function uses Clock.systemUTC() for "now" by default.
    val groups = remember(visibleSessions) {
        SessionGroups.groupSessions(visibleSessions)
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

    // v0.8.15 — background refresh while this screen is composed. The
    // lifecycle observer flips the ViewModel between the 15s foreground
    // and 60s background cadences; the poll loop itself never restarts
    // (see SessionListViewModel.startBackgroundRefresh).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The observer only sees transitions that happen after
        // registration, and the screen is already RESUMED when it first
        // composes — seed the foreground cadence explicitly so the first
        // tick isn't stuck on the 60s background rate.
        viewModel.onScreenResumed()
        viewModel.startBackgroundRefresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopBackgroundRefresh()
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
            // Wave 7: material3 PullToRefreshBox wraps the LazyColumn so a
            // swipe-down gesture calls viewModel.refresh() (the non-suspend
            // public entry — PullToRefreshBox.onRefresh is `() -> Unit`,
            // which lines up with `refresh()`; `refreshNow()` is suspend).
            // `isRefreshing` tracks isManualRefresh, not isLoading: the 15s
            // background poll sets isLoading, so binding to it made the
            // indicator animate on its own every tick with nobody touching
            // the screen.
            // Inner LazyColumn is unchanged; FastScrollbar remains a sibling
            // overlay on the right edge of the Box.
            PullToRefreshBox(
                isRefreshing = state.isManualRefresh,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
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
                        // visibleSessions, not state.sessions: the bar counts
                        // what the filter is showing, so "Select all" and the
                        // count above it agree with the rows on screen.
                        totalVisible = visibleSessions.count { it.sessionId != null },
                        onCancel = { viewModel.clearSelection() },
                        onToggleSelectAll = {
                            if (state.selectedIds.size == visibleSessions.count { it.sessionId != null }) {
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
                            .padding(start = 16.dp, end = 56.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onOpenMenu != null) {
                            CircleButton(
                                onClick = onOpenMenu,
                                contentDescription = "Open navigation menu",
                                icon = Icons.Filled.Menu,
                                size = 40,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            HermexWordmark(fontSize = 22.sp)
                            if (visibleSessions.isNotEmpty()) {
                                Text(
                                    text = "${visibleSessions.size} conversation" +
                                        if (visibleSessions.size == 1) "" else "s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                )
                            }
                        }
                        CircleButton(
                            onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) viewModel.updateSearchQuery("")
                            },
                            contentDescription =
                                if (searchVisible) "Close session search" else "Search sessions",
                            icon = Icons.Filled.Search,
                            size = 40,
                        )
                        if (onOpenMenu == null) {
                            CircleButton(
                                onClick = onOpenSettings,
                                contentDescription = "Open settings",
                                icon = Icons.Filled.Settings,
                                size = 40,
                            )
                        }
                    }
                }
                // Wave 7 Slice 7.2 — sidebar filter pills (All / Pinned /
                // Archived). Hidden in bulk-selection mode so the user
                // isn't distracted mid-action. Tapping the active pill
                // returns to All so a pill always serves as both a filter
                // and a toggle-off.
                item(key = "filter-pills") {
                    if (!state.selectionMode) {
                        FilterPills(
                            current = state.filterMode,
                            onPick = { mode ->
                                viewModel.setFilterMode(mode)
                            },
                        )
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
                if (!isPhone) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                        MenuRow(Icons.AutoMirrored.Filled.List, "Projects") { onOpenProjects() }
                        MenuRow(Icons.Filled.DateRange, "Tasks") { onOpenPanel("TASKS") }
                        MenuRow(Icons.Filled.Build, "Skills") { onOpenPanel("SKILLS") }
                        MenuRow(Icons.Filled.Face, "Memory") { onOpenPanel("MEMORY") }
                        MenuRow(Icons.Filled.Info, "Insights") { onOpenPanel("INSIGHTS") }
                    }
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
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.destructive,
                        )
                        // Show the URL the failed request went out against
                        // so the user can immediately tell whether they
                        // pointed the app at the wrong host (e.g. the
                        // `127.0.0.1:8787` classic when running on a real
                        // device where that means "the phone itself" not
                        // "the laptop"). Skipped during onboarding when no
                        // server has been configured yet.
                        state.lastFailedServer?.let { failedUrl ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Tried: $failedUrl",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.destructive.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "If you are on a real phone, " +
                                    "use the laptop's Tailscale IP, " +
                                    "not 127.0.0.1.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                            // Shortcut to Settings so the user can switch
                            // servers without first tapping the toolbar
                            // cog (the most common reason this banner
                            // appears in the first place is a stale URL).
                            if (state.lastFailedServer != null) {
                                TextButton(onClick = onOpenSettings) {
                                    Text("Change server")
                                }
                            }
                        }
                    }
                }
            }

            when {
                state.isLoading && visibleSessions.isEmpty() -> item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = palette.accent) }
                }

                visibleSessions.isEmpty() && state.errorMessage == null -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = when {
                                state.searchQuery.isNotBlank() -> "No matches"
                                state.filterMode == SessionListViewModel.FilterMode.Pinned &&
                                    viewModel.filteredSessions.isEmpty() &&
                                    state.sessions.any { it.pinned == true } ->
                                    "Nothing matches in Pinned"
                                state.filterMode == SessionListViewModel.FilterMode.Archived &&
                                    viewModel.filteredSessions.isEmpty() &&
                                    state.sessions.any { it.archived == true } ->
                                    "Nothing matches in Archived"
                                else -> "No sessions yet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            when {
                                state.searchQuery.isNotBlank() -> "Try another search."
                                else -> "Tap Chat to start one."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }

                else -> {
                    // Wave 6 Slice 6.2 — date-grouped section headers. The
                    // `groups` list is computed once above (keyed on
                    // state.sessions) so we don't churn on every recompose.
                    // Each group emits a sticky header row, then its sessions.
                    groups.forEach { group ->
                        item(key = "section-${group.section.label}") {
                            SectionHeader(
                                title = group.section.label,
                                count = group.sessions.size,
                            )
                        }
                        items(group.sessions, key = { it.stableId }) { session ->
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
            }
            }
            }
            // Excellence v1 Wave 1: FastScrollbar overlays the list on the
            // right edge. Hidden when there are fewer than 20 items (no UX
            // FastScrollbar for the session list.
            //
            // Wave 9.9 (2026-07-24): rewritten to take the LazyListState
            // directly. The bar uses pixel-perfect math and ChatGPT-style
            // auto-hide. The session list already had its own letter-jump
            // rail (separately added by `buildLetterIndex`); this scrollbar
            // is the standard one.
            FastScrollbar(
                listState = listState,
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

/**
 * Wave 7 Slice 7.2 — sidebar filter pill row. Three pills: All / Pinned /
 * Archived. Tapping the active pill returns to All. Material3
 * `FilterChip` for each, wrapped in a `Row` with 12dp horizontal padding.
 * The vertical padding sits between the wordmark row and the section
 * headers so the pills don't crowd either neighbour.
 */
@Composable
private fun FilterPills(
    current: SessionListViewModel.FilterMode,
    onPick: (SessionListViewModel.FilterMode) -> Unit,
) {
    val palette = LocalHermexPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = current == SessionListViewModel.FilterMode.All,
            onClick = { onPick(SessionListViewModel.FilterMode.All) },
            label = { Text("All") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = palette.accent.copy(alpha = 0.18f),
                selectedLabelColor = palette.accent,
            ),
        )
        FilterChip(
            selected = current == SessionListViewModel.FilterMode.Pinned,
            onClick = {
                onPick(
                    if (current == SessionListViewModel.FilterMode.Pinned) {
                        SessionListViewModel.FilterMode.All
                    } else {
                        SessionListViewModel.FilterMode.Pinned
                    },
                )
            },
            label = { Text("Pinned") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = palette.accent.copy(alpha = 0.18f),
                selectedLabelColor = palette.accent,
            ),
        )
        FilterChip(
            selected = current == SessionListViewModel.FilterMode.Archived,
            onClick = {
                onPick(
                    if (current == SessionListViewModel.FilterMode.Archived) {
                        SessionListViewModel.FilterMode.All
                    } else {
                        SessionListViewModel.FilterMode.Archived
                    },
                )
            },
            label = { Text("Archived") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = palette.accent.copy(alpha = 0.18f),
                selectedLabelColor = palette.accent,
            ),
        )
    }
}

/**
 * Wave 6 Slice 6.2 — small section header row between grouped session lists.
 * Renders the bucket title (Pinned / Today / Yesterday / Previous 7 days /
 * Earlier) and a count badge on the trailing edge. 16dp horizontal / 12dp top
 * / 4dp bottom; sits inline in the same LazyColumn as the rows beneath it.
 * Visual identity comes from the existing palette (`textSecondary` is the
 * muted-text style used elsewhere on this screen — `Sessions` heading +
 * offline banner).
 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    val palette = LocalHermexPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 56.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
            )
        }
    }
}

private fun nowMillis(): Long = java.lang.System.currentTimeMillis()

/** The icon + label menu rows under the wordmark (Tasks / Skills / Memory / Insights). */
@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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
            .padding(start = 20.dp, end = 56.dp, top = 10.dp, bottom = 10.dp),
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
                // v0.8.15 — live indicator: a pulsing 8dp green dot next
                // to the title while the session is streaming. The pulse
                // (0.3f→1f alpha, 900ms, reverse) keeps the row visibly
                // "alive" without any text churn.
                if (shouldShowStreamingDot(session)) {
                    val infinite = rememberInfiniteTransition(label = "streaming-pulse")
                    val alpha by infinite.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "pulse",
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .graphicsLayer { this.alpha = alpha }
                            .background(Color(0xFF34C759), shape = CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
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
        }
    }
}


// Wave 9.6 — `sumOfMeasuredHeights` removed. The FastScrollbar no
// longer asks for a pixel-based total height; it uses item-count
// fraction + LazyListState pixel-perfect edge flags.

/**
 * v0.8.15 — live-indicator dot rule for [SessionRow]. The pulsing green
 * dot renders while the server reports the session is actively streaming
 * — either the `is_streaming` flag or a live `active_stream_id` (the
 * historical marker, pre-v0.8.15 behavior). Owned by the row composable
 * and kept as a pure predicate so SessionRowStreamingDotTest can pin the
 * show/hide decision on the JVM (this module has no Compose UI-test
 * runner).
 */
internal fun shouldShowStreamingDot(session: SessionSummary): Boolean =
    session.isStreaming == true || session.activeStreamId != null
