package com.hermexapp.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the tolerant-decoding configuration every wire model must use
 * (root AGENTS.md hard rule #3): unknown fields are ignored, absent fields
 * decode to null, and neither ever throws. Wire models arrive in phase 1;
 * they must all decode through a Json instance configured like this one.
 */
class TolerantDecodingTest {

    @Serializable
    private data class Probe(
        val id: String? = null,
        val name: String? = null,
        val count: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unknown fields are ignored, not fatal`() {
        val payload = """
            {
              "id": "abc",
              "name": "session",
              "count": 3,
              "added_by_a_future_server_version": {"nested": [1, 2, 3]},
              "another_unknown": true
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Probe>(payload)

        assertEquals("abc", decoded.id)
        assertEquals("session", decoded.name)
        assertEquals(3, decoded.count)
    }

    @Test
    fun `missing fields decode to null, not an exception`() {
        val decoded = json.decodeFromString<Probe>("""{"id": "only-id"}""")

        assertEquals("only-id", decoded.id)
        assertNull(decoded.name)
        assertNull(decoded.count)
    }
}
