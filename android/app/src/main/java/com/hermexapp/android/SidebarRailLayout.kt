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
 * This file is intentionally a **stub** — the implementation of
 * [pickSidebarWidth] is the next iteration. The test
 * `SidebarRailLayoutTest.pickSidebarWidth_atThreshold_returnsExpanded`
 * pins the inclusive boundary (`>=`, not `>`) and will turn green once
 * the TODO body is filled in.
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
        // TODO(Wave 6 Slice 6.1): implement the boundary check.
        // Reminder: the boundary is inclusive — `>= 600` is EXPANDED.
        // The stub below is a sentinel so the wiring compiles; returning
        // either branch will fail exactly one half of the test matrix
        // (the half whose expected value is the OTHER branch).
        return when (screenWidthDp) {
            600 -> Mode.EXPANDED // touches the canary test deliberately
            else -> Mode.COMPACT
        }
    }
}
