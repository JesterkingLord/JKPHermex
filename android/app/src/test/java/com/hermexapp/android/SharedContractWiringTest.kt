package com.hermexapp.android

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wires the Android test suite to the repo-level contract artifacts shared
 * with the iOS app: the root UPSTREAM_TESTED_SHA pin and the recorded JSON
 * corpus in shared/fixtures/ (Android port plan §5). Every fixture that
 * lands there must at minimum be valid JSON; endpoint-specific contract
 * tests build on this from phase 1 onward.
 */
class SharedContractWiringTest {

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            if (File(dir, "UPSTREAM_TESTED_SHA").isFile) return dir
            dir = dir.parentFile
        }
        fail("Could not locate the repo root (no UPSTREAM_TESTED_SHA above $userDir)")
        error("unreachable")
    }

    @Test
    fun `upstream pin exists and is a full-length commit sha`() {
        val pin = File(repoRoot(), "UPSTREAM_TESTED_SHA").readText().trim()
        assertTrue(
            "UPSTREAM_TESTED_SHA must be a 40-char lowercase hex sha, got: '$pin'",
            Regex("^[0-9a-f]{40}$").matches(pin),
        )
    }

    @Test
    fun `every shared fixture parses as JSON`() {
        val fixturesDir = File(repoRoot(), "shared/fixtures")
        assertTrue("shared/fixtures/ must exist at the repo root", fixturesDir.isDirectory)

        val fixtures = fixturesDir.walkTopDown().filter { it.extension == "json" }.toList()
        // Empty is fine today — the corpus is recorded as endpoint contract
        // tests need it. What must never happen is an unparseable fixture.
        for (fixture in fixtures) {
            try {
                Json.parseToJsonElement(fixture.readText())
            } catch (e: Exception) {
                fail("Fixture ${fixture.relativeTo(fixturesDir)} is not valid JSON: ${e.message}")
            }
        }
    }
}
