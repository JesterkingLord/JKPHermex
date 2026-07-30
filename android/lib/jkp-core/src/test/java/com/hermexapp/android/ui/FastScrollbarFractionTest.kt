package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Wave 9.14 (2026-07-25) — Tests for [computePixelThumbGeometry] and
 * [scrollTargetForFraction].
 *
 * Replaces the Wave 9.11 [computeThumbGeometry] tests: the item-index
 * formula was proven wrong on-device (OnePlus CPH2343, adb uiautomator
 * + logcat) because the chat list has ONE item per message with
 * heights from ~200 px to > 10 000 px. Scrolling six full viewports
 * moved the reported position by 0.019. The pixel model below makes
 * the thumb proportional to real content pixels.
 */
class FastScrollbarFractionTest {

    @Test
    fun `fully hidden scrollbar does not intercept the content edge`() {
        assertFalse(shouldRenderFastScrollbar(canShow = true, alpha = 0f))
    }

    @Test
    fun `visible scrollbar renders only when content can scroll`() {
        assertTrue(shouldRenderFastScrollbar(canShow = true, alpha = 0.5f))
        assertFalse(shouldRenderFastScrollbar(canShow = false, alpha = 1f))
    }

    private fun uniformSizes(count: Int, height: Int): Map<Int, Int> =
        (0 until count).associateWith { height }

    // ------------------------------------------------------------------
    // Hide rules
    // ------------------------------------------------------------------

    @Test
    fun `empty list returns null`() {
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = emptyMap(),
            fallbackUnmeasuredItemSizePx = 0f,
            viewportHeightPx = 800,
            totalItemsCount = 0,
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertNull(g)
    }

    @Test
    fun `no measurements yet returns null (bar hides until first frame)`() {
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = emptyMap(),
            fallbackUnmeasuredItemSizePx = 0f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNull("no measured sizes → no estimate → hide", g)
    }

    @Test
    fun `content fits in viewport returns null (no scroll possible)`() {
        // Two 400px messages in an 800px viewport: totalPx == viewport.
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = mapOf(0 to 400, 1 to 400),
            fallbackUnmeasuredItemSizePx = 400f,
            viewportHeightPx = 800,
            totalItemsCount = 2,
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertNull("content fits → bar hidden", g)
    }

    // ------------------------------------------------------------------
    // Endpoint snapping — exact by construction
    // ------------------------------------------------------------------

