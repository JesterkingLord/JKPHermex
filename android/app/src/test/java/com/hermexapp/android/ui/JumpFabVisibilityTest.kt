package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for the JumpFab visibility contract (Wave 9.7).
 *
 * Two contracts are pinned here, both via pure helpers:
 *
 *   1. **Scroll-indicator pill** — visible while scrolling, hidden 1 s
 *      after scrolling stops. Pinned by [decideScrollIndicatorVisibility].
 *
 *   2. **At-most-one-pill role selection** — the composable never
 *      renders two pills at once. Pinned by [decideJumpFabRole].
 *
 * The v0.8.6 screenshot regression ("You made 2 scroll up and down
 * buttons now, one yellow and one white") came from letting the two
 * pills be visible simultaneously. The fix is mutual exclusion at the
 * decision layer; the test asserts it directly.
 */
class JumpFabVisibilityTest {

    // ---------- Scroll indicator (yellow pill) ----------

    @Test
    fun `scroll indicator visible while scrolling`() {
        assertTrue(
            decideScrollIndicatorVisibility(
                isScrolling = true,
                hideAfterScrollStop = false,
                contentIsScrollable = true,
            )
        )
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
        for (scrolling in listOf(true, false)) {
            for (hiddenAfter in listOf(true, false)) {
                assertFalse(
                    "scrolling=$scrolling, hiddenAfter=$hiddenAfter",
                    decideScrollIndicatorVisibility(
                        isScrolling = scrolling,
                        hideAfterScrollStop = hiddenAfter,
                        contentIsScrollable = false,
                    )
                )
            }
        }
    }

    // ---------- Mutual exclusion: at most one pill ----------

    @Test
    fun `while scrolling the role is SCROLL_PILL even when off-edge`() {
        // v0.8.6 double-pill bug fix: when isScrolling=true, the
        // SCROLL_PILL wins regardless of edge state. Never both pills.
        val r1 = decideJumpFabRole(
            isScrolling = true,
            hideAfterScrollStop = false,
            contentIsScrollable = true,
            firstVisibleIndex = 0,
            lastVisibleIndex = 0,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.SCROLL_PILL, r1)

        val r2 = decideJumpFabRole(
            isScrolling = true,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 20,
            lastVisibleIndex = 25,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.SCROLL_PILL, r2)
    }

    @Test
    fun `during 1s grace (not yet hidden) the role is SCROLL_PILL`() {
        // User stopped scrolling but the grace timer hasn't fired yet.
        // Still show SCROLL_PILL regardless of edge state.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = false,
            contentIsScrollable = true,
            firstVisibleIndex = 20,
            lastVisibleIndex = 25,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.SCROLL_PILL, r)
    }

    @Test
    fun `past grace off-edge shows JUMP_CHIP_BOTTOM`() {
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 20,
            lastVisibleIndex = 25,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.JUMP_CHIP_BOTTOM, r)
    }

    @Test
    fun `past grace at-bottom-edge shows JUMP_CHIP_TOP`() {
        // Scrolled to bottom of long list, room to scroll back up.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 47,
            lastVisibleIndex = 50,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.JUMP_CHIP_TOP, r)
    }

    @Test
    fun `past grace at-top-edge shows JUMP_CHIP_BOTTOM`() {
        // Looking at the oldest items, plenty below.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 0,
            lastVisibleIndex = 2,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.JUMP_CHIP_BOTTOM, r)
    }

    @Test
    fun `past grace at both edges with single item shows NONE`() {
        // Effectively: nothing to jump to. No chip, no pill.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 0,
            lastVisibleIndex = 0,
            lastIndex = 0,
        )
        assertEquals(JumpFabRole.NONE, r)
    }

    @Test
    fun `content not scrollable always returns NONE`() {
        // Even if past the grace and off an edge, no scroll possible,
        // so no controls at all.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = false,
            firstVisibleIndex = 0,
            lastVisibleIndex = 50,
            lastIndex = 50,
        )
        assertEquals(JumpFabRole.NONE, r)
    }

    @Test
    fun `past grace with showJumpChip false returns NONE even off-edge`() {
        // Caller disabled the chip; even past the grace, no chip.
        val r = decideJumpFabRole(
            isScrolling = false,
            hideAfterScrollStop = true,
            contentIsScrollable = true,
            firstVisibleIndex = 20,
            lastVisibleIndex = 25,
            lastIndex = 50,
            showJumpChip = false,
        )
        assertEquals(JumpFabRole.NONE, r)
    }

    // ---------- At-edge probe (decideJumpTarget, exposed for tests) ----------

    @Test
    fun `jump target NONE for empty list`() {
        assertEquals(JumpTarget.NONE,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 0))
        assertEquals(JumpTarget.NONE,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = -1))
    }

    @Test
    fun `jump target BOTTOM when at top of long list`() {
        assertEquals(JumpTarget.BOTTOM,
            decideJumpTarget(firstVisibleIndex = 0, lastVisibleIndex = 2, lastIndex = 50))
    }

    @Test
    fun `jump target TOP when at bottom of long list`() {
        assertEquals(JumpTarget.TOP,
            decideJumpTarget(firstVisibleIndex = 47, lastVisibleIndex = 50, lastIndex = 50))
    }

    @Test
    fun `jump target mid-list defaults to BOTTOM (jump-to-latest is the common intent)`() {
        assertEquals(JumpTarget.BOTTOM,
            decideJumpTarget(firstVisibleIndex = 20, lastVisibleIndex = 25, lastIndex = 50))
    }
}
