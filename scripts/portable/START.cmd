@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0portable.ps1" %*
if errorlevel 1 echo Operation failed. Please read the error above.
pause
