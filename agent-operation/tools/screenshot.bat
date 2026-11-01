@echo off
rem Thin wrapper so the tool can be run without typing the execution-policy flags.
rem Usage: screenshot.bat -Name rwx   |   screenshot.bat -Id 1234 -ClientOnly
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0screenshot.ps1" %*
exit /b %ERRORLEVEL%
