package com.hermexapp.android.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentSwatchTest {

    @Test
    fun `light swatches use a dark selection mark`() {
        assertEquals(Color.Black, accentSwatchForeground(Color.White))
        assertEquals(Color.Black, accentSwatchForeground(Color(0xFFFFD700)))
    }

    @Test
    fun `dark swatches use a light selection mark`() {
        assertEquals(Color.White, accentSwatchForeground(Color(0xFF1C1C1E)))
    }
}
