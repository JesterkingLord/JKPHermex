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
 * Wave 9 (2026-07-28) — FastScrollbar for `LazyColumn`.
 *
 * Improvements over Wave 1:
 *   * Wider hit zone (40 dp, was 28) so users can grab the bar without
 *     aiming for the thumb — the entire right edge of the timeline
 *     responds to drag, and the track visibly fades in on touch so the
 *     user gets immediate feedback.
 *   * The thumb itself widens while dragging (8 dp → 12 dp) for a
 *     familiar "I found it" feel borrowed from iOS / Gmail.
 *   * When the user taps anywhere along the bar (even without dragging),
 *     we snap to that fraction. Without this, touch-down on a stationary
 *     finger would do nothing — the previous build relied on a drag-start
 *     gesture which requires actual movement, leaving the bar feeling
 *     "dead".
 *
 * Pure Compose — no new dependencies. Caller supplies a `LazyListState`
 * via [onScrollToIndex] so this composable stays decoupled from scroll
 * implementation.
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

    // The thumb grows slightly while dragging so the user can see they
    // grabbed the right edge — same idea as iOS / Gmail.
    val thumbWidthDp by animateDpAsState(
        targetValue = if (dragging) 12.dp else 8.dp,
        label = "fastScroll.thumb",
    )
    val trackAlpha = if (dragging) 0.55f else 0.18f

    Box(
        modifier = modifier
            .width(40.dp)        // wave 9: 28 → 40 so users can grab the bar
            .fillMaxHeight()
            .testTag("fastScrollbar"),
        contentAlignment = Alignment.Center,
    ) {
        // Track — a faint vertical line that brightens while the user is
        // touching it.
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

        // Thumb.
        Spacer(
            modifier = Modifier
                .size(width = thumbWidthDp, height = 36.dp)
                .fillProgress(fraction = thumbFraction)
                .background(palette.accent, RoundedCornerShape(thumbWidthDp / 2)),
        )

        // Floating letter bubble while dragging (only when letterIndex set).
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

        // Gesture layer. Wave 9: drag-along-the-right-edge to scrub the
        // list. We use awaitPointerEventScope (always available in
        // PointerInputScope) so we don't depend on the experimental
        // `awaitEachGesture` helper.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .alpha(0f) // invisible but consumes gestures
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

                            // Track move events until pointer is up or
                            // cancelled. We commit the final target only
                            // when the user lifts their finger.
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
 * Modifier that translates the child by a *fraction* of the parent's
 * available travel. Uses `Modifier.layout` to read the parent's max height
 * at measurement time and assign a y-offset.
 */
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
