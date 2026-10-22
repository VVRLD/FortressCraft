@echo off
rem Starts Minecraft (with the FortCraft mod) and our hidden TF2 mod together, after building
rem whatever changed in the TF2 mod. To stop: close Minecraft. TF2 closes by itself a moment later.
setlocal
if exist "%~dp0config.local.cmd" call "%~dp0config.local.cmd"
rem Minecraft's build needs Java 25 (setup_windows.ps1 finds it).
if defined FORTCRAFT_JAVA_HOME set "JAVA_HOME=%FORTCRAFT_JAVA_HOME%"
call "%~dp0build_tf2.bat" || (pause & exit /b 1)
rem The test map: ready-made in prebuilt\, or compiled with tools\build_map.bat.
set "MAP=%~dp0..\..\source-sdk-2013\game\mod_tf\maps\fortcraft_flat.bsp"
rem An older copy is replaced too: a new release can fix the map (GitHub PR 4).
fc /b "%~dp0..\prebuilt\fortcraft_flat.bsp" "%MAP%" >nul 2>nul || if exist "%~dp0..\prebuilt\fortcraft_flat.bsp" (
  if not exist "%~dp0..\..\source-sdk-2013\game\mod_tf\maps" mkdir "%~dp0..\..\source-sdk-2013\game\mod_tf\maps"
  copy /y "%~dp0..\prebuilt\fortcraft_flat.bsp" "%MAP%" >nul
)
if not exist "%MAP%" call "%~dp0build_map.bat" >nul || (echo Building TF2's map failed: run tools\build_map.bat to see why. & pause & exit /b 1)
rem This window only starts Minecraft. Players watched its "88%% EXECUTING" line for an hour
rem (GitHub issues 27, 32): it stays at 88%% for as long as Minecraft is open, so it says so.
start "Minecraft (FortCraft) - leave this open" /MIN /D "%~dp0..\fabric" cmd /c "echo This window starts Minecraft. It shows 88%% EXECUTING for as long as Minecraft is open: that is normal. Play in the Minecraft window - check the taskbar. & .\gradlew.bat runClient"
call "%~dp0run_tf2.bat" %*
