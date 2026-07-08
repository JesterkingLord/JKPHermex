# install-android-toolchain-nouac.ps1
# Non-elevated installer for the JKPHermex Android build toolchain on Windows.
# Installs per-user (no admin needed):
#   - Microsoft OpenJDK 17 (via winget, user-scope if possible)
#   - Android command-line tools, downloaded to %LOCALAPPDATA%\Android\Sdk
#   - SDK packages: platforms;android-35, build-tools;35.0.0, platform-tools
# Sets JAVA_HOME and ANDROID_HOME as USER environment variables (not Machine).
# Adds %ANDROID_HOME%\cmdline-tools\latest\bin and %ANDROID_HOME%\platform-tools
# to the user PATH.
#
# Usage (no admin needed):
#   powershell -ExecutionPolicy Bypass -File scripts\install-android-toolchain-nouac.ps1
#
# After it finishes, CLOSE AND REOPEN your terminal so the new env vars
# take effect in new PowerShell / bash windows, then build:
#   cd android
#   .\gradlew assembleDebug

#Requires -Version 5.1
# Use 'Continue' so non-fatal RemoteException noise from `java -version` etc.
# doesn't kill the script. Each step is wrapped in try/catch to surface real errors.
$ErrorActionPreference = 'Continue'

function Write-Step($n, $title) {
    Write-Host ""
    Write-Host "[$n/6] $title" -ForegroundColor Cyan
    Write-Host ("-" * 60)
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Refuse to run elevated -- this script is the non-elevated path.
if (Test-Admin) {
    Write-Host "You are already running as Administrator." -ForegroundColor Yellow
    Write-Host "This script is the non-elevated path. Re-run from a normal PowerShell." -ForegroundColor Yellow
    Write-Host "Aborting to avoid touching machine-wide state." -ForegroundColor Red
    exit 1
}

# ---- 1. Pre-flight -------------------------------------------------------
Write-Step 1 "Pre-flight checks"

# winget
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget not found on PATH. Install 'App Installer' from the Microsoft Store and re-run."
}
Write-Host "winget: $(winget --version)" -ForegroundColor Green

# PowerShell version
$psv = $PSVersionTable.PSVersion
Write-Host "PowerShell: $psv" -ForegroundColor Green
if ($psv.Major -lt 5) { throw "PowerShell 5.1+ required." }

# Internet check
try {
    Invoke-WebRequest -Uri "https://github.com" -UseBasicParsing -TimeoutSec 10 | Out-Null
    Write-Host "Internet: OK" -ForegroundColor Green
} catch {
    throw "No internet access. Cannot download cmdline-tools."
}

# ---- 2. JDK 17 via winget (user-scope) -----------------------------------
Write-Step 2 "Installing Microsoft OpenJDK 17 (user-scope)"

# Check if JDK 17 is already on PATH (suppress stderr noise from Java's RemoteException)
$existingJava = (Get-Command java -ErrorAction SilentlyContinue)
$needsJdk = $true
if ($existingJava) {
    $ver = ''
    try {
        $ver = (& java -version 2>&1 | Select-Object -First 1) -replace '"',''
    } catch {
        $ver = ''
    }
    if ($ver -match '17\.') {
        Write-Host "JDK 17 already present: $ver" -ForegroundColor Green
        $needsJdk = $false
    } elseif ($ver) {
        Write-Host "Found java on PATH but version is $ver" -ForegroundColor Yellow
    } else {
        Write-Host "Found java on PATH but version unknown" -ForegroundColor Yellow
    }
}

