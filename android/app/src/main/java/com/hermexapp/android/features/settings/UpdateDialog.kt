package com.hermexapp.android.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermexapp.android.network.GitHubAsset
import com.hermexapp.android.network.UpdateResult

/**
 * Three-state dialog shown after the user taps "Check for updates":
 *
 *  - [UpdateResult.UpdateAvailable] → "Update available" with version, size,
 *    release notes, and a "Download" button that opens the APK URL in the
 *    browser (which then downloads via Chrome's download manager, the
 *    standard sideload path).
 *  - [UpdateResult.UpToDate] → "You're on the latest version" with the
 *    checked-against tag for confirmation.
 *  - [UpdateResult.Failed] → "Couldn't check for updates" with the
 *    network-error reason (short message, not a stack trace).
 *
 * The dialog owns no state — it renders whatever result the caller hands
 * in. The caller (SettingsScreen) owns the loading state and the result,
 * which keeps the data flow easy to test and lets the dialog be re-used
 * later for an "auto-check on settings open" feature without churn.
 */
@Composable
fun UpdateDialog(
    loading: Boolean,
    result: UpdateResult?,
    currentVersion: String?,
    onDismiss: () -> Unit,
    onOpenReleasePage: (String) -> Unit = {},
) {
    if (!loading && result == null) return
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(text = dialogTitle(loading, result)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                            Text(
                                "Contacting GitHub…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    result is UpdateResult.UpdateAvailable -> UpdateAvailableBody(result)
                    result is UpdateResult.UpToDate -> UpToDateBody(result, currentVersion)
                    result is UpdateResult.Failed -> FailedBody(result)
                }
            }
        },
        confirmButton = {
            when (val r = result) {
                is UpdateResult.UpdateAvailable -> {
                    TextButton(onClick = {
                        r.release.apkAsset?.browserDownloadUrl?.let(onOpenReleasePage)
                            ?: r.release.htmlUrl.let(onOpenReleasePage)
                    }) { Text("Download") }
                }
                is UpdateResult.UpToDate, is UpdateResult.Failed -> {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (result is UpdateResult.UpdateAvailable) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

@Composable
private fun UpdateAvailableBody(result: UpdateResult.UpdateAvailable) {
    val apk: GitHubAsset? = result.release.apkAsset
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${result.currentVersion} → ${result.latestVersion}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (apk != null) {
            Text(
                "${apk.name} · ${apk.sizeMb}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.release.body.isNotBlank()) {
            Text(
                result.release.body.take(400) + if (result.release.body.length > 400) "…" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UpToDateBody(result: UpdateResult.UpToDate, currentVersion: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Installed: ${currentVersion ?: result.currentVersion}",
            style = MaterialTheme.typography.bodyMedium,
        )
        result.latestVersion?.let {
            Text(
                "Latest on GitHub: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedBody(result: UpdateResult.Failed) {
    Text(
        result.reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun dialogTitle(loading: Boolean, result: UpdateResult?): String = when {
    loading -> "Checking for updates…"
    result is UpdateResult.UpdateAvailable -> "Update available"
    result is UpdateResult.UpToDate -> "You're up to date"
    result is UpdateResult.Failed -> "Couldn't check for updates"
    else -> ""
}

/**
 * Opens a URL in the system browser (Chrome, Firefox, etc.) via
 * `Intent.ACTION_VIEW`. Falls back silently if nothing handles the intent —
 * Android will show a "no app to open this" toast on its own.
 */
fun openUrlInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}