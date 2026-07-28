package com.hermexapp.android.features.chat

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerControlsTest {

    @Test
    fun `streaming with an empty draft exposes stop instead of send`() {
        assertEquals(
            ComposerPrimaryAction.STOP,
            composerPrimaryAction(isStreaming = true, hasDraft = false),
        )
    }

    @Test
    fun `a draft keeps send available even while a response is streaming`() {
        assertEquals(
            ComposerPrimaryAction.SEND,
            composerPrimaryAction(isStreaming = true, hasDraft = true),
        )
    }

    @Test
    fun `idle empty composer exposes disabled send`() {
        assertEquals(
            ComposerPrimaryAction.DISABLED_SEND,
            composerPrimaryAction(isStreaming = false, hasDraft = false),
        )
    }

    @Test
    fun `visible IME suppresses the separate navigation bar reservation`() {
        assertFalse(invokeComposerNavigationBarPaddingPolicy(imeVisible = true))
    }

    @Test
    fun `hidden IME keeps the navigation bar reservation`() {
        assertTrue(invokeComposerNavigationBarPaddingPolicy(imeVisible = false))
    }

    @Test
    fun `chat composer requests sentence capitalization from the keyboard`() {
        assertEquals(
            KeyboardCapitalization.Sentences,
            invokeChatComposerKeyboardOptions()?.capitalization,
        )
    }

    /**
     * Reflection keeps the test compilable during the RED step, before the
     * wished-for production policy exists. Once implemented this invokes the
     * real top-level function, so a missing or inverted policy fails here.
     */
    private fun invokeComposerNavigationBarPaddingPolicy(imeVisible: Boolean): Boolean {
        val method = Class.forName(
            "com.hermexapp.android.features.chat.ComposerControlsKt",
        ).declaredMethods.singleOrNull {
            it.name == "shouldApplyComposerNavigationBarPadding" &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
        // The missing-function RED state must fail the IME-visible assertion.
        return method?.invoke(null, imeVisible) as? Boolean ?: true
    }

    private fun invokeChatComposerKeyboardOptions(): KeyboardOptions? {
        val method = Class.forName(
            "com.hermexapp.android.features.chat.ComposerControlsKt",
        ).declaredMethods.singleOrNull {
            it.name == "chatComposerKeyboardOptions" && it.parameterCount == 0
        }
        return method?.invoke(null) as? KeyboardOptions
    }
}
