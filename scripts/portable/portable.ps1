param(
    [ValidateSet('Menu','Check','Connect','Install','Build','Verify','Log','Shell','Tcp')]
    [string]$Action = 'Menu',
    [string]$Device = ''
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
$bundleRoot = $PSScriptRoot
$projectRoot = Join-Path $bundleRoot 'CARLIFE'
$env:JAVA_HOME = Join-Path $bundleRoot 'tools/jdk17'
$env:ANDROID_HOME = Join-Path $bundleRoot 'tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $bundleRoot 'gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$gradlew = Join-Path $projectRoot 'gradlew.bat'
$logDir = Join-Path $bundleRoot 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Check-Environment {
    foreach ($required in @($java, $adb, $gradlew,
            (Join-Path $bundleRoot 'tools/gradle-8.9/bin/gradle.bat'),
            (Join-Path $env:ANDROID_HOME 'platforms/android-35/android.jar'),
            (Join-Path $env:ANDROID_HOME 'build-tools/34.0.0/aapt2.exe'),
            (Join-Path $projectRoot 'signing/carlife-debug.keystore'))) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Missing file: $required. Extract the ENTIRE ZIP first." }
    }
    $configPath = Join-Path $projectRoot 'local.properties'
    $configLines = @()
    if (Test-Path -LiteralPath $configPath) {
        $configLines = @([System.IO.File]::ReadAllLines($configPath) |
                Where-Object { $_ -notmatch '^\s*sdk\.dir\s*[:=]' })
    }
    $configLines += 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')
    [System.IO.File]::WriteAllLines($configPath, $configLines,
            [System.Text.UTF8Encoding]::new($false))
    Write-Host "Bundle: $bundleRoot"
    & $java -version
    if ($LASTEXITCODE -ne 0) { throw 'Bundled JDK failed to run' }
    & $adb version
    if ($LASTEXITCODE -ne 0) { throw 'Bundled ADB failed to run' }
    Write-Host 'Environment ready. Builds use the bundled tools and offline dependency cache.' -ForegroundColor Green
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed (exit $LASTEXITCODE)" }
}

function Select-Phone {
    $deviceLines = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list ADB devices' }
    $ready = @($deviceLines | Where-Object { $_ -match '^\S+\s+device\s*$' } |
            ForEach-Object { ($_ -split '\s+')[0] })
    if ($Device) {
        if ($ready -contains $Device) { return $Device }
        throw "Device $Device is not connected or authorized. Use Connect first."
    }
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -eq 0) {
        Write-Host ($deviceLines -join "`n")
        throw 'No authorized phone. Enable USB debugging and accept authorization, or use Connect.'
    }
    for ($i = 0; $i -lt $ready.Count; $i++) { Write-Host "$($i + 1). $($ready[$i])" }
    $choice = 0
    if (-not [int]::TryParse((Read-Host 'Choose phone number'), [ref]$choice) -or
            $choice -lt 1 -or $choice -gt $ready.Count) { throw 'Invalid phone number' }
    return $ready[$choice - 1]
}

function Connect-Phone {
    Write-Host 'A new laptop normally needs a NEW pairing. Do not reuse the old six-digit code.'
    Invoke-Adb -Arguments @('mdns','services')
    $pairAddress = (Read-Host 'Pairing IP:port (from Pair device with pairing code); Enter to skip').Trim()
    if ($pairAddress) {
        $pairCode = (Read-Host 'Current six-digit pairing code').Trim()
        Invoke-Adb -Arguments @('pair',$pairAddress,$pairCode)
        $pairCode = $null
    }
    $connectAddress = (Read-Host 'Connection IP:port (Wireless debugging MAIN page; different from pairing port)').Trim()
    if ($connectAddress) { Invoke-Adb -Arguments @('connect',$connectAddress) }
    Invoke-Adb -Arguments @('devices','-l')
}

function Install-App {
    $serial = Select-Phone
    $apk = Join-Path $bundleRoot 'APK/CarLink-Lab-0.16.1.apk'
    $builtApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    if (Test-Path -LiteralPath $builtApk) { $apk = $builtApk }
    if (-not (Test-Path -LiteralPath $apk)) { throw 'No APK found. Run Build first.' }
    Write-Host "Installing $apk on $serial"
    Invoke-Adb -Arguments @('-s',$serial,'install','-r',$apk)
    Invoke-Adb -Arguments @('-s',$serial,'shell','am','start','-W','-n','io.github.carlinklab/.MainActivity')
}

