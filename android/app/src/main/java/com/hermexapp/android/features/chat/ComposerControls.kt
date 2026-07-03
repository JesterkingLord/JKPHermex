package com.hermexapp.android.features.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermexapp.android.model.AgentCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selector chip row: model / profile / workspace / attach — the iOS `+` menu, flattened. */
@Composable
fun ComposerSelectorRow(viewModel: ChatViewModel, state: ChatViewModel.UiState) {
    val config = state.composerConfig
    var openPicker by remember { mutableStateOf<PickerKind?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val (bytes, name) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                    val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "image.jpg"
                    data to filename
                }
                if (bytes != null) viewModel.addAttachmentNow(bytes, name)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { openPicker = PickerKind.MODEL },
            label = { Text(config.selectedModelDisplayName ?: "Model") },
        )
        AssistChip(
            onClick = { openPicker = PickerKind.PROFILE },
            label = { Text(config.selectedProfile ?: config.activeProfile ?: "Profile") },
        )
        AssistChip(
            onClick = { openPicker = PickerKind.WORKSPACE },
            label = {
                Text(
                    (config.selectedWorkspace ?: config.lastWorkspace)
                        ?.substringAfterLast('/') ?: "Workspace",
                )
            },
        )
        AssistChip(
            onClick = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            label = { Text(if (state.isUploadingAttachment) "Uploading…" else "Attach") },
            enabled = !state.isUploadingAttachment,
        )
    }

    when (openPicker) {
        PickerKind.MODEL -> PickerDialog(
            title = "Model",
            options = listOf("Server default" to null) + config.modelGroups.flatMap { group ->
                group.models.map { "${it.displayName} (${group.name})" to (it.id to it.providerId) }
            },
            onPick = { value ->
                val pick = value as? Pair<*, *>
                viewModel.selectModel(pick?.first as? String, pick?.second as? String)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        PickerKind.PROFILE -> PickerDialog(
            title = "Profile",
            options = listOf("Active profile" to null) +
                config.profiles.map { it.displayName to it.name },
            onPick = { value ->
                viewModel.selectProfile(value as? String)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        PickerKind.WORKSPACE -> PickerDialog(
            title = "Workspace",
            options = listOf("Session workspace" to null) +
                config.workspaces.map { (it.name ?: it.path ?: "?") to it.path },
            onPick = { value ->
                viewModel.selectWorkspace(value as? String)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        null -> Unit
    }
}

private enum class PickerKind { MODEL, PROFILE, WORKSPACE }

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, Any?>>,
    onPick: (Any?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.take(30).forEach { (label, value) ->
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

/** Pending attachments above the composer, tap to remove. */
@Composable
fun AttachmentStrip(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    if (state.attachments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.attachments.forEach { attachment ->
            AssistChip(
                onClick = { viewModel.removeAttachment(attachment) },
                label = { Text("${if (attachment.isImage) "🖼 " else "📄 "}${attachment.name} ✕") },
            )
        }
    }
}

/** Slash-command autocomplete, shown while the draft is a lone `/token`. */
@Composable
fun SlashSuggestionList(
    suggestions: List<AgentCommand>,
    onPick: (AgentCommand) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            suggestions.forEach { command ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        command.name?.let { if (it.startsWith("/")) it else "/$it" } ?: "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    command.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
