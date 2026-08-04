# Delegation spec: JKPHermex production verification refresh

You are working in the JKPHermex repo (`E:\JKPHermex`, branch
`feat/jkp-modular-extraction`). The release gate already passed
2026-08-04 (`cb953c2` + `1882079`): 584 tests, lint 0/45, debug + unsigned
release APKs.

What still belongs in the v0.8.14 line: a second pass of reliability
hardening on the chat composer's connection-failure path, plus a
composable smoke-checklist doc the operator can run on a real device.

## Read first

- `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt`
- `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatViewModel.kt`
- `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ComposerControls.kt`
- `android/lib/jkp-settings/src/main/.../ServerUrlNormalizer.kt` (for the pairing host contract)
- `docs/PLAN_AND_ROADMAP.md`
- `CHANGELOG.md`

## What to build

### 1. Chat composer offline feedback
Today `ComposerControls` is always enabled when a session is selected.
When the underlying transport is reported `Unavailable` or `Failed`,
the send affordance should be visible but inert with a brief copy line
beneath the composer ("The connection is offline; your message will
send when the host is back.").

- Add a `connectionState: JkpConnectionState` field to whatever the
  composer's ViewModel exposes (read-only). The transport currently
  surfaces the connection state from the host heartbeat. If the
  ViewModel does not already track it, add a tiny wrapper.
- Add a `ChatComposerOfflineTest.kt` (JVM) that asserts the composer's
  send-enabled flag stays false when `connectionState` is Offline/Failed.

### 2. Device smoke checklist
- Create `docs/V0814_DEVICE_SMOKE_CHECKLIST.md` with a numbered list of
  manual checks:
  1. Open a real session; the chat scrollbar is draggable.
  2. Send a long message; the composer toggles open/close without
     losing focus.
  3. Toggle airplane mode; "The connection is offline…" appears.
  4. App background then foreground: no scroll-position jump.
  5. With JKPHermex installed: deep-link round trip from the super-app
     lands in the JKP chat surface within 2s.
- Each item names the expected evidence (a screenshot, a logcat line,
  etc). DO NOT execute adb commands (no device attached) — this is
  the checklist the operator runs later.

### 3. CHANGELOG + roadmap reconcile
- Bump nothing yet. Add a 2026-08-04 sub-entry under 0.8.14: "Chat
  composer offline feedback + v0.8.14 device smoke checklist" with
  one bullet per user-facing change.

## Hard rules

1. NO new Gradle dependencies.
2. NO adb commands. NO device-state mutations. The smoke checklist
   is documentation only.
3. Stage ONLY files you create/modify — explicit paths.
4. Baseline test count first; everything must stay green.

## Build and test

```bash
export JAVA_HOME="/c/Users/roflm/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot"
export TERM=dumb
cd /e/JKPHermex/android
./gradlew --console=plain testDebugUnitTest
```

## Report

Gradle result + JUnit XML count, files touched, commit SHA(s),
checklist artifact path.