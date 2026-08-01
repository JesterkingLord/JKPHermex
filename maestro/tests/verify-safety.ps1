[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$maestroRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([Parameter(Mandatory)][string]$Message)
    $failures.Add($Message)
}

$requiredRelativePaths = @(
    'config.yaml',
    'run-device-smoke.ps1',
    'README.md',
    'flows\connected-smoke.yaml',
    'subflows\require-connected-sessions.yaml',
    'subflows\primary-navigation.yaml',
    'subflows\existing-chat-scroll.yaml'
)

foreach ($relativePath in $requiredRelativePaths) {
    $path = Join-Path $maestroRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-Failure "Missing required Maestro file: $relativePath"
    }
}

$yamlFiles = Get-ChildItem -LiteralPath $maestroRoot -Recurse -Filter '*.yaml' -File -ErrorAction SilentlyContinue
$forbiddenYamlCommands = @(
    'launchApp',
    'clearState',
    'clearKeychain',
    'setPermissions',
    'inputText',
    'pasteText',
    'eraseText',
    'setClipboard',
    'openLink',
    'longPressOn',
    'stopApp',
    'killApp'
)

foreach ($yamlFile in $yamlFiles) {
    $content = Get-Content -LiteralPath $yamlFile.FullName -Raw
    foreach ($command in $forbiddenYamlCommands) {
        if ($content -match "(?m)^\s*-\s*$([regex]::Escape($command))\s*:?\s*") {
            Add-Failure "$($yamlFile.FullName): forbidden command '$command'"
        }
    }

    $mutatingTapPattern = '(?ms)^\s*-\s*tapOn\s*:\s*(?:(?!^\s*-\s).){0,320}\b(Send message|Stop response|Delete|Archive|Pin selected|Save|Connect|Pair|Update|Sign out|Forget server|Create|New chat)\b'
    if ($content -match $mutatingTapPattern) {
        Add-Failure "$($yamlFile.FullName): tapOn targets a state-changing action ('$($Matches[1])')"
    }
}

$runnerPath = Join-Path $maestroRoot 'run-device-smoke.ps1'
if (Test-Path -LiteralPath $runnerPath -PathType Leaf) {
    $runner = Get-Content -LiteralPath $runnerPath -Raw
    $forbiddenRunnerPatterns = @(
        '(?i)\bpm\s+clear\b',
        '(?i)\buninstall\b',
        '(?i)\binstall(?:-multiple)?\b',
        '(?i)\bforce-stop\b',
        '(?i)\bam\s+kill\b',
        '(?i)\bpm\s+(?:grant|revoke)\b',
        '(?i)\bclearState\b'
    )
    foreach ($pattern in $forbiddenRunnerPatterns) {
        if ($runner -match $pattern) {
            Add-Failure "run-device-smoke.ps1 contains forbidden device mutation matching '$pattern'"
        }
    }

    $requiredRunnerPatterns = @(
        '(?i)Get-Command\s+adb',
        '(?i)Get-Command\s+maestro',
        '(?i)JAVA_HOME',
        '(?i)devices\s+-l',
        '(?i)com\.hermexapp\.android',
        '(?i)dumpsys\s+package',
        '(?i)com\.hermexapp\.android/\.MainActivity',
        '(?i)maestro.+test.+maestro',
        '(?i)permission.+changed'
    )
    foreach ($pattern in $requiredRunnerPatterns) {
        if ($runner -notmatch $pattern) {
            Add-Failure "run-device-smoke.ps1 is missing safety behavior matching '$pattern'"
        }
    }
}

$configPath = Join-Path $maestroRoot 'config.yaml'
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    $config = Get-Content -LiteralPath $configPath -Raw
    if ($config -notmatch '(?m)^appId:\s*com\.hermexapp\.android\s*$') {
        Add-Failure 'config.yaml must target com.hermexapp.android'
    }
    if ($config -notmatch '(?m)^\s*-\s*[''"]?flows/\*\.yaml[''"]?\s*$') {
        Add-Failure 'config.yaml must discover only flows/*.yaml'
    }
    if ($config -notmatch '(?m)^\s*continueOnFailure:\s*false\s*$') {
        Add-Failure 'config.yaml must stop on the first failed flow'
    }
}

if ($failures.Count -gt 0) {
    Write-Error ("Maestro safety verification failed:`n - " + ($failures -join "`n - "))
    exit 1
}

Write-Output "Maestro safety verification passed ($($yamlFiles.Count) YAML files checked)."
