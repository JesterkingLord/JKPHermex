package com.hermexapp.android.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensitiveHeaderDisplayTest {
    @Test
    fun `saved header values use a constant mask regardless of secret length`() {
        val short = maskedHeaderValue("abc")
        val long = maskedHeaderValue("very-long-sensitive-gateway-token")

        assertEquals(short, long)
        assertEquals("••••••••", short)
    }

    @Test
    fun `saved header mask does not expose any secret characters`() {
        val secret = "client-secret-1234"
        val rendered = maskedHeaderValue(secret)

        assertFalse(rendered.contains("client"))
        assertFalse(rendered.contains("1234"))
        assertFalse(rendered.any(secret.toSet()::contains))
    }

    @Test
    fun `blank values retain the same non-revealing presentation`() {
        assertEquals(maskedHeaderValue("secret"), maskedHeaderValue(""))
        assertEquals(maskedHeaderValue("secret"), maskedHeaderValue("   "))
    }
}
