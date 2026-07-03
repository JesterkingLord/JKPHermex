package com.hermexapp.android.features.sessionlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermexapp.android.model.SessionSummary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onOpenSession: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Hermex") },
                actions = {
                    TextButton(onClick = { onOpenPanel("TASKS") }) { Text("Tasks") }
                    TextButton(onClick = { onOpenPanel("SKILLS") }) { Text("Skills") }
                    TextButton(onClick = { onOpenPanel("MEMORY") }) { Text("Memory") }
                    TextButton(onClick = { onOpenPanel("INSIGHTS") }) { Text("Usage") }
                    TextButton(onClick = onOpenSettings) { Text("⚙") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { viewModel.createSessionNow()?.let(onOpenSession) }
            }) { Text("+") }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Search sessions") },
                singleLine = true,
            )

            AnimatedVisibility(visible = state.isFromCache) {
                Text(
                    "Offline — showing cached sessions.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            AnimatedVisibility(visible = state.errorMessage != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
                }
            }

            when {
                state.isLoading && state.sessions.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                state.sessions.isEmpty() && state.errorMessage == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (state.searchQuery.isBlank()) "No sessions yet" else "No matches",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (state.searchQuery.isBlank()) {
                                Button(onClick = {
                                    scope.launch { viewModel.createSessionNow()?.let(onOpenSession) }
                                }) { Text("Start your first chat") }
                            }
                        }
                    }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.stableId }) { session ->
                        SessionRow(
                            session = session,
                            modifier = Modifier.animateItem(),
                            onClick = { session.sessionId?.let(onOpenSession) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actionTarget = session
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
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
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session.pinned == true) Text("📌", style = MaterialTheme.typography.labelSmall)
            Text(
                session.title?.ifBlank { null } ?: "Untitled session",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.isStreaming == true || session.activeStreamId != null) {
                Badge { Text("running") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                listOfNotNull(
                    session.model,
                    session.messageCount?.let { "$it msgs" },
                    if (session.isCronSession) "cron" else null,
                    if (session.isCliSession == true) "cli" else null,
                    if (session.archived == true) "archived" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatTimestamp(session.lastMessageAt ?: session.updatedAt ?: session.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(epochSeconds: Double?): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date((epochSeconds * 1000).toLong()))
}
