package com.hermexapp.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `headings, paragraphs, and lists parse into blocks`() {
        val md = """
            # Title
            Some intro text.

            - one
            - two

            1. first
            2. second
        """.trimIndent()

        val blocks = parseBlocks(md)

        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Some intro text."), blocks[1])
        assertEquals(MdBlock.BulletList(listOf("one", "two")), blocks[2])
        assertEquals(MdBlock.OrderedList(1, listOf("first", "second")), blocks[3])
    }

    @Test
    fun `fenced code block keeps its language and body`() {
        val md = """
            ```python
            def greet(name):
                return f"Hey {name}"
            ```
        """.trimIndent()

        val code = parseBlocks(md).single() as MdBlock.Code
        assertEquals("python", code.language)
        assertTrue(code.code.contains("def greet(name):"))
    }

    @Test
    fun `an unterminated fence still renders as a code block (streaming-safe)`() {
        // Mid-stream: the closing ``` has not arrived yet.
        val md = "```\npartial code line"
        val code = parseBlocks(md).single() as MdBlock.Code
        assertTrue(code.code.contains("partial code line"))
    }

    @Test
    fun `a pipe table parses header and rows`() {
        val md = """
            | Tool | Purpose |
            |------|---------|
            | terminal | Shell commands |
            | patch | File edits |
        """.trimIndent()

        val table = parseBlocks(md).single() as MdBlock.Table
        assertEquals(listOf("Tool", "Purpose"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("terminal", "Shell commands"), table.rows[0])
    }

    @Test
    fun `a table with only a header row still renders (streaming-safe)`() {
        val md = "| A | B |\n|---|---|"
        val table = parseBlocks(md).single() as MdBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `blockquotes and mixed content coexist`() {
        val blocks = parseBlocks("> a quote\n\nplain after")
        assertEquals(MdBlock.Quote("a quote"), blocks[0])
        assertEquals(MdBlock.Paragraph("plain after"), blocks[1])
    }

    @Test
    fun `empty input yields no blocks and never throws`() {
        assertTrue(parseBlocks("").isEmpty())
        assertTrue(parseBlocks("   \n  \n").isEmpty())
    }
}
