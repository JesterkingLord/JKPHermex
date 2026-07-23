package com.hermexapp.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave 6 Slice 6.1 — selector for phone-compact (88dp) vs tablet-expanded
 * (300dp) sidebar rails based on horizontal screen width.
 *
 * The threshold is 600dp (Material 3 "Medium → Expanded" boundary). Phones
 * in portrait sit at 360–411dp and get the 88dp rail; tablets at 600dp+
 * get the 300dp rail with the full session list. The boundary at *exactly*
 * 600dp is inclusive — Expanded wins.
 *
 * This pure-Kotlin function is the contract: any layout decision MUST route
 * through here rather than recomputing "screenWidthDp >= 600" inline. The
 * tests pin the boundary so a refactor can't silently flip it.
 */
class SidebarRailLayoutTest {

    @Test
    fun pickSidebarWidth_belowThreshold_returnsCompact() {
        assertEquals(SidebarRailLayout.Mode.COMPACT, SidebarRailLayout.pickSidebarWidth(599))
    }

    @Test
    fun pickSidebarWidth_atThreshold_returnsExpanded() {
        // Canary: the >= boundary matters for layout correctness.
        assertEquals(SidebarRailLayout.Mode.EXPANDED, SidebarRailLayout.pickSidebarWidth(600))
    }

    @Test
    fun pickSidebarWidth_wellAboveThreshold_returnsExpanded() {
        assertEquals(SidebarRailLayout.Mode.EXPANDED, SidebarRailLayout.pickSidebarWidth(1024))
    }

    @Test
    fun pickSidebarWidth_phoneWidth_returnsCompact() {
        assertEquals(SidebarRailLayout.Mode.COMPACT, SidebarRailLayout.pickSidebarWidth(360))
    }

    @Test
    fun pickSidebarWidth_foldableWidth_returnsExpanded() {
        // Galaxy Fold inner display is roughly 841dp wide.
        assertEquals(SidebarRailLayout.Mode.EXPANDED, SidebarRailLayout.pickSidebarWidth(841))
    }

    @Test
    fun pickSidebarWidth_watchSizedWidth_returnsCompact() {
        assertEquals(SidebarRailLayout.Mode.COMPACT, SidebarRailLayout.pickSidebarWidth(240))
    }

    @Test
    fun pickSidebarWidth_zeroWidth_returnsCompact() {
        // Defensive: an unconfigured display shouldn't throw.
        assertEquals(SidebarRailLayout.Mode.COMPACT, SidebarRailLayout.pickSidebarWidth(0))
    }

    @Test
    fun pickSidebarWidth_negativeWidth_returnsCompact() {
        // Defensive: strange config reports shouldn't crash callers.
        assertEquals(SidebarRailLayout.Mode.COMPACT, SidebarRailLayout.pickSidebarWidth(-1))
    }

    @Test
    fun pickSidebarWidth_extremeWidth_returnsExpanded() {
        assertEquals(SidebarRailLayout.Mode.EXPANDED, SidebarRailLayout.pickSidebarWidth(65535))
    }

    @Test
    fun compactDp_is88dp() {
        assertEquals(88, SidebarRailLayout.Mode.COMPACT.dp)
    }

    @Test
    fun expandedDp_is300dp() {
        assertEquals(300, SidebarRailLayout.Mode.EXPANDED.dp)
    }
}
