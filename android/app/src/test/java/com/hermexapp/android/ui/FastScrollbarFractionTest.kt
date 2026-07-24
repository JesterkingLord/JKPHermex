package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [computeScrollFraction] — Wave 9.5 (2026-07-24).
 *
 * Bug targeted: the FastScrollbar thumb on the chat timeline was
 * reported "stuck in the middle" because the 9.0 formula used a fixed
 * estimated item height (96 px) that was wildly under-sized for chat
 * messages. Real assistant replies are 300-400 px each, so the per-
 * item-rate fraction produced values around the middle for any non-
 * trivial scroll position.
 *
 * The new formula uses actual LazyListState measurements:
 *   `visibleItemsFirstOffsetPx / totalContentHeightPx`
 * which is the *true* fraction of scroll position regardless of how
 * tall individual items are.
 *
 * These tests pin:
 *   1. The Tier 1 path (real measurements): produces correct fractions
 *      for tall / mixed / uniform item heights.
 *   2. The Tier 2 fallback (estimate): kicks in when totalContentHeightPx
 *      is 0 (first frame, pre-measurement) and produces a sane fraction.
 *   3. Edge cases: empty list, degenerate inputs, clamping.
 */
class FastScrollbarFractionTest {

    @Test
    fun `empty list returns zero`() {
        assertEquals(0f, computeScrollFraction(
            firstVisibleIndex = 0,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 0,
            visibleItemsFirstOffsetPx = 0,
            visibleItemsLastBottomPx = 0,
            itemCount = 0,
        ), 0.0001f)
    }

    @Test
    fun `tier1 real measurement at top returns zero`() {
        // first visible item offset = 0 means user is at the top.
        assertEquals(0f, computeScrollFraction(
            firstVisibleIndex = 0,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 1000,
            visibleItemsFirstOffsetPx = 0,
            visibleItemsLastBottomPx = 1000,
            itemCount = 12,
        ), 0.0001f)
    }

    @Test
    fun `tier1 real measurement at exact half returns one-half`() {
        // first visible item offset = 2000 in a 4000 total = 0.5.
        assertEquals(0.5f, computeScrollFraction(
            firstVisibleIndex = 5,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 800,
            visibleItemsFirstOffsetPx = 2000,
            visibleItemsLastBottomPx = 2800,
            itemCount = 12,
        ), 0.001f)
    }

    @Test
    fun `tier1 with partial offset above first visible item correctly positions thumb`() {
        // firstVisibleScrollOffsetPx shifts the in-item scroll; the
        // top-edge of the first visible item is (visibleItemsFirstOffsetPx - offset).
        // If firstVisible=index2 with offset=80px within the item, top-edge=2000-80=1920.
        // 1920 / 4000 = 0.48
        assertEquals(0.48f, computeScrollFraction(
            firstVisibleIndex = 2,
            firstVisibleScrollOffsetPx = 80,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 320,
            visibleItemsFirstOffsetPx = 2000,
            visibleItemsLastBottomPx = 2320,
            itemCount = 12,
        ), 0.001f)
    }

    @Test
    fun `tier1 with tall mixed items still tracks correctly`() {
        // The bug report scenario: a chat timeline where items are taller
        // than the 96 px estimate. With items at index 0..3, all 400 px,
        // user scrolled to top of item 1 (visibleItemsFirstOffsetPx=400,
        // firstVisibleIndex=1, offsetPx=0 after clamp).
        // numerator = 400 - 0 = 400; fraction = 400 / 1600 = 0.25.
        // The OLD formula would have computed (1 + 0/96)/3 = 0.333, off by
        // 8% and increasingly worse with non-uniform item heights.
        assertEquals(0.25f, computeScrollFraction(
            firstVisibleIndex = 1,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 1600,
            visibleItemsHeightPx = 400,
            visibleItemsFirstOffsetPx = 400,
            visibleItemsLastBottomPx = 800,
            itemCount = 4,
        ), 0.001f)
    }

    @Test
    fun `tier1 clamps negative offset to zero`() {
        // Negative scroll-offset (defensive) gets clamped to 0 by
        // `offset = firstVisibleScrollOffsetPx.coerceAtLeast(0)`. With
        // visibleItemsFirstOffsetPx=200, total=4000, that gives
        // numerator=200, fraction=0.05.
        assertEquals(0.05f, computeScrollFraction(
            firstVisibleIndex = 3,
            firstVisibleScrollOffsetPx = -200,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 200,
            visibleItemsFirstOffsetPx = 200,
            visibleItemsLastBottomPx = 400,
            itemCount = 12,
        ), 0.0001f)
    }

    @Test
    fun `tier1 clamps above one when content compressed`() {
        // Synthetic input where visibleItemsFirstOffsetPx >= totalContentHeightPx
        // (e.g., layout recomposing mid-animation). Clamp to 1.
        val f = computeScrollFraction(
            firstVisibleIndex = 12,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 4000,
            visibleItemsHeightPx = 200,
            visibleItemsFirstOffsetPx = 5000, // beyond total
            visibleItemsLastBottomPx = 5200,
            itemCount = 12,
        )
        assertTrue("fraction must be in [0,1]", f in 0f..1f)
        assertEquals(1f, f, 0.0001f)
    }

    @Test
    fun `tier2 fallback used when total content height is zero`() {
        // First frame after mount: totalContentHeightPx = 0. Fall back
        // to per-item rate. With firstVisible=2, offsetPx=96, estHeight=96,
        // and itemCount=10, fractional=2+1=3, /9 = 0.333.
        val f = computeScrollFraction(
            firstVisibleIndex = 2,
            firstVisibleScrollOffsetPx = 96,
            estimatedItemHeightPx = 96,
            totalContentHeightPx = 0,
            visibleItemsHeightPx = 0,
            visibleItemsFirstOffsetPx = 0,
            visibleItemsLastBottomPx = 0,
            itemCount = 10,
        )
        assertEquals(0.333f, f, 0.001f)
    }

    @Test
    fun `tier2 fallback returns sane fraction for chat-screenshot scenario`() {
        // The screenshot showed 3 entries, user at top, only first visible.
        // Items estimated at 400px each (multi-paragraph assistant reply).
        // firstVisibleIndex=0, offsetPx=0, itemCount=3 -> 0/2 = 0.
        assertEquals(0f, computeScrollFraction(
            firstVisibleIndex = 0,
            firstVisibleScrollOffsetPx = 0,
            estimatedItemHeightPx = 400,
            totalContentHeightPx = 0,           // measurement pending
            visibleItemsHeightPx = 0,
            visibleItemsFirstOffsetPx = 0,
            visibleItemsLastBottomPx = 0,
            itemCount = 3,
        ), 0.0001f)
    }
}
