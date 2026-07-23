package com.hermexapp.android

/**
 * Wave 6 Slice 6.1 — the sidebar-layout picker.
 *
 * On phones (screenWidthDp < 600) the left rail is an 88dp column of icon
 * buttons ([SidebarRailCompact]). On tablets and small foldables
 * (screenWidthDp >= 600) the left rail is the full
 * [com.hermexapp.android.features.sessionlist.SessionListScreen] — there's
 * enough horizontal room for titles, previews, and the FastScrollbar.
 *
 * The boundary is `>= 600`, matching Material's `MediumWidth` window-size
 * class. A 600dp-wide 10" tablet in portrait lands on the tablet rail.
 *
 * Pure JVM — no Android dependencies. Tested by [SidebarRailLayoutTest].
 */
object SidebarRailLayout {

    /**
     * Which rail to render for the current screen width.
     *
     *  - [COMPACT]  → 88dp phone rail ([SidebarRailCompact])
     *  - [EXPANDED] → 300dp tablet rail ([SessionListScreen])
     */
    enum class Mode {
        COMPACT,
        EXPANDED,
    }

    /**
     * Choose the rail mode for the given screen width in density-independent
     * pixels.
     *
     * Contract:
     *   - screenWidthDp <  600  → [Mode.COMPACT]
     *   - screenWidthDp >= 600  → [Mode.EXPANDED]
     *
     * Defensive: defensive against non-positive widths (0, negative) — must
     * not throw and must fall back to [Mode.COMPACT].
     */
    fun pickSidebarWidth(screenWidthDp: Int): Mode {
        // Defensive: non-positive widths (≤ 0, weird mid-measure) fall
        // back to the smaller rail so a buggy caller can never crash
        // layout. Tested by `pickSidebarWidth_zeroWidth_returnsCompact`
        // and `pickSidebarWidth_negativeWidth_returnsCompact`.
        if (screenWidthDp < 600) return Mode.COMPACT
        // Boundary is inclusive on the EXPANDED side — Material's
        // `MediumWidth` window-size class starts at exactly 600dp, so a
        // 600dp-wide 10" tablet in portrait gets the tablet rail.
        // The canary test pins this exact branch.
        return Mode.EXPANDED
    }
}
