package com.hermexapp.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
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
private const val HIDE_DELAY_MS: Long = 1_000L

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
@Composable
fun ScrollIndicatorOnly(
    isScrolling: Boolean,
    contentIsScrollable: Boolean,
    modifier: Modifier = Modifier,
    hideDelayMs: Long = HIDE_DELAY_MS,
) {
    // Drives the 1-second grace window. Keyed on the two inputs the
    // timer depends on so a change cancels and restarts the timer.
    var hideAfterScrollStop by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, contentIsScrollable) {
        if (!contentIsScrollable) {
            hideAfterScrollStop = true
            return@LaunchedEffect
        }
        if (isScrolling) {
            // Cancel any pending hide timer; we want the pill VISIBLE.
            hideAfterScrollStop = false
            return@LaunchedEffect
        }
        // Scrolling just stopped. Start the grace timer.
        hideAfterScrollStop = false
        delay(hideDelayMs)
        hideAfterScrollStop = true
    }
    val visible = decideScrollIndicatorVisibility(
        isScrolling = isScrolling,
        hideAfterScrollStop = hideAfterScrollStop,
        contentIsScrollable = contentIsScrollable,
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(140)),
        exit = fadeOut(animationSpec = tween(140)),
        modifier = modifier,
    ) {
        ScrollIndicatorPill()
    }
}

@Composable
private fun ScrollIndicatorPill() {
    val palette = LocalHermexPalette.current
    Surface(
        color = palette.accent,
        contentColor = Color.White,
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(40.dp)
            .testTag("jumpFab.scrollIndicator"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Scrolling",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
