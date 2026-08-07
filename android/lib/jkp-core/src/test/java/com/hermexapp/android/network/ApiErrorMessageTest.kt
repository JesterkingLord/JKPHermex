package com.hermexapp.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed request tells the user.
 *
 * Written after a real session on the device: reading a file from a CLI
 * session 404s (the host's `/api/file` has no CLI fallback, unlike
 * `/api/list`), and the app answered "Check that the URL points to a Hermes
 * Web UI server" — advice to change a setting that was already correct, while
 * the server had said exactly what was wrong in the body.
 */
class ApiErrorMessageTest {

    @Test
    fun `a 404 that explains itself shows the server's reason`() {
        val error = ApiError.Http(404, """{"error": "Session not found"}""")

        assertEquals("Session not found", error.userMessage)
    }

    @Test
    fun `a 404 with no body still advises checking the server URL`() {
        // A genuinely wrong host is the case that advice was written for.
        val error = ApiError.Http(404, null)

        assertTrue(error.userMessage.contains("Hermes Web UI server"))
    }

    @Test
    fun `an HTML error page is not mistaken for a reason`() {
        val error = ApiError.Http(404, "<html><body>404 error not found</body></html>")

        assertTrue(error.userMessage.contains("Hermes Web UI server"))
    }

    @Test
    fun `a blank reason falls back rather than showing an empty message`() {
        val error = ApiError.Http(404, """{"error": ""}""")

        assertTrue(error.userMessage.contains("Hermes Web UI server"))
    }

    @Test
    fun `a stack trace in the body is not shown to the user`() {
        // Long, multi-line server output is not a sentence anyone can act on.
        val trace = """{"error": "Traceback (most recent call last):\n  File x\n  File y"}"""

        assertTrue(ApiError.Http(404, trace).userMessage.contains("Hermes Web UI server"))
    }

    @Test
    fun `an escaped quote inside the reason does not truncate it`() {
        val error = ApiError.Http(404, """{"error": "No file named \"a.txt\" here"}""")

        assertEquals("""No file named \"a.txt\" here""", error.userMessage)
    }

    @Test
    fun `other statuses are unaffected by the 404 handling`() {
        val error = ApiError.Http(408, """{"error": "Session not found"}""")

        assertTrue(error.userMessage.contains("took too long"))
    }
}
