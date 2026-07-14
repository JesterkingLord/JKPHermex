package com.hermexapp.android.network

import com.hermexapp.android.network.ApiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of `GET /repos/{owner}/{repo}/releases/latest` we actually need.
 *
 * GitHub's full release payload is huge (~30 fields). The app only needs
 * `tag_name` (e.g. "v0.2.0"), the human-readable `name`, `body` (markdown
 * release notes — shown in the update dialog), and the list of assets so we
 * can find the `.apk` file and its download URL.
 *
 * Decoded with kotlinx.serialization (the same engine the rest of the
 * networking layer uses via [ApiJson]) so unit tests don't need a stubbed
 * `org.json` on the classpath.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String = tagName,
    @SerialName("body") val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * The first `.apk` asset in the release, if any. JKPHermex currently
     * ships a single debug APK per release, so "first" is enough — but we
     * filter by extension to stay correct if you ever attach a `.sha256` or
     * mapping file alongside.
     */
    val apkAsset: GitHubAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /**
     * The version string (e.g. "0.2.0") parsed from `tagName`. Strips a
     * leading `v` and any pre-release suffix (`-rc1`, `-beta.2`).
     *
     * Returns null if the tag is not a valid semver-ish string — callers
     * should treat that as "we don't know what's on the other end".
     */
    val semver: SemanticVersion?
        get() = SemanticVersion.parse(tagName)
}

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val sizeBytes: Long = 0L,
) {
    val sizeMb: String get() = "%.1f MB".format(sizeBytes / 1_000_000.0)
}

object GitHubReleaseJson {

    /**
     * Parses the body of `GET /repos/{owner}/{repo}/releases/latest`.
     * Throws on malformed JSON — the caller should surface that as
     * "couldn't parse release info" to the user.
     */
    fun parse(json: String): GitHubRelease = ApiJson.decodeFromString(GitHubRelease.serializer(), json)
}

/**
 * Minimal `MAJOR.MINOR.PATCH[-PRERELEASE]` parser. Handles `0.1.0`,
 * `0.2.0-rc1`, `1.10.4-beta.2`. Not full SemVer 2.0.0 — we don't need
 * build metadata or complex pre-release ordering. Returns null on garbage.
 *
 * Comparison rules (kept simple):
 *  - Higher major wins.
 *  - If majors tie, higher minor wins.
 *  - If minors tie, higher patch wins.
 *  - A version WITHOUT a pre-release tag is NEWER than the same version
 *    WITH one (0.2.0 > 0.2.0-rc1, the standard "release > release-candidate"
 *    rule).
 */
data class SemanticVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?) {
    fun isNewerThan(other: SemanticVersion): Boolean {
        if (major != other.major) return major > other.major
        if (minor != other.minor) return minor > other.minor
        if (patch != other.patch) return patch > other.patch
        // Same X.Y.Z — whichever has no pre-release wins.
        val a = preRelease.isNullOrEmpty()
        val b = other.preRelease.isNullOrEmpty()
        return when {
            a && !b -> true   // this is release, other is rc → newer
            !a && b -> false  // this is rc, other is release → not newer
            else -> false     // both release OR both pre-release → equal
        }
    }

    companion object {
        fun parse(raw: String): SemanticVersion? {
            val s = raw.trim().removePrefix("v").removePrefix("V")
            if (s.isEmpty()) return null
            val dash = s.indexOf('-')
            val numeric = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1).takeIf { it.isNotEmpty() } else null
            val parts = numeric.split('.')
            if (parts.size != 3) return null
            val (maj, min, pat) = try {
                Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            } catch (_: NumberFormatException) {
                return null
            }
            if (maj < 0 || min < 0 || pat < 0) return null
            return SemanticVersion(maj, min, pat, pre)
        }
    }
}