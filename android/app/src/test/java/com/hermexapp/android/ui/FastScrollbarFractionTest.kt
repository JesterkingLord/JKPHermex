package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 9.11 (2026-07-24) — Tests for [computeThumbGeometry].
 *
 * The headline fix from v0.8.10 → v0.8.11 is the new "hide bar when
 * content fits in viewport" rule. The bar returning non-null with a
 * meaningless fraction=0 was producing the "thumb stuck at the top"
 * symptom the user reported on short chats. With the new guard
 * (canScrollBackward=false AND canScrollForward=false → return
 * null), short chats hide the bar entirely.
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
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertNull(g)
    }

    @Test
    fun `content fits in viewport returns null (no scroll possible)`() {
        // v0.8.11 fix: bar HIDES entirely when nothing to scroll.
        // The classic "stuck at top" bug came from this scenario
        // producing a position=0 thumb pinned to the top.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 400,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 2,
            viewportEndOffsetPx = 800,
            totalItemsCount = 2,
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertNull("content fits → bar hidden", g)
    }

    @Test
    fun `at top of 50-item list thumb position equals 0`() {
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 0,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = 800,
            totalItemsCount = 50,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0f, g!!.position, 0.001f)
        assertTrue("size must be at least MIN_VISIBLE", g.size >= 0.08f)
    }

    @Test
    fun `at bottom of 50-item list thumb position is at max`() {
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 47,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = -800,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = 0,
            totalItemsCount = 50,
            canScrollBackward = true,
            canScrollForward = false,
        )
        assertNotNull(g)
        // Position is clamped so thumb doesn't overflow track.
        assertTrue("pos at bottom should be high", g!!.position > 0.7f)
    }

    @Test
    fun `mid-list tall items position is proportional to scroll`() {
        // firstVisible=3, firstVisibleScrollOffset=80 of 200 = 0.4
        // lastPartial (4 items visible, last one fully in) = 0
        // positionRaw = (3 + 0.4) / 10 = 0.34
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 3,
            firstVisibleItemScrollOffsetPx = 80,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 800,
            lastVisibleItemSizePx = 250,
            visibleItemCount = 4,
            viewportEndOffsetPx = 1050,
            totalItemsCount = 10,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0.34f, g!!.position, 0.005f)
        assertEquals(0.36f, g.size, 0.005f)
    }

    @Test
    fun `user screenshot scenario at-bottom-of-long-chat shows thumb at bottom`() {
        // Replicates the v0.8.9 user's screenshot: chat at bottom,
        // but thumb showed at top.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 46,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 9200,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 4,
            viewportEndOffsetPx = 10000,
            totalItemsCount = 50,
            canScrollBackward = true,
            canScrollForward = false,
        )
        assertNotNull(g)
        assertTrue("pos must be near bottom", g!!.position > 0.7f)
    }

    @Test
    fun `content fits no scroll hidden (wave 9 11 primary fix)`() {
        // Two-message chat, both visible, can't scroll up or down.
        // v0.8.10 would have returned pos=0 here, putting the thumb
        // at the top of the track (which is the bug the user saw).
        // v0.8.11 returns null so the bar hides itself entirely.
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 400,
            lastVisibleItemOffsetPx = 400,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 2,
            viewportEndOffsetPx = 800,
            totalItemsCount = 2,
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertNull("fits in viewport → bar hidden", g)
    }

    @Test
    fun `thumbsize at minimum when content fits in viewport and scrolled fully past last`() {
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 400,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 3,
            viewportEndOffsetPx = 600,
            totalItemsCount = 50,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0.08f, g!!.size, 0.001f)
    }

    @Test
    fun `position clamped so thumb bottom does not exceed track end (index 49)`() {
        val g = computeThumbGeometry(
            firstVisibleItemIndex = 49,
            firstVisibleItemScrollOffsetPx = 0,
            firstVisibleItemSizePx = 200,
            lastVisibleItemOffsetPx = 200,
            lastVisibleItemSizePx = 200,
            visibleItemCount = 1,
            viewportEndOffsetPx = 400,
            totalItemsCount = 50,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0.92f, g!!.position, 0.005f)
    }

    @Test
    fun `firstPartialFraction = 0 when scrollOffset is 0`() {
        assertEquals(0f, fractionHiddenTop(0, 200), 0.0001f)
    }

    @Test
    fun `firstPartialFraction = 1 when scrolled past first item`() {
        assertEquals(1f, fractionHiddenTop(250, 200), 0.0001f)
    }

    @Test
    fun `lastPartialFraction = 0 when item bottom is within viewport`() {
        assertEquals(0f, fractionHiddenBottom(600, 200, 800), 0.0001f)
    }

    @Test
    fun `lastPartialFraction = fraction when item extends past viewport`() {
        // (600+2000-800)/2000 = 0.9
        assertEquals(0.9f, fractionHiddenBottom(600, 2000, 800), 0.001f)
    }

    @Test
    fun `monotonic through scroll range`() {
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
                canScrollBackward = true,
                canScrollForward = true,
            )
            assertNotNull(g)
            val pos = g!!.position
            assertTrue("pos at idx=$idx must be monotonic", pos >= last)
            last = pos
        }
    }
}
