package com.hermexapp.android.persistence

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import kotlinx.coroutines.runBlocking

class MigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val testContext = targetContext
        val databaseName = "hermex-migration-v2-v3-test.db"
        val databaseFile = testContext.getDatabasePath(databaseName)
        var room: HermexDatabase? = null

        try {
            check(databaseName != "hermex.db") {
                "Refusing to use the production database name"
            }
            check(databaseFile.absolutePath.contains(testContext.packageName) &&
                databaseFile.name == databaseName
            ) {
                "Fixture path does not belong to ${testContext.packageName}"
            }

            testContext.deleteDatabase(databaseName)
            databaseFile.parentFile?.mkdirs()
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

            room = HermexDatabase.build(testContext, databaseName)
            runBlocking {
                val note = room.notesDao().get("note-1")
                check(note?.title == "Keep me")
                check(note.body == "Preserved body")
                check(note.status == NoteStatus.IDEA)

                val prompt = room.promptsDao().get("prompt-1")
                check(prompt?.name == "Keep prompt")
                check(prompt.body == "Prompt body")
                check(prompt.usageCount == 7)
            }

            room.close()
            room = null
            testContext.deleteDatabase(databaseName)
            finish(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString("stream", "PASS: Room v2-to-v3 preserved note and prompt")
                },
            )
        } catch (throwable: Throwable) {
            room?.close()
            testContext.deleteDatabase(databaseName)
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", throwable.stackTraceToString()) },
            )
        }
    }
}