function Build-App {
    param([bool]$Verify = $false)
    $tasks = @('assembleDebug')
    if ($Verify) { $tasks += @('testDebugUnitTest','lintDebug') }
    $buildLog = Join-Path $logDir ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
    Push-Location $projectRoot
    try {
        # Windows PowerShell 5.1 treats redirected native stderr as ErrorRecords.
        # Compiler warnings must not terminate an otherwise successful build.
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $gradlew @tasks --offline --no-daemon --console=plain --max-workers=2 2>&1 |
                    ForEach-Object { $_.ToString() } | Tee-Object -FilePath $buildLog | Out-Host
            $buildExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($buildExit -ne 0) { throw "Build failed (exit $buildExit). Log: $buildLog" }
        $latestApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
        Copy-Item -LiteralPath $latestApk -Destination (Join-Path $bundleRoot 'APK/CarLink-Lab-latest.apk') -Force
        Write-Host "Built: $latestApk" -ForegroundColor Green
    } finally { Pop-Location }
}

function Show-Logs {
    $serial = Select-Phone
    $logFile = Join-Path $logDir ('phone-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
    Write-Host "Saving log to $logFile. Press Ctrl+C to stop."
    & $adb -s $serial logcat -v time 'CarLifeBridge:D' 'CarLifeClient:D' 'H264FrameEncoder:D' 'WebViewDashboard:D' 'TucsonTpms:D' 'AndroidRuntime:E' '*:S' |
            Tee-Object -FilePath $logFile | Out-Host
}

function Enable-Tcp {
    $serial = Select-Phone
    Write-Host 'Enable this BEFORE moving the laptop to the phone hotspot.'
    Write-Host 'This restarts adbd and may interrupt a CarLife USB/ADB connection. Recheck the dashboard afterward.'
    Invoke-Adb -Arguments @('-s',$serial,'tcpip','5555')
    Write-Host 'After connecting the laptop to the hotspot, use Connect and enter PHONE_HOTSPOT_IP:5555.'
    Write-Host 'The phone hotspot IP is usually the laptop Wi-Fi default gateway. A reboot may require enabling TCP again.'
}

function Open-Shell {
    Push-Location $projectRoot
    try {
        Write-Host 'Portable environment active. Example: .\gradlew.bat assembleDebug --offline'
        & powershell.exe -NoLogo -NoProfile -NoExit
    } finally { Pop-Location }
}

Check-Environment
switch ($Action) {
    'Check' { exit 0 }
    'Connect' { Connect-Phone; exit 0 }
    'Install' { Install-App; exit 0 }
    'Build' { Build-App; exit 0 }
    'Verify' { Build-App -Verify $true; exit 0 }
    'Log' { Show-Logs; exit 0 }
    'Shell' { Open-Shell; exit 0 }
    'Tcp' { Enable-Tcp; exit 0 }
}

while ($true) {
    Write-Host "`nCarLink Lab 0.16.1 - Windows portable debugging" -ForegroundColor Cyan
    Write-Host '1 Check tools and list phones'
    Write-Host '2 Pair / connect phone (Wi-Fi or hotspot TCP)'
    Write-Host '3 Install APK and open app'
    Write-Host '4 View and save CarLife logs'
    Write-Host '5 Build APK offline'
    Write-Host '6 Offline build + unit tests + Android Lint'
    Write-Host '7 Open PowerShell with portable environment'
    Write-Host '8 Enable legacy TCP 5555 before switching to hotspot'
    Write-Host '0 Exit'
    try {
        switch ((Read-Host 'Select').Trim()) {
            '1' { Invoke-Adb -Arguments @('devices','-l') }
            '2' { Connect-Phone }
            '3' { Install-App }
            '4' { Show-Logs }
            '5' { Build-App }
            '6' { Build-App -Verify $true }
            '7' { Open-Shell }
            '8' { Enable-Tcp }
            '0' { exit 0 }
            default { Write-Host 'Please choose 0-8.' }
        }
    } catch { Write-Host $_.Exception.Message -ForegroundColor Red }
}
