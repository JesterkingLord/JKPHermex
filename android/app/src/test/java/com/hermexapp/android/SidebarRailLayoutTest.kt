package com.hermexapp.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-unit tests for the sidebar-width picker used by Wave 6 Slice 6.1.
 *
 * The picker decides whether the left rail is the 88dp phone-style rail
 * ([SidebarRailCompact]) or the 300dp tablet-style [SessionListScreen],
 * based on the current screenWidthDp.
 *
 * Boundary contract:
 *   - screenWidthDp <  600  → COMPACT   (phone rail)
 *   - screenWidthDp >= 600  → EXPANDED  (tablet list)
 *
 * The exact-600dp case is a **canary**: it pins the boundary to the
 * inclusive direction (`>=`), matching the Material window-size class
 * threshold for `MediumWidth` and preventing an off-by-one regression
 * where 600dp phones render the wrong rail.
 *
 * No Android dependencies — these run in pure JVM, in the same gradle
 * `testDebugUnitTest` task as the rest of the suite.
 */
class SidebarRailLayoutTest {

    @Test
    fun pickSidebarWidth_belowThreshold_returnsCompact() {
        // 599 is one dp below the 600 boundary — must stay on the phone rail.
        assertEquals(
            SidebarRailLayout.Mode.COMPACT,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 599),
        )
    }

    @Test
    fun pickSidebarWidth_atThreshold_returnsExpanded() {
        // CANARY TEST: exactly 600dp must be EXPANDED (`>=`, not `>`).
        // Material's "MediumWidth" window size class starts at 600dp,
        // so a 600dp-wide 10" tablet in portrait lands on the tablet rail.
        assertEquals(
            SidebarRailLayout.Mode.EXPANDED,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 600),
        )
    }

    @Test
    fun pickSidebarWidth_wellAboveThreshold_returnsExpanded() {
        // 1024dp is a typical 10" tablet landscape — unambiguously tablet.
        assertEquals(
            SidebarRailLayout.Mode.EXPANDED,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 1024),
        )
    }

    @Test
    fun pickSidebarWidth_phoneWidth_returnsCompact() {
        // 360dp is the canonical portrait phone width (Pixel-class).
        assertEquals(
            SidebarRailLayout.Mode.COMPACT,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 360),
        )
    }

    @Test
    fun pickSidebarWidth_foldableWidth_returnsExpanded() {
        // 841dp is the Galaxy Fold inner-display width (smallest foldable
        // tab that's still wider than 600dp). It must show the tablet rail
        // — not the cramped 88dp phone rail.
        assertEquals(
            SidebarRailLayout.Mode.EXPANDED,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 841),
        )
    }

    @Test
    fun pickSidebarWidth_watchSizedWidth_returnsCompact() {
        // 240dp is roughly a Wear-OS-class width — clearly the phone rail.
        assertEquals(
            SidebarRailLayout.Mode.COMPACT,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 240),
        )
    }

    @Test
    fun pickSidebarWidth_zeroWidth_returnsCompact() {
        // Defensive: if Compose ever reports a 0dp width during a layout
        // pass (e.g. mid-measure), the picker must not throw and must
        // degrade to the smaller rail.
        assertEquals(
            SidebarRailLayout.Mode.COMPACT,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 0),
        )
    }

    @Test
    fun pickSidebarWidth_negativeWidth_returnsCompact() {
        // Defensive: a negative width means a buggy caller; the picker
        // must not throw and must fall back to the smaller rail.
        assertEquals(
            SidebarRailLayout.Mode.COMPACT,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = -1),
        )
    }

    @Test
    fun pickSidebarWidth_extremeWidth_returnsExpanded() {
        // 65535 (Int.MAX_VALUE low) — sanity-check that the upper end
        // of `Int` doesn't accidentally trip the picker.
        assertEquals(
            SidebarRailLayout.Mode.EXPANDED,
            SidebarRailLayout.pickSidebarWidth(screenWidthDp = 65535),
        )
    }
}
