package com.hermexapp.android.features.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Wave 9 (2026-07-28) — ComposerFeatureRail.
 *
 * A horizontal chip rail that sits **inside the composer card, beneath the
 * text field, above the controls row**, and replaces what users used to call
 * "the gray box". The rail shows a few lightweight buttons:
 *
 *   * **Improve**    — opens the [ImprovePromptSheet] with the current draft
 *                       pre-filled and walks the user through a one-tap
 *                       AI rewrite using a fast, cheap default model.
 *   * **Templates**  — opens the [ComposerTemplatesSheet] (next-best-action
 *                       starters: "Explain this", "Write tests", "Refactor",
 *                       "Plan", "Summarize", "Find bugs").
 *   * **From Note**  — opens the InsertPaletteSheet filtered to notes only.
 *   * **From Prompt** — opens the InsertPaletteSheet filtered to prompts only.
 *
 * The rail is hidden when the composer is non-empty — at that point the
 * user has already typed something and the rail's quick-actions would be
 * in the way of a typed draft.
 *
 * Pure UI: no side effects, no state of its own. Each chip just calls back
 * into the caller via `onImprove`/`onTemplates`/`onInsertNotes`/
 * `onInsertPrompts`. Hooked up by `ComposerBar`.
 *
 * @param visible when true the rail slides in beneath the text field.
 *   When false the rail stays hidden so the typed draft owns that space.
 * @param onImprove called when the user taps the "Improve" chip.
 * @param onTemplates called when the user taps the "Templates" chip.
 * @param onInsertNotes called when the user taps the "From Note" chip.
 * @param onInsertPrompts called when the user taps the "From Prompt" chip.
 */
@Composable
fun ComposerFeatureRail(
    visible: Boolean,
    onImprove: () -> Unit,
    onTemplates: () -> Unit,
    onInsertNotes: () -> Unit,
    onInsertPrompts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHermexPalette.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Fixed-height rail so the composer height doesn't pump on show/hide.
        LazyRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            // Built-in tiles — order matters: most-likely-used first.
            items(
                listOf(
                    FeatureTile(label = "✨ Improve", key = "improve", callback = onImprove),
                    FeatureTile(label = "🧩 Templates", key = "templates", callback = onTemplates),
                    FeatureTile(label = "📝 From note", key = "note", callback = onInsertNotes),
                    FeatureTile(label = "💬 From prompt", key = "prompt", callback = onInsertPrompts),
                ),
            ) { tile ->
                AssistChip(
                    onClick = tile.callback,
                    label = {
                        Text(
                            tile.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = palette.card,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = null,
                )
            }
        }
    }
}

/** Identifies a tile in the composer feature rail. Pure data; the tile itself
 *  holds no Compose state so `items(...)` can use stable identity. */
private data class FeatureTile(
    val label: String,
    val key: String,
    val callback: () -> Unit,
)
