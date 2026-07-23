package com.hermexapp.android.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.CircleButton
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Wave 4 Slice 4.2 — in-chat find UI.
 *
 * Thin overlay shown above the timeline when the user taps the 🔍 button in
 * the header. Pure stateless presentation; all logic lives on the VM.
 *
 * Layout (left → right):
 *   🔍  [query TextField  ][status text][↑][↓][✕]
 *
 * Status text comes from [ChatViewModel.UiState.searchStatusText] — either
 * "Type to search", "No matches", or "3 / 12".
 *
 * Keyboard: pressing Enter advances to the next match (search-bar standard
 * in browsers, Telegram, etc.). The TextField requests focus on first
 * composition so the IME pops immediately.
 */
@Composable
fun ChatSearchBar(
    query: String,
    statusText: String,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHermexPalette.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Refocus the field on every open (parent passes a fresh searchActive=true
    // → searchActive edge triggers this).
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = palette.textSecondary,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Find in chat") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
            )
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (statusText.startsWith("No matches")) {
                    palette.destructive
                } else {
                    palette.textSecondary
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            CircleButton(
                onClick = onPrev,
                glyph = "▲",
                size = 36,
            )
            CircleButton(
                onClick = onNext,
                glyph = "▼",
                size = 36,
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close search",
                    tint = palette.textSecondary,
                )
            }
        }
    }
}
