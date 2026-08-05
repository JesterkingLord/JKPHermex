package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandDispatchTest {

    @Test fun `parses steer with argument`() {
        val parsed = SlashCommand.parse("/steer fix the bug")
        assertNotNull(parsed)
        assertEquals(SlashCommand.STEER, parsed!!.first)
        assertEquals("fix the bug", parsed.second)
    }

    @Test fun `parses steer without argument`() {
        val parsed = SlashCommand.parse("/steer")
        assertNotNull(parsed)
        assertEquals(SlashCommand.STEER, parsed!!.first)
        assertEquals("", parsed.second)
    }

    @Test fun `parses queue with q alias`() {
        val parsed = SlashCommand.parse("/q hello")
        assertNotNull(parsed)
        assertEquals(SlashCommand.QUEUE, parsed!!.first)
        assertEquals("hello", parsed.second)
    }

    @Test fun `parses interrupt with stop alias`() {
        val parsed = SlashCommand.parse("/stop now")
        assertNotNull(parsed)
        assertEquals(SlashCommand.INTERRUPT, parsed!!.first)
        assertEquals("now", parsed.second)
    }

    @Test fun `parses rename with title alias`() {
        val parsed = SlashCommand.parse("/title New name")
        assertNotNull(parsed)
        assertEquals(SlashCommand.RENAME, parsed!!.first)
        assertEquals("New name", parsed.second)
    }

    @Test fun `parses status`() {
        val parsed = SlashCommand.parse("/status")
        assertNotNull(parsed)
        assertEquals(SlashCommand.STATUS, parsed!!.first)
    }

    @Test fun `parses help with question mark alias`() {
        val parsed = SlashCommand.parse("/?")
        assertNotNull(parsed)
        assertEquals(SlashCommand.HELP, parsed!!.first)
    }

    @Test fun `returns null for unknown verb`() {
        assertNull(SlashCommand.parse("/unknown"))
    }

    @Test fun `returns null for plain text without slash`() {
        assertNull(SlashCommand.parse("plain text"))
    }

    @Test fun `returns null for empty string`() {
        assertNull(SlashCommand.parse(""))
    }

    @Test fun `filterSlashCommands returns all when draft is just slash`() {
        val list = filterSlashCommands("/")
        assertEquals(6, list.size)
    }

    @Test fun `filterSlashCommands narrows by prefix`() {
        val list = filterSlashCommands("/s")
        assertTrue(list.size >= 2)
        assertTrue(list.all { it.verb.startsWith("s", ignoreCase = true) })
    }

    @Test fun `filterSlashCommands returns empty for plain text`() {
        assertTrue(filterSlashCommands("plain text").isEmpty())
    }

    @Test fun `filterSlashCommands returns empty when space is in draft`() {
        assertTrue(filterSlashCommands("/steer foo").isEmpty())
    }
}
