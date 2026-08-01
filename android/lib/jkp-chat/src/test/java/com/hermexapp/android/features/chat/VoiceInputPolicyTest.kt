package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputPolicyTest {
    @Test
    fun `api 31 prefers on-device recognition`() {
        assertEquals(
            VoiceRecognitionMode.ON_DEVICE,
            selectVoiceRecognitionMode(31, onDeviceAvailable = true, systemAvailable = true),
        )
    }

    @Test
    fun `api 30 never selects the api 31 on-device path`() {
        assertEquals(
            VoiceRecognitionMode.SYSTEM_DEFAULT,
            selectVoiceRecognitionMode(30, onDeviceAvailable = true, systemAvailable = true),
        )
    }

    @Test
    fun `system provider is the explicit fallback`() {
        assertEquals(
            VoiceRecognitionMode.SYSTEM_DEFAULT,
            selectVoiceRecognitionMode(35, onDeviceAvailable = false, systemAvailable = true),
        )
    }

    @Test
    fun `no provider means unavailable`() {
        assertEquals(
            VoiceRecognitionMode.UNAVAILABLE,
            selectVoiceRecognitionMode(35, onDeviceAvailable = false, systemAvailable = false),
        )
    }

    @Test
    fun `existing grant starts immediately`() {
        assertEquals(VoiceStartDecision.START, decideVoiceStart(true, true))
    }

    @Test
    fun `missing grant requests permission`() {
        assertEquals(VoiceStartDecision.REQUEST_PERMISSION, decideVoiceStart(true, false))
    }

    @Test
    fun `unavailable recognizer does nothing`() {
        assertEquals(VoiceStartDecision.UNAVAILABLE, decideVoiceStart(false, true))
    }
}
