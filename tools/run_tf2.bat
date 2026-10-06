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
start "" /D "%SDK%" "%SDK%\mod_tf_win64.exe" -novid -windowed -w 1280 -h 720 -insecure -condebug %HIDE% -fortcraft_logs "%LOGS%" %EXTRA% +sv_lan 1 +engine_no_focus_sleep 0 +snd_mute_losefocus 0 +mat_queue_mode 0 +fps_max 144 +mp_waitingforplayers_time 0 +mp_timelimit 0 +mp_winlimit 0 +mp_maxrounds 0 +map fortcraft_flat
