package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [computeScrollFraction] — Wave 9.6 (2026-07-24).
 *
 * Bug targeted: the user screenshot showed the FastScrollbar thumb
 * **stuck in the middle** even when the chat was scrolled to the
 * bottom (the last visible line was "Let me check Kimi progress one
 * more time" and there was no more content). The prior formulas had
 * two compounding problems:
 *
 *   1. `if (itemCount <= threshold) return` — the bar didn't render
 *      at all for short chats (5–15 messages). With threshold=20, the
 *      user's chat had **no bar at all**, just an invisible rail.
 *   2. The Tier 1 / Tier 2 fallback path. `sumOfMeasuredHeights()`
 *      returned 0 whenever measurement hadn't completed, silently
 *      routing every real-world scroll through Tier 2's
 *      per-item-rate formula (which divided by `itemCount - 1` and
 *      produced mid-list values almost regardless of position).
 *
 * Wave 9.6 replaces all of it with one formula:
 *
 *     canScrollBackward == false  → 0
 *     canScrollForward  == false  → 1
 *     otherwise:
 *         denom = max(1, totalItemsCount - visibleItemsCount)
 *         fraction = firstVisibleIndex / denom
 *         clamp [0, 1]
 *
 * LazyListState.canScrollForward / canScrollBackward are pixel-perfect
 * edge guards maintained by Compose internally, so we never need to
 * derive edge state from indices and item sizes. The fraction is
 * item-height-independent.
 *
 * These tests are written FIRST against the user's reported scenario
 * ("at bottom of 5-message chat, fraction should be 1.0") and against
 * the prior broken behavior ("smallest denominator should still give
 * a clamped-to-1 fraction, not stuck in the middle").
 */
class FastScrollbarFractionTest {

    @Test
    fun `empty list returns zero`() {
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = 0,
                totalItemsCount = 0,
                visibleItemsCount = 0,
                canScrollBackward = false,
                canScrollForward = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `at top of list returns zero regardless of visible item count`() {
        // canScrollBackward == false ⇒ we're at the top, period.
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = 0,
                totalItemsCount = 100,
                visibleItemsCount = 3,
                canScrollBackward = false,
                canScrollForward = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `at bottom of chat returns 1 even with few visible items`() {
        // === USER SCREENSHOT REGRESSION ===
        // Chat has 5 messages total, 2 visible on screen, user scrolled
        // all the way down so canScrollForward=false. Old formula:
        //   (firstVisibleIndex + offset/96) / (itemCount - 1)
        //   = (3 + 0/96) / 4 = 0.75  — stuck near the bottom but not at it.
        // New formula's canScrollForward guard: pixel-perfect, returns 1.
        assertEquals(
            1f,
            computeScrollFraction(
                firstVisibleIndex = 3,
                totalItemsCount = 5,
                visibleItemsCount = 2,
                canScrollBackward = true,
                canScrollForward = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `middle of long list uses item-count fraction`() {
        // 100 items, 10 visible on screen, scrolled so firstVisible=45.
        // denom = 100 - 10 = 90. fraction = 45 / 90 = 0.5.
        assertEquals(
            0.5f,
            computeScrollFraction(
                firstVisibleIndex = 45,
                totalItemsCount = 100,
                visibleItemsCount = 10,
                canScrollBackward = true,
                canScrollForward = true,
            ),
            0.001f,
        )
    }

    @Test
    fun `mid-list tall items use same item-count formula`() {
        // The chat-screenshot scenario as tall messages: 4 items,
        // 1 visible, scrolled to position firstVisible=1.
        // denom = 4 - 1 = 3. fraction = 1 / 3 = 0.333.
        // Old formula would have computed (1 + 0/96)/3 = 0.333 — same.
        // New formula is correct on principle, not by coincidence.
        assertEquals(
            0.333f,
            computeScrollFraction(
                firstVisibleIndex = 1,
                totalItemsCount = 4,
                visibleItemsCount = 1,
                canScrollBackward = true,
                canScrollForward = true,
            ),
            0.005f,
        )
    }

    @Test
    fun `near-bottom of long list approaches 1`() {
        // 100 items, 10 visible, scrolled so firstVisible=85.
        // denom = 90. fraction = 85/90 = 0.944.
        assertEquals(
            0.944f,
            computeScrollFraction(
                firstVisibleIndex = 85,
                totalItemsCount = 100,
                visibleItemsCount = 10,
                canScrollBackward = true,
                canScrollForward = true,
            ),
            0.005f,
        )
    }

    @Test
    fun `short list (5 messages) with 1 visible jumps to 1 at bottom`() {
        // User's screenshot scenario, distilled. The exact `1f` not
        // ~0.5 the old path produced.
        assertEquals(
            1f,
            computeScrollFraction(
                firstVisibleIndex = 4,
                totalItemsCount = 5,
                visibleItemsCount = 1,
                canScrollBackward = true,
                canScrollForward = false,
            ),
            0.0001f,
        )
    }

    @Test
    fun `firstVisible greater than denom clamps to 1`() {
        // Synthetic edge case: visibleItemsCount > totalItemsCount
        // (one item, all on screen, the user can scroll past everything
        // very quickly). Clamp to 1.
        val f = computeScrollFraction(
            firstVisibleIndex = 5,
            totalItemsCount = 3,
            visibleItemsCount = 5,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertTrue("fraction must be in [0, 1]", f in 0f..1f)
        assertEquals(1f, f, 0.0001f)
    }

    @Test
    fun `negative firstVisibleIndex clamps to 0`() {
        // Defensive — hydration edge case where LazyList briefly reports
        // a -1 first index before measurement. Should not go below 0.
        assertEquals(
            0f,
            computeScrollFraction(
                firstVisibleIndex = -1,
                totalItemsCount = 50,
                visibleItemsCount = 3,
                canScrollBackward = true,
                canScrollForward = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `no scroll possible either direction returns 0`() {
        // One-message-fits-on-screen chat. No scroll possible. The
        // helper correctly returns 0 — there's no meaningful position.
        val f = computeScrollFraction(
            firstVisibleIndex = 0,
            totalItemsCount = 1,
            visibleItemsCount = 1,
            canScrollBackward = false,
            canScrollForward = false,
        )
        assertEquals(0f, f, 0.0001f)
    }
}
