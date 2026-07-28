package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderNavigationTest {

    @Test
    fun `phone drawer replaces the duplicate back control with one menu action`() {
        assertEquals(ChatLeadingAction.MENU, chatLeadingAction(hasDrawer = true))
    }

    @Test
    fun `nested chat without a drawer keeps the back action`() {
        assertEquals(ChatLeadingAction.BACK, chatLeadingAction(hasDrawer = false))
    }
}
