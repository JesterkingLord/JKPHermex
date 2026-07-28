package com.hermexapp.android.features.prompts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hermexapp.android.persistence.PromptEntity
import kotlinx.coroutines.launch

/**
 * PromptsScreen — the prompt-library UI.
 *
 * The screen's purpose is two-fold:
 * 1. Manage a library of reusable prompt templates (name / body / tags).
 * 2. Make those templates easy to insert into the chat composer via
 *    an Insert button on the row + inside the inline editor.
 *
 * Insert signaling is decoupled from the composable via
 * [PromptsViewModel.lastInsertedBody] — when the user taps Insert,
 * the VM publishes the prompt body and bumps usageCount; the chat
 * composer (or any other subscriber) consumes the value and asks the
 * VM to acknowledge it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsScreen(
    onClose: () -> Unit,
    viewModel: PromptsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var editor by remember { mutableStateOf<EditorState>(EditorState.Closed) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.testTag("prompts_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectionMode) "${state.selection.size} selected"
                        else "Prompts"
                    )
                },
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
                            modifier = Modifier.testTag("prompts_search_toggle"),
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
                BulkPromptsBar(
                    count = state.selection.size,
                    onDelete = {
                        val dropped = state.selection.size
                        viewModel.deleteSelected()
                        scope.launch { snackbarHost.showSnackbar("Deleted $dropped prompt${if (dropped != 1) "s" else ""}.") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val id = viewModel.createBlank()
                        editor = EditorState.Open(id)
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New prompt") },
                    modifier = Modifier.testTag("prompts_fab"),
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
                    placeholder = { Text("Search prompts…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("prompts_search_field"),
                )
            }

            (editor as? EditorState.Open)?.let { open ->
                PromptEditor(
                    promptId = open.promptId,
                    viewModel = viewModel,
                    state = state,
                    onClose = { editor = EditorState.Closed },
                    onInsert = { prompt ->
                        viewModel.requestInsert(prompt)
                        scope.launch { snackbarHost.showSnackbar("Inserted into chat.") }
                        editor = EditorState.Closed
                    },
                )
            }

            when {
                state.isEmpty && !state.filterActive -> PromptsEmpty(modifier = Modifier.fillMaxSize())
                state.isEmpty && state.filterActive -> NoSearchHits(query = state.query, modifier = Modifier.fillMaxSize())
                else -> PromptsList(
                    state = state,
                    onSwipeDelete = { id ->
                        // Capture before delete so UNDO can restore the row.
                        val deleted = state.prompts.firstOrNull { it.id == id }
                        viewModel.delete(id)
                        scope.launch {
                            val result = snackbarHost.showSnackbar(
                                message = "Prompt deleted.",
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
                    onInsert = { prompt ->
                        viewModel.requestInsert(prompt)
                        scope.launch { snackbarHost.showSnackbar("Inserted into chat.") }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private sealed class EditorState {
    data object Closed : EditorState()
    data class Open(val promptId: String) : EditorState()
}

/**
 * PromptEditor — inline editor card with three fields (name, body,
 * tags). Each change immediately upserts into the store; the
 * Insert button publishes a body via [PromptsViewModel.requestInsert].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptEditor(
    promptId: String,
    viewModel: PromptsViewModel,
    state: PromptsViewModel.UiState,
    onClose: () -> Unit,
    onInsert: (PromptEntity) -> Unit,
) {
    val existing = state.prompts.firstOrNull { it.id == promptId }
    var name by remember(promptId) { mutableStateOf(existing?.name ?: "") }
    var body by remember(promptId) { mutableStateOf(existing?.body ?: "") }
    var tags by remember(promptId) { mutableStateOf(existing?.tags ?: "") }

    LaunchedEffect(existing?.name) { name = existing?.name.orEmpty() }
    LaunchedEffect(existing?.body) { body = existing?.body.orEmpty() }
    LaunchedEffect(existing?.tags) { tags = existing?.tags.orEmpty() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("prompt_editor"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (name.isBlank()) "New prompt" else "Editing \"$name\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("prompt_editor_close"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close editor")
                }
            }
            Spacer(Modifier.height(8.dp))
            InlineField(
                value = name,
                onChange = {
                    name = it
                    viewModel.upsert(
                        PromptEntity(
                            id = promptId, name = it, body = body, tags = tags,
                            pinned = existing?.pinned == true,
                            usageCount = existing?.usageCount ?: 0,
                            updatedAtMillis = System.currentTimeMillis(),
                            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                        )
                    )
                },
                placeholder = "Prompt name (e.g. \"Summarize article\")",
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.SemiBold,
            )
            InlineField(
                value = body,
                onChange = {
                    body = it
                    viewModel.upsert(
                        PromptEntity(
                            id = promptId, name = name, body = it, tags = tags,
                            pinned = existing?.pinned == true,
                            usageCount = existing?.usageCount ?: 0,
                            updatedAtMillis = System.currentTimeMillis(),
                            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                        )
                    )
                },
                placeholder = "The prompt body. Use {{variables}} for the chat composer to substitute.",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                fontWeight = FontWeight.Normal,
            )
            InlineField(
                value = tags,
                onChange = {
                    tags = it
                    viewModel.upsert(
                        PromptEntity(
                            id = promptId, name = name, body = body, tags = it,
                            pinned = existing?.pinned == true,
                            usageCount = existing?.usageCount ?: 0,
                            updatedAtMillis = System.currentTimeMillis(),
                            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                        )
                    )
                },
                placeholder = "tags (comma separated, e.g. \"writing, summary\")",
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Normal,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val p = PromptEntity(
                            id = promptId, name = name, body = body, tags = tags,
                            pinned = existing?.pinned == true,
                            usageCount = existing?.usageCount ?: 0,
                            updatedAtMillis = System.currentTimeMillis(),
                            createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                        )
                        viewModel.upsert(p)
                        onInsert(p)
                    },
                    enabled = body.isNotBlank(),
                    modifier = Modifier.testTag("prompt_editor_insert"),
                ) {
                    Icon(Icons.Filled.AddBox, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Insert")
                }
            }
        }
    }
}

/**
 * Inline text field that visually integrates into the editor card.
 * Same pattern as the notes editor to keep the UI consistent.
 */
