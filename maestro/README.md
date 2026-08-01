# JKPHermex safe Maestro smoke

This workspace checks the already-paired Android app on one physical device. It
only navigates, asserts visible UI, and performs scroll gestures. It does not
clear state, change permissions, type, send, create, edit, archive, delete,
sign out, update, or modify server settings. It never opens TalkBack.

## Prerequisites

- One authorized Android device in `adb devices -l`.
- `com.hermexapp.android` already installed and paired to a reachable server.
- At least one existing session.
- Maestro CLI on `PATH`.
- `JAVA_HOME` pointing to JDK 17. The runner prepends that JDK's `bin` directory
  because this workstation's default `java` may still be Java 8.

Maestro's `launchApp` command is deliberately absent: Maestro currently grants
all app permissions by default when that command runs. The PowerShell runner
foregrounds the exported launcher activity through ADB instead and compares the
package's runtime-permission state before and after the test.

## Optional debug update

Build first, then update the existing package without clearing its data:

```powershell
cd E:\JKPHermex\android
.\gradlew.bat --no-daemon assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Do not uninstall the app, run `pm clear`, reset permissions, or use an install
command without `-r` on the operator's phone.

## Run

From the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\maestro\tests\verify-safety.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\maestro\run-device-smoke.ps1
```

The runner stops if no device, multiple devices, an unauthorized device, a
missing package, a missing tool, or a runtime-permission change is detected.
Unpaired/onboarding state and an empty session list fail with labeled Maestro
assertions. The runner does not attempt to repair prerequisites by changing data.

## Manual long-chat scrollbar acceptance

The default flow scrolls the first existing chat but does not assume it contains
enough messages to show the fast scrollbar. For the full physical check:

1. Open a known long conversation without editing its composer.
2. Drag the named `Scroll position` control to the top, middle, and bottom.
3. Confirm the viewport follows continuously without jumps or stuck regions.
4. Scroll away from the end, confirm exactly one `Scroll to latest` control, tap
   it, and verify the true bottom settles with the control hidden.
5. Confirm the composer remains fully visible above the keyboard/navigation bar.

Do not enable TalkBack for this acceptance pass.
