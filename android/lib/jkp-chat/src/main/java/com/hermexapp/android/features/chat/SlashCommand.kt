package com.hermexapp.android.features.chat

/**
 * Operator-routed slash commands that the chat composer intercepts before
 * sending. Server-routed commands (`/branch`, `/compact`, etc.) are NOT
 * listed here — they pass straight to `/api/chat/start` like any other
 * user message and the Hermes server handles them.
 *
 * Mirrors iOS `SlashCommand.swift` (ios/HermesMobile/Features/Chat/
 * SlashCommand.swift:57-58 for `case queue` / `case steer`,
 * SlashCommandCatalog.swift:117/124 for the catalog). Keep the names
 * stable so server-side suggestions and tooling line up.
 */
internal enum class SlashCommand {
    STEER,
    QUEUE,
    INTERRUPT,
    RENAME,
    STATUS,
    HELP;

    companion object {
        /**
         * Parse a composer draft into `(command, argument)`. Returns `null`
         * when the draft is not a slash command (no leading `/`, or unknown
         * verb). Pure function — no I/O, no state — so it's trivial to test.
         */
        fun parse(draft: String): Pair<SlashCommand, String>? {
            val trimmed = draft.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
            val body = trimmed.substring(1)
            val spaceIdx = body.indexOf(' ')
            val verb = if (spaceIdx < 0) body else body.substring(0, spaceIdx)
            val args = if (spaceIdx < 0) "" else body.substring(spaceIdx + 1).trim()
            val match = when (verb.lowercase()) {
                "steer" -> STEER
                "queue", "q" -> QUEUE
                "interrupt", "stop", "cancel" -> INTERRUPT
                "title", "rename" -> RENAME
                "status", "stat" -> STATUS
                "help", "?" -> HELP
                else -> return null
            }
            return match to args
        }
    }
}

/**
 * Local-only catalog of slash commands rendered in the composer picker.
 * Server-supplied `AgentCommand` entries (via `/api/commands`) are filtered
 * to skip `cliOnly`/`gatewayOnly` flags already in [ComposerConfig.slashSuggestions];
 * the static list here is what the picker shows BEFORE `/api/commands`
 * round-trips, so it must be self-contained.
 */
internal data class SlashCommandDescriptor(
    val command: SlashCommand,
    val verb: String,
    val description: String,
    val argHint: String,
)

internal val LOCAL_SLASH_COMMANDS: List<SlashCommandDescriptor> = listOf(
    SlashCommandDescriptor(
        SlashCommand.STEER, "steer",
        "Inject a hint at the next tool boundary without stopping the current run.",
        "<message>",
    ),
    SlashCommandDescriptor(
        SlashCommand.QUEUE, "queue",
        "Buffer a follow-up message that auto-sends when the current run finishes.",
        "<message>",
    ),
    SlashCommandDescriptor(
        SlashCommand.INTERRUPT, "interrupt",
        "Cancel the current run and send this message as the next turn.",
        "<message>",
    ),
    SlashCommandDescriptor(
        SlashCommand.RENAME, "title",
        "Rename this session (alias: /rename).",
        "<new title>",
    ),
    SlashCommandDescriptor(
        SlashCommand.STATUS, "status",
        "Show a one-line summary of the current session.",
        "",
    ),
    SlashCommandDescriptor(
        SlashCommand.HELP, "help",
        "List every slash command available in this composer.",
        "",
    ),
)

/**
 * Filter the static catalog to the verbs that match a partial token, so
 * the suggestion chip row populates as the operator types. Same shape as
 * iOS's suggestion filter in `ChatComposerSelectorSheets.swift`.
 */
internal fun filterSlashCommands(draft: String): List<SlashCommandDescriptor> {
    val trimmed = draft.trim()
    if (!trimmed.startsWith("/")) return emptyList()
    // Once the operator pastes a space, they've committed — no more chips.
    if (trimmed.contains(' ')) return emptyList()
    val token = trimmed.substring(1)
    if (token.isEmpty()) return LOCAL_SLASH_COMMANDS
    return LOCAL_SLASH_COMMANDS.filter { it.verb.startsWith(token, ignoreCase = true) }
}
