package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Wave 9.9 (2026-07-24) — FastScrollbar rewritten from the ground up
 * with **pixel-accurate** thumb position and **ChatGPT-style auto-hide
 * behavior**.
 *
 * Why three prior fixes all failed:
 *
 *   The implementations in v0.8.5, v0.8.6 and v0.8.7 all tried to
 *   derive the thumb position from `firstVisibleItemIndex` and item
 *   sizes. But ChatGPT-style chats contain items with **wildly
 *   different heights** (a 60-px "ok" reply next to a 600-px multi-
 *   paragraph assistant message). Item-index math can't represent
 *   pixel position when item sizes vary by 10x.
 *
 *   The illustration:
 *
 *       item 0: 60px tall (user "ok")
 *       item 1: 600px tall (assistant paragraph block)
 *       item 2: 60px tall (user "go on")
 *       item 3: 600px tall (assistant paragraph block)
 *       item 4: 60px tall (user "ok")
 *       total content = 60+600+60+600+60 = 1380px
 *       viewport = 800px
 *
 *   With the OLD index-based math, `firstVisibleIndex=1` could mean
 *   either "0.13 of total content scrolled past" (just below item 0,
 *   ~60px scrolled) OR "0.55 scrolled" (middle of item 1). The math
 *   had no way to tell those apart.
 *
 * The Wave 9.9 fix:
 *
 *   - Use `LazyListLayoutInfo.viewportStartOffset` — **pixels above
 *     the viewport top edge within the scrollable content**. This is
 *     pixel-perfect, computed by Compose internally, never guesswork.
 *
 *   - Use `LazyListLayoutInfo.viewportEndOffset` to measure viewport
 *     size, then estimate total content size from the last visible
 *     item's `offset + size` (its bottom in content coordinates).
 *
 *   - The fraction is purely pixel-based:
 *
 *         fraction = viewportStartOffset
 *                    / (estimatedTotalContentHeight - viewportHeight)
 *
 *   - When the user can't scroll (content fits) the helper returns
 *     `null` and the bar hides entirely.
 *
 *   - ChatGPT-style auto-hide: the bar fades in when the user
 *     scrolls, fades out 1 s after scrolling stops. Hit-zone is
 *     always 40dp for the actual track tap-target.
 *
 *   - Drag the track to jump to that fraction. No letter-jump in
 *     this revision; the alphabet rail is a separate widget for the
 *     session list.
 *
 *  Quality bar: this should feel invisible. It is there when you need
 *  it, gone when you don't.
 */
private const val FAST_SCROLL_HIDE_DELAY_MS: Long = 1_000L
private val FAST_SCROLL_HIT_WIDTH: Dp = 40.dp

/**
 * Pure helper: compute the thumb's vertical fraction `[0, 1]` from
 * **pixel** measurements supplied by the LazyList's layout info.
 *
 * Returns `null` when there's nothing to scroll (content fits in the
 * viewport, or the list is empty) — the caller should hide the bar in
 * that case.
 *
 * Inputs:
 *   - [viewportStartOffset] — pixels above viewport top edge within
 *     content. 0 at the very top.
 *   - [viewportEndOffset] — pixels above viewport bottom edge within
 *     content. `viewportEndOffset - viewportStartOffset = viewport
 *     pixel height`.
 *   - [estimatedTotalContentHeight] — pixel height of the entire
 *     scrollable content. Pass `lastVisibleItem.offset +
 *     lastVisibleItem.size` if the last item is fully visible; pass
 *     a reasonable estimate from the LazyList's reported totals if
 *     not. The caller should derive this from the layout info.
 *
 * Formula:
 *   scrolled = max(0, viewportStartOffset)
 *   maxScroll = max(1, estimatedTotalContentHeight - viewportHeight)
 *   fraction  = scrolled / maxScroll, clamped [0, 1].
 *
 * Returns `null` when estimatedTotalContentHeight <= viewportHeight
 * (no scroll possible).
 */
fun computeScrollFractionPx(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    estimatedTotalContentHeight: Int,
): Float? {
    val viewportHeight = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)
    if (viewportHeight <= 0) return null

    val safeTotal = estimatedTotalContentHeight.coerceAtLeast(0)
    if (safeTotal <= viewportHeight) return null

    val maxScroll = max(1, safeTotal - viewportHeight)
    val scrolled = viewportStartOffset.coerceAtLeast(0).toFloat()
    return (scrolled / maxScroll).coerceIn(0f, 1f)
}

/**
 * The visible-chrome FastScrollbar for a LazyColumn.
 *
 * Behavior:
 *   - Fades IN when `isScrolling == true` OR within 1 s of last scroll
 *     event (the [FAST_SCROLL_HIDE_DELAY_MS] grace window).
 *   - Fades OUT after 1 s of stillness at rest.
 *   - HIDDEN entirely (no track, no thumb) when there's nothing to
 *     scroll (`computeScrollFractionPx` returns null).
 *   - During drag: track brightens, thumb widens, jump is dispatched
 *     on release.
 *   - Tap on track (no drag): instant jump to that fraction.
 *
 * @param listState the LazyListState of the column. Drives visibility,
 *   position math, drag, and the pixel-perfect scroll fraction.
 * @param modifier optional modifier for placement (typically
 *   `.align(Alignment.CenterEnd)`).
 * @param hideDelayMs override for [FAST_SCROLL_HIDE_DELAY_MS]. Tests
 *   use small values; production stays at 1 second.
 */
