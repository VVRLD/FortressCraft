@echo off
title FortCraft
if not exist "%~dp0tools\config.local.cmd" (
  echo Setup has not been run yet. Double-click START-HERE.cmd first.
  pause
  exit /b 1
)
rem Normal Minecraft terrain with mobs. For the flat test world use tools\play.bat instead.
rem The normal launch: low-end mode off (see PLAY-FORTCRAFT-LOW-END.cmd).
if exist "%~dp0fabric\run\fortcraft_low_end.txt" del "%~dp0fabric\run\fortcraft_low_end.txt"
call "%~dp0tools\play_normal.bat" %*
