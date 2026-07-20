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
 * Two routes, tried in order so a single dependency can't sink the check:
 *
 *  1. **Releases API** — `GET {apiBase}/repos/{owner}/{repo}/releases?per_page=1`.
 *     Rich payload (tag + notes + the .apk asset download URL). Unauthenticated
 *     calls are rate-limited to **60 requests/hour per IP**; on a phone that IP
 *     is a shared carrier/CGNAT pool, so 403s are common and expected.
 *  2. **Tags web page** — `GET {webBase}/{owner}/{repo}/tags`, scraped for the
 *     newest `/releases/tag/<tag>` link. Served by GitHub's web CDN, NOT the
 *     API, so it isn't rate-limited. Yields only the version (no per-release
 *     asset URL), so an available update links to the release page instead.
 *
 * The HTTP timeout is intentionally short (10s) so a stuck network call
 * doesn't leave the settings screen looking frozen.
 */
class UpdateChecker(
    private val owner: String,
    private val repo: String,
    private val httpClient: OkHttpClient = defaultClient(),
    /** Injectable for tests (MockWebServer). Production: api.github.com. */
    private val apiBase: String = "https://api.github.com",
    /** Injectable for tests (MockWebServer). Production: github.com. */
    private val webBase: String = "https://github.com",
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
        val latest = fetchLatest()
        if (latest !is SourceResult.Ok) {
            val reason = (latest as? SourceResult.Err)?.reason
                ?: "Couldn't reach GitHub to check for updates."
            return UpdateResult.Failed(reason)
        }
        val release = latest.release
        val current = SemanticVersion.parse(currentVersion)
        val latestVer = release.semver
        if (current == null || latestVer == null) {
            // We have the release but couldn't compare — at least tell the
            // user what's available even if we can't order them.
            return UpdateResult.UpToDate(currentVersion, release.tagName)
        }
        return if (latestVer.isNewerThan(current)) {
            UpdateResult.UpdateAvailable(currentVersion, release.tagName, release)
        } else {
            UpdateResult.UpToDate(currentVersion, release.tagName)
        }
    }

    /** Internal result of the two-route fetch. */
    private sealed class SourceResult {
        data class Ok(val release: GitHubRelease) : SourceResult()
        data class Err(val reason: String) : SourceResult()
    }

    /**
     * Try the rich Releases API first; if it's unavailable (rate-limit 403,
     * any non-2xx, or a transient network error) fall back to scraping the
     * tags page. Only returns [SourceResult.Err] when BOTH routes fail,
     * carrying the underlying reason so the dialog is self-diagnosing
     * instead of the old generic "Couldn't reach GitHub".
     */
    private fun fetchLatest(): SourceResult {
        val api = fetchFromApi()
        when (api) {
            is SourceResult.Ok -> return api
            is SourceResult.Err -> {
                val web = fetchFromWeb()
                if (web is SourceResult.Ok) return web
                // Both failed — surface the API reason (the primary route).
                return api
            }
        }
    }

    /** Route 1: Releases API (rich payload, rate-limited). */
    private fun fetchFromApi(): SourceResult {
        val url = "$apiBase/repos/$owner/$repo/releases?per_page=1"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JKPHermex-Android/$owner")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SourceResult.Err(apiFailureReason(response.code))
                }
                val body = response.body?.string()
                    ?: return SourceResult.Err("GitHub returned an empty response.")
                val release = runCatching { GitHubReleaseJson.parseFirst(body) }.getOrNull()
                    ?: return SourceResult.Err("Couldn't read GitHub's update info.")
                SourceResult.Ok(release)
            }
        } catch (_: IOException) {
            SourceResult.Err("No internet connection to GitHub.")
        } catch (_: RuntimeException) {
            SourceResult.Err("Couldn't read GitHub's update info.")
        }
    }

    /**
     * Route 2: tags web page (not API rate-limited). Yields only the version;
     * the release object links to the release page for the download button.
     */
    private fun fetchFromWeb(): SourceResult {
        val url = "$webBase/$owner/$repo/tags"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (JKPHermex update check)")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SourceResult.Err("GitHub's update page returned HTTP ${response.code}.")
                }
                val body = response.body?.string() ?: return SourceResult.Err(
                    "GitHub returned an empty response.",
                )
                val tag = newestTagFromHtml(body)
                    ?: return SourceResult.Err("Couldn't find any release on GitHub.")
                SourceResult.Ok(
                    GitHubRelease(
                        tagName = tag,
                        name = tag,
                        body = "",
                        htmlUrl = "$webBase/$owner/$repo/releases/tag/$tag",
                    ),
                )
            }
        } catch (_: IOException) {
            SourceResult.Err("No internet connection to GitHub.")
        } catch (_: RuntimeException) {
            SourceResult.Err("Couldn't read GitHub's update page.")
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

        /** Human-readable reason for a non-2xx API response. */
        private fun apiFailureReason(code: Int): String = when (code) {
            403 -> "GitHub rate limit reached (shared IP). Try again in a few minutes."
            404 -> "No releases are published on GitHub yet."
            in 500..599 -> "GitHub's servers had a problem (HTTP $code). Try again."
            else -> "GitHub returned an error (HTTP $code)."
        }

        /**
         * Extract the newest `vX.Y.Z[-pre]` tag from GitHub's tags-page HTML by
         * scanning `/releases/tag/<tag>` links in document order (newest first).
         * Pure + side-effect-free so unit tests lock it without a live page.
         */
        internal fun newestTagFromHtml(html: String): String? {
            val regex = Regex("/releases/tag/([^\"<>\\s]+)")
            for (m in regex.findAll(html)) {
                val tag = m.groupValues[1]
                if (SemanticVersion.parse(tag) != null) return tag
            }
            return null
        }
    }
}
