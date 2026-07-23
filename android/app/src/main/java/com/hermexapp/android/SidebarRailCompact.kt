package com.hermexapp.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Wave 6 Slice 6.1 — the **phone** sidebar rail.
 *
 * On tablets (sw >= 600dp) we render a full [com.hermexapp.android.features.sessionlist.SessionListScreen]
 * in the rail — there's enough horizontal room for titles, previews, and the
 * FastScrollbar. On phones the rail is **88dp wide** and just shows a vertical
 * column of buttons:
 *
 *  - Top:    [BACK]     → back to the full SessionList (sets `screen = SessionList`)
 *  - Top:    [JKP logo]
 *  - Mid:    [NEW]      → create a fresh chat and open it (M3 FAB-ish)
 *  - Mid:    [LIST]     → back to the full SessionList
 *  - Bot:    [GEAR]     → Settings
 *
 * Hidden behaviour:
 *  - Wordmark is rotated 90° so it reads vertically (like a book spine)
 *    and the Brand feels "scrollable". When 88dp isn't enough for vertical
 *    text + glyph, the wordmark appears as a single dim circle.
 *  - Tapping outside the chat to dismiss focus is the system Back gesture —
 *    we don't add an in-rail "X". The Wordmark is the home affordance.
 *
 * Why an 88dp rail instead of a hamburger:
 *  - One fixed rail is always visible, like every major chat client (Slack,
 *    Discord, ChatGPT iOS). A hamburger makes the app feel like a mobile
 *    utility, not a chat workspace.
 *  - 88dp is just over the Material 48dp touch-target minimum, with room for
 *    a column of icon buttons.
 */
@Composable
fun SidebarRailCompact(
    onBackToList: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHermexPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas)
            // Visual separator on the right edge — gives the rail a clear border
            // against the chat content. Drop on tablets (handled by the parent
            // layout's chrome).
            .padding(end = 1.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Back / home button — returns to the full session list.
            RailIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = "Back to all conversations",
                palette = palette,
                onClick = onBackToList,
            )
            // Brand dot — a 32dp filled circle in the accent color. Acts as the
            // visual anchor for the rail ("this is JKP Mobile, do you know?").
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(palette.accent, CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            // New chat — same role as the FAB in the full session list.
            RailIconButton(
                icon = Icons.Filled.Add,
                description = "New chat",
                palette = palette,
                onClick = onNewChat,
            )
            // Chat list shortcut — same destination as BACK but redundantly
            // surfaced for muscle memory ("I want my list of chats").
            RailIconButton(
                icon = Icons.Filled.ChatBubbleOutline,
                description = "All conversations",
                palette = palette,
                onClick = onBackToList,
            )
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.weight(1f))
            RailIconButton(
                icon = Icons.Filled.Settings,
                description = "Settings",
                palette = palette,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun RailIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: com.hermexapp.android.ui.theme.HermexPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(palette.card, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,  // semantics above carries it
            tint = palette.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}
