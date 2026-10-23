$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot 'tools/adb.exe'
function Run-Adb([string[]]$Arguments) {
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB operation failed. Check the phone address, code and authorization.' }
}
Run-Adb -Arguments @('mdns','services')
$pairEndpoint = (Read-Host 'Pairing IP:port from phone pairing-code dialog (Enter to skip)').Trim()
if ($pairEndpoint) {
    $pairCode = (Read-Host 'Current six-digit pairing code').Trim()
    Run-Adb -Arguments @('pair',$pairEndpoint,$pairCode)
    $pairCode = $null
}
$connectEndpoint = (Read-Host 'Connection IP:port from Wireless debugging MAIN page').Trim()
if ($connectEndpoint) { Run-Adb -Arguments @('connect',$connectEndpoint) }
Run-Adb -Arguments @('devices','-l')
Write-Host 'Return to the receiver window and click Refresh devices.'
