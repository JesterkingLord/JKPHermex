package com.hermexapp.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingIntentParserTest {

    // --- Pairing completion (query-string form) ---

    @Test
    fun `complete pairing from full URL with pair_id and token in query`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=abc&token=xyz"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
        val cp = intent as PairingIntent.CompletePairing
        assertEquals("100.88.54.29", cp.serverUrl.host)
        assertEquals(8642, cp.serverUrl.port)
        assertEquals("abc", cp.pairId)
        assertEquals("xyz", cp.token)
    }

    @Test
    fun `complete pairing from fragment form (desktop default)`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect#pair_id=abc&token=xyz"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
        val cp = intent as PairingIntent.CompletePairing
        assertEquals("abc", cp.pairId)
        assertEquals("xyz", cp.token)
    }

    @Test
    fun `complete pairing from https hostname with full pair URL`() {
        val raw = "https://hermes.example.com/v1/pair/connect?pair_id=p1&token=t1"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
        val cp = intent as PairingIntent.CompletePairing
        assertEquals("hermes.example.com", cp.serverUrl.host)
        assertEquals(443, cp.serverUrl.port)
        assertEquals("p1", cp.pairId)
        assertEquals("t1", cp.token)
    }

    @Test
    fun `query takes precedence when both query and fragment carry credentials`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=Q&token=QT#pair_id=F&token=FT"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
        val cp = intent as PairingIntent.CompletePairing
        assertEquals("Q", cp.pairId)
        assertEquals("QT", cp.token)
    }

    @Test
    fun `server URL is normalized (path, query, fragment stripped)`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t"
        val intent = PairingIntentParser.parse(raw) as PairingIntent.CompletePairing
        assertEquals("/", intent.serverUrl.encodedPath)
        assertEquals("", intent.serverUrl.encodedQuery.orEmpty())
        assertEquals("", intent.serverUrl.encodedFragment.orEmpty())
    }

    // --- ServerUrlOnly fallback (no pair path) ---

    @Test
    fun `URL without pair path is ServerUrlOnly`() {
        val raw = "http://100.88.54.29:8642/some/other/path?foo=bar"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.ServerUrlOnly)
        val s = intent as PairingIntent.ServerUrlOnly
        assertEquals("100.88.54.29", s.serverUrl.host)
        assertEquals("/", s.serverUrl.encodedPath) // normalized
    }

    @Test
    fun `URL with pair path but missing token is ServerUrlOnly`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=abc"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.ServerUrlOnly)
    }

    @Test
    fun `URL with pair path but missing pair_id is ServerUrlOnly`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?token=xyz"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.ServerUrlOnly)
    }

    // --- Defaults and tolerance ---

    @Test
    fun `missing scheme defaults to https`() {
        val raw = "hermes.example.com/v1/pair/connect?pair_id=p&token=t"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
        val cp = intent as PairingIntent.CompletePairing
        assertEquals("https", cp.serverUrl.scheme)
        assertEquals("hermes.example.com", cp.serverUrl.host)
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        val raw = "   http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t   \n"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.CompletePairing)
    }

    // --- Invalid cases ---

    @Test
    fun `empty string is Invalid`() {
        assertTrue(PairingIntentParser.parse("") is PairingIntent.Invalid)
    }

    @Test
    fun `whitespace-only is Invalid`() {
        assertTrue(PairingIntentParser.parse("   \t\n") is PairingIntent.Invalid)
    }

    @Test
    fun `unsupported scheme is Invalid`() {
        val intent = PairingIntentParser.parse("ftp://hermes.example.com/v1/pair/connect?pair_id=p&token=t")
        assertTrue(intent is PairingIntent.Invalid)
    }

    @Test
    fun `unparseable string is Invalid`() {
        assertTrue(PairingIntentParser.parse("not a url at all") is PairingIntent.Invalid)
    }

    @Test
    fun `cleartext to public host is Invalid (matches ServerUrlNormalizer rule)`() {
        // 8.8.8.8 is not in any allowlist (not loopback, not CGNAT, not private).
        val intent = PairingIntentParser.parse("http://8.8.8.8:8000/")
        assertTrue(intent is PairingIntent.Invalid)
        val inv = intent as PairingIntent.Invalid
        assertTrue("reason should mention cleartext: ${inv.reason}", inv.reason.contains("Cleartext", ignoreCase = true))
    }

    @Test
    fun `cleartext to localhost is allowed`() {
        val intent = PairingIntentParser.parse("http://127.0.0.1:8787/")
        assertTrue(intent is PairingIntent.ServerUrlOnly)
    }

    @Test
    fun `cleartext to Tailscale CGNAT range is allowed`() {
        val intent = PairingIntentParser.parse("http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t")
        assertTrue(intent is PairingIntent.CompletePairing)
    }

    @Test
    fun `blank pair_id with non-blank token still produces ServerUrlOnly`() {
        val raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=&token=t"
        val intent = PairingIntentParser.parse(raw)
        assertTrue(intent is PairingIntent.ServerUrlOnly)
    }
}