package com.hermexapp.android.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermexapp.android.network.GitHubAsset
import com.hermexapp.android.network.UpdateResult
import com.hermexapp.android.update.UpdateDownloadState

/**
 * Four-state update dialog shown after the user taps "Check for updates":
 *
 *  - [UpdateResult.UpdateAvailable] → "Update available" with version, size,
 *    release notes, and a "Download & Install" button that downloads the APK
 *    in-app (via the backend relay) and triggers the standard Android install
 *    dialog. While downloading, a live progress bar replaces the button.
 *  - [UpdateResult.UpToDate] → "You're on the latest version" with the
 *    checked-against tag for confirmation.
 *  - [UpdateResult.Failed] → "Couldn't check for updates" with the
 *    network-error reason (short message, not a stack trace).
 *  - [downloadState] → Download/install progress overlay. When downloading,
 *    shows a progress bar. When ready, shows "Install" button. When failed,
 *    shows the error and a "Retry" button.
 *
 * The dialog owns no download state — the caller (SettingsScreen) owns the
 * [UpdateDownloadState] and the [ApkUpdater], which keeps the data flow
 * easy to test and lets the dialog be re-used for the auto-check snackbar
 * in MainActivity without churn.
 */
@Composable
fun UpdateDialog(
    loading: Boolean,
    result: UpdateResult?,
    currentVersion: String?,
    downloadState: UpdateDownloadState?,
    onDismiss: () -> Unit,
    onDownloadAndInstall: () -> Unit = {},
    onRetryDownload: () -> Unit = {},
    onOpenReleasePage: (String) -> Unit = {},
) {
    if (!loading && result == null && downloadState == null) return

    // Download is in flight or complete — show the download overlay instead
    // of the "update available" body, but keep the same dialog shell.
    val showDownloadUi = downloadState != null

    AlertDialog(
        onDismissRequest = { if (!loading && downloadState !is UpdateDownloadState.Downloading) onDismiss() },
        title = { Text(text = dialogTitle(loading, result, showDownloadUi)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> CheckingBody()
                    showDownloadUi -> DownloadBody(downloadState!!)
                    result is UpdateResult.UpdateAvailable -> UpdateAvailableBody(result)
                    result is UpdateResult.UpToDate -> UpToDateBody(result, currentVersion)
                    result is UpdateResult.Failed -> FailedBody(result)
                }
            }
        },
        confirmButton = {
            when {
                downloadState is UpdateDownloadState.Downloading -> {
                    // No confirm button while downloading (can't cancel mid-stream).
                }
                downloadState is UpdateDownloadState.ReadyToInstall -> {
                    TextButton(onClick = onDismiss) { Text("Install") }
                }
                downloadState is UpdateDownloadState.Failed -> {
                    TextButton(onClick = onRetryDownload) { Text("Retry") }
                }
                result is UpdateResult.UpdateAvailable -> {
                    TextButton(onClick = onDownloadAndInstall) { Text("Download & Install") }
                }
                result is UpdateResult.UpToDate || result is UpdateResult.Failed -> {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            }
        },
        dismissButton = {
            if ((result is UpdateResult.UpdateAvailable && downloadState == null) ||
                downloadState is UpdateDownloadState.Failed
            ) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

@Composable
private fun CheckingBody() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        Text(
            "Checking for updates…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DownloadBody(state: UpdateDownloadState) {
    when (state) {
        is UpdateDownloadState.Downloading -> {
            Text(
                "Downloading update…",
                style = MaterialTheme.typography.bodyMedium,
            )
            val frac = state.fraction
            if (frac >= 0f) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${mb(state.downloadedBytes)} / ${mb(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    mb(state.downloadedBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is UpdateDownloadState.ReadyToInstall -> {
            Text(
                "Download complete. Tap Install to update.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is UpdateDownloadState.Failed -> {
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
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

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

private fun dialogTitle(loading: Boolean, result: UpdateResult?, downloading: Boolean): String = when {
    loading -> "Checking for updates…"
    downloading -> "Updating…"
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
