package com.hermexapp.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermexapp.android.ui.theme.LocalHermexPalette

/**
 * The jump action exists only while newer transcript content is below the
 * viewport. Arbitrary positioning, including the exact top, belongs to the
 * fast scrollbar so the two controls never duplicate or obscure each other.
 */
internal fun shouldShowJumpToLatest(canScrollForward: Boolean): Boolean = canScrollForward

/** A compact 40 dp visual inside a native-sized 48 dp button shell. */
@Composable
fun JumpToLatestButton(
    canScrollForward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!shouldShowJumpToLatest(canScrollForward)) return

    val palette = LocalHermexPalette.current
    Box(
        modifier = modifier
            .size(48.dp)
            .testTag("jumpFab.scrollToLatest")
            .semantics {
                contentDescription = "Scroll to latest"
                role = Role.Button
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.accent,
            contentColor = Color.White,
            shape = CircleShape,
            shadowElevation = 6.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
