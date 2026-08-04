package com.hermexapp.android.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermexapp.android.model.PendingClarification
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Clarification overlay: the agent needs more information. Free-text answer plus
 * any offered choices as quick-pick chips. Mirrors the iOS `ClarificationRequestOverlay`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClarificationOverlay(clarification: PendingClarification, onRespond: (String) -> Unit) {
    val palette = LocalHermexPalette.current
    var answer by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* must be answered; ignore outside taps */ },
        confirmButton = {
            Button(onClick = { onRespond(answer.trim()) }, enabled = answer.isNotBlank()) {
                Text("Send")
            }
        },
        title = { Text("The agent needs input") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(clarification.displayQuestion, style = MaterialTheme.typography.bodyMedium)
                if (clarification.displayChoices.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        clarification.displayChoices.forEach { choice ->
                            OutlinedButton(onClick = { onRespond(choice) }) { Text(choice) }
                        }
                    }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type an answer", color = palette.textSecondary) },
                )
            }
        },
    )
}
