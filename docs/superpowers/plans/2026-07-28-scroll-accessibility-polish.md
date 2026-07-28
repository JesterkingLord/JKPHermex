# Scroll and Accessibility Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the competing chat arrows with one accessible jump-to-latest control, make the fast scrollbar adjustable with TalkBack, and bring every audited custom Android target to a calm, usable 48 dp minimum.

**Architecture:** Preserve `FastScrollbar`'s measured-pixel geometry and three-pass settling as the only scroll engine. Put small, testable decisions in pure Kotlin helpers, keep compact visuals inside 48 dp Compose shells, and use one shared `AccentSwatch` for selection semantics. JVM tests cover decisions and normalization; UIAutomator/TalkBack on the OPPO prove Compose bounds, semantics, reflow, and real scrolling without adding a dependency.

**Tech Stack:** Kotlin 2.1, Jetpack Compose Material 3, Room 2.7, JUnit 4, Gradle wrapper, Android SDK/ADB, OPPO CPH2343 API 33.

## Global Constraints

- Work on `feat/wave-9-premium-polish`, never `master`.
- Add no dependency and change no API endpoint, JSON model, persistence schema, server behavior, version, or release metadata.
- Preserve measured-height caching, inverse pixel targeting, endpoint snapping, three-pass settling, tap/drag behavior, and the 60-second fade policy.
- Use an outer 48 dp native touch target; compact visuals may remain 32-40 dp.
- Derive names, roles, values, disabled state, and selection state from real component behavior; inner icons are decorative.
- Write and observe a failing test before every production behavior change. For Compose-only bounds that cannot be covered without a new dependency, a failing physical UIAutomator measurement is the RED test.
- Keep phone data intact. Instrumentation creates its fixture in the test APK's sandbox, never `com.hermexapp.android`'s production database.
- Keep UI dumps and screenshots under `screenshots/device-qa-2026-07-27/` and out of Git.
- Commit locally after each independently verified task; do not push, tag, open a PR, merge, or distribute without new operator approval.

---

### Task 1: Single Jump-to-Latest Control

**Files:**
- Modify: `android/app/src/test/java/com/hermexapp/android/ui/ScrollIndicatorOnlyTest.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/ui/JumpFab.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `LazyListState.canScrollForward`, `LazyListState.animateScrollToItem(Int)`, and `ChatViewModel.markSeen()`.
- Produces: `internal fun shouldShowJumpToLatest(canScrollForward: Boolean): Boolean` and `@Composable fun JumpToLatestButton(canScrollForward: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Replace the stale grace-window tests with the desired visibility contract**

```kotlin
class ScrollIndicatorOnlyTest {
    @Test
    fun `jump to latest is visible when content exists below`() {
        assertTrue(shouldShowJumpToLatest(canScrollForward = true))
    }

    @Test
    fun `jump to latest is hidden at the bottom or when content fits`() {
        assertFalse(shouldShowJumpToLatest(canScrollForward = false))
    }
}
```

The production mutation this catches is rendering the control from `canScrollBackward`, scroll activity, or a stale timeout instead of whether content exists below.

- [ ] **Step 2: Run the focused test and observe RED**

Run:

```powershell
cd E:\JKPHermex\android
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.ScrollIndicatorOnlyTest --console=plain
```

Expected: compilation fails because `shouldShowJumpToLatest` does not exist.

- [ ] **Step 3: Replace the legacy dual-arrow implementation**

Delete `HIDE_DELAY_MS`, `decideScrollIndicatorVisibility`, `ScrollIndicatorOnly`, `DirectionPill`, their obsolete history, and the upward-arrow imports. Implement:

```kotlin
internal fun shouldShowJumpToLatest(canScrollForward: Boolean): Boolean = canScrollForward

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
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Wire the chat to the single action and protected lane**

Import `JumpToLatestButton`, replace `ScrollIndicatorOnly`, remove `canScrollBackward` and `onScrollUp`, and use:

```kotlin
JumpToLatestButton(
    canScrollForward = listState.canScrollForward,
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 56.dp, bottom = 24.dp),
    onClick = {
        scope.launch {
            if (listState.canScrollForward && state.entries.isNotEmpty()) {
                listState.animateScrollToItem(state.entries.lastIndex)
                viewModel.markSeen()
            }
        }
    },
)
```

Replace the contradictory Wave 9.6-9.13 comment block with a short explanation of the one-button contract.

- [ ] **Step 5: Run focused tests and compile**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.ScrollIndicatorOnlyTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```

