package com.hermexapp.android.features.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.model.AgentCommand
import com.hermexapp.android.ui.HermexPickerSheet
import com.hermexapp.android.ui.PickerRow
import com.hermexapp.android.ui.PickerSection
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The iOS composer: one large rounded dark container holding the text field
 * ("Ask anything... /commands") and a control row (+ attach, model selector,
 * send circle), with workspace/profile pills beneath it.
 */
@Composable
fun ComposerBar(
    viewModel: ChatViewModel,
    state: ChatViewModel.UiState,
    onSendHaptic: () -> Unit = {},
    onStopHaptic: () -> Unit = {},
) {
    val palette = LocalHermexPalette.current
    val config = state.composerConfig
    var openPicker by remember { mutableStateOf<PickerKind?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = rememberVoiceInputController(onText = viewModel::appendDictatedText)

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

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = palette.card,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = state.composerText,
                    onValueChange = viewModel::updateComposerText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.composerText.isEmpty()) {
                                Text(
                                    "Ask anything... /commands",
                                    color = palette.textSecondary,
                                    fontSize = 17.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.isUploadingAttachment) "…" else "+",
                        fontSize = 24.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.clickable(enabled = !state.isUploadingAttachment) {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    SelectorText(
                        label = (config.selectedModelDisplayName ?: "model").take(14),
                        onClick = { openPicker = PickerKind.MODEL },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

                    // Voice dictation → populates the composer only (iOS voice-input contract).
                    if (voice.isAvailable) {
                        Text(
                            if (voice.isListening) "◉" else "🎤",
                            fontSize = 20.sp,
                            color = if (voice.isListening) palette.destructive else palette.textSecondary,
                            modifier = Modifier.clickable {
                                if (voice.isListening) voice.stop() else voice.start()
                            },
                        )
                    }

                    // Send when there's a draft; stop when idle-handed mid-run.
                    val showStop = state.isStreaming &&
                        state.composerText.isBlank() && state.attachments.isEmpty()
                    val canSend = state.composerText.isNotBlank() || state.attachments.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                if (showStop) palette.destructive else palette.control,
                                CircleShape,
                            )
                            .clickable(enabled = showStop || canSend) {
                                if (showStop) {
                                    onStopHaptic()
                                    viewModel.stop()
                                } else {
                                    onSendHaptic()
                                    viewModel.send()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (showStop) "■" else "↑",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = if (showStop) 14.sp else 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Workspace + profile pills under the composer, like iOS.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(
                text = "📁 ${
                    ((config.selectedWorkspace ?: config.lastWorkspace)
                        ?.substringAfterLast('/') ?: "workspace").take(16)
                } ⌄",
                onClick = { openPicker = PickerKind.WORKSPACE },
            )
            PillChip(
                text = "👤 ${(config.selectedProfile ?: config.activeProfile ?: "Default").take(14)} ⌄",
                onClick = { openPicker = PickerKind.PROFILE },
            )
        }
    }

    when (openPicker) {
        // Model picker: one section per provider group, plus a "server default"
        // row. Value is (modelId?, providerId?) — null means server default.
        PickerKind.MODEL -> HermexPickerSheet(
            title = "Model",
            sections = listOf(
                PickerSection<Pair<String?, String?>>(
                    header = null,
                    rows = listOf(PickerRow("Server default", null to null)),
                ),
            ) + config.modelGroups.map { group ->
                PickerSection(
                    header = group.name,
                    rows = group.models.map { PickerRow(it.displayName, it.id to it.providerId) },
                )
            },
            isSelected = { it.first == config.selectedModelId },
            onPick = { (modelId, providerId) ->
                viewModel.selectModel(modelId, providerId)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        PickerKind.PROFILE -> HermexPickerSheet(
            title = "Profile",
            sections = listOf(
                PickerSection(
                    header = null,
                    rows = listOf(PickerRow<String?>("Active profile", null)) +
                        config.profiles.map { PickerRow(it.displayName, it.name) },
                ),
            ),
            isSelected = { it == config.selectedProfile },
            searchable = config.profiles.size > 8,
            onPick = { name ->
                viewModel.selectProfile(name)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        PickerKind.WORKSPACE -> HermexPickerSheet(
            title = "Workspace",
            sections = listOf(
                PickerSection(
                    header = null,
                    rows = listOf(PickerRow<String?>("Session workspace", null)) +
                        config.workspaces.map {
                            PickerRow(it.name ?: it.path ?: "?", it.path, sublabel = it.path)
                        },
                ),
            ),
            isSelected = { it == config.selectedWorkspace },
            searchable = config.workspaces.size > 8,
            onPick = { path ->
                viewModel.selectWorkspace(path)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        null -> Unit
    }
}

@Composable
private fun SelectorText(label: String, onClick: () -> Unit) {
    val palette = LocalHermexPalette.current
    Text(
        "$label ⌄",
        style = MaterialTheme.typography.labelLarge,
        color = palette.textSecondary,
        modifier = Modifier.clickable(onClick = onClick),
        maxLines = 1,
    )
}

@Composable
private fun PillChip(text: String, onClick: () -> Unit) {
    val palette = LocalHermexPalette.current
    Surface(color = palette.card, shape = CircleShape, onClick = onClick) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private enum class PickerKind { MODEL, PROFILE, WORKSPACE }

/** Pending attachments above the composer, tap to remove — dark pills like iOS. */
@Composable
fun AttachmentStrip(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    if (state.attachments.isEmpty()) return
    val palette = LocalHermexPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.attachments.forEach { attachment ->
            Surface(
                color = palette.card,
                shape = CircleShape,
                onClick = { viewModel.removeAttachment(attachment) },
            ) {
                Text(
                    "${if (attachment.isImage) "🖼 " else "📄 "}${attachment.name}  ✕",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            suggestions.forEachIndexed { index, command ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        command.name?.let { if (it.startsWith("/")) it else "/$it" } ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.accent,
                    )
                    command.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(color = palette.bubble)
                }
            }
        }
    }
}
