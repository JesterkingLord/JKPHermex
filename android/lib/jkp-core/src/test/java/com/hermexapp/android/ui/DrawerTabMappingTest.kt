package com.hermexapp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM unit tests for the [MainScreenTab] enum + drawerVisibleOrder
 * invariant. No Compose runtime is needed — these are importable by any
 * host and run fast.
 *
 * Why a separate file from the Compose-side ToolsPane tests: the tab
 * mapping is the *contract* between DrawerPane.kt and MainActivity, and
 * a tweak-invariant test catches accidental re-orders before the UI
 * even mounts.
 */
class DrawerTabMappingTest {

    @Test fun `visibleOrder lists every tab exactly once`() {
        val seen = MainScreenTab.visibleOrder
        // 1) No duplicates.
        assertEquals(
            "visibleOrder has ${seen.size} entries but enum has ${MainScreenTab.entries.size}",
            MainScreenTab.entries.size,
            seen.size,
        )
        // 2) Same set as entries().toList() — order may differ between
        //    Compose recompilation, but the SET must match.
        assertEquals(
            MainScreenTab.entries.toSet(),
            seen.toSet(),
        )
    }

    @Test fun `visibleOrder follows Wave 6 muscle memory - new and sessions first`() {
        val order = MainScreenTab.visibleOrder
        assertEquals(
            listOf(
                MainScreenTab.NewChat,
                MainScreenTab.Sessions,
                MainScreenTab.Projects,
                MainScreenTab.Tasks,
                MainScreenTab.Skills,
                MainScreenTab.Memory,
                MainScreenTab.Insights,
                MainScreenTab.Notes,
                MainScreenTab.Prompts,
                MainScreenTab.Settings,
            ),
            order,
        )
    }

    @Test fun `fromKey round-trips each tab`() {
        for (tab in MainScreenTab.entries) {
            assertSame(tab, MainScreenTab.fromKey(tab.key))
        }
    }

    @Test fun `fromKey returns null for unknown keys (forward-compat)`() {
        assertNull(MainScreenTab.fromKey("nope"))
        assertNull(MainScreenTab.fromKey(""))
        assertNull(MainScreenTab.fromKey("NEW"))  // case-sensitive on purpose
    }

    @Test fun `every tab has a non-empty label and key`() {
        for (tab in MainScreenTab.entries) {
            assert(tab.key.isNotBlank()) { "$tab has blank key" }
            assert(tab.label.isNotBlank()) { "$tab has blank label" }
        }
    }

    @Test fun `every tab key is unique`() {
        val keys = MainScreenTab.entries.map { it.key }
        assertEquals(
            "duplicate keys: $keys",
            keys.size,
            keys.toSet().size,
        )
    }

    @Test fun `every tab has a distinct testTag from drawerItemFor`() {
        val tags = MainScreenTab.entries.map { Tags.drawerItemFor(it) }
        assertEquals(
            "duplicate tags: $tags",
            tags.size,
            tags.toSet().size,
        )
        // Sanity: each tag starts with the documented prefix.
        for (tag in tags) {
            assert(tag.startsWith("drawer_item_")) { "tag $tag missing prefix" }
        }
    }
}
