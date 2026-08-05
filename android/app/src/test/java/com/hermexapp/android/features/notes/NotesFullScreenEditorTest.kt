package com.hermexapp.android.features.notes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor used to be a card stacked above the note list, so an open note
 * competed for the screen with a scrolling list of every other note — and
 * until the row was filtered out, the same note appeared twice. Google Keep
 * opens a note; it does not show it beside everything else.
 *
 * Decision recorded here rather than in a commit message alone: the list keeps
 * its LazyColumn and swipe-to-delete instead of moving to a staggered grid.
 * Swipe-to-dismiss does not survive a grid, and trading a working, tested
 * delete-with-undo for a nicer silhouette is a bad exchange.
 *
 * No Compose UI-test runner exists in this module, so the wiring is asserted
 * in source — the same approach as the other screen contracts here.
 */
class NotesFullScreenEditorTest {

    private fun source(): String = File(
        listOf(
            "src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
            "app/src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
            "../app/src/main/java/com/hermexapp/android/features/notes/NotesScreen.kt",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `an open note takes the screen instead of sharing it with the list`() {
        assertTrue(
            "the list must not be drawn while a note is open",
            source().contains("if (openEditor != null) return@Column"),
        )
    }

    @Test
    fun `system back closes the open note before leaving Notes`() {
        // Otherwise back from an open note exits the screen entirely, which
        // reads as "my edit was thrown away".
        assertTrue(
            source().contains("BackHandler(enabled = editor is EditorState.Open)"),
        )
    }

    @Test
    fun `the top bar arrow also closes the note first`() {
        assertTrue(
            source().contains("if (editor is EditorState.Open) editor = EditorState.Closed else onClose()"),
        )
    }

    @Test
    fun `the new-note button does not float over a note being written`() {
        assertTrue(
            source().contains("!state.selectionMode && editor !is EditorState.Open"),
        )
    }

    @Test
    fun `search is hidden while a note is open`() {
        // Searching the list you cannot see is meaningless.
        assertTrue(
            source().contains("visible = showSearch && openEditor == null"),
        )
    }

    @Test
    fun `the list keeps swipe-to-delete rather than becoming a grid`() {
        val text = source()
        assertTrue("the list stays a LazyColumn", text.contains("LazyColumn"))
        assertTrue("swipe-to-delete stays", text.contains("SwipeToDismissBox"))
    }
}
