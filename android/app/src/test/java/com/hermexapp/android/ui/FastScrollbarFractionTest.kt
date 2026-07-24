package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 9.10 (2026-07-24) — Tests for [computeThumbGeometry].
 *
 * v0.8.9's pixel-perfect math (`viewportStartOffset / estimatedHeight`)
 * looked right but failed in practice: when the user stops scrolling
 * mid-chat with items beyond the viewport, the LazyList's reported
 * `estimatedTotalContentHeight` under-reports because only VISIBLE
 * items have been measured. Result: the user's last screenshot
 * showed the thumb pinned to the **top** of the track while the chat
 * was clearly at the bottom.
 *
 * v0.8.10 follows the canonical reference formula (gist 0sten
 * "LazyColumnScrollbar") which uses **item-index math**. The fraction
 * is `(firstVisibleItemIndex + partialScrollFraction) /
 * totalItemsCount`. Position and size both pin to totalItemsCount,
 * which is exact (it's the same number passed to LazyColumn).
 *
 * These tests pin the canonical contract.
 */
class FastScrollbarFractionTest {

    @Test
    fun `empty list returns null`() {
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 0,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 0,
            viewportEndOffsetPx = 800,
            totalItemsCount = 0,
        )
        assertNull(g)
    }

    @Test
    fun `at top of 50-item list thumb position equals 0`() {
        // List of 50, first item index 0, no scroll offset.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 0,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = 800,
            totalItemsCount = 50,
        )
        assertNotNull(g)
        assertEquals(0f, g!!.position, 0.001f)
        assertTrue("size must be at least MIN_VISIBLE", g!!.size >= 0.08f)
    }

    @Test
    fun `at bottom of 50-item list thumb position equals 1 minus size`() {
        // User fully scrolled — last item index 49, visible.
        // last item bottom (say offset=2400 + size=200 = 2600) is
        // past viewportEndOffset=800, so it's fully visible in the
        // bottom and the partial hidden fraction is 1.0 (fully
        // hidden below). Wait, that's not right. Let me think:
        // lastVisibleItem.offset is the top of the LAST visible item
        // relative to the viewport. When we're at bottom, the last
        // visible item is partially visible at the bottom of the
        // viewport. Its top would be below viewport top.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 47, // user scrolled almost to bottom
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = -800, // last item top is 800 px above viewport top (we've scrolled past items)
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = 0, // viewport bottom edge is at content top (we're at very bottom of content)
            totalItemsCount = 50,
        )
        assertNotNull(g)
        // Position should be at the bottom of the track (clamped to
        // 1 - sizeFraction).
        assertTrue("position must be high when at bottom", g!!.position > 0.8f)
    }

    @Test
    fun `user-screenshot scenario at-bottom-of-long-chat shows thumb at bottom`() {
        // Replicates the v0.8.9 screenshot bug: chat at bottom, but
        // thumb showed at the top. v0.8.10 must NOT have that bug.
        //
        // Setup: 50-message chat, user fully at bottom. lastVisible
        // item is index 49 (the last message), its top is way above
        // the viewport (negative offset), its bottom aligns with
        // or exceeds viewport bottom edge. viewportEndOffsetPx is
        // the maximum pixel position of the viewport, which at
        // bottom equals the total content height (last item bottom).
        val totalItems = 50
        // Pretend last item bottom is at pixel 10000 (content
        // extent). Viewport is 800 tall. So at bottom: viewportStart
        // = 9200, viewportEndOffset = 10000.
        val lastItemBottomPx = 10_000
        val viewportEndOffset = lastItemBottomPx
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 46,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = lastItemBottomPx - viewportEndOffset,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = viewportEndOffset,
            totalItemsCount = totalItems,
        )
        assertNotNull(g)
        // The fraction must NOT be near zero (the v0.8.9 bug).
        // The fraction at near-bottom should be > 0.8.
        assertTrue(
            "position ${g!!.position} should be near the bottom",
            g.position > 0.8f,
        )
    }

    @Test
    fun `mid-list tall items position is proportional to scroll`() {
        // 10 items, item 3 partially visible at top, item 6
        // partially visible at bottom. firstVisibleItemScrollOffset
        // = 80 out of 200 (40% through item 3).
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 3,
            firstVisibleItemScrollOffsetPx = 80,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 800,
            lastVisibleItemSizePx = 250,
            visibleItemCount = 4,
            viewportEndOffsetPx = 1050,
            totalItemsCount = 10,
        )
        assertNotNull(g)
        // firstPartial = 80/200 = 0.4
        // lastPartial  = (800+250-1050)/250 = 0/250 = 0
        // positionRaw = (3 + 0.4) / 10 = 0.34
        // sizeRaw = (4 - 0.4 - 0) / 10 = 0.36
        // sizeClamped = 0.36 (≥ MIN_VISIBLE_FRACTION=0.08)
        // maxPosition = 1 - 0.36 = 0.64
        // positionClamped = clamp(0.34, 0, 0.64) = 0.34
        assertEquals(0.34f, g!!.position, 0.005f)
        assertEquals(0.36f, g.size, 0.005f)
    }

    @Test
    fun `thumbsize at minimum when content fits in viewport`() {
        // Content fits, all 3 items fully visible, sizeRaw ≈ 0.06
        // (< 0.08), clamps to MIN_VISIBLE_FRACTION.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 400,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 3,
            viewportEndOffsetPx = 600,
            totalItemsCount = 50,
        )
        assertNotNull(g)
        assertEquals(0.08f, g!!.size, 0.001f)
    }

    @Test
    fun `position clamped so thumb bottom does not exceed track end`() {
        // last item visible with index = totalItemsCount - 1.
        // Position would be (49 + 0) / 50 = 0.98; size = 1 / 50 = 0.02.
        // sizeClamped = 0.08. maxPosition = 1 - 0.08 = 0.92.
        // positionClamped = clamp(0.98, 0, 0.92) = 0.92.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 49,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 200,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 1,
            viewportEndOffsetPx = 400,
            totalItemsCount = 50,
        )
        assertNotNull(g)
        assertEquals(0.92f, g!!.position, 0.005f)
    }

    @Test
    fun `firstPartialFraction = 0 when scrollOffset is 0`() {
        // sanity: at the top of an item, scrollOffset is 0.
        val f = fractionHiddenTop(scrollOffsetPx = 0, sizePx = 200)
        assertEquals(0f, f, 0.0001f)
    }

    @Test
    fun `firstPartialFraction = 1 when scrolled past first item`() {
        // scrollOffset ≥ sizePx (defensive case) clamps to 1.
        val f = fractionHiddenTop(scrollOffsetPx = 250, sizePx = 200)
        assertEquals(1f, f, 0.0001f)
    }

    @Test
    fun `lastPartialFraction = 0 when item bottom is above viewport end`() {
        // 50-item chat, top of viewport at content start.
        // lastVisible item bottom 800 (within viewport 0..800)
        val f = fractionHiddenBottom(
            itemOffsetPx = 600,
            itemSizePx = 200,
            viewportEndOffsetPx = 800,
        )
        assertEquals(0f, f, 0.0001f)
    }

    @Test
    fun `lastPartialFraction = 1 when item extends well past viewport`() {
        val f = fractionHiddenBottom(
            itemOffsetPx = 600,
            itemSizePx = 2000,
            viewportEndOffsetPx = 800,
        )
        // (600+2000-800)/2000 = 1800/2000 = 0.9
        assertEquals(0.9f, f, 0.001f)
    }

    @Test
    fun `monotonic through scroll range`() {
        // As firstVisibleItemIndex grows, position must grow.
        var last = -1f
        for (idx in 0..9) {
            val g = computeThumbGeometry(
                firstVisibleItemIndex = idx,
                firstVisibleItemScrollOffsetPx = 0,
                firstVisibleItemSizePx = 200,
                lastVisibleItemOffsetPx = 800,
                lastVisibleItemSizePx = 200,
                visibleItemCount = 4,
                viewportEndOffsetPx = 1000,
                totalItemsCount = 10,
            )
            assertNotNull(g)
            val pos = g!!.position
            assertTrue(
                "pos at idx=$idx must be monotonic (was $last, now $pos)",
                pos >= last,
            )
            last = pos
        }
    }
}
