package com.hermexapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
 * A Gmail-style right-edge scrollbar for a `LazyColumn`.
 *
 * Wave 1 of Excellence v1 (2026-07-27). Pure Compose — no new deps.
 *
 * Renders:
 *   * a 28 dp-wide transparent hit zone covering the right edge of the parent
 *   * a faint vertical track (2 dp wide, accent at 18% alpha)
 *   * a thumb (8 dp × 36 dp) painted at `firstVisibleIndex / (itemCount - 1)`
 *   * while dragging, a floating letter bubble appears to the left of the
 *     thumb (only when [letterIndex] is non-empty)
 *
 * Drag gestures:
 *   * On release, [onScrollToIndex] is invoked with the resolved item index.
 *     The caller is responsible for actually moving the list there (we keep
 *     this composable pure so it composes with any `LazyListState`).
 *
 * Hidden when `itemCount <= threshold` so a 5-session list stays clean.
 *
 * @param itemCount total items in the list (excluding header/footer rows)
 * @param firstVisibleIndex index of the first fully-or-partially-visible row
 * @param firstVisibleScrollOffsetPx pixels of the first visible row clipped
 *   at the top — positions the thumb smoothly between rows
 * @param estimatedItemHeightPx rough height of one row; only used to convert
 *   `firstVisibleScrollOffsetPx` into a fraction-of-row offset
 * @param threshold lists with fewer items render no scrollbar
 * @param letterIndex for letter-jump lists, `letter -> firstVisibleIndex`
 *   mapping built by the caller (e.g. derived from session titles). When
 *   empty, drag end scrolls proportional to fraction.
 * @param onScrollToIndex callback invoked when the user releases the drag;
 *   the caller should call `listState.scrollToItem(target)`.
 */
@Composable
fun FastScrollbar(
    itemCount: Int,
    firstVisibleIndex: Int,
    firstVisibleScrollOffsetPx: Int = 0,
    estimatedItemHeightPx: Int = 56,
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
        if (itemCount <= 1) 0f
        else {
            val fractional = firstVisibleIndex +
                firstVisibleScrollOffsetPx.toFloat() /
                    max(1, estimatedItemHeightPx).toFloat()
            (fractional / (itemCount - 1).toFloat()).coerceIn(0f, 1f)
        }
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

    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .testTag("fastScrollbar"),
        contentAlignment = Alignment.Center,
    ) {
        // Track — a faint vertical line.
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .background(palette.accent.copy(alpha = 0.18f), RoundedCornerShape(1.dp)),
        )

        // Thumb — placed in the parent's top-left, then translated down by a
        // fraction of the parent's available height. Modifier.layout{} gives us
        // the parent's constraints in pixels without forcing us to switch to a
        // full custom Layout.
        val thumbTravelDpFraction: (Float) -> Dp = { frac ->
            // We can't see the parent height during the measurement pass, so we
            // pass the fraction through and let the offset be a *percentage*
            // of the parent's available travel. The post-layout happens below.
            0.dp // dummy; real translation comes from .fillProgress below
        }
        Spacer(
            modifier = Modifier
                .size(width = 8.dp, height = 36.dp)
                .fillProgress(fraction = thumbFraction)
                .background(palette.accent, RoundedCornerShape(4.dp)),
        )

        // Floating letter bubble while dragging (only when letterIndex set).
        if (dragging && snappedLetter != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .padding(end = 56.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.pillBackground),
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

        // Gesture layer — overlay the full hit zone.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(itemCount, letterIndex) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.y / size.height.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val totalHeight = size.height.toFloat().coerceAtLeast(1f)
                            dragFraction = (dragFraction + dragAmount / totalHeight)
                                .coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            val target = resolveTargetIndex(
                                fraction = dragFraction,
                                itemCount = itemCount,
                                letterIndex = letterIndex,
                            )
                            onScrollToIndex(target)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        )
    }
}

/**
 * Modifier that translates the child by a *fraction* of the parent's
 * available travel. Uses `Modifier.layout` to read the parent's max height
 * at measurement time and assign a y-offset.
 *
 * The fraction is `[0, 1]` where 0 = top, 1 = bottom.
 */
private fun Modifier.fillProgress(fraction: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val parentHeight = constraints.maxHeight
    val thumbHeight = placeable.height
    // Travel = parent height - thumb height, then apply the fraction.
    val travel = (parentHeight - thumbHeight).coerceAtLeast(0)
    val yOffset = (travel * fraction.coerceIn(0f, 1f)).roundToInt()
    layout(placeable.width, parentHeight) {
        placeable.placeRelative(0, yOffset)
    }
}

/** Maps a drag fraction in `[0, 1]` to a concrete LazyColumn index. */
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