Expected: both exit 0.

- [ ] **Step 6: Review and commit**

```powershell
git diff --check
git add android/app/src/test/java/com/hermexapp/android/ui/ScrollIndicatorOnlyTest.kt android/app/src/main/java/com/hermexapp/android/ui/JumpFab.kt android/app/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt
git commit -m "feat(android): add one jump-to-latest control"
```

---

### Task 2: TalkBack-Adjustable Fast Scrollbar

**Files:**
- Modify: `android/app/src/test/java/com/hermexapp/android/ui/FastScrollbarFractionTest.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/ui/FastScrollbar.kt`

**Interfaces:**
- Consumes: existing local suspend function `settleAtFraction(fraction: Float, animateFirstPass: Boolean)`.
- Produces: `internal fun normalizeRequestedScrollFraction(requested: Float): Float?` and `internal fun buildScrollStateDescription(position: Float): String`.

- [ ] **Step 1: Add failing normalization and user-copy tests**

```kotlin
@Test
fun `accessibility progress accepts finite values and clamps endpoints`() {
    assertEquals(0f, normalizeRequestedScrollFraction(-0.4f))
    assertEquals(0.375f, normalizeRequestedScrollFraction(0.375f))
    assertEquals(1f, normalizeRequestedScrollFraction(1.4f))
}

@Test
fun `accessibility progress rejects non finite values`() {
    assertNull(normalizeRequestedScrollFraction(Float.NaN))
    assertNull(normalizeRequestedScrollFraction(Float.POSITIVE_INFINITY))
    assertNull(normalizeRequestedScrollFraction(Float.NEGATIVE_INFINITY))
}

@Test
fun `scroll position description is user facing and locale stable`() {
    val previous = Locale.getDefault()
    try {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("25 percent through content", buildScrollStateDescription(0.25f))
    } finally {
        Locale.setDefault(previous)
    }
}
```

Remove the tests that pin `FastScrollbar pos=... size=...` as spoken copy. The production mutations caught are accepting `NaN`, failing to clamp, or exposing instrumentation jargon to TalkBack.

- [ ] **Step 2: Run the focused test and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.FastScrollbarFractionTest --console=plain
```

Expected: compilation fails because the two new helpers do not exist.

- [ ] **Step 3: Implement the pure accessibility contract**

```kotlin
internal fun normalizeRequestedScrollFraction(requested: Float): Float? =
    requested.takeIf(Float::isFinite)?.coerceIn(0f, 1f)

internal fun buildScrollStateDescription(position: Float): String =
    "${(position.coerceIn(0f, 1f) * 100f).roundToInt()} percent through content"
```

Keep `buildScrollSemantics` only if debug instrumentation still consumes it; do not assign it to `contentDescription`.

- [ ] **Step 4: Add Name, Role, Value, and SetProgress to the rendered node**

Import `setProgress`, then update the existing semantics block:

```kotlin
.semantics {
    contentDescription = "Scroll position"
    stateDescription = buildScrollStateDescription(positionFraction)
    progressBarRangeInfo = ProgressBarRangeInfo(positionFraction, 0f..1f, 0)
    setProgress { requested ->
        val fraction = normalizeRequestedScrollFraction(requested)
            ?: return@setProgress false
        scope.launch { settleAtFraction(fraction, animateFirstPass = true) }
        true
    }
}
```

Do not change geometry, hit width, pointer input, fade timing, or settle passes.

- [ ] **Step 5: Run focused scrollbar suites**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.FastScrollbarFractionTest --tests com.hermexapp.android.ui.FastScrollbarLayoutTest --tests com.hermexapp.android.ui.FastScrollbarLetterIndexTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```

