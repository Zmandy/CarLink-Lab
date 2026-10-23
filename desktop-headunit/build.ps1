param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
if (-not $Destination) { $Destination = Join-Path $PSScriptRoot 'build' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$Destination = (Resolve-Path -LiteralPath $Destination).Path
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework C# compiler is missing' }
$mainExe = Join-Path $Destination 'CarLinkHeadUnit.exe'
$checksExe = Join-Path $Destination 'ProtocolChecks.exe'
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /utf8output /reference:System.Windows.Forms.dll /reference:System.Drawing.dll "/out:$mainExe" (Join-Path $PSScriptRoot 'HeadUnit.cs')
if ($LASTEXITCODE -ne 0) { throw 'Desktop receiver compilation failed' }
& $compiler /nologo /target:exe /platform:anycpu /optimize+ /utf8output "/reference:$mainExe" "/out:$checksExe" (Join-Path $PSScriptRoot 'ProtocolChecks.cs')
if ($LASTEXITCODE -ne 0) { throw 'Protocol checks compilation failed' }
Write-Output "Built: $Destination/CarLinkHeadUnit.exe"
