@echo off
title FortCraft
if not exist "%~dp0tools\config.local.cmd" (
  echo Setup has not been run yet. Double-click START-HERE.cmd first.
  pause
  exit /b 1
)
rem Normal Minecraft terrain with mobs. For the flat test world use tools\play.bat instead.
call "%~dp0tools\play_normal.bat" %*
