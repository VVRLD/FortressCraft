@echo off
rem Builds our TF2 mod's client.dll and server.dll (only what changed) and installs them into
rem ..\..\source-sdk-2013\game\mod_tf\bin\x64. TF2 must not be running.
setlocal
set MSBUILD=C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\amd64\MSBuild.exe
set SRC=%~dp0..\..\source-sdk-2013\src
tasklist /fi "imagename eq mod_tf_win64.exe" | find /i "mod_tf_win64.exe" >nul && (
  echo TF2 is still running. Close Minecraft, wait a few seconds, and try again.
  exit /b 1
)
for %%P in (game\client\client_win64_tf.vcxproj game\server\server_win64_tf.vcxproj) do (
  "%MSBUILD%" "%SRC%\%%P" /m /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v145 /v:quiet /nologo /clp:ErrorsOnly || exit /b 1
)
echo TF2 mod built and installed.
