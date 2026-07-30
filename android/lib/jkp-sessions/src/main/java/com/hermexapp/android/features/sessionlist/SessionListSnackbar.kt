package com.hermexapp.android.features.sessionlist

import androidx.compose.material3.SnackbarDuration

/**
 * Pure formatter that turns a [SessionListEvent] into the text the snackbar
 * will display. Keeping this in its own file (and pure / side-effect-free)
 * means the wording can be unit-tested with plain JUnit and a hand-built
 * event, without spinning up a Compose runtime.
 *
 * Material 3 `SnackbarHost` does the actual render. The action label, when
 * one is offered, lives next to the message separated by Material's
 * default; we don't need to include it in the body.
 */
object SessionListSnackbar {
    /**
     * Snackbar body text for the given event. UI callers should pass the
     * result to [SnackbarHostState.showSnackbar] along with
     * [SnackbarDuration.Short] (or `Long` for errors). Returns `null` when the
     * event should not surface a snackbar at all (used by the screen to skip
     * events that arrive while the user is mid-selection — the toolbar already
     * shows progress for those).
     */
    fun messageFor(event: SessionListEvent): String? = when (event) {
        is SessionListEvent.Deleted -> when (event.titles.size) {
            0 -> "Deleted session. This cannot be undone."
            1 -> "Deleted \"${event.titles.first()}\". This cannot be undone."
            else -> {
                val first = event.titles.first()
                "Deleted ${event.titles.size} sessions including \"$first\". This cannot be undone."
            }
        }
        is SessionListEvent.Archived -> when (event.archived) {
            true -> when (event.ids.size) {
                1 -> "Archived session."
                else -> "Archived ${event.ids.size} sessions."
            }
            false -> when (event.ids.size) {
                1 -> "Unarchived session."
                else -> "Unarchived ${event.ids.size} sessions."
            }
        }
        is SessionListEvent.Pinned -> when (event.pinned) {
            true -> when (event.ids.size) {
                1 -> "Pinned session."
                else -> "Pinned ${event.ids.size} sessions."
            }
            false -> when (event.ids.size) {
                1 -> "Unpinned session."
                else -> "Unpinned ${event.ids.size} sessions."
            }
        }
        is SessionListEvent.Moved -> if (event.projectName.isNullOrBlank()) {
            "Moved \"${event.sessionTitle.ifBlank { "session" }}\" to no project."
        } else {
            "Moved \"${event.sessionTitle.ifBlank { "session" }}\" to \"${event.projectName}\"."
        }
        is SessionListEvent.Duplicated -> "Duplicated \"${event.originalTitle ?: "session"}\"."
        is SessionListEvent.Forked -> "Forked \"${event.originalTitle ?: "session"}\"."
        is SessionListEvent.Renamed -> if (event.sessionTitle == event.newTitle) {
            null
        } else {
            "Renamed \"${event.sessionTitle.ifBlank { "session" }}\" to \"${event.newTitle}\"."
        }
        is SessionListEvent.ActionError -> event.message
    }

    /** Default duration for short-lived success events. Errors use LONG via the caller. */
    val SUCCESS_DURATION: SnackbarDuration = SnackbarDuration.Short
    /** Errors stay on screen longer so the user can read and dismiss. */
    val ERROR_DURATION: SnackbarDuration = SnackbarDuration.Long
}
