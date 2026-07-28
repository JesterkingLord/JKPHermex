package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFilterTest {

    @Test
    fun `filter derives from the collected session snapshot`() {
        val sessions = listOf(
            SessionSummary(sessionId = "one", pinned = true),
            SessionSummary(sessionId = "two", pinned = false),
        )

        assertEquals(
            listOf("one"),
            filterSessions(sessions, SessionListViewModel.FilterMode.Pinned).map { it.sessionId },
        )
        assertEquals(
            listOf("one", "two"),
            filterSessions(sessions, SessionListViewModel.FilterMode.All).map { it.sessionId },
        )
    }
}
