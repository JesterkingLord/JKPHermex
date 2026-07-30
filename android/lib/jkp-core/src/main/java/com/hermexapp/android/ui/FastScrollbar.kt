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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Wave 9.14 (2026-07-25) — FastScrollbar pixel-based rewrite.
 *
 * The v0.8.10 → v0.8.14 revisions used the "canonical" item-index
 * formula (`(firstVisibleIndex + firstPartial) / totalItemsCount`).
 * On-device verification (adb uiautomator + logcat, OnePlus CPH2343)
 * proved that formula structurally wrong FOR THIS LIST: the chat
 * renders ONE LazyColumn item per message, so a 97-message session
 * has 97 items whose heights range from ~200 px to > 10 000 px.
 * Scrolling a full screen inside one tall message moved the reported
 * position by < 2% (measured pos=0.019 after ~6 viewport swipes),
 * because the intra-item pixel offset was divided by 97. The thumb
 * looked "stuck at the top" — the exact user report.
 *
 * ## The math (pixel-based, Wave 9.14)
 *
 * Compose does not expose totalContentHeight, so we ESTIMATE it from
 * a persistent measured-size cache (see [MeasuredSizeCache]):
 *
 *   heightOf(i) = measuredSizes[i] ?: averageOf(measuredSizes)
 *   totalPx     = Σ heightOf(i)            for i in 0..<totalItems
 *   scrollPx    = Σ heightOf(i)            for i < firstVisibleIndex
 *               + firstVisibleItemScrollOffset
 *   position    = scrollPx / (totalPx − viewportPx)
 *   size        = viewportPx / totalPx   (clamped to a min fraction)
 *
 * The cache converges to exact as the user scrolls (every visible
 * item reports its real measured height each frame). The endpoints
 * are SNAPPED from `canScrollBackward` / `canScrollForward`, so the
 * thumb is EXACTLY at top at scroll position 0 and EXACTLY at bottom
 * when the list can't scroll further — regardless of estimation
 * error in the middle.
 *
 * ## Visual design
 *
 *   - Auto-hide: 60 s delay (Wave 9.12) — anything shorter reads as
 *     "broken" for a navigation affordance the user relies on.
 *   - Thumb is 5dp wide when idle, animates to 8dp on drag.
 *   - 40dp wide hit zone for tap/drag.
 *   - Bar hides entirely when content fits in the viewport.
 *
 * ## Quality bar
 *
 *   Every position computation lives in the pure, unit-tested
 *   helpers [computePixelThumbGeometry] / [scrollTargetForFraction] /
 *   [thumbTopEdgeOffsetPx]. On-device proof: adb uiautomator dumps
 *   read the `FastScrollbar pos=…` content description at top, mid,
 *   and bottom scroll positions.
 */

/**
 * Wave 9.12 — scrollbar auto-hide delay extended so the user can
 * always see where they are. The previous 1 500 ms fade-out made
 * the bar disappear while reading, which the user read as
 * \"stuck\". Now the bar fades in once on first scroll, then
 * stays visible until the chat scrolls off-screen entirely.
 *
 * Because the same Compose `alpha` fade drives both appearance and
 * disappearance, a value of 60 000 ms is effectively \"always on\"
 * during normal use while still allowing the bar to vanish when
 * the LazyColumn itself unmounts (the LaunchedEffect's canShow=false
 * branch is independent of the timer).
 */
private const val FAST_SCROLL_HIDE_DELAY_MS: Long = 60_000L
private const val FAST_SCROLL_EDGE_SNAP_FRACTION = 0.02f
private const val FAST_SCROLL_SETTLE_PASSES = 3
private val FAST_SCROLL_HIT_WIDTH: Dp = 48.dp
/** Wave 9.12 — reduced the thumb height so it doesn't dominate the track. */
private val FAST_SCROLL_THUMB_HEIGHT: Dp = 32.dp
/** Minimum visible thumb size as a fraction of the track (0..1). */
private const val MIN_VISIBLE_FRACTION: Float = 0.08f
private const val THUMB_DRAG_WIDTH_DP: Int = 8
private const val THUMB_IDLE_WIDTH_DP: Int = 5