Expected: both exit 0.

- [ ] **Step 6: Review and commit**

```powershell
git diff --check
git add android/app/src/test/java/com/hermexapp/android/ui/FastScrollbarFractionTest.kt android/app/src/main/java/com/hermexapp/android/ui/FastScrollbar.kt
git commit -m "feat(android): make fast scrollbar accessible"
```

---

### Task 3: Shared Accessible Accent Swatch

**Files:**
- Create: `android/app/src/main/java/com/hermexapp/android/ui/AccentSwatch.kt`
- Create: `android/app/src/test/java/com/hermexapp/android/ui/AccentSwatchTest.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/sessionlist/ProjectsScreen.kt`

**Interfaces:**
- Consumes: `AccentPreset(displayName, hex)`, `accentColorFromHex`, and `circleButtonTouchTargetDp(32)`.
- Produces: `internal fun accentSwatchForeground(background: Color): Color` and `@Composable fun AccentSwatch(preset: AccentPreset, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Add failing contrast-helper tests**

```kotlin
class AccentSwatchTest {
    @Test
    fun `light swatches use a dark selection mark`() {
        assertEquals(Color.Black, accentSwatchForeground(Color.White))
        assertEquals(Color.Black, accentSwatchForeground(Color(0xFFFFD700)))
    }

    @Test
    fun `dark swatches use a light selection mark`() {
        assertEquals(Color.White, accentSwatchForeground(Color(0xFF1C1C1E)))
    }
}
```

The production mutation caught is rendering an invisible white check on light presets or a black check on dark presets.

- [ ] **Step 2: Run the focused test and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.AccentSwatchTest --console=plain
```

Expected: compilation fails because `accentSwatchForeground` does not exist.

- [ ] **Step 3: Implement the shared 48/32 dp component**

```kotlin
internal fun accentSwatchForeground(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

@Composable
fun AccentSwatch(
    preset: AccentPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swatch = accentColorFromHex(preset.hex)
    Box(
        modifier = modifier
            .size(circleButtonTouchTargetDp(32).dp)
            .semantics {
                contentDescription = preset.displayName
                role = Role.Button
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(swatch, CircleShape)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = accentSwatchForeground(swatch))
            }
        }
    }
}
```

- [ ] **Step 4: Replace both bespoke swatch loops**

In Settings, render every preset with `AccentSwatch(preset, accent == preset) { prefs.setAccent(preset) }` and always render the name below, using selected color only as a secondary cue. In Projects, render `AccentSwatch(preset, color.equals(preset.hex, ignoreCase = true)) { color = preset.hex }`. Remove obsolete `Box`, `clip`, `CircleShape`, and direct color imports only when unused.

- [ ] **Step 5: Run focused tests and compile**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.ui.AccentSwatchTest --tests com.hermexapp.android.ui.HermexComponentsTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```

Expected: both exit 0.

- [ ] **Step 6: Review and commit**

```powershell
git diff --check
git add android/app/src/main/java/com/hermexapp/android/ui/AccentSwatch.kt android/app/src/test/java/com/hermexapp/android/ui/AccentSwatchTest.kt android/app/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt android/app/src/main/java/com/hermexapp/android/features/sessionlist/ProjectsScreen.kt
git commit -m "feat(android): add accessible accent swatches"
```

---

### Task 4: Protected Sessions Header and Real Bulk Disabled State

**Files:**
- Create: `android/app/src/test/java/com/hermexapp/android/features/sessionlist/BulkSessionActionsBarTest.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/sessionlist/BulkSessionActionsBar.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/sessionlist/SessionListScreen.kt`
- Delete: `android/app/src/test/java/com/hermexapp/android/features/sessionlist/LiveClockTest.kt`

**Interfaces:**
- Produces: `internal fun bulkMutationEnabled(selectedCount: Int): Boolean`.
- Preserves: cancel/select-all always enabled; pin/archive/delete enabled only for positive selection.

- [ ] **Step 1: Add the failing bulk-action contract test**

```kotlin
class BulkSessionActionsBarTest {
    @Test
    fun `bulk mutations are disabled without selected sessions`() {
        assertFalse(bulkMutationEnabled(0))
        assertFalse(bulkMutationEnabled(-1))
    }

