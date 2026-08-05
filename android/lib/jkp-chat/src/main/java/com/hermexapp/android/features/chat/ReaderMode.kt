package com.hermexapp.android.features.chat

/**
 * Maps the persisted reader-mode preference to composer visibility.
 *
 * `AppPrefs.readerMode` means "the transcript fills the screen and the composer
 * is hidden", so it is the INVERSE of composer visibility. ChatScreen briefly
 * used the preference directly, which meant the default (`readerMode = false`)
 * hid the composer on a fresh install: opening any chat gave you no way to type
 * until you found the floating show-composer button.
 *
 * The unit tests around the preference stayed green through that bug because
 * they only exercised `AppPrefs` and never the mapping. This function exists so
 * the mapping itself is a thing that can be asserted.
 *
 * @param readerMode the persisted preference, or null when no preferences
 *   store is attached (previews and tests) — in which case the composer shows.
 */
internal fun composerVisibleFor(readerMode: Boolean?): Boolean = readerMode != true
