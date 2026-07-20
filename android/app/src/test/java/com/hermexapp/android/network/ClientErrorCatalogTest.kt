package com.hermexapp.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientErrorCatalogTest {

    @Test
    fun `catalog includes host-aligned codes`() {
        val codes = ClientErrorCatalog.ALL.map { it.code }.toSet()
        assertTrue(codes.contains("invalid_device_grant"))
        assertTrue(codes.contains("invalid_api_key"))
        assertTrue(codes.contains("billing_quota"))
        assertTrue(codes.contains("rate_limit"))
        assertTrue(codes.contains("approval_wait"))
        assertTrue(codes.contains("network_unreachable"))
        assertTrue(codes.contains("server_error"))
        // Excellence 13.8a: full required host set — no silent drift.
        assertEquals(ClientErrorCatalog.REQUIRED_HOST_CODES, codes)
    }

    @Test
    fun `invalid_api_key shares phone re-pair copy with invalid_device_grant`() {
        assertEquals(
            ClientErrorCatalog.INVALID_DEVICE_GRANT.message,
            ClientErrorCatalog.INVALID_API_KEY.message,
        )
        assertEquals(
            ClientErrorCatalog.INVALID_API_KEY,
            ClientErrorCatalog.byCode("invalid_api_key"),
        )
    }

    @Test
    fun `byCode returns exact entries`() {
        assertEquals(
            ClientErrorCatalog.INVALID_DEVICE_GRANT,
            ClientErrorCatalog.byCode("invalid_device_grant"),
        )
        assertEquals(null, ClientErrorCatalog.byCode(null))
        assertEquals(null, ClientErrorCatalog.byCode(""))
    }

    @Test
    fun `classify prefers explicit code`() {
        val hit = ClientErrorCatalog.classify(status = 500, code = "rate_limit")
        assertEquals("rate_limit", hit.code)
    }

    @Test
    fun `classify maps auth grant and quota heuristics`() {
        assertEquals(
            "invalid_device_grant",
            ClientErrorCatalog.classify(status = 401, message = "invalid_device_grant").code,
        )
        assertEquals(
            "billing_quota",
            ClientErrorCatalog.classify(message = "spending-limit reached").code,
        )
        assertEquals(
            "rate_limit",
            ClientErrorCatalog.classify(status = 429).code,
        )
        assertEquals(
            "approval_wait",
            ClientErrorCatalog.classify(message = "waiting for user approval /yolo").code,
        )
        assertEquals(
            "network_unreachable",
            ClientErrorCatalog.classify(offline = true).code,
        )
        assertEquals(
            "server_error",
            ClientErrorCatalog.classify(status = 502).code,
        )
    }

    @Test
    fun `userMessage surfaces approval tip copy`() {
        val msg = ClientErrorCatalog.userMessage(code = "approval_wait")
        assertTrue(msg.contains("approval") || msg.contains("YOLO") || msg.contains("yolo"))
    }

    @Test
    fun `classify embeds catalog codes in free-text SSE payloads`() {
        assertEquals(
            "invalid_api_key",
            ClientErrorCatalog.classify(message = "invalid_api_key expired").code,
        )
        assertEquals(
            "invalid_device_grant",
            ClientErrorCatalog.classify(message = "error: invalid_device_grant").code,
        )
        assertEquals(
            "rate_limit",
            ClientErrorCatalog.classify(message = "rate_limit hit").code,
        )
        // Auth heuristic must not fall through to unknown when invalid+key matched.
        assertEquals(
            "invalid_device_grant",
            ClientErrorCatalog.classify(message = "invalid key material").code,
        )
    }

    @Test
    fun `classify 403 policy is not auth re-pair`() {
        assertEquals(
            "policy_blocked",
            ClientErrorCatalog.classify(
                status = 403,
                message = "policy blocked by provider",
            ).code,
        )
        assertEquals(
            "policy_blocked",
            ClientErrorCatalog.classify(
                status = 403,
                message = ClientErrorCatalog.POLICY_BLOCKED.message,
            ).code,
        )
        val bare = ClientErrorCatalog.classify(status = 403)
        assertTrue(bare.code != "invalid_device_grant")
        assertTrue(bare.code != "invalid_api_key")
    }
}
