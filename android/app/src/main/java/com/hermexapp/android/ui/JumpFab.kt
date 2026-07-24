package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.HermexPalette
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.delay

/**
 * Wave 9.7 (2026-07-24) — JumpFab rewritten to render **at most one**
 * floating control at any moment, in response to the v0.8.6 screenshot
 * regression: the user saw two pills (a yellow accent pill AND a white
 * pill) stacked on the right side of the chat.
 *
 * v0.8.6 had two separate `AnimatedVisibility` widgets — a yellow
 * "scroll indicator" that pulsed while scrolling and a white
 * "jump chip" that appeared after the grace window — stacked in a
 * single Column. When both were visible simultaneously (any time you
 * were off-edge AND had scrolled within the last second), the user saw
 * two pills. The fix: **one widget, role-based content**.
 *
 * ## Single-pill contract (enforced at the composable level)
 *
 * 1. `!contentIsScrollable` — no pills, ever (nothing to scroll).
 *
 * 2. `isScrolling == true` — show the **yellow pill** (accent color)
 *    with an `↓` glyph at full opacity. This is the "yes, you're
 *    scrolling" confirmation.
 *
 * 3. `isScrolling == false`, within 1 s of last scroll, off an edge —
 *    show the **yellow pill** during the grace window. Same pill, same
 *    role: "you just scrolled."
 *
 * 4. `isScrolling == false`, after 1 s grace, off an edge — show the
 *    **white pill** (palette.pillBackground) with a directional glyph
 *    (↓ if there's more content below, ↑ if scrolled up). Tap to jump.
 *
 * 5. `isScrolling == false`, after 1 s grace, at-edge — show **no pill**.
 *    The list is at the natural resting point; nothing to confirm.
 *
 * The previously-separate `decideJumpTarget()` is preserved as a
 * helper that's still called by the unit tests, but the **single-pill**
 * composable reads `decideScrollIndicatorVisibility()` and chooses
 * what to render.
 *
 * ## Pure-helper split
 *
 *  - [decideScrollIndicatorVisibility] → boolean: do we show the
 *    yellow "scrolling" pill? YES exactly while `isScrolling` is true
 *    OR during the post-scroll grace window. (No role distinction —
 *    the pill is always yellow when visible.)
 *
 *  - [decideJumpTarget] → enum: is there a useful jump from the
 *    current position, and which direction? Tests pin the at-edge
 *    contract separately.
 */
private const val HIDE_DELAY_MS: Long = 1_000L

/**
 * Decides whether the yellow "scrolling" pill should be visible **right now**.
 *
 * Inputs:
 *   * [isScrolling] — from `LazyListState.isScrollInProgress`.
 *   * [hideAfterScrollStop] — flips to `true` once the [HIDE_DELAY_MS]
 *     grace window has elapsed since the last `isScrolling=true`. The
 *     composable drives this via a single [LaunchedEffect].
 *   * [contentIsScrollable] — `LazyListState.canScrollForward ||
 *     LazyListState.canScrollBackward`. When false, the pill is
 *     permanently hidden (there's nothing to scroll).
 *
 * Returns `true` exactly when the yellow pill should paint.
 */
fun decideScrollIndicatorVisibility(
    isScrolling: Boolean,
    hideAfterScrollStop: Boolean,
    contentIsScrollable: Boolean,
): Boolean {
    if (!contentIsScrollable) return false
    if (isScrolling) return true
    return !hideAfterScrollStop
}

/**
 * At-edge probe for the jump chip. Pure function, exposed for unit tests.
 *
 * Returns:
 *   * [JumpTarget.NONE] when there's nothing meaningful to jump to
 *     (empty list, single-item list, or at-both-edges with no room).
 *   * [JumpTarget.BOTTOM] when the user is at-or-near the top (room
 *     to scroll down to latest).
 *   * [JumpTarget.TOP] when the user is at-or-near the bottom and
 *     there's history to scroll back to.
 */
enum class JumpTarget { NONE, TOP, BOTTOM }

fun decideJumpTarget(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
): JumpTarget {
    if (lastIndex <= 0) return JumpTarget.NONE
    val first = firstVisibleIndex.coerceAtLeast(0)
    val last = lastVisibleIndex.coerceAtLeast(0)
    val atTop = first == 0
    val atBottom = last >= lastIndex
    return when {
        atTop && atBottom -> JumpTarget.NONE
        atTop -> JumpTarget.BOTTOM
        atBottom -> JumpTarget.TOP
        else -> JumpTarget.BOTTOM
    }
}

/**
 * Decide what the JumpFab should be in this exact state.
 *
 * Three-state model — at most one of these is true at any time, so
 * the composable never renders two pills.
 *
 *   * SCROLL_PILL — yellow pill, ↓ at full opacity. Confirms that the
 *     user just scrolled or is currently scrolling.
 *   * JUMP_CHIP — white pill, directional glyph (↑ or ↓). Tap to jump
 *     to the far edge. Visible only after the 1 s grace, and only when
 *     the user is currently off-edge.
 *   * NONE — no pill. The list is at rest at an edge; nothing useful
 *     to confirm or jump to.
 */
enum class JumpFabRole { SCROLL_PILL, JUMP_CHIP_BOTTOM, JUMP_CHIP_TOP, NONE }

