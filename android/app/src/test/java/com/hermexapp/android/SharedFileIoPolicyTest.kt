package com.hermexapp.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the share-intent path against ContentResolver work on the UI thread. */
class SharedFileIoPolicyTest {
    @Test
    fun `shared file bytes and display name are both resolved on the IO dispatcher`() {
        val source = resolveMainActivitySource().readText()
        val sharedFileBlock = Regex(
            "withContext\\(Dispatchers\\.IO\\) \\{[\\s\\S]*?openInputStream\\(uri\\)[\\s\\S]*?" +
                "resolveDisplayName\\(context, uri\\)[\\s\\S]*?\\n\\s*\\}",
        )

        assertTrue(
            "Shared-file bytes and metadata must be read inside the same Dispatchers.IO block.",
            sharedFileBlock.containsMatchIn(source),
        )
        assertFalse(
            "Do not resolve shared-file metadata again after returning to the UI dispatcher.",
            Regex("\\n\\s*val name = resolveDisplayName\\(context, uri\\)").containsMatchIn(source),
        )
    }

    private fun resolveMainActivitySource(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val direct = File(directory, "src/main/java/com/hermexapp/android/MainActivity.kt")
            if (direct.isFile) return direct
            val fromRepo = File(directory, "android/app/src/main/java/com/hermexapp/android/MainActivity.kt")
            if (fromRepo.isFile) return fromRepo
            directory = directory.parentFile
        }
        error("Could not locate MainActivity.kt from ${System.getProperty("user.dir")}")
    }
}
