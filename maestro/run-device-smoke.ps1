[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$appId = 'com.hermexapp.android'
$component = 'com.hermexapp.android/.MainActivity'
$maestroRoot = $PSScriptRoot

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw 'adb was not found on PATH. Set up Android platform-tools first.'
}

$maestroCommand = Get-Command maestro -ErrorAction SilentlyContinue
if ($null -eq $maestroCommand) {
    throw 'maestro was not found on PATH. Set up the Maestro CLI first.'
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME is not set. Point it to JDK 17 before running Maestro.'
}
$javaBin = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaBin -PathType Leaf)) {
    throw "JAVA_HOME does not contain bin\java.exe: $env:JAVA_HOME"
}
$env:Path = "$(Split-Path -Parent $javaBin);$env:Path"

$deviceOutput = @(& $adbCommand.Source devices -l)
if ($LASTEXITCODE -ne 0) {
    throw "adb devices -l failed with exit code $LASTEXITCODE."
}
$deviceLines = @($deviceOutput | Where-Object { $_ -match '^\S+\s+\S+' })
$unauthorizedLines = @($deviceLines | Where-Object { $_ -notmatch '^\S+\s+device(?:\s|$)' })
if ($unauthorizedLines.Count -gt 0) {
    throw "ADB has a device that is not authorized/online: $($unauthorizedLines -join '; ')"
}
$authorizedLines = @($deviceLines | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
if ($authorizedLines.Count -ne 1) {
    throw "Expected exactly one authorized ADB device; found $($authorizedLines.Count)."
}

$serial = ($authorizedLines[0] -split '\s+')[0]
$modelMatch = [regex]::Match($authorizedLines[0], '(?:^|\s)model:(\S+)')
$model = if ($modelMatch.Success) { $modelMatch.Groups[1].Value } else { 'unknown' }
$androidVersion = (& $adbCommand.Source -s $serial shell getprop ro.build.version.release | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not read Android version from $serial."
}

$packagePath = (& $adbCommand.Source -s $serial shell pm path $appId | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $packagePath -notmatch '^package:') {
    throw "$appId is not installed on $serial. Follow the optional package-update step in maestro/README.md."
}

function Get-PermissionSnapshot {
    $dump = @(& $adbCommand.Source -s $serial shell dumpsys package $appId)
    if ($LASTEXITCODE -ne 0) {
        throw "dumpsys package failed for $appId."
    }
    return @(
        $dump |
            Where-Object { $_ -match 'granted=(?:true|false)' } |
            ForEach-Object { $_.Trim() } |
            Sort-Object -Unique
    )
}

$permissionsBefore = @(Get-PermissionSnapshot)
Write-Output "Device: $model ($serial), Android $androidVersion"
Write-Output "Package: $packagePath"

$startOutput = @(& $adbCommand.Source -s $serial shell am start -W -n $component)
if ($LASTEXITCODE -ne 0) {
    throw "Could not foreground $component.`n$($startOutput -join "`n")"
}

$maestroExitCode = 1
$permissionChanged = $false
try {
    & $maestroCommand.Source test $maestroRoot
    $maestroExitCode = $LASTEXITCODE
}
finally {
    $permissionsAfter = @(Get-PermissionSnapshot)
    $permissionDiff = @(Compare-Object -ReferenceObject $permissionsBefore -DifferenceObject $permissionsAfter)
    if ($permissionDiff.Count -gt 0) {
        $permissionChanged = $true
        Write-Error -ErrorAction Continue "Runtime permission state changed during the Maestro smoke run:`n$($permissionDiff | Out-String)"
    }
}

if ($permissionChanged) {
    exit 2
}
if ($maestroExitCode -ne 0) {
    exit $maestroExitCode
}

Write-Output 'Safe Maestro device smoke passed; runtime permission state is unchanged.'
