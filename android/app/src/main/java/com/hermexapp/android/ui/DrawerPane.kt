package com.hermexapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * ToolsPane — the slide-in drawer body used in phone widths (<600dp).
 *
 * Tablet (≥600dp) keeps the always-on [SidebarRailLayout] from Wave 6.
 * This pane intentionally mirrors the rail's six affordances so muscle
 * memory transfers between form factors.
 *
 * Why one shared body shape across phone/tablet (not a separate rail+drawer):
 * - keeps nav-affordance parity between phone and tablet runs
 * - lets future per-screen "open in drawer" affordances reuse one composable
 * - makes the back-stack semantics trivial: choose a tile, get the screen
 *
 * Test surface:
 * - Tags every row with [Tags.drawerItemFor] for Compose UI tests.
 * - Avatar row is [Tags.DRAWER_AVATAR] for the (later) profile screen.
 */
@Composable
fun ToolsPane(
    selected: MainScreenTab,
    onSelect: (MainScreenTab) -> Unit,
    avatarFallbackInitials: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    Surface(
        modifier = modifier
            .width(280.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars)
                .testTag(Tags.TOOLS_PANE)
        ) {
            // Header: avatar + display initial — non-tap target for Wave 8.1;
            // a future profile sheet can be wired here without touching nav.
            DrawerHeader(
                avatarFallbackInitials = avatarFallbackInitials,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            )

            // Divider (light) keeps header from bleeding into items.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // Body: scrollable list of tools. Adding a screen? Add a
            // MainScreenTab enum value + matching tile; body stays symmetric.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(Tags.TOOLS_PANE_LIST),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(MainScreenTab.visibleOrder) { tab ->
                    DrawerItem(
                        tab = tab,
                        selected = selected == tab,
                        onClick = { onSelect(tab) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(Tags.drawerItemFor(tab))
                    )
                }
            }

            // Footer: app version + signature; cheap, no click target.
            DrawerFooter(
                versionName = versionName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun DrawerHeader(
    avatarFallbackInitials: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatarFallbackInitials.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "JKPHermex",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Personal AI console",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerFooter(versionName: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            versionName?.let { "v$it" } ?: "JKPHermex",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Native JKP control surface",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One row of the drawer. Mirrors the rail item shape so the same haptic +
 * ripple feel works; picks tone via [selected] and lets the click ripple
 * stay default. 48dp minimum keeps it inside Android's touch-target guideline.
 */
@Composable
private fun DrawerItem(
    tab: MainScreenTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = fg,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = tab.label,
            color = fg,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * MainScreenTab — closed set of top-level tools exposed from the phone drawer.
 *
 * The visible order is fixed (ToolsPane uses [visibleOrder] for stable
 * tests + predictable muscle memory). Future additions: append to the end.
 *
 * [key] is the stable persisted value; never reorder or rename — it shows
 * up in any future analytics.
 *
 * We deliberately mirror MainActivity's private `Screen` enum here so
 * DrawerPane.kt remains dependency-light. MainActivity translates between
 * the two via [screenToTab] / [tabToScreen].
 */
enum class MainScreenTab(
    val key: String,
    val label: String,
    val icon: ImageVector
) {
    NewChat("new", "New chat", Icons.Filled.Add),
    Sessions("sessions", "Sessions", Icons.Filled.ChatBubbleOutline),
    Projects("projects", "Projects", Icons.Filled.Folder),
    Tasks("tasks", "Tasks", Icons.Filled.DateRange),
    Skills("skills", "Skills", Icons.Filled.Build),
    Memory("memory", "Memory", Icons.Filled.Face),
    Insights("insights", "Insights", Icons.Filled.Info),
    Notes("notes", "Notes", Icons.Filled.Description),
    Prompts("prompts", "Prompts", Icons.Filled.AutoAwesome),
    Settings("settings", "Settings", Icons.Filled.Settings);

    companion object {
        /**
         * Visual order in the drawer. Do NOT alphabetize — operators trained
         * on Wave 6 expect "New chat → Sessions → Settings" at the top.
         */
        val visibleOrder: List<MainScreenTab> = listOf(
            NewChat,
            Sessions,
            Projects,
            Tasks,
            Skills,
            Memory,
            Insights,
            Notes,
            Prompts,
            Settings,
        )

        /**
         * Lookup by stable key. Returns null on unknown (forward-compat:
         * newer builds may serialize a tab the old build doesn't know).
         */
        fun fromKey(key: String): MainScreenTab? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Stable Compose UI test tags. Centralised here so test code can import
 * one symbol and the production reference is the source of truth for
 * what exists.
 */
object Tags {
    const val TOOLS_PANE = "tools_pane"
    const val TOOLS_PANE_LIST = "tools_pane_list"
    const val DRAWER_AVATAR = "drawer_avatar"
    const val DRAWER_TOGGLE = "drawer_toggle"

    fun drawerItemFor(tab: MainScreenTab): String = "drawer_item_${tab.key}"
}

/**
 * PhoneDrawerScaffold — the phone-width host of a ModalNavigationDrawer
 * wrapping arbitrary content (today: the chat screen). Used ONLY when
 * [LocalConfiguration].screenWidthDp < 600dp; tablet keeps its always-on
 * SidebarRailLayout from Wave 6.
 *
 * Behavior contract:
 * - Drawer starts CLOSED each composition (matches the user's expectation
 *   that "opening the app lands me at chat, not in the menu").
 * - Tapping ☰ toggles drawer open/closed.
 * - Predictive-back gesture (Android 14+) closes the drawer before app exit.
 * - Selecting a tab programmatically ([onSelect]) AND tells the parent to
 *   swap its screen content; the drawer then auto-closes.
 *
 * ExperimentalMaterial3Api is opted-in at the composable level — the
 * underlying APIs were stable from Material3 1.3+, but Compose still
 * tags the drawer composables experimental in some BOM releases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDrawerScaffold(
    selected: MainScreenTab,
    onSelect: (MainScreenTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (openDrawer: () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The M3 drawer API closes itself on item-click only when invoked via the
    // built-in `gesturesEnabled` modifier; we close programmatically so the
    // tap → navigate → close sequence feels synchronous.
    val handleSelect: (MainScreenTab) -> Unit = { tab ->
        onSelect(tab)
        scope.launch { drawerState.close() }
    }

    // Back-press handling: if drawer is open, swallowing back just closes it.
    // When closed, fall through to the per-screen BackHandler already wired
    // up by RenderScreen().
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ToolsPane(
                selected = selected,
                onSelect = handleSelect,
                avatarFallbackInitials = "JK",
            )
        },
    ) {
        content {
            scope.launch {
                if (drawerState.isOpen) drawerState.close() else drawerState.open()
            }
        }
    }
}
