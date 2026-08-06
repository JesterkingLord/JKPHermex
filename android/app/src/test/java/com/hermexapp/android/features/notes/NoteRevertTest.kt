package com.hermexapp.android.features.notes

import com.hermexapp.android.persistence.NoteEntity
import com.hermexapp.android.persistence.NoteStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note editor autosaves on every keystroke, so there was no earlier
 * version to go back to: a mistyped edit simply became the note. That is a
 * sharp edge in a feature that has already lost data once — the editor was
 * dropping characters until `1e14ab1`.
 *
 * "Undo edits" restores the note as it was when the editor opened. This pins
 * when that offer appears, because an always-lit button that usually does
 * nothing trains people to ignore it.
 */
class NoteRevertTest {

    private fun note(title: String, body: String) = NoteEntity(
        id = "n1",
        title = title,
        body = body,
        colorHex = "#FFE082",
        pinned = false,
        status = NoteStatus.IDEA,
        updatedAtMillis = 1L,
        createdAtMillis = 1L,
    )

    @Test
    fun `an untouched note offers nothing to undo`() {
        val opened = note("gamedev", "working on EFURC")

        assertFalse(noteHasUnsavedEdits(opened, "gamedev", "working on EFURC"))
    }

    @Test
    fun `a changed body offers the undo`() {
        val opened = note("gamedev", "working on EFURC")

        assertTrue(noteHasUnsavedEdits(opened, "gamedev", "wrking on EFURC"))
    }

    @Test
    fun `a changed title offers the undo`() {
        val opened = note("gamedev", "working on EFURC")

        assertTrue(noteHasUnsavedEdits(opened, "EFURC", "working on EFURC"))
    }

    @Test
    fun `losing the whole body still offers a way back`() {
        // The case that matters most: text deleted by accident is already
        // saved, so the snapshot is the only remaining copy.
        val opened = note("gamedev", "working on EFURC until it's finished")

        assertTrue(noteHasUnsavedEdits(opened, "gamedev", ""))
    }

    @Test
    fun `a brand-new note has nothing to revert to`() {
        // Created empty by the FAB — there is no earlier version, and offering
        // "undo" would suggest one exists.
        assertFalse(noteHasUnsavedEdits(null, "anything", "typed so far"))
    }
}
