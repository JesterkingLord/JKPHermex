# Safe Maestro Device Smoke Implementation Plan

> **Execution note:** Follow this plan task by task with test-first checkpoints.

**Goal:** Add a physical-Android Maestro smoke workspace that exercises connected
JKPHermex navigation and scrolling without clearing state, changing permissions,
or mutating local/server content.

**Design:** `docs/superpowers/specs/2026-08-01-safe-maestro-device-smoke-design.md`

**Constraints:** No app/API schema changes, no new project dependencies, no
TalkBack, no package clearing/uninstall, and no send/delete/settings/server
mutations. Maestro is not currently installed and ADB currently sees no device,
so local structural gates precede physical execution.

---

## Task 1: Executable safety contract

**Files:**

- Create: `maestro/tests/verify-safety.ps1`

1. Write a dependency-free PowerShell verifier that requires the planned config,
   runner, root flow, and three subflows.
2. Make it reject forbidden YAML commands (`launchApp`, `clearState`,
   `setPermissions`, `inputText`, `pasteText`, and state-changing action labels)
   and destructive ADB operations (`pm clear`, uninstall, grant/revoke, force-stop).
3. Make it require the safe ADB component start, one-device check, package check,
   permission before/after comparison, JDK 17 preference, and Maestro invocation.
4. Run it before implementation and record the expected RED result because the
   required workspace files do not exist.
5. Commit only after Tasks 2-4 make the verifier GREEN.

## Task 2: Workspace and safe runner

**Files:**

- Create: `maestro/config.yaml`
- Create: `maestro/run-device-smoke.ps1`
- Create: `maestro/README.md`

1. Configure `appId: com.hermexapp.android`, discovery limited to
   `flows/*.yaml`, and fail-fast execution.
2. Implement runner preflights for `adb`, `maestro`, `%JAVA_HOME%\bin\java.exe`,
   exactly one authorized device, and an already installed package.
3. Snapshot normalized granted-runtime-permission lines before and after the run.
4. Foreground `com.hermexapp.android/.MainActivity` with `adb shell am start -W`.
5. Invoke `maestro test maestro`, preserve its exit code, and independently fail
   if permissions changed.
6. Document optional `adb install -r`, safe-run instructions, prerequisites,
   expected state, forbidden operations, and manual long-chat scrollbar checks.

## Task 3: State-aware root and prerequisite flow

**Files:**

- Create: `maestro/flows/connected-smoke.yaml`
- Create: `maestro/subflows/require-connected-sessions.yaml`

1. Create a root flow that contains no `launchApp` and calls subflows in a fixed
   order.
2. Add conditional labeled failures for welcome/onboarding and Connect states.
3. If resumed inside a chat, use `Back to sessions`; otherwise use the named menu
   action and Sessions drawer item.
4. Assert the Sessions title plus All/Pinned/Archived controls.
5. Fail clearly on `No sessions yet`; assert a dynamic conversation count and an
   existing `.* messages` row instead of creating fixtures.

## Task 4: Read-only navigation and scroll flows

**Files:**

- Create: `maestro/subflows/primary-navigation.yaml`
- Create: `maestro/subflows/existing-chat-scroll.yaml`

1. Visit Projects, Tasks, Skills, Memory, Insights, Notes, Prompts, and Settings
   using visible drawer labels and screen-title assertions only.
2. Return to Sessions after each screen through its named Back action; never touch
   rows, switches, links, FABs, editors, or updater controls.
3. Open the first existing conversation through its visible message-count text.
4. Assert the composer shell without focusing it.
5. Perform percentage swipes only inside the message viewport; conditionally tap
   `Scroll to latest` when a long-enough chat exposes it.
6. Return to Sessions and assert the stable controls again.

## Task 5: Local validation and commit

**Files:**

- Modify if needed: all files under `maestro/`
- Update: `CURRENT.md` (local-only, never stage)

1. Run `powershell -NoProfile -ExecutionPolicy Bypass -File
   maestro/tests/verify-safety.ps1`; require GREEN.
2. Parse the runner with PowerShell's AST parser; require zero syntax errors.
3. Run `git diff --check` and inspect the full Maestro diff for accidental mutable
   actions or operator-specific data.
4. Run `gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`; require
   success.
5. Commit the complete Maestro workspace as one focused commit.

## Task 6: Physical-device acceptance when available

1. Confirm one authorized ADB device and capture model/Android version.
2. Install only with `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.
3. Run the migration instrumentation runner and prove no fixture DB/lock remains.
4. Verify installed `allowBackup=false` through package inspection.
5. Run `maestro/run-device-smoke.ps1`; require all flows and the permission-state
   comparison to pass.
6. Confirm conversation count and production database remain intact.
7. Manually test the named `Scroll position` control on a known long chat at
   top/middle/bottom and verify jump-to-latest behavior. Do not enable TalkBack.
8. Record evidence in `CURRENT.md`; do not commit device screenshots or metadata.
