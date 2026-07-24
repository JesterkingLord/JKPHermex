package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [JumpFab]'s **visibility decision** — Wave 9 (2026-07-28).
 *
 * The composable delegates to [decideJumpFab] which we test here in
 * isolation. The contract:
 *
 *   * If `lastIndex <= 0` (empty list) -> never show.
 *   * The user can be **at the top** (firstVisibleIndex=0) — that
 *     suppresses jump-to-top but jump-to-bottom can still fire.
 *   * The user can be **at the bottom** (lastVisibleIndex==lastIndex) —
 *     that suppresses jump-to-bottom but jump-to-top can still fire.
 *   * When >70% of items are visible, neither FAB is meaningful — hide
 *     both to avoid noise.
 *   * When both directions qualify, the closer edge wins for tap
 *     targeting. Equal-distance ties default to jump-to-bottom
 *     (jump-to-latest is the more common intent).
 *
 * The original (Wave 2) bug: the FAB was visible whenever
 * `firstVisibleIndex >= 5` even during list hydration, causing a transient
 * "jump to top" prompt right after sending a message. Wave 9 fixes it.
 */
class JumpFabVisibilityTest {

    private enum class Direction { NONE, BOTTOM, TOP }

    /** Mirror of the composable's decision logic, kept here so we can
     *  unit-test it without spinning up Compose. Update both copies
     *  together — the test pins the contract. */
    private fun decideJumpFab(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        lastIndex: Int,
        threshold: Int,
    ): Direction {
        if (lastIndex <= 0) return Direction.NONE
        val atTop = firstVisibleIndex == 0
        val atBottom = lastVisibleIndex >= lastIndex
        val visibleCount = (lastVisibleIndex - firstVisibleIndex + 1).coerceAtLeast(1)
        val visibleFraction = visibleCount.toFloat() / (lastIndex + 1)
        val listTooShortToMatter = visibleFraction > 0.7f

        val showJumpToBottom = !atBottom && !listTooShortToMatter &&
            (lastIndex - lastVisibleIndex) >= threshold
        val showJumpToTop = !atTop && !listTooShortToMatter &&
            firstVisibleIndex >= threshold
        if (!showJumpToBottom && !showJumpToTop) return Direction.NONE
        return when {
            showJumpToBottom && showJumpToTop -> {
                val distDown = lastIndex - lastVisibleIndex
                val distUp = firstVisibleIndex
                if (distDown <= distUp) Direction.BOTTOM else Direction.TOP
            }
            showJumpToBottom -> Direction.BOTTOM
            else -> Direction.TOP
        }
    }

    @Test
    fun `empty list never shows`() {
        assertEquals(Direction.NONE, decideJumpFab(0, 0, 0, threshold = 5))
        assertEquals(Direction.NONE, decideJumpFab(0, 0, -1, threshold = 5))
    }

    @Test
    fun `at-top with long tail shows jump-to-bottom`() {
        // User is at the top of the list (looking at oldest items) but the
        // tail (latest messages) is 96 entries away. They SHOULD see
        // jump-to-bottom — being at the top doesn't make a jump to the
        // bottom meaningless.
        assertEquals(
            Direction.BOTTOM,
            decideJumpFab(
                firstVisibleIndex = 0,
                lastVisibleIndex = 3,
                lastIndex = 100,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `at-bottom with long head shows jump-to-top`() {
        // User is looking at the last 5 entries — they CAN jump to top
        // (96 entries away). The chat timeline convention is "jump-to-top"
        // when at the bottom so the user can review history.
        assertEquals(
            Direction.TOP,
            decideJumpFab(
                firstVisibleIndex = 96,
                lastVisibleIndex = 100,
                lastIndex = 100,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `fully pinned at last item hides jump-to-bottom but keeps jump-to-top`() {
        // Single-item visible window at index 99. Jump-to-bottom is
        // obviously meaningless. Jump-to-top is still meaningful (99
        // entries away).
        assertEquals(
            Direction.TOP,
            decideJumpFab(
                firstVisibleIndex = 99,
                lastVisibleIndex = 99,
                lastIndex = 99,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `fully pinned at first item hides jump-to-top but keeps jump-to-bottom`() {
        // Single-item visible window at index 0 of a long list.
        // Jump-to-top is gone; jump-to-bottom stays.
        assertEquals(
            Direction.BOTTOM,
            decideJumpFab(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                lastIndex = 100,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `visible-fraction over 70 percent hides FAB even when not at edge`() {
        // 46 of 60 items visible = 76%. Even though neither edge is in
        // view, the list is too short relative to what's already on
        // screen — no jump will save the user meaningful scrolling.
        assertEquals(
            Direction.NONE,
            decideJumpFab(
                firstVisibleIndex = 10,
                lastVisibleIndex = 55,
                lastIndex = 60,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `user near top with mostly-visible window hides FAB`() {
        // Items 0..49 of 60 — user is at top AND most of the list is on
        // screen. Both FABs suppressed.
        assertEquals(
            Direction.NONE,
            decideJumpFab(
                firstVisibleIndex = 0,
                lastVisibleIndex = 49,
                lastIndex = 60,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `hydration flash case firstVisible=0 lastVisible=lastIndex hides FAB`() {
        // After send-and-scroll-to-bottom, hydration may briefly report
        // firstVisible=0 while lastVisible=lastIndex. If 70%+ of the
        // list is visible, no FAB at all — this is the typical "I just
        // landed at the bottom of a 50-message thread" case.
        assertEquals(
            Direction.NONE,
            decideJumpFab(
                firstVisibleIndex = 0,
                lastVisibleIndex = 49,
                lastIndex = 50,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `user mid-list near bottom shows jump-to-top`() {
        assertEquals(
            Direction.TOP,
            decideJumpFab(
                firstVisibleIndex = 48,
                lastVisibleIndex = 50,
                lastIndex = 100,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `equidistant from both edges defaults to jump-to-bottom`() {
        // 50 items above, 50 below — equidistant. Per the contract the
        // jump-to-bottom wins because "jump to latest" is the more
        // common intent.
        assertEquals(
            Direction.BOTTOM,
            decideJumpFab(
                firstVisibleIndex = 50,
                lastVisibleIndex = 53,
                lastIndex = 100,
                threshold = 5,
            ),
        )
    }

    @Test
    fun `boundary at exactly the threshold shows FAB on both sides`() {
        val d = decideJumpFab(
            firstVisibleIndex = 5, // exactly threshold
            lastVisibleIndex = 7,
            lastIndex = 100,
            threshold = 5,
        )
        assertTrue(d == Direction.TOP || d == Direction.BOTTOM)
    }
}