/** Pure helper exposing the thumb position + size math for unit tests. */
data class ThumbGeometry(val position: Float, val size: Float)

/** Rejects invalid accessibility values and clamps finite requests to the track. */
internal fun normalizeRequestedScrollFraction(requested: Float): Float? =
    requested.takeIf(Float::isFinite)?.coerceIn(0f, 1f)

/** Human-readable value announced by TalkBack. */
internal fun buildScrollStateDescription(position: Float): String =
    "${(position.coerceIn(0f, 1f) * 100f).roundToInt()} percent through content"

/** Hidden scrollbars must not leave an invisible touch target over chat content. */
internal fun shouldRenderFastScrollbar(canShow: Boolean, alpha: Float): Boolean =
    canShow && alpha > 0.01f

/**
 * Persistent cache of measured item heights, keyed by LazyColumn
 * item index. Filled from `layoutInfo.visibleItemsInfo` — every item
 * on screen reports its real pixel height each frame, so the cache
 * converges to exact as the user scrolls.
 *
 * The cache is cleared when `totalItemsCount` changes because indices
 * shift when entries are inserted/replaced (the chat keys items by
 * entry id, not index). Endpoint snapping in
 * [computePixelThumbGeometry] hides the brief re-convergence at the
 * two positions the user checks most: very top and very bottom.
 *
 * NOT thread-safe by design: Compose snapshot reads happen on the
 * main thread only.
 */
internal class MeasuredSizeCache {
    private val sizes = HashMap<Int, Int>()
    private var forTotalCount = -1

    /** Records the measured heights of [visible]; clears on list change. */
    fun record(totalItemsCount: Int, visible: List<androidx.compose.foundation.lazy.LazyListItemInfo>) {
        if (totalItemsCount != forTotalCount) {
            sizes.clear()
            forTotalCount = totalItemsCount
        }
        for (item in visible) {
            if (item.size > 0) sizes[item.index] = item.size
        }
    }

    /** Defensive copy of the current index → heightPx map. */
    fun snapshot(): Map<Int, Int> = HashMap(sizes)

    /** Number of items with a real measured height so far. */
    fun measuredCount(): Int = sizes.size

    /**
     * Fallback height for unmeasured items.
     *
     * Wave 9.14 on-device lesson: a MEAN is poisoned by outlier
     * messages. The user's session opens on a single ~10 000 px
     * assistant message; with only that item measured the mean was
     * 10 000 px, the estimated total content became ~97 × 10 000 px,
     * and the thumb reported pos=0.017 after six full screens of
     * scrolling — the "stuck at top" bug again.
     *
     * Instead we use the MEDIAN of the measured heights, seeded with
     * one virtual prior sample of `0.75 × viewportHeightPx` (a
     * typical chat message is roughly three quarters of a screen).
     * The prior means the fallback is sane from the very first frame
     * and one giant outlier cannot drag it up; as typical messages
     * get measured the median converges to the typical height.
     */
    fun fallbackPx(viewportHeightPx: Float): Float {
        val prior = (viewportHeightPx * 0.75f).coerceAtLeast(1f)
        val samples = (sizes.values.map { it.toFloat() } + prior).sorted()
        // Lower-middle median: for an even sample count prefer the
        // smaller middle value so a single giant message can't raise
        // the fallback above the prior.
        return samples[(samples.size - 1) / 2]
    }
}

/**
 * Intermediate pixel metrics for the scrollbar model. Exposed (and
 * returned by [estimateContentMetrics]) so both the geometry helper
 * and the on-device debug log read the SAME numbers the math uses —
 * the v0.8.14 debugging cycle suffered from the log and the geometry
 * computing different things.
 */
