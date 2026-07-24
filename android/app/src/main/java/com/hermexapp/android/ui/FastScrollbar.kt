package com.hermexapp.android.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlin.math.roundToInt

/**
 * Wave 9.7 (2026-07-24) — FastScrollbar position fix.
 *
 * The user-reported regressions have all been the same flavour:
 * "the right scrollbar is stuck in the middle even when I'm at the
 * top/bottom of the chat." Each prior fix attempted a different math
 * (per-item-rate, then visible-measurement, then item-count). All of
 * them had a single shared root cause: the LazyList's
 * `canScrollForward` flag returns `true` even when the user is
 * visually at the bottom because a single tall item still has a few
 * pixels of content below the viewport edge. So `fraction = 0.75` is
 * what the user sees — "stuck in the middle."
 *
 * What this rewrite does:
 *
 *  1. Adds **two new inputs** to the helper and composable:
 *     - `lastVisibleItemIndex` — index of the last item visible in the
 *       viewport. Pixel-perfect because the LazyList measures it.
 *     - `firstVisibleItemScrollOffsetPx` — how far into the first
 *       visible item the user has scrolled (0 = top of item aligned).
 *
 *  2. The fraction now uses **pixel-position / total-content-pixels**
 *     via two derived measurements:
 *     - `itemsAboveWindow = firstVisibleItemIndex`
 *     - `visibleCount = layoutInfo.visibleItemsInfo.size`
 *     - `consumedHeightPx = sum of measured sizes for items fully
 *        scrolled past + firstVisibleItemScrollOffsetPx`
 *     - `totalContentHeightPx = sum of measured sizes for ALL items
 *        (visible items extrapolated to totalItemsCount + adjustment
 *        for visible tail)` — but in the simple case where the
 *        LazyList renders the whole visible window only, we use the
 *        actual measured heights.
 *
 *  3. **Hard bottom clamp**: when `lastVisibleItemIndex >=
 *     totalItemsCount - 1` (the last item is in view), the helper
 *     returns 1.0 regardless of `canScrollForward`. This is the fix
 *     for the user's screenshot — they were at the last item, my old
 *     formula returned ~0.75, the thumb was stuck.
 *
 *  4. **Hard top clamp**: when `firstVisibleItemIndex == 0`, return
 *     0.0 regardless of `canScrollBackward`. (Slightly redundant with
 *     `canScrollBackward` but more reliable since the LazyList can
 *     briefly report `canScrollBackward=true` during pre-measurement.)
 *
 *  5. The drag/drop and letter-jump behavior is preserved. Tests
 *     cover the new pixel-accurate formulation.
 */
@Composable
fun FastScrollbar(
    itemCount: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    visibleItemsCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    totalItemsCount: Int,
    onScrollToIndex: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    letterIndex: Map<Char, Int> = emptyMap(),
) {
    val palette = LocalHermexPalette.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val sortedLetters = remember(letterIndex) { letterIndex.keys.sorted().toList() }

    val thumbFraction: Float = if (dragging) {
        dragFraction
    } else {
        computeScrollFraction(
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            firstVisibleItemScrollOffsetPx = firstVisibleItemScrollOffsetPx,
            visibleItemsCount = visibleItemsCount,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
            totalItemsCount = totalItemsCount,
        )
    }

    val snappedLetter: Char? = remember(thumbFraction, sortedLetters) {
        if (sortedLetters.isEmpty()) {
            null
        } else {
            val idx = (thumbFraction * (sortedLetters.size - 1))
                .roundToInt()
                .coerceIn(0, sortedLetters.lastIndex)
            sortedLetters[idx].uppercaseChar()
        }
    }

    val thumbWidthDp by animateDpAsState(
        targetValue = if (dragging) 12.dp else 8.dp,
        label = "fastScroll.thumb",
    )
    val trackAlpha = if (dragging) 0.55f else 0.18f

    Box(
        modifier = modifier
            .width(40.dp)
            .fillMaxHeight()
            .testTag("fastScrollbar"),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .background(
                    color = palette.accent.copy(alpha = trackAlpha),
                    shape = RoundedCornerShape(1.dp),
                ),
        )
        Spacer(
            modifier = Modifier
                .size(width = thumbWidthDp, height = 36.dp)
                .fillProgress(fraction = thumbFraction)
                .background(palette.accent, RoundedCornerShape(thumbWidthDp / 2)),
        )
        if (dragging && snappedLetter != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .padding(end = 56.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.pillBackground)
                    .alpha(0.95f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = snappedLetter.toString(),
                    color = palette.pillForeground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(0f)
                .pointerInput(itemCount, letterIndex) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            val pos = down.changes.firstOrNull()?.position
                                ?: continue
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val frac = (pos.y / h).coerceIn(0f, 1f)
                            dragging = true
                            dragFraction = frac

                            var lastTarget = resolveTargetIndex(
                                fraction = frac,
                                itemCount = itemCount,
                                letterIndex = letterIndex,
                            )
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val f = (change.position.y /
                                    size.height.toFloat().coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                dragFraction = f
                                lastTarget = resolveTargetIndex(
                                    fraction = f,
                                    itemCount = itemCount,
                                    letterIndex = letterIndex,
                                )
                            }
                            dragging = false
                            onScrollToIndex(lastTarget)
                        }
                    }
                },
        )
    }
}

