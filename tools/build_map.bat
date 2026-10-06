@echo off
rem Compiles tf2mod\maps\fortcraft_flat.vmf (made by tools\make_flat_map.py) with the SDK's map
rem tools and installs it as ..\..\source-sdk-2013\game\mod_tf\maps\fortcraft_flat.bsp.
setlocal
rem Full paths (the tools can't write their log through ".." paths).
for %%I in ("%~dp0..") do set ROOT=%%~fI
for %%I in ("%~dp0..\..\source-sdk-2013") do set SDK=%%~fI
set BIN=%~dp0..\..\source-sdk-2013\game\bin\x64
set GAME=%SDK%\game\mod_tf
rem The tools can't use the TF2 mod's own game config (it mounts content by Steam app id), so
rem they get a small one of their own.
set BUILDGAME=%~dp0..\tf2mod\maps\buildgame
set SRC=%~dp0..\tf2mod\maps\fortcraft_flat.vmf
set WORK=%ROOT%\logs\map
rem The tools need the engine's own DLLs (tier0, vstdlib, filesystem) from Source SDK Base 2013.
set PATH=C:\Program Files (x86)\Steam\steamapps\common\Source SDK Base 2013 Multiplayer\bin\x64;%PATH%
if not exist "%WORK%" mkdir "%WORK%"
copy /y "%SRC%" "%WORK%\fortcraft_flat.vmf" >nul || exit /b 1
"%BIN%\vbsp.exe" -game "%BUILDGAME%" "%WORK%\fortcraft_flat" || exit /b 1
rem No vvis / vrad: the map is one empty space (vvis says "Empty map" and stops), so there's
rem nothing to work out about visibility; lighting is full-bright in-game anyway.
if not exist "%GAME%\maps" mkdir "%GAME%\maps"
copy /y "%WORK%\fortcraft_flat.bsp" "%GAME%\maps\fortcraft_flat.bsp" >nul || exit /b 1
echo Map built and installed.