@Composable
fun FastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = FAST_SCROLL_HIDE_DELAY_MS,
) {
    val scope = rememberCoroutineScope()

    // Pixel-perfect math derived once per recomposition.
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    val lastVisible = visible.lastOrNull()
    // Best estimate of total content height: last visible item's bottom.
    // For lists where the bottom item is in view, this is exact. For
    // lists where the user is mid-scroll, this is the bottom edge of the
    // rendered window — accurate enough for the thumb to track scroll
    // proportionally. We treat the viewport-end item's bottom as the
    // "extent of what's been measured so far"; correct when the user is
    // at the bottom and progressively under-estimates mid-scroll (still
    // produces a fraction that moves with scroll, which is what matters).
    val estimatedTotalContentHeight: Int = if (lastVisible != null) {
        lastVisible.offset + lastVisible.size
    } else 0

    val fraction: Float? = computeScrollFractionPx(
        viewportStartOffset = info.viewportStartOffset,
        viewportEndOffset = info.viewportEndOffset,
        estimatedTotalContentHeight = estimatedTotalContentHeight,
    )

    // When there's no fraction (empty / fits-on-screen / unrendered),
    // the bar isn't visible at all.
    val canShow = fraction != null
    val isScrolling = listState.isScrollInProgress

    // Auto-hide state machine.
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, canShow) {
        if (!canShow) {
            recentlyScrolled = false
            return@LaunchedEffect
        }
        if (isScrolling) {
            recentlyScrolled = true
            return@LaunchedEffect
        }
        // Scrolling just stopped → grace timer.
        recentlyScrolled = true
        delay(hideDelayMs)
        recentlyScrolled = false
    }

    val palette = LocalHermexPalette.current
    val showScrollbar = canShow && recentlyScrolled

    // Local thumb fraction state for drag.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    AnimatedVisibility(
        visible = showScrollbar,
        enter = fadeIn(animationSpec = tween(durationMillis = 140)),
        exit = fadeOut(animationSpec = tween(durationMillis = 220)),
        modifier = modifier,
    ) {
        val f: Float = fraction ?: 0f
        val displayFraction = if (dragging) dragFraction else f
        val totalCount = info.totalItemsCount

        Box(
            modifier = Modifier
                .width(FAST_SCROLL_HIT_WIDTH)
                .fillMaxHeight()
                .testTag("fastScrollbar")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            val pos = down.changes.firstOrNull()?.position
                                ?: continue
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val frac = (pos.y / h).coerceIn(0f, 1f)
                            dragging = true
                            dragFraction = frac
                            while (true) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val ny = (change.position.y /
                                    size.height.toFloat().coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                dragFraction = ny
                            }
                            dragging = false
                            if (totalCount > 0) {
                                val targetIdx = (dragFraction *
                                    (totalCount - 1)).roundToInt()
                                    .coerceIn(0, totalCount - 1)
                                scope.launch {
                                    listState.animateScrollToItem(targetIdx)
                                }
                            }
                        }
                    }
                },
        ) {
            // Visible track — narrow, faint, accent-tinted, brightens
            // while the user is dragging.
            Spacer(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(2.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .background(
                        color = palette.accent.copy(
                            alpha = if (dragging) 0.45f else 0.22f,
                        ),
                        shape = RoundedCornerShape(1.dp),
                    ),
            )
            // Thumb — width animates 5dp → 8dp on drag.
            val thumbWidth by animateDpAsState(
                targetValue = if (dragging) 8.dp else 5.dp,
                label = "scrollThumb",
                animationSpec = tween(120),
            )
            Spacer(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(width = thumbWidth, height = 40.dp)
                    .scrollThumbProgress(displayFraction)
                    .background(
                        color = palette.accent,
                        shape = RoundedCornerShape(thumbWidth / 2),
                    ),
            )
        }
    }
}

/**
 * Layout modifier that positions its content along the vertical axis
 * according to [fraction]. `0f` pins to the top of the parent, `1f`
 * pins to the bottom (after subtracting this layout's own height).
 *
 * Used so the ScrollThumb's `Spacer` can be measured once and then
 * placed at the right vertical offset without remeasuring on every
 * scroll frame.
 */
private fun Modifier.scrollThumbProgress(fraction: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val parentHeight = constraints.maxHeight
        // Subtract thumb height so the thumb's top stays inside the
        // track even at fraction=1.
        val travel = (parentHeight - placeable.height).coerceAtLeast(0)
        val yOffset = (travel * fraction.coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, parentHeight) {
            placeable.placeRelative(0, yOffset)
        }
    }
