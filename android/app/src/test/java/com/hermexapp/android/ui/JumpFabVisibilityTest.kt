package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [JumpFab]'s **visibility decision**.
 *
 * Wave 9.5 (2026-07-24) refactored [JumpFab] to delegate to a pure
 * [decideJumpFabVisibility] function so the production composable and
 * this test share ONE source of truth. This file calls that function
 * directly — no more mirrored copy of the logic. A change to the
 * composable that forgets to update the test now compiles, but the
 * test will start failing for any behavior the helper still pins.
 *
 * Contract (in priority order):
 *   1. Empty list (lastIndex <= 0) → never show.
 *   2. Mid-scroll (isScrollInProgress) → suppress, the user just sent
 *      a message and the LazyList is animating — no flicker.
 *   3. The user is at top (firstVisibleIndex == 0) → no jump-to-top.
 *      They MAY still see jump-to-bottom (scrolled up to read history).
 *   4. The user is at bottom (lastVisibleIndex >= lastIndex) → no
 *      jump-to-bottom. They MAY still see jump-to-top (reviewing history).
 *   5. List mostly fits the viewport (≥ 70% visible) → hide both; a
 *      jump won't save meaningful scrolling.
 *   6. Both directions qualify → closer edge wins; equal-distance tie
 *      goes to bottom (jump-to-latest is the more common intent).
 *
 * Regressions targeted (all from real user-reported bugs in v0.8.4):
 *   - Empty / 1-item list with FAB still rendering ⇒ bug in screenshot.
 *   - Mid-scroll flash after send → 9.0/9.5 fix.
 *   - Tall chat messages (300+ px each) skewing the 70%-visible rule.
 */
class JumpFabVisibilityTest {

    @Test
    fun `empty list never shows`() {
        assertEquals(JumpFabDirection.NONE,
            decideJumpFabVisibility(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 0))
        assertEquals(JumpFabDirection.NONE,
            decideJumpFabVisibility(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = -1))
    }

