package com.hermexapp.android.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding pinned against the real shapes emitted by `list_dir`
 * (`.codex-tmp/hermes-webui/api/workspace.py`), because the DTO had drifted
 * from them: it declared `@SerialName("is_directory")`, which the server never
 * sends.
 *
 * The endpoint emits two different shapes:
 *  - a regular entry has NO directory flag at all — its kind is in `type`;
 *  - a symlink carries `is_dir`, plus `target` and `target_outside_workspace`.
 *
 * So `is_directory` decoded to null every time. Ordinary directories still
 * browsed by luck of the `type == "dir"` fallback, but a symlink pointing at a
 * directory did not: its own type is "symlink", and the flag that would have
 * said otherwise was unreadable.
 */
class WorkspaceEntryDecodingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a regular directory has no flag and is recognised by type`() {
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"src","path":"src","type":"dir","size":null,"mtime_ns":1754300000000000000}""",
        )

        assertNull("the server sends no is_dir for regular entries", entry.isDir)
        assertTrue(entry.isBrowsableDirectory)
        assertEquals(1754300000000000000L, entry.mtimeNs)
    }

    @Test
    fun `a regular file is not browsable and carries its size`() {
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"README.md","path":"README.md","type":"file","size":4096,"mtime_ns":17543}""",
        )

        assertFalse(entry.isBrowsableDirectory)
        assertEquals(4096L, entry.size)
    }

    @Test
    fun `a symlink to a directory is browsable`() {
        // The regression: type is "symlink", so only is_dir can say this is a
        // directory — and is_dir was unreadable before.
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"link","path":"link","type":"symlink","target":"/ws/real",
                "is_dir":true,"target_outside_workspace":false,"mtime_ns":17543}""",
        )

        assertTrue("a symlinked directory must be browsable", entry.isBrowsableDirectory)
        assertEquals("/ws/real", entry.target)
    }

    @Test
    fun `a symlink to a file is not browsable`() {
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"link","path":"link","type":"symlink","target":"/ws/f.txt",
                "is_dir":false,"target_outside_workspace":false,"size":12,"mtime_ns":17543}""",
        )

        assertFalse(entry.isBrowsableDirectory)
    }

    @Test
    fun `a symlink escaping the workspace is never offered for navigation`() {
        // The server withholds target, size and resolved kind for these on
        // purpose and refuses to read through them, so the UI must not invite
        // a tap that can only fail.
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"escape","path":"escape","type":"symlink","is_dir":false,
                "target_outside_workspace":true,"mtime_ns":17543}""",
        )

        assertFalse(entry.isBrowsableDirectory)
        assertNull("the destination is deliberately not disclosed", entry.target)
        assertTrue(entry.targetOutsideWorkspace == true)
    }

    @Test
    fun `an escaping symlink is not browsable even if is_dir says otherwise`() {
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"escape","path":"escape","type":"symlink","is_dir":true,
                "target_outside_workspace":true}""",
        )

        assertFalse(entry.isBrowsableDirectory)
    }

    @Test
    fun `unknown and missing fields are tolerated`() {
        val entry = json.decodeFromString<WorkspaceEntry>(
            """{"name":"x","some_future_field":"ignored"}""",
        )

        assertEquals("x", entry.name)
        assertNull(entry.size)
        assertFalse(entry.isBrowsableDirectory)
    }

    @Test
    fun `the list response decodes with its workspace path`() {
        val response = json.decodeFromString<DirectoryListResponse>(
            """{"entries":[{"name":"a","type":"dir"}],"path":".",
                "workspace":"C:/ws","workspace_recovered":false,"signature":"abc"}""",
        )

        assertEquals(1, response.entries?.size)
        assertEquals("C:/ws", response.workspace)
    }
}
