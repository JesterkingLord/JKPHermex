package com.hermexapp.android.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * A small, dependency-free Markdown renderer built for *incrementally growing*
 * text (the plan's flagged risk — swift-markdown-ui has no Compose analog).
 * The block parser tolerates an unterminated fence or half-written table, so a
 * response mid-stream never throws or flickers: an open ``` fence just renders
 * what it has so far as a code block. Supports headings, bold/italic/inline
 * code/links inline, fenced code blocks, bullet/ordered lists, blockquotes, and
 * pipe tables — the shapes hermes responses actually use.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    // Pre-process math regions: $...$ and $$...$$ become tagged tokens
    // that the inline renderer recognises and styles as math (italic
    // serif). This is the G-lite pass — no layout engine, just Unicode
    // symbol substitution + sup/sub conversion.
    val preprocessed = remember(text) { MathLite.replaceWithTags(text) }
    val blocks = remember(preprocessed) { parseBlocks(preprocessed) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> BlockView(block, baseStyle) }
    }
}

@Composable
private fun BlockView(block: MdBlock, baseStyle: androidx.compose.ui.text.TextStyle) {
    val palette = LocalHermexPalette.current
    when (block) {
        is MdBlock.Heading -> Text(
            inlineAnnotated(block.text, palette.accent),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
        )

        is MdBlock.Paragraph -> Text(inlineAnnotated(block.text, palette.accent), style = baseStyle)

        is MdBlock.Code -> Surface(
            color = palette.card,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language?.takeIf { it.isNotBlank() } ?: "code",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CodeCopyButton(
                        code = block.code,
                        language = block.language.orEmpty(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val codeColors = CodeColors(
                    keyword = palette.accent,
                    string = palette.success,
                    comment = palette.textSecondary,
                    number = palette.warning,
                )
                val highlighted = remember(block.code, block.language, palette.accent) {
                    highlightCode(block.code, block.language, codeColors)
                }
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        highlighted,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }

        is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = baseStyle, color = palette.textSecondary)
                    Text(inlineAnnotated(item, palette.accent), style = baseStyle)
                }
            }
        }

        is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${block.start + index}.", style = baseStyle, color = palette.textSecondary)
                    Text(inlineAnnotated(item, palette.accent), style = baseStyle)
                }
            }
        }

        is MdBlock.Quote -> Surface(
            color = palette.card,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                inlineAnnotated(block.text, palette.accent),
                modifier = Modifier.padding(12.dp),
                style = baseStyle,
                color = palette.textSecondary,
            )
        }

        is MdBlock.Table -> TableView(block, baseStyle)
    }
}

@Composable
private fun TableView(table: MdBlock.Table, baseStyle: androidx.compose.ui.text.TextStyle) {
    val palette = LocalHermexPalette.current
    val columnWidth = 160.dp
    Surface(color = palette.card, shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            Row {
                table.header.forEach { cell ->
                    Text(
                        inlineAnnotated(cell, palette.accent),
                        modifier = Modifier.width(columnWidth).padding(6.dp),
                        style = baseStyle,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            table.rows.forEach { row ->
                Row {
                    row.forEach { cell ->
                        Text(
                            inlineAnnotated(cell, palette.accent),
                            modifier = Modifier.width(columnWidth).padding(6.dp),
                            style = baseStyle,
                        )
                    }
                }
            }
        }
    }
}

// ── Inline formatting: **bold**, *italic*/_italic_, `code`, [text](url),
//    ⟦display:...⟧ / ⟦inline:...⟧ math tokens (from MathLite) ──

private fun inlineAnnotated(source: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                // Math tokens emitted by MathLite.replaceWithTags.
                c == '⟦' -> {
                    val close = source.indexOf('⟧', i)
                    if (close > 0) {
                        val tag = source.substring(i + 1, close)
                        val (body, isDisplay) = parseMathTag(tag)
                        if (body != null) {
                            // Math gets a distinct visual treatment: italic
                            // serif, slightly larger when displayed. The
                            // underlying body is plain Unicode text so it
                            // survives any further inline parsing.
                            val style = SpanStyle(
                                fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Serif,
                            )
                            withStyle(style) { append(body) }
                        } else {
                            append(source.substring(i, close + 1))
                        }
                        i = close + 1
                    } else { append(c); i++ }
                }
                c == '*' && i + 1 < source.length && source[i + 1] == '*' -> {
                    val end = source.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(source.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(c); i++ }
                }
                (c == '*' || c == '_') -> {
                    val end = source.indexOf(c, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(source.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                c == '`' -> {
                    val end = source.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                            append(source.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }
                c == '[' -> {
                    val close = source.indexOf(']', i)
                    val open = if (close > 0) source.getOrNull(close + 1) else null
                    if (close > 0 && open == '(') {
                        val paren = source.indexOf(')', close)
                        if (paren > 0) {
                            withStyle(SpanStyle(color = linkColor)) { append(source.substring(i + 1, close)) }
                            i = paren + 1
                        } else { append(c); i++ }
                    } else { append(c); i++ }
                }
                else -> { append(c); i++ }
            }
        }
    }

private fun parseMathTag(tag: String): Pair<String?, Boolean> {
    val isDisplay = tag.startsWith("display:")
    val isInline = tag.startsWith("inline:")
    if (!isDisplay && !isInline) return null to false
    return tag.substringAfter(':') to isDisplay
}

/**
 * Wave 9 — copy-to-clipboard button on every code block.
 *
 * One of the highest-friction moments in a chat shell is "the model
 * just spewed 30 lines of code; I want to paste it into my editor."
 * Without a button the user has to long-press the bubble, select all,
 * copy — which inside a horizontal scroll container is a chore.
 *
 * The button shows "Copy" then briefly flips to "Copied ✓" for
 * confirmation. The confirmation uses a single `var copied by
 * remember { mutableStateOf(false) }` keyed by `code` so a second
 * distinct code block doesn't share the flag.
 */
@Composable
private fun CodeCopyButton(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    val palette = LocalHermexPalette.current
    var copied by remember(code) { mutableStateOf(false) }
    androidx.compose.material3.TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(code))
            copied = true
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp,
            vertical = 2.dp,
        ),
    ) {
        if (copied) {
            Text(
                "Copied ✓",
                style = MaterialTheme.typography.labelSmall,
                color = palette.success,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.height(12.dp).width(12.dp),
                tint = palette.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Copy",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
        }
    }
    if (copied) {
        androidx.compose.runtime.LaunchedEffect(code) {
            kotlinx.coroutines.delay(1_500L)
            copied = false
        }
    }
}
