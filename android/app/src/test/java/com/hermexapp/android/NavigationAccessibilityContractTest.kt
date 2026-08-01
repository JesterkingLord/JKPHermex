package com.hermexapp.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards custom navigation rows that do not get Material's touch expansion. */
class NavigationAccessibilityContractTest {
    @Test
    fun `drawer and session menu rows keep 48dp targets without duplicate icon labels`() {
        val drawer = source(
            "android/lib/jkp-core/src/main/java/com/hermexapp/android/ui/DrawerPane.kt",
        ).readText().substringAfter("private fun DrawerItem(")
        val menu = source(
            "android/lib/jkp-sessions/src/main/java/com/hermexapp/android/features/sessionlist/SessionListScreen.kt",
        ).readText().substringAfter("private fun MenuRow(").substringBefore("private fun SessionActionsDialog(")

        assertTrue(drawer.contains(".heightIn(min = 48.dp)"))
        assertTrue(drawer.contains("contentDescription = null"))
        assertTrue(menu.contains(".heightIn(min = 48.dp)"))
    }

    private fun source(relativePath: String): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not locate $relativePath from $userDirectory")
    }
}
