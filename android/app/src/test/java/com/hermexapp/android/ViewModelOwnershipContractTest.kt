package com.hermexapp.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures every ViewModel manually created with remember is explicitly disposed. */
class ViewModelOwnershipContractTest {
    @Test
    fun `composition-owned view models cancel their scopes when their screen leaves`() {
        val source = mainActivitySource().readText()
        val helper = source.substringAfter("private fun DisposeViewModelOnExit(")
            .substringBefore("private fun resolveDisplayName(")

        assertTrue(helper.contains("viewModel.viewModelScope.cancel()"))
        listOf(
            "DisposeViewModelOnExit(sessionListViewModel)",
            "DisposeViewModelOnExit(chatViewModel, chatViewModel::teardown)",
            "DisposeViewModelOnExit(paletteVm)",
            "DisposeViewModelOnExit(workspaceViewModel)",
            "DisposeViewModelOnExit(panelsViewModel)",
            "DisposeViewModelOnExit(notesVm)",
            "DisposeViewModelOnExit(promptsVm)",
        ).forEach { expected ->
            assertTrue("Missing ownership cleanup: $expected", source.contains(expected))
        }
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
