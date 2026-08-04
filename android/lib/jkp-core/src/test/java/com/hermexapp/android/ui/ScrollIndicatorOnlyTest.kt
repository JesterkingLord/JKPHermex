package com.hermexapp.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollIndicatorOnlyTest {

    @Test
    fun `jump to latest is visible while a run is streaming and content exists below`() {
        assertTrue(shouldShowJumpToLatest(canScrollForward = true, isStreaming = true))
    }

    @Test
    fun `jump to latest is hidden when the run is idle`() {
        assertFalse(shouldShowJumpToLatest(canScrollForward = true, isStreaming = false))
    }

    @Test
    fun `jump to latest is hidden at the bottom or when content fits`() {
        assertFalse(shouldShowJumpToLatest(canScrollForward = false, isStreaming = true))
    }

    @Test
    fun `jump to latest targets the true end of a tall final item`() {
        val target = jumpToLatestTarget(itemCount = 7)

        assertEquals(6, target?.itemIndex)
        assertEquals(Int.MAX_VALUE, target?.itemScrollOffsetPx)
    }

    @Test
    fun `jump to latest has no target for an empty timeline`() {
        assertNull(jumpToLatestTarget(itemCount = 0))
    }
}
