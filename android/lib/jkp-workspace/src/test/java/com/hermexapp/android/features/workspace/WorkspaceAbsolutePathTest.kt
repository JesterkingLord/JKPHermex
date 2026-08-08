package com.hermexapp.android.features.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The join that feeds `/api/media`, pinned against the endpoint's live
 * behaviour rather than assumption.
 *
 * Probed on the running host: an absolute path returns 200 with the file's
 * bytes in either slash style, while a **relative** path and anything outside
 * the server's allowed roots both return **403**. So the interesting cases here
 * are the ones that must return null — every null is a request not sent, and
 * every non-null is a request that has to be accepted.
 */
class WorkspaceAbsolutePathTest {

    @Test
    fun `a windows root keeps backslashes`() {
        assertEquals(
            """C:\Users\me\workspace\notes\todo.png""",
            workspaceAbsolutePath("""C:\Users\me\workspace""", "notes/todo.png"),
        )
    }

    @Test
    fun `a posix root keeps forward slashes`() {
        assertEquals(
            "/home/me/ws/notes/todo.png",
            workspaceAbsolutePath("/home/me/ws", "notes/todo.png"),
        )
    }

    @Test
    fun `a relative path in windows form is rejoined with the root's separator`() {
        // /api/list reports separators inconsistently across hosts; the root
        // decides, so the server sees one style rather than a mixture.
        assertEquals(
            """C:\ws\a\b.png""",
            workspaceAbsolutePath("""C:\ws""", """a\b.png"""),
        )
    }

    @Test
    fun `no root means no request`() {
        // The deployed /api/list omits `workspace`, so this is the ordinary
        // case, not an edge one. /api/media answers a relative path with 403,
        // so there is nothing worth sending.
        assertNull(workspaceAbsolutePath(null, "a.png"))
        assertNull(workspaceAbsolutePath("", "a.png"))
        assertNull(workspaceAbsolutePath("   ", "a.png"))
    }

    @Test
    fun `a missing relative path means no request`() {
        assertNull(workspaceAbsolutePath("""C:\ws""", null))
    }

    @Test
    fun `traversal is refused here rather than sent to be refused there`() {
        // The server blocks it anyway (403), so a request built to be rejected
        // is a bug on this side worth failing loudly.
        assertNull(workspaceAbsolutePath("""C:\ws""", "../../Windows/System32/drivers/etc/hosts"))
        assertNull(workspaceAbsolutePath("/home/me/ws", "a/../../b.png"))
    }

    @Test
    fun `dot is the workspace root, which is how api-list spells it`() {
        assertEquals("""C:\ws""", workspaceAbsolutePath("""C:\ws""", "."))
        assertEquals("""C:\ws""", workspaceAbsolutePath("""C:\ws""", ""))
    }

    @Test
    fun `a trailing separator on the root does not double up`() {
        assertEquals("""C:\ws\a.png""", workspaceAbsolutePath("""C:\ws\""", "a.png"))
        assertEquals("/ws/a.png", workspaceAbsolutePath("/ws/", "a.png"))
    }

    @Test
    fun `leading slashes on the relative path do not produce an empty segment`() {
        assertEquals("""C:\ws\a.png""", workspaceAbsolutePath("""C:\ws""", "/a.png"))
    }

    @Test
    fun `names alone classify, for paths that arrive without an entry`() {
        assertEquals(WorkspaceFileKind.IMAGE, workspaceFileKind("shot.PNG"))
        assertEquals(WorkspaceFileKind.CODE, workspaceFileKind("main.kt"))
        // A leading dot is a hidden file, not an extension.
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(".gitignore"))
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind("LICENSE"))
    }
}
