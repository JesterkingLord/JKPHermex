package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
 * Wave 9.6 (2026-07-24) — JumpFab rewritten to match the user-requested
 * UX verbatim. After the v0.8.5 build shipped with the navigation FAB
 * (at-edge show/hide), the user reported the actual desired behavior:
 *
 *   "I want it to show while scrolling, then hide 1 second after
 *    scrolling stops, and the scrollbar to reflect actual position."
 *
 * The JumpFab is now an **iOS-style scroll indicator**: visible while
 * the user is scrolling, hidden 1 second after scrolling stops. A
 * separate, smaller jump-to-top/jump-to-bottom chip lives next to it
 * and only appears once the list is settled at a non-edge position.
 *
 * Implementation details:
 *
 *   * [decideScrollIndicatorVisibility] is the pure helper for the
 *     "am I visible **right now**?" question. It takes the LazyList's
 *     own `isScrolling` flag plus a `hideAfterScrollStop` boolean that
 *     flips `true` exactly once after a [HIDE_DELAY_MS] grace window.
 *
 *   * The grace timer is driven by [LaunchedEffect] keyed on `isScrolling`
 *     and `contentIsScrollable`. When scrolling starts we flip
 *     `hideAfterScrollStop` back to `false` and cancel the timer; when
 *     scrolling stops we restart the timer.
 *
 *   * [decideJumpTarget] is the pure helper for the secondary chip's
 *     at-edge detection. Preserved as a separate concept so its tests
 *     still pin the old contract.
 *
 *   * The at-edge hide-after-the-fact behavior is broken. The old
 *     "JumpFab should disappear when I'm at the bottom" was a bug; the
 *     user explicitly asked for a scroll indicator, not a navigation
 *     control. We keep the navigation affordance as a smaller, less
 *     prominent jump chip that's only shown when the list has settled
 *     (i.e., not while scrolling).
 */
private const val HIDE_DELAY_MS: Long = 1_000L

/**
 * Decides whether the scroll indicator should be visible **right now**.
 *
 * Inputs:
 *   * [isScrolling] — from `LazyListState.isScrollInProgress`.
 *   * [hideAfterScrollStop] — flips to `true` once the [HIDE_DELAY_MS]
 *     grace window has elapsed since the last `isScrolling=true`. The
 *     composable drives this via a single [LaunchedEffect].
 *   * [contentIsScrollable] — `LazyListState.canScrollForward ||
 *     LazyListState.canScrollBackward`. When false, the indicator is
 *     permanently hidden (there's nothing to scroll).
 *
 * Returns `true` exactly when the indicator chip should paint.
 */
fun decideScrollIndicatorVisibility(
    isScrolling: Boolean,
    hideAfterScrollStop: Boolean,
    contentIsScrollable: Boolean,
): Boolean {
    if (!contentIsScrollable) return false
    if (isScrolling) return true
    // isScrolling == false; we're either in the 1s grace window or past it.
    return !hideAfterScrollStop
}

/**
 * Computes which direction the (secondary) jump chip should point and
 * the index it would scroll to. Pure function.
 *
 * Contract:
 *   * Empty / single-item list (`lastIndex <= 0`): NONE.
 *   * At both edges (only possible with <= 1 item): NONE.
 *   * At top, not at bottom: BOTTOM.
 *   * At bottom, not at top: TOP.
 *   * Mid-list: BOTTOM (the dominant intent in long chat threads —
 *     "jump to latest").
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
 * Top-level JumpFab composable. Two halves:
 *
 *   1. The scroll indicator (round pulsing pill). Always shows while
 *      scrolling; hides 1s after scrolling stops.
 *   2. The jump chip (smaller, with directional arrow). Only shows
 *      when the list has settled (`!isScrolling`) AND the user is off
 *      one of the edges (i.e., there's room to jump either direction).
 *
 * The two halves are independently toggleable via [showIndicator] and
 * [showJumpChip] for tests + non-overlay callers.
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

    // Single LaunchedEffect keyed on the inputs the timer depends on.
    // When scrolling starts, this coroutine restarts and cancels the
    // previous hide timer. When scrolling stops, it starts a new timer
    // that flips hideAfterScrollStop = true after the grace window.
    var hideAfterScrollStop by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, contentIsScrollable) {
        if (!contentIsScrollable) {
            hideAfterScrollStop = true
            return@LaunchedEffect
        }
        if (isScrolling) {
            hideAfterScrollStop = false
            return@LaunchedEffect
        }
        // Scrolling just stopped. Start the grace timer.
        hideAfterScrollStop = false
        delay(hideDelayMs)
        hideAfterScrollStop = true
    }
    val indicatorVisible = showIndicator && decideScrollIndicatorVisibility(
        isScrolling = isScrolling,
        hideAfterScrollStop = hideAfterScrollStop,
        contentIsScrollable = contentIsScrollable,
    )

    // Secondary jump chip — only when settled, off an edge.
    val jumpTarget = if (showJumpChip) {
        decideJumpTarget(
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            lastIndex = lastIndex,
        )
    } else JumpTarget.NONE
    val jumpingToBottom = jumpTarget == JumpTarget.BOTTOM
    val chipVisible = jumpTarget != JumpTarget.NONE && !isScrolling

    val palette = LocalHermexPalette.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(
            visible = indicatorVisible,
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(durationMillis = hideDelayMs.toInt())),
        ) {
            ScrollIndicatorPill(isScrolling = isScrolling)
        }
        AnimatedVisibility(
            visible = chipVisible,
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            JumpChip(
                jumpingToBottom = jumpingToBottom,
                onClick = {
                    val target = if (jumpingToBottom) lastIndex else 0
                    onScrollToIndex(target)
                },
                palette = palette,
            )
        }
    }
}

@Composable
private fun ScrollIndicatorPill(isScrolling: Boolean) {
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.accent.copy(alpha = if (isScrolling) 1f else 0.85f),
        contentColor = Color.White,
        shape = CircleShape,
        shadowElevation = if (isScrolling) 10.dp else 4.dp,
        modifier = Modifier
            .size(40.dp)
            .testTag("jumpFab.scrollIndicator"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Scrolling",
                modifier = Modifier.padding(8.dp),
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
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
