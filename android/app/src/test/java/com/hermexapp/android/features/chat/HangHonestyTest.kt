package com.hermexapp.android.features.chat

import com.hermexapp.android.network.ClientErrorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HangHonestyTest {

    @Test
    fun `no tip when not streaming or before threshold`() {
        assertNull(HangHonesty.tipIfStalled(isStreaming = false, silentSeconds = 60, hasPendingApproval = false))
        assertNull(HangHonesty.tipIfStalled(isStreaming = true, silentSeconds = 14, hasPendingApproval = false))
    }

    @Test
    fun `tip appears after silent threshold without local approval`() {
        val tip = HangHonesty.tipIfStalled(
            isStreaming = true,
            silentSeconds = HangHonesty.STALL_SECONDS,
            hasPendingApproval = false,
        )
        assertEquals(HangHonesty.TIP, tip)
        assertTrue(tip!!.contains("approval") || tip.contains("yolo") || tip.contains("YOLO"))
    }

    @Test
    fun `suppresses tip when local approval overlay is up`() {
        assertNull(
            HangHonesty.tipIfStalled(
                isStreaming = true,
                silentSeconds = 90,
                hasPendingApproval = true,
            ),
        )
    }

    @Test
    fun `banner includes elapsed after threshold`() {
        val banner = HangHonesty.banner(22)
        assertTrue(banner.contains("22s"))
        assertTrue(banner.contains("approval") || banner.lowercase().contains("yolo"))
    }

    @Test
    fun `transport failure maps connection drops to network honesty`() {
        val msg = HangHonesty.transportFailureMessage("Software caused connection abort")
        assertTrue(msg.contains("unreachable") || msg.lowercase().contains("connection"))
        assertTrue(msg.contains("resend") || msg.contains("session"))
    }

    @Test
    fun `empty transport failure still returns stream interrupted copy`() {
        val msg = HangHonesty.transportFailureMessage(null)
        assertEquals(HangHonesty.STREAM_INTERRUPTED, msg)
    }

    @Test
    fun `stream error maps invalid_device_grant through catalog`() {
        val msg = HangHonesty.streamErrorMessage("invalid_device_grant: revoked")
        assertEquals(ClientErrorCatalog.INVALID_DEVICE_GRANT.message, msg)
        assertTrue(HangHonesty.isAuthFailureMessage("invalid_device_grant"))
        assertTrue(HangHonesty.isAuthFailureMessage("invalid_api_key expired"))
    }

    @Test
    fun `stream error maps rate limit and empty payload`() {
        val rate = HangHonesty.streamErrorMessage("rate_limit hit")
        assertEquals(ClientErrorCatalog.RATE_LIMIT.message, rate)
        assertEquals(ClientErrorCatalog.UNKNOWN.message, HangHonesty.streamErrorMessage(null))
        assertEquals(false, HangHonesty.isAuthFailureMessage("rate_limit"))
    }

    @Test
    fun `stream drop recovery keeps partial and offers resend on transport`() {
        val withPartial = HangHonesty.streamDropRecovery(
            hadPartialAssistantText = true,
            isTransportDrop = true,
        )
        assertEquals(true, withPartial.keepPartial)
        assertEquals(true, withPartial.offerResend)
        assertTrue(withPartial.tip.contains("unreachable") || withPartial.tip.contains("resend"))

        val noPartialInBand = HangHonesty.streamDropRecovery(
            hadPartialAssistantText = false,
            isTransportDrop = false,
        )
        assertEquals(true, noPartialInBand.keepPartial)
        assertEquals(false, noPartialInBand.offerResend)
        assertTrue(HangHonesty.shouldKeepPartialTranscript(true))
        assertTrue(HangHonesty.shouldKeepPartialTranscript(false)) // contract: never wipe policy
    }
}
