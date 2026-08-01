package com.hermexapp.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects retry-safe navigation from shares and the Notes implement action. */
class SharedDraftHandoffContractTest {
    private val source by lazy { mainActivitySource().readText() }

    @Test
    fun `pending share is consumed only after a session is created`() {
        val effect = source.substringAfter("LaunchedEffect(pendingShare)")
            .substringBefore("// A notification tap deep-links")

        assertTrue(effect.indexOf("createSessionNow()") < effect.indexOf("consume(content)"))
        assertFalse(effect.contains("consume() ?: return@LaunchedEffect"))
    }

    @Test
    fun `implement note uses only the shared draft handoff`() {
        val callback = source.substringAfter("onImplementNote = { note ->")
            .substringBefore("},\n            )")

        assertTrue(callback.contains("container.sharedDraftStore.offer(prompt)"))
        assertFalse(callback.contains("pendingNewChatFromWidget"))
        assertFalse(callback.contains("sharePrefill = prompt"))
    }

    private fun mainActivitySource(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            val direct = File(current, "src/main/java/com/hermexapp/android/MainActivity.kt")
            if (direct.isFile) return direct
            val fromRepo = File(current, "android/app/src/main/java/com/hermexapp/android/MainActivity.kt")
            if (fromRepo.isFile) return fromRepo
            directory = current.parentFile
        }
        error("Could not locate MainActivity.kt from $userDirectory")
    }
}
