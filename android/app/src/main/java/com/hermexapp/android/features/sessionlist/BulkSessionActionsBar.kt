package com.hermexapp.android.features.sessionlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Contextual top bar shown above the session list while the user is in bulk-
 * select mode (long-press on a row).
 *
 * Layout, left → right:
 *   • Cancel — leaves selection mode and clears the set
 *   • "N selected" — current count, ellipsises if it overflows
 *   • Select all / Deselect all — toggles bulk-selection for everything visible
 *   • Pin / Archive / Delete — applies to every selected session; Delete
 *     pulls the shared confirm dialog from the parent screen via [onDeleteRequest]
 *
 * Why a separate composable (vs. inline `if (selectionMode) { ... }` in the
 * session list):
 *   • Keeps `SessionListScreen` readable — the long-press and the per-row
 *     click logic stay focused on the list
 *   • Lends itself to the eventual "double-pane tablet" layout (Wave 5)
 *     where the toolbar can sit in a side rail
 *   • Lets the action icons be themed centrally without bleeding into the
 *     list composable
 *
 * Swipe-to-dismiss on individual rows is disabled while the bar is visible
 * (handled in the screen — the bar's presence is the signal).
 */
@Composable
fun BulkSessionActionsBar(
    selectedCount: Int,
    totalVisible: Int,
    onCancel: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHermexPalette.current
    val allSelected = totalVisible > 0 && selectedCount == totalVisible

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.card,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionIcon(
                icon = Icons.Filled.Close,
                contentDescription = "Cancel selection",
                tint = palette.textSecondary,
                onClick = onCancel,
            )
            Text(
                text = if (selectedCount == 0) "Select sessions" else "$selectedCount selected",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                fontWeight = FontWeight.SemiBold,
            )
            ActionIcon(
                icon = Icons.Filled.DoneAll,
                contentDescription = if (allSelected) "Deselect all" else "Select all",
                tint = if (allSelected) palette.accent else palette.textSecondary,
                onClick = onToggleSelectAll,
            )
            // Pin / Archive / Delete are disabled when 0 selected so the user
            // can't tap them by accident. Material's recommended pattern is
            // to keep them visible (so the discoverable surface area stays
            // constant) but to grey them out.
            ActionIcon(
                icon = Icons.Filled.PushPin,
                contentDescription = "Pin selected",
                tint = if (selectedCount > 0) palette.textSecondary else palette.textSecondary.copy(alpha = 0.35f),
                onClick = { if (selectedCount > 0) onPin() },
            )
            ActionIcon(
                icon = Icons.Filled.Archive,
                contentDescription = "Archive selected",
                tint = if (selectedCount > 0) palette.warning else palette.textSecondary.copy(alpha = 0.35f),
                onClick = { if (selectedCount > 0) onArchive() },
            )
            ActionIcon(
                icon = Icons.Filled.Delete,
                contentDescription = "Delete selected",
                tint = if (selectedCount > 0) palette.destructive else palette.textSecondary.copy(alpha = 0.35f),
                onClick = { if (selectedCount > 0) onDelete() },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 40dp circular touch target consistent with `CircleButton` in `HermexComponents.kt`. */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(LocalHermexPalette.current.bubble, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
