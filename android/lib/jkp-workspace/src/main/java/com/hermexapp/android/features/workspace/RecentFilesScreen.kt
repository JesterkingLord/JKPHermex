package com.hermexapp.android.features.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.CircleButton
import com.hermexapp.android.ui.HermexHeader
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Files from across recent sessions (Files library, slice 5).
 *
 * The subtitle always names how many sessions were read, because "Recent
 * files" on its own would imply every session — and a list that quietly
 * covered ten of sixty while looking complete is worse than no list.
 */
@Composable
fun RecentFilesScreen(
    viewModel: RecentFilesViewModel,
    onOpenFile: (sessionId: String, path: String) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = LocalHermexPalette.current

    LaunchedEffect(Unit) {
        if (!state.hasLoaded && !state.isLoading) viewModel.refresh()
    }
    BackHandler { onClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.canvas,
        topBar = {
            HermexHeader(
                title = "Recent files",
                subtitle = recentFilesSubtitle(state),
                onBack = onClose,
                actions = {
                    CircleButton(
                        onClick = { viewModel.refresh() },
                        contentDescription = "Refresh recent files",
                        icon = Icons.Filled.Refresh,
                        size = 40,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.errorMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = palette.destructive,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // The filter is state, not a fetch: switching tabs must not cost
            // another ten requests.
            val visible = viewModel.visibleFiles(state)

            if (state.canFilterByUpload && state.files.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecentFileFilter.entries.forEach { option ->
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { viewModel.setFilter(option) },
                            label = { Text(option.label, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 40.dp),
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
            }

            when {
                state.isLoading && state.files.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                visible.isEmpty() && state.hasLoaded ->
                    EmptyPanel(recentFilesEmptyMessage(state.filter))

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.stableId }) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    val path = file.entry.path ?: return@clickable
                                    onOpenFile(file.source.sessionId, path)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(workspaceFileKind(file.entry).glyph)
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    file.entry.name ?: file.entry.path ?: "?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Which session this came from, so an unfamiliar
                                // filename is still placeable.
                                Text(
                                    recentFileSourceLabel(file.source),
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
    }
}

/**
 * The honest one-liner under the title.
 *
 * Pure, and separate from the composable, so the wording of the partial case
 * can be asserted in a unit test rather than only seen on a device.
 */
internal fun recentFilesSubtitle(state: RecentFilesViewModel.UiState): String? = when {
    !state.hasLoaded -> null
    state.sessionsAttempted == 0 -> null
    state.isPartial -> {
        val read = state.sessionsAttempted - state.sessionsFailed
        "From $read of your last ${state.sessionsAttempted} sessions"
    }
    else -> "From your last ${state.sessionsAttempted} sessions"
}
