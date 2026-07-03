package com.hermexapp.android.features.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermexapp.android.features.chat.ChatViewModel.TimelineEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onRunFinished: (String?) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) { viewModel.load() }
    DisposableEffect(Unit) { onDispose { viewModel.teardown() } }

    // Completion signal: haptic + notification hook, once per finished run.
    LaunchedEffect(state.finishedRunCount) {
        if (state.finishedRunCount > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onRunFinished(state.title)
        }
    }

    // Follow the stream: keep the newest entry visible as it grows.
    LaunchedEffect(state.entries.size, (state.entries.lastOrNull() as? TimelineEntry.AssistantMessage)?.text?.length) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.title ?: "Chat",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = onOpenFiles) { Text("Files") }
                    TextButton(onClick = onOpenGit) { Text("Git") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AnimatedVisibility(visible = state.isFromCache) {
                Text(
                    "Offline — showing the cached transcript.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            AnimatedVisibility(visible = state.errorMessage != null) {
                Text(
                    state.errorMessage.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                state.isLoading && state.entries.isEmpty() ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                state.entries.isEmpty() ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Send a message to start the run.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        TimelineEntryView(entry)
                    }
                }
            }

            SlashSuggestionList(
                suggestions = state.slashSuggestions,
                onPick = viewModel::applySlashCommand,
            )
            AttachmentStrip(state, viewModel)
            ComposerSelectorRow(viewModel, state)
            Composer(
                text = state.composerText,
                isStreaming = state.isStreaming,
                canSend = state.composerText.isNotBlank() || state.attachments.isNotEmpty(),
                onTextChange = viewModel::updateComposerText,
                onSend = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.send()
                },
                onStop = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.stop()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineEntryView(entry: TimelineEntry) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    fun copyModifier(text: String): Modifier = Modifier.combinedClickable(
        onClick = {},
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            clipboard.setText(AnnotatedString(text))
        },
    )

    when (entry) {
        is TimelineEntry.UserMessage -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = copyModifier(entry.text),
            ) {
                Text(
                    entry.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is TimelineEntry.AssistantMessage -> Text(
            // Plain text for now — streaming markdown is its own follow-up slice.
            entry.text + if (entry.isStreaming) " ▍" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = copyModifier(entry.text),
        )

        is TimelineEntry.Reasoning -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = copyModifier(entry.text),
        )

        is TimelineEntry.ToolCall -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                Text(
                    listOfNotNull(
                        entry.name ?: "tool",
                        when {
                            entry.isRunning -> "running…"
                            entry.isError -> "failed"
                            else -> entry.durationSeconds?.let { "%.1fs".format(it) } ?: "done"
                        },
                    ).joinToString(" — "),
                    style = MaterialTheme.typography.labelMedium,
                )
                entry.preview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        is TimelineEntry.Notice -> Text(
            entry.text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Composer(
    text: String,
    isStreaming: Boolean,
    canSend: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (isStreaming) "Steer the run…" else "Message") },
            maxLines = 4,
        )
        if (isStreaming) {
            Button(onClick = onStop) { Text("Stop") }
        }
        Button(onClick = onSend, enabled = canSend) {
            Text(if (isStreaming) "Steer" else "Send")
        }
    }
}
