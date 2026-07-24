package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for the JumpFab visibility contracts (Wave 9.6).
 *
 * After the v0.8.4 → v0.8.5 cycle shipped with the opposite UX of
 * what the user requested ("the white up-and-down button does the
 * opposite now"), Wave 9.6 splits the JumpFab into two halves and
 * pins both contracts here:
 *
 *   1. **Scroll indicator** — visible while the user is scrolling,
 *      hidden 1 second after scrolling stops. Pinned by
 *      [decideScrollIndicatorVisibility].
 *
 *   2. **Jump chip** — visible only when the list has settled AND the
 *      user is off one of the edges. Pinned by [decideJumpTarget].
 *
 * Regression history (locked behind these tests):
 *   - v0.8.3 and earlier: a 5-message chat had no scrollbar at all
 *     due to `itemCount <= threshold` with threshold=20.
 *   - v0.8.5: JumpFab suppressed during scroll (the inverse of what
 *     the user wanted).
 *   - v0.8.4: scrollbar thumb stuck in middle because the
 *     estimatedItemHeightPx=96 path was wrong for tall messages.
 */
class JumpFabVisibilityTest {

    // ---------- scroll indicator ----------

    @Test
    fun `scroll indicator visible while scrolling`() {
        assertTrue(
            decideScrollIndicatorVisibility(
                isScrolling = true,
                hideAfterScrollStop = false,
                contentIsScrollable = true,
            )
        )
        // Even if hideAfterScrollStop was somehow true (race), the
        // scroll-in-progress state forces visible.
        assertTrue(
            decideScrollIndicatorVisibility(
                isScrolling = true,
                hideAfterScrollStop = true,
                contentIsScrollable = true,
            )
        )
    }

    @Test
    fun `scroll indicator visible during 1s grace after scroll stop`() {
        // Just stopped scrolling (within the grace window).
        assertTrue(
            decideScrollIndicatorVisibility(
                isScrolling = false,
                hideAfterScrollStop = false,
                contentIsScrollable = true,
            )
        )
    }

    @Test
    fun `scroll indicator hidden 1s after scroll stop`() {
        // 1s+ after stop → grace window expired → hidden.
        assertFalse(
            decideScrollIndicatorVisibility(
                isScrolling = false,
                hideAfterScrollStop = true,
                contentIsScrollable = true,
            )
        )
    }

    @Test
    fun `scroll indicator hidden when content not scrollable`() {
        // Empty / single-message-fits-on-screen chats have nothing
        // to scroll; indicator should never render.
        assertFalse(
            decideScrollIndicatorVisibility(
                isScrolling = false,
                hideAfterScrollStop = false,
                contentIsScrollable = false,
            )
        )
        assertFalse(
            decideScrollIndicatorVisibility(
                isScrolling = true,
                hideAfterScrollStop = false,
                contentIsScrollable = false,
            )
        )
    }

    // ---------- jump chip ----------

    @Test
    fun `jump chip NONE for empty list`() {
        assertEquals(JumpTarget.NONE,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 0))
        assertEquals(JumpTarget.NONE,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = -1))
    }

    @Test
    fun `jump chip NONE when both edges are visible (single item that fits)`() {
        assertEquals(JumpTarget.NONE,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 0))
    }

    @Test
    fun `jump chip BOTTOM when at top of long list`() {
        // User scrolled up to read history → offer jump to latest.
        assertEquals(JumpTarget.BOTTOM,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 2, lastIndex = 50))
    }

    @Test
    fun `jump chip TOP when at bottom of long list`() {
        // User at the bottom, can jump back to top.
        assertEquals(JumpTarget.TOP,
            decideJumpTarget(firstVisibleIndex = 47, lastVisibleIndex = 50, lastIndex = 50))
    }

    @Test
    fun `jump chip mid-list defaults to BOTTOM (jump-to-latest is the common intent)`() {
        assertEquals(JumpTarget.BOTTOM,
            decideJumpTarget(firstVisibleIndex = 20, lastVisibleIndex = 25, lastIndex = 50))
    }

    @Test
    fun `jump chip defensively clamps negative indices`() {
        // LazyList hydration can briefly report -1 for `lastVisible`.
        // Both calls below should not crash. The resulting direction
        // is incidental — what matters is the coercion safety.
        //
        // first=0, last=-1 coerced to 0 → atTop=true → BOTTOM.
        val r1 = decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = -1, lastIndex = 50)
        assertEquals(JumpTarget.BOTTOM, r1)
        // first=-1 coerced to 0, last=50 → atTop=true AND atBottom=true
        // (visible index caught up to lastIndex) → NONE.
        val r2 = decideJumpTarget(firstVisibleIndex = -1, lastVisibleIndex = 50, lastIndex = 50)
        assertEquals(JumpTarget.NONE, r2)
    }
}
