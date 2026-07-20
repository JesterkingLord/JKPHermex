package com.hermexapp.android.network

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of "is there a new version?" — sealed so the UI can render each
 * branch distinctly (spinner vs "up to date" vs "update available" vs
 * "network failed" vs "unparseable response").
 */
sealed class UpdateResult {
    /** The latest release on GitHub has a higher version than what's installed. */
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val release: GitHubRelease,
    ) : UpdateResult()

    /** Whatever's published is the same as or older than what's installed. */
    data class UpToDate(val currentVersion: String, val latestVersion: String?) : UpdateResult()

    /** Network call failed or returned non-2xx. */
    data class Failed(val reason: String) : UpdateResult()
}

/**
 * Fetches the latest release from a GitHub repo and compares it against
 * the installed app version.
 *
 * Wired up so the caller (SettingsScreen) can construct one cheaply per
 * "Check for updates" tap — `OkHttpClient` is expensive so it's held as a
 * constructor field. The HTTP timeout is intentionally short (10s) so a
 * stuck network call doesn't leave the settings screen looking frozen.
 *
 * GitHub's API is unauthenticated for public repos, rate-limited to 60
 * requests/hour per IP. For an app that the user opens maybe twice a day
 * that's plenty. If you ever need more, swap in a token here.
 */
class UpdateChecker(
    private val owner: String,
    private val repo: String,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * Checks for a newer version, using the installed app's versionName as
     * the baseline. `currentVersion` is fetched from PackageManager because
     * that's the source of truth — `BuildConfig.VERSION_NAME` could be
     * wrong if you're running a different build flavor.
     */
    fun check(context: Context): UpdateResult {
        val currentVersion = installedVersionName(context) ?: return UpdateResult.Failed(
            "Couldn't determine the installed app version.",
        )
        return checkAgainst(currentVersion)
    }

    /**
     * Pure version-comparison entry point — no Context needed. Useful
     * for tests and for the manual "Check for updates" button that
     * sometimes gets invoked with an explicit baseline.
     */
    fun checkAgainst(currentVersion: String): UpdateResult {
        val release = fetchLatest() ?: return UpdateResult.Failed(
            "Couldn't reach GitHub to check for updates.",
        )
        val current = SemanticVersion.parse(currentVersion)
        val latest = release.semver
        if (current == null || latest == null) {
            // We have the release but couldn't compare — at least tell the
            // user what's available even if we can't order them.
            return UpdateResult.UpToDate(currentVersion, latest?.let { release.tagName })
        }
        return if (latest.isNewerThan(current)) {
            UpdateResult.UpdateAvailable(currentVersion, release.tagName, release)
        } else {
            UpdateResult.UpToDate(currentVersion, release.tagName)
        }
    }

    private fun fetchLatest(): GitHubRelease? {
        // Use /releases (not /releases/latest) so pre-release builds (rc, beta)
        // are surfaced — /releases/latest only returns the newest *stable*
        // release, which means RC channels would never appear in-app.
        val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=1"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JKPHermex-Android/$owner")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                runCatching { GitHubReleaseJson.parseFirst(body) }.getOrNull()
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // org.json throws on malformed input — treat as "no release"
            // rather than crashing the settings screen.
            null
        }
    }

    companion object {
        fun installedVersionName(context: Context): String? = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}