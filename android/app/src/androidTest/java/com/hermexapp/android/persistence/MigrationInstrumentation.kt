package com.hermexapp.android.persistence

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.runBlocking

class MigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val testContext = targetContext
        val databaseName = "hermex-migration-v2-v4-test.db"
        val databaseFile = testContext.getDatabasePath(databaseName)
        var failure: Throwable? = null

        try {
            check(databaseName != "hermex.db") {
                "Refusing to use the production database name"
            }
            check(
                databaseFile.parentFile?.canonicalFile ==
                    testContext.getDatabasePath("sentinel").parentFile?.canonicalFile &&
                    databaseFile.name == databaseName,
            ) {
                "Fixture path does not belong to ${testContext.packageName}"
            }

            testContext.deleteDatabase(databaseName)
            databaseFile.parentFile?.mkdirs()
            createVersionTwoFixture(databaseFile)
            verifyMigratedToLatest(testContext, databaseName)
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            cleanupFixture(testContext, databaseName, databaseFile)
                ?.let { cleanupFailure ->
                    failure = appendFailure(failure, cleanupFailure)
                }
        }

        val result = failure
        if (result == null) {
            finish(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString("stream", "PASS: Room v2-to-v4 preserved note, labels default and prompt")
                },
            )
        } else {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", result.stackTraceToString()) },
            )
        }
    }

    private fun createVersionTwoFixture(databaseFile: File) {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE cached_payloads (`key` TEXT NOT NULL, json TEXT NOT NULL, " +
                    "fetchedAtMillis INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
            sqlite.execSQL(
                "CREATE TABLE local_notes (id TEXT NOT NULL, title TEXT NOT NULL, " +
                    "body TEXT NOT NULL, color_hex TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                    "updated_at_millis INTEGER NOT NULL, created_at_millis INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "CREATE TABLE local_prompts (id TEXT NOT NULL, name TEXT NOT NULL, " +
                    "body TEXT NOT NULL, tags TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                    "usage_count INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL, " +
                    "created_at_millis INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "INSERT INTO local_notes VALUES " +
                    "('note-1','Keep me','Preserved body','#FFE9A2',1,200,100)",
            )
            sqlite.execSQL(
                "INSERT INTO local_prompts VALUES " +
                    "('prompt-1','Keep prompt','Prompt body','tag',0,7,201,101)",
            )
            sqlite.version = 2
        }
    }

    /**
     * Opens the v2 fixture with the current schema, which runs the whole
     * migration chain (2 → 3 → 4), and checks the user's rows survived it.
     *
     * The point is the content, not the schema: a migration that drops and
     * recreates the table would also "succeed" at reaching v4.
     */
    private fun verifyMigratedToLatest(context: Context, databaseName: String) {
        val room = HermexDatabase.build(context, databaseName)
        try {
            runBlocking {
                val note = room.notesDao().get("note-1")
                check(note?.title == "Keep me")
                check(note.body == "Preserved body")
                check(note.status == NoteStatus.IDEA)
                // v4: pre-existing notes become unlabelled rather than lost.
                check(note.labels == "") { "labels should default to empty, was '${note.labels}'" }

                val prompt = room.promptsDao().get("prompt-1")
                check(prompt?.name == "Keep prompt")
                check(prompt.body == "Prompt body")
                check(prompt.usageCount == 7)

                // The new column has to be writable, not merely present.
                room.notesDao().upsert(note.copy(labels = "work,ideas"))
                check(room.notesDao().get("note-1")?.labels == "work,ideas")
            }
        } finally {
            room.close()
        }
    }

    private fun cleanupFixture(
        context: Context,
        databaseName: String,
        databaseFile: File,
    ): Throwable? = runCatching {
        val cacheDirectory = context.cacheDir.canonicalFile
        val lockFile = File(context.cacheDir, "$databaseName.lck").canonicalFile
        check(lockFile.parentFile == cacheDirectory) {
            "Fixture lock escaped the app cache directory"
        }
        check(context.deleteDatabase(databaseName) || !databaseFile.exists()) {
            "Could not delete migration fixture database"
        }
        check(!lockFile.exists() || lockFile.delete()) {
            "Could not delete migration fixture lock"
        }
        val remainingDatabases = databaseFile.parentFile
            ?.listFiles { _, name -> name.startsWith(databaseName) }
            .orEmpty()
        check(remainingDatabases.isEmpty() && !lockFile.exists()) {
            "Migration fixture artifacts remain after cleanup"
        }
    }.exceptionOrNull()

    private fun appendFailure(existing: Throwable?, next: Throwable): Throwable {
        if (existing == null) return next
        existing.addSuppressed(next)
        return existing
    }
}
