package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.SessionSummary
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
/**
 * Wave 6 Slice 6.2 — date-grouped section headers for the session list.
 *
 * Sections are exactly five, ordered top-to-bottom by recency:
 *   1. PINNED  — all `pinned == true` sessions (regardless of date)
 *   2. TODAY   — last message / update within today's local date
 *   3. YESTERDAY — within yesterday's local date
 *   4. PREVIOUS_7_DAYS — 2..7 days back
 *   5. EARLIER  — older than 7 days
 *
 * "Date" is the user's LOCAL date (ZoneId.systemDefault() at the call site,
 * overridable via [clock] + [zone] for tests). Session ordering within a
 * section is unchanged from the caller's list — the caller is expected to
 * pre-sort by `lastMessageAt` or `updatedAt` desc before calling.
 *
 * Sessions without a parseable date (updatedAt==null, <=0, NaN, or
 * unparseable) fall into the EARLIER bucket as a defensive fallback — these
 * are legacy or partially-synced rows and shouldn't crash the layout.
 */
object SessionGroups {

    /** The five section labels, ordered for rendering. */
    enum class Section(val label: String) {
        PINNED("Pinned"),
        TODAY("Today"),
        YESTERDAY("Yesterday"),
        PREVIOUS_7_DAYS("Previous 7 days"),
        EARLIER("Earlier"),
    }

    /**
     * Grouped section: a header + the sessions that fall into it.
     * The header `Session` is a UI-friendly tag, not a real session row —
     * the caller must drop it into `LazyColumn` as a header item.
     */
    data class Group(
        val section: Section,
        /** Sessions in this group, in caller-provided order (preserved). */
        val sessions: List<SessionSummary>,
    )

    /**
     * Group [sessions] into the five fixed buckets.
     *
     * Pinned sessions are filtered out first and emitted separately at the
     * top regardless of date — ChatGPT / iOS Mail style.
     *
     * @param sessions   The list, expected pre-sorted newest-first.
     * @param nowMillis  Override for "current instant" — defaults to [Clock.systemUTC].
     * @param zone       Override for the user's local zone — defaults to [ZoneId.systemDefault].
     */
    fun groupSessions(
        sessions: List<SessionSummary>,
        nowMillis: Long = Clock.systemUTC().millis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Group> {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today: LocalDate = now.toLocalDate()
        val yesterday: LocalDate = today.minusDays(1)
        val sevenDaysAgo: LocalDate = today.minusDays(7)

        val pinned = mutableListOf<SessionSummary>()
        val todayList = mutableListOf<SessionSummary>()
        val yesterdayList = mutableListOf<SessionSummary>()
        val previous7 = mutableListOf<SessionSummary>()
        val earlier = mutableListOf<SessionSummary>()

        for (session in sessions) {
            if (session.pinned == true) {
                pinned.add(session)
            } else {
                val date = bucketDateFor(session, zone)
                if (date == null) {
                    earlier.add(session)
                } else when {
                    date.isEqual(today) -> todayList.add(session)
                    date.isEqual(yesterday) -> yesterdayList.add(session)
                    !date.isBefore(sevenDaysAgo) -> previous7.add(session)
                    else -> earlier.add(session)
                }
            }
        }

        val out = mutableListOf<Group>()
        if (pinned.isNotEmpty()) out.add(Group(Section.PINNED, pinned))
        if (todayList.isNotEmpty()) out.add(Group(Section.TODAY, todayList))
        if (yesterdayList.isNotEmpty()) out.add(Group(Section.YESTERDAY, yesterdayList))
        if (previous7.isNotEmpty()) out.add(Group(Section.PREVIOUS_7_DAYS, previous7))
        if (earlier.isNotEmpty()) out.add(Group(Section.EARLIER, earlier))
        return out
    }

    /**
     * Compute the *date bucket* a session falls into, in the user's local zone.
     *
     * Picks the freshest of (lastMessageAt, updatedAt, createdAt). Defensive:
     * returns null for null / non-positive / NaN values so the caller can fall
     * back to the EARLIER bucket without blowing up the layout.
     */
    private fun bucketDateFor(
        session: SessionSummary,
        zone: ZoneId,
    ): LocalDate? {
        // Sequence over (lastMessageAt, updatedAt, createdAt) — pick the
        // freshest valid one. Defensive: returns null for null / non-positive
        // / NaN values so the caller can fall back to the EARLIER bucket.
        val lastMessageAt = session.lastMessageAt
        val updatedAt = session.updatedAt
        val createdAt = session.createdAt
        val epochSeconds: Double? = when {
            lastMessageAt != null && lastMessageAt.isFinite() && lastMessageAt > 0.0 -> lastMessageAt
            updatedAt != null && updatedAt.isFinite() && updatedAt > 0.0 -> updatedAt
            createdAt != null && createdAt.isFinite() && createdAt > 0.0 -> createdAt
            else -> null
        }
        if (epochSeconds == null) return null
        return try {
            Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
        } catch (e: Exception) {
            null
        }
    }

    /** Spacing values for the section headers — exposed for the UI layer to use. */
    val SECTION_HEADER_TOP_PADDING_DP: Int = 18
    val SECTION_HEADER_BOTTOM_PADDING_DP: Int = 6

    /**
     * Returns the human-readable bucket label for a session key, used in
     * snapshot-test assertions or accessibility announcements ("3 messages
     * in Today"). UI layers should use the enum's `label` directly when
     * rendering the section header.
     */
    fun humanSectionFor(session: SessionSummary, nowMillis: Long = Clock.systemUTC().millis(), zone: ZoneId = ZoneId.systemDefault()): Section? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val sevenDaysAgo = today.minusDays(7)
        if (session.pinned == true) return Section.PINNED
        val date = bucketDateFor(session, zone) ?: return Section.EARLIER
        return when {
            date.isEqual(today) -> Section.TODAY
            date.isEqual(yesterday) -> Section.YESTERDAY
            !date.isBefore(sevenDaysAgo) -> Section.PREVIOUS_7_DAYS
            else -> Section.EARLIER
        }
    }

    /** Convenience: number of distinct days since [epochSeconds]. Used by tests. */
    internal fun daysSince(epochSeconds: Double, nowMillis: Long, zone: ZoneId): Long {
        val then = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(then, today)
    }
}
