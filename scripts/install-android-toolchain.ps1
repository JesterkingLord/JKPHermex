# install-android-toolchain.ps1
# One-shot installer for the JKPHermex Android build toolchain on Windows.
# Installs: Microsoft OpenJDK 17, Android command-line tools, and the
# SDK packages required by android/app/build.gradle.kts (compileSdk 35,
# build-tools 35.0.0, platform-tools). Sets JAVA_HOME and ANDROID_HOME
# as per-user persistent environment variables.
#
# Usage (from a normal PowerShell — this script self-elevates if needed):
#   powershell -ExecutionPolicy Bypass -File scripts\install-android-toolchain.ps1
#
# After it finishes, CLOSE AND REOPEN your terminal so the new env vars
# take effect, then run:
#   cd android
#   .\gradlew assembleDebug

#Requires -Version 5.1
$ErrorActionPreference = 'Stop'

function Write-Step($n, $title) {
    Write-Host ""
    Write-Host "[$n/5] $title" -ForegroundColor Cyan
    Write-Host ("-" * 60)
}

function Assert-Command($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command '$name' not found on PATH. Install it and re-run."
    }
}

# ---- Self-elevation ------------------------------------------------------
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent()
)
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Re-launching elevated (this installer needs admin to set machine-level env vars)..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", $PSCommandPath
    ) -Verb RunAs -Wait
    exit $LASTEXITCODE
}

# ---- 1. winget -----------------------------------------------------------
Write-Step 1 "Checking winget"
Assert-Command winget
$wingetVersion = (winget --version) 2>&1
Write-Host "winget $wingetVersion found" -ForegroundColor Green

# ---- 2. JDK 17 ------------------------------------------------------------
Write-Step 2 "Installing Microsoft OpenJDK 17"
$jdk = Get-Command java -ErrorAction SilentlyContinue
$needsJdk = $true
if ($jdk) {
    $javaVerOutput = & java -version 2>&1 | Select-Object -First 1
    if ($javaVerOutput -match '"17\.' ) {
        Write-Host "JDK 17 already present: $javaVerOutput" -ForegroundColor Green
        $needsJdk = $false
    } else {
        Write-Host "Found Java but not 17: $javaVerOutput" -ForegroundColor Yellow
    }
}
if ($needsJdk) {
    Write-Host "Running: winget install --id Microsoft.OpenJDK.17 --accept-package-agreements --accept-source-agreements"
    winget install --id Microsoft.OpenJDK.17 `
        --accept-package-agreements `
        --accept-source-agreements `
        --source winget
    if ($LASTEXITCODE -ne 0) {
        throw "winget install OpenJDK 17 failed (exit $LASTEXITCODE)"
    }
}

# ---- 3. Android command-line tools ---------------------------------------
Write-Step 3 "Installing Android command-line tools"
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$cmdlineToolsDir = Join-Path $androidHome "cmdline-tools\latest\bin"
if (Test-Path (Join-Path $cmdlineToolsDir "sdkmanager.bat")) {
    Write-Host "Android cmdline-tools already present at $cmdlineToolsDir" -ForegroundColor Green
} else {
    Write-Host "Downloading commandlinetools-win to $androidHome\cmdline-tools\latest\"
    $zipPath = Join-Path $env:TEMP "commandlinetools-win.zip"
    $url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    if (Test-Path (Join-Path $androidHome "cmdline-tools")) {
        Write-Host "Removing old cmdline-tools dir" -ForegroundColor Yellow
        Remove-Item -Recurse -Force (Join-Path $androidHome "cmdline-tools")
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $androidHome "cmdline-tools") | Out-Null
    Expand-Archive -Path $zipPath -DestinationPath (Join-Path $androidHome "cmdline-tools") -Force
    # The zip extracts to cmdline-tools/cmdline-tools — rename to .../latest
    $extracted = Join-Path $androidHome "cmdline-tools\cmdline-tools"
    $target = Join-Path $androidHome "cmdline-tools\latest"
    if (Test-Path $extracted) {
        if (Test-Path $target) { Remove-Item -Recurse -Force $target }
        Move-Item -Path $extracted -Destination $target
    }
    Remove-Item $zipPath
    Write-Host "Installed cmdline-tools to $target" -ForegroundColor Green
}

# ---- 4. SDK packages -----------------------------------------------------
Write-Step 4 "Installing Android SDK packages (platforms;android-35, build-tools;35.0.0, platform-tools)"
$env:ANDROID_HOME = $androidHome
$env:PATH = "$cmdlineToolsDir;$env:PATH"

Write-Host "Accepting SDK licenses..."
$yes = New-Object System.Text.StringBuilder
for ($i=0; $i -lt 20; $i++) { [void]$yes.AppendLine("y") }
$yes.ToString() | & "$cmdlineToolsDir\sdkmanager.bat" --licenses | Out-Null

& "$cmdlineToolsDir\sdkmanager.bat" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager install failed (exit $LASTEXITCODE)"
}
Write-Host "SDK packages installed" -ForegroundColor Green

# ---- 5. Environment variables --------------------------------------------
Write-Step 5 "Setting persistent environment variables"

# Find JDK 17 install path
$jdk17Path = (Get-ChildItem "C:\Program Files\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1).FullName
if (-not $jdk17Path) {
    $jdk17Path = (Get-ChildItem "C:\Program Files\OpenJDK\jdk-*" -Directory -ErrorAction SilentlyContinue |
                 Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $jdk17Path) {
    $jdk17Path = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue |
                 Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $jdk17Path) {
    Write-Host "WARNING: Could not auto-detect JDK 17 install path." -ForegroundColor Yellow
    Write-Host "Set JAVA_HOME manually after this script finishes." -ForegroundColor Yellow
} else {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk17Path, "Machine")
    Write-Host "JAVA_HOME = $jdk17Path" -ForegroundColor Green
}

[Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidHome, "Machine")
Write-Host "ANDROID_HOME = $androidHome" -ForegroundColor Green

# Add SDK tools to system PATH
$pathAdditions = @(
    (Join-Path $androidHome "cmdline-tools\latest\bin"),
    (Join-Path $androidHome "platform-tools")
)
$currentPath = [Environment]::GetEnvironmentVariable("Path", "Machine")
foreach ($p in $pathAdditions) {
    if ($currentPath -notlike "*$p*") {
        $currentPath = "$currentPath;$p"
    }
}
[Environment]::SetEnvironmentVariable("Path", $currentPath, "Machine")

Write-Host ""
Write-Host "DONE." -ForegroundColor Green
Write-Host ""
Write-Host "Close and reopen your terminal for the new env vars to take effect, then verify:"
Write-Host "  java -version"
Write-Host "  sdkmanager --list_installed"
Write-Host ""
Write-Host "Then build the APK:"
Write-Host "  cd android"
Write-Host "  .\gradlew assembleDebug"
Write-Host ""
Write-Host "APK output: android\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Install on phone: adb install -r android\app\build\outputs\apk\debug\app-debug.apk"
