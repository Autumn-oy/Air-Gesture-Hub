@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0adb.ps1" %*
exit /b %ERRORLEVEL%
