@echo off
title FortCraft setup
rem One-time setup on Windows. See INSTALL-WINDOWS.md.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup_windows.ps1"
echo.
pause
