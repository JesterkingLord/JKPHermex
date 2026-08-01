package com.hermexapp.android.features.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static UI contracts for interactions that JVM tests cannot render. */
class SettingsInteractionContractTest {
    @Test
    fun `toggle rows expose one full-width switch target with a 48dp minimum`() {
        val source = settingsSource().readText()
        val toggleRow = source.substringAfter("private fun ToggleRow(").substringBefore("private fun InfoRow(")

        assertTrue(toggleRow.contains(".heightIn(min = 48.dp)"))
        assertTrue(toggleRow.contains(".toggleable("))
        assertTrue(toggleRow.contains("role = Role.Switch"))
        assertTrue(toggleRow.contains("onCheckedChange = null"))
    }

    @Test
    fun `new custom header values are concealed and can be deliberately revealed`() {
        val source = settingsSource().readText()
        val dialog = source.substringAfter("private fun AddHeaderDialog(").substringBefore("private fun ToggleRow(")

        assertTrue(dialog.contains("PasswordVisualTransformation()"))
        assertTrue(dialog.contains("VisualTransformation.None"))
        assertTrue(dialog.contains("KeyboardType.Password"))
        assertTrue(dialog.contains("if (revealValue) \"Hide value\" else \"Show value\""))
    }

    private fun settingsSource(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            val direct = File(
                current,
                "src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt",
            )
            if (direct.isFile) return direct
            val fromRepo = File(
                current,
                "android/lib/jkp-settings/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt",
            )
            if (fromRepo.isFile) return fromRepo
            directory = current.parentFile
        }
        error("Could not locate SettingsScreen.kt from ${System.getProperty("user.dir")}")
    }
}
