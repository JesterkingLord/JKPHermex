package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Wave 9 / 9.5 (2026-07-24) — JumpFab rewritten for **show-only-when-truly-off-edge**.
 *
 * Two failing cases the previous build had:
 *   1. Hydration flash — the LazyList momentarily reports
 *      `firstVisibleItemIndex = 0` while hydrating content, which used
 *      to transiently trigger the "jump-to-top" affordance while the
 *      user was effectively pinned at the bottom of a populated list.
 *   2. Hardcoded thresholds — a chat timeline where every assistant
 *      message is multi-paragraph (~300px each) made the "≥70% visible
 *      → hide" rule frequently fire when the list genuinely was scrollable,
 *      and not fire when a 1-item list was fully on-screen.
 *
 * The decision now lives in [decideJumpFabVisibility] (pure function).
 * Both this composable and the regression test (JumpFabVisibilityTest)
 * call it, so a code-path drift between test and production is impossible.
 *
 * Defensive guards (Wave 9.5 — added after the screenshot "JumpFab always
 * visible" bug):
 *   - `isScrolling` parameter — `LazyListState.isScrollInProgress`. If the
 *     list is animating to bottom (e.g., after send), the FAB is suppressed
 *     to avoid mid-animation pops. Defaults to false for tests.
 *   - `lastIndex` clamp — the visible-fraction denominator uses
 *     `(lastIndex + 1).coerceAtLeast(1)` so an empty list doesn't divide
 *     by zero, AND a single-item list (lastIndex=0) still computes
 *     correctly (1+1=2, 1/2=0.5 < 0.7 → shown only if not at-bottom).
 *   - `atBottom`'s `lastVisibleIndex >= lastIndex` is the relaxed
 *     convention used here; the per-direction distance-from-edge check
 *     handles ties.
 */
@Composable
fun JumpFab(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
    threshold: Int = 5,
    isScrolling: Boolean = false,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHermexPalette.current
    val direction = decideJumpFabVisibility(
        firstVisibleIndex = firstVisibleIndex,
        lastVisibleIndex = lastVisibleIndex,
        lastIndex = lastIndex,
        threshold = threshold,
        isScrolling = isScrolling,
    )
    if (direction == JumpFabDirection.NONE) return

    val jumpingToBottom = direction == JumpFabDirection.BOTTOM

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Surface(
            color = palette.pillBackground,
            contentColor = palette.pillForeground,
            shape = CircleShape,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(48.dp)
                .testTag(if (jumpingToBottom) "jumpFab.bottom" else "jumpFab.top")
                .clickable {
                    val target = if (jumpingToBottom) lastIndex else 0
                    onScrollToIndex(target)
                },
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
}

/**
 * Three directions the FAB can take. NONE means the composable returns
 * early and renders nothing.
 */
enum class JumpFabDirection { NONE, BOTTOM, TOP }

/**
 * Pure decision function. Used by both [JumpFab] (production) and
 * [com.hermexapp.android.ui.JumpFabVisibilityTest] (unit tests). Update
 * both copies together — calling this single function from both sides
 * makes drift impossible. Critical: this is the only place that owns
 * the visibility contract.
 *
 * Returns [JumpFabDirection.BOTTOM] when the user is far from the bottom
 * and the tail isn't on-screen; [JumpFabDirection.TOP] when far from the
 * top and the head isn't on-screen; [JumpFabDirection.NONE] when neither
 * jump would save the user meaningful scrolling OR the list is mid-scroll.
 */
fun decideJumpFabVisibility(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
    threshold: Int = 5,
    isScrolling: Boolean = false,
    listTooShortFraction: Float = 0.7f,
): JumpFabDirection {
    // Empty list: no FAB ever.
    if (lastIndex <= 0) return JumpFabDirection.NONE
    // Mid-scroll: suppress. Calling site knows the LazyListState
    // isScrolling; during animation, firstVisibleItemIndex briefly
    // steps through intermediate values which would cause a flicker.
    if (isScrolling) return JumpFabDirection.NONE

    // Defensive: handle weird input the LazyList could feed us when the
    // message list briefly contains an unusual number of entries.
    val first = firstVisibleIndex.coerceAtLeast(0)
    val last = lastVisibleIndex.coerceAtLeast(0)
    val total = (lastIndex + 1).coerceAtLeast(1)

    val atTop = first == 0
    val atBottom = last >= lastIndex
    // visibleCount must include BOTH endpoints in [first, last] inclusive.
    val visibleCount = ((last - first) + 1).coerceAtLeast(1)
    val visibleFraction = visibleCount.toFloat() / total
    val listTooShort = visibleFraction >= listTooShortFraction

    // Distances from each edge, in item-count units.
    val distanceFromTop = first
    val distanceFromBottom = (lastIndex - last).coerceAtLeast(0)

    val showBottom = !atBottom && !listTooShort && distanceFromBottom >= threshold
    val showTop = !atTop && !listTooShort && distanceFromTop >= threshold

    if (!showBottom && !showTop) return JumpFabDirection.NONE

    // When both qualify, pick the closer edge for tap-targeting. Equal-
    // distance ties default to bottom (jump-to-latest is the more
    // common intent).
    return if (showBottom && showTop) {
        if (distanceFromBottom <= distanceFromTop) JumpFabDirection.BOTTOM
        else JumpFabDirection.TOP
    } else if (showBottom) {
        JumpFabDirection.BOTTOM
    } else {
        JumpFabDirection.TOP
    }
}
