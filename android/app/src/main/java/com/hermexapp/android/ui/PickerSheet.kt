package com.hermexapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/** One selectable row in a [HermexPickerSheet]. */
data class PickerRow<T>(val label: String, val value: T, val sublabel: String? = null)

/** A group of rows under an optional header (e.g. a model-provider group). */
data class PickerSection<T>(val header: String?, val rows: List<PickerRow<T>>)

/**
 * The themed bottom-sheet picker used for model / profile / workspace selection
 * (and the default-model setting) — the Android counterpart of the iOS selection
 * sheets. On the Hermex canvas with a search field, provider group headers, and a
 * gold checkmark on the current selection, replacing plain Material dropdowns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> HermexPickerSheet(
    title: String,
    sections: List<PickerSection<T>>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    searchable: Boolean = true,
) {
    val palette = LocalHermexPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    // Filter rows by label/sublabel; drop sections left empty by the filter.
    val filtered = remember(sections, query) {
        if (query.isBlank()) {
            sections
        } else {
            val q = query.trim().lowercase()
            sections.mapNotNull { section ->
                val rows = section.rows.filter {
                    it.label.lowercase().contains(q) || it.sublabel?.lowercase()?.contains(q) == true
                }
                if (rows.isEmpty()) null else section.copy(rows = rows)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.canvas,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )

            if (searchable) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(palette.card, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(palette.accent),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("Search", color = palette.textSecondary, fontSize = 16.sp)
                            }
                            inner()
                        },
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                filtered.forEach { section ->
                    section.header?.let { header ->
                        item(key = "header-$header") {
                            Text(
                                header.uppercase(),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textSecondary,
                            )
                        }
                    }
                    items(section.rows.size) { index ->
                        val row = section.rows[index]
                        PickerRowView(
                            row = row,
                            selected = isSelected(row.value),
                            onClick = { onPick(row.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> PickerRowView(row: PickerRow<T>, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalHermexPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.sublabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            // Gold checkmark on the active selection (iOS + 9thLevel parity).
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Text("✓", color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}
