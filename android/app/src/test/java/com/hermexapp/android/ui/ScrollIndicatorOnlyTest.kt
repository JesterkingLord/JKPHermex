package com.hermexapp.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollIndicatorOnlyTest {

    @Test
    fun `jump to latest is visible when content exists below`() {
        assertTrue(shouldShowJumpToLatest(canScrollForward = true))
    }

    @Test
    fun `jump to latest is hidden at the bottom or when content fits`() {
        assertFalse(shouldShowJumpToLatest(canScrollForward = false))
    }
}
