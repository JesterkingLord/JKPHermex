package com.hermexapp.android.features.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned notes already sorted to the top (`ORDER BY pinned DESC` in
 * NotesStore), but nothing said so, and an unlabelled boundary just reads as
 * an arbitrarily ordered list. Google Keep labels the two groups.
 *
 * Headers only earn their space when both groups exist — a single header over
 * the entire list tells the reader nothing.
 */
class NotesSectionHeaderTest {

    @Test
    fun `both groups present means both are labelled`() {
        assertTrue(showSectionHeaders(pinnedCount = 1, otherCount = 1))
        assertTrue(showSectionHeaders(pinnedCount = 3, otherCount = 9))
    }

    @Test
    fun `an all-pinned list is not labelled`() {
        assertFalse(showSectionHeaders(pinnedCount = 4, otherCount = 0))
    }

    @Test
    fun `a list with nothing pinned is not labelled`() {
        assertFalse(showSectionHeaders(pinnedCount = 0, otherCount = 4))
    }

    @Test
    fun `an empty list is not labelled`() {
        assertFalse(showSectionHeaders(pinnedCount = 0, otherCount = 0))
    }
}
