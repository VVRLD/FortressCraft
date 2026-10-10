@echo off
title FortCraft setup
rem One-time setup on Windows. See INSTALL-WINDOWS.md.
rem Runs the script's text rather than the file, so PCs that block .ps1 scripts still work.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$env:FORTCRAFT_TOOLS='%~dp0tools\'; & ([scriptblock]::Create((Get-Content -Raw -LiteralPath '%~dp0tools\setup_windows.ps1')))"
echo.
pause
