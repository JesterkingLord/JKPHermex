# Building JKPHermex

This document describes how to build the Android app from source.

## Prerequisites (one-time)

The build needs:

| Tool | Version | Why |
|---|---|---|
| **JDK** | 17+ | AGP 8.7.3 requires JDK 17; Kotlin 2.0.21 compiles to JVM 17 bytecode |
| **Android SDK** | platform 35, build-tools 35.0.0, platform-tools | compileSdk=35, targetSdk=35 |
| **Disk** | ~5 GB free | SDK + Gradle caches + first build |
| **OS** | Windows / macOS / Linux | Cross-platform; the Gradle wrapper handles the rest |

You do **not** need to install Gradle yourself — the `gradlew` wrapper is included.

## Quick install (Windows, one script)

The fastest path on Windows is the bundled installer:

```powershell
# Run from a normal PowerShell (it will self-elevate if needed for SDK install):
powershell -ExecutionPolicy Bypass -File scripts\install-android-toolchain.ps1
```

This installs:

1. **Microsoft OpenJDK 17** via `winget install Microsoft.OpenJDK.17`
2. **Android command-line tools** to `%LOCALAPPDATA%\Android\Sdk` (or `$env:ANDROID_HOME` if set)
3. **SDK packages:** `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
4. **Environment variables** (per-user, persistent):
   - `JAVA_HOME` → OpenJDK 17 install path
   - `ANDROID_HOME` → Android SDK root
   - `PATH` updated for both

After it finishes, **close and reopen your terminal** for the env vars to take effect, then verify:

```powershell
java -version          # should print "17.x"
sdkmanager --list_installed   # should show platforms;android-35, build-tools;35.0.0, platform-tools
```

## Manual install (any OS)

### macOS (Homebrew)

```bash
brew install --cask microsoft-openjdk
brew install --cask android-commandlinetools

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
mv "$(brew --prefix android-commandlinetools)"/latest "$ANDROID_HOME/cmdline-tools/latest"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "platform-tools"
```

### Linux (apt + manual SDK)

```bash
sudo apt install -y openjdk-17-jdk-headless unzip

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
curl -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip
mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "platform-tools"
```

### Windows (manual, no script)

```powershell
# 1. JDK 17
winget install Microsoft.OpenJDK.17

# 2. Android command-line tools — download from
#    https://developer.android.com/studio#command-line-tools-only
#    Unzip to C:\Users\<you>\AppData\Local\Android\Sdk\cmdline-tools\latest\
#    (must be named "latest" — the SDK looks for that exact folder)

# 3. Set env vars (PowerShell, per-user, persistent):
[Environment]::SetEnvironmentVariable("JAVA_HOME", (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\JDK\17').JavaHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")

# 4. Install SDK packages:
$env:PATH = "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

## Build the APK

```bash
cd android
./gradlew assembleDebug
```

The first build downloads ~500 MB of Gradle/AGP dependencies. Subsequent builds are
incremental and take 10-60 seconds.

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

To install via ADB without leaving the terminal:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Run the unit tests

```bash
cd android
./gradlew test
```

## Build a release APK (for distribution)

```bash
cd android
./gradlew assembleRelease
```

Release builds require a signing config. For now, the project ships without one —
the `release` build type in `app/build.gradle.kts` will fail until you add
a `signingConfigs { release { ... } }` block. For local development and
sideloading, the debug APK is sufficient.

## Troubleshooting

### "SDK location not found"

Set `ANDROID_HOME` (or create `android/local.properties` with `sdk.dir=...`):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

`local.properties` is gitignored — never commit it.

### "Build failed: compileSdk 35 is not installed"

Run:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

### "Unsupported class file major version 65"

You have JDK 21+ but the build is trying to use a newer version somewhere. Set
`JAVA_HOME` explicitly to a JDK 17 install.

### "Daemon will be stopped at the end of the build"

Harmless. Gradle is just telling you it tore down its background worker. The
next build will spin it back up.

### Build is very slow on first run

Expected. Gradle is downloading AGP, Kotlin compiler, Compose, and the full
dependency graph. After the first build, subsequent builds are 10-60 seconds.

### `adb` doesn't see the phone

1. Enable **Developer options** on the phone (tap "Build number" 7 times in Settings → About)
2. Enable **USB debugging** in Developer options
3. Plug in via USB
4. Approve the "Allow USB debugging" prompt on the phone
5. Run `adb devices` — your phone should appear as a serial number
6. If it doesn't, install the OEM USB driver (Samsung, Google, OnePlus each have their own)