data class ContentMetrics(
    val totalPx: Float,
    val scrollPx: Float,
    val scrollablePx: Float,
    val fallbackPx: Float,
)

/**
 * Builds the pixel model: estimated total content height, current
 * scroll offset in px, and the scrollable range. Pure function.
 *
 * `heightOf(i) = measuredItemSizesPx[i] ?: fallbackUnmeasuredItemSizePx`
 *
 * Returns null when the model cannot produce a meaningful estimate
 * (empty list, non-positive viewport, or no fallback available).
 */
fun estimateContentMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    measuredItemSizesPx: Map<Int, Int>,
    fallbackUnmeasuredItemSizePx: Float,
    viewportHeightPx: Int,
    totalItemsCount: Int,
): ContentMetrics? {
    if (totalItemsCount <= 0 || viewportHeightPx <= 0) return null
    if (fallbackUnmeasuredItemSizePx <= 0f) return null

    fun heightOf(index: Int): Float =
        measuredItemSizesPx[index]?.takeIf { it > 0 }?.toFloat()
            ?: fallbackUnmeasuredItemSizePx

    var totalPx = 0f
    var scrollPx = 0f
    for (i in 0 until totalItemsCount) {
        val h = heightOf(i)
        totalPx += h
        if (i < firstVisibleItemIndex) scrollPx += h
    }
    scrollPx += firstVisibleItemScrollOffsetPx

    return ContentMetrics(
        totalPx = totalPx,
        scrollPx = scrollPx,
        scrollablePx = totalPx - viewportHeightPx,
        fallbackPx = fallbackUnmeasuredItemSizePx,
    )
}

/**
 * Wave 9.14 — pixel-based thumb geometry. Pure function; see the
 * file-level KDoc for the derivation and the on-device evidence that
 * motivates it.
 *
 * @param firstVisibleItemIndex index of the first (partially) visible
 *   item.
 * @param firstVisibleItemScrollOffsetPx pixels scrolled past the top
 *   of that item.
 * @param measuredItemSizesPx index → measured height in px for every
 *   item seen so far (from [MeasuredSizeCache]).
 * @param fallbackUnmeasuredItemSizePx fallback height for items not
 *   yet measured; typically [MeasuredSizeCache.averagePx].
 * @param viewportHeightPx viewport height in px
 *   (`viewportEndOffset − viewportStartOffset`).
 * @param totalItemsCount total items in the list (NOT visible).
 * @param canScrollBackward true if the list can scroll up.
 * @param canScrollForward true if the list can scroll down. When both
 *   are false (content fits) the helper returns null — the bar hides.
 *
 * Returns `null` when the bar should hide (no scroll possible, no
 * measurements yet, or content fits in the viewport).
 */
fun computePixelThumbGeometry(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    measuredItemSizesPx: Map<Int, Int>,
    fallbackUnmeasuredItemSizePx: Float,
    viewportHeightPx: Int,
    totalItemsCount: Int,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): ThumbGeometry? {
    // ChatGPT/iOS rule: no scroll possible → no bar. Returning a
    // sentinel position=0 here would pin a meaningless thumb to the
    // top of the track (the v0.8.10 "stuck at top" symptom).
    if (!canScrollBackward && !canScrollForward) return null

    val metrics = estimateContentMetrics(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffsetPx = firstVisibleItemScrollOffsetPx,
        measuredItemSizesPx = measuredItemSizesPx,
        fallbackUnmeasuredItemSizePx = fallbackUnmeasuredItemSizePx,
        viewportHeightPx = viewportHeightPx,
        totalItemsCount = totalItemsCount,
    ) ?: return null
    if (metrics.scrollablePx <= 0f) return null

    val sizeFraction =
        (viewportHeightPx / metrics.totalPx).coerceIn(MIN_VISIBLE_FRACTION, 1f)
    val rawPosition = (metrics.scrollPx / metrics.scrollablePx).coerceIn(0f, 1f)
    // Endpoint snapping: top and bottom are EXACT, taken from the
    // list's own scroll state, so estimation error can never strand
    // the thumb away from an end the user is actually at.
    val position = when {
        !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> rawPosition
    }
    return ThumbGeometry(position, sizeFraction)
}

