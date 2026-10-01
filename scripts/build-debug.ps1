param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$gradle = $null
$distRoot = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.9-bin'
if (Test-Path $distRoot) {
    $gradle = Get-ChildItem $distRoot -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\gradle-8\.9\\bin\\gradle\.bat$' } |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $gradle) {
    $gradle = Join-Path $root 'gradlew.bat'
}

"Using Gradle: $gradle"
$arguments = @(':app:assembleDebug')
if ($Offline) {
    $arguments += '--offline'
}

& $gradle @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    throw "Build finished but APK was not found: $apk"
}

Write-Host "APK: $apk"