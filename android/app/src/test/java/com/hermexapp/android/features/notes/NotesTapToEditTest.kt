package com.hermexapp.android.features.notes

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported from real use, 2026-08-05: "still can't edit notes in JKPHermex".
 *
 * The row tap was an explicit no-op outside selection mode, and the FAB only
 * ever created a *new* note, so an existing note had no route to the editor at
 * all. It could be read, pinned, searched and deleted — never edited.
 *
 * This module has no Compose UI-test runner (no Robolectric or instrumentation,
 * and test-only dependencies are out of policy here), so the same approach as
 * the other screen contracts applies: assert the wiring in source. It is a
 * weaker check than a click test, but it does catch the exact regression —
 * a row whose tap goes nowhere.
 */
class NotesTapToEditTest {

    private fun notesScreenSource(): String = File(
        listOf(
            "src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
            "app/src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
            "../app/src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `the notes list exposes an edit callback`() {
        val source = notesScreenSource()
        assertTrue(
            "NotesList must take an onEdit callback",
            source.contains("onEdit: (String) -> Unit"),
        )
        assertTrue(
            "the screen must open the editor for the tapped note",
            source.contains("onEdit = { id -> editor = EditorState.Open(id) }"),
        )
    }

    @Test
    fun `tapping a row outside selection mode opens that note`() {
        val source = notesScreenSource()
        assertTrue(
            "a row tap must reach onEdit",
            source.contains("if (state.selectionMode) viewModel.toggleSelection(note.id) else onEdit(note.id)"),
        )
    }

    @Test
    fun `the row tap is no longer a documented no-op`() {
        val source = notesScreenSource()
        assertFalse(
            "the no-op comment described the bug; it must not come back",
            source.contains("No-op otherwise"),
        )
    }

    @Test
    fun `selection mode still selects rather than opening the editor`() {
        // Long-press starts multi-select; while it is active a tap must keep
        // toggling selection, or bulk delete becomes unusable.
        val source = notesScreenSource()
        assertTrue(
            source.contains("if (state.selectionMode) viewModel.toggleSelection(note.id)"),
        )
    }
}