@Composable
private fun InlineField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
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
private fun PromptsEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("No prompts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap New prompt to save a template. Insert it into the chat composer anytime.",
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
        Text("No prompts match \"$query\"", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PromptsList(
    state: PromptsViewModel.UiState,
    onSwipeDelete: (String) -> Unit,
    onInsert: (PromptEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("prompts_list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.prompts, key = { it.id }) { prompt ->
            PromptRow(
                prompt = prompt,
                onInsert = { onInsert(prompt) },
                onSwipeDelete = { onSwipeDelete(prompt.id) },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PromptRow(
    prompt: PromptEntity,
    onInsert: () -> Unit,
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
        modifier = Modifier.testTag("prompt_row_${prompt.id}"),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val displayName = prompt.name.ifBlank { "(unnamed prompt)" }
                    Text(
                        displayName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (prompt.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onInsert,
                        enabled = prompt.body.isNotBlank(),
                        modifier = Modifier.testTag("prompt_insert_${prompt.id}"),
                    ) {
                        Icon(Icons.Filled.AddBox, contentDescription = null)
                        Spacer(Modifier.width(2.dp))
                        Text("Insert")
                    }
                }
                if (prompt.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prompt.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (prompt.tags.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TagChips(tags = prompt.tags)
                }
                if (prompt.usageCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Used ${prompt.usageCount}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagChips(tags: String) {
    val tagList = parsePromptTags(tags)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        tagList.take(4).forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag("prompt_tag_$tag"),
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (tagList.size > 4) {
            Text(
                "+${tagList.size - 4}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun parsePromptTags(tags: String): List<String> =
    tags.split(',').map { it.trim() }.filter { it.isNotBlank() }

@Composable
private fun BulkPromptsBar(
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
