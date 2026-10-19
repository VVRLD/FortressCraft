@echo off
rem Starts our TF2 mod (built from ..\..\source-sdk-2013) on our own empty map (fortcraft_flat, see tools\build_map.bat), offline.
rem   -insecure        VAC off (TF2's own option); +sv_lan 1: LAN only, no internet players
rem   +fps_max 144     cap TF2's frame rate until it learns Minecraft's (then it matches it)
rem   +snd_mute_losefocus 0  keep TF2's sound on while its window is hidden
rem   +mat_queue_mode 0  draw on the main thread (the GPU overlay copy talks to Direct3D directly)
rem   +mp_waitingforplayers_time 0 etc.: no "waiting for players" round reset (it respawned you after 30 s) and no round end
rem   -fortcraft_hidden  hide TF2's window; it closes by itself when Minecraft closes
rem Pass "visible" first to keep TF2's window on screen:  tools\run_tf2.bat visible
rem Extra switches go after it (or alone):
rem   -fortcraft_no_overlay   no weapon/HUD copy at all (to tell overlay cost from TF2's own)
rem   -fortcraft_cpu_overlay  use the old read-back copy instead of the GPU one
rem   -fortcraft_lowend       low-end mode: 720p, TF2's lowest graphics (PLAY-FORTCRAFT-LOW-END.cmd)
setlocal
set SDK=%~dp0..\..\source-sdk-2013\game
set LOGS=%~dp0..\logs
if not exist "%LOGS%" mkdir "%LOGS%"
set HIDE=-fortcraft_hidden
set EXTRA=%*
if /i "%~1"=="visible" (
  set HIDE=
  set EXTRA=%2 %3 %4
)
rem Python that setup found outside PATH (GitHub issue 18).
if exist "%~dp0config.local.cmd" call "%~dp0config.local.cmd"
if defined FORTCRAFT_PYTHON_DIR set "PATH=%FORTCRAFT_PYTHON_DIR%;%PATH%"
rem TF2's engine is Steam's Source SDK Base 2013 Multiplayer; without its 64-bit files TF2 stops
rem with "Failed to load the launcher DLL" (GitHub issue 8).
if defined FORTCRAFT_SDK_BASE_DIR if not exist "%FORTCRAFT_SDK_BASE_DIR%\bin\x64\launcher.dll" (
  echo Source SDK Base 2013 Multiplayer is missing bin\x64\launcher.dll. In Steam: right-click it,
  echo Properties, Betas: choose None; then Installed Files: Verify integrity of tool files.
  exit /b 1
)
rem The player's own TF2 graphics settings (motion blur and vertical sync always off).
set GFX=
echo.%* | find /i "-fortcraft_lowend" >nul && set GFX=--low
python "%~dp0tf2_graphics.py" %GFX% "%SDK%\mod_tf\cfg\fortcraft_graphics.cfg" || echo Couldn't copy your TF2 graphics settings; TF2 keeps its own.
rem TF2 runs until it closes. If Minecraft is still open then, TF2 crashed or quit by itself
rem (for example when the sound device changed, GitHub issue 19, or on a class change, issue 3):
rem start it again and it reconnects. At most 5 times, so a TF2 that can't start doesn't loop.
set RESTARTS=0
:launch
start "" /wait /D "%SDK%" "%SDK%\mod_tf_win64.exe" -novid -windowed -w 1280 -h 720 -insecure -condebug %HIDE% -fortcraft_logs "%LOGS%" %EXTRA% +sv_lan 1 +engine_no_focus_sleep 0 +snd_mute_losefocus 0 +mat_queue_mode 0 +fps_max 144 +mp_waitingforplayers_time 0 +mp_timelimit 0 +mp_winlimit 0 +mp_maxrounds 0 +exec fortcraft_graphics +map fortcraft_flat
python "%~dp0minecraft_running.py" || exit /b 0
if %RESTARTS% GEQ 5 (echo TF2 closed 5 times while Minecraft was open; not starting it again. See logs\tf2_*.log and mod_tf\console.log. & exit /b 1)
set /a RESTARTS+=1
echo TF2 closed while Minecraft is still open; starting TF2 again (%RESTARTS% of 5).
goto launch
