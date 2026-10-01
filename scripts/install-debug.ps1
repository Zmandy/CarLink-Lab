param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build-debug.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    throw "APK not found: $apk"
}

$candidatePaths = @()
if ($env:LOCALAPPDATA) {
    $candidatePaths += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
if ($env:ANDROID_HOME) {
    $candidatePaths += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
}
if ($env:ANDROID_SDK_ROOT) {
    $candidatePaths += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
}
$adbCandidates = @($candidatePaths | Where-Object { Test-Path $_ })

if ($adbCandidates.Count -eq 0) {
    throw 'adb.exe not found. Set ANDROID_HOME or install Android platform-tools.'
}

$adb = $adbCandidates[0]
& $adb devices -l
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "ADB install failed with exit code $LASTEXITCODE"
}

Write-Host "Installed: $apk"