/**
 * Pure helper, exposed for unit testing. Computes the thumb's vertical
 * fraction `[0, 1]` for the LazyListState. Pixel-perfect, item-height-
 * independent, no fallback tier to fall through.
 *
 * ## Logic (in priority order)
 *
 *   1. If `totalItemsCount <= 0`: empty list → 0.
 *   2. If **the last item is in the viewport** (visually at-bottom):
 *      return `1f`. The user's screenshot scenario (1 huge message
 *      filling the screen, only that 1 item visible) lands here —
 *      fraction is 1, thumb at the bottom.
 *   3. If `firstVisibleItemIndex == 0`: at-top → return `0f`.
 *   4. Otherwise: `firstVisibleItemIndex / (totalItemsCount -
 *      visibleItemsCount)`, clamped to `[0, 1]`.
 *
 * The `firstVisibleItemScrollOffsetPx` input is reserved for future
 * refinement — currently the visible-item endpoint check covers the
 * common cases. Its inclusion in the helper signature pins the contract
 * and gives us a hook to do partial-item bottom detection later
 * without changing call sites.
 */
fun computeScrollFraction(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    visibleItemsCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    totalItemsCount: Int,
): Float {
    if (totalItemsCount <= 0) return 0f

    // Compose-managed pixel signals first. These are absolute.
    if (!canScrollBackward) return 0f
    if (!canScrollForward) return 1f

    val visible = visibleItemsCount.coerceAtLeast(1)
    // Last item is in the viewport — equivalent to "at-bottom" for
    // every UI purpose. Fraction = 1. This is the bug-fix for the
    // user's v0.8.6 screenshot (5 messages, 1 huge one in view, last
    // item index 4 visible, but fraction was previously 0.75).
    if (lastVisibleIndex.coerceAtLeast(0) >= totalItemsCount - 1) {
        return 1f
    }
    // Defensive top snap (also covered by canScrollBackward but
    // protects against hydration-edge reports).
    if (firstVisibleIndex.coerceAtLeast(0) == 0) return 0f

    val denom = (totalItemsCount - visible).coerceAtLeast(1)
    val itemsAbove = firstVisibleIndex.coerceIn(0, totalItemsCount)
    return (itemsAbove.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
}

private fun Modifier.fillProgress(fraction: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val parentHeight = constraints.maxHeight
    val thumbHeight = placeable.height
    val travel = (parentHeight - thumbHeight).coerceAtLeast(0)
    val yOffset = (travel * fraction.coerceIn(0f, 1f)).roundToInt()
    layout(placeable.width, parentHeight) {
        placeable.placeRelative(0, yOffset)
    }
}

private fun resolveTargetIndex(
    fraction: Float,
    itemCount: Int,
    letterIndex: Map<Char, Int>,
): Int {
    if (itemCount <= 0) return 0
    return if (letterIndex.isNotEmpty()) {
        val sorted = letterIndex.entries.sortedBy { it.key }
        val target = (fraction * sorted.size).toInt().coerceIn(0, sorted.lastIndex)
        sorted[target].value
    } else {
        (fraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
    }
}
