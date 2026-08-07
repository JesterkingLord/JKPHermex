package com.hermexapp.android.persistence

import com.hermexapp.android.config.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One file this device uploaded.
 *
 * @param path the workspace path the server returned, which is what a file
 *   listing will later show — matching on the name alone would claim any file
 *   that happened to share it.
 * @param filename what it was called when sent, kept for display.
 * @param uploadedAtMillis when the upload succeeded.
 */
@Serializable
data class UploadRecord(
    val path: String,
    val filename: String,
    val uploadedAtMillis: Long,
)

/**
 * What this device uploaded, so the file library can say so.
 *
 * The library wants "things we created" against "things we uploaded", and the
 * host does not record that: `/api/list` reports a name, a type, a size and an
 * mtime, and nothing about who wrote the file or how it arrived. There is no
 * field to read and no endpoint that knows.
 *
 * So this is the honest half of the answer, and its limits are the reason the
 * UI must say **"Uploaded from this device"** rather than "Uploaded". A file
 * the operator dropped in from their PC, or that an agent wrote, is
 * indistinguishable here from any other file on disk — it will appear under
 * "Everything else", and that is correct rather than merely convenient. A
 * label claiming more than the data supports is the failure to avoid: it would
 * be wrong precisely when the operator relies on it.
 *
 * Backed by [KeyValueStore] rather than Room, like [SentPromptsStore]: the
 * notes schema is already versioned and migrated, and a capped append-only
 * list does not justify another migration.
 *
 * Local only. Nothing here is uploaded, and it records paths — never contents.
 */
class UploadLedger(
    private val store: KeyValueStore,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    private var records: List<UploadRecord> = load()

    /** Newest first. */
    fun all(): List<UploadRecord> = records

    /**
     * Records a successful upload. Call it only after the server confirms one:
     * a ledger that lists uploads that never landed is worse than no ledger,
     * because it labels a file the operator never sent.
     *
     * Re-uploading the same path updates its timestamp rather than adding a
     * second row — the library shows files, not events.
     */
    fun record(path: String, filename: String, atMillis: Long) {
        if (path.isBlank()) return
        val updated = buildList {
            add(UploadRecord(path, filename, atMillis))
            addAll(records.filterNot { it.path == path })
        }.take(maxEntries)
        records = updated
        persist(updated)
    }

    /** True when this device uploaded [path]. */
    fun wasUploadedFromThisDevice(path: String?): Boolean =
        path != null && records.any { it.path == path }

    /** Forgets everything. Used when signing out, so one device's history does not describe another's. */
    fun clear() {
        records = emptyList()
        persist(emptyList())
    }

    private fun load(): List<UploadRecord> {
        val raw = store.getString(KEY) ?: return emptyList()
        // A ledger that cannot be read is not worth crashing over: the cost of
        // losing it is a file showing under "Everything else", which is the
        // same thing that happens for every file uploaded from the PC anyway.
        return runCatching { json.decodeFromString<List<UploadRecord>>(raw) }.getOrDefault(emptyList())
    }

    private fun persist(value: List<UploadRecord>) {
        store.putString(KEY, json.encodeToString(value))
    }

    private companion object {
        const val KEY = "upload_ledger_v1"
        const val MAX_ENTRIES = 500
        val json = Json { ignoreUnknownKeys = true }
    }
}