    @Test
    fun `bulk mutations are enabled with a selected session`() {
        assertTrue(bulkMutationEnabled(1))
        assertTrue(bulkMutationEnabled(20))
    }
}
```

The production mutation caught is exposing a clickable no-op mutation when selection is empty.

- [ ] **Step 2: Run the focused test and observe RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.features.sessionlist.BulkSessionActionsBarTest --console=plain
```

Expected: compilation fails because `bulkMutationEnabled` does not exist.

- [ ] **Step 3: Implement true disabled behavior and 48 dp shells**

Add `bulkMutationEnabled`, pass `enabled` into each mutation `ActionIcon`, and change `ActionIcon` to a 48 dp outer node with a 40 dp inner circle. The outer node owns `contentDescription`, `Role.Button`, click, and `disabled()` semantics; the inner icon has `contentDescription = null`.

```kotlin
internal fun bulkMutationEnabled(selectedCount: Int): Boolean = selectedCount > 0

@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(circleButtonTouchTargetDp(40).dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(LocalHermexPalette.current.bubble, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
```

For pin/archive/delete, pass `enabled = bulkMutationEnabled(selectedCount)` and pass the original callbacks directly; remove the guarded no-op lambdas.

- [ ] **Step 4: Reserve the scrollbar lane and remove duplicate clock**

Change the wordmark row to `.padding(start = 16.dp, end = 56.dp, top = 12.dp, bottom = 12.dp)`. Remove the `LiveClock()` call, function, `minuteBucket`, and now-unused time/date state imports. Delete `LiveClockTest.kt`; it tested a feature that no longer exists.

- [ ] **Step 5: Run focused tests and compile**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.features.sessionlist.BulkSessionActionsBarTest --tests com.hermexapp.android.ui.HermexComponentsTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```

Expected: both exit 0.

- [ ] **Step 6: Review and commit**

```powershell
git diff --check
git add android/app/src/test/java/com/hermexapp/android/features/sessionlist/BulkSessionActionsBarTest.kt android/app/src/main/java/com/hermexapp/android/features/sessionlist/BulkSessionActionsBar.kt android/app/src/main/java/com/hermexapp/android/features/sessionlist/SessionListScreen.kt android/app/src/test/java/com/hermexapp/android/features/sessionlist/LiveClockTest.kt
git commit -m "fix(android): protect session list controls"
```

---

### Task 5: Minimum-Height Interactive Rows

**Files:**
- Modify: `android/app/src/main/java/com/hermexapp/android/features/sessionlist/ProjectsScreen.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/chat/ComposerControls.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/workspace/WorkspaceScreens.kt`
- Modify: `android/app/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt`

**Interfaces:**
- Produces no new public API; each custom clickable/combined-clickable node below owns at least a 48 dp measured height.

- [ ] **Step 1: Capture the physical RED evidence**

Install the current pre-change debug APK with `adb install -r`, navigate to Projects, Settings, slash suggestions, workspace files/Git, and a short-message chat, then dump each accessibility tree:

```powershell
adb shell uiautomator dump /sdcard/hermex-red.xml
adb exec-out cat /sdcard/hermex-red.xml > screenshots/device-qa-2026-07-27/accessibility-red.xml
```

Record at least one target below 144 px on the 3x-density OPPO. Existing verified failures are the 108 px Projects session row and 72-102 px swatches; the fresh dump makes the RED evidence reproducible.

- [ ] **Step 2: Add 48 dp minimum height to audited rows**

Apply `heightIn(min = 48.dp)` before the click modifier so the semantic/click node owns the full bound:

```kotlin
Modifier
    .fillMaxWidth()
    .heightIn(min = 48.dp)
    .clickable(onClick = onClick)
