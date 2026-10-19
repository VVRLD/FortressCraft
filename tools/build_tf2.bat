@echo off
rem Builds our TF2 mod's client.dll and server.dll (only what changed) and installs them into
rem ..\..\source-sdk-2013\game\mod_tf\bin\x64. TF2 must not be running.
rem Uses the Visual Studio found by setup_windows.ps1 (tools\config.local.cmd) when present.
setlocal
set "MSBUILD=C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\amd64\MSBuild.exe"
set "FORTCRAFT_TOOLSET=v145"
if exist "%~dp0config.local.cmd" call "%~dp0config.local.cmd"
if defined FORTCRAFT_PREBUILT goto prebuilt
if defined FORTCRAFT_MSBUILD set "MSBUILD=%FORTCRAFT_MSBUILD%"
rem Python found by setup outside PATH (GitHub issue 18): Valve's build runs "python".
if defined FORTCRAFT_PYTHON_DIR set "PATH=%FORTCRAFT_PYTHON_DIR%;%PATH%"
if not exist "%MSBUILD%" (echo Visual Studio's MSBuild not found. Run tools\setup_windows.bat first. & exit /b 1)
set SRC=%~dp0..\..\source-sdk-2013\src
tasklist /fi "imagename eq mod_tf_win64.exe" | find /i "mod_tf_win64.exe" >nul && (
  echo TF2 is still running. Close Minecraft, wait a few seconds, and try again.
  exit /b 1
)
rem Valve's build turns its VScript (.nut) files into *_nut.h headers with Python. If Python was
rem missing on an earlier try, those headers were left empty and are never made again
rem ("g_Script_spawn_helper: undeclared identifier", GitHub issue 2): delete empty ones.
for /r "%SRC%\game" %%F in (*_nut.h) do if %%~zF LSS 64 del "%%F"
for %%P in (game\client\client_win64_tf.vcxproj game\server\server_win64_tf.vcxproj) do (
  "%MSBUILD%" "%SRC%\%%P" /m /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=%FORTCRAFT_TOOLSET% /v:quiet /nologo /clp:ErrorsOnly || exit /b 1
)
echo TF2 mod built and installed.
exit /b 0

:prebuilt
rem Release download: the TF2 side comes ready-built in ..\prebuilt\win64 (no Visual Studio).
rem Install it again if it differs from what's installed (a newer release unzipped over this one).
set "PRE=%~dp0..\prebuilt\win64"
set "GAME=%~dp0..\..\source-sdk-2013\game"
if not exist "%PRE%\client.dll" (echo The ready-built TF2 files are missing from prebuilt\win64. Run START-HERE.cmd again. & exit /b 1)
for %%F in (client.dll server.dll) do fc /b "%PRE%\%%F" "%GAME%\mod_tf\bin\x64\%%F" >nul 2>nul || copy /y "%PRE%\%%F" "%GAME%\mod_tf\bin\x64\%%F" >nul || (echo Couldn't install %%F: is TF2 still running? & exit /b 1)
fc /b "%PRE%\mod_tf_win64.exe" "%GAME%\mod_tf_win64.exe" >nul 2>nul || copy /y "%PRE%\mod_tf_win64.exe" "%GAME%\mod_tf_win64.exe" >nul || (echo Couldn't install mod_tf_win64.exe: is TF2 still running? & exit /b 1)
exit /b 0
