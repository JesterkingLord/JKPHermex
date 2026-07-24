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
 * Wave 9 (2026-07-28) — JumpFab rewritten for **show-only-when-truly-off-edge**.
 *
 * Previously (Wave 2) the FAB appeared whenever the user was 5+ items from
 * either edge, but two failing cases pushed it back into view at unwanted
 * moments:
 *   * When the chat first opens the LazyList momentarily reports
 *     `firstVisibleItemIndex = 0` while hydrating content, which can
 *     transiently trigger the "jump-to-top" affordance while the user is
 *     effectively pinned at the bottom of a populated list.
 *   * After sending a message the list animates to the bottom, but during
 *     the animation `firstVisibleItemIndex` briefly steps through the
 *     intermediate indices — surfacing the top-jump FAB mid-animation.
 *
 * New rule: the FAB is hidden unless the user is genuinely **far** from the
 * relevant edge. Concretely:
 *   * Show ⤵ (jump-to-bottom) when there are at least [threshold] items
 *     between the *first* visible row and the *last* visible row AND the
 *     list tail is not on-screen.
 *   * Show ⤴ (jump-to-top) when there are at least [threshold] items between
 *     the first row and index 0 AND the list head is not on-screen.
 *
 * We also pass `firstVisibleIndex` + `lastVisibleIndex` (rather than only
 * `firstVisibleIndex`) so we can hide the FAB the moment the user is
 * effectively back at the bottom — even if hydration still claims the
 * first row is index 0.
 *
 * Animation: the FAB fades + scales in/out on `visible` so it never jars
 * the user during transient compositions.
 *
 * Tap behavior: when both directions could fit, the FAB picks the closest
 * edge — so returning from a small over-scroll snaps to the bottom, going
 * back to a long scroll snaps to the top. Tapping either way calls
 * [onScrollToIndex] with the resolved target.
 */
@Composable
fun JumpFab(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    lastIndex: Int,
    threshold: Int = 5,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lastIndex <= 0) return
    val palette = LocalHermexPalette.current

    // "At edge" only suppresses jumps TOWARDS that edge, not away from
    // it. The user can still want to jump to the opposite end — being
    // at the top of the list doesn't make a "jump to bottom" meaningless.
    val atTop = firstVisibleIndex == 0
    val atBottom = lastVisibleIndex >= lastIndex
    // Items the user can see (first row through last visible row). When
    // this covers most of the list, the list is too short to warrant a
    // jump button.
    val visibleCount = (lastVisibleIndex - firstVisibleIndex + 1).coerceAtLeast(1)
    val visibleFraction = visibleCount.toFloat() / (lastIndex + 1).coerceAtLeast(1)
    val listTooShortToMatter = visibleFraction > 0.7f
    val distanceFromTop = firstVisibleIndex
    val distanceFromBottom = (lastIndex - lastVisibleIndex).coerceAtLeast(0)

    val showJumpToBottom = !atBottom && !listTooShortToMatter &&
        distanceFromBottom >= threshold
    val showJumpToTop = !atTop && !listTooShortToMatter &&
        distanceFromTop >= threshold
    val visible = showJumpToBottom || showJumpToTop
    // Pick the closer edge for tap-targeting: closest edge wins. If the
    // user is equally far from both, default to bottom (jump-to-latest
    // is the more common intent).
    val jumpingToBottom = when {
        showJumpToBottom && showJumpToTop -> distanceFromBottom <= distanceFromTop
        showJumpToBottom -> true
        else -> false
    }

    AnimatedVisibility(
        visible = visible,
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
