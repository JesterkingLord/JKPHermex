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
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

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
