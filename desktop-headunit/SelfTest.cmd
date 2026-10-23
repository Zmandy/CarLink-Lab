@echo off
"%~dp0ProtocolChecks.exe"
if errorlevel 1 goto failed
start /wait "" "%~dp0CarLinkHeadUnit.exe" --self-test
if errorlevel 1 (
  goto failed
) else (
  echo Self test passed. Read self-test.txt.
)
pause
exit /b 0
:failed
echo Self test failed. Read startup-error.txt and self-test.txt.
pause
exit /b 1
