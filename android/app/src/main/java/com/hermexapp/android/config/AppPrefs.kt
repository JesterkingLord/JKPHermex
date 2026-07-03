package com.hermexapp.android.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/**
 * Non-secret app preferences (theme, default panel days, …). Plain
 * SharedPreferences — secrets live in the Keystore-backed SecretStore, never here.
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("hermex_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeChoice.SYSTEM),
    )
    val theme: StateFlow<ThemeChoice> = _theme

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        prefs.edit().putString(KEY_THEME, choice.name).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
