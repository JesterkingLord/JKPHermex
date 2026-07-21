package com.hermexapp.android.ui.markdown

/** Block-level markdown model. Kept separate from the composables so the parser is unit-testable. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String?, val code: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val start: Int, val items: List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

/**
 * Streaming-tolerant block parser. Never throws on partial input: an
 * unterminated ``` fence renders everything after it as a code block, and a
 * table with only a header row still renders. Mirrors what the iOS streaming
 * markdown pipeline handles for hermes responses.
 */
fun parseBlocks(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.split("\n")
    var i = 0

    fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.replace(Regex("[|\\-: ]"), "").isEmpty() && t.contains("-")
    }

    fun splitRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> i++

            // Fenced code block — tolerate a missing closing fence.
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume closing fence when present
                blocks.add(MdBlock.Code(language, code.toString().trimEnd('\n')))
            }

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
                i++
            }

            trimmed.startsWith(">") -> {
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.appendLine(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
            }

            // Pipe table: a header row followed by a |---|---| separator.
            trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                val header = splitRow(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size && (lines[i].trim().startsWith("- ") || lines[i].trim().startsWith("* "))) {
                    items.add(lines[i].trim().drop(2).trim())
                    i++
                }
                blocks.add(MdBlock.BulletList(items))
            }

            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                val items = mutableListOf<String>()
                val start = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                while (i < lines.size && Regex("^\\d+\\.\\s").containsMatchIn(lines[i].trim())) {
                    items.add(lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s"), ""))
                    i++
                }
                blocks.add(MdBlock.OrderedList(start, items))
            }

            else -> {
                val paragraph = StringBuilder()
                while (i < lines.size && lines[i].trim().isNotEmpty() &&
                    !lines[i].trim().startsWith("```") &&
                    !lines[i].trim().startsWith("#") &&
                    !lines[i].trim().startsWith(">") &&
                    !lines[i].trim().startsWith("- ") &&
                    !lines[i].trim().startsWith("* ") &&
                    !(lines[i].trim().startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
                ) {
                    if (paragraph.isNotEmpty()) paragraph.append("\n")
                    paragraph.append(lines[i].trim())
                    i++
                }
                if (paragraph.isNotEmpty()) blocks.add(MdBlock.Paragraph(paragraph.toString()))
            }
        }
    }

    return blocks
}
