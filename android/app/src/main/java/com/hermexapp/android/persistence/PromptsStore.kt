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
 * Local-only prompt. Offline-first; never round-trips through the JKP
 * server. Similar lifecycle to [NoteEntity] — see the file header on
 * `NotesStore.kt` for the rationale.
 *
 * Fields:
 * - [id] UUID-style; not Room auto-generate (server might hand us a
 *   remote id later).
 * - [name] is the human label shown in the library list; required
 *   non-blank (list rejects blank names on save).
 * - [body] is the prompt template. Supports {{handlebars}} syntax;
 *   [ChatViewModel] future expansion will substitute session-scoped
 *   values (server name, transcript path, etc.) before sending the
 *   prompt to the LLM.
 * - [tags] is a comma-separated string. Tag-driven filtering is
 *   Wave 8.7 polish; for now the list scans substring-regex against
 *   `name + body + tags` for its search box.
 * - [pinned] floats to top.
 * - [usageCount] increments when the chat composer inserts this
 *   prompt; the list sorts by usageCount desc as a tie-breaker so
 *   operators' most-used prompts surface first.
 * - [updatedAtMillis] / [createdAtMillis] same semantics as
 *   [NoteEntity].
 */
@Entity(tableName = "local_prompts")
data class PromptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
    val tags: String,
    val pinned: Boolean,
    @ColumnInfo(name = "usage_count") val usageCount: Int,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

@Dao
interface PromptsDao {
    /**
     * Order: pinned DESC → usage_count DESC → updated_at_millis DESC.
     * Operators almost always want "my most-recently-used prompts at the
     * top, falling back to most-recently-edited prompts beyond that".
     */
    @Query(
        "SELECT * FROM local_prompts " +
            "ORDER BY pinned DESC, usage_count DESC, updated_at_millis DESC"
    )
    fun observeAll(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM local_prompts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PromptEntity?

    @Query("SELECT * FROM local_prompts WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<PromptEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prompt: PromptEntity)

    @Update
    suspend fun update(prompt: PromptEntity)

    @Query("UPDATE local_prompts SET pinned = :pinned, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAtMillis: Long)

    @Query("UPDATE local_prompts SET usage_count = usage_count + 1, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun bumpUsage(id: String, updatedAtMillis: Long)

    @Query("DELETE FROM local_prompts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM local_prompts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM local_prompts")
    suspend fun count(): Int
}

/**
 * Prompt-storage seam. Same shape as [NoteStore]; interface + Room impl
 * + in-memory fake.
 */
interface PromptStore {
    fun observeAll(): Flow<List<PromptEntity>>
    fun observe(id: String): Flow<PromptEntity?>
    suspend fun get(id: String): PromptEntity?
    suspend fun upsert(prompt: PromptEntity)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun bumpUsage(id: String)
    suspend fun delete(id: String)
    suspend fun count(): Int
}

class RoomPromptStore(private val dao: PromptsDao) : PromptStore {
    override fun observeAll(): Flow<List<PromptEntity>> = dao.observeAll()
    override fun observe(id: String): Flow<PromptEntity?> = dao.observe(id)
    override suspend fun get(id: String): PromptEntity? = dao.get(id)
    override suspend fun upsert(prompt: PromptEntity) {
        // Same last-write-wins policy as RoomNoteStore — if the row already
        // exists with a newer updated_at, KEEP the newer value.
        val existing = dao.get(prompt.id)
        if (existing == null || prompt.updatedAtMillis >= existing.updatedAtMillis) {
            dao.upsert(prompt)
        }
    }
    override suspend fun setPinned(id: String, pinned: Boolean) =
        dao.setPinned(id, pinned, System.currentTimeMillis())
    override suspend fun bumpUsage(id: String) =
        dao.bumpUsage(id, System.currentTimeMillis())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun count(): Int = dao.count()
}

class InMemoryPromptStore(
    private val clock: () -> Long = { 0L },
) : PromptStore {
    private val map = LinkedHashMap<String, PromptEntity>()
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<PromptEntity>>(emptyList())
    override fun observeAll(): Flow<List<PromptEntity>> = state
    override fun observe(id: String): Flow<PromptEntity?> =
        kotlinx.coroutines.flow.MutableStateFlow(map[id])
    override suspend fun get(id: String): PromptEntity? = map[id]
    override suspend fun upsert(prompt: PromptEntity) {
        val existing = map[prompt.id]
        if (existing == null || prompt.updatedAtMillis >= existing.updatedAtMillis) {
            map[prompt.id] = prompt
            state.value = sortedAll()
        }
    }
    override suspend fun setPinned(id: String, pinned: Boolean) {
        val cur = map[id] ?: return
        map[id] = cur.copy(pinned = pinned, updatedAtMillis = clock())
        state.value = sortedAll()
    }
    override suspend fun bumpUsage(id: String) {
        val cur = map[id] ?: return
        map[id] = cur.copy(usageCount = cur.usageCount + 1, updatedAtMillis = clock())
        state.value = sortedAll()
    }
    override suspend fun delete(id: String) {
        map.remove(id)
        state.value = sortedAll()
    }
    override suspend fun count(): Int = map.size
    private fun sortedAll(): List<PromptEntity> = map.values.sortedWith(
        compareByDescending<PromptEntity> { it.pinned }
            .thenByDescending { it.usageCount }
            .thenByDescending { it.updatedAtMillis }
    )
}
