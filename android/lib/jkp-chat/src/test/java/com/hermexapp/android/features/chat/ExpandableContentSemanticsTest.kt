package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandableContentSemanticsTest {
    @Test
    fun `expanded content exposes an expanded state value`() {
        assertEquals("Expanded", expandableStateDescription(expanded = true))
    }

    @Test
    fun `collapsed content exposes a collapsed state value`() {
        assertEquals("Collapsed", expandableStateDescription(expanded = false))
    }
}
