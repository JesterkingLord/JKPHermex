package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 9.8 (2026-07-24) — Tests for [ScrollIndicatorOnly], the single
 * yellow pill the user asked for in the v0.8.7 → v0.8.8 transition.
 *
 * History of these tests:
 *   - v0.8.5: JumpFabVisibilityTest was added, pinning a navigation-
 *     control FAB (jump-to-top/jump-to-bottom). User said no.
 *   - v0.8.6: tests pinned the (broken) dual-pill contract. Same.
 *   - v0.8.7: tests pinned mutual-exclusion roles. User said no, again.
 *   - v0.8.8 (this revision): the navigation affordance is REMOVED.
 *     The only function the test references is
 *     [ScrollIndicatorOnly.decideVisibility]; any test file mentioning
 *     JumpFab / JumpChip / JumpFabRole / JumpTarget will simply fail
 *     to compile. The white pill no longer exists.
 */
class ScrollIndicatorOnlyTest {

    @Test
    fun `visible while scrolling`() {
        for (hideAfter in listOf(true, false)) {
            for (scrollable in listOf(true, false)) {
                if (!scrollable) continue
                assertTrue(
                    "scrolling=true, hideAfter=$hideAfter",
                    decideScrollIndicatorVisibility(
                        isScrolling = true,
                        hideAfterScrollStop = hideAfter,
                        contentIsScrollable = scrollable,
                    )
                )
            }
        }
    }

    @Test
    fun `visible during the 1-second grace window after scrolling stops`() {
        // isScrolling just became false. The LaunchedEffect flips
        // hideAfterScrollStop=false; visibility is then still true.
        assertTrue(
            decideScrollIndicatorVisibility(
                isScrolling = false,
                hideAfterScrollStop = false,
                contentIsScrollable = true,
            )
        )
    }

    @Test
    fun `hidden after the 1-second grace window elapses`() {
        // After delay(1000) completes, hideAfterScrollStop becomes
        // true. The pill is now permanently hidden until scrolling
        // resumes.
        assertFalse(
            decideScrollIndicatorVisibility(
                isScrolling = false,
                hideAfterScrollStop = true,
                contentIsScrollable = true,
            )
        )
    }

    @Test
    fun `hidden when there is nothing to scroll`() {
        // Single-message-fits-on-screen chat, or empty chat.
        for (scrolling in listOf(true, false)) {
            for (hiddenAfter in listOf(true, false)) {
                assertFalse(
                    "scrolling=$scrolling, hiddenAfter=$hiddenAfter, content not scrollable",
                    decideScrollIndicatorVisibility(
                        isScrolling = scrolling,
                        hideAfterScrollStop = hiddenAfter,
                        contentIsScrollable = false,
                    )
                )
            }
        }
    }

    @Test
    fun `graceful recovery — scrolling again cancels the pending hide`() {
        // While the LaunchedEffect is delayed, a new scroll event
        // restarts the effect and resets hideAfterScrollStop to false.
        // The pill stays visible. The visibility check is honest:
        assertEquals(
            true,
            decideScrollIndicatorVisibility(
                isScrolling = true,
                hideAfterScrollStop = false,  // reset by new LaunchedEffect
                contentIsScrollable = true,
            )
        )
    }
}
