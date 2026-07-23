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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * Wave 2 (2026-07-27) — JumpFab: a circular FAB that appears when the
 * user has scrolled *away* from the bottom (or top) of a scrollable
 * list, and snaps the list back to the nearest end with a tap. The
 * icon direction reflects the *currently visible* edge:
 *
 *   * ⤵ when the user is scrolled away from the bottom (most common
 *     case — "jump to latest").
 *   * ⤴ when the user is scrolled below the last item (over-scroll);
 *     tapping snaps to the *top* (index 0).
 *
 * The FAB fades and scales in/out smoothly so it doesn't fight the
 * user's attention. The threshold defaults to 5 entries; with fewer
 * non-edge items, the FAB stays hidden (no UX value).
 *
 * @param firstVisibleIndex the LazyListState's first visible index.
 * @param lastIndex the index of the final entry in the list. When the
 *   list is empty, the FAB stays hidden (caller's responsibility —
 *   we render nothing rather than a zombie button).
 * @param distanceFromBottom computed once by the caller as
 *   `lastIndex - firstVisibleIndex`.
 * @param threshold minimum number of items the user has to be away
 *   from an edge before the FAB shows. Default 5.
 * @param onScrollToIndex callback; the caller passes a lambda that
 *   invokes LazyListState.animateScrollToItem(target).
 */
@Composable
fun JumpFab(
    firstVisibleIndex: Int,
    lastIndex: Int,
    distanceFromBottom: Int,
    threshold: Int = 5,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lastIndex <= 0) return
    val palette = LocalHermexPalette.current
    // Decide which direction the user is "away" from:
    //   * Positive distanceFromBottom -> user scrolled up away from newest
    //   * firstVisibleIndex is large enough to mean "can jump to top"
    val showJumpToBottom = distanceFromBottom >= threshold
    val showJumpToTop = firstVisibleIndex >= threshold
    val visible = showJumpToBottom || showJumpToTop
    val jumpingToBottom = showJumpToBottom && (!showJumpToTop || distanceFromBottom >= firstVisibleIndex)

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
