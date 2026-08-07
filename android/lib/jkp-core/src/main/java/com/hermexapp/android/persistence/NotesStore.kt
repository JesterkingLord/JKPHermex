package com.hermexapp.android.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Local-only note. Offline-first; never round-trips through the JKP server.
 *
 * Why a dedicated entity + DAO instead of piggy-backing on [CachedPayload]:
 * a note is mutable from a swipe-delete / inline-edit / re-color user
 * action, where the cache is a network-derived blob we re-fetch on miss.
 * Mixing the two would force every "edit note" call to invalidate the
 * network cache, and every cache hit to survive a "delete note" call.
 *
 * Storage is local SQLite via Room; backup policy is the system default
 * for app-private files (preserved across reinstall upgrades, lost on
 * factory-reset). If a future Wave wants cloud sync, [NoteStore] is the
 * interface to swap; nothing else in the app touches the entity directly.
 *
 * Fields:
 * - [id] is a UUID-style string generated at creation (not Room auto
 *   generateInt — server could later hand us a remote id and we want
 *   room to accept it).
 * - [title] is user-visible; may be blank, but the list shows
 *   "<body preview>" when blank so the row stays clickable.
 * - [body] is the full note text (multi-line). Stored as-is; rendering
 *   uses the chat markdown engine when present, plain Text when absent.
 * - [colorHex] is a 6-digit hex like "#FFE9A2"; the list paints a small
 *   swatch to the left of each row.
 * - [pinned] floats to the top of the list, ordering below by
 *   [updatedAtMillis].
 * - [updatedAtMillis] is an epoch-ms; sort key for everything except
 *   pinned. UI renders "3 minutes ago" / "Yesterday" / "Jun 4" from this.
 * - [createdAtMillis] is fixed at insert; rendered only when a note has
 *   no edits ever.
 */
