@echo off
rem Windows: packs FortCraft's and both games' logs plus basic system facts into one zip to attach
rem to a GitHub issue (see tools\collect_logs.ps1). Writes FortCraft-windows-logs-<time>.zip next
rem to my-passthrough.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect_logs.ps1"
pause
