# Scroll and Accessibility Polish Design

**Status:** Approved by the operator on 2026-07-28
**Date:** 2026-07-28
**Scope:** Android 0.8.14 quality pass only
**Evidence device:** OPPO CPH2343, Android 13/API 33, 360 dp width

## Goal

Make Hermex's scrolling and custom interactive controls feel as calm and
predictable as ChatGPT while meeting the project's 48 dp native target rule,
remaining usable with TalkBack, and preserving the existing pixel-accurate
scroll model.

## Constraints

- Do not add dependencies or change API endpoints, JSON models, persistence, or
  server behavior.
- Preserve the existing measured-height cache, inverse pixel targeting,
  endpoint snapping, three-pass settling, tap-to-position, drag behavior, and
  60-second scrollbar fade policy.
- Preserve user data and keep device screenshots/UI dumps out of Git.
- Use Kotlin and Jetpack Compose patterns already present in the Android app.
- Every behavior change receives a failing test before production code.

## Device evidence and root causes

The physical audit covered dark mode, light mode, standard text, the OPPO's
1.35x `Large` text setting (stricter than the roadmap's 1.3x requirement), and
UIAutomator trees for the primary screens.

The audit established these root causes:

1. `ScrollIndicatorOnly` renders separate up and down buttons whenever a chat
   can scroll in both directions. Both sit in the same right-edge lane as the
   fast scrollbar, producing a three-control stack over transcript text.
2. `FastScrollbar` exposes range information but no `SetProgress` action, so a
   TalkBack user has no single-pointer alternative to its drag gesture.
3. The scrollbar owns a 48 dp overlay lane. The Sessions header leaves only
   16 dp of end padding, so its search button is accessibility-clipped while
   that overlay is visible. At 1.35x text its reported width fell from 48 dp to
   16 dp.
4. Several custom controls paint and click on the same 24-40 dp box instead of
   keeping a compact visual inside a 48 dp touch shell.
5. Settings and Projects color dots lack stable labels and selected-state
   semantics; Projects also communicates selection only through dot size/color.
6. Several custom clickable text rows can measure below 48 dp when their
   optional secondary text is absent.

## Approved interaction model

### One contextual jump-to-latest control

The chat shows one accent-colored down-arrow control if and only if
`LazyListState.canScrollForward` is true. It is hidden at the latest message and
when content fits in the viewport. Tapping it animates to the final timeline
entry and calls the existing `markSeen()` path.

There is no jump-to-top button. The fast scrollbar already provides arbitrary
positioning, including exact top navigation, so a second directional button is
redundant. Removing it matches ChatGPT's single jump-to-latest convention and
eliminates the current stacked-arrow state.

The control keeps a 40 dp visual circle inside a transparent 48 dp button shell.
It sits 56 dp from the right edge and 24 dp above the composer, leaving the
scrollbar's 48 dp lane plus an 8 dp visual gap unobstructed. Its accessible name
is `Scroll to latest`, role is Button, and its icon is decorative.

### Adjustable fast scrollbar

The scrollbar retains its current narrow track and thumb, 48 dp hit lane,
auto-hide behavior, tap behavior, and direct drag behavior. While rendered, its
semantics expose:

- name: `Scroll position`;
- value: `<N> percent through content`;
- range: continuous `0f..1f`;
- `SetProgress`: clamps finite requests to `0f..1f`, rejects non-finite values,
  and launches the existing `settleAtFraction` path.

This makes every drag outcome available through a non-drag accessibility action
without introducing a second scroll implementation. Technical instrumentation
copy such as `FastScrollbar pos=...` must not be spoken as the user-facing
label; exact device verification can continue through the existing debug log,
test tag, geometry tests, and screenshot bounds.

### Protected scrollbar lane

Interactive controls rendered underneath the 48 dp scrollbar overlay are moved
outside it. The Sessions header uses 56 dp end padding, and the jump-to-latest
shell also ends before that lane. Session row text may continue under the quiet
visual track, but buttons may not.

The redundant in-app `LiveClock` is removed from the Sessions phone header. The
system status bar already shows the time, and removing the duplicate preserves
title/search space at large text sizes.

## Accessible component treatment

### Shared accent swatch

Settings and Projects use one reusable `AccentSwatch` composable:

- 48 dp touch shell;
- 32 dp color visual;
- button role and `preset.displayName` accessible name;
- `selected` semantics;
- a visible checkmark and outline when selected, so state is not color-only;
- decorative inner check icon (`contentDescription = null`).

The component changes only local preference/project color selection and retains
the existing `AccentPreset` values.

### Compact icon buttons

Jump and bulk-session actions use 48 dp outer shells with 40 dp visuals. The
outer node owns the button role, label, click action, and disabled state; the
inner icon is decorative. Pin, archive, and delete bulk actions are genuinely
disabled and removed from the click path when no session is selected instead of
remaining focusable no-op actions.

To keep the five-action bulk bar usable at 360 dp, its icon visuals remain 40 dp
and spacing is reduced only as needed; the selected-count text may ellipsize but
must not force a target below 48 dp.

### Minimum-height interactive rows

The following source-confirmed custom click targets receive a 48 dp minimum
height without enlarging typography or changing their actions:

- Projects session rows;
- Settings model and About link rows;
- slash-command suggestions;
- workspace browser and Git-diff rows;
- short user/assistant message action surfaces;
- compact tool-call and reasoning-card toggles.

Rows that already exceed 48 dp through real content keep their current padding.
Material controls with built-in minimum targets are not wrapped again.

## Component boundaries

- `ui/JumpFab.kt`: one jump-to-latest composable and its pure visibility rule.
- `features/chat/ChatScreen.kt`: supplies `canScrollForward`, scrolls to the last
  entry, marks it seen, reserves the right-edge lane, and applies 48 dp minima to
  interactive timeline surfaces.
- `ui/FastScrollbar.kt`: user-facing range semantics and guarded progress
  normalization; existing geometry/settling remains the single data path.
- `ui/AccentSwatch.kt`: reusable visual and semantic color selector.
- `features/settings/SettingsScreen.kt` and
  `features/sessionlist/ProjectsScreen.kt`: consume `AccentSwatch` and add row
  minimums.
- `features/sessionlist/BulkSessionActionsBar.kt`: compact 48 dp action shells
  and real disabled behavior.
- `features/sessionlist/SessionListScreen.kt`: protected header lane and removal
  of the redundant clock.
- `features/chat/ComposerControls.kt` and
  `features/workspace/WorkspaceScreens.kt`: source-confirmed row minimums.

## Error and edge handling

- Empty/fitting lists render neither scrollbar nor jump button.
- A non-finite accessibility progress request returns `false` and performs no
  scroll.
- Finite out-of-range progress is clamped before entering existing scroll math.
- If list geometry disappears before a requested scroll settles, the existing
  null-target return remains authoritative and the app does not crash.
- Jump-to-latest is guarded by `canScrollForward`; an empty timeline cannot
  expose the action.
- Disabled bulk mutations do not expose click actions.

## Test strategy

### Automated red-green coverage

1. Replace the stale grace-window `ScrollIndicatorOnlyTest` contract with tests
   proving the jump control is shown only when content exists below.
2. Add fast-scroll accessibility normalization tests for finite values,
   endpoint clamping, and rejection of `NaN`/infinity.
3. Replace the technical spoken-description assertion with a locale-stable,
   user-facing percentage-description assertion.
4. Extend the existing touch-target helper tests to cover 24, 32, and 40 dp
   visuals resolving to 48 dp shells.
5. Run each focused test first in RED and then GREEN, followed by both debug and
   release unit-test suites, lint, debug/release assembly, and full Gradle build.

Tests assert observable decisions and scroll targets, not source strings or
mock presence. Compose-only geometry and semantics are additionally proven on
the real device rather than by adding a new test dependency.

### Physical-device acceptance

After installing the new debug APK on the connected OPPO:

1. Standard text, dark mode: at chat midpoint exactly one `Scroll to latest`
   button exists, its bounds are at least 144x144 px, it does not overlap the
   scrollbar lane, and no `Scroll to top` node exists.
2. At chat bottom the jump button is absent; scrolling upward makes it return;
   tapping it reaches the exact latest entry and hides it.
3. Scrollbar top, midpoint tap, bottom, and continuous drag remain exact.
4. TalkBack focuses `Scroll position`, announces the percentage, and can adjust
   it in both directions without a drag gesture.
5. With the Sessions scrollbar visible, Search retains at least 144x144 px and
   does not intersect the rightmost 144 px scrollbar lane.
6. Settings and Projects swatches announce color and selected state, show a
   visible non-color selection mark, and retain 48 dp bounds.
7. Bulk actions and audited rows expose at least 48 dp bounds; disabled actions
   do not activate.
8. Repeat key screens in light mode and at the OPPO `Large` 1.35x text setting,
   then restore dark mode and 1.0 text exactly.
9. Re-open Gboard and confirm the prior `adjustResize` no-gap proof remains true.

## Non-goals

- No new scrolling algorithm, list architecture, animation system, dependency,
  API contract, database migration, or release/version change.
- No new jump-to-top affordance elsewhere.
- No redesign of Material controls that already satisfy the target and semantic
  requirements.
- No commit, push, tag, PR, or distribution without the operator's explicit
  approval.
