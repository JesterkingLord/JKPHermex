package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Wave 6 Slice 6.2 — date-bucketing for the sidebar section headers.
 *
 * Pins the bucket rules so a refactor can't silently mis-classify a session
 * (e.g. pushing "2 days ago" into TODAY or "5 days ago" into EARLIER).
 *
 * All tests use a fixed "now" (`NOW_EPOCH`) and ZoneId so they don't drift
 * with the calendar. Epoch seconds: 2026-07-15 12:00:00 UTC.
 */
class SessionGroupsTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun session(
        title: String = "Untitled",
        updatedAt: Double? = null,
        lastMessageAt: Double? = null,
        createdAt: Double? = null,
        pinned: Boolean? = null,
        sessionId: String? = null,
    ) = SessionSummary(
        sessionId = sessionId,
        title = title,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        createdAt = createdAt,
        pinned = pinned,
    )

    @Test
    fun pinnedSection_routesToPinned_evenIfRecent() {
        val pinnedToday = session(
            title = "Pinned today",
            updatedAt = NOW_EPOCH - 60.0,   // 1 minute ago
            pinned = true,
        )
        val groups = SessionGroups.groupSessions(
            listOf(pinnedToday),
            nowMillis = NOW_EPOCH_MILLIS,
            zone = zone,
        )
        assertEquals(1, groups.size)
        assertEquals(SessionGroups.Section.PINNED, groups[0].section)
        assertEquals(1, groups[0].sessions.size)
        assertEquals("Pinned today", groups[0].sessions[0].title)
    }

    @Test
    fun recentSession_goesToToday() {
        val s = session("Today", updatedAt = NOW_EPOCH - 60.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.TODAY, g.single().section)
    }

    @Test
    fun yesterdaySession_goesToYesterday() {
        val s = session("Yesterday", updatedAt = NOW_EPOCH - 26 * 3600.0) // 26 hours back
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.YESTERDAY, g.single().section)
    }

    @Test
    fun threeDaysAgo_goesToPrevious7Days() {
        val s = session("Three days ago", updatedAt = NOW_EPOCH - 3 * 86400.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.PREVIOUS_7_DAYS, g.single().section)
    }

    @Test
    fun sevenDaysAgo_goesToEarlier_boundaryInclusive() {
        // 7 days exactly = the boundary. Date isEqual comparison puts it in PREVIOUS_7_DAYS.
        val s = session("Seven days ago", updatedAt = NOW_EPOCH - 7 * 86400.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.PREVIOUS_7_DAYS, g.single().section)
    }

    @Test
    fun tenDaysAgo_goesToEarlier() {
        val s = session("Ten days ago", updatedAt = NOW_EPOCH - 10 * 86400.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.EARLIER, g.single().section)
    }

    @Test
    fun nullDate_fallsIntoEarlier_defensively() {
        val s = session("No date", updatedAt = null, lastMessageAt = null, createdAt = null)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.EARLIER, g.single().section)
    }

    @Test
    fun zeroDate_fallsIntoEarlier_defensively() {
        val s = session("Zero epoch", updatedAt = 0.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.EARLIER, g.single().section)
    }

    @Test
    fun negativeDate_fallsIntoEarlier_defensively() {
        val s = session("Bad epoch", updatedAt = -1.0)
        val g = SessionGroups.groupSessions(listOf(s), NOW_EPOCH_MILLIS, zone)
        assertEquals(SessionGroups.Section.EARLIER, g.single().section)
    }

    @Test
    fun multipleSessions_groupedInOrder_pinnedTodayYesterdayThenEarlier() {
        val listOfSessions = listOf(
            session("old", updatedAt = NOW_EPOCH - 30 * 86400.0),
            session("yesterday", updatedAt = NOW_EPOCH - 26 * 3600.0),
            session("pinned-today", updatedAt = NOW_EPOCH - 60.0, pinned = true),
            session("today-2", updatedAt = NOW_EPOCH - 120.0),
            session("three-days", updatedAt = NOW_EPOCH - 3 * 86400.0),
        )
        val groups = SessionGroups.groupSessions(listOfSessions, NOW_EPOCH_MILLIS, zone)
        // Order must be: PINNED, TODAY, YESTERDAY, PREVIOUS_7_DAYS, EARLIER
        assertEquals(5, groups.size)
        assertEquals(SessionGroups.Section.PINNED, groups[0].section)
        assertEquals(SessionGroups.Section.TODAY, groups[1].section)
        assertEquals(SessionGroups.Section.YESTERDAY, groups[2].section)
        assertEquals(SessionGroups.Section.PREVIOUS_7_DAYS, groups[3].section)
        assertEquals(SessionGroups.Section.EARLIER, groups[4].section)
    }

    @Test
    fun sectionOrdering_emptySectionsAreOmitted() {
        // No pinned; no earlier. Only TODAY should show up.
        val listOfSessions = listOf(
            session("today", updatedAt = NOW_EPOCH - 60.0),
        )
        val groups = SessionGroups.groupSessions(listOfSessions, NOW_EPOCH_MILLIS, zone)
        assertEquals(1, groups.size)
        assertEquals(SessionGroups.Section.TODAY, groups[0].section)
    }

    @Test
    fun withinSection_preservesCallerOrder() {
        // Caller pre-sorts by lastMessageAt desc; we don't reorder within a section.
        val listOfSessions = listOf(
            session("third-today", updatedAt = NOW_EPOCH - 3600.0),
            session("first-today", updatedAt = NOW_EPOCH - 60.0),
            session("second-today", updatedAt = NOW_EPOCH - 600.0),
        )
        val groups = SessionGroups.groupSessions(listOfSessions, NOW_EPOCH_MILLIS, zone)
        val titles = groups.single().sessions.map { it.title }
        assertEquals(listOf("third-today", "first-today", "second-today"), titles)
    }

    @Test
    fun humanSectionFor_picksFreshestOfLastMessageAtUpdatedAtCreatedAt() {
        // No lastMessageAt, no updatedAt, but createdAt is in the past week.
        val s = session(
            title = "no updates",
            updatedAt = null,
            lastMessageAt = null,
            createdAt = NOW_EPOCH - 3 * 86400.0,
        )
        assertEquals(
            SessionGroups.Section.PREVIOUS_7_DAYS,
            SessionGroups.humanSectionFor(s, NOW_EPOCH_MILLIS, zone),
        )
    }

    @Test
    fun sectionLabels_matchSpec() {
        // The label text is part of the design contract — pinned in test so a
        // typo doesn't drift the UI silently.
        assertEquals("Pinned", SessionGroups.Section.PINNED.label)
        assertEquals("Today", SessionGroups.Section.TODAY.label)
        assertEquals("Yesterday", SessionGroups.Section.YESTERDAY.label)
        assertEquals("Previous 7 days", SessionGroups.Section.PREVIOUS_7_DAYS.label)
        assertEquals("Earlier", SessionGroups.Section.EARLIER.label)
    }

    @Test
    fun sectionOrder_constantOrder() {
        // Ensure Section.values() always returns in render order — defensive.
        val order = SessionGroups.Section.values().toList()
        assertEquals(
            listOf(
                SessionGroups.Section.PINNED,
                SessionGroups.Section.TODAY,
                SessionGroups.Section.YESTERDAY,
                SessionGroups.Section.PREVIOUS_7_DAYS,
                SessionGroups.Section.EARLIER,
            ),
            order,
        )
    }

    companion object {
        // Fixed "now" for deterministic tests: 2026-07-15 12:00:00 UTC.
        // 1789502400 is the unix epoch for that instant.
        private const val NOW_EPOCH: Double = 1789502400.0
        private const val NOW_EPOCH_MILLIS: Long = 1_789_502_400_000L
    }
}
