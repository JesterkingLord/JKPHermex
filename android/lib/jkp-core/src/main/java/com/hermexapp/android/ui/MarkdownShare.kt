package com.hermexapp.android.ui

import android.content.Context
import android.content.Intent

/**
 * Wave 3 Slice 3.1 — share an assistant message (and optional surrounding
 * user context) as plain Markdown via the standard Android share sheet.
 *
 * The whole share surface is a thin wrapper around [Intent.ACTION_SEND] with
 * `text/plain` so chat apps, mail, notes, and DocumentUI all accept it. We
 * intentionally avoid `text/markdown` (unsupported by most share targets) and
 * `Html.fromHtml` (renders inconsistently across apps).
 *
 * `surroundingUserText` is the user prompt that *preceded* the assistant
 * reply — omitted silently when blank so the share is just the assistant's
 * answer.
 *
 * The function is pure (given a non-null [Context]): the Intent is
 * constructed and dispatched synchronously, and the function returns the
 * Intent that was used (mainly so JVM unit tests can verify it without
 * needing to stub Activity launch). Callers pass the result to
 * `startActivity(...)` themselves.
 */
fun shareAsMarkdown(
    context: Context,
    assistantText: String,
    surroundingUserText: String? = null,
    sessionTitle: String? = null,
    chooserTitle: String = "Share as Markdown",
): Intent {
    val body = markdownShareBody(assistantText, surroundingUserText, sessionTitle)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, sessionTitle?.takeIf { it.isNotBlank() } ?: "Hermex reply")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Pure builder used by both the [shareAsMarkdown] entry point and by the JVM
 * unit test suite. Extracted so the body-formatting logic can be exercised
 * without a Context.
 */
internal fun markdownShareBody(
    assistantText: String,
    surroundingUserText: String?,
    sessionTitle: String?,
): String = buildString {
    if (!sessionTitle.isNullOrBlank()) append("# ").append(sessionTitle).append('\n')
    if (!surroundingUserText.isNullOrBlank()) {
        append("> **You:** ").append(surroundingUserText.trim()).append("\n\n")
    }
    append(assistantText.trim()).append('\n')
}