/** Result of mapping a track fraction back to a list scroll target. */
data class ScrollTarget(val itemIndex: Int, val itemScrollOffsetPx: Int)

/**
 * Inverse of [computePixelThumbGeometry]: maps a tap/drag fraction on
 * the track (0 = top, 1 = bottom) to the (itemIndex, offset) the
 * LazyColumn should scroll to, using the same measured-size model.
 * Pure function; unit-tested alongside the geometry.
 */
fun scrollTargetForFraction(
    fraction: Float,
    measuredItemSizesPx: Map<Int, Int>,
    fallbackUnmeasuredItemSizePx: Float,
    viewportHeightPx: Int,
    totalItemsCount: Int,
): ScrollTarget? {
    if (totalItemsCount <= 0 || viewportHeightPx <= 0) return null
    if (fallbackUnmeasuredItemSizePx <= 0f) return null

    val clampedFraction = fraction.coerceIn(0f, 1f)
    if (clampedFraction <= FAST_SCROLL_EDGE_SNAP_FRACTION) {
        return ScrollTarget(0, 0)
    }
    if (clampedFraction >= 1f - FAST_SCROLL_EDGE_SNAP_FRACTION) {
        // LazyListState clamps this oversized offset to the true bottom.
        // This avoids estimates leaving the final viewport a few pixels short.
        return ScrollTarget(totalItemsCount - 1, Int.MAX_VALUE)
    }

    fun heightOf(index: Int): Float =
        measuredItemSizesPx[index]?.takeIf { it > 0 }?.toFloat()
            ?: fallbackUnmeasuredItemSizePx

    var totalPx = 0f
    for (i in 0 until totalItemsCount) totalPx += heightOf(i)
    val scrollablePx = (totalPx - viewportHeightPx).coerceAtLeast(0f)
    val targetPx = clampedFraction * scrollablePx

    var acc = 0f
    for (i in 0 until totalItemsCount) {
        val h = heightOf(i)
        if (targetPx < acc + h || i == totalItemsCount - 1) {
            val offset = (targetPx - acc).coerceIn(0f, h).roundToInt()
            return ScrollTarget(i, offset)
        }
        acc += h
    }
    return ScrollTarget(0, 0) // unreachable: loop always returns
}

/**
 * Composable. Pass the LazyListState; the bar does the rest.
 *
 * ChatGPT-style auto-hide, PIXEL-based math (see file KDoc) so the
 * thumb tracks real scroll position even when one item is many
 * viewports tall. Drag the bar to jump; tap the track to jump to
 * that fraction.
 *
 *   heightOf(i) = measuredSizes[i] ?: avg(measured)
 *   position = pxScrolled / (totalContentPx − viewportPx)
 *   size     = viewportPx / totalContentPx
 *
 * Endpoints snap from canScrollBackward/canScrollForward, so the
 * thumb's top/bottom positions are exact by construction.
 */
