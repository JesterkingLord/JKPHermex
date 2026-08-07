package com.hermexapp.android.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Labels are stored as one comma-separated string, the same shape as
 * `local_prompts.tags`. Parsing is where the sharp edges are: ordinary typing
 * produces blanks and repeats that would otherwise render as empty, untappable
 * chips.
 */
class NoteLabelsTest {

    @Test
    fun `no labels parses to nothing`() {
        assertEquals(emptyList<String>(), NoteLabels.parse(null))
        assertEquals(emptyList<String>(), NoteLabels.parse(""))
        assertEquals(emptyList<String>(), NoteLabels.parse("   "))
        // A row migrated from v3 arrives with the empty-string default.
        assertEquals(emptyList<String>(), NoteLabels.parse(","))
    }

    @Test
    fun `labels are trimmed and order is kept`() {
        assertEquals(listOf("work", "ideas"), NoteLabels.parse(" work , ideas "))
    }

    @Test
    fun `blank entries from ordinary typing are dropped`() {
        // "work,,ideas," is what a trailing comma or a double comma produces.
        assertEquals(listOf("work", "ideas"), NoteLabels.parse("work,,ideas,"))
    }

    @Test
    fun `duplicates are dropped case-insensitively, keeping the first spelling`() {
        assertEquals(listOf("Work", "ideas"), NoteLabels.parse("Work,work,ideas,WORK"))
    }

    @Test
    fun `join round-trips and cleans at the same time`() {
        assertEquals("work,ideas", NoteLabels.join(listOf(" work ", "ideas", "work", "")))
        assertEquals("", NoteLabels.join(emptyList()))
    }

    @Test
    fun `contains matches case-insensitively and ignores padding`() {
        assertTrue(NoteLabels.contains("work,ideas", "WORK"))
        assertTrue(NoteLabels.contains("work,ideas", " ideas "))
        assertFalse(NoteLabels.contains("work,ideas", "plan"))
        assertFalse(NoteLabels.contains("", "work"))
    }

    @Test
    fun `a label containing spaces survives intact`() {
        // Only commas separate; "game dev" is one label, not two.
        assertEquals(listOf("game dev", "efurc"), NoteLabels.parse("game dev, efurc"))
    }
}