@Entity(tableName = "local_notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    val pinned: Boolean,
    @ColumnInfo(name = "status") val status: String = NoteStatus.IDEA,
    /**
     * Comma-separated labels, same storage shape as `local_prompts.tags` so the
     * two features stay consistent. Empty string means unlabelled; a row from
     * before v4 gets that from the column default.
     *
     * Parse with [NoteLabels.parse] rather than splitting by hand — it drops
     * blanks and duplicates that would otherwise render as empty chips.
     */
    @ColumnInfo(name = "labels") val labels: String = "",
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

/**
 * Wave 9 (2026-07-28) — note lifecycle stage.
 *
 *   * IDEA  – free-form scratch. The user is still collecting thoughts.
 *   * PLAN  – the user thinks this is a recipe they might run someday,
 *             but it isn't ready to be implemented yet.
 *   * ACTION – the user has marked this as something to *do*, not just
 *             to store. The "Implement with AI" button becomes live in
 *             the editor; the user's chat composer can be pre-filled
 *             with the note's body via the NotesScreen "🤖 Implement"
 *             action.
 *
 * Persisted as a string for forward compatibility (new statuses can be
 * added without a schema bump). UI surfaces a chip with the matching
 * glyph and a one-tap switcher for the next two statuses; long-press
 * lets the user pick any value.
 */
object NoteStatus {
    const val IDEA = "idea"
    const val PLAN = "plan"
    const val ACTION = "action"

    val ALL: List<String> = listOf(IDEA, PLAN, ACTION)

    /** Short glyph + label combo used by the status chip in the editor. */
    data class Display(val glyph: String, val label: String)
    fun displayFor(status: String): Display = when (status) {
        ACTION -> Display("⚡", "Action")
        PLAN -> Display("🧭", "Plan")
        else -> Display("💡", "Idea")
    }
}

@Dao
interface NotesDao {
    /** Live, query-side Flow so the UI stays in sync with on-disk edits. */
    @Query("SELECT * FROM local_notes ORDER BY pinned DESC, updated_at_millis DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM local_notes WHERE id = :id LIMIT 1")
    suspend fun get(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE local_notes SET pinned = :pinned, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAtMillis: Long)

    /** Wave 9: stage marker. Defaults preserve the status column if absent. */
    @Query("UPDATE local_notes SET status = :status, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAtMillis: Long)

    @Query("DELETE FROM local_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM local_notes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM local_notes")
    suspend fun count(): Int
}

/**
 * The note-storage seam. Interface lives here so unit tests can run an
 * [InMemoryNoteStore]; production [RoomNoteStore] runs against the
 * existing [HermexDatabase].
 *
 * API mirrors a small "local notes table" — read-all (live), read-one,
 * create/update, set-pinned, delete. Each operation is suspending
 * because Room requires off-main-thread calls.
 */
interface NoteStore {
    fun observeAll(): Flow<List<NoteEntity>>
    suspend fun get(id: String): NoteEntity?
    suspend fun upsert(note: NoteEntity)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setStatus(id: String, status: String)
    suspend fun delete(id: String)
    suspend fun count(): Int
}

class RoomNoteStore(private val dao: NotesDao) : NoteStore {
    override fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()
    override suspend fun get(id: String): NoteEntity? = dao.get(id)
    override suspend fun upsert(note: NoteEntity) {
        // Last-write-wins on updatedAt: if the note already exists with a
        // newer updated_at, KEEP the newer value. This guards against the
        // race where two edits land in quick succession and the older one
        // wins by happenstance.
        val existing = dao.get(note.id)
        if (existing == null || note.updatedAtMillis >= existing.updatedAtMillis) {
            dao.upsert(note)
        }
    }
    override suspend fun setPinned(id: String, pinned: Boolean) =
        dao.setPinned(id, pinned, System.currentTimeMillis())
    override suspend fun setStatus(id: String, status: String) =
        dao.setStatus(id, status, System.currentTimeMillis())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun count(): Int = dao.count()
}

/**
 * Test-time fake. Backed by a plain LinkedHashMap keyed by id; observeAll
 * pushes every mutation through a MutableStateFlow so Compose UI tests
 * see updates synchronously.
 */
class InMemoryNoteStore(
    private val clock: () -> Long = { 0L },
) : NoteStore {
    private val map = LinkedHashMap<String, NoteEntity>()
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<NoteEntity>>(emptyList())

    // observeAll returns the live sorted view backed by [state]. Every
    // mutation calls [publish], which keeps state.value in sync, so any
    // caller collecting from this Flow sees the current snapshot and any
    // subsequent change.
    override fun observeAll(): Flow<List<NoteEntity>> = state
    override suspend fun get(id: String): NoteEntity? = map[id]
    override suspend fun upsert(note: NoteEntity) {
        val existing = map[note.id]
        if (existing == null || note.updatedAtMillis >= existing.updatedAtMillis) {
            map[note.id] = note
            publish()
        }
    }
    override suspend fun setPinned(id: String, pinned: Boolean) {
        val cur = map[id] ?: return
        map[id] = cur.copy(pinned = pinned, updatedAtMillis = clock())
        publish()
    }
    override suspend fun setStatus(id: String, status: String) {
        val cur = map[id] ?: return
        map[id] = cur.copy(status = status, updatedAtMillis = clock())
        publish()
    }
    override suspend fun delete(id: String) {
        map.remove(id)
        publish()
    }
    override suspend fun count(): Int = map.size
    private fun publish() {
        val sorted = map.values.sortedWith(
            compareByDescending<NoteEntity> { it.pinned }.thenByDescending { it.updatedAtMillis }
        )
        state.value = sorted
    }
}

/**
 * Note labels, stored as one comma-separated string.
 *
 * Kept as a string rather than a related table because labels are a display
 * grouping, not an entity — a join table would cost a migration and a second
 * DAO to answer a question a `LIKE` already answers.
 */
object NoteLabels {
    /**
     * Splits stored labels for display: trimmed, blanks dropped, duplicates
     * removed case-insensitively, original order kept.
     *
     * Blank entries come from ordinary typing ("work,,ideas", a trailing
     * comma) and would otherwise render as empty chips that cannot be tapped.
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** Joins labels back to storage form, applying the same cleaning. */
    fun join(labels: List<String>): String = parse(labels.joinToString(",")).joinToString(",")

    /** True when [raw] carries [label], compared case-insensitively. */
    fun contains(raw: String?, label: String): Boolean =
        parse(raw).any { it.equals(label.trim(), ignoreCase = true) }
}