    @Test
    fun `single-item list shows nothing when its only entry is fully visible`() {
        // User screenshot scenario: chat has 1 message, fully on screen.
        assertEquals(JumpFabDirection.NONE,
            decideJumpFabVisibility(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 0))
        // lastIndex=1 with only index 0 visible → distance-from-bottom = 1
        // which is below threshold (5 default), so no FAB.
        assertEquals(JumpFabDirection.NONE,
            decideJumpFabVisibility(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 1))
        // Same for lastIndex=2 (default threshold): distance-from-bottom = 2 < 5.
        assertEquals(JumpFabDirection.NONE,
            decideJumpFabVisibility(firstVisibleIndex = 0, lastVisibleIndex = 0, lastIndex = 2))
        // At threshold=1, the FAB WOULD show because distance >= 1.
        assertEquals(JumpFabDirection.BOTTOM,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                lastIndex = 2,
                threshold = 1,
            ),
        )
    }

    @Test
    fun `at-top with long tail shows jump-to-bottom`() {
        // User is at the top of the list (looking at oldest items) but the
        // tail (latest messages) is 96 entries away. They SHOULD see
        // jump-to-bottom — being at the top doesn't make a jump to the
        // bottom meaningless.
        assertEquals(
            JumpFabDirection.BOTTOM,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 3,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `at-bottom with long head shows jump-to-top`() {
        // User is looking at the last 5 entries — they CAN jump to top
        // (96 entries away). The chat timeline convention is "jump-to-top"
        // when at the bottom so the user can review history.
        assertEquals(
            JumpFabDirection.TOP,
            decideJumpFabVisibility(
                firstVisibleIndex = 96,
                lastVisibleIndex = 100,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `fully pinned at last item hides jump-to-bottom but keeps jump-to-top`() {
        // Single-item visible window at index 99. Jump-to-bottom is
        // obviously meaningless. Jump-to-top is still meaningful (99
        // entries away).
        assertEquals(
            JumpFabDirection.TOP,
            decideJumpFabVisibility(
                firstVisibleIndex = 99,
                lastVisibleIndex = 99,
                lastIndex = 99,
            ),
        )
    }

    @Test
    fun `fully pinned at first item hides jump-to-top but keeps jump-to-bottom`() {
        // Single-item visible window at index 0 of a long list.
        // Jump-to-top is gone; jump-to-bottom stays.
        assertEquals(
            JumpFabDirection.BOTTOM,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `visible-fraction over 70 percent hides FAB even when not at edge`() {
        // 46 of 60 items visible = 76%. Even though neither edge is in
        // view, the list is too short relative to what's already on
        // screen — no jump will save the user meaningful scrolling.
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 10,
                lastVisibleIndex = 55,
                lastIndex = 60,
            ),
        )
    }

    @Test
    fun `user near top with mostly-visible window hides FAB`() {
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 49,
                lastIndex = 60,
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
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 49,
                lastIndex = 50,
            ),
        )
    }

    @Test
    fun `user mid-list near bottom shows jump-to-top`() {
        assertEquals(
            JumpFabDirection.TOP,
            decideJumpFabVisibility(
                firstVisibleIndex = 48,
                lastVisibleIndex = 50,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `equidistant from both edges defaults to jump-to-bottom`() {
        // 50 items above, 50 below — equidistant. Per the contract the
        // jump-to-bottom wins because "jump to latest" is the more
        // common intent.
        assertEquals(
            JumpFabDirection.BOTTOM,
            decideJumpFabVisibility(
                firstVisibleIndex = 50,
                lastVisibleIndex = 53,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `boundary at exactly the threshold shows FAB on both sides`() {
        val d = decideJumpFabVisibility(
            firstVisibleIndex = 5, // exactly threshold
            lastVisibleIndex = 7,
            lastIndex = 100,
        )
        assertTrue(d == JumpFabDirection.TOP || d == JumpFabDirection.BOTTOM)
    }

    @Test
    fun `mid-scroll (isScrolling true) always suppresses FAB`() {
        // LazyList is animating after a send. firstVisibleItemIndex
        // can briefly read 0 even though the user is effectively
        // pinned at the bottom. Without this guard, the FAB would
        // flicker in and back out mid-animation.
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 100,
                lastIndex = 100,
                isScrolling = true,
            ),
        )
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 50,
                lastVisibleIndex = 60,
                lastIndex = 100,
                isScrolling = true,
            ),
        )
    }

    @Test
    fun `negative lastVisibleIndex does not produce a phantom FAR-from-bottom`() {
        // Defensive guard: if the LazyList reports -1 (no visible items
        // just yet, hydration edge case), we MUST not compute
        // distanceFromBottom = lastIndex - (-1) = lastIndex + 1, which
        // would make a 2-item chat look "huge" and trigger the FAB even
        // when the user is at the top of an effectively empty screen.
        // The fix: clamp `last` to at least 0.
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = -1,
                lastIndex = 2,
            ),
        )
    }

    @Test
    fun `exact screenshot scenario returns NONE at default threshold`() {
        // The screenshot in the v0.8.4 bug report: 3 entries (assistant
        // message + 2 footer notices), user is at the top, only first
        // entry visible on screen. FAB MUST be hidden.
        // distanceFromBottom = lastIndex - lastVisibleIndex = 2 - 0 = 2.
        // Default threshold=5 → distance < threshold → hidden. ✓
        // distanceFromTop = 0 → already at top → cannot jump to top. ✓
        // visibleFraction = 1/3 = 0.33 < 0.7 → list "long enough". ✓
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                lastIndex = 2,
            ),
        )
        // Same scenario with 2 entries (the simplest 2-item chat): still
        // hidden at default threshold (distance=2 < 5).
        assertEquals(
            JumpFabDirection.NONE,
            decideJumpFabVisibility(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                lastIndex = 1,
            ),
        )
    }
}
