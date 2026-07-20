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
     * Route 2: git smart-HTTP refs (not API rate-limited, not JS-rendered).
     *
     * `GET {webBase}/{owner}/{repo}/info/refs?service=git-upload-pack` returns
     * plain pkt-line text listing every ref — including `refs/tags/vX.Y.Z`.
     * Unlike the HTML tags page (which GitHub now renders client-side, so a
     * raw fetch contains zero tag links) and unlike the API (60/hr rate limit
     * that shared phone/carrier IPs trip constantly), this endpoint is a
     * static git protocol dump: always present, never rate-limited, and it
     * survives VPN/proxy interception because it's not the JSON API.
     *
     * Yields only the newest version (no per-release asset URL), so an
     * available update links to the release page for the download button.
     */
    private fun fetchFromWeb(): SourceResult {
        val url = "$webBase/$owner/$repo/info/refs?service=git-upload-pack"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "git/2.0 (JKPHermex update check)")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SourceResult.Err("GitHub's git endpoint returned HTTP ${response.code}.")
                }
                val body = response.body?.string() ?: return SourceResult.Err(
                    "GitHub returned an empty response.",
                )
                val tag = newestTagFromGitRefs(body)
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
            SourceResult.Err("Couldn't read GitHub's update info.")
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
         * Extract the newest `vX.Y.Z[-pre]` tag from a git smart-HTTP
         * `info/refs?service=git-upload-pack` pkt-line response.
         *
         * Each ref line carries `<sha> refs/tags/<name>`; annotated tags also
         * emit a `<sha> refs/tags/<name>^{}` peeled line. We scan every
         * `refs/tags/` occurrence (the `^{}` suffix is excluded by the char
         * class), keep only valid semver tags, and return the highest.
         *
         * Uses [isTagNewer] rather than [SemanticVersion.isNewerThan] because
         * the latter deliberately treats two pre-releases with equal numeric
         * parts as unordered — but git refs for `v0.6.0-rc1`…`rc6` all share
         * `0.6.0`, so we need real pre-release ordering to pick `rc6`.
         *
         * Pure + side-effect-free so unit tests lock it against a captured
         * response without a live network call.
         */
        internal fun newestTagFromGitRefs(body: String): String? {
            val regex = Regex("refs/tags/([^\\s\\^\\u0000]+)")
            var best: SemanticVersion? = null
            var bestTag: String? = null
            for (m in regex.findAll(body)) {
                val ver = SemanticVersion.parse(m.groupValues[1]) ?: continue
                val cur = best
                if (cur == null || isTagNewer(ver, cur)) {
                    best = ver
                    bestTag = m.groupValues[1]
                }
            }
            return bestTag
        }

        /**
         * Total ordering over two parsed versions, including pre-release
         * identifiers (release > any pre-release, then natural-sort the
         * pre-release string so `rc6 > rc1` and `rc10 > rc9`).
         */
        private fun isTagNewer(candidate: SemanticVersion, current: SemanticVersion): Boolean {
            if (candidate.major != current.major) return candidate.major > current.major
            if (candidate.minor != current.minor) return candidate.minor > current.minor
            if (candidate.patch != current.patch) return candidate.patch > current.patch
            val cPre = candidate.preRelease
            val bPre = current.preRelease
            if (cPre.isNullOrEmpty()) return !bPre.isNullOrEmpty()
            if (bPre.isNullOrEmpty()) return false
            return comparePreRelease(cPre, bPre) > 0
        }

        /**
         * Pragmatic semver-ish pre-release comparison: split on `.`/`-`,
         * compare token-by-token with a natural (digit-aware) ordering.
         * Not full SemVer 2.0.0, but correct for the `rcN` / `beta.N` shapes
         * this repo actually ships.
         */
        private fun comparePreRelease(a: String, b: String): Int {
            val pa = a.split('.', '-')
            val pb = b.split('.', '-')
            val n = minOf(pa.size, pb.size)
            for (i in 0 until n) {
                val xa = pa[i]; val xb = pb[i]
                val na = xa.toIntOrNull(); val nb = xb.toIntOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1 // numeric identifier < alphanumeric
                    nb != null -> 1
                    else -> naturalCompare(xa, xb)
                }
                if (cmp != 0) return cmp
            }
            return pa.size.compareTo(pb.size)
        }

        /** Natural (digit-run-aware) string compare: `rc10 > rc9`. */
        private fun naturalCompare(a: String, b: String): Int {
            var i = 0; var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]; val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ni = i; while (ni < a.length && a[ni].isDigit()) ni++
                    var nj = j; while (nj < b.length && b[nj].isDigit()) nj++
                    val na = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
                    val nb = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
                    val cmp = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                    if (cmp != 0) return cmp
                    i = ni; j = nj
                } else {
                    if (ca != cb) return ca.compareTo(cb)
                    i++; j++
                }
            }
            return a.length.compareTo(b.length)
        }
    }
}
