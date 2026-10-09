@echo off
title FortCraft setup
rem One-time setup on Windows: see tools\setup_windows.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_windows.ps1"
echo.
pause
