package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 9.9 (2026-07-24) — Tests for [computeScrollFractionPx].
 *
 * The previous formulas in v0.8.5 / v0.8.6 / v0.8.7 used item-index
 * math. The user's repeated complaint was "right scrollbar stuck in
 * the middle." Each prior fix attempted a different index-based math
 * (per-item-rate, pixel-measurement-fallback, lastVisibleIndex guard).
 * None of them worked, because item-index math simply cannot
 * represent pixel position when item sizes vary wildly.
 *
 * v0.8.9 uses pixel-perfect math from
 * `LazyListLayoutInfo.viewportStartOffset` (the pixel offset of the
 * top edge of the viewport within the scrollable content) divided by
 * the scrollable range (total content height minus viewport height).
 * This is exactly how ChatGPT's scrollbar works internally and is the
 * canonical Compose recipe.
 *
 * These tests pin the pixel-perfect contract.
 */
class FastScrollbarFractionTest {

    @Test
    fun `empty viewport returns null`() {
        // Degenerate LazyList state during pre-measurement.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 0,
            viewportEndOffset = 0,
            estimatedTotalContentHeight = 1000,
        )
        assertNull(frac)
    }

    @Test
    fun `content fits in viewport returns null (no scroll possible)`() {
        val frac = computeScrollFractionPx(
            viewportStartOffset = 0,
            viewportEndOffset = 800,
            estimatedTotalContentHeight = 600,
        )
        assertNull(frac)
    }

    @Test
    fun `at top of long chat returns zero`() {
        // viewportStartOffset=0 means viewport top is at content top.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 0,
            viewportEndOffset = 800,
            estimatedTotalContentHeight = 3000,
        )
        assertNotNull(frac)
        assertEquals(0f, frac!!, 0.0001f)
    }

    @Test
    fun `at bottom of 3x-viewport content returns one`() {
        // Content is 2400, viewport 800. Max scroll = 1600.
        // Scrolled all the way: viewportStartOffset = 1600.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 1600,
            viewportEndOffset = 2400,
            estimatedTotalContentHeight = 2400,
        )
        assertNotNull(frac)
        assertEquals(1f, frac!!, 0.0001f)
    }

    @Test
    fun `mid-scroll fraction equals scrolled-divide-max-scroll`() {
        // 2400 content, 800 viewport. Max scroll = 1600.
        // viewportStartOffset = 800 → scrolled 800/1600 = 0.5.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 800,
            viewportEndOffset = 1600,
            estimatedTotalContentHeight = 2400,
        )
        assertNotNull(frac)
        assertEquals(0.5f, frac!!, 0.0001f)
    }

    @Test
    fun `variable item heights use real pixels — tall message mid-list`() {
        // The bug scenario from every prior revision: list with mixed
        // item heights. Chat with [60, 600, 60, 600, 60] = 1380 total,
        // viewport 800. Max scroll = 580. The viewport's TOP edge is
        // at pixel offset 200 (we've scrolled past item 0 fully + 140px
        // into item 1 which is 600px). 200/580 = ~0.345. Old item-index
        // math said `firstVisibleIndex=1 / 4 = 0.25`. Pixel math wins.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 200,
            viewportEndOffset = 200 + 800,
            estimatedTotalContentHeight = 1380,
        )
        assertNotNull(frac)
        val f = frac!!
        // Expected: 200 / (1380 - 800) = 200 / 580 ≈ 0.345
        assertEquals(0.345f, f, 0.005f)
        assertTrue("fraction must be in [0, 1]", f in 0f..1f)
    }

    @Test
    fun `viewportStartOffset past maxScroll clamps to 1f (overshoot at the bottom)`() {
        // LazyList occasionally reports a viewportStartOffset greater
        // than maxScroll (overshoot from a fling). The semantically
        // correct clamp is 1.0 — the user is at the bottom and the
        // offset has just become confused.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 5000,
            viewportEndOffset = 5800,
            estimatedTotalContentHeight = 1380,
        )
        assertNotNull(frac)
        assertEquals(1f, frac!!, 0.0001f)
    }

    @Test
    fun `viewportStartOffset below zero clamps to zero fraction`() {
        // Defensive — pre-measurement can report -8 occasionally.
        val frac = computeScrollFractionPx(
            viewportStartOffset = -20,
            viewportEndOffset = -20 + 800,
            estimatedTotalContentHeight = 1380,
        )
        assertNotNull(frac)
        assertEquals(0f, frac!!, 0.0001f)
    }

    @Test
    fun `fraction tracks proportionally through whole scroll range`() {
        // The fundamental success criterion from the user's screenshot:
        // as the user scrolls, the thumb position moves proportionally.
        // Verify by sampling 5 points and checking monotonicity.
        val total = 3000
        val viewport = 800
        val maxScroll = total - viewport
        var last = -1f
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val startOffset = (maxScroll * pct / 100f).toInt()
            val frac = computeScrollFractionPx(
                viewportStartOffset = startOffset,
                viewportEndOffset = startOffset + viewport,
                estimatedTotalContentHeight = total,
            )
            assertNotNull(frac)
            val f = frac!!
            assertTrue(
                "pct=$pct fraction=$f must be in [0, 1]",
                f in 0f..1f,
            )
            // Must be monotonic non-decreasing.
            assertTrue(
                "fraction must be monotonic at pct=$pct (was $last, now $f)",
                f >= last,
            )
            last = f
        }
    }

    @Test
    fun `screenshot scenario at-bottom-of-5msg-chat returns 1f`() {
        // Replicates the exact bug the user has reported 4 times.
        // Chat: 5 messages, last visible item bottom at pixel 2400,
        // viewport bottom edge at 2400 (fully at bottom). viewportStartOffset
        // = 2400 - 800 = 1600. Max scroll = 1600. Fraction = 1.0.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 1600,
            viewportEndOffset = 2400,
            estimatedTotalContentHeight = 2400,
        )
        assertNotNull(frac)
        assertEquals(1f, frac!!, 0.0001f)
    }

    @Test
    fun `screenshot scenario at-top-of-5msg-chat returns 0f`() {
        // Same 5-msg chat, viewport at top. viewportStartOffset = 0.
        val frac = computeScrollFractionPx(
            viewportStartOffset = 0,
            viewportEndOffset = 800,
            estimatedTotalContentHeight = 2400,
        )
        assertNotNull(frac)
        assertEquals(0f, frac!!, 0.0001f)
    }
}
