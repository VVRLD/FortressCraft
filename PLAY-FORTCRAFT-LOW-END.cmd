@echo off
title FortCraft (low-end mode)
if not exist "%~dp0tools\config.local.cmd" (
  echo Setup has not been run yet. Double-click START-HERE.cmd first.
  pause
  exit /b 1
)
rem Low-end mode, for weaker PCs and laptops: both games at 60 fps, TF2 at 720p on its lowest
rem graphics, and Minecraft's settings turned down (fast graphics, 6 chunks, fewer particles).
rem Minecraft's settings stay lowered afterwards; raise them again in Minecraft's options.
if not exist "%~dp0fabric\run" mkdir "%~dp0fabric\run"
echo FortCraft low-end mode: started with PLAY-FORTCRAFT-LOW-END.cmd> "%~dp0fabric\run\fortcraft_low_end.txt"
call "%~dp0tools\play_normal.bat" -fortcraft_lowend %*
