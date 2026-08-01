package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastScrollbarGestureTest {
    @Test
    fun `track coordinate maps top middle and bottom to normalized fractions`() {
        assertEquals(0f, trackFractionAt(positionYPx = 0f, trackHeightPx = 800)!!, 0.0001f)
        assertEquals(0.5f, trackFractionAt(positionYPx = 400f, trackHeightPx = 800)!!, 0.0001f)
        assertEquals(1f, trackFractionAt(positionYPx = 800f, trackHeightPx = 800)!!, 0.0001f)
    }

    @Test
    fun `track coordinate clamps pointer movement beyond the track`() {
        assertEquals(0f, trackFractionAt(positionYPx = -20f, trackHeightPx = 800)!!, 0.0001f)
        assertEquals(1f, trackFractionAt(positionYPx = 920f, trackHeightPx = 800)!!, 0.0001f)
    }

    @Test
    fun `track coordinate rejects invalid geometry`() {
        assertNull(trackFractionAt(positionYPx = 1f, trackHeightPx = 0))
        assertNull(trackFractionAt(positionYPx = 1f, trackHeightPx = -1))
        assertNull(trackFractionAt(positionYPx = Float.NaN, trackHeightPx = 800))
        assertNull(trackFractionAt(positionYPx = Float.POSITIVE_INFINITY, trackHeightPx = 800))
    }
}
