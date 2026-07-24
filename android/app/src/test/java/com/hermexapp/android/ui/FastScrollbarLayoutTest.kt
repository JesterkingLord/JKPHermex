package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 9.13 (2026-07-24) — Tests for [thumbTopEdgeOffsetPx].
 *
 * The previous "thumb stuck at top" bug was caused by the layout
 * modifier placing the thumb's CENTER on the fraction line; at
 * fraction = 1.0 the bottom half of the thumb extended past the
 * track. Users read that as "stuck at the top".
 *
 * The fix anchors the thumb's TOP edge to a [0, trackHeight - thumb]
 * travel range so the entire thumb always stays inside the visible
 * track. These tests prove the math at the boundaries and at
 * midpoints for a trackHeight/thumb combination that mirrors the
 * real chat scrollbar (1268 px tall track, 96 px tall thumb).
 */
class FastScrollbarLayoutTest {

    private val trackHeightPx = 1268
    private val thumbHeightPx = 96

    @Test
    fun `fraction 0_0 puts thumb at track top`() {
        val y = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 0.0f)
        // Thumb top edge should sit ABOVE the inset so the visible
        // thumb aligns with the visible track starting at trackTop.
        // trackInset = thumbHeightPx / 2 = 48 → trackTop = 48.
        // travel = trackBottom - trackTop - thumbH = 1268 - 48 - 48 - 96 = 1076.
        // y = trackTop + 0 = 48.
        assertEquals(48, y)
    }

    @Test
    fun `fraction 1_0 puts thumb at track bottom without overflow`() {
        val y = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 1.0f)
        // y = trackTop + travel = 48 + 1076 = 1124.
        // Thumb top = 1124, thumb bottom = 1124 + 96 = 1220.
        // trackBottom = 1268 - 48 = 1220. So the thumb's bottom
        // edge aligns exactly with the visible track's bottom edge.
        assertEquals(1124, y)
        // The CRITICAL assertion: thumb bottom must NOT exceed
        // track bottom. The prior implementation violated this.
        assertTrue(
            "thumb bottom (${y + thumbHeightPx}) must not exceed track bottom (${trackHeightPx - 48})",
            y + thumbHeightPx <= trackHeightPx - 48,
        )
    }

    @Test
    fun `fraction 0_5 puts thumb at midpoint`() {
        val y = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 0.5f)
        val mid = (48 + 1124) / 2
        // Allow ±1 rounding tolerance for the roundToInt call.
        assertTrue("y=$y should be near mid=$mid", kotlin.math.abs(y - mid) <= 1)
    }

    @Test
    fun `fraction is clamped to 0`() {
        val yTop = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, -0.5f)
        val yZero = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 0.0f)
        assertEquals(yZero, yTop)
    }

    @Test
    fun `fraction is clamped to 1`() {
        val yBot = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 1.5f)
        val yOne = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, 1.0f)
        assertEquals(yOne, yBot)
    }

    @Test
    fun `edge case - small track where thumb is bigger than travel`() {
        // If thumb height ≥ trackHeight, we cannot place the thumb
        // inside the track at all. Function must return 0 instead of
        // a negative offset (which would render the thumb above the
        // track).
        val y = thumbTopEdgeOffsetPx(trackHeightPx = 100, thumbHeightPx = 200, fraction = 0.5f)
        // travel = (100 - 50 - 200).coerceAtLeast(0) = 0.
        // y = trackTop + 0 = 50.
        // The thumb (200 px) overflows the track (100 px) but at
        // least the offset is non-negative.
        assertTrue("y=$y should be non-negative", y >= 0)
    }

    @Test
    fun `edge case - zero track height returns 0`() {
        assertEquals(0, thumbTopEdgeOffsetPx(0, 96, 0.5f))
    }

    @Test
    fun `edge case - zero thumb height returns 0`() {
        assertEquals(0, thumbTopEdgeOffsetPx(1268, 0, 0.5f))
    }

    /**
     * The headline regression test for the user complaint.
     * Before the fix: at fraction = 1.0 the thumb's bottom edge
     * extended past the visible track, making the thumb appear
     * "stuck at the top". After the fix: the thumb's bottom edge
     * sits exactly on the visible track's bottom edge.
     */
    @Test
    fun `regression - thumb never overflows track at any fraction`() {
        for (i in 0..20) {
            val fraction = i / 20f
            val y = thumbTopEdgeOffsetPx(trackHeightPx, thumbHeightPx, fraction)
            // Thumb top must be ≥ track top (no overflow upward).
            assertTrue(
                "fraction=$fraction: thumb top ($y) below track top (48)",
                y >= 48,
            )
            // Thumb bottom must be ≤ track bottom (no overflow downward).
            assertTrue(
                "fraction=$fraction: thumb bottom (${y + thumbHeightPx}) above track bottom (${trackHeightPx - 48})",
                y + thumbHeightPx <= trackHeightPx - 48,
            )
        }
    }
}
