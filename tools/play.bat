@echo off
rem Starts Minecraft (with the FortCraft mod) and our hidden TF2 mod together, after building
rem whatever changed in the TF2 mod and its map. To stop: close Minecraft. TF2 closes by itself a moment later.
setlocal
call "%~dp0build_tf2.bat" || (pause & exit /b 1)
call "%~dp0build_map.bat" >nul || (echo Building TF2's map failed: run tools\build_map.bat to see why. & pause & exit /b 1)
start "Minecraft (FortCraft)" /MIN /D "%~dp0..\fabric" cmd /c gradlew.bat runClient
call "%~dp0run_tf2.bat" %*
