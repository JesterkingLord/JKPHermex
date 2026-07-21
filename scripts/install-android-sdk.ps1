# install-android-sdk.ps1
# Installs the Android SDK (cmdline-tools + platforms;android-35 + build-tools;35.0.0 + platform-tools)
# to %LOCALAPPDATA%\Android\Sdk and sets per-user environment variables.
# No admin needed. JDK 17 must already be installed.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\install-android-sdk.ps1

#Requires -Version 5.1
$ErrorActionPreference = 'Continue'

function Write-Step($n, $title) {
    Write-Host ""
    Write-Host "[$n/4] $title" -ForegroundColor Cyan
    Write-Host ("-" * 60)
}

# ---- 1. Pre-flight -------------------------------------------------------
Write-Step 1 "Pre-flight checks"

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget not found on PATH."
}

# Confirm JDK 17 is reachable
$jdk17Path = "C:\Users\roflm\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot"
if (-not (Test-Path $jdk17Path)) {
    # Try to find any jdk-17* under %LOCALAPPDATA%\Programs\Microsoft
    $candidates = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA "Programs\Microsoft") -Directory -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -match '^jdk-17' }
    if ($candidates.Count -gt 0) {
        $jdk17Path = $candidates[0].FullName
    } else {
        throw "JDK 17 not found at $jdk17Path. Run scripts\install-android-toolchain-nouac.ps1 first."
    }
}
Write-Host "JDK 17 at: $jdk17Path" -ForegroundColor Green

# ---- 2. cmdline-tools ----------------------------------------------------
Write-Step 2 "Downloading Android cmdline-tools"

$androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path $androidHome)) {
    New-Item -ItemType Directory -Force -Path $androidHome | Out-Null
}

$cmdlineToolsBin = Join-Path $androidHome "cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $cmdlineToolsBin) {
    Write-Host "cmdline-tools already present" -ForegroundColor Green
} else {
    $zipPath = Join-Path $env:TEMP "commandlinetools-win-11076708.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Write-Host "Downloading $url"
    Write-Host "  to: $zipPath"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $wc = New-Object System.Net.WebClient
    $wc.DownloadFile($url, $zipPath)
    $size = (Get-Item $zipPath).Length
    Write-Host "Downloaded $([math]::Round($size/1MB,1)) MB" -ForegroundColor Green
    if ($size -lt 50MB) { throw "Download too small, aborting." }

    # Extract
    $tempExtract = Join-Path $env:TEMP ("cmdline-tools-extract-{0}" -f (Get-Random))
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
    Write-Host "Extracting to $tempExtract"
    Expand-Archive -Path $zipPath -DestinationPath $tempExtract -Force

    # The zip extracts to tempExtract/cmdline-tools/ — move to $androidHome/cmdline-tools/latest/
    $extractedDir = Join-Path $tempExtract "cmdline-tools"
    $targetDir = Join-Path $androidHome "cmdline-tools\latest"
    if (Test-Path $targetDir) { Remove-Item -Recurse -Force $targetDir }
    New-Item -ItemType Directory -Force -Path (Join-Path $androidHome "cmdline-tools") | Out-Null
    Move-Item -Path $extractedDir -Destination $targetDir -Force

    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
    Write-Host "cmdline-tools installed to $targetDir" -ForegroundColor Green
}

# ---- 3. SDK packages ----------------------------------------------------
Write-Step 3 "Installing SDK packages (platforms;android-35, build-tools;35.0.0, platform-tools)"

$env:JAVA_HOME = $jdk17Path
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:PATH = "$jdk17Path\bin;$cmdlineToolsBin;$env:PATH"

Write-Host "Accepting SDK licenses..."
$yes = New-Object System.Text.StringBuilder
for ($i=0; $i -lt 20; $i++) { [void]$yes.AppendLine("y") }
$yes.ToString() | & $cmdlineToolsBin --licenses 2>&1 | Out-Null
Write-Host "Licenses accepted" -ForegroundColor Green

Write-Host "Installing platforms;android-35, build-tools;35.0.0, platform-tools..."
& $cmdlineToolsBin "platforms;android-35" "build-tools;35.0.0" "platform-tools" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager install failed (exit $LASTEXITCODE)"
}
Write-Host "SDK packages installed" -ForegroundColor Green

# ---- 4. Per-user env vars -----------------------------------------------
Write-Step 4 "Setting per-user environment variables"

[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk17Path, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $androidHome, "User")

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

Write-Host ""
Write-Host "DONE." -ForegroundColor Green
Write-Host ""
Write-Host "Close and reopen any terminals / IDEs for the new env vars to take effect." -ForegroundColor Yellow
Write-Host ""
Write-Host "Verify in a NEW terminal:"
Write-Host "  java -version"
Write-Host "  adb --version"
Write-Host "  sdkmanager --list_installed"
Write-Host ""
Write-Host "Then build:"
Write-Host "  cd E:\JKPHermex\android"
Write-Host "  .\gradlew assembleDebug"
Write-Host ""
Write-Host "APK output: android\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Install on phone: adb install -r android\app\build\outputs\apk\debug\app-debug.apk"
