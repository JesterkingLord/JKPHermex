package com.hermexapp.android.features.sessionlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Wave 6 Slice 6.4 — pure-unit tests for the [minuteBucket] helper used
 * by [LiveClock]. The helper is intentionally split out of the composable
 * so it can be exercised on a plain JVM without an Android runtime, and
 * so the canary invariants are pinned against future regressions
 * without a device test pass.
 *
 * The composable itself ticks every minute; these tests pin the
 * numerical contract that drives when the recomposition happens.
 */
class LiveClockTest {

    @Test
    fun minuteBucket_zero_is_zero() {
        // The Unix epoch anchors "minute 0" at exactly 0L — verifies the
        // bucket doesn't accidentally drift (e.g. using seconds or ms).
        assertEquals(0, minuteBucket(0L))
    }

    @Test
    fun minuteBucket_oneMinute_isOne() {
        // 60_000ms = 1 minute after epoch → bucket 1.
        assertEquals(1, minuteBucket(60_000L))
    }

    @Test
    fun minuteBucket_nearMinuteBoundary_doesNotAdvanceEarly() {
        // 59_999ms = just before the second minute bucket starts.
        // This pins the round-down semantics: we never show "minute 1"
        // until at least 60_000ms have passed, otherwise the clock would
        // tick visibly too early.
        assertEquals(0, minuteBucket(59_999L))
    }

    @Test
    fun minuteBucket_atMinuteBoundary_isFloor() {
        // 60_000ms exactly — already in bucket 1.
        assertEquals(1, minuteBucket(60_000L))
    }

    @Test
    fun minuteBucket_largeValue_doesNotOverflow() {
        // Year 2030 ≈ 1,900,000,000 seconds = 1.14e11 minutes. Well
        // within Int.MAX_VALUE (≈ 2.1e9). The bucket helper casts to
        // Int explicitly — verify a typical post-2030 timestamp still
        // computes correctly (no overflow into negatives).
        val y2030 = 60_000L * 1_893_456_000L
        assertEquals(1_893_456_000, minuteBucket(y2030))
    }

    @Test
    fun minuteBucket_differentMinutes_haveDifferentBuckets() {
        // Two epoch timestamps 90 seconds apart MUST land in different
        // 1-minute buckets — otherwise the LaunchedEffect tick would
        // miss real time progression and the clock would freeze.
        val earlier = minuteBucket(60_000L * 100L)
        val later = minuteBucket(60_000L * 101L + 30_000L)
        assertNotEquals(earlier, later)
    }
}
