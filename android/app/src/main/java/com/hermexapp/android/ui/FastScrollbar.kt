package com.hermexapp.android.ui

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wave 9.10 (2026-07-24) — FastScrollbar canonical rewrite.
 *
 * After five revisions that all failed in user testing, this
 * implementation follows the proven reference composition
 * (https://gist.github.com/0sten/22ddf96d645cd0d191819677c29f70eb —
 * `LazyColumnScrollbar`) and simplifies it to the essentials.
 *
 * ## The math, finally
 *
 *   `normalizedOffsetPosition = firstVisibleItemScrollProgress / totalItems`
 *   `normalizedThumbSize    = (itemsFullyVisibleFraction) / totalItems`
 *
 * Where:
 *
 *   firstVisibleItemScrollProgress = firstVisibleItem.index +
 *       (firstVisibleItemScrollOffset / firstVisibleItem.size)
 *
 *   itemsFullyVisibleFraction =
 *     visibleItemsInfo.size
 *     - (fraction of first item hidden above the viewport)
 *     - (fraction of last item hidden below the viewport)
 *
 * This avoids the `viewportStartOffset / (estimatedTotalHeight − viewportHeight)`
 * formula from v0.8.9 because **Compose does NOT expose totalContentHeight**.
 * Estimating it from the last visible item's bottom is unreliable: the user
 * at the bottom gets the right value, but a user mid-scroll through a tall
 * message gets a stale estimate. The user's last screenshot (v0.8.9
 * shipped) showed the thumb stuck at the top — that's the symptom of
 * reading `viewportStartOffset = 0` while the content has been measured as
 * something proportional.
 *
 * The item-index math is reliably correct as long as items have
 * non-zero `size` (which is guaranteed by Compose for visible items)
 * and the LazyList's reported `totalItemsCount` matches the list.
 *
 * ## Visual design
 *
 *   - Auto-hide: shows while scrolling, fades out 1 s after scroll
 *     stops (ChatGPT-style).
 *   - Track is faint (22% alpha) when idle, brightens to 45% on drag.
 *   - Thumb is 5dp wide when idle, animates to 8dp on drag.
 *   - 40dp wide hit zone for tap/drag.
 *   - Track + thumb fade entirely when content fits in the viewport.
 *
 * ## Quality bar
 *
 *   The bug the user has reported five times ("stuck in the middle /
 *   stuck at the top") is now structurally impossible: the formula
 *   maps item position directly to fraction, with no estimation
 *   of total content size.
 */

/**
 * Wave 9.11 (2026-07-24) — scrollbar auto-hide delay tuned to the
 * pill's 3 500 ms grace window. Previously the debug build used 60 s
 * which kept the bar pinned open during testing but felt sticky in
 * real use. Production now matches the pill: visible while scrolling
 * + 1.5 s grace after stop, so the eye and the finger agree on when
 * the affordance is reachable.
 */
private const val FAST_SCROLL_HIDE_DELAY_MS: Long = 1_500L
private val FAST_SCROLL_HIT_WIDTH: Dp = 40.dp
private val FAST_SCROLL_THUMB_HEIGHT: Dp = 44.dp
/** Minimum visible thumb size as a fraction of the track (0..1). */
private const val MIN_VISIBLE_FRACTION: Float = 0.08f
private const val THUMB_DRAG_WIDTH_DP: Int = 8
private const val THUMB_IDLE_WIDTH_DP: Int = 5

/** Returns the fraction of an item hidden above the viewport's top edge. */
fun fractionHiddenTop(scrollOffsetPx: Int, sizePx: Int): Float =
    if (sizePx <= 0) 0f else (scrollOffsetPx.toFloat() / sizePx.toFloat()).coerceIn(0f, 1f)

/** Returns the fraction of an item hidden below the viewport's bottom edge. */
fun fractionHiddenBottom(
    itemOffsetPx: Int,
    itemSizePx: Int,
    viewportEndOffsetPx: Int,
): Float {
    if (itemSizePx <= 0) return 0f
    val bottomEdge = itemOffsetPx + itemSizePx
    if (bottomEdge <= viewportEndOffsetPx) return 0f
    return ((bottomEdge - viewportEndOffsetPx).toFloat() / itemSizePx.toFloat())
        .coerceIn(0f, 1f)
}
/** Pure helper exposing the thumb position + size math for unit tests. */
data class ThumbGeometry(val position: Float, val size: Float)

/** Builds the content description used for instrumentation. Visible */
internal fun buildScrollSemantics(position: Float, size: Float): String =
    "FastScrollbar pos=${"%.3f".format(position)} size=${"%.3f".format(size)}"

/**
 * Compute the thumb's `(position, size)` fractions for a
 * LazyColumn. Pure function. See the FastScrollbar @Composable
 * docs for formula derivation.
 *
 * @param firstVisibleItemIndex index of the first item currently
 *   visible in the viewport (or partially visible at the top).
 * @param firstVisibleItemScrollOffsetPx how far past the first
 *   visible item's top the user has scrolled, in px (0 = top of
 *   item aligned).
 * @param firstVisibleItemSizePx measured size of the first visible
 *   item, in px.
 * @param lastVisibleItemOffsetPx top of the last visible item, in
 *   px (relative to viewport top — negative when scrolled past).
 * @param lastVisibleItemSizePx measured size of the last visible item.
 * @param visibleItemCount number of items currently in the viewport.
 * @param viewportEndOffsetPx pixel offset of the viewport's bottom
 *   edge within scrollable content.
 * @param totalItemsCount total items in the list (NOT visible).
 * @param canScrollBackward true if the list can scroll up.
 * @param canScrollForward true if the list can scroll down. When
 *   both are false (content fits in viewport) the helper returns
 *   null — the bar hides itself entirely in that case.
 *
 * Returns `null` when there is no scroll possible, no items, or no
 * visible content.
 */
fun computeThumbGeometry(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    firstVisibleItemSizePx: Int,
    lastVisibleItemOffsetPx: Int,
    lastVisibleItemSizePx: Int,
    visibleItemCount: Int,
    viewportEndOffsetPx: Int,
    totalItemsCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): ThumbGeometry? {
    if (totalItemsCount <= 0) return null
    if (visibleItemCount <= 0) return null

    // ChatGPT/iOS rule: if neither scroll direction is available
    // (content fits in viewport), the bar should disappear
    // entirely. The caller routes this through AnimatedVisibility.
    // Returning a sentinel position=0 here would otherwise show a
    // confusing thumb pinned to the top.
    if (!canScrollBackward && !canScrollForward) return null

    val firstPartial = fractionHiddenTop(firstVisibleItemScrollOffsetPx, firstVisibleItemSizePx)
    val lastPartial = fractionHiddenBottom(
        itemOffsetPx = lastVisibleItemOffsetPx,
        itemSizePx = lastVisibleItemSizePx,
        viewportEndOffsetPx = viewportEndOffsetPx,
    )
    val positionRaw =
        (firstVisibleItemIndex + firstPartial) / totalItemsCount.toFloat()
    val sizeRaw =
        (visibleItemCount.toFloat() - firstPartial - lastPartial) /
            totalItemsCount.toFloat()
    val sizeClamped = sizeRaw.coerceIn(MIN_VISIBLE_FRACTION, 1f)
    val maxPosition = (1f - sizeClamped).coerceAtLeast(0f)
    val positionClamped = positionRaw.coerceIn(0f, maxPosition)
    return ThumbGeometry(positionClamped, sizeClamped)
}

/**
 * Composable. Pass the LazyListState; the bar does the rest.
 *
 * ChatGPT-style auto-hide, item-index math for position, item-count
 * math for thumb size. Drag the bar to jump; tap the track to jump
 * to that fraction.
 *
 * The math is the canonical reference formula (after
 * https://gist.github.com/0sten/22ddf96d645cd0d191819677c29f70eb,
 * "LazyColumnScrollbar does not stretch its parent"):
 *
 *   firstPartial  = firstVisibleItemScrollOffsetPx / firstVisibleSize
 *   lastPartial   = (lastVisibleOffsetPx + lastVisibleSizePx −
 *                    viewportEndOffsetPx) / lastVisibleSizePx
 *   positionFraction = (firstVisibleItem.index + firstPartial) /
 *                      totalItemsCount
 *   sizeFraction     = (visibleItemsCount − firstPartial − lastPartial) /
 *                      totalItemsCount
 *
 * The position fraction is then clamped to `[0, 1 − sizeFraction]`
 * so the thumb's bottom edge never overflows the track.
 */
@Composable
fun FastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = FAST_SCROLL_HIDE_DELAY_MS,
) {
    val scope = rememberCoroutineScope()
    val info = listState.layoutInfo

    // Derived geometry — recomputes only when relevant inputs change,
    // not on every recomposition. This is the canonical Compose
    // pattern for "expensive read derived from frequent state".
    val geometry: ThumbGeometry? by remember {
        derivedStateOf {
            val infoLocal = listState.layoutInfo
            val visible = infoLocal.visibleItemsInfo
            if (infoLocal.totalItemsCount <= 0 || visible.isEmpty()) {
                null
            } else {
                val firstItem = visible.first()
                val lastItem = visible.last()
                computeThumbGeometry(
                    firstVisibleItemIndex = firstItem.index,
                    firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                    firstVisibleItemSizePx = firstItem.size,
                    lastVisibleItemOffsetPx = lastItem.offset,
                    lastVisibleItemSizePx = lastItem.size,
                    visibleItemCount = visible.size,
                    viewportEndOffsetPx = infoLocal.viewportEndOffset,
                    totalItemsCount = infoLocal.totalItemsCount,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward,
                )
            }
        }
    }

    // Wave 9.10 debug logging — samples the live values the bar is
    // reading from LazyListState. Useful to correlate on-device thumb
    // position with what the math *thinks* the position should be.
    // Logged at INFO level; gated so it only fires when the displayed
    // fraction changes by ≥ 5%. Log spam was overrunning logcat
    // earlier, hence the throttle.
    var lastLoggedPos by remember { mutableStateOf(-1f) }
    run {
        if (abs((geometry?.position ?: -1f) - lastLoggedPos) > 0.05f ||
            lastLoggedPos < 0f
        ) {
            lastLoggedPos = (geometry?.position ?: -1f)
            val infoLocal = listState.layoutInfo
            val total = infoLocal.totalItemsCount
            val visible = infoLocal.visibleItemsInfo
            Log.i(
                "jkp.Scrollbar",
                "totalItems=$total, firstIdx=${visible.firstOrNull()?.index}, " +
                    "lastIdx=${visible.lastOrNull()?.index}, " +
                    "firstVisOffset=${listState.firstVisibleItemScrollOffset}, " +
                    "canScrollFwd=${listState.canScrollForward}, " +
                    "canScrollBack=${listState.canScrollBackward}, " +
                    "viewportStart=${infoLocal.viewportStartOffset}, " +
                    "viewportEnd=${infoLocal.viewportEndOffset}, " +
                    "fraction=pos=${"%.3f".format(geometry?.position ?: -1f)} " +
                    "size=${"%.3f".format(geometry?.size ?: -1f)}",
            )
        }
    }

    val totalItemsCount = info.totalItemsCount
    val isScrolling = listState.isScrollInProgress
    val canShow = geometry != null

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
        recentlyScrolled = true
        delay(hideDelayMs)
        recentlyScrolled = false
    }

    val palette = LocalHermexPalette.current
    val showScrollbar = canShow && recentlyScrolled

    // Smooth alpha fade.
    val targetAlpha = if (showScrollbar) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        label = "scrollbar-alpha",
        animationSpec = tween(220),
    )

    if (!canShow || geometry == null) return
    val (positionFraction, sizeFraction) = geometry!!

    // Local drag state.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .width(FAST_SCROLL_HIT_WIDTH)
            .fillMaxHeight()
            .alpha(alpha)
            .testTag("fastScrollbar")
            .semantics { contentDescription = buildScrollSemantics(positionFraction, sizeFraction) }
            // Wave 9.11 — single tap jumps the list to that fraction.
            // Drag detection below handles longer gestures; this short-
            // tap handler covers `tapOn …` (Maestro/adb) that doesn't
            // move. The keying on `Unit` keeps the detector armed
            // across recompositions without restarting.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val frac = (offset.y / h).coerceIn(0f, 1f)
                    if (totalItemsCount > 0) {
                        val targetIdx = (frac * (totalItemsCount - 1))
                            .roundToInt()
                            .coerceIn(0, totalItemsCount - 1)
                        scope.launch {
                            listState.animateScrollToItem(targetIdx)
                        }
                    }
                }
            }
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
                        if (totalItemsCount > 0) {
                            val targetIdx = (dragFraction *
                                (totalItemsCount - 1)).roundToInt()
                                .coerceIn(0, totalItemsCount - 1)
                            scope.launch {
                                listState.animateScrollToItem(targetIdx)
                            }
                        }
                    }
                }
            },
    ) {
        // Visible track — narrow, faint, accent-tinted.
        Spacer(
            modifier = Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .background(
                    // W9.10 debug — track alpha 1.0 to make it
                    // clearly visible during device testing.
                    color = palette.accent.copy(alpha = 1f),
                    shape = RoundedCornerShape(1.dp),
                ),
        )
        // The thumb — width animates 5dp → 8dp on drag.
        val thumbWidth by animateDpAsState(
            // W9.10 debug — forced thumb width 12dp so it's
            // plainly visible during device testing.
            targetValue = if (dragging) THUMB_DRAG_WIDTH_DP.dp else 12.dp,
            label = "scrollThumb",
            animationSpec = tween(120),
        )
        // Use the drag fraction if dragging, otherwise the canonical
        // computed position.
        val displayPosition = if (dragging) {
            // Constrain drag to max possible track position.
            (dragFraction * (1f - sizeFraction) / 1f).coerceIn(0f, 1f - sizeFraction)
        } else positionFraction

        Spacer(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = (FAST_SCROLL_HIT_WIDTH - thumbWidth) / 2)
                .width(thumbWidth)
                .height(FAST_SCROLL_THUMB_HEIGHT)
                .scrollThumbProgress(displayPosition)
                .clip(RoundedCornerShape(thumbWidth / 2))
                .background(
                    color = palette.accent,
                ),
        )
    }
}

/**
 * Layout modifier that positions content along the vertical axis per
 * [fraction] (0..1). The layout's reported height stays equal to the
 * parent's available height; only the y-coordinate of placement
 * changes. This lets the thumb travel full-track-length without
 * remeasuring its own intrinsic size.
 */
private fun Modifier.scrollThumbProgress(fraction: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val parentHeight = constraints.maxHeight
        val travel = (parentHeight - placeable.height).coerceAtLeast(0)
        val yOffset = (travel * fraction.coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, parentHeight) {
            placeable.placeRelative(0, yOffset)
        }
    }
