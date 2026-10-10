@echo off
title FortCraft setup
rem One-time setup on Windows: see tools\setup_windows.ps1.
rem Runs the script's text rather than the file, so PCs that block .ps1 scripts still work.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$env:FORTCRAFT_TOOLS='%~dp0'; & ([scriptblock]::Create((Get-Content -Raw -LiteralPath '%~dp0setup_windows.ps1')))"
echo.
pause