/**
 * Pure function — at most one of `SCROLL_PILL`, `JUMP_CHIP_BOTTOM`,
 * `JUMP_CHIP_TOP`, or `NONE` for any input combination.
 *
 * The v0.8.6 double-pill bug was caused by letting two independent
 * computations both be visible at the same time. This function enforces
 * mutual exclusion: when the SCROLL_PILL is on, the JUMP_CHIP is
 * suppressed (and vice versa).
 */
fun decideJumpFabRole(
    isScrolling: Boolean,
    hideAfterScrollStop: Boolean,
    contentIsScrollable: Boolean,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
    showJumpChip: Boolean = true,
): JumpFabRole {
    if (!contentIsScrollable) return JumpFabRole.NONE

    // While scrolling, the yellow SCROLL_PILL wins outright. The chip
    // is suppressed entirely — they don't share screen real estate.
    if (isScrolling) return JumpFabRole.SCROLL_PILL

    // In the 1 s grace window: still SCROLL_PILL (the indicator the
    // user asked for).
    if (!hideAfterScrollStop) return JumpFabRole.SCROLL_PILL

    // Past the grace window. Decide whether a JUMP_CHIP helps.
    if (!showJumpChip) return JumpFabRole.NONE
    return when (
        decideJumpTarget(
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            lastIndex = lastIndex,
        )
    ) {
        JumpTarget.TOP -> JumpFabRole.JUMP_CHIP_TOP
        JumpTarget.BOTTOM -> JumpFabRole.JUMP_CHIP_BOTTOM
        JumpTarget.NONE -> JumpFabRole.NONE
    }
}

/**
 * The JumpFab composable. Renders **at most one** floating pill.
 *
 * Behavior is driven by [decideJumpFabRole]:
 *   * SCROLL_PILL        → yellow accent pill, ↓ icon, full opacity.
 *   * JUMP_CHIP_BOTTOM    → white pill, ↓ icon, clickable to jump to bottom.
 *   * JUMP_CHIP_TOP       → white pill, ↑ icon, clickable to jump to top.
 *   * NONE                → composable returns early, no rendering.
 */
@Composable
fun JumpFab(
    isScrolling: Boolean,
    contentIsScrollable: Boolean,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = HIDE_DELAY_MS,
    showIndicator: Boolean = true,
    showJumpChip: Boolean = true,
) {
    if (!showIndicator && !showJumpChip) return

    // Single LaunchedEffect drives the 1-second hide-after-scroll-stop.
    var hideAfterScrollStop by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, contentIsScrollable) {
        if (!contentIsScrollable) {
            hideAfterScrollStop = true
            return@LaunchedEffect
        }
        if (isScrolling) {
            // Cancels the pending hide timer; recreating from scratch.
            hideAfterScrollStop = false
            return@LaunchedEffect
        }
        // Scrolling just stopped → start grace timer.
        hideAfterScrollStop = false
        delay(hideDelayMs)
        hideAfterScrollStop = true
    }

    val role = decideJumpFabRole(
        isScrolling = isScrolling,
        hideAfterScrollStop = hideAfterScrollStop,
        contentIsScrollable = contentIsScrollable,
        firstVisibleIndex = firstVisibleIndex,
        lastVisibleIndex = lastVisibleIndex,
        lastIndex = lastIndex,
        showJumpChip = showJumpChip && showIndicator, // both default-on
    )

    val palette = LocalHermexPalette.current

    AnimatedVisibility(
        visible = role != JumpFabRole.NONE,
        enter = fadeIn(animationSpec = tween(140)),
        exit = fadeOut(animationSpec = tween(140)),
        modifier = modifier,
    ) {
        when (role) {
            JumpFabRole.SCROLL_PILL -> ScrollIndicatorPill(isScrolling = false)
            JumpFabRole.JUMP_CHIP_BOTTOM -> JumpChip(
                jumpingToBottom = true,
                onClick = { onScrollToIndex(lastIndex) },
                palette = palette,
            )
            JumpFabRole.JUMP_CHIP_TOP -> JumpChip(
                jumpingToBottom = false,
                onClick = { onScrollToIndex(0) },
                palette = palette,
            )
            JumpFabRole.NONE -> { /* unreachable: AnimatedVisibility is gated on role != NONE */ }
        }
    }
}

@Composable
private fun ScrollIndicatorPill(isScrolling: Boolean) {
    val palette = LocalHermexPalette.current
    Surface(
        // Yellow accent for the scroll indicator — same color as
        // the active fab in the live-debug chip, intentional.
        color = palette.accent,
        contentColor = Color.White,
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(40.dp)
            .testTag("jumpFab.scrollIndicator"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Scrolling",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun JumpChip(
    jumpingToBottom: Boolean,
    onClick: () -> Unit,
    palette: HermexPalette,
) {
    Surface(
        color = palette.pillBackground,
        contentColor = palette.pillForeground,
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(48.dp)
            .testTag(if (jumpingToBottom) "jumpFab.bottom" else "jumpFab.top")
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (jumpingToBottom) Icons.Filled.ArrowDownward
                    else Icons.Filled.ArrowUpward,
                contentDescription = if (jumpingToBottom) "Jump to latest message"
                    else "Jump to top",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
