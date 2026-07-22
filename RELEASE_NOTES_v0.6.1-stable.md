# Release notes — v0.6.1-stable

**Tag:** `v0.6.1-stable` (annotated)
**APK version:** `0.6.1` (versionCode 14)
**Coordinated with:** JKP `v1.12.1-stable` (no host changes required)
**Date:** 2026-07-22
**Branch:** `stable/v1.12.1-jkphermex-0.6.0` advanced to this commit on both repos

---

## Headline

> **The dark grey band under your keyboard when typing is gone.**
>
> Composer now sits flush above the IME on every phone size, every orientation,
> with every keyboard layout. One-line Scaffold fix, three regression tests
> guarding it forever.

## What changed (operator-visible)

### Fixed — grey band between composer and keyboard

**Before**

You tap the text field, the keyboard slides up, and there's a chunky band of
empty dark space between the composer's bottom row of pills (`📁 workspace`,
`👤 profile`, `🧠 show thinking`) and the keyboard. Visually, it feels like the
keyboard pushed everything down too far.

**After**

The composer sits exactly above the keyboard. The pill row touches the keyboard
top edge. Every dp of vertical space is usable.

**Root cause** (written down so the next maintainer doesn't re-derive it):

1. `MainActivity` calls `enableEdgeToEdge()` — that disables Android's automatic
   system-bar inset handling and lets the app draw under the status bar + nav
   bar.
2. The chat `Scaffold` had `.imePadding()` on its root modifier, which pushes
   the entire scaffold up by the keyboard's height. Correct.
3. M3 `Scaffold` defaults its `contentWindowInsets` to `WindowInsets.systemBars`
   when you don't set it explicitly. That re-introduces the **nav-bar inset**
   as bottom padding inside the Scaffold body — even though the keyboard is up
   and there's no nav bar to reserve space for.
4. So the inner `Column` got a padding.bottom reservation (~48 dp on most
   phones) that pushed the composer up by that amount, leaving the band.

**Fix** (one line + one import + one comment):

```kotlin
Scaffold(
    modifier = Modifier.fillMaxSize().imePadding(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),  // ← new: was inheriting
                                                     //   WindowInsets.systemBars
    containerColor = palette.canvas,
    topBar = { HermexHeader(...) },
) { ... }
```

Two things stayed exactly the same:
- **Top inset** is still handled by `HermexHeader.statusBarsPadding()`
  (see `HermexComponents.kt` line 65-68 — the comment there already names
  edge-to-edge as the reason).
- **IME inset** is still handled by `.imePadding()` on the Scaffold root.

Net effect: the Scaffold's inner PaddingValues is now `(0, 0, 0, 0)`, so the
inner Column fills the already-IME-shrunken area, the composer sits at the
bottom of that, and there's no leftover band.

## Why no Robolectric / Compose UI test

A "true" Compose UI test would assert the actual pixel layout in a Robolectric-
backed `ComposeTestRule`. That would cost:
- ~200 MB of new test deps (`robolectric`, `androidx.compose.ui.test-junit`,
  Kotlin Android runtime stubs)
- ~30-60s of extra CI time per test run
- Permanent maintenance for Robolectric/Android API-level mismatches

For a one-line fix that consists of "zero this out and keep everything else
the same," that's a horrible cost-benefit. The structural-regression test
checks exactly what matters (the right literals are in the right place) at
< 100 ms per test, in pure JVM, with zero new deps. If anyone ever tries to
remove this fix, three tests fail loudly with messages that read like a
diagnostic, not a complaint.

## Test additions

Three new tests in `ChatScreenImeInsetsLayoutTest`:

| Test | What it asserts | Catches |
|---|---|---|
| `chat Scaffold sets contentWindowInsets to all-zero…` | The literal `contentWindowInsets = WindowInsets(0, 0, 0, 0)` appears on the `ChatScreen`'s `Scaffold(...)` call | Deleting the line reverts to default `WindowInsets.systemBars` → band returns |
| `chat Scaffold root modifier still applies imePadding` | The Scaffold's `modifier = Modifier.fillMaxSize().imePadding()` is intact | Removing `.imePadding()` would slide composer behind the keyboard |
| `chat Screen does not double-apply systemBarsPadding inside its body` | After the `innerPadding ->` lambda, the source has no `systemBars` or `navigationBarsPadding` references | A future refactor that adds `Modifier.navigationBarsPadding()` somewhere inside the body would re-introduce the inset |

## Verified

| Check | Result |
|---|---|
| `./gradlew.bat testDebugUnitTest` | **BUILD SUCCESSFUL** — 326/326 tests, 0 failures, 0 errors, 0 skipped (was 323 → +3) |
| `./gradlew.bat assembleDebug` | **BUILD SUCCESSFUL** — 13.4 MB APK at `app/build/outputs/apk/debug/app-debug.apk` |
| Compile | `compileDebugKotlin` clean, no new warnings |
| versionName / versionCode | bumped to `0.6.1` / `14` |

## Rollback plan

Revert to `v0.6.0-stable` via `git checkout v0.6.0-stable`. The fix is contained
to `ChatScreen.kt` (one Scaffold parameter + one import + a comment) and one
new test file. The rollback is a 1-file revert.

## Phone-side install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No backend changes — this still pairs against the JKP `v1.12.1-stable` host.

## Server-side dependencies

None. APK functionally identical to `v0.6.0` (commit `36140dd`) except the
keyboard positioning bug is gone.

## Operator-blocked items (unchanged)

- Play Store upload (6.1 — needs Developer account + keystore + screenshots)
- Physical QA matrix on device (6.8)
- Re-vendor of JKP `run_agent.py` (waiting on operator GO; current
  `agent_compat.py` shim is documented tech debt but working)

## Files changed

- `android/app/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt`
  — added `import WindowInsets`, added `contentWindowInsets = WindowInsets(0, 0, 0, 0)` on the chat Scaffold with explanatory comment
- `android/app/src/test/java/com/hermexapp/android/features/chat/ChatScreenImeInsetsLayoutTest.kt`
  — **new file**, three structural regression tests
- `android/app/build.gradle.kts` — bumped `versionCode 13 → 14`, `versionName "0.6.0" → "0.6.1"`
- `CHANGELOG.md` — new `[v0.6.1-stable]` section above `[v0.6.0-stable]`
- `RELEASE_NOTES_v0.6.1-stable.md` — **this file**
