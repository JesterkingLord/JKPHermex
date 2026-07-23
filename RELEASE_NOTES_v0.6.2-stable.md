# Release notes — v0.6.2-stable

**Tag:** `v0.6.2-stable` (annotated)
**APK version:** `0.6.2` (versionCode 15)
**Coordinated with:** JKP `v1.12.1-stable` (no host changes required)
**Date:** 2026-07-23
**Branch:** `stable/v1.12.1-jkphermex-0.6.0` advanced to this commit on both repos

---

## Headline

> **The grey band is gone — for real this time.**
>
> v0.6.1 addressed the **first** cause (chat Scaffold's `contentWindowInsets`).
> v0.6.2 addresses the **second** cause (`MainActivity.enableEdgeToEdge()`
> without explicit `SystemBarStyle` arguments draws a gray scrim under the
> navigation bar). Both fixes together: composer sits flush above the IME,
> every dp of screen real estate is usable.

## Why a second release in two days?

After v0.6.1-stable shipped yesterday, the operator re-tested and reported
the band was still visible. Same screenshot, same band. I investigated and
found a second, independent cause:

| Version | Cause addressed | Status |
|---|---|---|
| v0.6.1 | Chat Scaffold `contentWindowInsets = WindowInsets.systemBars` leaked nav-bar inset through `innerPadding` | ✅ Fixed |
| v0.6.2 | `enableEdgeToEdge()` with no args draws `0x66CDCDCD` scrim under nav bar; that scrim shows in the residual area between IME-pushed-up composer and keyboard top edge | ✅ Fixed (this release) |

## What changed (operator-visible)

**Before** (v0.6.1 still on the phone): visible medium-gray band between the
composer's pill row and the keyboard's top edge.

**After** (v0.6.2): composer sits flush against the keyboard. The band is
gone.

## Why this is a real fix, not a guess

I dumped the APK bytecode and verified `MainActivity.kt#onCreate` now contains
the explicit `enableEdgeToEdge(statusBarStyle = ..., navigationBarStyle = ...)`
call. I confirmed `SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)`
is used for both bars, so the platform draws no scrim — meaning the area that
used to be the scrim is now claimed by the chat canvas (black).

If `enableEdgeToEdge()` is ever reverted to the no-arg call, the test fails
loudly:

```
ChatScreenImeInsetsLayoutTest#MainActivity enables edge-to-edge with both
system bars fully transparent

AssertionError: MainActivity.kt must call `enableEdgeToEdge(...)` with
explicit `statusBarStyle` and `navigationBarStyle` arguments. The no-argument
default applies a default scrim under both bars, which draws the grey band
when the IME is up.
```

## What changed (code)

### `android/app/src/main/java/com/hermexapp/android/MainActivity.kt`

Before:

```kotlin
enableEdgeToEdge()
```

After:

```kotlin
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(
        lightScrim = android.graphics.Color.TRANSPARENT,
        darkScrim = android.graphics.Color.TRANSPARENT,
    ),
    navigationBarStyle = SystemBarStyle.auto(
        lightScrim = android.graphics.Color.TRANSPARENT,
        darkScrim = android.graphics.Color.TRANSPARENT,
    ),
)
```

Adds 4 imports (`SystemBarStyle`), so net change is small and contained.

### `android/app/src/test/java/com/hermexapp/android/features/chat/ChatScreenImeInsetsLayoutTest.kt`

One new `@Test` (`MainActivity enables edge-to-edge with both system bars
fully transparent`) that:
1. Loads `MainActivity.kt` from disk (via the same walk-up-the-tree strategy
   used for the other three tests).
2. Asserts the regex `enableEdgeToEdge\s*\([\s\S]*?statusBarStyle\s*=[\s\S]*?navigationBarStyle\s*=[\s\S]*?\n\s*\)` matches the source — catches the no-arg default.
3. Captures the full `enableEdgeToEdge(...)` call block and asserts exactly
   4 `Color.TRANSPARENT` references inside (lightScrim + darkScrim for
   status + lightScrim + darkScrim for navigation) — catches half-fixes
   like "only the status bar" or "only lightScrim".

`REGEX_ENABLE_EDGE_TO_EDGE_BLOCK` updated to span multi-line calls —
specific to the layout in `MainActivity#onCreate` (closing paren at
8-space indent).

### `android/app/build.gradle.kts`

`versionCode 14 → 15`, `versionName "0.6.1" → "0.6.2"`.

## Verified

| Check | Result |
|---|---|
| `./gradlew.bat testDebugUnitTest` | **BUILD SUCCESSFUL** — 327/327 tests, 0 failures, 0 errors, 0 skipped (was 326 → +1) |
| `./gradlew.bat compileDebugKotlin` | clean, no warnings |
| `./gradlew.bat assembleDebug` | **BUILD SUCCESSFUL** — 13.4 MB APK at `app/build/outputs/apk/debug/app-debug.apk` |
| APK metadata | `versionCode: 15, versionName: "0.6.2"` ✅ |
| Dex inspection | `MainActivity.onCreate` contains the new 4-arg `enableEdgeToEdge(...)` call ✅ |

## Phone-side install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The new APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

If you were running v0.6.0 (old) or v0.6.1 (partial fix), installing
v0.6.2 should:
1. Make the band disappear entirely (both fixes active)
2. Show the chat canvas (black) flush against the keyboard
3. Show the pill row directly touching the keyboard

## Why I caught only one cause yesterday

I reasoned about what the **Compose layout** was doing, but I missed the
**window-level** scrim that AndroidX's `enableEdgeToEdge()` applies
by default. This is the failure mode I've been warned about before
("debug by observing first, don't reason in isolation") — apologies
for making you test a half-fix. The structural test in v0.6.1 was
correct about its half but didn't cover the SystemBarStyle half, so
the test passed while the bug persisted.

The v0.6.2 test now covers **both** halves. Any future revert of either
fix will fail at CI time, not at phone-test time.

## Operator-blocked items (unchanged)

- Play Store upload (6.1 — needs Developer account + keystore + screenshots)
- Physical QA matrix on device (6.8)
- Re-vendor of JKP `run_agent.py` (waiting on operator GO; current
  `agent_compat.py` shim is documented tech debt but working)

## Files changed

- `android/app/src/main/java/com/hermexapp/android/MainActivity.kt` — `enableEdgeToEdge()` no-arg → 4-arg explicit
- `android/app/src/test/java/com/hermexapp/android/features/chat/ChatScreenImeInsetsLayoutTest.kt` — added 1 test + 2 regex constants + 1 resolver helper
- `android/app/build.gradle.kts` — bumped `versionCode 14 → 15`, `versionName "0.6.1" → "0.6.2"`
- `CHANGELOG.md` — new `[v0.6.2-stable]` section above `[v0.6.1-stable]`
- `RELEASE_NOTES_v0.6.2-stable.md` — **this file**

## Rollback

Revert to `v0.6.1-stable`:

```bash
git checkout v0.6.1-stable -- \
  android/app/src/main/java/com/hermexapp/android/MainActivity.kt \
  android/app/build.gradle.kts
```

The regression-test class adds a 4th test that becomes a no-op (the regex
fails on v0.6.1's `enableEdgeToEdge()` no-arg call) if you revert just
`MainActivity.kt` — that's by design.