if ($needsJdk) {
    Write-Host "Running: winget install --id Microsoft.OpenJDK.17 --scope user ..."
    # --scope user installs to %LOCALAPPDATA% and doesn't need admin
    winget install --id Microsoft.OpenJDK.17 `
        --scope user `
        --accept-package-agreements `
        --accept-source-agreements `
        --source winget
    if ($LASTEXITCODE -ne 0) {
        Write-Host "winget install failed (exit $LASTEXITCODE). Trying machine scope..." -ForegroundColor Yellow
        winget install --id Microsoft.OpenJDK.17 `
            --accept-package-agreements `
            --accept-source-agreements `
            --source winget
        if ($LASTEXITCODE -ne 0) {
            throw "winget install OpenJDK 17 failed. Install it manually from https://aka.ms/msopenjdk"
        }
    }
}

# Locate the JDK 17 install
function Find-Jdk17 {
    $candidates = @()
    # winget user-scope installs to %LOCALAPPDATA%\Programs\Microsoft\OpenJDK\jdk-17*
    $userLocal = Join-Path $env:LOCALAPPDATA "Programs\Microsoft\OpenJDK"
    if (Test-Path $userLocal) { $candidates += Get-ChildItem $userLocal -Directory -ErrorAction SilentlyContinue }
    # winget machine-scope installs to C:\Program Files\Microsoft\jdk-17*
    $pf = "C:\Program Files\Microsoft"
    if (Test-Path $pf) { $candidates += Get-ChildItem $pf -Directory -ErrorAction SilentlyContinue }
    # Manual install to C:\Program Files\OpenJDK\jdk-17*
    $pf2 = "C:\Program Files\OpenJDK"
    if (Test-Path $pf2) { $candidates += Get-ChildItem $pf2 -Directory -ErrorAction SilentlyContinue }
    foreach ($c in $candidates) {
        if ($c.Name -match 'jdk-17') { return $c.FullName }
    }
    return $null
}
$jdk17Path = Find-Jdk17
if (-not $jdk17Path) {
    throw "Could not auto-detect JDK 17 install path. Set JAVA_HOME manually."
}
Write-Host "JDK 17 at: $jdk17Path" -ForegroundColor Green

# ---- 3. Android command-line tools ---------------------------------------
Write-Step 3 "Installing Android command-line tools"

# Use %LOCALAPPDATA%\Android\Sdk as ANDROID_HOME (Microsoft-recommended for user installs)
$androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path $androidHome)) {
    New-Item -ItemType Directory -Force -Path $androidHome | Out-Null
}

$cmdlineToolsBin = Join-Path $androidHome "cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $cmdlineToolsBin) {
    Write-Host "cmdline-tools already present at $androidHome\cmdline-tools\latest" -ForegroundColor Green
} else {
    Write-Host "Downloading commandlinetools-win to $androidHome\cmdline-tools\latest\"
    $zipPath = Join-Path $env:TEMP "commandlinetools-win-11076708.zip"
    $url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $wc = New-Object System.Net.WebClient
    $wc.DownloadFile($url, $zipPath)
    if (-not (Test-Path $zipPath) -or (Get-Item $zipPath).Length -lt 50MB) {
        throw "Download failed or file too small. Check internet."
    }

    # The zip extracts to cmdline-tools/cmdline-tools/ — we want cmdline-tools/latest/
    $extractDest = Join-Path $androidHome "cmdline-tools"
    $tempExtract = Join-Path $env:TEMP "cmdline-tools-extract"
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
    Expand-Archive -Path $zipPath -DestinationPath $tempExtract -Force

    # cmdline-tools-extract\cmdline-tools\ -> $androidHome\cmdline-tools\latest\
    $sourceDir = Join-Path $tempExtract "cmdline-tools"
    $targetDir = Join-Path $extractDest "latest"
    if (Test-Path $targetDir) { Remove-Item -Recurse -Force $targetDir }
    New-Item -ItemType Directory -Force -Path $extractDest | Out-Null
    Move-Item -Path $sourceDir -Destination $targetDir

    Remove-Item $zipPath -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
    Write-Host "Installed cmdline-tools to $targetDir" -ForegroundColor Green
}

# ---- 4. SDK packages -----------------------------------------------------
Write-Step 4 "Installing Android SDK packages (no UAC needed)"

$env:ANDROID_HOME = $androidHome
$env:JAVA_HOME = $jdk17Path
$env:PATH = "$jdk17Path\bin;$cmdlineToolsBin;$env:PATH"

Write-Host "Accepting SDK licenses (auto-yes)..."
$yes = New-Object System.Text.StringBuilder
for ($i=0; $i -lt 20; $i++) { [void]$yes.AppendLine("y") }
$yes.ToString() | & $cmdlineToolsBin --licenses 2>&1 | Out-Null

Write-Host "Installing platforms;android-35, build-tools;35.0.0, platform-tools..."
& $cmdlineToolsBin "platforms;android-35" "build-tools;35.0.0" "platform-tools" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager install failed (exit $LASTEXITCODE). Check $androidHome"
}
Write-Host "SDK packages installed" -ForegroundColor Green

# ---- 5. Per-user environment variables -----------------------------------
Write-Step 5 "Setting USER environment variables (no admin needed)"

[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk17Path, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $androidHome, "User")

# Update user PATH
$pathAdditions = @(
    (Join-Path $jdk17Path "bin"),
    (Join-Path $androidHome "cmdline-tools\latest\bin"),
    (Join-Path $androidHome "platform-tools")
)
$currentUserPath = [Environment]::GetEnvironmentVariable("Path", "User")
$newUserPath = $currentUserPath
foreach ($p in $pathAdditions) {
    if ($newUserPath -notlike "*$p*") {
        if ($newUserPath.Length -gt 0 -and -not $newUserPath.EndsWith(';')) {
            $newUserPath += ';'
        }
        $newUserPath += $p
    }
}
[Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
Write-Host "User env vars set:" -ForegroundColor Green
Write-Host "  JAVA_HOME = $jdk17Path"
Write-Host "  ANDROID_HOME = $androidHome"
Write-Host "  ANDROID_SDK_ROOT = $androidHome"

# ---- 6. Verify in this session -------------------------------------------
Write-Step 6 "Verifying in current session"

# Set for the current process so the build that follows sees the right env
$env:JAVA_HOME = $jdk17Path
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:PATH = "$jdk17Path\bin;$androidHome\cmdline-tools\latest\bin;$androidHome\platform-tools;$env:PATH"

$javaCheck = & "$jdk17Path\bin\java.exe" -version 2>&1 | Select-Object -First 1
Write-Host "java -version : $javaCheck" -ForegroundColor Green

$sdkmanager = "$androidHome\cmdline-tools\latest\bin\sdkmanager.bat"
$installed = & $sdkmanager --list_installed 2>&1 | Select-String -Pattern "platforms;android-35|build-tools;35.0.0|platform-tools" | ForEach-Object { $_.Line.Trim() }
Write-Host "Installed SDK packages:" -ForegroundColor Green
foreach ($pkg in $installed) { Write-Host "  $pkg" }

Write-Host ""
Write-Host "DONE." -ForegroundColor Green
Write-Host ""
Write-Host "IMPORTANT: Close and reopen any terminals / IDEs for the new env vars to take effect." -ForegroundColor Yellow
Write-Host ""
Write-Host "Then verify in a NEW terminal:"
Write-Host "  java -version"
Write-Host "  sdkmanager --list_installed"
Write-Host ""
Write-Host "Then build the APK:"
Write-Host "  cd android"
Write-Host "  .\gradlew assembleDebug"
Write-Host ""
Write-Host "APK output: android\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Install on phone: adb install -r android\app\build\outputs\apk\debug\app-debug.apk"