    @Test
    fun `at very top position is exactly 0`() {
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0f, g!!.position, 0.0001f)
        assertTrue("size must be at least MIN_VISIBLE", g.size >= 0.08f)
    }

    @Test
    fun `at very bottom position is exactly 1 regardless of estimate`() {
        // Only a few items measured; the average for the rest is a
        // guess — but canScrollForward=false must still snap to 1.
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 46,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = mapOf(46 to 200, 47 to 200, 48 to 200, 49 to 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
            canScrollBackward = true,
            canScrollForward = false,
        )
        assertNotNull(g)
        assertEquals("bottom snap is exact", 1f, g!!.position, 0.0001f)
    }

    // ------------------------------------------------------------------
    // The bug scenario: one item per chat message, wildly variable heights
    // ------------------------------------------------------------------

    @Test
    fun `scrolling inside one giant message moves the thumb proportionally`() {
        // Reproduces the 2026-07-25 on-device failure: 97 items,
        // item 0 is 10 000 px tall (a long assistant message), the
        // viewport is 1 220 px. After scrolling 6 000 px INTO item 0
        // the item-index formula reported pos=0.019 (thumb stuck at
        // top). The pixel formula must report ~6000/scrollablePx.
        val total = 97
        val measured = mutableMapOf<Int, Int>()
        measured[0] = 10_000
        val avg = 500f // measured average of the few others seen so far
        val viewport = 1_220
        val scrolledIntoGiant = 6_000

        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = scrolledIntoGiant,
            measuredItemSizesPx = measured,
            fallbackUnmeasuredItemSizePx = avg,
            viewportHeightPx = viewport,
            totalItemsCount = total,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertNotNull(g)
        // totalPx = 10 000 + 96×500 = 58 000; scrollable = 56 780.
        val expected = scrolledIntoGiant / 56_780f
        assertEquals(expected, g!!.position, 0.001f)
        assertTrue(
            "pixel position ($expected) must be ~5x larger than the " +
                "broken item-index value (0.019)",
            g.position > 0.10f,
        )
    }

    @Test
    fun `uniform items mid-list position matches pixel expectation`() {
        // 50 items × 200 px, viewport 800: totalPx = 10 000,
        // scrollable = 9 200. Scrolled to item 10 top → 2 000 px.
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 10,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(2_000f / 9_200f, g!!.position, 0.001f)
        assertEquals(800f / 10_000f, g.size, 0.001f)
    }

    @Test
    fun `unmeasured items fall back to the fallback estimate`() {
        // Only the visible item measured (300 px); 9 others unknown.
        // totalPx = 300 + 9×300 = 3 000; viewport 600 → size = 0.2.
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = mapOf(0 to 300),
            fallbackUnmeasuredItemSizePx = 300f,
            viewportHeightPx = 600,
            totalItemsCount = 10,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0.2f, g!!.size, 0.001f)
    }

    @Test
    fun `thumb size clamps to minimum visible fraction for huge content`() {
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = uniformSizes(500, 1_000),
            fallbackUnmeasuredItemSizePx = 1_000f,
            viewportHeightPx = 800,
            totalItemsCount = 500,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertNotNull(g)
        assertEquals(0.08f, g!!.size, 0.0001f)
    }

    @Test
    fun `position is monotonic as scroll advances`() {
        var last = -1f
        val sizes = uniformSizes(50, 200)
        for (idx in 0..45 step 5) {
            val g = computePixelThumbGeometry(
                firstVisibleItemIndex = idx,
                firstVisibleItemScrollOffsetPx = 100,
                measuredItemSizesPx = sizes,
                fallbackUnmeasuredItemSizePx = 200f,
                viewportHeightPx = 800,
                totalItemsCount = 50,
                canScrollBackward = true,
                canScrollForward = true,
            )
            assertNotNull(g)
            assertTrue("pos at idx=$idx must be monotonic", g!!.position >= last)
            last = g.position
        }
    }

    @Test
    fun `zero-height measured entries are ignored, not divide-by-zero`() {
        val g = computePixelThumbGeometry(
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffsetPx = 0,
            measuredItemSizesPx = mapOf(0 to 0, 1 to 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 10,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertNotNull(g)
        // Item 0 fell back to the average (200), so scrollPx = 200.
        assertEquals(200f / (10 * 200f - 800f), g!!.position, 0.001f)
    }

    // ------------------------------------------------------------------
    // Inverse mapping: track fraction → scroll target
    // ------------------------------------------------------------------

    @Test
    fun `scroll target at fraction 0 is the list top`() {
        val t = scrollTargetForFraction(
            fraction = 0f,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
        )
        assertNotNull(t)
        assertEquals(0, t!!.itemIndex)
        assertEquals(0, t.itemScrollOffsetPx)
    }

    @Test
    fun `scroll target at fraction 1 requests the exact list bottom`() {
        val t = scrollTargetForFraction(
            fraction = 1f,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
        )
        assertNotNull(t)
        // scrollable = 10 000 − 800 = 9 200 → item 46, offset 0.
        assertEquals(49, t!!.itemIndex)
        assertEquals(Int.MAX_VALUE, t.itemScrollOffsetPx)
    }

    @Test
    fun `bottom edge tolerance requests the exact list bottom`() {
        val t = scrollTargetForFraction(
            fraction = 0.985f,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
        )
        assertNotNull(t)
        assertEquals(49, t!!.itemIndex)
        assertEquals(Int.MAX_VALUE, t.itemScrollOffsetPx)
    }

    @Test
    fun `scroll target mid-track maps through the pixel model`() {
        // 50 × 200 px, viewport 800 → scrollable 9 200; fraction 0.5
        // → 4 600 px → item 23 (4 600 px), offset 0.
        val t = scrollTargetForFraction(
            fraction = 0.5f,
            measuredItemSizesPx = uniformSizes(50, 200),
            fallbackUnmeasuredItemSizePx = 200f,
            viewportHeightPx = 800,
            totalItemsCount = 50,
        )
        assertNotNull(t)
        assertEquals(23, t!!.itemIndex)
        assertEquals(0, t.itemScrollOffsetPx)
    }

    @Test
    fun `scroll target inside a giant item returns intra-item offset`() {
        // Item 0 = 10 000 px, 96 × 500 px, viewport 1 220.
        // totalPx = 58 000, scrollable = 56 780; fraction 0.1 →
        // 5 678 px → still inside item 0 with offset 5 678.
        val t = scrollTargetForFraction(
            fraction = 0.1f,
            measuredItemSizesPx = mapOf(0 to 10_000),
            fallbackUnmeasuredItemSizePx = 500f,
            viewportHeightPx = 1_220,
            totalItemsCount = 97,
        )
        assertNotNull(t)
        assertEquals(0, t!!.itemIndex)
        assertEquals(5_678, t.itemScrollOffsetPx)
    }

    @Test
    fun `scroll target with invalid inputs returns null`() {
        assertNull(
            scrollTargetForFraction(
                fraction = 0.5f,
                measuredItemSizesPx = emptyMap(),
                fallbackUnmeasuredItemSizePx = 0f,
                viewportHeightPx = 800,
                totalItemsCount = 50,
            ),
        )
        assertNull(
            scrollTargetForFraction(
                fraction = 0.5f,
                measuredItemSizesPx = uniformSizes(50, 200),
                fallbackUnmeasuredItemSizePx = 200f,
                viewportHeightPx = 0,
                totalItemsCount = 50,
            ),
        )
        assertNull(
            scrollTargetForFraction(
                fraction = 0.5f,
                measuredItemSizesPx = uniformSizes(50, 200),
                fallbackUnmeasuredItemSizePx = 200f,
                viewportHeightPx = 800,
                totalItemsCount = 0,
            ),
        )
    }

    // ------------------------------------------------------------------
    // MeasuredSizeCache behaviour
    // ------------------------------------------------------------------

    /** Minimal fake — LazyListItemInfo is a plain interface. */
    private class FakeItem(
        override val index: Int,
        override val size: Int,
    ) : androidx.compose.foundation.lazy.LazyListItemInfo {
        override val key: Any get() = index
        override val offset: Int get() = 0
        override val contentType: Any? get() = null
    }

    @Test
    fun `cache records visible sizes and median fallback uses viewport prior`() {
        val cache = MeasuredSizeCache()
        cache.record(10, listOf(FakeItem(0, 100), FakeItem(1, 300)))
        assertEquals(mapOf(0 to 100, 1 to 300), cache.snapshot())
        // Samples = {100, 300} + prior 0.75×800 = {100, 300, 600}
        // → lower-middle median = 300.
        assertEquals(300f, cache.fallbackPx(800f), 0.001f)
    }

    @Test
    fun `fallback is viewport prior when nothing measured yet`() {
        val cache = MeasuredSizeCache()
        assertEquals(600f, cache.fallbackPx(800f), 0.001f)
    }

    @Test
    fun `one giant outlier cannot poison the median fallback`() {
        // The on-device failure mode: the session opens on a single
        // ~10 000 px message. A MEAN fallback would be 10 000 px and
        // the thumb would freeze at the top; the median with prior
        // must stay at the prior.
        val cache = MeasuredSizeCache()
        cache.record(97, listOf(FakeItem(0, 10_000)))
        assertEquals(600f, cache.fallbackPx(800f), 0.001f)
    }

    @Test
    fun `fallback converges to typical message height as items are measured`() {
        val cache = MeasuredSizeCache()
        cache.record(
            97,
            listOf(
                FakeItem(0, 10_000),
                FakeItem(1, 400),
                FakeItem(2, 500),
                FakeItem(3, 600),
            ),
        )
        // Samples = {10000, 400, 500, 600} + prior 600
        // sorted = [400, 500, 600, 600, 10000] → median idx 2 → 600.
        assertEquals(600f, cache.fallbackPx(800f), 0.001f)
    }

    @Test
    fun `cache clears when total item count changes (indices shift)`() {
        val cache = MeasuredSizeCache()
        cache.record(10, listOf(FakeItem(0, 100)))
        cache.record(11, listOf(FakeItem(0, 500), FakeItem(1, 500)))
        // Stale height for index 0 must be gone — the list was
        // replaced/prepended, so index → height mapping reset.
        assertEquals(mapOf(0 to 500, 1 to 500), cache.snapshot())
    }

    @Test
    fun `cache ignores zero-height items`() {
        val cache = MeasuredSizeCache()
        cache.record(10, listOf(FakeItem(0, 0), FakeItem(1, 200)))
        assertEquals(mapOf(1 to 200), cache.snapshot())
    }

    @Test
    fun `accessibility progress accepts finite values and clamps endpoints`() {
        assertEquals(0f, normalizeRequestedScrollFraction(-0.4f) ?: Float.NaN, 0f)
        assertEquals(0.375f, normalizeRequestedScrollFraction(0.375f) ?: Float.NaN, 0f)
        assertEquals(1f, normalizeRequestedScrollFraction(1.4f) ?: Float.NaN, 0f)
    }

    @Test
    fun `accessibility progress rejects non finite values`() {
        assertNull(normalizeRequestedScrollFraction(Float.NaN))
        assertNull(normalizeRequestedScrollFraction(Float.POSITIVE_INFINITY))
        assertNull(normalizeRequestedScrollFraction(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `scroll position description is user facing and locale stable`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "25 percent through content",
                buildScrollStateDescription(0.25f),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}
