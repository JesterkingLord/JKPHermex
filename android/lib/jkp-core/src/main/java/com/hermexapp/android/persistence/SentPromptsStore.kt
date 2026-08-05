package com.hermexapp.android.persistence

import com.hermexapp.android.config.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One message the operator actually sent, remembered so it can be reused.
 *
 * @param body the message text exactly as sent.
 * @param lastSentAtMillis when it was last sent — the list is newest-first.
 * @param timesSent how often this exact text has been sent.
 */
@Serializable
data class SentPrompt(
    val body: String,
    val lastSentAtMillis: Long,
    val timesSent: Int = 1,
)

/**
 * A rolling history of the prompts the operator has sent.
 *
 * The prompt library ([PromptsStore]) only ever held prompts typed into it by
 * hand, so "the prompt I wrote last week that worked well" was gone unless the
 * operator had thought, at the time, to save it. This records sends as they
 * happen, and the prompts screen offers each one for promotion into the real
 * library.
 *
 * Deliberately backed by [KeyValueStore] rather than Room: the library's Room
 * schema is already versioned and migrated, and a rolling capped history does
 * not justify a schema change. It is a single JSON blob of at most [MAX_ENTRIES]
 * rows.
 *
 * Everything here stays on the device — the same place the drafts and the
 * library already live. Nothing is uploaded.
 */
class SentPromptsStore(
    private val store: KeyValueStore,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val _history = MutableStateFlow(load())

    /** Newest first. */
    val history: StateFlow<List<SentPrompt>> = _history

    /**
     * Records a sent message.
     *
     * Re-sending the same text does not add a duplicate row: it moves the
     * existing one to the front and counts it, so a prompt used ten times
     * reads as one useful prompt rather than ten lines of noise.
     *
     * Blank text is ignored. Slash commands are ignored too — `/status` is an
     * instruction to the app, not a prompt worth keeping.
     */
    fun record(body: String, atMillis: Long) {
        val text = body.trim()
        if (text.isEmpty() || text.startsWith("/")) return

        val existing = _history.value.firstOrNull { it.body == text }
        val entry = SentPrompt(
            body = text,
            lastSentAtMillis = atMillis,
            timesSent = (existing?.timesSent ?: 0) + 1,
        )
        val next = (listOf(entry) + _history.value.filterNot { it.body == text })
            .take(maxEntries)
        _history.value = next
        persist(next)
    }

    /** Drops one entry — for when the operator does not want it kept. */
    fun forget(body: String) {
        val next = _history.value.filterNot { it.body == body }
        _history.value = next
        persist(next)
    }

    fun clear() {
        _history.value = emptyList()
        persist(emptyList())
    }

    private fun persist(entries: List<SentPrompt>) {
        // History is a convenience, never the source of truth: a failed write
        // must not take down the send that triggered it.
        runCatching { store.putString(KEY, json.encodeToString(entries)) }
    }

    private fun load(): List<SentPrompt> {
        val raw = runCatching { store.getString(KEY) }.getOrNull() ?: return emptyList()
        // A payload written by an older build is dropped rather than crashing
        // the app on launch.
        return runCatching { json.decodeFromString<List<SentPrompt>>(raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        const val KEY = "sent_prompts_history_v1"

        /** Enough to cover recent work without turning into an archive. */
        const val MAX_ENTRIES = 50

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
