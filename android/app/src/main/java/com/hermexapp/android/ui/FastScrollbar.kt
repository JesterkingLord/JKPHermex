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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Wave 9 / 9.5 (2026-07-24) — FastScrollbar for `LazyColumn`.
 *
 * Improvements over Wave 1 (still true):
 *   * Wider hit zone (40 dp, was 28) so the user can grab the bar without
 *     aiming for the thumb.
 *   * The thumb itself widens while dragging (8 dp → 12 dp).
 *   * A stationary tap on the bar snaps the list to that fraction, so the
 *     bar never feels "dead".
 *
 * Wave 9.5 (2026-07-24) — the position math is now honest about item
 * heights.
 *
 *   The 9.0 build used the formula
 *
 *       (firstVisibleIndex + firstVisibleScrollOffsetPx / estimatedItemHeightPx)
 *           / (itemCount - 1)
 *
 *   This was wrong whenever the chat timeline contained tall assistant
 *   messages (~300-400px each) because the *actual* item height was much
 *   greater than the hard-coded 96px estimate. The thumb would end up
 *   near the middle of the bar even though the list had scrolled far
 *   past one or two huge messages. The user reported: "scroll bar handle
 *   does not reflect the correct position of where we are, it's always
 *   stuck in the middle."
 *
 * New formula measures **real** scroll progress using the LazyListState's
 * own offsets and sum of measured item heights. It works correctly when:
 *   * Items have wildly different heights (a short user message next to a
 *     multi-paragraph assistant reply).
 *   * The list is short enough that the thumb covers >70% of the bar.
 *
 * The trade-off: on the first frame after a list mutation, the visible-
 * items map may not yet include items beyond the first; the helper falls
 * back to a per-item-rate estimate so the thumb is never pinned.
 */
@Composable
fun FastScrollbar(
    itemCount: Int,
    firstVisibleIndex: Int,
    firstVisibleScrollOffsetPx: Int = 0,
    estimatedItemHeightPx: Int = 56,
    totalContentHeightPx: Int = 0,
    visibleItemsHeightPx: Int = 0,
    visibleItemsFirstOffsetPx: Int = 0,
    visibleItemsLastBottomPx: Int = 0,
    threshold: Int = 20,
    letterIndex: Map<Char, Int> = emptyMap(),
    onScrollToIndex: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (itemCount <= threshold) return
    val palette = LocalHermexPalette.current

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val sortedLetters = remember(letterIndex) {
        letterIndex.keys.sorted().toList()
    }

    val thumbFraction: Float = if (dragging) {
        dragFraction
    } else {
        computeScrollFraction(
            firstVisibleIndex = firstVisibleIndex,
            firstVisibleScrollOffsetPx = firstVisibleScrollOffsetPx,
            estimatedItemHeightPx = estimatedItemHeightPx,
            totalContentHeightPx = totalContentHeightPx,
            visibleItemsHeightPx = visibleItemsHeightPx,
            visibleItemsFirstOffsetPx = visibleItemsFirstOffsetPx,
            visibleItemsLastBottomPx = visibleItemsLastBottomPx,
            itemCount = itemCount,
        )
    }

    val snappedLetter: Char? = remember(thumbFraction, sortedLetters) {
        if (sortedLetters.isEmpty()) null
        else {
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
                            if (pos == null) continue
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
                                val change = event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                val f = (change.position.y / size.height.toFloat().coerceAtLeast(1f))
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
 * fraction `[0, 1]` from real LazyColumn layout data.
 *
 * Tier 1 (preferred): when [totalContentHeightPx] (sum of all rendered
 * item heights, or the LazyListState's reported total) is non-zero, use
 * [visibleItemsFirstOffsetPx] / [totalContentHeightPx]. This is the
 * correct formula regardless of item-height variance.
 *
 * Tier 2 (fallback for the first frame after a list mutation): use
 * the per-item-rate formula `(firstVisibleIndex +
 * firstVisibleScrollOffsetPx / estimatedItemHeightPx) / (itemCount -
 * 1)` clamped to `[0, 1]`. This is what the old build did, kept as a
 * fallback so the thumb never freezes when real measurements haven't
 * landed yet.
 *
 * Returns 0f when the list is empty or when the inputs are degenerate.
 */
fun computeScrollFraction(
    firstVisibleIndex: Int,
    firstVisibleScrollOffsetPx: Int,
    estimatedItemHeightPx: Int,
    totalContentHeightPx: Int,
    visibleItemsHeightPx: Int,
    visibleItemsFirstOffsetPx: Int,
    visibleItemsLastBottomPx: Int,
    itemCount: Int,
): Float {
    if (itemCount <= 0) return 0f

    val first = firstVisibleIndex.coerceAtLeast(0)
    val offset = firstVisibleScrollOffsetPx.coerceAtLeast(0)

    // Tier 1: real measurements available. Use the actual top-edge of
    // the first visible item relative to the total content height.
    if (totalContentHeightPx > 0) {
        val numerator = (visibleItemsFirstOffsetPx - offset).coerceAtLeast(0)
        val frac = numerator.toFloat() / totalContentHeightPx.toFloat()
        return frac.coerceIn(0f, 1f)
    }

    // Tier 2: fallback per-item-rate estimate. Preserves the old
    // behavior so we don't regress pre-Wave-9.5 chats.
    val estHeight = max(1, estimatedItemHeightPx).toFloat()
    val fractional = first + offset / estHeight
    val denom = (itemCount - 1).coerceAtLeast(1).toFloat()
    return (fractional / denom).coerceIn(0f, 1f)
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
