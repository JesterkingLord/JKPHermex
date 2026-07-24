package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette
import kotlinx.coroutines.delay

/**
 * Wave 9.8 (2026-07-24) — JumpFab reduced to **exactly one pill** with
 * a single, simple contract.
 *
 * History of revisions for this component:
 *   - v0.8.4 and earlier: navigation-control FAB (jump-to-top/bottom).
 *     User said this was wrong: they wanted a scroll indicator, not a
 *     navigation control.
 *   - v0.8.5: tried to invert the existing nav-control logic without
 *     rewriting the abstraction. Result: still showed the wrong pill at
 *     the wrong times.
 *   - v0.8.6: built a "scroll indicator" + "jump chip" pair, both
 *     rendered in the same Column. User: "you made 2 scroll up and
 *     down buttons now wtf one is yellow and one white."
 *   - v0.8.7: tried to gate one with the other using a single role
 *     helper. User: "the white button is still there wtf just remove
 *     it are you even testing your features?"
 *
 * v0.8.8 (this revision): the white pill is GONE. There is no longer a
 * jump-to-top/jump-to-bottom affordance in this composable. The only
 * thing this composable can render is a single yellow accent pill with
 * an `↓` glyph that:
 *   - Appears when `isScrolling == true`.
 *   - Stays for 1 second after `isScrolling` flips to false (grace
 *     window via [HIDE_DELAY_MS]).
 *   - Disappears after the grace window elapses.
 *   - Never appears when there's nothing to scroll.
 *
 * No click handler, no jump behavior, no top/bottom directionality.
 * Just the visual confirmation the user described:
 *   "I want it to show while I'm scrolling, and then hide 1 sec after
 *    scrolling stops."
 */
/**
 * Wave 9.11 (2026-07-24) — pill grace window extended so the affordance
 * is actually reachable. The previous 1 000 ms window was enough for
 * the eye but not for fingers: by the time a user lifted their thumb
 * and decided to tap, the pill had already faded out.
 *
 * 3 500 ms keeps the pill visible after scrolling for a comfortable
 * tap window while still hiding once the user settles. Hides immediately
 * when there is nothing to scroll.
 */
private const val HIDE_DELAY_MS: Long = 3_500L

/**
 * Pure decision helper, exposed for unit testing.
 *
 * Returns `true` exactly when the yellow scroll-indicator pill should
 * paint at this moment.
 *
 * Contract:
 *   - `!contentIsScrollable` → never show (nothing to scroll).
 *   - `isScrolling == true` → show.
 *   - `isScrolling == false && hideAfterScrollStop == false` → show
 *     (we're inside the 1 s grace window).
 *   - `isScrolling == false && hideAfterScrollStop == true` → hide
 *     (past the grace window, indicator stays off).
 */
fun decideScrollIndicatorVisibility(
    isScrolling: Boolean,
    hideAfterScrollStop: Boolean,
    contentIsScrollable: Boolean,
): Boolean {
    if (!contentIsScrollable) return false
    if (isScrolling) return true
    // isScrolling == false. Inside the 1 s grace → show; past it → hide.
    return !hideAfterScrollStop
}

/**
 * The single composable. Renders at most one pill (yellow, `↓` glyph)
 * and never anything else.
 *
 * The [hideDelayMs] parameter is overridable for tests; production
 * uses [HIDE_DELAY_MS].
 */
/**
 * Wave 9.12 (2026-07-24) — TWO pill layout: ↑ to jump to first entry,
 * ↓ to jump to latest. Always visible whenever the chat overflows
 * the viewport, with explicit click affordance and clear semantics.
 *
 * The user's literal reports:
 *   - "the up and down button works but it needs to switch the arrow
 *      up after it's down"
 *   - "no it still just scrolls down"
 *   - "the side scroll is still stuck up there"
 *
 * The earlier design auto-hid the pill within 1–3.5 s of scroll
 * stopping, then re-showed it as a single pill with an icon flip.
 * That made the affordance feel like a single "always-scrolls-down"
 * button because users couldn't see the icon switch. This revision
 * makes BOTH pills stick around whenever the user can scroll either
 * direction. The ↓ only appears when there's content BELOW (chat
 * is scrolled up); the ↑ only appears when there's content ABOVE
 * (chat is at the bottom).
 *
 * `onScrollUp` taps trigger `listState.animateScrollToItem(0)`.
 * `onScrollDown` taps trigger `listState.animateScrollToItem(last)`.
 * When only one direction is available the corresponding pill
 * renders alone; when neither is available (chat fits in viewport)
 * nothing renders.
 */
@Composable
fun ScrollIndicatorOnly(
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canScrollForward && !canScrollBackward) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        if (canScrollBackward) {
            DirectionPill(
                arrowIsUp = true,
                onClick = onScrollUp,
            )
        }
        if (canScrollForward) {
            DirectionPill(
                arrowIsUp = false,
                onClick = onScrollDown,
            )
        }
    }
}

@Composable
private fun DirectionPill(arrowIsUp: Boolean, onClick: () -> Unit) {
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.accent,
        contentColor = Color.White,
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(40.dp)
            .testTag(if (arrowIsUp) "jumpFab.scrollUp" else "jumpFab.scrollDown")
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (arrowIsUp) {
                    Icons.Filled.ArrowUpward
                } else {
                    Icons.Filled.ArrowDownward
                },
                contentDescription = if (arrowIsUp) {
                    "Scroll to top"
                } else {
                    "Scroll to latest"
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}