@Composable
fun FastScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = FAST_SCROLL_HIDE_DELAY_MS,
) {
    val scope = rememberCoroutineScope()

    // Wave 9.14 — persistent measured-height cache. Lives across
    // recompositions; converges to exact heights as the user scrolls.
    val sizeCache = remember { MeasuredSizeCache() }

    // Derived geometry — recomputes only when relevant inputs change,
    // not on every recomposition. This is the canonical Compose
    // pattern for "expensive read derived from frequent state".
    // Recording visible sizes here (rather than in a separate effect)
    // guarantees the cache and the geometry read the SAME frame's
    // layout info; record() is idempotent so the side effect is safe.
    val geometry: ThumbGeometry? by remember {
        derivedStateOf {
            val infoLocal = listState.layoutInfo
            if (infoLocal.totalItemsCount <= 0 || infoLocal.visibleItemsInfo.isEmpty()) {
                null
            } else {
                sizeCache.record(infoLocal.totalItemsCount, infoLocal.visibleItemsInfo)
                val viewportPx = infoLocal.viewportEndOffset - infoLocal.viewportStartOffset
                computePixelThumbGeometry(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                    measuredItemSizesPx = sizeCache.snapshot(),
                    fallbackUnmeasuredItemSizePx = sizeCache.fallbackPx(viewportPx.toFloat()),
                    viewportHeightPx = viewportPx,
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
    // fraction changes by ≥ 1%. Log spam was overrunning logcat
    // earlier, hence the throttle.
    // Wave 9.14 — the log now prints the SAME ContentMetrics the
    // geometry uses (scrollPx / totalPx / fallback), so an on-device
    // reading can be checked against the pure-function unit tests.
    val debugLoggingEnabled = remember { Log.isLoggable("jkp.Scrollbar", Log.DEBUG) }
    LaunchedEffect(debugLoggingEnabled, listState) {
        if (!debugLoggingEnabled) return@LaunchedEffect
        var lastLoggedPos = -1f
        snapshotFlow { geometry }.collect { currentGeometry ->
            val position = currentGeometry?.position ?: -1f
            if (kotlin.math.abs(position - lastLoggedPos) < 0.01f && lastLoggedPos >= 0f) {
                return@collect
            }
            lastLoggedPos = position
            val infoLocal = listState.layoutInfo
            val total = infoLocal.totalItemsCount
            val visible = infoLocal.visibleItemsInfo
            val viewportPx = infoLocal.viewportEndOffset - infoLocal.viewportStartOffset
            val metrics = estimateContentMetrics(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                measuredItemSizesPx = sizeCache.snapshot(),
                fallbackUnmeasuredItemSizePx = sizeCache.fallbackPx(viewportPx.toFloat()),
                viewportHeightPx = viewportPx,
                totalItemsCount = total,
            )
            Log.d(
                "jkp.Scrollbar",
                "totalItems=$total, firstIdx=${visible.firstOrNull()?.index}, " +
                    "lastIdx=${visible.lastOrNull()?.index}, " +
                    "firstVisOffset=${listState.firstVisibleItemScrollOffset}, " +
                    "canScrollFwd=${listState.canScrollForward}, " +
                    "canScrollBack=${listState.canScrollBackward}, " +
                    "scrollPx=${metrics?.scrollPx?.toInt()}, " +
                    "totalPx=${metrics?.totalPx?.toInt()}, " +
                    "fallback=${metrics?.fallbackPx?.toInt()}, " +
                    "measured=${sizeCache.measuredCount()}, " +
                    "fraction=pos=${"%.3f".format(Locale.ROOT, position)} " +
                    "size=${"%.3f".format(Locale.ROOT, currentGeometry?.size ?: -1f)}",
            )
        }
    }

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

    if (!shouldRenderFastScrollbar(canShow, alpha) || geometry == null) return
    val (positionFraction, sizeFraction) = geometry!!

    // Local drag state.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    suspend fun settleAtFraction(fraction: Float, animateFirstPass: Boolean) {
        repeat(FAST_SCROLL_SETTLE_PASSES) { pass ->
            val info = listState.layoutInfo
            val viewportPx = info.viewportEndOffset - info.viewportStartOffset
            val target = scrollTargetForFraction(
                fraction = fraction,
                measuredItemSizesPx = sizeCache.snapshot(),
                fallbackUnmeasuredItemSizePx = sizeCache.fallbackPx(viewportPx.toFloat()),
                viewportHeightPx = viewportPx,
                totalItemsCount = info.totalItemsCount,
            ) ?: return

            if (animateFirstPass && pass == 0) {
                listState.animateScrollToItem(target.itemIndex, target.itemScrollOffsetPx)
            } else {
                listState.scrollToItem(target.itemIndex, target.itemScrollOffsetPx)
            }

            if (pass < FAST_SCROLL_SETTLE_PASSES - 1) {
                // Let LazyColumn measure the newly visible (often very tall)
                // message before recomputing the inverse pixel estimate.
                withFrameNanos { }
                withFrameNanos { }
            }
        }
    }

    // Keep the transcript under the user's finger while dragging. Earlier
    // builds moved only the painted thumb and jumped the list on finger-up,
    // which felt disconnected and made precise positioning difficult.
    LaunchedEffect(dragging, dragFraction) {
        if (!dragging) return@LaunchedEffect
        val info = listState.layoutInfo
        val target = scrollTargetForFraction(
            fraction = dragFraction,
            measuredItemSizesPx = sizeCache.snapshot(),
            fallbackUnmeasuredItemSizePx = sizeCache.fallbackPx(
                (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
            ),
            viewportHeightPx = info.viewportEndOffset - info.viewportStartOffset,
            totalItemsCount = info.totalItemsCount,
        )
        if (target != null) {
            listState.scrollToItem(target.itemIndex, target.itemScrollOffsetPx)
        }
    }

    Box(
        modifier = modifier
            .width(FAST_SCROLL_HIT_WIDTH)
            .fillMaxHeight()
            .alpha(alpha)
            .testTag("fastScrollbar")
            .semantics {
                contentDescription = "Scroll position"
                stateDescription = buildScrollStateDescription(positionFraction)
                progressBarRangeInfo = ProgressBarRangeInfo(positionFraction, 0f..1f, 0)
                setProgress { requested ->
                    val fraction = normalizeRequestedScrollFraction(requested)
                        ?: return@setProgress false
                    scope.launch {
                        settleAtFraction(fraction, animateFirstPass = true)
                    }
                    true
                }
            }
            // Wave 9.11 — single tap jumps the list to that fraction.
            // Drag detection below handles longer gestures; this short-
            // tap handler covers `tapOn …` (Maestro/adb) that doesn't
            // move. The keying on `Unit` keeps the detector armed
            // across recompositions without restarting.
            // Wave 9.14 — the fraction is mapped through the same
            // pixel model as the thumb, so tapping mid-track lands
            // mid-content even with wildly variable message heights.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val frac = (offset.y / h).coerceIn(0f, 1f)
                    scope.launch {
                        settleAtFraction(frac, animateFirstPass = true)
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
                        dragFraction = frac
                        val touchSlop = viewConfiguration.touchSlop
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            if (kotlin.math.abs(change.position.y - pos.y) > touchSlop) {
                                moved = true
                                dragging = true
                            }
                            val ny = (change.position.y /
                                size.height.toFloat().coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                            dragFraction = ny
                        }
                        dragging = false
                        if (moved) {
                            scope.launch {
                                settleAtFraction(dragFraction, animateFirstPass = false)
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
                    color = palette.accent.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(1.dp),
                ),
        )
        // The thumb. Width animates between [THUMB_IDLE_WIDTH_DP] and
        // [THUMB_DRAG_WIDTH_DP] when a drag begins.
        val thumbWidth by animateDpAsState(
            targetValue = if (dragging) THUMB_DRAG_WIDTH_DP.dp else THUMB_IDLE_WIDTH_DP.dp,
            label = "scrollThumb",
            animationSpec = tween(120),
        )
        // Wave 9.12 — thumb always renders within the visible track.
        // The math treats `displayPosition` as a fraction in [0..1],
        // but the rendered thumb is a finite `FAST_SCROLL_THUMB_HEIGHT`
        // tall. So the travel it can actually cover is
        // `(trackHeight - thumbHeight)`. We measure that here and let
        // `scrollThumbProgress` place the thumb centered on its target
        // fraction (top-edge → position 0.0, bottom-edge → position 1.0).
        // This makes the thumb's bounds unambiguously map to scroll
        // position even when at the very top of the content.
        // Wave 9.14 — `positionFraction` is now a normalized PIXEL
        // fraction already clamped to [0, 1], and a drag fraction is
        // the same unit (tap point on the track), so the drag value
        // needs no re-normalization — just the [0, 1] clamp.
        val displayPosition = if (dragging) {
            dragFraction.coerceIn(0f, 1f)
        } else positionFraction

        Spacer(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = (FAST_SCROLL_HIT_WIDTH - thumbWidth) / 2)
                // Wave 9.14 — ORDER MATTERS. scrollThumbProgress must
                // wrap width/height, not the other way around: the
                // layout modifier reads `constraints.maxHeight` as the
                // TRACK height. When width()/height() sat outside it,
                // they coerced the incoming constraints to exactly the
                // thumb size (96 px), so the "track" was 96 px tall,
                // travel was 0, and the thumb rendered ~24 px below
                // the track top at EVERY scroll position — the
                // "stuck at top" symptom persisted even after the
                // position math was fixed. The 9 pure-function layout
                // tests could not see this because they test
                // thumbTopEdgeOffsetPx, not the modifier chain.
                // On-device proof: screenshot crops at pos=0/0.5/1.
                .scrollThumbProgress(displayPosition)
                .width(thumbWidth)
                .height(FAST_SCROLL_THUMB_HEIGHT)
                .clip(RoundedCornerShape(thumbWidth / 2))
                .background(
                    color = palette.accent,
                ),
        )
    }
}

/**
 * Wave 9.13 — Pure function returning the thumb's top-edge offset in pixels
 * for a track of [trackHeightPx] and a thumb of [thumbHeightPx] given a
 * [fraction] in [0, 1].
 *
 * The thumb's TOP edge travels from `0` (fraction = 0) to
 * `trackHeightPx - thumbHeightPx` (fraction = 1) so the entire
 * thumb stays inside the visible track. The track spacer has
 * 6 dp of inset on each end; we apply that as the track bounds so
 * the thumb aligns with the visible (padded) track, not the
 * parent's raw bounds.
 *
 * Exposed for unit testing the geometry independent of Compose's
 * layout pass. The [scrollThumbProgress] modifier below delegates
 * to this so the layout modifier stays a one-liner.
 *
 * Example (trackHeightPx = 1268, thumbHeightPx = 96):
 *   fraction = 0.0  → yOffset =   0 (thumb sits flush with track top)
 *   fraction = 1.0  → yOffset = 1172 (thumb sits flush with track bottom)
 *   fraction = 0.5  → yOffset =  586 (thumb sits mid-track)
 */
internal fun thumbTopEdgeOffsetPx(
    trackHeightPx: Int,
    thumbHeightPx: Int,
    fraction: Float,
): Int {
    if (trackHeightPx <= 0 || thumbHeightPx <= 0) return 0
    // Track inset = thumb half-height, capped to a quarter of the
    // parent height. This keeps the thumb inside the visible area
    // even at the boundaries.
    val trackInset = (thumbHeightPx / 2f).toInt().coerceAtMost(trackHeightPx / 4)
    val trackTop = trackInset
    val trackBottom = trackHeightPx - trackInset
    val travel = (trackBottom - trackTop - thumbHeightPx).coerceAtLeast(0)
    val f = fraction.coerceIn(0f, 1f)
    return trackTop + (travel.toFloat() * f).roundToInt()
}

/**
 * Layout modifier that positions content along the vertical axis per
 * [fraction] (0..1). See [thumbTopEdgeOffsetPx] for the pure-math
 * derivation; this modifier is just the Compose wrapper that reads
 * placeable sizes and delegates.
 */
private fun Modifier.scrollThumbProgress(fraction: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val parentHeight = constraints.maxHeight
        val thumbH = placeable.height
        val yOffset = thumbTopEdgeOffsetPx(
            trackHeightPx = parentHeight,
            thumbHeightPx = thumbH,
            fraction = fraction,
        )
        layout(placeable.width, parentHeight) {
            placeable.placeRelative(0, yOffset)
        }
    }
