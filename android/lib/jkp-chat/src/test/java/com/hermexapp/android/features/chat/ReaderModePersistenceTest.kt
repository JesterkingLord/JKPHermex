package com.hermexapp.android.features.chat

import com.hermexapp.android.config.AppPrefs
import com.hermexapp.android.config.InMemoryKeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 3 — full-screen reader mode persistence ([AppPrefs.readerMode]).
 *
 * ChatScreen drives its composer visibility from this preference, so the
 * operator's hide/show choice must survive app restarts. These tests pin the
 * three contracts the screen relies on:
 *  1. the default is off (composer visible);
 *  2. [AppPrefs.setReaderMode] flips the exposed StateFlow immediately;
 *  3. the value round-trips through the store — a fresh [AppPrefs] over the
 *     same [InMemoryKeyValueStore] observes the persisted choice.
 */
class ReaderModePersistenceTest {

    @Test
    fun `reader mode defaults to off`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        assertFalse(prefs.readerMode.value)
    }

    @Test
    fun `setReaderMode flips the state flow`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        prefs.setReaderMode(true)
        assertTrue(prefs.readerMode.value)
        prefs.setReaderMode(false)
        assertFalse(prefs.readerMode.value)
    }

    @Test
    fun `a fresh install opens with the composer visible`() {
        // The bug this pins: ChatScreen seeded composerVisible from the
        // preference directly, so the default (reader mode off) HID the
        // composer. Opening any chat on a new install left no way to type
        // until the operator found the floating show-composer button.
        val prefs = AppPrefs(InMemoryKeyValueStore())
        assertTrue(composerVisibleFor(prefs.readerMode.value))
    }

    @Test
    fun `reader mode on means the composer is hidden`() {
        val prefs = AppPrefs(InMemoryKeyValueStore())
        prefs.setReaderMode(true)
        assertFalse(composerVisibleFor(prefs.readerMode.value))

        prefs.setReaderMode(false)
        assertTrue(composerVisibleFor(prefs.readerMode.value))
    }

    @Test
    fun `with no preferences store attached the composer still shows`() {
        // Previews and tests construct ChatViewModel without AppPrefs; a null
        // preference must never mean "hide the composer".
        assertTrue(composerVisibleFor(null))
    }

    @Test
    fun `reader mode persists across AppPrefs instances on the same store`() {
        val store = InMemoryKeyValueStore()
        AppPrefs(store).setReaderMode(true)

        // A second AppPrefs over the same store simulates an app restart:
        // the state flow must hydrate from the persisted boolean.
        val reloaded = AppPrefs(store)
        assertTrue(reloaded.readerMode.value)
    }
}
