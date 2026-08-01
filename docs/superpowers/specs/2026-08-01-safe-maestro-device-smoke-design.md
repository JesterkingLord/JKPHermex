# Safe Maestro Device Smoke Design

**Date:** 2026-08-01
**Status:** Approved for implementation by the operator's standing "continue and
approve, do what is best" direction
**Scope:** Android physical-device smoke automation for the existing installed
JKPHermex app

## Objective

Add a small, maintainable Maestro workspace that proves the installed phone app
can open, reach the connected session list, navigate its primary read-only
surfaces, open an existing conversation, and exercise chat scrolling without
changing operator-owned content or configuration.

The suite is an acceptance aid, not a replacement for unit, lint, build, or Room
migration instrumentation gates.

## Safety contract

Every default smoke run must preserve:

- app databases, preferences, pairing credentials, cookies, drafts, notes, prompts,
  conversations, and attachments;
- Android runtime-permission grants;
- server-side sessions, messages, projects, tasks, skills, memory, and settings;
- the installed app package unless the operator separately requests `adb install -r`.

The suite therefore must never contain `clearState`, `setPermissions`, `inputText`,
`pasteText`, long-press, send, stop-response, pin, archive, delete, create, save,
pair, connect, update, sign-out, or forget-server commands. It must not use
Maestro's `launchApp`: current Maestro documentation states that `launchApp`
grants all permissions by default. Instead, the runner foregrounds the exported
launcher activity with `adb shell am start -W -n
com.hermexapp.android/.MainActivity`, which does not clear app state or alter
permission grants.

TalkBack is outside this suite and must not be opened or enabled.

## Structure

```text
maestro/
  config.yaml
  run-device-smoke.ps1
  README.md
  flows/
    connected-smoke.yaml
  subflows/
    require-connected-sessions.yaml
    primary-navigation.yaml
    existing-chat-scroll.yaml
```

`config.yaml` discovers only `flows/*.yaml`, so reusable subflows are not run as
independent tests. The single root flow calls the subflows in a deterministic,
fail-fast order.

## Runner behavior

`run-device-smoke.ps1` will:

1. resolve `adb` and `maestro`, using `%JAVA_HOME%\bin` for the Maestro process so
   the repository's available JDK 17 wins over the machine's default Java 8;
2. require exactly one authorized ADB device and print its model/Android version;
3. require `com.hermexapp.android` to already be installed;
4. snapshot the package's granted runtime-permission lines;
5. foreground `.MainActivity` through ADB;
6. run `maestro test maestro` from the repository root;
7. snapshot runtime permissions again and fail if Maestro changed them.

It will not install, uninstall, clear, force-stop, grant, revoke, or reset anything.
Installing the current debug APK remains a separate, explicit `adb install -r`
step documented in the README.

## Flow behavior

### Connected-state preflight

The preflight waits for app content, then fails with a clear labeled assertion if
it sees onboarding (`Get started` or the connection form). It navigates back to
Sessions when the app resumes inside a chat, asserts the Sessions controls, and
fails clearly when `No sessions yet` is visible. It never creates a fixture
conversation.

### Primary navigation

The navigation subflow opens the app drawer using its named `Open navigation menu`
action and visits Projects, Tasks, Skills, Memory, Insights, Notes, Prompts, and
Settings. Each visit performs visibility assertions only, then returns to Sessions.
It never taps row actions, switches, chips, links, editors, floating action buttons,
or updater controls.

### Existing-chat scroll

The scroll subflow opens the first existing session through its visible message
count text, asserts the composer shell, and performs percentage-based vertical
gestures within the message viewport. When a long-enough chat exposes `Scroll to
latest`, it uses that named action and verifies the composer remains visible. The
flow returns to Sessions and never focuses the composer or sends content.

The default smoke accepts a short first conversation: absence of the contextual
jump control does not fail navigation smoke. Detailed fast-scrollbar drag
acceptance remains a manual physical-device step because its visibility depends on
conversation length and auto-hide timing.

## Selector strategy

Use this order:

1. stable accessibility names already shipped in Compose;
2. stable visible screen titles and controls;
3. regular-expression text for dynamic counts;
4. percentage gestures limited to the center message viewport.

Do not use absolute pixels, session titles, database IDs, operator content, or
locale-dependent timestamps. Avoid adding production-only automation IDs until a
real selector gap is demonstrated on the phone.

## Failure behavior

The root flow stops on the first failed assertion. Custom `assertTrue` labels must
distinguish these prerequisites:

- app is unpaired or on onboarding;
- connected session list is unreachable;
- there are no existing sessions;
- an expected read-only surface cannot be opened.

The runner returns Maestro's non-zero exit code and separately fails if permission
state changed. It does not attempt recovery by resetting device or app state.

## Verification

Local verification:

- parse every YAML file;
- scan flow and runner text for the forbidden command/action vocabulary;
- run PowerShell parser validation on `run-device-smoke.ps1`;
- keep Android unit, lint, and aggregate build gates green.

Physical-device acceptance when ADB is available:

- optionally update the app only with `adb install -r`;
- run the migration instrumentation runner first and prove fixture cleanup;
- run the Maestro workspace through the safe runner;
- confirm pairing, conversation count, runtime permissions, and production database
  remain intact;
- manually drag the named `Scroll position` control in a known long conversation
  and verify smooth top/middle/bottom movement plus jump-to-latest behavior.

## Source notes

- Maestro `launchApp` and permission behavior:
  https://docs.maestro.dev/api-reference/commands/launchapp
- Conditional `runFlow` blocks:
  https://docs.maestro.dev/maestro-flows/flow-control-and-logic/conditions
- Percentage-based swipes:
  https://docs.maestro.dev/reference/commands-available/swipe
- Workspace discovery configuration:
  https://docs.maestro.dev/api-reference/configuration/workspace-configuration
