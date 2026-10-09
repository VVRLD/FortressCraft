@echo off
rem Builds our TF2 mod's client.dll and server.dll (only what changed) and installs them into
rem ..\..\source-sdk-2013\game\mod_tf\bin\x64. TF2 must not be running.
rem Uses the Visual Studio found by setup_windows.ps1 (tools\config.local.cmd) when present.
setlocal
set "MSBUILD=C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\amd64\MSBuild.exe"
set "FORTCRAFT_TOOLSET=v145"
if exist "%~dp0config.local.cmd" call "%~dp0config.local.cmd"
if defined FORTCRAFT_MSBUILD set "MSBUILD=%FORTCRAFT_MSBUILD%"
if not exist "%MSBUILD%" (echo Visual Studio's MSBuild not found. Run tools\setup_windows.bat first. & exit /b 1)
set SRC=%~dp0..\..\source-sdk-2013\src
tasklist /fi "imagename eq mod_tf_win64.exe" | find /i "mod_tf_win64.exe" >nul && (
  echo TF2 is still running. Close Minecraft, wait a few seconds, and try again.
  exit /b 1
)
for %%P in (game\client\client_win64_tf.vcxproj game\server\server_win64_tf.vcxproj) do (
  "%MSBUILD%" "%SRC%\%%P" /m /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=%FORTCRAFT_TOOLSET% /v:quiet /nologo /clp:ErrorsOnly || exit /b 1
)
echo TF2 mod built and installed.