```

Use that ordering for:

- `ProjectSessionRow`;
- Settings default-model row and `AboutLinkRow`;
- every `SlashSuggestionList` item and removable attachment surface;
- workspace browser rows and Git file rows;
- short user-message surfaces, assistant-message surfaces, expandable tool-call surfaces, and `ThinkingCard`.

Do not make noninteractive notices or tool calls without a preview clickable. Preserve existing typography, padding, action callbacks, colors, and shapes.

Use these exact modifier shapes at the audited call sites:

```kotlin
// Projects session row
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)

// Settings default-model row
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showModelPicker = true }

// Settings About row
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)

// Slash suggestion
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(command) }

// Removable attachment chip
Surface(
    color = palette.card,
    shape = CircleShape,
    onClick = { viewModel.removeAttachment(attachment) },
    modifier = Modifier.heightIn(min = 48.dp),
)

// Workspace file/directory row
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
    val path = entry.path ?: return@clickable
    if (entry.isBrowsableDirectory) viewModel.loadDirectory(path) else viewModel.openFile(path)
}

// Workspace Git row
Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
    onClick = { file.path?.let { viewModel.openDiff(it, file.staged) } },
    onLongClick = { actionFile = file },
)

// User and assistant message action surfaces
Modifier.widthIn(max = 320.dp).heightIn(min = 48.dp).combinedClickable(
    onClick = { showEdit = true },
    onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        showEdit = true
    },
)

Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
    onClick = { showActions = true },
    onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        showActions = true
    },
)

// Tool call; leave preview-less cards noninteractive
Modifier.fillMaxWidth().then(
    if (hasPreview) {
        Modifier.heightIn(min = 48.dp).clickable { userToggled = !expanded }
    } else {
        Modifier
    },
)

// Thinking card
Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
    userToggled = !expanded
}
```

- [ ] **Step 3: Compile and run the directly related unit suites**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.hermexapp.android.features.chat.ComposerControlsTest --tests com.hermexapp.android.ui.HermexComponentsTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```

Expected: both exit 0.

- [ ] **Step 4: Install and observe GREEN bounds**

```powershell
.\gradlew.bat assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell uiautomator dump /sdcard/hermex-green.xml
adb exec-out cat /sdcard/hermex-green.xml > screenshots/device-qa-2026-07-27/accessibility-green.xml
```

On the OPPO, every audited node must measure at least 144 px high. Verify taps still invoke the same action and rows do not clip at 1.35x text.

- [ ] **Step 5: Review and commit**

```powershell
git diff --check
git add android/app/src/main/java/com/hermexapp/android/features/sessionlist/ProjectsScreen.kt android/app/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt android/app/src/main/java/com/hermexapp/android/features/chat/ComposerControls.kt android/app/src/main/java/com/hermexapp/android/features/workspace/WorkspaceScreens.kt android/app/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt
git commit -m "fix(android): enforce accessible row targets"
```

Do not stage either XML dump.

---

### Task 6: Isolated End-to-End Room v2-to-v3 Fixture

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt`

**Interfaces:**
- Consumes: `HermexDatabase.build(Context)`, `NotesDao.get(String)`, and `PromptsDao.get(String)`.
- Produces: an Android framework `Instrumentation` runner in test package `com.hermexapp.android.test`; it never opens the installed app's database directory.

- [ ] **Step 1: Configure the existing Android test APK without adding a dependency**

Add this existing-SDK runner setting inside `defaultConfig`:

```kotlin
testInstrumentationRunner = "com.hermexapp.android.persistence.MigrationInstrumentation"
```

- [ ] **Step 2: Implement the isolated migration fixture**

Create a runner extending `android.app.Instrumentation`. In `onStart`, resolve `context.getDatabasePath("hermex.db")`, assert that its path contains `.test`, create the three exact v2 tables with `android.database.sqlite.SQLiteDatabase`, insert one note and one prompt, set `database.version = 2`, close SQLite, and open `HermexDatabase.build(context)`. With `runBlocking`, assert:

```kotlin
package com.hermexapp.android.persistence

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import kotlinx.coroutines.runBlocking

class MigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val testContext = context
        val databaseName = "hermex.db"
        val databaseFile = testContext.getDatabasePath(databaseName)
        var room: HermexDatabase? = null
        try {
            check(testContext.packageName.endsWith(".test")) {
                "Refusing to create migration fixture outside the test package"
            }
            check(databaseFile.absolutePath.contains(testContext.packageName)) {
                "Fixture path does not belong to ${testContext.packageName}"
            }
            testContext.deleteDatabase(databaseName)
            databaseFile.parentFile?.mkdirs()

            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqlite ->
                sqlite.execSQL(
                    "CREATE TABLE cached_payloads (`key` TEXT NOT NULL, json TEXT NOT NULL, " +
                        "fetchedAtMillis INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                sqlite.execSQL(
                    "CREATE TABLE local_notes (id TEXT NOT NULL, title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, color_hex TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                        "updated_at_millis INTEGER NOT NULL, created_at_millis INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))",
                )
                sqlite.execSQL(
                    "CREATE TABLE local_prompts (id TEXT NOT NULL, name TEXT NOT NULL, " +
                        "body TEXT NOT NULL, tags TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                        "usage_count INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL, " +
                        "created_at_millis INTEGER NOT NULL, PRIMARY KEY(id))",
                )
                sqlite.execSQL(
                    "INSERT INTO local_notes VALUES " +
                        "('note-1','Keep me','Preserved body','#FFE9A2',1,200,100)",
                )
                sqlite.execSQL(
                    "INSERT INTO local_prompts VALUES " +
                        "('prompt-1','Keep prompt','Prompt body','tag',0,7,201,101)",
                )
                sqlite.version = 2
            }

            room = HermexDatabase.build(testContext)
            runBlocking {
                val note = room.notesDao().get("note-1")
                check(note?.title == "Keep me")
                check(note.body == "Preserved body")
                check(note.status == NoteStatus.IDEA)

                val prompt = room.promptsDao().get("prompt-1")
                check(prompt?.name == "Keep prompt")
                check(prompt.body == "Prompt body")
                check(prompt.usageCount == 7)
            }
            room.close()
            room = null
            testContext.deleteDatabase(databaseName)
            finish(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString("stream", "PASS: Room v2-to-v3 preserved note and prompt")
                },
            )
        } catch (throwable: Throwable) {
            room?.close()
            testContext.deleteDatabase(databaseName)
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", throwable.stackTraceToString()) },
            )
        }
    }
}
```

- [ ] **Step 3: Build, install, and run the test APK**

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
$migrationOutput = adb shell am instrument -w com.hermexapp.android.test/com.hermexapp.android.persistence.MigrationInstrumentation
$migrationOutput
if ($migrationOutput -notmatch 'PASS: Room v2-to-v3 preserved note and prompt' -or $migrationOutput -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'Room migration instrumentation failed' }
```

Expected: PASS text and `INSTRUMENTATION_CODE: -1`. Confirm the installed app still shows the operator's original Notes and Prompts; the runner's fixture existed only in the `.test` package.

- [ ] **Step 4: Replace the source-string migration unit test**

Delete `android/app/src/test/java/com/hermexapp/android/persistence/HermexDatabaseMigrationTest.kt`. Its assertions inspect SQL text and are superseded by the real Room-open fixture.

- [ ] **Step 5: Review and commit**

```powershell
git diff --check
git add android/app/build.gradle.kts android/app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt android/app/src/test/java/com/hermexapp/android/persistence/HermexDatabaseMigrationTest.kt
git commit -m "test(android): verify Room migration on device"
```

---

### Task 7: Full Automated Regression Gate

**Files:**
- Modify only if a failure reveals a root cause and receives a new failing regression test first.

**Interfaces:**
- Produces fresh evidence for debug/release unit tests, lint, both APKs, and the complete build.

- [ ] **Step 1: Run all focused debug/release checks**

```powershell
cd E:\JKPHermex\android
.\gradlew.bat testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug assembleRelease --console=plain
```

