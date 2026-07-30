package com.hermexapp.android.features.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.HermexPickerSheet
import com.hermexapp.android.ui.PickerRow
import com.hermexapp.android.ui.PickerSection

/**
 * Wave 9 (2026-07-28) — ComposerTemplatesSheet.
 *
 * A picker sheet listing the **built-in prompt templates** shipped with
 * the app. The user taps one to drop the template's body into the
 * composer, then edits the placeholder slots (e.g. "function name") before
 * hitting send.
 *
 * The templates are *static*: they're hard-coded here, not user-editable.
 * User-authored long-form snippets live in the Prompts drawer screen
 * (and the long-press insert palette). This sheet only carries "starter"
 * phrasing — a few of the most common AI requests we expect a mobile
 * user to send.
 *
 * The picker sheet itself is also a ModalBottomSheet, so we don't wrap it
 * in another one — we just give the picker a meaningful title.
 *
 * @param onPick called when the user picks a template; the body is the
 *   template text the composer should adopt.
 * @param onDismiss called when the sheet is closed without picking.
 */
@Composable
fun ComposerTemplatesSheet(
    onPick: (body: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Templates",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Pick a starter. You can edit it before sending.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        HermexPickerSheet(
            title = "Quick starts",
            sections = listOf(
                PickerSection(
                    header = null,
                    rows = BuiltInComposerTemplates.map { (label, body) ->
                        PickerRow(
                            label = label,
                            value = body,
                            sublabel = body.lineSequence().firstOrNull().orEmpty().take(60),
                        )
                    },
                ),
            ),
            isSelected = { false },
            onPick = { body -> onPick(body) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * The built-in prompt templates shown by [ComposerTemplatesSheet].
 *
 * Keep this list ordered: most-likely-used first. New entries should
 * follow iOS parity + "user requested it recently" + avoids duplication
 * with built-in slash commands.
 *
 * Bodies use placeholders like `function name` rather than curly-brace
 * syntax so the user can ask the model to fill them in or fill them in
 * themselves.
 */
internal val BuiltInComposerTemplates: List<Pair<String, String>> = listOf(
    "Explain this code" to
        "Explain what the following code does, step by step, in plain language:\n\n```\n<paste code here>\n```",
    "Write tests" to
        "Write unit tests for the following code. Cover the happy path, common edge cases, and any error branches. Use the project's existing test framework.\n\n```\n<paste code here>\n```",
    "Refactor" to
        "Refactor the following code for readability. Preserve behaviour. Annotate any non-obvious naming choices.\n\n```\n<paste code here>\n```",
    "Find bugs" to
        "Review the following code for bugs, race conditions, security issues, and error-handling gaps. List each finding with file:line and a short fix recommendation.\n\n```\n<paste code here>\n```",
    "Plan" to
        "Plan the implementation of <feature>. Break the work into ordered, atomic steps. For each step list the files likely to change and any risks.",
    "Summarize" to
        "Summarize the following in <N> bullet points, lead with the most important conclusion:\n\n<paste text here>",
    "Add doc comment" to
        "Add a documentation comment to the following declaration. Match the project's existing docstring style. Do not change behaviour:\n\n```\n<declaration>\n```",
    "Commit message" to
        "Write a Conventional Commit message for the staged diff. Run `git diff --staged` first. Format: `<type>(<scope>): <subject>` then a short body.",
)
