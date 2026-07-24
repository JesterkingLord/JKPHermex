package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [computeScrollFraction] — Wave 9.7 (2026-07-24).
 *
 * Bug targeted by THIS revision:
 *   In v0.8.6 the user reported "right scrollbar still doesn't move
 *   depending where we are, it's stuck in the middle." The cause:
 *   my v0.8.6 formula was
 *
 *     fraction = firstVisibleIndex / (totalItemsCount - visibleItemsCount)
 *
 *   For the screenshot (5 messages, 1 huge one filling the viewport,
 *   last item index 4 visible) the formula returned 0.75 — not 1.0.
 *   The LazyList reported `canScrollForward = true` because a sliver
 *   of the last item still extended past the viewport edge, so the
 *   "perfect" pixel guard didn't fire and the formula gave the
 *   mid-list value.
 *
 * Wave 9.7 adds two things:
 *   1. `lastVisibleIndex` parameter — the helper now snaps to 1.0
 *      whenever the last item is in the viewport, regardless of
 *      `canScrollForward`.
 *   2. `firstVisibleItemScrollOffsetPx` parameter — reserved for
 *      future partial-item bottom detection (currently unused but
 *      pinned in the signature for forward compatibility).
 *
 * All earlier tests are preserved with their assertions. The new
 * "user screenshot" test asserts that the scenario from v0.8.6 — last
 * item visible in viewport, fraction = 1.0 — is the new contract.
 */
class FastScrollbarFractionTest {

    @Test
    fun `empty list returns zero`() {
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 0,
                canScrollBackward = false,
                canScrollForward = false,
                totalItemsCount = 0,
            ),
            0.0001f,
        )
    }

    @Test
    fun `at top of list returns zero regardless of visible item count`() {
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 3,
                canScrollBackward = false,
                canScrollForward = true,
                totalItemsCount = 100,
            ),
            0.0001f,
        )
    }

    @Test
    fun `USER SCREENSHOT - at bottom of 5-msg chat with last item visible returns 1f`() {
        // This is the exact regression case from v0.8.6: 5 messages,
        // 1 huge one filling the viewport, last item index 4 visible.
        // The sliver of the last item extends past the viewport, so
        // canScrollForward=true BUT lastVisibleIndex (4) >= totalItemsCount-1 (4).
        // The lastVisibleIndex guard now fires: fraction = 1.
        assertEquals(
            1f,
            computeScrollFraction(
                firstVisibleIndex = 3,
                lastVisibleIndex = 4,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 1,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 5,
            ),
            0.0001f,
        )
    }

    @Test
    fun `at bottom of chat where canScrollForward=false returns 1f`() {
        // The "easy" at-bottom case — pixel-perfect canScrollForward
        // guard handles it.
        assertEquals(
            1f,
            computeScrollFraction(
                firstVisibleIndex = 3,
                lastVisibleIndex = 4,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 2,
                canScrollBackward = true,
                canScrollForward = false,
                totalItemsCount = 5,
            ),
            0.0001f,
        )
    }

    @Test
    fun `middle of long list uses item-count fraction`() {
        // 100 items, 10 visible, scrolled so firstVisible=45.
        // denom = 90. fraction = 45 / 90 = 0.5.
        assertEquals(
            0.5f,
            computeScrollFraction(
                firstVisibleIndex = 45,
                lastVisibleIndex = 50,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 10,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 100,
            ),
            0.001f,
        )
    }

    @Test
    fun `mid-list tall items use same item-count formula`() {
        assertEquals(
            0.333f,
            computeScrollFraction(
                firstVisibleIndex = 1,
                lastVisibleIndex = 2,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 1,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 4,
            ),
            0.005f,
        )
    }

    @Test
    fun `near-bottom of long list approaches 1`() {
        // 100 items, 10 visible, scrolled so firstVisible=85.
        // lastVisibleIndex=90 (so lastVisibleIndex < totalItemsCount-1=99,
        // not at bottom by the lastVisible guard). frac = 85/90 = 0.944.
        assertEquals(
            0.944f,
            computeScrollFraction(
                firstVisibleIndex = 85,
                lastVisibleIndex = 90,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 10,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 100,
            ),
            0.005f,
        )
    }

    @Test
    fun `firstVisible greater than denom clamps to 1`() {
        // Synthetic edge case: visibleItemsCount > totalItemsCount
        // (one item, all on screen, the user can scroll past everything
        // very quickly). Clamp to 1.
        val f = computeScrollFraction(
            firstVisibleIndex = 5,
            lastVisibleIndex = 8,
            firstVisibleItemScrollOffsetPx = 0,
            visibleItemsCount = 5,
            canScrollBackward = true,
            canScrollForward = true,
            totalItemsCount = 3,
        )
        assertTrue("fraction must be in [0, 1]", f in 0f..1f)
        assertEquals(1f, f, 0.0001f)
    }

    @Test
    fun `negative firstVisibleIndex clamps to 0`() {
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = -1,
                lastVisibleIndex = 2,
                firstVisibleItemScrollOffsetPx = 0,
                visibleItemsCount = 3,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 50,
            ),
            0.0001f,
        )
    }

    @Test
    fun `no scroll possible either direction returns 0`() {
        val f = computeScrollFraction(
            firstVisibleIndex = 0,
            lastVisibleIndex = 0,
            firstVisibleItemScrollOffsetPx = 0,
            visibleItemsCount = 1,
            canScrollBackward = false,
            canScrollForward = false,
            totalItemsCount = 1,
        )
        assertEquals(0f, f, 0.0001f)
    }

    @Test
    fun `scrollOffset does not change fraction when last item is already in view`() {
        // Reserved param. Even with a non-trivial scrollOffsetPx
        // (e.g., item partially scrolled into view) the lastVisible
        // guard takes priority and returns 1.
        assertEquals(
            1f,
            computeScrollFraction(
                firstVisibleIndex = 4,
                lastVisibleIndex = 4,
                firstVisibleItemScrollOffsetPx = 250, // half-ish scroll
                visibleItemsCount = 1,
                canScrollBackward = true,
                canScrollForward = true,
                totalItemsCount = 5,
            ),
            0.0001f,
        )
    }
}
