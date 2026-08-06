package com.hermexapp.android.features.workspace

import com.hermexapp.android.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server does not classify files — `/api/list` reports only "dir", "file"
 * or "symlink" — so the kind shown in the browser is derived from the name.
 */
class WorkspaceFileKindTest {

    private fun file(name: String) = WorkspaceEntry(name = name, path = name, type = "file")

    @Test
    fun `a directory is a folder whatever it is called`() {
        val dir = WorkspaceEntry(name = "assets.png", path = "assets.png", type = "dir")

        // A directory named like an image is still a directory.
        assertEquals(WorkspaceFileKind.FOLDER, workspaceFileKind(dir))
    }

    @Test
    fun `media, documents, code and archives are recognised`() {
        assertEquals(WorkspaceFileKind.IMAGE, workspaceFileKind(file("shot.PNG")))
        assertEquals(WorkspaceFileKind.VIDEO, workspaceFileKind(file("clip.mp4")))
        assertEquals(WorkspaceFileKind.AUDIO, workspaceFileKind(file("take.wav")))
        assertEquals(WorkspaceFileKind.DOCUMENT, workspaceFileKind(file("PLAN.md")))
        assertEquals(WorkspaceFileKind.CODE, workspaceFileKind(file("Main.kt")))
        assertEquals(WorkspaceFileKind.ARCHIVE, workspaceFileKind(file("bundle.zip")))
    }

    @Test
    fun `the extension is matched case-insensitively`() {
        assertEquals(WorkspaceFileKind.IMAGE, workspaceFileKind(file("A.JpEg")))
    }

    @Test
    fun `a hidden file is not classified by its leading dot`() {
        // ".gitignore" has no extension — the dot starts the name.
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(file(".gitignore")))
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(file(".env")))
    }

    @Test
    fun `a name with no extension is not guessed at`() {
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(file("LICENSE")))
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(file("Makefile")))
        assertEquals(WorkspaceFileKind.OTHER, workspaceFileKind(file("archive.")))
    }

    @Test
    fun `only the last extension counts`() {
        assertEquals(WorkspaceFileKind.ARCHIVE, workspaceFileKind(file("backup.tar.gz")))
        assertEquals(WorkspaceFileKind.CODE, workspaceFileKind(file("app.debug.kt")))
    }

    @Test
    fun `an escaping symlink is marked blocked, not shown as an ordinary file`() {
        // The server withholds its target and refuses to read through it, so
        // presenting it as a normal file invites a tap that can only fail.
        val escaping = WorkspaceEntry(
            name = "outside.png",
            path = "outside.png",
            type = "symlink",
            isDir = false,
            targetOutsideWorkspace = true,
        )

        assertEquals(WorkspaceFileKind.BLOCKED, workspaceFileKind(escaping))
    }
}
