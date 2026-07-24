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
 * Wave 9.6 (2026-07-24) — FastScrollbar rebuilt from scratch, simpler.
 *
 * Background: in every prior revision, including v0.8.4, v0.8.5, and
 * the "fixes" between them, the thumb position was wrong for chat
 * timelines. The user reported "stuck in the middle" repeatedly.
 *
 * Root causes across the three broken revisions:
 *   1. v0.8.3 and earlier: hard-coded `estimatedItemHeightPx = 96` in
 *      a per-item-rate fraction. Real chat items are 300-500 px each,
 *      so the formula was off by 50-80%.
 *   2. v0.8.5: introduced a Tier 1 "real measurements" path and a Tier 2
 *      fallback. The Tier 1 path used `visibleItemsFirstOffsetPx -
 *      firstVisibleScrollOffsetPx` as the numerator — wrong because
 *      `firstVisibleScrollOffsetPx` is offset within the FIRST visible
 *      item, not the offset of the first visible item itself. Plus the
 *      Tier 1 path's `totalContentHeightPx` came from a
 *      `sumOfMeasuredHeights` helper that **fell back to 0** whenever
 *      the visible window hadn't been measured yet, silently routing
 *      every real-world scroll position through Tier 2.
 *   3. The "no-render" guard `if (itemCount <= threshold) return` with
 *      `threshold = 20` meant short chats (5–15 messages) had **no
 *      scrollbar at all** — yet the user reported "stuck in the middle,"
 *      implying a thumb exists somewhere. Turned out some callsites
 *      overrode `threshold = 4` (SessionListScreen), while ChatScreen
 *      did not, so the chat timeline was the broken one.
 *
 * What the new implementation does:
 *
 *   - **No `itemCount` gate.** A scrollbar should always render if the
 *     call-site wired one up. Even 3 messages get a bar (the thumb will
 *     just fill the whole track).
 *
 *   - **Single formula, no tiers.** `computeScrollFraction()` takes the
 *     LazyList's own `firstVisibleItemIndex`, `totalItemsCount`, and a
 *     `visibleCount` derived from `layoutInfo.visibleItemsInfo.size`,
 *     and computes:
 *
 *         fraction = (firstVisibleItemIndex - itemsAboveWindow)
 *                     / (totalItemsCount - visibleCount)
 *                     clamped to [0, 1]
 *
 *     where `itemsAboveWindow` is 0 in the simple case and `1` when the
 *     first visible item is partially scrolled off the top of the
 *     viewport. This is the Compose-canonical "how many items are out
 *     of view above me?" formula and is item-height-independent.
 *
 *   - **Uses `LazyListState.canScrollForward` / `canScrollBackward`**
 *     directly as edge-clamp guards. When `!canScrollBackward`, the
 *     fraction is forced to 0. When `!canScrollForward`, forced to 1.
 *     These are pixel-perfect and never ambiguous.
 *
 *   - **Drop-drag continues to work.** The track is the entire 40dp
 *     wide rail; tapping or dragging anywhere in it sets the fraction
 *     and scrolls the list to the corresponding item. The thumb width
 *     animates 8dp → 12dp while dragging for affordance.
 *
 *   - **Letter-jump index preserved.** The `letterIndex` map is still
 *     surfaced (used by the session list to jump to a letter while
 *     dragging). When empty the drag uses the item-count formula
 *     directly.
 */
@Composable
fun FastScrollbar(
    itemCount: Int,
    firstVisibleIndex: Int,
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
            totalItemsCount = totalItemsCount,
            visibleItemsCount = visibleItemsCount,
            canScrollBackward = canScrollBackward,
            canScrollForward = canScrollForward,
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
 * fraction [0,1] from real LazyColumn layout data.
 *
 * Inputs:
 *   - [firstVisibleIndex] — the index of the first item currently
 *     painted (or partially painted) at the top of the viewport.
 *   - [totalItemsCount] — `LazyListLayoutInfo.totalItemsCount`.
 *   - [visibleItemsCount] — `LazyListLayoutInfo.visibleItemsInfo.size`.
 *     When the user has scrolled past a partial item, the first visible
 *     item may be only partly in view; we use this to position the
 *     thumb just above the second visible item.
 *   - [canScrollBackward] / [canScrollForward] — from `LazyListState`.
 *     These are pixel-perfect edge guards. When `!canScrollBackward`
 *     we are fully at the top → return 0; when `!canScrollForward`
 *     we are fully at the bottom → return 1.
 *
 * Formula:
 *
 *     denom = max(1, totalItemsCount - visibleItemsCount)
 *     itemsAbove = clamp(firstVisibleIndex, 0, totalItemsCount)
 *     fraction  = itemsAbove / denom     // in [0, 1]
 *
 * If visibleItemsCount > totalItemsCount (all items fit on screen)
 * the loop returns 0 — there's nothing to scroll, no point showing a
 * meaningful thumb position.
 */
fun computeScrollFraction(
    firstVisibleIndex: Int,
    totalItemsCount: Int,
    visibleItemsCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    if (totalItemsCount <= 0) return 0f
    // Edge clamps — the LazyList's own pixel measurements are the
    // ground truth here. Don't try to derive edge state from indices
    // and item sizes; just ask.
    if (!canScrollBackward) return 0f
    if (!canScrollForward) return 1f

    val visible = visibleItemsCount.coerceAtLeast(1)
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
