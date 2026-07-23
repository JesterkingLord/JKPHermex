package com.hermexapp.android.features.sessionlist

/**
 * One-shot, transient events the [SessionListViewModel] emits so the screen can
 * show Material 3 snackbars / dialogs without coupling state updates (which
 * drive recomposition) to transient UX. Compare with `errorMessage` (state),
 * which the screen renders persistently while the message stays set.
 *
 * Design notes (Excellence v1 Wave 0):
 *   * Single delete → [Deleted] with N=1; bulk → N-many. The screen formats
 *     the snackbar copy based on the list size.
 *   * UNDO is intentionally **NOT** part of this contract. The server has no
 *     restore-from-trash primitive, and the only client-side "undo" we could
 *     offer (re-create empty shells) loses message history — that would be a
 *     false promise. See `docs/PLAN_excellence_v1.md` §11 for the operator
 *     decision that arrived at this. If the operator later wants a
 *     best-effort shell-only undo (e.g. for bulk ops) we add it here, not in
 *     the screen.
 *   * `ActionError` is for **action**-level failures (a delete returned an
 *     error response). It is *not* the same as the `errorMessage` state which
 *     is for load-level failures (initial refresh failed). Keeping the two
 *     channels separate lets the screen clear stale UX noise after a retry.
 */
sealed interface SessionListEvent {
    val timestamp: Long

    data class Deleted(
        val ids: List<String>,
        val titles: List<String>,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Archived(
        val ids: List<String>,
        val archived: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Pinned(
        val ids: List<String>,
        val pinned: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Moved(
        val sessionTitle: String,
        val projectName: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Duplicated(
        val originalTitle: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Forked(
        val originalTitle: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class Renamed(
        val sessionTitle: String,
        val newTitle: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent

    data class ActionError(
        val message: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : SessionListEvent
}
