package com.hermexapp.android.features.sessionlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-unit tests for the snackbar-message formatter. We pin the exact
 * copy because the operator-visible text was discussed across multiple
 * chat turns (see plan §0.2). Any change here is a UX change that needs
 * an operator sign-off, not a "while I was here" rewrite.
 *
 * No Android dependencies — these run in pure JVM, in the same gradle
 * `testDebugUnitTest` task as everything else.
 */
class SessionListSnackbarTest {

    @Test
    fun `Deleted single session shows title with cannot-be-undone copy`() {
        val message = SessionListSnackbar.messageFor(
            SessionListEvent.Deleted(ids = listOf("abc"), titles = listOf("Plan v2")),
        )
        assertNotNull(message)
        assertTrue(
            "Expected 'Plan v2' and 'cannot' in '$message'",
            message!!.contains("Plan v2") && message.contains("cannot"),
        )
    }

    @Test
    fun `Deleted bulk counts titles (sessions with a name) and shows the first one`() {
        // ids.size == 4 but only 3 had titles — the count in the copy
        // intentionally follows titles.size (matches what the screen
        // already shows the title for). Mixed null-title rows show in
        // the count underneath only via the bulk toolbar's own N.
        val message = SessionListSnackbar.messageFor(
            SessionListEvent.Deleted(
                ids = listOf("a", "b", "c", "d"),
                titles = listOf("Alpha", "Beta", "Gamma"),
            ),
        )
        assertEquals(
            "Deleted 3 sessions including \"Alpha\". This cannot be undone.",
            message,
        )
    }

    @Test
    fun `Deleted single with empty title falls back to generic copy`() {
        val message = SessionListSnackbar.messageFor(
            SessionListEvent.Deleted(ids = listOf("x"), titles = emptyList()),
        )
        assertEquals(
            "Deleted session. This cannot be undone.",
            message,
        )
    }

    @Test
    fun `Archived singular matches verb`() {
        assertEquals(
            "Archived session.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Archived(ids = listOf("a"), archived = true),
            ),
        )
        assertEquals(
            "Unarchived session.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Archived(ids = listOf("a"), archived = false),
            ),
        )
    }

    @Test
    fun `Archived plural reports count`() {
        assertEquals(
            "Archived 5 sessions.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Archived(ids = (1..5).map { "id-$it" }, archived = true),
            ),
        )
    }

    @Test
    fun `Pinned plural reports count`() {
        assertEquals(
            "Pinned 3 sessions.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Pinned(
                    ids = listOf("a", "b", "c"),
                    pinned = true,
                ),
            ),
        )
    }

    @Test
    fun `Moved with null project name uses no-project copy`() {
        assertEquals(
            "Moved \"Foo\" to no project.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Moved(sessionTitle = "Foo", projectName = null),
            ),
        )
        // Blank title falls back to the literal "session" placeholder
        // (same fallback the screen applies to untitled rows); the VM
        // also passes a non-blank title by default so this is the edge
        // case where the VM never had a title to capture.
        assertEquals(
            "Moved \"session\" to no project.",
            SessionListSnackbar.messageFor(
                SessionListEvent.Moved(sessionTitle = "", projectName = null),
            ),
        )
    }

    @Test
    fun `Moved with project name formats quote-pair`() {
        assertEquals(
            "Moved \"Foo\" to \"Bar\".",
            SessionListSnackbar.messageFor(
                SessionListEvent.Moved(sessionTitle = "Foo", projectName = "Bar"),
            ),
        )
    }

    @Test
    fun `Renamed to identical title returns null so the screen does not flash a redundant snackbar`() {
        // Idempotent rename (e.g. user pressed Enter without changing
        // anything). The formatter returns null so the screen's
        // `message ?: return@collect` skips the snackbar entirely.
        assertEquals(
            null,
            SessionListSnackbar.messageFor(
                SessionListEvent.Renamed(sessionTitle = "Same", newTitle = "Same"),
            ),
        )
    }

    @Test
    fun `Renamed with different title shows both`() {
        assertEquals(
            "Renamed \"Old\" to \"New\".",
            SessionListSnackbar.messageFor(
                SessionListEvent.Renamed(sessionTitle = "Old", newTitle = "New"),
            ),
        )
    }

    @Test
    fun `ActionError passes through the error message verbatim`() {
        assertEquals(
            "Could not delete session.",
            SessionListSnackbar.messageFor(
                SessionListEvent.ActionError(message = "Could not delete session."),
            ),
        )
    }

    @Test
    fun `Duplicated and Forked include the source title when known`() {
        assertEquals(
            "Duplicated \"My chat\".",
            SessionListSnackbar.messageFor(
                SessionListEvent.Duplicated(originalTitle = "My chat"),
            ),
        )
        assertEquals(
            "Forked \"Side experiment\".",
            SessionListSnackbar.messageFor(
                SessionListEvent.Forked(originalTitle = "Side experiment"),
            ),
        )
    }

    @Test
    fun `Duplicated with null title uses literal session placeholder`() {
        // Edge case: server deletes the original before the snackbar renders.
        assertEquals(
            "Duplicated \"session\".",
            SessionListSnackbar.messageFor(SessionListEvent.Duplicated(originalTitle = null)),
        )
    }

    @Test
    fun `SUCCESS_DURATION is Short and ERROR_DURATION is Long`() {
        // Pinned durations are part of the screen's contract, so we assert
        // them at the test level — a future maintainer flipping the value
        // will see this test fail.
        assertEquals(
            androidx.compose.material3.SnackbarDuration.Short,
            SessionListSnackbar.SUCCESS_DURATION,
        )
        assertEquals(
            androidx.compose.material3.SnackbarDuration.Long,
            SessionListSnackbar.ERROR_DURATION,
        )
    }
}
