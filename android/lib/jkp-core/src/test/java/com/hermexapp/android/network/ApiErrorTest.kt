package com.hermexapp.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 403 that explains itself must not be answered with a guess.
 *
 * The host refuses read-only imported sessions with a plain reason, and 45 of
 * the operator's 78 sessions are un-writable (every claude_code and subagent
 * import, plus messaging sources). Answering all of them with "check the
 * server password" sends the user to change a credential that is already
 * correct. This is the same defect already fixed for 404.
 */
class ApiErrorTest {

    @Test
    fun `a 403 shows the server's own reason`() {
        val e = ApiError.Http(
            403,
            """{"error":"Read-only imported sessions cannot be renamed from WebUI"}""",
        )

        assertEquals("Read-only imported sessions cannot be renamed from WebUI", e.userMessage)
    }

    @Test
    fun `a 403 with no reason still advises the password`() {
        // A genuinely refused request says nothing in the body; that is when
        // the credential advice is the right answer.
        val e = ApiError.Http(403, null)

        assertTrue(
            "expected the password advice, got: ${e.userMessage}",
            "password" in e.userMessage,
        )
    }

    @Test
    fun `a 404 that explains itself is unchanged`() {
        val e = ApiError.Http(404, """{"error":"Session not found"}""")

        assertEquals("Session not found", e.userMessage)
    }
}
