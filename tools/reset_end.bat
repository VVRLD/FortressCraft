@echo off
rem Repeatable, backup-first reset for FortCraft Normal's End dimension.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0reset_end.ps1"
if errorlevel 1 pause
