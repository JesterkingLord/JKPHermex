package com.hermexapp.android.config

import android.content.Context
import com.hermexapp.android.model.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Header Logo Color presets, mirroring the iOS `HeaderLogoColor.presets` and
 *  extending with project-flavoured options (EFER/EFURC/EFEMM + JKP Void).
 *
 *  New accents are added at the bottom so existing user choices (saved by
 *  enum `name`, not by ordinal) don't silently change for anyone upgrading
 *  from v0.1.0. The picker UI wraps via [FlowRow] so 16 circles fit
 *  cleanly on phone screens.
 *
 *  Add a preset in three steps:
 *   1. New enum entry below (displayName, hex).
 *   2. (optional) update light-mode contrast in `HermexTheme.kt` if the
 *      hue doesn't survive the darken-for-light-mode pass.
 *   3. Bump `versionCode` in `app/build.gradle.kts` so the picker rebuilds.
 */
enum class AccentPreset(val displayName: String, val hex: String) {
    // Original 6 (v0.1.0 baseline — do not reorder).
    GOLD("Gold", "#FFD700"),
    BLUE("Blue", "#5B7CFF"),
    PURPLE("Purple", "#AF52DE"),
    RED("Red", "#FF3B30"),
    GREEN("Green", "#34C759"),
    WHITE("White", "#FFFFFF"),

    // New in v0.2.0 — additional brand-tied and JKP-flavoured options.
    AMBER("Amber", "#FFB300"),       // warm gold alternative
    CYAN("Cyan", "#00D4FF"),          // bright sky
    MAGENTA("Magenta", "#FF2D92"),    // hot pink
    ORANGE("Orange", "#FF6B1A"),      // sunset
    TEAL("Teal", "#1ABC9C"),          // cool teal
    INDIGO("Indigo", "#5856D6"),      // deep blue-purple
    PINK("Pink", "#FF8AB9"),          // pastel pink
    EFER_CRYPT_RED("EFER Crypt", "#8B0000"),  // EFER 3D RPG brand blood-red
    EFURC_CYAN("EFURC Cyan", "#00C7B7"),      // EFURC TCG brand cyan
    EFEMM_PURPLE("EFEMM Purple", "#9B59FF"),  // EFEMM Match-3 brand purple
    JKP_VOID("JKP Void", "#1A1A1A"),          // subtle dark accent (gray-on-gold works well)
    ;

    companion object {
        fun fromHex(hex: String?): AccentPreset = entries.firstOrNull { it.hex == hex } ?: GOLD
    }
}

/**
 * Non-secret app preferences (theme, accent color, chat display toggles).
 * Plain SharedPreferences — secrets live in the Keystore-backed SecretStore,
 * never here. Each pref is a StateFlow so the UI reacts immediately.
 */
class AppPrefs(private val store: KeyValueStore) {

    constructor(context: Context) : this(KeyValueStore.forPrefs(context, "hermex_prefs"))

    private val _theme = MutableStateFlow(
        runCatching { ThemeChoice.valueOf(store.getString(KEY_THEME) ?: "") }
            .getOrDefault(ThemeChoice.SYSTEM),
    )
    val theme: StateFlow<ThemeChoice> = _theme

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        store.putString(KEY_THEME, choice.name)
    }

    /** Header Logo Color — the brand accent (iOS #261). */
    private val _accent = MutableStateFlow(AccentPreset.fromHex(store.getString(KEY_ACCENT)))
    val accent: StateFlow<AccentPreset> = _accent

    fun setAccent(preset: AccentPreset) {
        _accent.value = preset
        store.putString(KEY_ACCENT, preset.hex)
    }

    /** "Expand Thinking by default" (iOS chat display setting). */
    private val _expandThinking = MutableStateFlow(store.getBoolean(KEY_EXPAND_THINKING, false))
    val expandThinking: StateFlow<Boolean> = _expandThinking

    fun setExpandThinking(value: Boolean) {
        _expandThinking.value = value
        store.putBoolean(KEY_EXPAND_THINKING, value)
    }

    /** "Expand Tool Calls by default". */
    private val _expandTools = MutableStateFlow(store.getBoolean(KEY_EXPAND_TOOLS, false))
    val expandTools: StateFlow<Boolean> = _expandTools

    fun setExpandTools(value: Boolean) {
        _expandTools.value = value
        store.putBoolean(KEY_EXPAND_TOOLS, value)
    }

    /** Response-completion notifications master switch (default on). */
    private val _notificationsEnabled = MutableStateFlow(store.getBoolean(KEY_NOTIFICATIONS, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled

    fun setNotificationsEnabled(value: Boolean) {
        _notificationsEnabled.value = value
        store.putBoolean(KEY_NOTIFICATIONS, value)
    }

    /**
     * Per-app reasoning-effort preference (mirrors the iOS
     * `selectedReasoningEffort` + `AppStorage.reasoningEffort`).
     *
     * The value is sent on every `/api/chat/start` body so the user doesn't
     * have to set it per-message; it's also persisted to the server via
     * `POST /api/reasoning` once the gateway round-trips it back. Storing
     * the literal "auto" string would 400 the server, so we encode
     * [ReasoningEffort.AUTO] as the empty string and translate at read time.
     */
    private val _reasoningEffort = MutableStateFlow(decodeReasoningEffort(store.getString(KEY_REASONING_EFFORT)))
    val reasoningEffort: StateFlow<ReasoningEffort> = _reasoningEffort

    fun setReasoningEffort(value: ReasoningEffort) {
        _reasoningEffort.value = value
        store.putString(KEY_REASONING_EFFORT, encodeReasoningEffort(value))
    }

    /**
     * Show-vs-hide the model's reasoning blocks in the timeline (iOS
     * `showReasoning`). Default true because hiding would silently drop
     * reasoning content the user is paying tokens for.
     */
    private val _showReasoning = MutableStateFlow(store.getBoolean(KEY_SHOW_REASONING, true))
    val showReasoning: StateFlow<Boolean> = _showReasoning

    fun setShowReasoning(value: Boolean) {
        _showReasoning.value = value
        store.putBoolean(KEY_SHOW_REASONING, value)
    }

    private fun decodeReasoningEffort(raw: String?): ReasoningEffort =
        ReasoningEffort.fromServer(raw)

    private fun encodeReasoningEffort(value: ReasoningEffort): String =
        value.wireValue ?: ""   // AUTO → "" (we never send "auto" to the server)

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ACCENT = "accent_hex"
        const val KEY_EXPAND_THINKING = "expand_thinking"
        const val KEY_EXPAND_TOOLS = "expand_tools"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_REASONING_EFFORT = "reasoning_effort"
        const val KEY_SHOW_REASONING = "show_reasoning"
    }
}
