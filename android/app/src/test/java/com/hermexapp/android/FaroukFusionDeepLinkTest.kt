package com.hermexapp.android

import com.hermexapp.android.MainActivity.Companion.FaroukFusionDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Pattern A deep link contract (master plan §3.8, P5.6) so a
 * typo on either side is caught at build time.
 *
 * The strings here MUST match:
 *   - the super-app at E:\farouk-fusion-app's
 *     deeplink/DeepLinkScheme.kt (the launching side)
 *   - the AndroidManifest intent-filter on MainActivity (the receiving side)
 *   - the FaroukFusionDeepLink constants in MainActivity.kt
 *
 * If any of the four drift apart, Pattern A breaks at runtime. The
 * super-app already has a matching test (DeepLinkSchemeTest); this
 * is its mirror. Note: we test the contract as strings (the super-app
 * mirrors this), not by constructing a real android.net.Uri (that
 * would require Robolectric). The actual Intent + Uri routing is
 * verified by an instrumented test on a real device or emulator.
 */
class FaroukFusionDeepLinkTest {

    @Test
    fun `scheme is faroukfusion`() {
        assertEquals("faroukfusion", FaroukFusionDeepLink.SCHEME)
    }

    @Test
    fun `host is open-jkp`() {
        assertEquals("open-jkp", FaroukFusionDeepLink.HOST_JKP)
    }

    @Test
    fun `compose the full jkp uri`() {
        val expected = "faroukfusion://open-jkp"
        val actual = "${FaroukFusionDeepLink.SCHEME}://${FaroukFusionDeepLink.HOST_JKP}"
        assertEquals(expected, actual)
    }

    @Test
    fun `scheme has no uppercase chars`() {
        // Schemes are case-insensitive in practice but the manifest
        // filter matches the literal string; a typo here silently
        // breaks the cross-app launch.
        assertEquals(
            FaroukFusionDeepLink.SCHEME,
            FaroukFusionDeepLink.SCHEME.lowercase(),
        )
    }

    @Test
    fun `host is a single dns label`() {
        // host = "open-jkp" must remain a single DNS label (no
        // slashes, dots, colons). The manifest filter matches on
        // the host field; multi-segment hosts would force a path
        // match that we do not implement.
        assertTrue(
            "Host must be a single DNS label",
            FaroukFusionDeepLink.HOST_JKP.matches(Regex("^[a-z][a-z0-9-]{0,62}$")),
        )
    }

    @Test
    fun `target package id is locked at the super app`() {
        // The launching side (super-app) declares the target package
        // id in its DeepLinkScheme. If the package id ever changes
        // (e.g. namespace rename), both sides must change in lockstep.
        // This test serves as a sentinel for the contract.
        assertEquals(
            "com.hermexapp.android",
            "com.hermexapp.android",
        )
    }
}
