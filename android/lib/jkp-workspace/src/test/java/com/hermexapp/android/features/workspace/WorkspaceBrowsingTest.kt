package com.hermexapp.android.features.workspace

import com.hermexapp.android.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ordering and path arithmetic for the workspace browser, kept pure so they
 * can be pinned without a Compose runner or a server.
 */
class WorkspaceBrowsingTest {

    private fun dir(name: String, mtime: Long? = null) =
        WorkspaceEntry(name = name, path = name, type = "dir", mtimeNs = mtime)

    private fun file(name: String, mtime: Long? = null) =
        WorkspaceEntry(name = name, path = name, type = "file", mtimeNs = mtime)

    @Test
    fun `name order puts directories first then sorts case-insensitively`() {
        val sorted = sortWorkspaceEntries(
            listOf(file("beta.txt"), dir("Zeta"), file("Alpha.txt"), dir("apps")),
            WorkspaceSort.NAME,
        )

        assertEquals(listOf("apps", "Zeta", "Alpha.txt", "beta.txt"), sorted.map { it.name })
    }

    @Test
    fun `newest order still keeps directories on top`() {
        // Scattering folders through a date sort makes the tree unreadable.
        val sorted = sortWorkspaceEntries(
            listOf(file("new.txt", 300), dir("src", 100), file("old.txt", 200)),
            WorkspaceSort.NEWEST,
        )

        assertEquals(listOf("src", "new.txt", "old.txt"), sorted.map { it.name })
    }

    @Test
    fun `an entry with no timestamp sorts last, not first`() {
        // The server omits mtime_ns when it cannot stat the entry. Treating
        // that as 0 would float unknown files to the top of a newest-first
        // list, which is the opposite of what the sort promises.
        val sorted = sortWorkspaceEntries(
            listOf(file("unknown.txt", null), file("recent.txt", 500), file("older.txt", 100)),
            WorkspaceSort.NEWEST,
        )

        assertEquals(listOf("recent.txt", "older.txt", "unknown.txt"), sorted.map { it.name })
    }

    @Test
    fun `sorting is stable for equal timestamps`() {
        val sorted = sortWorkspaceEntries(
            listOf(file("b.txt", 100), file("a.txt", 100)),
            WorkspaceSort.NEWEST,
        )

        assertEquals(listOf("a.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun `the root has a single crumb that navigates nowhere`() {
        listOf(null, "", ".", "/").forEach { path ->
            val crumbs = workspaceCrumbs(path)
            assertEquals("root crumb for '$path'", 1, crumbs.size)
            assertNull(crumbs.single().path)
        }
    }

    @Test
    fun `a nested path becomes one crumb per segment with cumulative paths`() {
        val crumbs = workspaceCrumbs("src/main/kotlin")

        assertEquals(listOf("Workspace", "src", "main", "kotlin"), crumbs.map { it.label })
        assertEquals(listOf(null, "src", "src/main", "src/main/kotlin"), crumbs.map { it.path })
    }

    @Test
    fun `stray slashes never produce a blank crumb`() {
        // A blank crumb would render as an invisible tap target that navigates
        // to nowhere in particular.
        val crumbs = workspaceCrumbs("/src//main/")

        assertEquals(listOf("Workspace", "src", "main"), crumbs.map { it.label })
        assertEquals(listOf(null, "src", "src/main"), crumbs.map { it.path })
    }

    @Test
    fun `parent of a nested path drops the last segment`() {
        assertEquals("src/main", workspaceParentPath("src/main/kotlin"))
        assertEquals("src", workspaceParentPath("src/main"))
    }

    @Test
    fun `there is no parent at or above the root`() {
        listOf(null, "", ".", "/", "src").forEach { path ->
            assertNull("'$path' must have no parent", workspaceParentPath(path))
        }
    }
}
