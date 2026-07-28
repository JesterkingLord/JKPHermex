package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HermexComponentsTest {

    @Test
    fun `compact visual buttons retain a 48dp touch target`() {
        assertEquals(48, circleButtonTouchTargetDp(36))
        assertEquals(48, circleButtonTouchTargetDp(40))
        assertEquals(48, circleButtonTouchTargetDp(48))
    }

    @Test
    fun `larger button requests keep their larger touch target`() {
        assertEquals(56, circleButtonTouchTargetDp(56))
    }
}
