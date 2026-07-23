package com.hermexapp.android

/**
 * Wave 6 Slice 6.1 — pure-Kotlin decision for the persistent sidebar rail.
 *
 * The sidebar rail is permanent on phones (Compact, 88dp wide — enough for a
 * column of icon buttons) and a full session list on tablets (Expanded,
 * 300dp). The boundary sits exactly on Material 3's Medium→Expanded window
 * width breakpoint (600dp), inclusive toward Expanded so a 600dp layout
 * (the smallest Android tablet profile) gets the full list.
 *
 * The function is deliberately pure-Kotlin (no androidx.compose imports) so
 * that [SidebarRailLayoutTest] can run as a plain JVM unit test without
 * Robolectric or instrumented infrastructure. The Compose-side caller reads
 * the result and applies Modifier.width(dp.dp) appropriately.
 */
object SidebarRailLayout {

    /** Boundary between compact phone rail and expanded tablet list. */
    const val EXPANDED_THRESHOLD_DP: Int = 600

    /** Output modes — extends to a `.dp` for Compose callers. */
    enum class Mode(val dp: Int) {
        /** 88dp Wordmark + 4-icon rail. */
        COMPACT(88),
        /** 300dp full session list (used on sw >= 600dp). */
        EXPANDED(300),
    }

    /**
     * Pick the sidebar mode for a given horizontal width in dp.
     *
     * Inputs at or above [EXPANDED_THRESHOLD_DP] return [Mode.EXPANDED];
     * anything else returns [Mode.COMPACT]. Negative or zero inputs also
     * return [Mode.COMPACT] so a misconfigured [android.content.res.Configuration]
     * can't crash the layout pass.
     */
    fun pickSidebarWidth(screenWidthDp: Int): Mode =
        if (screenWidthDp >= EXPANDED_THRESHOLD_DP) Mode.EXPANDED else Mode.COMPACT
}
