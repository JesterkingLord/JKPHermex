package com.hermexapp.android.features.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.features.composer.ComposerFeatureRail
import com.hermexapp.android.model.AgentCommand
import com.hermexapp.android.model.ReasoningEffort
import com.hermexapp.android.ui.HermexPickerSheet
import com.hermexapp.android.ui.PickerRow
import com.hermexapp.android.ui.PickerSection
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ComposerPrimaryAction { SEND, STOP, DISABLED_SEND }

fun composerPrimaryAction(isStreaming: Boolean, hasDraft: Boolean): ComposerPrimaryAction = when {
    isStreaming && !hasDraft -> ComposerPrimaryAction.STOP
    hasDraft -> ComposerPrimaryAction.SEND
    else -> ComposerPrimaryAction.DISABLED_SEND
}

/** Keep system-button clearance only while the software keyboard is absent. */
fun shouldApplyComposerNavigationBarPadding(imeVisible: Boolean): Boolean = !imeVisible

/** Match modern chat composers so Gboard renders its candidate toolbar when empty. */
fun chatComposerKeyboardOptions(): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
)

/**
 * The iOS composer: one large rounded dark container holding the text field
 * ("Ask anything... /commands") and a control row (+ attach, model selector,
 * send circle), with workspace/profile pills beneath it.
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ComposerBar(
    viewModel: ChatViewModel,
    state: ChatViewModel.UiState,
    onSendHaptic: () -> Unit = {},
    onStopHaptic: () -> Unit = {},
    onLongPressSendHaptic: () -> Unit = {},
    onLongPressSend: (() -> Unit)? = null,
    onHideComposer: () -> Unit = {},
    // Wave 9: feature rail callbacks. The rail only renders when the
    // composer is empty — once the user starts typing, the typed text
    // owns the available vertical space. Every callback is optional,
    // letting the caller render a rail-less composer on screens that
    // don't want the chip row.
    onImproveDraft: (() -> Unit)? = null,
    onOpenTemplates: (() -> Unit)? = null,
    onInsertFromNotes: (() -> Unit)? = null,
    onInsertFromPrompts: (() -> Unit)? = null,
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
        modifier = Modifier
            .fillMaxWidth()
            // Wave 9: respect the gesture-nav inset so the bottom of the
            // composer doesn't crash into the phone's system button bar.
            // The Scaffold root owns `imePadding()`, which already raises
            // the composer when the keyboard appears — so this only adds
            // space when the keyboard is *down*.
            .then(
                if (shouldApplyComposerNavigationBarPadding(WindowInsets.isImeVisible)) {
                    Modifier.navigationBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    keyboardOptions = chatComposerKeyboardOptions(),
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

                // Wave 9: chip rail of quick actions. Hidden once the user
                // starts typing so the typed draft owns the available
                // vertical space. Only renders when at least one callback
                // is wired — empty on Notes-only screens, for example.
                val railCallbacks = listOfNotNull(
                    onImproveDraft,
                    onOpenTemplates,
                    onInsertFromNotes,
                    onInsertFromPrompts,
                )
                if (railCallbacks.isNotEmpty()) {
                    ComposerFeatureRail(
                        visible = state.composerText.isEmpty() && state.attachments.isEmpty(),
                        onImprove = { onImproveDraft?.invoke() },
                        onTemplates = { onOpenTemplates?.invoke() },
                        onInsertNotes = { onInsertFromNotes?.invoke() },
                        onInsertPrompts = { onInsertFromPrompts?.invoke() },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !state.isUploadingAttachment,
                        modifier = Modifier.size(48.dp),
                    ) {
                        if (state.isUploadingAttachment) {
                            Text("…", color = palette.textSecondary, fontSize = 20.sp)
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Attach image",
                                tint = palette.textSecondary,
                            )
                        }
                    }
                    IconButton(
                        onClick = onHideComposer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = "Hide message composer",
                            tint = palette.textSecondary,
                        )
                    }
                    SelectorText(
                        label = (config.selectedModelDisplayName ?: "model").take(14),
                        onClick = { openPicker = PickerKind.MODEL },
                        modifier = Modifier.weight(1f),
                    )

                    // Voice dictation → populates the composer only (iOS voice-input contract).
                    if (voice.isAvailable) {
                        IconButton(
                            onClick = { if (voice.isListening) voice.stop() else voice.start() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = if (voice.isListening) "Stop dictation" else "Start dictation",
                                tint = if (voice.isListening) palette.destructive else palette.textSecondary,
                            )
                        }
                    }

                    // Send when there's a draft; stop when idle-handed mid-run.
                    val hasDraft = state.composerText.isNotBlank() || state.attachments.isNotEmpty()
                    val primaryAction = composerPrimaryAction(state.isStreaming, hasDraft)
                    val showStop = primaryAction == ComposerPrimaryAction.STOP
                    val canSend = primaryAction == ComposerPrimaryAction.SEND
                    // Long-press to open the insert palette. We map this to the
                    // Show ↑, not Show ■, so a long-press during a run does
                    // nothing — it's discoverable, but doesn't conflict with the
                    // "stop" affordance.
                    val enabled = showStop || canSend
                    val longPressEnabled = onLongPressSend != null && !showStop && canSend
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = if (showStop) "Stop response" else "Send message"
                                role = Role.Button
                                if (!enabled) disabled()
                            }
                            .then(
                                if (longPressEnabled) {
                                    Modifier.combinedClickable(
                                        enabled = true,
                                        onClick = {
                                            if (showStop) {
                                                onStopHaptic()
                                                viewModel.stop()
                                            } else {
                                                onSendHaptic()
                                                viewModel.send()
                                            }
                                        },
                                        onLongClick = {
                                            onLongPressSendHaptic()
                                            onLongPressSend?.invoke()
                                        },
                                    )
                                } else {
                                    Modifier.clickable(enabled = enabled) {
                                        if (showStop) {
                                            onStopHaptic()
                                            viewModel.stop()
                                        } else {
                                            onSendHaptic()
                                            viewModel.send()
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (showStop) palette.destructive else palette.control,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (showStop) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                tint = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    palette.textSecondary
                                },
                            )
                        }
                    }
                }
            }
        }

        // Workspace + profile pills under the composer, like iOS.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(
                text = "Reasoning: ${reasoningShortLabel(state.selectedReasoningEffort)}",
                onClick = { openPicker = PickerKind.REASONING },
            )
            PillChip(
                text = "Workspace: ${
                    ((config.selectedWorkspace ?: config.lastWorkspace)
                        ?.substringAfterLast('/') ?: "workspace").take(16)
                }",
                onClick = { openPicker = PickerKind.WORKSPACE },
            )
            PillChip(
                text = "Profile: ${(config.selectedProfile ?: config.activeProfile ?: "Default").take(14)}",
                onClick = { openPicker = PickerKind.PROFILE },
            )
            PillChip(
                text = "Thinking: ${if (state.showReasoning) "on" else "off"}",
                onClick = { viewModel.setShowReasoning(!state.showReasoning) },
            )
        }

        // Non-blocking banner: the server told us to wait, or the effort was
        // clamped. Cleared the next time the user opens the picker.
        state.reasoningErrorMessage?.let { msg ->
            Text(
                msg,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = palette.warning,
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
        PickerKind.REASONING -> HermexPickerSheet(
            title = "Reasoning effort",
            sections = listOf(
                PickerSection(
                    header = null,
                    rows = ReasoningEffort.entries.map { effort ->
                        PickerRow(
                            label = effort.displayName,
                            value = effort,
                            sublabel = reasoningSublabel(effort),
                        )
                    },
                ),
            ),
            isSelected = { it == state.selectedReasoningEffort },
            onPick = { effort ->
                viewModel.selectReasoningEffort(effort)
                openPicker = null
            },
            onDismiss = { openPicker = null },
        )
        null -> Unit
    }
}

/** Two-letter short form for the composer chip. Keeps the row tight. */
private fun reasoningShortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.AUTO -> "auto"
    ReasoningEffort.NONE -> "off"
    ReasoningEffort.MINIMAL -> "min"
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> "med"
    ReasoningEffort.HIGH -> "high"
    ReasoningEffort.XHIGH -> "xhi"
}

/** One-liner explaining what the effort does — shown in the picker sheet. */
private fun reasoningSublabel(effort: ReasoningEffort): String? = when (effort) {
    ReasoningEffort.AUTO -> "Server picks the default (usually Medium)"
    ReasoningEffort.NONE -> "Disable reasoning blocks entirely"
    ReasoningEffort.MINIMAL -> "Lightest reasoning, fastest response"
    ReasoningEffort.LOW -> "Brief reasoning, low cost"
    ReasoningEffort.MEDIUM -> "Balanced (the server's default)"
    ReasoningEffort.HIGH -> "Deep reasoning, higher cost"
    ReasoningEffort.XHIGH -> "Maximum reasoning — extended-thinking models only"
}

@Composable
private fun SelectorText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.bubble,
        shape = CircleShape,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PillChip(text: String, onClick: () -> Unit) {
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.card,
        shape = CircleShape,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private enum class PickerKind { MODEL, PROFILE, WORKSPACE, REASONING }

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
                modifier = Modifier.heightIn(min = 48.dp),
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
                        .heightIn(min = 48.dp)
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