Expected: exit 0; no unit-test failure; lint has zero errors; both APKs exist.

- [ ] **Step 2: Run the complete Android build**

```powershell
.\gradlew.bat build --console=plain
```

Expected: `BUILD SUCCESSFUL` and exit 0.

- [ ] **Step 3: Inspect reports rather than inferring counts**

Read every XML under `app/build/test-results/testDebugUnitTest` and `testReleaseUnitTest`; sum `tests`, `failures`, `errors`, and `skipped`. Read `app/build/reports/lint-results-debug.txt` and verify `0 errors`. Run `git diff --check` and inspect `git status --short` so QA artifacts and operator notes remain unstaged.

---

### Task 8: Connected-OPPO Acceptance and Completion Audit

**Files:**
- Modify: `docs/PLAN_AND_ROADMAP.md`
- Modify locally only: `CURRENT.md`
- Modify: `CHANGELOG.md` only if it contains a current unreleased section for these changes; do not bump a version.

**Interfaces:**
- Produces physical evidence for scroll accuracy, accessibility semantics, target bounds, dark/light mode, 1.35x text, and IME regression safety.

- [ ] **Step 1: Install the freshly built debug APK without clearing data**

```powershell
adb install -r E:\JKPHermex\android\app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.hermexapp.android
adb shell monkey -p com.hermexapp.android -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 2: Verify the single jump action and pixel scrollbar**

In the 125-message scrollbar-design chat, capture top, mid-track tap, continuous drag, and bottom. Require exact debug fractions `0.000`, `0.500` for the mid tap after settling, a continuously changing drag fraction, and `1.000`. At midpoint require exactly one `Scroll to latest` node, no `Scroll to top` node, a minimum 144x144 px shell, and no intersection with the rightmost 144 px scrollbar lane. Tap it and require the latest entry plus disappearance of the action.

- [ ] **Step 3: Verify TalkBack without risking session mutation**

Complete TalkBack's first-run tutorial before returning to Hermex. Focus `Scroll position`; require the announced percentage and use TalkBack's adjustable action in both directions. Confirm the list moves and the percentage changes without dragging. Do not swipe from a session row until TalkBack focus is visibly established. Afterward restore the exact prior accessibility service state.

- [ ] **Step 4: Verify header, swatches, bulk actions, and rows**

With Sessions scrollbar visible, require Search bounds at least 144x144 px and no overlap with the scrollbar lane. In Settings and Projects, require every swatch to announce its color and selected state, render a visible outline/check, and measure at least 144x144 px. Enter bulk selection: with zero selected, pin/archive/delete must announce disabled and not invoke; with one selected, each must be focusable in a 144x144 px shell. Check all Task 5 rows against the same minimum.

- [ ] **Step 5: Repeat reflow/theme and IME checks**

Capture core screens in dark and light themes at font scale 1.0, then at OPPO Large 1.35x. Require no clipped labels, no overlapping controls, and no target shrinking. Reopen Gboard in chat and require the composer metadata rail to meet the keyboard directly with no gray gap. Restore dark theme, font scale 1.0, and the original accessibility state exactly.

- [ ] **Step 6: Audit every approved requirement against evidence**

Re-read `docs/superpowers/specs/2026-07-28-scroll-accessibility-polish-design.md` and this plan. For each behavior and physical acceptance item, point to a test result, UIAutomator bound, log fraction, screenshot, or device observation. Treat missing or indirect evidence as incomplete and continue fixing through red-green cycles.

- [ ] **Step 7: Update resumable documentation and commit only tracked release notes**

Update `docs/PLAN_AND_ROADMAP.md` and local-only `CURRENT.md` with exact commands/results and remaining gaps. Update `CHANGELOG.md` only in its existing unreleased section. Run `git diff --check`, stage only the intended tracked documentation, and commit with:

```powershell
git commit -m "docs: record accessibility device verification"
```

Do not stage `CURRENT.md`, screenshots, UI dumps, `prompts and notes/`, or unrelated operator files. Do not push or distribute.
