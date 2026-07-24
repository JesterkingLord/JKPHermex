package com.hermexapp.android.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Offline cache seam (Android port plan phase 3). Values are the raw response
 * JSON keyed by host + resource, so the cache never has its own schema to
 * migrate when upstream shapes change — re-decoding stays as tolerant as the
 * network path. Production uses Room ([RoomCacheStore]); tests use the
 * in-memory fake.
 */
interface CacheStore {
    suspend fun save(key: String, json: String)
    suspend fun load(key: String): String?
    suspend fun delete(key: String)

    /**
     * Bound the on-disk cache: drop anything older than [maxAgeMillis] and keep
     * only the [keepTranscripts] most-recent per-session transcripts. Called
     * once at startup; a no-op for the in-memory fake. Faithful to the iOS
     * ports' cache-retention sweep.
     */
    suspend fun prune(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS, keepTranscripts: Int = DEFAULT_KEEP_TRANSCRIPTS) {}

    companion object {
        fun sessionsKey(host: String) = "sessions::$host"
        fun sessionKey(host: String, sessionId: String) = "session::$host::$sessionId"

        const val DEFAULT_KEEP_TRANSCRIPTS = 50
        const val DEFAULT_MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1000 // 90 days
    }
}

/**
 * The retention policy shared by every [CacheStore]. Pure and unit-tested; the
 * Room [CachedPayloadDao] queries ([CachedPayloadDao.deleteOlderThan] +
 * [CachedPayloadDao.trimTranscripts]) mirror these exact rules in SQL, and
 * [InMemoryCacheStore.prune] calls it directly.
 */
object CacheRetention {
    /** Keys to evict: anything older than [maxAgeMillis], plus transcripts beyond
     *  the [keepTranscripts] most-recently fetched. [entries] is (key, fetchedAtMillis). */
    fun victims(
        entries: List<Pair<String, Long>>,
        nowMillis: Long,
        maxAgeMillis: Long,
        keepTranscripts: Int,
    ): List<String> {
        val cutoff = nowMillis - maxAgeMillis
        val tooOld = entries.filter { it.second < cutoff }.map { it.first }
        val overCap = entries
            .filter { it.first.startsWith("session::") }
            .sortedByDescending { it.second }
            .drop(keepTranscripts)
            .map { it.first }
        return (tooOld + overCap).distinct()
    }
}

class InMemoryCacheStore(private val clock: () -> Long = { 0L }) : CacheStore {
    // key -> (json, fetchedAtMillis), insertion-ordered.
    private val values = LinkedHashMap<String, Pair<String, Long>>()
    override suspend fun save(key: String, json: String) { values[key] = json to clock() }
    override suspend fun load(key: String): String? = values[key]?.first
    override suspend fun delete(key: String) { values.remove(key) }
    override suspend fun prune(maxAgeMillis: Long, keepTranscripts: Int) {
        val victims = CacheRetention.victims(
            values.map { it.key to it.value.second }, clock(), maxAgeMillis, keepTranscripts,
        )
        victims.forEach { values.remove(it) }
    }
}

@Entity(tableName = "cached_payloads")
data class CachedPayload(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAtMillis: Long,
)

@Dao
interface CachedPayloadDao {
    @Upsert
    suspend fun upsert(payload: CachedPayload)

    @Query("SELECT * FROM cached_payloads WHERE `key` = :key")
    suspend fun get(key: String): CachedPayload?

    @Query("DELETE FROM cached_payloads WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM cached_payloads WHERE fetchedAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Drop transcript blobs beyond the [keep] most-recently fetched. */
    @Query(
        "DELETE FROM cached_payloads WHERE `key` LIKE 'session::%' AND `key` NOT IN " +
            "(SELECT `key` FROM cached_payloads WHERE `key` LIKE 'session::%' " +
            "ORDER BY fetchedAtMillis DESC LIMIT :keep)",
    )
    suspend fun trimTranscripts(keep: Int)
}

/**
 * Local notes + prompts table is registered here so KSP generates the
 * matching DAO getters AND we can extend the database with more entities
 * in future waves without touching call-sites.
 *
 * Migration policy: `fallbackToDestructiveMigration` is fine BECAUSE:
 *  - The cache ([CachedPayload]) is disposable by design (re-fetch on
 *    miss is cheaper than a sync-engine).
 *  - [NoteEntity] / [PromptEntity] are user-owned content; destructive
 *    migrations on a user-owned table would silently delete work.
 *
 * Therefore: cache-table v1 → v2 stays destructive, but notes-table
 * v1 creation in this migration adds the table without dropping any
 * data. If we ever need a v3 (renames, splits), write a real
 * Migration(1,2) object before bumping @Database.version.
 *
 * Wave 9 (2026-07-28): v2 → v3 adds the `status` column to `local_notes`.
 * Notes carry an IDEA / PLAN / ACTION marker (defaults to IDEA for any
 * row from an older build, since SQLite will fill with the column
 * default). With `fallbackToDestructiveMigration` we don't need a
 * Migration object for now — if a future release wants to preserve
 * user notes across upgrades, add a Migration(2,3) object before
 * bumping @Database.version.
 */
@Database(
    entities = [CachedPayload::class, NoteEntity::class, PromptEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HermexDatabase : RoomDatabase() {
    abstract fun cachedPayloadDao(): CachedPayloadDao
    abstract fun notesDao(): NotesDao
    abstract fun promptsDao(): PromptsDao

    companion object {
        fun build(context: Context): HermexDatabase =
            Room.databaseBuilder(context, HermexDatabase::class.java, "hermex.db")
                // The cache is disposable by design (raw JSON blobs): on any
                // schema bump, dropping it just means one extra network fetch.
                .fallbackToDestructiveMigration()
                .build()
    }
}

class RoomCacheStore(private val dao: CachedPayloadDao) : CacheStore {
    override suspend fun save(key: String, json: String) {
        dao.upsert(CachedPayload(key, json, System.currentTimeMillis()))
    }

    override suspend fun load(key: String): String? = dao.get(key)?.json
    override suspend fun delete(key: String) = dao.delete(key)

    override suspend fun prune(maxAgeMillis: Long, keepTranscripts: Int) {
        dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMillis)
        dao.trimTranscripts(keepTranscripts)
    }
}
