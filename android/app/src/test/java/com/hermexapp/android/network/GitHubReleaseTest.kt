package com.hermexapp.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GitHubReleaseJson.parse] and [SemanticVersion].
 *
 * These cover the two pieces of genuinely-interesting logic: the JSON
 * parser (turns GitHub's release payload into our data class) and the
 * semver comparator (orders `0.2.0-rc1 < 0.2.0`, handles garbage, etc.).
 *
 * The HTTP layer of [UpdateChecker] is one line of OkHttp — testing it
 * would be testing OkHttp, not us. The production call hits GitHub's
 * unauthenticated `/releases/latest` endpoint at runtime.
 */
class GitHubReleaseTest {

    @Test
    fun `parses a real-looking release payload`() {
        val json = """
            {
              "tag_name": "v0.2.0",
              "name": "JKPHermex v0.2.0",
              "body": "## What's new\n- Rebrand to JKP Mobile\n- GitHub update check",
              "html_url": "https://github.com/JesterkingLord/JKPHermex/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "JKPHermex-v0.2.0-debug.apk",
                  "browser_download_url": "https://github.com/.../JKPHermex-v0.2.0-debug.apk",
                  "size": 12345678
                }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseJson.parse(json)
        assertEquals("v0.2.0", release.tagName)
        assertEquals("JKPHermex v0.2.0", release.name)
        assertTrue(release.body.contains("Rebrand"))
        assertEquals(1, release.assets.size)
        assertEquals("JKPHermex-v0.2.0-debug.apk", release.assets[0].name)
        assertEquals(12345678L, release.assets[0].sizeBytes)
        assertNotNull(release.apkAsset)
        assertEquals("12.3 MB", release.apkAsset!!.sizeMb)
    }

    @Test
    fun `apkAsset returns null when no apk is attached`() {
        val json = """
            { "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
              "assets": [ { "name": "checksums.txt", "browser_download_url": "x", "size": 100 } ] }
        """.trimIndent()
        val release = GitHubReleaseJson.parse(json)
        assertNull(release.apkAsset)
    }

    @Test
    fun `apkAsset picks apk over other extensions`() {
        val json = """
            { "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
              "assets": [
                { "name": "README.md", "browser_download_url": "a", "size": 100 },
                { "name": "app.apk", "browser_download_url": "b", "size": 200 },
                { "name": "app.sha256", "browser_download_url": "c", "size": 50 }
              ] }
        """.trimIndent()
        val release = GitHubReleaseJson.parse(json)
        assertNotNull(release.apkAsset)
        assertEquals("app.apk", release.apkAsset!!.name)
    }

    @Test
    fun `apkAsset is case-insensitive on extension`() {
        val json = """
            { "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
              "assets": [ { "name": "JKP.APK", "browser_download_url": "x", "size": 1 } ] }
        """.trimIndent()
        val release = GitHubReleaseJson.parse(json)
        assertNotNull(release.apkAsset)
        assertEquals("JKP.APK", release.apkAsset!!.name)
    }

    @Test
    fun `empty assets list parses cleanly`() {
        val json = """{ "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "" }"""
        val release = GitHubReleaseJson.parse(json)
        assertTrue(release.assets.isEmpty())
        assertNull(release.apkAsset)
    }

    @Test
    fun `tolerates unknown fields in the payload`() {
        val json = """
            { "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
              "draft": false, "prerelease": false, "created_at": "2026-07-14T19:00:00Z",
              "author": { "login": "someone" },
              "assets": [] }
        """.trimIndent()
        val release = GitHubReleaseJson.parse(json)
        assertEquals("v0.2.0", release.tagName)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `throws on malformed JSON`() {
        val ex = runCatching { GitHubReleaseJson.parse("this is not json") }
        assertTrue("expected failure but got ${ex.getOrNull()}", ex.isFailure)
    }
}

class SemanticVersionTest {

    @Test
    fun `parses plain three-part versions`() {
        val v = SemanticVersion.parse("0.1.0")
        assertNotNull(v)
        assertEquals(0, v!!.major)
        assertEquals(1, v.minor)
        assertEquals(0, v.patch)
        assertNull(v.preRelease)
    }

    @Test
    fun `parses v-prefixed versions`() {
        val v = SemanticVersion.parse("v1.2.3")
        assertNotNull(v)
        assertEquals(1, v!!.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
    }

    @Test
    fun `parses pre-release suffixes`() {
        val v = SemanticVersion.parse("v0.2.0-rc1")
        assertNotNull(v)
        assertEquals(0, v!!.major)
        assertEquals(2, v.minor)
        assertEquals(0, v.patch)
        assertEquals("rc1", v.preRelease)
    }

    @Test
    fun `parses beta-dot pre-release`() {
        val v = SemanticVersion.parse("1.10.4-beta.2")
        assertNotNull(v)
        assertEquals("beta.2", v!!.preRelease)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("1"))
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("1.2.3.4"))
        assertNull(SemanticVersion.parse("a.b.c"))
        assertNull(SemanticVersion.parse("not a version"))
        assertNull(SemanticVersion.parse("1.-1.0"))
    }

    @Test
    fun `isNewerThan — major wins`() {
        assertTrue(SemanticVersion.parse("2.0.0")!!.isNewerThan(SemanticVersion.parse("1.9.9")!!))
    }

    @Test
    fun `isNewerThan — minor wins when major ties`() {
        assertTrue(SemanticVersion.parse("0.2.0")!!.isNewerThan(SemanticVersion.parse("0.1.99")!!))
    }

    @Test
    fun `isNewerThan — patch wins when major and minor tie`() {
        assertTrue(SemanticVersion.parse("0.1.1")!!.isNewerThan(SemanticVersion.parse("0.1.0")!!))
    }

    @Test
    fun `isNewerThan — release beats rc with same numeric parts`() {
        val release = SemanticVersion.parse("0.2.0")
        val rc = SemanticVersion.parse("0.2.0-rc1")
        assertTrue(release!!.isNewerThan(rc!!))
        assertTrue(!rc!!.isNewerThan(release))
    }

    @Test
    fun `isNewerThan — false when versions are equal`() {
        val a = SemanticVersion.parse("0.1.0")!!
        val b = SemanticVersion.parse("0.1.0")!!
        assertTrue(!a.isNewerThan(b))
        assertTrue(!b.isNewerThan(a))
    }
}