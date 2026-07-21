package com.hermexapp.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download progress state for the in-app updater.
 *
 * The UI renders each state distinctly:
 *  - [Downloading] → live progress bar (bytes / total, or indeterminate if
 *    the server didn't send Content-Length).
 *  - [ReadyToInstall] → download complete; the caller should invoke
 *    [ApkUpdater.installApk] to trigger the standard Android install dialog.
 *  - [Failed] → short error message + retry affordance.
 */
sealed class UpdateDownloadState {
    /** Download in progress. [totalBytes] is -1 when Content-Length is absent. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState() {
        /** 0.0–1.0, or -1f for indeterminate. */
        val fraction: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
    }

    /** Download complete — [apkFile] is ready to hand to the installer. */
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState()

    /** Download failed — [reason] is a short user-facing message. */
    data class Failed(val reason: String) : UpdateDownloadState()
}

/**
 * In-app APK updater. Downloads the newest JKPHermex APK from the backend
 * relay (`/api/hermex/latest-apk` — served over Tailscale, so it works
 * through the phone's VPN just like the version check) and triggers the
 * standard Android install-confirmation dialog.
 *
 * The download runs entirely on [Dispatchers.IO] (blocking OkHttp
 * `execute()` is fine there). Progress is emitted as a [Flow] so the UI
 * can show a live progress bar without polling.
 *
 * This never auto-installs: [installApk] fires `ACTION_VIEW` with the
 * APK's content:// URI, which always shows the user the standard
 * "Do you want to install this app?" confirmation screen.
 */
class ApkUpdater(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {

    /**
     * Download the newest APK from `[baseUrl]/api/hermex/latest-apk`.
     * Emits progress [UpdateDownloadState.Downloading] updates as bytes
     * arrive, then [UpdateDownloadState.ReadyToInstall] or
     * [UpdateDownloadState.Failed].
     *
     * The downloaded file is written to `cacheDir/apk/update.apk` (private
     * to this app, exposed to the installer only via FileProvider).
     */
    fun downloadLatestApk(
        context: Context,
        baseUrl: String,
    ): Flow<UpdateDownloadState> = flow {
        val url = baseUrl.trimEnd('/') + "/api/hermex/latest-apk"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JKPHermex-Android/updater")
            .build()

        val apkDir = File(context.cacheDir, "apk").apply { mkdirs() }
        val outFile = File(apkDir, "update.apk")

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(UpdateDownloadState.Failed("Server returned HTTP ${response.code}."))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(UpdateDownloadState.Failed("Empty response from server."))
                    return@flow
                }
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                outFile.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloadedBytes += read
                        emit(UpdateDownloadState.Downloading(downloadedBytes, totalBytes))
                    }
                    out.flush()
                }

                // Sanity: a real APK is ~11 MB; <1 MB means we got an error page.
                if (outFile.length() < 1_000_000) {
                    outFile.delete()
                    emit(UpdateDownloadState.Failed("Downloaded file is too small — likely an error page."))
                    return@flow
                }

                emit(UpdateDownloadState.ReadyToInstall(outFile))
            }
        } catch (e: IOException) {
            outFile.delete()
            emit(UpdateDownloadState.Failed("Download failed: ${e.message ?: "network error"}."))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Whether the app currently has permission to install APKs from unknown
     * sources. On API 26+ this is a per-app toggle the user grants in
     * Settings; on older versions it's a global toggle (effectively always
     * grantable, so we return true).
     */
    fun canInstallUnknownApps(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Open the system Settings screen where the user grants "install from
     * this app" (API 26+). On pre-O devices, opens the generic security
     * settings screen.
     */
    fun openInstallPermissionSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Trigger the standard Android install-confirmation dialog for [apkFile].
     *
     * Uses a `content://` URI via [FileProvider] (required since API 24 —
     * `file://` URIs are blocked for inter-app sharing). The installer
     * always shows the user a confirmation screen; this never auto-installs.
     */
    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
